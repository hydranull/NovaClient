package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import nova.nova_client.client.mods.ZoomMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Zoom: Hand (und gehaltenes Item) ausblenden, solange gezoomt wird - wenn so eingestellt. */
@Mixin(GameRenderer.class)
public abstract class ZoomHandMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void nova_client$zoomHideHand(CallbackInfo ci) {
        if (ZoomMod.hidesHand()) {
            ci.cancel();
        }
    }
}
