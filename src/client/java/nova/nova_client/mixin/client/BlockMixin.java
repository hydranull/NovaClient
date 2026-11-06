package nova.nova_client.mixin.client;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import nova.nova_client.client.mods.XrayMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public abstract class BlockMixin {
    // Face-Culling des Fabric-Renderers (Indigo): Erz-Seiten neben Stein trotzdem zeichnen
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void nova_client$xrayShowFaces(BlockState state, BlockState neighborState, Direction direction,
                                                  CallbackInfoReturnable<Boolean> cir) {
        if (XrayMod.isEnabled() && XrayMod.shouldRender(state)) {
            cir.setReturnValue(true);
        }
    }
}
