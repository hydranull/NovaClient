package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import nova.nova_client.client.HandGlowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import net.minecraft.client.renderer.GameRenderer;
//?} else {
/*import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
*///?}

/** Glowing Outlines: nach dem Hand-Pass die Konturen der Hand-Items mit Glow-Shader nachziehen. */
//? if >=26.1 {
@Mixin(GameRenderer.class)
public abstract class HandGlowPassMixin {
    // Der Render-Pass der Hand ist hier schon zu, der vorbereitete Frame noch offen (er wird gleich geschlossen)
    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;close()V"))
    private void nova_client$handGlowPass(FeatureRenderDispatcher.PreparedFrame frame, Operation<Void> original) {
        try {
            HandGlowRenderer.afterHandPass(frame);
        } finally {
            original.call(frame);
        }
    }
}
//?} else {
/*// 1.21.11: die Hand wird am Ende von renderHandsWithItems gezeichnet, solange die Kamera-Drehung noch auf dem
// Model-View-Stack liegt (der spaetere Aufruf in GameRenderer.renderLevel liefe ohne sie -> Kontur verdreht)
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandGlowPassMixin {
    @WrapOperation(method = "renderHandsWithItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures()V"))
    private void nova_client$handGlowPass(FeatureRenderDispatcher dispatcher, Operation<Void> original) {
        HandGlowRenderer.renderHandFeatures(dispatcher);
    }
}
*///?}
