package nova.nova_client.client;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import nova.nova_client.client.mods.SkyVfxMod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
//? if >=26.1 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
//?}

/**
 * Zeichnet den gewaehlten {@link SkyStyle} als Himmel - komplett im Shader (shaders/core/nova_sky.fsh): ein Wuerfel
 * um die Kamera, und fuer jeden Bildschirmpixel rechnet der Fragment-Shader aus der Blickrichtung die Farbe. So ist
 * der Himmel bei jeder Aufloesung scharf, die Animation laeuft mit voller Bildrate, die CPU hat nichts zu tun, und
 * jeder Himmel reicht rundherum - auch unter den Horizont (z.B. hoch in der Luft).
 * <p>
 * Die Vorschau im Menue zeichnet derselbe Shader in kleine Bilder ({@link #renderPreview}). Laesst sich der Shader
 * nicht bauen, bleibt der Vanilla-Himmel (Meldung im Log).
 */
public final class SkyVfx {
    private static final Logger LOGGER = LoggerFactory.getLogger("Nova Sky VFX");
    /** Halbe Kantenlaenge des Wuerfels um die Kamera (Bloecke, innerhalb der Sichtweite). */
    private static final float CUBE = 50.0F;
    private static final int CUBE_INDICES = 36;
    /** Senkrechtes Sichtfeld der Vorschau (Grad). */
    private static final float PREVIEW_FOV = 64.0F;

    /** Himmelszeit faengt nach so vielen Sekunden von vorn an (float bleibt genau genug fuer den Shader). */
    private static final double CLOCK_WRAP = 3600.0D;

    private static double clock;
    private static long lastClock;

    /** Nach einem Fehlschlag so lange warten, bevor neu gebaut wird (z.B. nach F3+T / Ressourcenpaket-Wechsel). */
    private static final long RETRY_MS = 5000L;

    private static RenderPipeline worldPipeline;
    private static RenderPipeline previewPipeline;
    /** null = noch nicht versucht, sonst ob die Shader gebaut werden konnten. */
    private static Boolean valid;
    private static long retryAt;
    /** Fehlschlag schon gemeldet (Log + Chat nur einmal, nicht bei jedem neuen Versuch). */
    private static boolean reported;
    private static GpuBuffer cube;

    //? if >=26.1 {
    // Schneller Weg (26.x): der Himmel wird erst nach den undurchsichtigen Bloecken gezeichnet, ganz hinten mit
    // Tiefentest - der teure Shader rechnet nur die Pixel, in denen man den Himmel sieht. Was sich nie aendert, wird
    // in eine Rundum-Textur gebacken (16 Bit): der Weltraum (Nebel, Milchstrasse), den Black Hole und Ringed Giant
    // teilen - einmal gebacken, auch beim Wechsel des Himmels.
    private static final int SPACE_WIDTH = 2048;
    private static final int SPACE_HEIGHT = 1024;
    private static RenderPipeline fastPipeline;
    private static RenderPipeline latePipeline;
    /** Backen in die 16-Bit-Textur; bake8, falls nur 8 Bit moeglich waren (die Pipeline muss das Format kennen). */
    private static RenderPipeline bakePipeline;
    private static RenderPipeline bake8Pipeline;
    private static boolean space16;
    private static boolean fastValid;
    private static TextureTarget space;
    /** nova_sky.fsh, Stil 4 = Weltraum in die Rundum-Textur backen. */
    private static final int BAKE_SPACE = 4;
    /** Weltraum ist in space gebacken. */
    private static boolean baked;
    /** Der Haken nach den Bloecken laeuft (SkyPassMixin) - sonst bleibt alles im Himmel-Pass. */
    private static boolean lateHookSeen;
    /** Himmel-Pass hat den Himmel an den Haken nach den Bloecken weitergegeben. */
    private static boolean latePending;
    //?}

    private SkyVfx() {
    }

    /**
     * Himmelszeit in Sekunden (eigene Uhr, unabhaengig von "Animationen aus" im Menue). Laeuft mit der eingestellten
     * Geschwindigkeit weiter - frueher war es Uhrzeit mal Geschwindigkeit, dann sprang die Animation bei jeder
     * Aenderung des Reglers um Minuten.
     */
    public static float time() {
        long now = Util.getMillis();
        if (lastClock == 0L) {
            clock = (now % 3_600_000L) / 1000.0D;
        } else {
            // nach langen Pausen (Spiel haengt, Fenster minimiert) nicht weit springen
            clock += Math.min(now - lastClock, 250L) / 1000.0D * SkyVfxMod.speed;
            clock %= CLOCK_WRAP;
        }
        lastClock = now;
        return (float) clock;
    }


    /** Farbe am Horizont (RGB) fuer den Nebel in der Ferne. */
    public static int horizonColor() {
        return SkyVfxMod.style.horizon;
    }

    // ---------------------------------------------------------------- Zeichnen

    /**
     * Himmel-Pass: true = gezeichnet, false = Shader nicht verfuegbar (dann bleibt der Vanilla-Himmel). Die eigenen
     * Bilder dieses Himmels kommen gleich mit drauf.
     */
    public static boolean render() {
        if (!ready()) {
            return false;
        }
        //? if >=26.1 {
        if (latePending) {
            // Der Haken nach den Bloecken hat den letzten Himmel nicht abgeholt -> wieder im Himmel-Pass zeichnen
            latePending = false;
            lateHookSeen = false;
        }
        // Ohne eigene Bilder (die liegen vor dem Himmel und haben keinen Tiefentest) erst nach den Bloecken zeichnen
        if (lateHookSeen && fastReady() && SkyImages.placed(SkyVfxMod.style).isEmpty()) {
            latePending = true;
            return true;
        }
        //?}
        renderShader(false);
        renderImages(SkyVfxMod.style);
        return true;
    }

    /** Haupt-Pass direkt nach den undurchsichtigen Bloecken (SkyPassMixin), jeden Frame. */
    public static void renderLate() {
        //? if >=26.1 {
        lateHookSeen = true;
        if (latePending) {
            latePending = false;
            renderShader(true);
        }
        //?}
    }

    private static void renderShader(boolean late) {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        //? if >=26.1 {
        if (fastReady()) {
            // w: Tiefe "ganz hinten" fuer nova_sky_late.vsh
            Vector4f fastParams = parameters(SkyVfxMod.style, time());
            fastParams.w = late ? farZ() : 0.0F;
            GpuBufferSlice slice = RenderSystem.getDynamicUniforms().writeTransform(RenderCompat.modelView(), fastParams,
                    new Vector3f(), new Matrix4f());
            try (RenderPass pass = RenderCompat.pass(() -> "Nova Sky VFX", target.getColorTextureView(), target.getDepthTextureView())) {
                RenderCompat.setPipeline(pass, late ? latePipeline : fastPipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", slice);
                RenderCompat.texture(pass, "Sampler0", space.getColorTextureView(), spaceSampler());
                RenderCompat.vertices(pass, cube);
                RenderCompat.drawQuads(pass, CUBE_INDICES);
            }
            return;
        }
        //?}
        GpuBufferSlice params = RenderSystem.getDynamicUniforms().writeTransform(RenderCompat.modelView(),
                parameters(SkyVfxMod.style, time()), new Vector3f(), new Matrix4f());
        try (RenderPass pass = RenderCompat.pass(() -> "Nova Sky VFX", target.getColorTextureView(), target.getDepthTextureView())) {
            RenderCompat.setPipeline(pass, worldPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", params);
            RenderCompat.vertices(pass, cube);
            RenderCompat.drawQuads(pass, CUBE_INDICES);
        }
    }

    /**
     * Vorschau eines Himmels in target, Blick in die Richtung aus {@link SkyStyle#previewYaw}/{@link
     * SkyStyle#previewPitch}. flip: Bild oben/unten getauscht zeichnen (je nachdem, wie es spaeter gezeigt wird).
     */
    public static boolean renderPreview(SkyStyle style, RenderTarget target, float time, boolean flip) {
        return renderPreview(style, target, time, flip, style.previewYaw, style.previewPitch, PREVIEW_FOV);
    }

    /** Vorschau mit frei gewaehlter Blickrichtung (Grad, wie {@link SkyStyle#previewYaw}) und Sichtfeld (senkrecht). */
    public static boolean renderPreview(SkyStyle style, RenderTarget target, float time, boolean flip, float yawDegrees,
                                        float pitchDegrees, float fovDegrees) {
        if (!ready()) {
            return false;
        }
        float tanY = (float) Math.tan(Math.toRadians(fovDegrees * 0.5F));
        float tanX = tanY * target.width / (float) Math.max(1, target.height);
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        Vector3f forward = new Vector3f((float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch),
                (float) (Math.cos(yaw) * Math.cos(pitch)));
        Vector3f right = new Vector3f(forward).cross(0.0F, 1.0F, 0.0F).normalize();
        Vector3f up = new Vector3f(right).cross(forward).normalize();
        Matrix4f camera = new Matrix4f()
                .setColumn(0, new Vector4f(right, 0.0F))
                .setColumn(1, new Vector4f(up, 0.0F))
                .setColumn(2, new Vector4f(forward, 0.0F))
                .setColumn(3, new Vector4f(tanX, tanY, flip ? 1.0F : 0.0F, 0.0F));
        GpuBufferSlice params = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),
                parameters(style, time), new Vector3f(), camera);
        try (RenderPass pass = RenderCompat.pass(() -> "Nova Sky preview", target.getColorTextureView(), null)) {
            RenderCompat.setPipeline(pass, previewPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", params);
            RenderCompat.drawFullscreen(pass);
        }
        return true;
    }

    /** Shader-Parameter: Zeit, Himmel, Helligkeit (siehe nova_sky.fsh). */
    private static Vector4f parameters(SkyStyle style, float time) {
        return new Vector4f(time, style.ordinal(), SkyVfxMod.brightness(style), 0.0F);
    }

    // ---------------------------------------------------------------- Shader & Wuerfel

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("nova_client", path);
    }

    /**
     * Shader bereit? Ein Fehlschlag ist nicht endgueltig: alle paar Sekunden wird erneut gebaut (kostet fast nichts,
     * die Grafikkarte merkt sich das Ergebnis bis zum naechsten Ressourcen-Neuladen).
     */
    private static boolean ready() {
        if (valid == null || !valid && Util.getMillis() >= retryAt) {
            boolean ok = build();
            if (!ok) {
                retryAt = Util.getMillis() + RETRY_MS;
                if (!reported) {
                    reported = true;
                    NovaNotify.chat("Sky VFX", "§cYour graphics driver could not build the sky shader - keeping the"
                            + " normal sky. Details are in logs/latest.log (search for \"nova_sky\").");
                }
            } else if (reported) {
                LOGGER.info("Sky VFX shaders are working now");
                reported = false;
            }
            valid = ok;
        }
        return valid;
    }

    private static boolean build() {
        try {
            if (worldPipeline == null) {
                worldPipeline = RenderCompat.pipeline(id("pipeline/nova_sky"), id("core/nova_sky"), id("core/nova_sky"),
                        DefaultVertexFormat.POSITION, true, null);
                previewPipeline = RenderCompat.pipeline(id("pipeline/nova_sky_preview"), id("core/nova_sky_preview"),
                        id("core/nova_sky"), null, false, null);
            }
            boolean ok = RenderCompat.compiles(worldPipeline)
                    && RenderCompat.compiles(previewPipeline);
            //? if >=26.1 {
            if (ok) {
                buildFast();
            }
            //?}
            if (ok && cube == null) {
                cube = buildCube();
            } else if (!ok && !reported) {
                // Den genauen Compiler-Fehler schreibt Minecraft direkt davor ins Log ("Couldn't compile ... nova_sky")
                LOGGER.error("Sky VFX shaders could not be compiled - keeping the vanilla sky");
            }
            return ok;
        } catch (RuntimeException e) {
            if (!reported) {
                LOGGER.error("Sky VFX shaders could not be created - keeping the vanilla sky", e);
            }
            return false;
        }
    }

    //? if >=26.1 {
    /** Pipelines fuer den schnellen Weg. Klappt das nicht, wird der Himmel wie bisher gezeichnet (Himmel-Pass). */
    private static void buildFast() {
        try {
            if (fastPipeline == null) {
                fastPipeline = RenderCompat.builder(id("pipeline/nova_sky_fast"), id("core/nova_sky"), id("core/nova_sky"),
                        DefaultVertexFormat.POSITION, true, null, "Sampler0").withShaderDefine("SPACE_BAKED").build();
                latePipeline = RenderCompat.glowDepth(RenderCompat.builder(id("pipeline/nova_sky_late"),
                        id("core/nova_sky_late"), id("core/nova_sky"), DefaultVertexFormat.POSITION, true, null,
                        "Sampler0").withShaderDefine("SPACE_BAKED"), true).build();
                bakePipeline = RenderCompat.builder(id("pipeline/nova_sky_bake"), id("core/nova_sky_preview"),
                        id("core/nova_sky"), null, false, null)
                        .withColorTargetState(new ColorTargetState(java.util.Optional.empty(), GpuFormat.RGBA16_UNORM,
                                ColorTargetState.WRITE_ALL)).build();
                bake8Pipeline = RenderCompat.pipeline(id("pipeline/nova_sky_bake8"), id("core/nova_sky_preview"),
                        id("core/nova_sky"), null, false, null);
            }
            fastValid = RenderCompat.compiles(fastPipeline)
                    && RenderCompat.compiles(latePipeline)
                    && RenderCompat.compiles(bakePipeline)
                    && RenderCompat.compiles(bake8Pipeline);
            if (!fastValid) {
                LOGGER.warn("Sky VFX fast path could not be compiled - drawing the sky the old way");
            }
        } catch (RuntimeException e) {
            fastValid = false;
            LOGGER.warn("Sky VFX fast path could not be created - drawing the sky the old way", e);
        }
    }

    /** 16 Bit je Farbe (keine Stufen im Himmelsverlauf); klappt das nicht, 8 Bit. */
    private static TextureTarget createSpace() {
        try {
            TextureTarget target = RenderCompat.target16("Nova Sky space", SPACE_WIDTH, SPACE_HEIGHT);
            space16 = true;
            return target;
        } catch (RuntimeException e) {
            LOGGER.warn("Sky VFX: no 16 bit texture, using 8 bit", e);
            space16 = false;
            return RenderCompat.target("Nova Sky space", SPACE_WIDTH, SPACE_HEIGHT, false);
        }
    }

    /** Schneller Weg bereit? Backt beim ersten Mal die Rundum-Textur. */
    private static boolean fastReady() {
        if (fastValid && (space == null || !baked)) {
            try {
                if (space == null) {
                    space = createSpace();
                }
                TextureTarget target = space;
                // zw = Texturgroesse
                GpuBufferSlice params = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),
                        new Vector4f(0.0F, BAKE_SPACE, SPACE_WIDTH, SPACE_HEIGHT), new Vector3f(), new Matrix4f());
                try (RenderPass pass = RenderCompat.pass(() -> "Nova Sky bake", target.getColorTextureView(), null)) {
                    RenderCompat.setPipeline(pass, space16 ? bakePipeline : bake8Pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", params);
                    RenderCompat.drawFullscreen(pass);
                }
                baked = true;
            } catch (RuntimeException e) {
                fastValid = false;
                LOGGER.warn("Sky VFX could not bake its texture - drawing the sky the old way", e);
            }
        }
        return fastValid && space != null && baked;
    }

    /** Breite: rundum wiederholen (Azimut), Hoehe: am Rand bleiben (Zenit/Nadir). */
    private static GpuSampler spaceSampler() {
        return RenderSystem.getSamplerCache().getSampler(AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
    }

    /** z (vor der Division durch w) knapp vor "ganz hinten": dort ist die Tiefe nach dem Leeren 0. */
    private static float farZ() {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1.0e-6F : -1.0F + 2.0e-6F;
    }
    //?}

    /** Wuerfel um die Kamera: sechs Seiten, je vier Ecken reihum. */
    private static GpuBuffer buildCube() {
        float s = CUBE;
        float[][] faces = {
                {s, -s, -s, s, -s, s, s, s, s, s, s, -s},
                {-s, -s, -s, -s, s, -s, -s, s, s, -s, -s, s},
                {-s, s, -s, s, s, -s, s, s, s, -s, s, s},
                {-s, -s, -s, -s, -s, s, s, -s, s, s, -s, -s},
                {-s, -s, s, -s, s, s, s, s, s, s, -s, s},
                {-s, -s, -s, s, -s, -s, s, s, -s, -s, s, -s}};
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(24 * DefaultVertexFormat.POSITION.getVertexSize())) {
            BufferBuilder builder = RenderCompat.quads(bytes, DefaultVertexFormat.POSITION);
            for (float[] face : faces) {
                for (int i = 0; i < 4; i++) {
                    builder.addVertex(face[i * 3], face[i * 3 + 1], face[i * 3 + 2]);
                }
            }
            try (MeshData data = builder.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "Nova Sky VFX cube",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data.vertexBuffer());
            }
        }
    }

    // ---------------------------------------------------------------- Eigene Bilder (SkyImages)

    /** Abstand der Bilder (innerhalb des Himmels-Wuerfels, damit sie davor liegen). */
    private static final float PICTURE_DISTANCE = 45.0F;

    private static RenderPipeline picturePipeline;
    private static Boolean imagesValid;
    private static GpuBuffer pictureQuad;

    /** Alle Bilder, die in diesen Himmel gesetzt wurden - nach dem Himmel, in der Reihenfolge der Liste. */
    private static void renderImages(SkyStyle style) {
        List<SkyImages.Placement> list = SkyImages.placed(style);
        if (list.isEmpty() || !imagesReady()) {
            return;
        }
        for (SkyImages.Placement placement : list) {
            DynamicTexture texture = SkyImages.texture(placement.file);
            if (texture == null) {
                continue;
            }
            float halfWidth = PICTURE_DISTANCE * (float) Math.tan(Math.toRadians(placement.size * 0.5F));
            float halfHeight = halfWidth / Math.max(0.05F, SkyImages.aspect(placement.file));
            // Bild schaut von +Z zur Kamera; drehen in seine Richtung (Yaw 0 = +Z, 90 = +X; Pitch nach oben)
            Matrix4f pose = RenderCompat.modelView()
                    .rotateY((float) Math.toRadians(placement.yaw))
                    .rotateX((float) Math.toRadians(-placement.pitch))
                    .translate(0.0F, 0.0F, PICTURE_DISTANCE)
                    .scale(halfWidth, halfHeight, 1.0F);
            drawTextured(texture, pose);
        }
    }

    private static void drawTextured(DynamicTexture texture, Matrix4f pose) {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(pose,
                new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());
        try (RenderPass pass = RenderCompat.pass(() -> "Nova Sky image", target.getColorTextureView(), target.getDepthTextureView())) {
            RenderCompat.setPipeline(pass, picturePipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            RenderCompat.texture(pass, "Sampler0", texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            RenderCompat.vertices(pass, pictureQuad);
            RenderCompat.drawQuads(pass, 6);
        }
    }

    private static boolean imagesReady() {
        if (imagesValid == null) {
            try {
                // Vanilla-Shader position_tex_color (Textur * Farbe), durchsichtig, ohne Tiefe - wie der Himmel
                picturePipeline = RenderCompat.pipeline(id("pipeline/nova_sky_picture"),
                        Identifier.withDefaultNamespace("core/position_tex_color"),
                        Identifier.withDefaultNamespace("core/position_tex_color"),
                        DefaultVertexFormat.POSITION_TEX_COLOR, true, BlendFunction.TRANSLUCENT, "Sampler0");
                imagesValid = RenderCompat.compiles(picturePipeline);
                if (imagesValid) {
                    pictureQuad = buildPictureQuad();
                } else {
                    LOGGER.error("Sky VFX image shader could not be compiled");
                }
            } catch (RuntimeException e) {
                LOGGER.error("Sky VFX image shader could not be created", e);
                imagesValid = false;
            }
        }
        return imagesValid;
    }

    /** Einheits-Quadrat (-1..1); +x liegt von der Kamera aus links, daher u = 0 bei x = +1 (nicht gespiegelt). */
    private static GpuBuffer buildPictureQuad() {
        try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(4 * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize())) {
            BufferBuilder builder = RenderCompat.quads(bytes, DefaultVertexFormat.POSITION_TEX_COLOR);
            builder.addVertex(1.0F, 1.0F, 0.0F).setUv(0.0F, 0.0F).setColor(0xFFFFFFFF);
            builder.addVertex(-1.0F, 1.0F, 0.0F).setUv(1.0F, 0.0F).setColor(0xFFFFFFFF);
            builder.addVertex(-1.0F, -1.0F, 0.0F).setUv(1.0F, 1.0F).setColor(0xFFFFFFFF);
            builder.addVertex(1.0F, -1.0F, 0.0F).setUv(0.0F, 1.0F).setColor(0xFFFFFFFF);
            try (MeshData data = builder.buildOrThrow()) {
                return RenderSystem.getDevice().createBuffer(() -> "Nova Sky picture quad",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data.vertexBuffer());
            }
        }
    }

}
