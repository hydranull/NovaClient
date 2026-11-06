package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.mods.BoatFlyMod;
import nova.nova_client.client.mods.EspMod;
import nova.nova_client.client.mods.FreecamMod;
import nova.nova_client.client.mods.FreelookMod;
import nova.nova_client.client.mods.TargetEspMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityMixin {
    // Mausbewegung dreht im Freecam (bzw. Freelook "Camera") die Kamera statt den Spieler (pro Frame -> smooth)
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void nova_client$freecamTurn(double yRot, double xRot, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().player) {
            return;
        }
        if (FreecamMod.isEnabled()) {
            FreecamMod.turn(yRot, xRot);
            ci.cancel();
        } else if (FreelookMod.turnsCamera()) {
            FreelookMod.turn(yRot, xRot);
            ci.cancel();
        }
    }

    // Boat Fly: Bewegung des gesteuerten Fahrzeugs ersetzen
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 nova_client$boatFly(Vec3 movement, MoverType type) {
        return BoatFlyMod.onEntityMove((Entity) (Object) this, type, movement);
    }

    // ESP-Farbe: Spieler rot, Monster orange, sonstige Mobs gruen (Team-Farben haben Vorrang)
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void nova_client$espColor(CallbackInfoReturnable<Integer> cir) {
        Entity self = (Entity) (Object) this;
        // Spezial-ESPs (Villager, Trader, ...) haben ihre eigene Farbe
        TargetEspMod target = TargetEspMod.find(self);
        if (target != null && target.outline && self.getTeam() == null) {
            cir.setReturnValue(target.color());
            return;
        }
        // Erst das billige ESP-Flag pruefen, getTeam() ist eine Scoreboard-Suche pro Entity und Frame
        if (EspMod.shouldGlow(self) && self.getTeam() == null) {
            cir.setReturnValue(EspMod.color(self));
        }
    }
}
