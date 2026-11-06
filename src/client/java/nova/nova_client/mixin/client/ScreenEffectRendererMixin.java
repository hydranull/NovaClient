package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.ScreenEffectRenderer;
import nova.nova_client.client.mods.ClearLiquidsMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear Liquids: kein Wasser-Schleier vor der Kamera und in Lava kein Feuer vor den Augen. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void nova_client$clearWater(CallbackInfo ci) {
        if (ClearLiquidsMod.hideWaterOverlay()) {
            ci.cancel();
        }
    }

    @Inject(method = "submitFire", at = @At("HEAD"), cancellable = true)
    private static void nova_client$clearLavaFire(CallbackInfo ci) {
        if (ClearLiquidsMod.hideFireOverlay()) {
            ci.cancel();
        }
    }
}
