package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import nova.nova_client.client.mods.SchematicBuilderMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Schematic Builder: solange der Bot baut, bewegt die Maus die Kamera nicht, das Mausrad wechselt keinen Hotbar-Platz
 * und Mausklicks im Spiel tun nichts (in Menues funktioniert die Maus normal).
 */
@Mixin(MouseHandler.class)
public abstract class FarmBuilderMouseMixin {
    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void nova_client$farmBuilderCamera(double movementTime, CallbackInfo ci) {
        if (SchematicBuilderMod.locksInput() || nova.nova_client.client.tunnelfinder.BotControl.locksInput()
                || nova.nova_client.client.mods.SpawnerProtectMod.locksInput()) {
            accumulatedDX = 0.0D;
            accumulatedDY = 0.0D;
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void nova_client$farmBuilderScroll(long handle, double xOffset, double yOffset, CallbackInfo ci) {
        if ((SchematicBuilderMod.locksInput() || nova.nova_client.client.tunnelfinder.BotControl.locksInput()
                || nova.nova_client.client.mods.SpawnerProtectMod.locksInput()) && Minecraft.getInstance().gui.screen() == null) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void nova_client$farmBuilderClick(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if ((SchematicBuilderMod.locksInput() || nova.nova_client.client.tunnelfinder.BotControl.locksInput()
                || nova.nova_client.client.mods.SpawnerProtectMod.locksInput()) && Minecraft.getInstance().gui.screen() == null) {
            ci.cancel();
        }
    }
}
