package nova.nova_client.client;

//? if >=26.1 {
import com.mojang.renderpearl.api.GpuFormat;
//?}
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.mods.MotionBlurMod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Motion Blur, zwei Arten. Beide laufen direkt nachdem die Welt gezeichnet ist - Hand und HUD kommen erst danach und
 * bleiben scharf.
 * <ul>
 *     <li><b>Smooth</b> (Standard): das letzte Bild wird leicht ueber das neue gelegt, jede Drehung zieht eine weiche
 *     Spur. Die Deckkraft haengt von der Bildzeit ab, damit es bei jeder FPS gleich aussieht
 *     (shaders/core/motion_blur_smooth.fsh).</li>
 *     <li><b>Per-pixel</b>: wie in Videoschnitt-Programmen - ein Shader berechnet fuer jeden Pixel, wo er im letzten
 *     Bild war, und verwischt ihn entlang dieser Strecke (shaders/core/motion_blur.fsh).</li>
 * </ul>
 * <p>
 * Die Rueckprojektion: Pixel + Tiefe -> Position relativ zur Kamera (inverse aktuelle View-Projektion) -> um die
 * Kamerabewegung verschoben -> mit der View-Projektion des letzten Bildes auf den Bildschirm. Minecraft zeichnet relativ
 * zur Kamera, deshalb steckt die Kamerabewegung als Verschiebung zwischen den beiden Matrizen.
 */
public final class MotionBlur {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/MotionBlur");
    /** Bezug fuer "gleicher Look bei jeder FPS": so viel Bewegung wie in einem Bild bei 60 FPS. */
    private static final float REFERENCE_FPS = 60.0F;
    /** Mehr Kamerabewegung in einem Bild = Teleport/Respawn: kein Verwischen ueber den ganzen Bildschirm. */
    private static final double TELEPORT_DISTANCE = 6.0D;

    private static RenderPipeline pipeline;
    private static Boolean pipelineValid;
    private static boolean failed;
    private static TextureTarget output;

    // Smooth: das letzte fertige Bild
    private static RenderPipeline smoothPipeline;
    private static Boolean smoothValid;
    private static TextureTarget history;
    private static boolean primed;

    // Kamera des letzten Bildes
    private static final Matrix4f PREVIOUS = new Matrix4f();
    private static boolean hasPrevious;
    private static double previousX;
    private static double previousY;
    private static double previousZ;
    private static ClientLevel previousLevel;
    private static long lastNanos;
    private static float seed;

    private MotionBlur() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("nova_client", path);
    }

    /**
     * Aus dem GameRenderer-Mixin: die Welt ist fertig gezeichnet (Farbe + Tiefe), gleich wird die Tiefe fuer die Hand
     * geleert. projection = genau die Projektion dieses Bildes (inkl. Wackeln beim Laufen, Uebelkeit ...).
     */
    public static void apply(Matrix4f projection) {
        Minecraft client = Minecraft.getInstance();
        if (!MotionBlurMod.isEnabled() || failed || client.level == null) {
            hasPrevious = false;
            primed = false;
            lastNanos = 0L;
            return;
        }
        Camera camera = client.gameRenderer.mainCamera();
        Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        Vec3 pos = camera.position();

        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : (now - lastNanos) / 1.0E9F;
        lastNanos = now;

        double dx = pos.x - previousX;
        double dy = pos.y - previousY;
        double dz = pos.z - previousZ;
        boolean jump = !hasPrevious || client.level != previousLevel || dt <= 0.0F || dt > 0.25F
                || dx * dx + dy * dy + dz * dz > TELEPORT_DISTANCE * TELEPORT_DISTANCE;
        if (MotionBlurMod.mode == MotionBlurMod.MODE_SMOOTH) {
            try {
                smooth(client, dt, jump);
            } catch (RuntimeException e) {
                failed = true;
                LOGGER.error("Motion Blur disabled after a render error", e);
            }
        } else if (!jump && pipelineReady()) {
            try {
                Matrix4f reproject = new Matrix4f(PREVIOUS).translate((float) dx, (float) dy, (float) dz)
                        .mul(new Matrix4f(viewProjection).invert());
                render(client, reproject, shutter(dt));
            } catch (RuntimeException e) {
                failed = true;
                LOGGER.error("Motion Blur disabled after a render error", e);
            }
        }
        PREVIOUS.set(viewProjection);
        previousX = pos.x;
        previousY = pos.y;
        previousZ = pos.z;
        previousLevel = client.level;
        hasPrevious = true;
    }

    /** Smooth: letztes Bild ueberblenden, danach das neue fuer das naechste Bild merken. */
    private static void smooth(Minecraft client, float dt, boolean jump) {
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        if (history == null) {
            history = RenderCompat.target("Nova motion blur history", main.width, main.height, false);
            primed = false;
        } else if (history.width != main.width || history.height != main.height) {
            history.resize(main.width, main.height);
            primed = false;
        }
        if (primed && !jump && smoothReady()) {
            // Staerke gilt fuer 60 FPS, bei mehr Bildern pro Sekunde entsprechend weniger je Bild
            float retention = (float) Math.pow(MotionBlurMod.smoothStrength / 100.0D, dt * REFERENCE_FPS);
            if (retention >= 1.0F / 255.0F) {
                seed = (seed + 0.61803398875F) % 1.0F;
                GpuBufferSlice params = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),
                        new Vector4f(retention, 0.0F, 0.0F, 0.0F), new Vector3f(seed, 0.0F, 0.0F), new Matrix4f());
                try (RenderPass pass = RenderCompat.pass(() -> "Nova motion blur (smooth)", main.getColorTextureView(), null)) {
                    RenderCompat.setPipeline(pass, smoothPipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", params);
                    RenderCompat.texture(pass, "InSampler", history.getColorTextureView(), sampler(FilterMode.NEAREST));
                    RenderCompat.drawFullscreen(pass);
                }
            }
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(), history.getColorTexture(),
                0, 0, 0, 0, 0, main.width, main.height);
        primed = true;
    }

    private static boolean smoothReady() {
        if (smoothValid == null) {
            try {
                smoothPipeline = RenderCompat.pipeline(id("pipeline/motion_blur_smooth"), Identifier.withDefaultNamespace("core/screenquad"),
                        id("core/motion_blur_smooth"), null, false, BlendFunction.TRANSLUCENT, "InSampler");
                smoothValid = RenderCompat.compiles(smoothPipeline);
                if (!smoothValid) {
                    LOGGER.error("Smooth motion blur shader could not be compiled");
                }
            } catch (RuntimeException e) {
                LOGGER.error("Smooth motion blur shader could not be created", e);
                smoothValid = false;
            }
            if (!smoothValid) {
                failed = true;
            }
        }
        return smoothValid;
    }

    /** Anteil der Bewegung eines Bildes, der verwischt wird (Verschlusswinkel, Staerke, FPS-Ausgleich). */
    private static float shutter(float dt) {
        float amount = MotionBlurMod.strength * (MotionBlurMod.shutterAngle / 360.0F);
        if (MotionBlurMod.fpsIndependent) {
            // Bei 144 FPS bewegt sich pro Bild viel weniger als bei 60: so verlaengern, dass es gleich aussieht
            amount *= Math.max(0.25F, Math.min(4.0F, 1.0F / (REFERENCE_FPS * dt)));
        }
        return amount;
    }

    private static void render(Minecraft client, Matrix4f reproject, float shutter) {
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        ensureTarget(main.width, main.height);
        seed = (seed + 0.61803398875F) % 1.0F; // Rauschen jedes Bild etwas anders -> beim Bewegen kein festes Muster
        GpuBufferSlice params = RenderSystem.getDynamicUniforms().writeTransform(reproject,
                new Vector4f(shutter, MotionBlurMod.samples(), zZeroToOne() ? 1.0F : 0.0F, MotionBlurMod.maxBlur / 100.0F),
                new Vector3f(seed, 0.0F, 0.0F), new Matrix4f());
        try (RenderPass pass = RenderCompat.pass(() -> "Nova motion blur", output.getColorTextureView(), null)) {
            RenderCompat.setPipeline(pass, pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", params);
            RenderCompat.texture(pass, "InSampler", main.getColorTextureView(), sampler(FilterMode.LINEAR));
            RenderCompat.texture(pass, "DepthSampler", main.getDepthTextureView(), sampler(FilterMode.NEAREST));
            RenderCompat.drawFullscreen(pass);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(output.getColorTexture(), main.getColorTexture(),
                0, 0, 0, 0, 0, main.width, main.height);
    }

    /** Tiefe im Puffer 0..1 = NDC (26.x je nach Grafik-Backend) statt -1..1 (OpenGL wie in 1.21.11)? */
    private static boolean zZeroToOne() {
        //? if >=26.1 {
        return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        //?} else {
        /*return false;
        *///?}
    }

    private static GpuSampler sampler(FilterMode filter) {
        return RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                filter, filter, false);
    }

    private static void ensureTarget(int width, int height) {
        if (output == null) {
            output = RenderCompat.target("Nova motion blur", width, height, false);
        } else if (output.width != width || output.height != height) {
            output.resize(width, height);
        }
    }

    private static boolean pipelineReady() {
        if (pipelineValid == null) {
            try {
                pipeline = RenderCompat.pipeline(id("pipeline/motion_blur"), Identifier.withDefaultNamespace("core/screenquad"),
                        id("core/motion_blur"), null, false, null, "InSampler", "DepthSampler");
                pipelineValid = RenderCompat.compiles(pipeline);
                if (!pipelineValid) {
                    LOGGER.error("Motion Blur shader could not be compiled");
                }
            } catch (RuntimeException e) {
                LOGGER.error("Motion Blur shader could not be created", e);
                pipelineValid = false;
            }
            if (!pipelineValid) {
                failed = true;
            }
        }
        return pipelineValid;
    }

    /** Nach einem Fehler (Shader, Grafiktreiber) beim naechsten Einschalten neu versuchen. */
    public static void retry() {
        failed = false;
        pipelineValid = null;
        smoothValid = null;
        hasPrevious = false;
        primed = false;
    }

    public static boolean failed() {
        return failed;
    }
}
