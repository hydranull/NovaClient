package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.CloudRenderer;
import nova.nova_client.client.mods.SkyVfxMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
    // Sky VFX: keine Wolken vor dem Nordlicht-Himmel
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void nova_client$hideClouds(CallbackInfo ci) {
        if (SkyVfxMod.isEnabled()) {
            ci.cancel();
        }
    }
}
