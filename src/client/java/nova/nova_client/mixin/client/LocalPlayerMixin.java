package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.PlayerRideableJumping;
import nova.nova_client.client.mods.BoatFlyMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Positions-Pakete werden im Freecam nicht mehr blockiert, damit Server/Singleplayer die echte Position kennen (der
 * Spieler folgt im Freecam normaler Physik). Gezielt wird im Freecam wie normal vom Spieler aus.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    // Boat Fly: auf Pferden & Co. ist die Leertaste "hoch" statt Sprung
    @ModifyReturnValue(method = "jumpableVehicle", at = @At("RETURN"))
    private PlayerRideableJumping nova_client$boatFlyNoJump(PlayerRideableJumping original) {
        return original != null && BoatFlyMod.cancelsMountJump((LocalPlayer) (Object) this) ? null : original;
    }
}
