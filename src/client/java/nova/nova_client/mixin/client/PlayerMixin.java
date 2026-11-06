package nova.nova_client.mixin.client;

import net.minecraft.world.entity.player.Player;
import nova.nova_client.client.mods.ReachMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
    // Reach: Reichweite zum Anvisieren/Schlagen von Entities (nur fuer den eigenen Spieler)
    @Inject(method = "entityInteractionRange", at = @At("HEAD"), cancellable = true)
    private void nova_client$reach(CallbackInfoReturnable<Double> cir) {
        if (ReachMod.applies((Player) (Object) this)) {
            cir.setReturnValue((double) ReachMod.distance);
        }
    }
}
