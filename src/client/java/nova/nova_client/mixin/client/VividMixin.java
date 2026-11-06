package nova.nova_client.mixin.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import nova.nova_client.client.Vivid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vivid: in render direkt nachdem die Welt samt Hand und Leucht-Umrissen fertig ist (beide Versionen gleich aufgebaut).
 * HUD und Menues kommen danach und behalten ihre Farben.
 */
@Mixin(GameRenderer.class)
public abstract class VividMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;doEntityOutline()V", shift = At.Shift.AFTER), require = 0)
    private void nova_client$vivid(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        Vivid.apply();
    }
}
