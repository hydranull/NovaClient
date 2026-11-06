package nova.nova_client.mixin.client;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Schematic Builder: Ergebnis einer Platzierung vorher ausrechnen (Ausrichtung, Wand-/Boden-Variante ...). */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState nova_client$getPlacementState(BlockPlaceContext context);
}
