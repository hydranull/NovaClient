package nova.nova_client.client.farmbuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Was kommt beim Abbauen heraus (normales Werkzeug, ohne Behutsamkeit)? Der Bot baut nur Bloecke ab, die er danach
 * genauso wieder hinsetzen kann - Glas, Eis, Glowstone & Co. gehen beim Abbauen kaputt und werden nie angefasst.
 */
public final class Drops {
    /** Droppen nichts Setzbares (oder nur manchmal) - gehen beim Abbauen verloren. */
    private static final Set<Block> LOST = Set.of(Blocks.GLASS_PANE, Blocks.GLOWSTONE, Blocks.SEA_LANTERN,
            Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF, Blocks.CLAY, Blocks.MELON, Blocks.TURTLE_EGG, Blocks.SNIFFER_EGG,
            Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD,
            Blocks.SMALL_AMETHYST_BUD, Blocks.SCULK, Blocks.SCULK_VEIN, Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR,
            Blocks.CALIBRATED_SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.POWDER_SNOW,
            Blocks.COBWEB, Blocks.BEEHIVE, Blocks.BEE_NEST, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT,
            Blocks.GILDED_BLACKSTONE, Blocks.GRAVEL, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.DEAD_BUSH,
            Blocks.FROGSPAWN, Blocks.REINFORCED_DEEPSLATE, Blocks.TALL_GRASS, Blocks.LARGE_FERN,
            Blocks.SHORT_GRASS, Blocks.FERN, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT,
            Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY);
    /** Droppen einen anderen Block (Stein -> Bruchstein ...): als Gelaende okay, als Farm-Block nicht. */
    private static final Set<Block> DIFFERENT = Set.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.NETHER_QUARTZ_ORE,
            Blocks.NETHER_GOLD_ORE, Blocks.INFESTED_STONE);

    private Drops() {
    }

    /** Geht der Block beim Abbauen verloren (kein setzbares Item, z.B. Glas)? */
    public static boolean lost(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.TINTED_GLASS) {
            return false; // getoentes Glas droppt sich selbst
        }
        return LOST.contains(block) || state.is(BlockTags.IMPERMEABLE) || block instanceof StainedGlassPaneBlock
                || state.is(BlockTags.ICE) || state.is(BlockTags.LEAVES) || state.is(BlockTags.CORAL_BLOCKS)
                || state.is(BlockTags.CORALS) || state.is(BlockTags.WALL_CORALS) || block instanceof InfestedBlock
                || block instanceof HugeMushroomBlock;
    }

    /**
     * Kommt beim Abbauen genau dieser Block wieder heraus (Erde/Gras/Ackerboden zaehlen als gleich - die wandeln
     * sich ohnehin ineinander um)? Nur solche Farm-Bloecke duerfen fuer einen Weg oder freie Sicht weg.
     */
    public static boolean dropsItself(BlockState state) {
        if (lost(state) || DIFFERENT.contains(state.getBlock()) || isOre(state)) {
            return false;
        }
        return true;
    }

    /** Erze droppen Rohstoffe statt sich selbst. */
    private static boolean isOre(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore");
    }
}
