package nova.nova_client.mixin.client;

import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.Window;
//? if >=26.1 {
import net.minecraft.server.packs.PackMetadataResources;
//?} else {
/*import net.minecraft.server.packs.PackResources;
*///?}
import nova.nova_client.client.NovaWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nova-Logo in Taskleiste und Titelleiste, direkt nachdem Minecraft sein eigenes Symbol gesetzt hat. */
@Mixin(Window.class)
public abstract class NovaWindowMixin {
    @Shadow
    public abstract long handle();

    //? if >=26.1 {
    @Inject(method = "setIcon(Lnet/minecraft/server/packs/PackMetadataResources;Lcom/mojang/blaze3d/platform/IconSet;)V",
            at = @At("TAIL"))
    private void nova_client$novaIcon(PackMetadataResources resources, IconSet iconSet, CallbackInfo ci) {
        NovaWindow.applyIcon(handle());
    }
    //?} else {
    /*@Inject(method = "setIcon", at = @At("TAIL"))
    private void nova_client$novaIcon(PackResources resources, IconSet iconSet, CallbackInfo ci) {
        NovaWindow.applyIcon(handle());
    }
    *///?}
}
