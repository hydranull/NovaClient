package nova.nova_client.client;

//? if >=26.1 {
import com.mojang.renderpearl.api.GpuFormat;
//?}
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.mods.VividMod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vivid: ein Durchgang ueber das fertige Bild der Welt (samt Hand, vor HUD und Menues) - leicht nachschaerfen, matte
 * Farben kraeftig und schon satte nur wenig anheben, sanfte S-Kurve (shaders/core/vivid.fsh). Beim Umschalten blendet
 * der Effekt in einer knappen halben Sekunde weich ueber.
 */
public final class Vivid {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Vivid");
    /** Wie schnell sich die Staerke beim Umschalten dem Ziel naehert. */
    private static final float FADE_SPEED = 7.0F;
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector4f PARAMS = new Vector4f();
    private static final Vector3f NONE = new Vector3f();

    private static RenderPipeline pipeline;
    private static Boolean valid;
    private static TextureTarget output;
    private static float amount;
    private static long lastNanos;

    private Vivid() {
    }

    /** Aus dem GameRenderer-Mixin: Welt, Hand und Leucht-Umrisse sind fertig, HUD und Menues kommen gleich danach. */
    public static void apply() {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Math.min(0.1F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;
        amount = NovaGui.approach(amount, VividMod.isEnabled() ? 1.0F : 0.0F, FADE_SPEED, dt);
        if (amount <= 0.0F || !ready()) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            if (output == null) {
                output = RenderCompat.target("Nova vivid", main.width, main.height, false);
            } else if (output.width != main.width || output.height != main.height) {
                output.resize(main.width, main.height);
            }
            PARAMS.set(amount, 0.0F, 0.0F, 0.0F);
            GpuBufferSlice params = RenderSystem.getDynamicUniforms().writeTransform(IDENTITY, PARAMS, NONE, IDENTITY);
            try (RenderPass pass = RenderCompat.pass(() -> "Nova vivid", output.getColorTextureView(), null)) {
                RenderCompat.setPipeline(pass, pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", params);
                RenderCompat.texture(pass, "InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getSampler(
                        AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, false));
                RenderCompat.drawFullscreen(pass);
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(output.getColorTexture(), main.getColorTexture(),
                    0, 0, 0, 0, 0, main.width, main.height);
        } catch (RuntimeException e) {
            valid = false;
            LOGGER.error("Vivid disabled after a render error", e);
        }
    }

    private static boolean ready() {
        if (valid == null) {
            try {
                pipeline = RenderCompat.pipeline(Identifier.fromNamespaceAndPath("nova_client", "pipeline/vivid"),
                        Identifier.withDefaultNamespace("core/screenquad"), Identifier.fromNamespaceAndPath("nova_client", "core/vivid"),
                        null, false, null, "InSampler");
                valid = RenderCompat.compiles(pipeline);
                if (!valid) {
                    LOGGER.error("Vivid shader could not be compiled");
                }
            } catch (RuntimeException e) {
                LOGGER.error("Vivid shader could not be created", e);
                valid = false;
            }
        }
        return valid;
    }

    /** Nach einem Fehler (Shader, Grafiktreiber) beim naechsten Einschalten neu versuchen. */
    public static void retry() {
        valid = null;
    }
}
