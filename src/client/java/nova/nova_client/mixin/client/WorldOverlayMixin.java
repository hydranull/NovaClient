package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import nova.nova_client.client.farmbuilder.WorldOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Schematic Builder: gedachte Bloecke fuer "Was waere, wenn"-Pruefungen (siehe {@link WorldOverlay}). Bewusst als
 * ModifyReturnValue (keine Objekte je Aufruf) - getBlockState wird sehr oft aufgerufen; ohne aktives Overlay kostet das
 * nur einen Feld-Test.
 */
@Mixin(Level.class)
public abstract class WorldOverlayMixin {
    @ModifyReturnValue(method = "getBlockState", at = @At("RETURN"))
    private BlockState nova_client$overlayBlock(BlockState original, BlockPos pos) {
        if (WorldOverlay.active) {
            BlockState state = WorldOverlay.get(pos);
            if (state != null) {
                return state;
            }
        }
        return original;
    }

    @ModifyReturnValue(method = "getFluidState", at = @At("RETURN"))
    private FluidState nova_client$overlayFluid(FluidState original, BlockPos pos) {
        if (WorldOverlay.active) {
            BlockState state = WorldOverlay.get(pos);
            if (state != null) {
                return state.getFluidState();
            }
        }
        return original;
    }
}
