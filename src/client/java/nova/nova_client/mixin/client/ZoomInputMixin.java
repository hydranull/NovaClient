package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import nova.nova_client.client.mods.ZoomMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Zoom: Maus dreht beim Zoomen passend langsamer (optional mit weicher Kamera), das Mausrad stellt die Zoom-Staerke
 * ein statt den Hotbar-Platz zu wechseln.
 */
@Mixin(MouseHandler.class)
public abstract class ZoomInputMixin {
    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void nova_client$zoomSensitivity(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
        double scale = ZoomMod.mouseScale();
        original.call(player, yaw * scale, pitch * scale);
    }

    @ModifyExpressionValue(method = "turnPlayer", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Options;smoothCamera:Z"))
    private boolean nova_client$zoomCinematic(boolean smooth) {
        return smooth || ZoomMod.cinematic();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void nova_client$zoomScroll(long handle, double xOffset, double yOffset, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        // Freecam: Mausrad = Flug-Tempo (Zoom geht vor)
        if (client.gui.screen() == null && (ZoomMod.onScroll(yOffset)
                || nova.nova_client.client.mods.FreecamMod.onScroll(yOffset))) {
            ci.cancel();
        }
    }
}
