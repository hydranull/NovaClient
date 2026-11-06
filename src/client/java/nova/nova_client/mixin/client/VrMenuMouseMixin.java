package nova.nova_client.mixin.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import nova.nova_client.client.VrMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** VR Menu: Mausposition durch das gekippte Glasfenster zurueckrechnen, Fensterleiste ziehen. */
@Mixin(MouseHandler.class)
public abstract class VrMenuMouseMixin {
    @Shadow
    private double xpos;
    @Shadow
    private double ypos;

    @Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("RETURN"), cancellable = true)
    private void nova_client$vrMouseX(Window window, CallbackInfoReturnable<Double> cir) {
        if (VrMenu.mapsMouse()) {
            cir.setReturnValue(VrMenu.mapX(window, xpos, ypos));
        }
    }

    @Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("RETURN"), cancellable = true)
    private void nova_client$vrMouseY(Window window, CallbackInfoReturnable<Double> cir) {
        if (VrMenu.mapsMouse()) {
            cir.setReturnValue(VrMenu.mapY(window, xpos, ypos));
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void nova_client$vrWindowBar(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (VrMenu.onButton(info.button(), action, xpos, ypos)) {
            ci.cancel();
        }
    }
}
