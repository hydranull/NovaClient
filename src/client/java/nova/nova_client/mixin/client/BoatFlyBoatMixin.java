package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import nova.nova_client.client.mods.BoatFlyMod;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Boat Fly: A/D drehen das Boot nicht - es schaut immer in deine Blickrichtung. */
@Mixin(AbstractBoat.class)
public abstract class BoatFlyBoatMixin {
    @ModifyExpressionValue(method = "controlBoat", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;inputLeft:Z", opcode = Opcodes.GETFIELD))
    private boolean nova_client$boatFlyLeft(boolean original) {
        return original && !BoatFlyMod.locksBoatTurning((AbstractBoat) (Object) this);
    }

    @ModifyExpressionValue(method = "controlBoat", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;inputRight:Z", opcode = Opcodes.GETFIELD))
    private boolean nova_client$boatFlyRight(boolean original) {
        return original && !BoatFlyMod.locksBoatTurning((AbstractBoat) (Object) this);
    }
}
