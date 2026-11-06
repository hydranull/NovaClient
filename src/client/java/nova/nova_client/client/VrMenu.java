package nova.nova_client.client;

//? if >=26.1 {
import com.mojang.renderpearl.api.GpuFormat;
//?}
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import nova.nova_client.client.mods.VrMenuMod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * VR Menu: Nova-Menues schweben als Glasfenster im Raum, wie bei einer AR-/VR-Brille (Vision-Pro-Stil).
 *
 * Ablauf pro Frame:
 * 1. Beim Aufbau des Bildschirms ({@link #beginFrame}) wird nach dem HUD eine Trennstelle gesetzt; Vanilla-Unschaerfe
 *    und Menue-Abdunklung fallen weg, damit die Welt scharf bleibt (wie das Passthrough-Bild einer Brille).
 * 2. Alles nach der Trennstelle (das Menue) zeichnet der GuiRenderer in ein eigenes Bild ({@link #contentTarget}).
 * 3. Am Ende ({@link #composite}) wird die Welt verkleinert und weichgezeichnet (Milchglas) und das Menue als
 *    perspektivisch gekipptes, schwebendes Glasfenster mit Randlicht, Glanz, Lichtbrechung und Schatten eingesetzt.
 *
 * Damit Klicks stimmen, rechnet {@link #mapX}/{@link #mapY} die Mausposition durch dieselbe 3D-Ebene zurueck.
 * Unter dem Fenster liegt eine Fensterleiste: ziehen = verschieben, Rechtsklick = zuruecksetzen.
 */
public final class VrMenu {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/VrMenu");

    /** Sichtfeld der virtuellen Kamera (senkrecht). Kleiner = schwaechere Perspektive (Fenster wirkt weiter weg). */
    private static final float FOV = (float) Math.toRadians(50.0D);
    /** Raster des Fensters: fein genug, dass die perspektivische Verzerrung nicht sichtbar ist. */
    private static final int GRID_X = 48;
    private static final int GRID_Y = 32;
    /** Glasrand um das Menue (Anteil der Hoehe), damit nichts an den runden Ecken abgeschnitten wird. */
    private static final float PAD = 0.025F;
    /** Rand um das Menue fuer Glasrand, Schatten, Lichtschein und Leiste (Anteil der Hoehe). */
    private static final float MARGIN_SIDE = 0.12F;
    private static final float MARGIN_TOP = 0.12F;
    private static final float MARGIN_BOTTOM = 0.18F;
    private static final float OPEN_SECONDS = 0.45F;
    private static final float CLOSE_SECONDS = 0.22F;
    /** Unschaerfe der Welt hinter dem Glas wird in 1/DOWNSAMPLE der Aufloesung berechnet. */
    private static final int DOWNSAMPLE = 4;

    private static final int VERTEX_COUNT = GRID_X * GRID_Y * 4;
    private static final int INDEX_COUNT = GRID_X * GRID_Y * 6;

    // ---------------------------------------------------------------- GPU-Ressourcen
    private static RenderPipeline panelPipeline;
    private static RenderPipeline blurPipeline;
    /** null = noch nicht geprueft, sonst ob beide Shader kompiliert werden konnten. */
    private static Boolean pipelinesValid;
    private static boolean failed;
    private static TextureTarget content;
    private static TextureTarget blurA;
    private static TextureTarget blurB;
    private static GpuBuffer vertices;
    private static ByteBufferBuilder bytes;
    private static RenderCompat.Ortho projection;

    // ---------------------------------------------------------------- Zustand
    /** In diesem Frame wird ein Menue ins Glasfenster gezeichnet (gesetzt beim Aufbau, verbraucht in composite). */
    private static boolean frameActive;
    /** Das Inhaltsbild enthaelt ein Menue (auch nach dem Schliessen, fuer die Ausblende-Animation). */
    private static boolean hasContent;
    /** 0 = unsichtbar, 1 = ganz da. */
    private static float appear;
    private static boolean opening = true;
    private static long lastNanos;

    // Aktuelle Lage des Fensters (Kamera im Ursprung, x rechts, y unten, z in den Bildschirm)
    private static boolean valid;
    private static int frameWidth;
    private static int frameHeight;
    private static float focal;
    private static final Vector3f CENTER = new Vector3f();
    private static final Vector3f AXIS_X = new Vector3f();
    private static final Vector3f AXIS_Y = new Vector3f();
    private static final Vector3f NORMAL = new Vector3f();
    private static float yaw = Float.NaN;
    private static float pitch;
    private static float barHover;

    // Fensterleiste ziehen
    private static boolean dragging;
    private static float dragStartX;
    private static float dragStartY;
    private static float dragOffsetX;
    private static float dragOffsetY;

    private static final float[] LOCAL = new float[2];

    private VrMenu() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("nova_client", path);
    }

    // ================================================================ Wann?

    /** Wird dieser Bildschirm als Glasfenster gezeigt? Nur Nova-Menues in einer Welt, nicht der HUD-Editor. */
    public static boolean eligible(Screen screen) {
        if (!VrMenuMod.isEnabled() || failed || screen == null || screen instanceof HudEditorScreen) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        return client.level != null && NovaScale.isNovaClass(screen.getClass());
    }

    /**
     * Beim Aufbau des Bildschirms (Screen-Mixin, direkt nach der ersten Ebene): true = Trennstelle setzen,
     * das Menue landet dann im Glasfenster.
     */
    public static boolean beginFrame(Screen screen) {
        if (screen != Minecraft.getInstance().gui.screen() || !eligible(screen) || !pipelinesReady()) {
            return false;
        }
        frameActive = true;
        return true;
    }

    /** Wird dieser Bildschirm gerade ins Glasfenster gezeichnet? (dann keine Vanilla-Unschaerfe/-Abdunklung) */
    public static boolean drawsScreen(Screen screen) {
        return frameActive && screen == Minecraft.getInstance().gui.screen();
    }

    /** Vanilla-Unschaerfe der Welt auslassen (die Welt bleibt scharf, nur hinter dem Glas wird weichgezeichnet). */
    public static boolean skipVanillaBlur() {
        return frameActive;
    }

    /** Menue-Abdunklung im Fenster schwaecher, damit das Milchglas durchscheint. */
    public static float backgroundFactor() {
        return frameActive ? 0.45F : 1.0F;
    }

    // ================================================================ Rendering

    /** Ziel fuer alles nach der Trennstelle: im VR-Modus das eigene Inhaltsbild statt des Hauptbilds. */
    public static RenderTarget contentTarget(RenderTarget main) {
        if (!frameActive) {
            return main;
        }
        try {
            ensureTargets(main.width, main.height);
            RenderCompat.clearTransparent(content.getColorTexture(), content.getDepthTexture());
            hasContent = true;
            return content;
        } catch (RuntimeException e) {
            fail(e);
            frameActive = false;
            return main;
        }
    }

    /** Ende des GUI-Renderings: Glasfenster ins Hauptbild setzen (auch waehrend der Ausblende-Animation). */
    public static void composite() {
        boolean live = frameActive;
        frameActive = false;

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Math.min(0.1F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;

        Minecraft client = Minecraft.getInstance();
        if (live) {
            opening = true;
            appear = UiSettings.animations ? Math.min(1.0F, appear + dt / OPEN_SECONDS) : 1.0F;
        } else if (hasContent && appear > 0.0F && UiSettings.animations && VrMenuMod.isEnabled() && !failed
                && client.level != null && client.gui.screen() == null) {
            // Menue geschlossen: Fenster gleitet zurueck und blendet aus
            opening = false;
            appear = Math.max(0.0F, appear - dt / CLOSE_SECONDS);
        } else {
            appear = 0.0F;
        }
        if (appear <= 0.0F || !hasContent) {
            reset();
            return;
        }

        try {
            RenderTarget main = client.gameRenderer.mainRenderTarget();
            ensureTargets(main.width, main.height);
            update(client, main, dt);
            blurBackdrop(main);
            drawPanel(main);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static void reset() {
        appear = 0.0F;
        valid = false;
        hasContent = false;
        opening = true;
        yaw = Float.NaN;
        if (dragging) {
            dragging = false;
            ModConfig.save();
        }
    }

    private static void fail(Exception e) {
        if (!failed) {
            LOGGER.error("VR Menu disabled after a render error", e);
        }
        failed = true;
        reset();
    }

    // ---------------------------------------------------------------- Lage im Raum

    private static float smooth(float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        return t * t * (3.0F - 2.0F * t);
    }

    private static void update(Minecraft client, RenderTarget main, float dt) {
        frameWidth = main.width;
        frameHeight = main.height;
        float w = frameWidth;
        float h = frameHeight;
        focal = h / (2.0F * (float) Math.tan(FOV / 2.0F));
        float spawn = opening ? NovaGui.easeOutCubic(appear) : smooth(appear);

        // Maus in Bildpixeln
        Window window = client.getWindow();
        float mouseX = (float) (client.mouseHandler.xpos() * w / Math.max(1, window.getScreenWidth()));
        float mouseY = (float) (client.mouseHandler.ypos() * h / Math.max(1, window.getScreenHeight()));
        if (dragging) {
            VrMenuMod.setOffset(dragOffsetX + (mouseX - dragStartX) / w, dragOffsetY + (mouseY - dragStartY) / h);
        }

        // Head-Tracking: die Seite unter der Maus weicht leicht zurueck, als wuerde man das Fenster antippen
        float nx = Math.max(-1.0F, Math.min(1.0F, mouseX / w * 2.0F - 1.0F));
        float ny = Math.max(-1.0F, Math.min(1.0F, mouseY / h * 2.0F - 1.0F));
        float track = VrMenuMod.headTracking;
        float targetYaw = -VrMenuMod.tilt - track * 7.0F * nx;
        float targetPitch = track * 5.0F * ny;
        if (Float.isNaN(yaw)) {
            yaw = targetYaw;
            pitch = targetPitch;
        } else {
            float k = 1.0F - (float) Math.exp(-dt * 6.0F);
            yaw += (targetYaw - yaw) * k;
            pitch += (targetPitch - pitch) * k;
        }

        // Schweben: langsames Auf und Ab, leichtes Wiegen (Perioden ohne gemeinsamen Takt -> wirkt natuerlich)
        float time = (Util.getMillis() % 3_600_000L) / 1000.0F;
        float bob = 0.0F;
        float sway = 0.0F;
        float roll = 0.0F;
        if (VrMenuMod.floating) {
            bob = (float) Math.sin(time * Math.PI * 2.0D / 7.0D) * h * 0.006F;
            sway = (float) Math.sin(time * Math.PI * 2.0D / 11.0D) * 0.35F;
            roll = (float) Math.sin(time * Math.PI * 2.0D / 9.3D) * 0.22F;
        }

        // Beim Oeffnen kommt das Fenster von etwas weiter hinten/unten nach vorne
        float depth = focal / VrMenuMod.size * (1.0F + 0.12F * (1.0F - spawn));
        float toWorld = depth / focal;
        CENTER.set(VrMenuMod.offsetX * w * toWorld,
                (VrMenuMod.offsetY * h + bob + (1.0F - spawn) * h * 0.025F) * toWorld,
                depth);
        Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(yaw + sway))
                .rotateX((float) Math.toRadians(pitch))
                .rotateZ((float) Math.toRadians(roll));
        rotation.transform(AXIS_X.set(1.0F, 0.0F, 0.0F));
        rotation.transform(AXIS_Y.set(0.0F, 1.0F, 0.0F));
        AXIS_X.cross(AXIS_Y, NORMAL);
        valid = true;

        boolean hover = VrMenuMod.windowBar && (dragging || barHit(client.mouseHandler.xpos(), client.mouseHandler.ypos()));
        barHover = NovaGui.approach(barHover, hover ? 1.0F : 0.0F, 12.0F, dt);
    }

    /** Punkt auf dem Fenster (Bildpixel, Mitte = 0) -> Bildschirmpixel. */
    private static void project(float lx, float ly, float[] out) {
        float x = CENTER.x + AXIS_X.x * lx + AXIS_Y.x * ly;
        float y = CENTER.y + AXIS_X.y * lx + AXIS_Y.y * ly;
        float z = Math.max(1.0F, CENTER.z + AXIS_X.z * lx + AXIS_Y.z * ly);
        out[0] = frameWidth * 0.5F + focal * x / z;
        out[1] = frameHeight * 0.5F + focal * y / z;
    }

    /** Fensterkoordinaten (Mausposition in Fensterpixeln) -> Punkt auf dem Fenster (Bildpixel, Mitte = 0). */
    private static boolean toLocal(double rawX, double rawY, float[] out) {
        if (!valid) {
            return false;
        }
        Window window = Minecraft.getInstance().getWindow();
        float sx = (float) (rawX * frameWidth / Math.max(1, window.getScreenWidth()));
        float sy = (float) (rawY * frameHeight / Math.max(1, window.getScreenHeight()));
        float dx = (sx - frameWidth * 0.5F) / focal;
        float dy = (sy - frameHeight * 0.5F) / focal;
        float denom = dx * NORMAL.x + dy * NORMAL.y + NORMAL.z;
        if (Math.abs(denom) < 1.0E-5F) {
            return false;
        }
        float t = CENTER.dot(NORMAL) / denom;
        if (t <= 0.0F) {
            return false;
        }
        float hx = dx * t - CENTER.x;
        float hy = dy * t - CENTER.y;
        float hz = t - CENTER.z;
        out[0] = hx * AXIS_X.x + hy * AXIS_X.y + hz * AXIS_X.z;
        out[1] = hx * AXIS_Y.x + hy * AXIS_Y.y + hz * AXIS_Y.z;
        return true;
    }

    // ================================================================ Maus

    /** Soll die Mausposition durch das Glasfenster umgerechnet werden? */
    public static boolean mapsMouse() {
        return valid && appear > 0.0F && eligible(Minecraft.getInstance().gui.screen());
    }

    public static double mapX(Window window, double rawX, double rawY) {
        if (dragging || !toLocal(rawX, rawY, LOCAL)) {
            return -10000.0D;
        }
        return (LOCAL[0] + frameWidth * 0.5F) * window.getGuiScaledWidth() / (double) frameWidth;
    }

    public static double mapY(Window window, double rawX, double rawY) {
        if (dragging || !toLocal(rawX, rawY, LOCAL)) {
            return -10000.0D;
        }
        return (LOCAL[1] + frameHeight * 0.5F) * window.getGuiScaledHeight() / (double) frameHeight;
    }

    private static float barWidth() {
        return Math.max(60.0F, frameWidth * 0.075F);
    }

    private static float barHeight() {
        return Math.max(6.0F, frameHeight * 0.0095F);
    }

    /** Abstand Glaskante -> Leiste (im Shader gleich berechnet). */
    private static float barGap() {
        return frameHeight * 0.022F;
    }

    private static float pad() {
        return frameHeight * PAD;
    }

    private static boolean barHit(double rawX, double rawY) {
        if (!toLocal(rawX, rawY, LOCAL)) {
            return false;
        }
        float top = frameHeight * 0.5F + pad() + barGap();
        float pad = barHeight() * 1.5F;
        return Math.abs(LOCAL[0]) <= barWidth() * 0.65F + pad && LOCAL[1] >= top - pad && LOCAL[1] <= top + barHeight() + pad;
    }

    /** Maustaste (MouseHandler-Mixin): true = von der Fensterleiste verbraucht. action: 1 = gedrueckt, 0 = losgelassen. */
    public static boolean onButton(int button, int action, double rawX, double rawY) {
        if (dragging) {
            if (action == 0 && button == 0) {
                dragging = false;
                ModConfig.save();
            }
            return true;
        }
        if (action != 1 || !VrMenuMod.windowBar || !mapsMouse() || !barHit(rawX, rawY)) {
            return false;
        }
        Window window = Minecraft.getInstance().getWindow();
        if (button == 0) {
            dragging = true;
            dragStartX = (float) (rawX * frameWidth / Math.max(1, window.getScreenWidth()));
            dragStartY = (float) (rawY * frameHeight / Math.max(1, window.getScreenHeight()));
            dragOffsetX = VrMenuMod.offsetX;
            dragOffsetY = VrMenuMod.offsetY;
            return true;
        }
        if (button == 1) {
            VrMenuMod.setOffset(0.0F, 0.0F);
            ModConfig.save();
            NovaGui.clickSound();
            return true;
        }
        return false;
    }

    // ================================================================ GPU

    private static boolean pipelinesReady() {
        if (pipelinesValid == null) {
            try {
                panelPipeline = RenderCompat.pipeline(id("pipeline/vr_panel"), id("core/vr_panel"), id("core/vr_panel"),
                        DefaultVertexFormat.POSITION_TEX_COLOR, true, BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA,
                        "Sampler0", "Sampler1");
                blurPipeline = RenderCompat.pipeline(id("pipeline/vr_blur"), Identifier.withDefaultNamespace("core/screenquad"),
                        id("core/vr_blur"), null, false, null, "InSampler");
                pipelinesValid = RenderCompat.compiles(panelPipeline)
                        && RenderCompat.compiles(blurPipeline);
                if (!pipelinesValid) {
                    LOGGER.error("VR Menu shaders could not be compiled - menus stay flat");
                }
            } catch (RuntimeException e) {
                LOGGER.error("VR Menu shaders could not be created - menus stay flat", e);
                pipelinesValid = false;
            }
        }
        return pipelinesValid;
    }

    private static void ensureTargets(int width, int height) {
        if (content == null) {
            content = RenderCompat.target("Nova VR menu", width, height, true);
        } else if (content.width != width || content.height != height) {
            content.resize(width, height);
        }
        int bw = Math.max(1, width / DOWNSAMPLE);
        int bh = Math.max(1, height / DOWNSAMPLE);
        if (blurA == null) {
            blurA = RenderCompat.target("Nova VR blur A", bw, bh, false);
            blurB = RenderCompat.target("Nova VR blur B", bw, bh, false);
        } else if (blurA.width != bw || blurA.height != bh) {
            blurA.resize(bw, bh);
            blurB.resize(bw, bh);
        }
        if (vertices == null) {
            int size = VERTEX_COUNT * DefaultVertexFormat.POSITION_TEX_COLOR.getVertexSize();
            vertices = RenderSystem.getDevice().createBuffer(() -> "Nova VR panel", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
            bytes = new ByteBufferBuilder(size);
            projection = new RenderCompat.Ortho("Nova VR panel");
        }
    }

    private static GpuSampler linear() {
        return RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, false);
    }

    // ---------------------------------------------------------------- Milchglas

    /** Welt (inkl. HUD) verkleinern und zweimal weichzeichnen: der Hintergrund hinter dem Glas. */
    private static void blurBackdrop(RenderTarget main) {
        blurPass(main.getColorTextureView(), blurA, 0.0F, 0.0F, 0.0F, 1.0F);
        blurPass(blurA.getColorTextureView(), blurB, 1.0F, 0.0F, 1.0F, 1.0F);
        blurPass(blurB.getColorTextureView(), blurA, 0.0F, 1.0F, 1.0F, 1.0F);
        blurPass(blurA.getColorTextureView(), blurB, 1.0F, 0.0F, 1.0F, 2.0F);
        blurPass(blurB.getColorTextureView(), blurA, 0.0F, 1.0F, 1.0F, 2.0F);
    }

    /** mode 0 = verkleinern, 1 = Gauss in Richtung (dirX, dirY) mit Schrittweite step. */
    private static void blurPass(GpuTextureView input, RenderTarget output, float dirX, float dirY, float mode, float step) {
        GpuBufferSlice params = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),
                new Vector4f(dirX, dirY, mode, step), new Vector3f(), new Matrix4f());
        try (RenderPass pass = RenderCompat.pass(() -> "Nova VR blur", output.getColorTextureView(), null)) {
            RenderCompat.setPipeline(pass, blurPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", params);
            RenderCompat.texture(pass, "InSampler", input, linear());
            RenderCompat.drawFullscreen(pass);
        }
    }

    // ---------------------------------------------------------------- Glasfenster

    private static void drawPanel(RenderTarget main) {
        float w = frameWidth;
        float h = frameHeight;
        float x0 = -w * 0.5F - h * MARGIN_SIDE;
        float x1 = w * 0.5F + h * MARGIN_SIDE;
        float y0 = -h * 0.5F - h * MARGIN_TOP;
        float y1 = h * 0.5F + h * MARGIN_BOTTOM;

        // Raster einmal projizieren, dann als Vierecke ausgeben
        float[] sx = new float[(GRID_X + 1) * (GRID_Y + 1)];
        float[] sy = new float[sx.length];
        float[] u = new float[GRID_X + 1];
        float[] v = new float[GRID_Y + 1];
        float[] point = new float[2];
        for (int i = 0; i <= GRID_X; i++) {
            float lx = x0 + (x1 - x0) * i / GRID_X;
            u[i] = lx / w + 0.5F;
            for (int j = 0; j <= GRID_Y; j++) {
                float ly = y0 + (y1 - y0) * j / GRID_Y;
                if (i == 0) {
                    v[j] = ly / h + 0.5F;
                }
                project(lx, ly, point);
                sx[j * (GRID_X + 1) + i] = point[0];
                sy[j * (GRID_X + 1) + i] = point[1];
            }
        }
        BufferBuilder builder = RenderCompat.quads(bytes, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int j = 0; j < GRID_Y; j++) {
            for (int i = 0; i < GRID_X; i++) {
                vertex(builder, sx, sy, u, v, i, j);
                vertex(builder, sx, sy, u, v, i, j + 1);
                vertex(builder, sx, sy, u, v, i + 1, j + 1);
                vertex(builder, sx, sy, u, v, i + 1, j);
            }
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (MeshData data = builder.buildOrThrow()) {
            encoder.writeToBuffer(vertices.slice(), data.vertexBuffer());
        }

        GpuBufferSlice projectionSlice = projection.get(1000.0F, 11000.0F, w, h, true);
        GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(
                new Matrix4f().translation(0.0F, 0.0F, -6000.0F), panelParams(), lightParams(), styleParams());

        try (RenderPass pass = RenderCompat.pass(() -> "Nova VR panel", main.getColorTextureView(), null)) {
            RenderCompat.setPipeline(pass, panelPipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Projection", projectionSlice);
            pass.setUniform("DynamicTransforms", transform);
            RenderCompat.texture(pass, "Sampler0", content.getColorTextureView(), linear());
            RenderCompat.texture(pass, "Sampler1", blurA.getColorTextureView(), linear());
            RenderCompat.vertices(pass, vertices);
            RenderCompat.drawQuads(pass, INDEX_COUNT);
        }
    }

    private static void vertex(BufferBuilder builder, float[] sx, float[] sy, float[] u, float[] v, int i, int j) {
        int index = j * (GRID_X + 1) + i;
        builder.addVertex(sx[index], sy[index], 0.0F).setUv(u[i], v[j]).setColor(0xFFFFFFFF);
    }

    /** x = Sichtbarkeit, yz = Fenstergroesse in Bildpixeln, w = Eckenradius. */
    private static Vector4f panelParams() {
        float visible = opening ? Math.min(1.0F, appear * 1.6F) : smooth(appear);
        float radius = Math.min(frameWidth, frameHeight) * 0.045F;
        return new Vector4f(visible, frameWidth, frameHeight, radius);
    }

    /** x = Zeit, yz = Lichtfleck auf dem Glas (wandert mit der Neigung, wie eine echte Spiegelung). */
    private static Vector3f lightParams() {
        float time = (Util.getMillis() % 3_600_000L) / 1000.0F;
        float turn = yaw + VrMenuMod.tilt;
        return new Vector3f(time, 0.24F - turn * 0.025F, 0.04F + pitch * 0.025F);
    }

    /** Spalten: Glas-Toenung, Akzent + Glow, (Schatten, hell, Inhalt, Leiste), (Leistengroesse, Glasrand, Hover). */
    private static Matrix4f styleParams() {
        // Panelfarbe, abgedunkelt: dunkles Glas
        int panel = NovaGui.PANEL;
        float tr = ((panel >> 16) & 0xFF) / 255.0F * 0.55F;
        float tg = ((panel >> 8) & 0xFF) / 255.0F * 0.55F;
        float tb = (panel & 0xFF) / 255.0F * 0.55F;
        float ta = 0.30F;
        int accent = NovaGui.BRAND;
        // Das Nova-Design leuchtet nicht: nur ein Hauch Akzent am Glasrand
        float glow = 0.08F;
        float shadow = 0.45F;
        float contentAlpha = opening ? smooth(appear * 1.25F) : smooth(appear);
        float bar = VrMenuMod.windowBar ? (opening ? smooth(appear * 1.4F - 0.4F) : smooth(appear)) : 0.0F;
        return new Matrix4f(
                tr, tg, tb, ta,
                ((accent >> 16) & 0xFF) / 255.0F, ((accent >> 8) & 0xFF) / 255.0F, (accent & 0xFF) / 255.0F, glow,
                shadow, 0.0F, contentAlpha, bar,
                barWidth(), barHeight(), pad(), barHover);
    }
}
