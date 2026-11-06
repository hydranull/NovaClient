package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import nova.nova_client.client.mods.FreecamMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: Hand auf Wunsch ausblenden; die freie Kamera wackelt nicht mit, wenn der Koerper weiterlaeuft. */
@Mixin(GameRenderer.class)
public abstract class FreecamViewMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void nova_client$freecamHand(CallbackInfo ci) {
        if (FreecamMod.hidesHand()) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void nova_client$freecamNoBob(CallbackInfo ci) {
        if (FreecamMod.isEnabled()) {
            ci.cancel();
        }
    }
}
