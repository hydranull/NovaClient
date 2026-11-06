package nova.nova_client.client;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.mods.GlowingOutlinesMod;
import nova.nova_client.mixin.client.GameRendererAccessor;
import nova.nova_client.mixin.client.ShaderManagerAccessor;
//? if >=26.1 {
import com.mojang.renderpearl.api.commands.RenderPass;
import nova.nova_client.mixin.client.LevelRendererAccessor;
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Glowing Outlines: Vanilla zeichnet den Glowing-Umriss vor der Hand, deshalb bekommen gehaltene Items nie einen.
 * Hier wird der Hand-Pass nachgebaut: Items (mit Outline-Farbe aus HandGlowItemMixin) landen zusaetzlich im
 * Entity-Outline-Buffer, eine eigene Shader-Kette macht daraus Kontur + Glow, Vanilla blendet das danach ins Bild.
 */
public final class HandGlowRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/GlowingOutlines");
    private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("nova_client", "hand_glow");

    /** Wurde in diesem Frame ein Item mit Kontur eingereiht? */
    private static boolean pending;
    /** Laeuft gerade der Hand-Pass mit Glow (siehe LevelRendererMixin)? */
    private static boolean handPass;
    private static PostChain chain;
    private static String chainKey;
    private static boolean failed;

    private HandGlowRenderer() {
    }

    /** Outline-Farbe fuer ein Hand-Item, 0 = keine. */
    public static int outlineColor(int vanilla) {
        if (!GlowingOutlinesMod.isEnabled() || failed) {
            return vanilla;
        }
        pending = true;
        return GlowingOutlinesMod.color();
    }

    public static boolean inHandPass() {
        return handPass;
    }

    //? if >=26.1 {
    /**
     * 26.3: nach dem Hand-Pass (sein Render-Pass ist schon zu, der Frame noch offen) die Konturen der Hand-Items in den
     * Entity-Outline-Puffer zeichnen und den Glow darauf legen. Vanilla blendet den Puffer erst nach der Hand ins Bild
     * (blitEntityOutline nach renderLevel) - die Umrisse der Welt deshalb jetzt schon einblenden, sonst loescht sie das
     * Leeren fuer die Hand.
     */
    public static void afterHandPass(FeatureRenderDispatcher.PreparedFrame frame) {
        if (!pending) {
            return;
        }
        pending = false;
        Minecraft client = Minecraft.getInstance();
        LevelRendererAccessor level = (LevelRendererAccessor) client.levelRenderer;
        RenderTarget outline = level.nova_client$outlineTarget();
        if (outline == null || !frame.hasAnyOutline()) {
            return;
        }
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        if (level.nova_client$rendersOutline()) {
            outline.blitAndBlendToTexture(main.getColorTextureView(), main.getDepthTextureView());
        }
        RenderCompat.clearTransparent(outline.getColorTexture(), outline.getDepthTexture());
        handPass = true;
        try (RenderPass pass = RenderCompat.pass(() -> "Nova hand outline", outline.getColorTextureView(),
                outline.getDepthTextureView())) {
            RenderSystem.bindDefaultUniforms(pass);
            frame.executeOutline(pass);
        } finally {
            handPass = false;
        }
        // Kontur + Glow bleiben im Outline-Buffer, Vanilla blendet sie gleich danach (einmal) ins Bild
        applyGlow(client, outline);
        level.nova_client$setRendersOutline(true);
    }
    //?} else {
    /*/^*
     * 1.21.11: Ersatz fuer FeatureRenderDispatcher.renderAllFeatures am Ende von FirstPersonHandsAndItemsRenderer
     * .renderHandsWithItems - dort ist die Kamera-Drehung noch auf dem Model-View-Stack (spaeter gezeichnet sitzt die
     * Kontur verdreht neben dem Item). Vanilla blendet den Outline-Buffer erst nach der Hand ins Bild (doEntityOutline) -
     * deshalb die Umrisse der Welt jetzt schon einblenden, den Buffer fuer die Hand leeren und das Ergebnis Vanilla zum
     * Einblenden ueberlassen.
     ^/
    public static void renderHandFeatures(FeatureRenderDispatcher dispatcher) {
        handPass = true;
        try {
            renderHandFeaturesInner(dispatcher);
        } finally {
            handPass = false;
        }
    }

    private static void renderHandFeaturesInner(FeatureRenderDispatcher dispatcher) {
        if (!pending) {
            dispatcher.renderAllFeatures();
            return;
        }
        pending = false;
        Minecraft client = Minecraft.getInstance();

        RenderTarget outline = client.levelRenderer.entityOutlineTarget();
        if (outline == null) {
            dispatcher.renderAllFeatures();
            return;
        }

        client.levelRenderer.doEntityOutline();
        RenderCompat.clearTransparent(outline.getColorTexture(), outline.getDepthTexture());
        dispatcher.renderAllFeatures();
        client.renderBuffers().bufferSource().endBatch();
        client.renderBuffers().outlineBufferSource().endOutlineBatch();
        // ohne Glow-Kette bleibt nur die Kontur der Hand (Vanilla blendet den Buffer gleich ein)
        applyGlow(client, outline);
    }
    *///?}

    /** Glow-Kette auf den Outline-Buffer anwenden. false = keine Kette (fehlgeschlagen). */
    private static boolean applyGlow(Minecraft client, RenderTarget outline) {
        PostChain glowChain = chain(outline.height);
        if (glowChain == null) {
            return false;
        }
        FrameGraphBuilder builder = new FrameGraphBuilder();
        TargetMap targets = new TargetMap();
        targets.replace(LevelTargetBundle.ENTITY_OUTLINE_TARGET_ID, builder.importExternal("entity_outline", outline));
        glowChain.addToFrame(builder, outline.width, outline.height, targets);
        builder.execute(((GameRendererAccessor) client.gameRenderer).nova_client$resourcePool());
        return true;
    }

    // ---------------------------------------------------------------- Shader-Kette

    /**
     * Kette fuer die aktuellen Einstellungen; wird neu gebaut, wenn sich Glow/Staerke/Breite oder die Fensterhoehe aendern.
     * Die Pixelwerte gelten fuer 720p und werden mit der Aufloesung skaliert, damit der Glow ueberall gleich gross wirkt.
     */
    private static PostChain chain(int screenHeight) {
        float scale = Math.max(1.0F, screenHeight / 720.0F);
        float radius = GlowingOutlinesMod.glow * 2.0F * scale;
        float width = GlowingOutlinesMod.width * scale;
        String key = String.format(Locale.ROOT, "%.0f|%.2f|%.1f",
                radius, GlowingOutlinesMod.intensity, width);
        if (chain != null && key.equals(chainKey)) {
            return chain;
        }
        close();
        try {
            PostChainConfig config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(configJson(radius, width)))
                    .getOrThrow();
            ShaderManagerAccessor shaders = (ShaderManagerAccessor) Minecraft.getInstance().getShaderManager();
            //? if >=26.1 {
            chain = PostChain.load(config, Minecraft.getInstance().getTextureManager(),
                    Set.of(LevelTargetBundle.ENTITY_OUTLINE_TARGET_ID), CHAIN_ID,
                    shaders.nova_client$postChainProjection(), shaders.nova_client$postChainProjectionMatrixBuffer());
            //?} else {
            /*chain = PostChain.load(config, Minecraft.getInstance().getTextureManager(),
                    Set.of(LevelTargetBundle.ENTITY_OUTLINE_TARGET_ID), CHAIN_ID,
                    shaders.nova_client$postChainProjectionMatrixBuffer());
            *///?}
            chainKey = key;
            return chain;
        } catch (Exception e) {
            // Nicht jeden Frame erneut versuchen: Mod bleibt an, zeichnet aber nichts mehr
            failed = true;
            LOGGER.error("Glowing Outlines shader could not be loaded", e);
            return null;
        }
    }

    public static void close() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        chainKey = null;
    }

    private static String configJson(float radiusPx, float widthPx) {
        String radius = String.format(Locale.ROOT, "%.1f", radiusPx);
        String intensity = String.format(Locale.ROOT, "%.3f", GlowingOutlinesMod.intensity);
        String width = String.format(Locale.ROOT, "%.1f", widthPx);
        return """
                {
                  "targets": { "swap": {}, "swap2": {} },
                  "passes": [
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "nova_client:post/hand_glow_blur",
                      "inputs": [ { "sampler_name": "In", "target": "minecraft:entity_outline", "bilinear": true } ],
                      "output": "swap",
                      "uniforms": { "GlowConfig": [
                        { "name": "BlurDir", "type": "vec2", "value": [ 1.0, 0.0 ] },
                        { "name": "Radius", "type": "float", "value": RADIUS } ] }
                    },
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "nova_client:post/hand_glow_blur",
                      "inputs": [ { "sampler_name": "In", "target": "swap", "bilinear": true } ],
                      "output": "swap2",
                      "uniforms": { "GlowConfig": [
                        { "name": "BlurDir", "type": "vec2", "value": [ 0.0, 1.0 ] },
                        { "name": "Radius", "type": "float", "value": RADIUS } ] }
                    },
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "nova_client:post/hand_glow_combine",
                      "inputs": [
                        { "sampler_name": "Mask", "target": "minecraft:entity_outline" },
                        { "sampler_name": "Blur", "target": "swap2", "bilinear": true } ],
                      "output": "swap",
                      "uniforms": { "CombineConfig": [
                        { "name": "Intensity", "type": "float", "value": INTENSITY },
                        { "name": "OutlineWidth", "type": "float", "value": WIDTH } ] }
                    },
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "minecraft:post/blit",
                      "inputs": [ { "sampler_name": "In", "target": "swap" } ],
                      "output": "minecraft:entity_outline",
                      "uniforms": { "BlitConfig": [
                        { "name": "ColorModulate", "type": "vec4", "value": [ 1.0, 1.0, 1.0, 1.0 ] } ] }
                    }
                  ]
                }
                """.replace("RADIUS", radius).replace("INTENSITY", intensity).replace("WIDTH", width);
    }

    /** Einfache TargetBundle-Implementierung fuer addToFrame. */
    private static final class TargetMap implements PostChain.TargetBundle {
        private final Map<Identifier, ResourceHandle<RenderTarget>> targets = new HashMap<>();

        @Override
        public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
            targets.put(id, handle);
        }

        @Override
        public ResourceHandle<RenderTarget> get(Identifier id) {
            return targets.get(id);
        }
    }
}
