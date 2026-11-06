package nova.nova_client.client.farmbuilder;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Set;

/**
 * Welche Items braucht ein Block? Sonderfaelle: Wasser/Lava (Eimer), obere Haelften (nichts), Ackerboden (Erde + Hacke),
 * Doppelstufen (2 Stufen), Kerzen/Seegurken/Schnee (Anzahl) usw.
 */
public final class Materials {
    /** Block kann nicht gebaut werden (Feuer, Portale, Kolbenkoepfe ...). */
    public static final Need NONE = new Need(Items.AIR, 0, Kind.SKIP);

    public enum Kind {
        /** Normal setzen. */
        PLACE,
        /** Fluessigkeit aus dem Eimer (kommt ganz zum Schluss). */
        FLUID,
        /** Wird nicht gebaut. */
        SKIP
    }

    public record Need(Item item, int count, Kind kind) {
    }

    /** Diese Bloecke entstehen von selbst oder sind im Survival nicht setzbar. */
    private static final Set<Block> IGNORED = Set.of(
            Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY,
            Blocks.BUBBLE_COLUMN, Blocks.FROSTED_ICE, Blocks.PISTON_HEAD, Blocks.MOVING_PISTON, Blocks.BARRIER,
            Blocks.LIGHT, Blocks.STRUCTURE_VOID, Blocks.KELP_PLANT, Blocks.KELP, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS);

    /** Eigenschaften, die angeben wie viele Items in einem Block stecken. */
    private static final Property<?>[] COUNT_PROPERTIES = {
            BlockStateProperties.CANDLES, BlockStateProperties.PICKLES, BlockStateProperties.EGGS,
            BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT, BlockStateProperties.SEGMENT_AMOUNT};

    private Materials() {
    }

    public static Need of(BlockState state) {
        if (state == null || state.isAir()) {
            return NONE;
        }
        Block block = state.getBlock();
        if (IGNORED.contains(block)) {
            return NONE;
        }
        if (block == Blocks.WATER) {
            return state.getValue(BlockStateProperties.LEVEL) == 0 ? new Need(Items.WATER_BUCKET, 1, Kind.FLUID) : NONE;
        }
        if (block == Blocks.LAVA) {
            return state.getValue(BlockStateProperties.LEVEL) == 0 ? new Need(Items.LAVA_BUCKET, 1, Kind.FLUID) : NONE;
        }
        // Obere Haelften und Kopfteile entstehen beim Setzen der unteren Haelfte
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return NONE;
        }
        if (block instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return NONE;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH) {
            return new Need(Items.DIRT, 1, Kind.PLACE);
        }
        if (block == Blocks.ATTACHED_MELON_STEM) {
            return new Need(Items.MELON_SEEDS, 1, Kind.PLACE);
        }
        if (block == Blocks.ATTACHED_PUMPKIN_STEM) {
            return new Need(Items.PUMPKIN_SEEDS, 1, Kind.PLACE);
        }
        Item item = block.asItem();
        if (item == Items.AIR) {
            return NONE;
        }
        int count = 1;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE) && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            count = 2;
        }
        for (Property<?> property : COUNT_PROPERTIES) {
            if (state.hasProperty(property) && state.getValue(property) instanceof Integer amount) {
                count = Math.max(1, amount);
            }
        }
        return new Need(item, count, Kind.PLACE);
    }

    /** Wasser in einem wassergeloggten Block (z.B. Stufe unter Wasser) - kommt mit den Fluessigkeiten zum Schluss. */
    public static boolean waterlogged(BlockState state) {
        return state != null && state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    /** Braucht der Block nach dem Setzen noch ein Werkzeug (Ackerboden: Hacke, Trampelpfad: Schaufel)? */
    public static boolean needsHoe(BlockState state) {
        return state != null && state.getBlock() == Blocks.FARMLAND;
    }

    public static boolean needsShovel(BlockState state) {
        return state != null && state.getBlock() == Blocks.DIRT_PATH;
    }
}
