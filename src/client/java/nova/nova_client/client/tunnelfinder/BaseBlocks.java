package nova.nova_client.client.tunnelfinder;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * Einordnung der Bloecke fuer den Tunnel Base Finder: Bauwerks-Marker, Funktionsbloecke und Lichtquellen, die so nicht
 * generiert werden. Jede Blockart wird einmal anhand ihres Namens eingeordnet und als Bitmaske zwischengespeichert.
 */
public final class BaseBlocks {
    public enum Dimension {
        OVERWORLD, NETHER, END, OTHER
    }

    /** Bauwerks-Marker, die Spieler fast nie verbauen (Cobweb, Infested, Suspicious Sand/Gravel, ...). */
    public static final int STRONG_MARKER = 1;
    private static final int FUNCTION = 1 << 1;
    /** Natuerliche Lichtquelle (Lava, Leuchtflechten, Amethyst, ...). */
    private static final int NATURAL_LIGHT = 1 << 2;
    /** Lichtquelle, die nur im Nether natuerlich ist (Glowstone, Shroomlight). */
    private static final int NETHER_LIGHT = 1 << 3;

    private static final Reference2IntOpenHashMap<Block> CACHE = new Reference2IntOpenHashMap<>();

    private static final Set<String> STRONG = Set.of(
            "end_portal_frame", "reinforced_deepslate", "trial_spawner", "vault", "spawner", "cobweb", "rail",
            "suspicious_sand", "suspicious_gravel", "sculk_shrieker", "mud_bricks");
    private static final Set<String> FUNCTIONS = Set.of(
            "crafting_table", "furnace", "smoker", "blast_furnace", "anvil", "chipped_anvil", "damaged_anvil",
            "enchanting_table", "brewing_stand", "hopper", "dropper", "dispenser", "observer", "piston",
            "sticky_piston", "piston_head", "moving_piston", "redstone_wire", "repeater", "comparator",
            "redstone_block", "lever", "target", "daylight_detector", "note_block", "jukebox", "ender_chest",
            "smithing_table", "grindstone", "loom", "cartography_table", "fletching_table", "stonecutter", "lectern",
            "composter", "beacon", "respawn_anchor", "lodestone", "crafter", "cauldron", "water_cauldron",
            "lava_cauldron", "powder_snow_cauldron", "bell", "scaffolding", "redstone_lamp", "tripwire_hook",
            "calibrated_sculk_sensor", "chiseled_bookshelf", "conduit");
    private static final Set<String> NATURAL_LIGHTS = Set.of(
            "lava", "magma_block", "glow_lichen", "cave_vines", "cave_vines_plant", "amethyst_cluster",
            "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud", "sculk_sensor", "sculk_catalyst",
            "brown_mushroom", "fire", "soul_fire", "redstone_ore", "deepslate_redstone_ore", "end_portal_frame",
            "end_portal", "end_gateway", "nether_portal", "crying_obsidian", "sea_pickle", "dragon_egg");
    private static final Set<String> NETHER_LIGHTS = Set.of("glowstone", "shroomlight");

    private BaseBlocks() {
    }

    public static Dimension dimensionOf(ClientLevel level) {
        if (level.dimension() == Level.OVERWORLD) {
            return Dimension.OVERWORLD;
        }
        if (level.dimension() == Level.NETHER) {
            return Dimension.NETHER;
        }
        return level.dimension() == Level.END ? Dimension.END : Dimension.OTHER;
    }

    public static int flags(BlockState state) {
        Block block = state.getBlock();
        int flags = CACHE.getOrDefault(block, -1);
        if (flags < 0) {
            flags = classify(BuiltInRegistries.BLOCK.getKey(block).getPath());
            CACHE.put(block, flags);
        }
        return flags;
    }

    private static int classify(String p) {
        int flags = 0;
        if (STRONG.contains(p) || p.startsWith("infested_")) {
            flags |= STRONG_MARKER;
        }
        if (FUNCTIONS.contains(p) || p.endsWith("_bed") || p.endsWith("shulker_box") || p.endsWith("_sign")
                || p.endsWith("_banner")) {
            flags |= FUNCTION;
        }
        if (NATURAL_LIGHTS.contains(p)) {
            flags |= NATURAL_LIGHT;
        }
        if (NETHER_LIGHTS.contains(p)) {
            flags |= NETHER_LIGHT;
        }
        return flags;
    }

    /** Fest und begehbar-blockierend (Wand, Boden, Decke). Fluessigkeiten und Pflanzen/Fackeln zaehlen nicht. */
    public static boolean isSolid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && state.isSolid();
    }

    /** Lichtquelle, die so nicht generiert wird (Fackeln, Laternen, Glowstone in der Oberwelt, ...). */
    public static boolean isArtificialLight(BlockState state, int flags, Dimension dimension) {
        if (state.getLightEmission() <= 0 || (flags & (NATURAL_LIGHT | FUNCTION)) != 0) {
            return false; // Funktionsbloecke (Ofen, Enderkiste, ...) zaehlen schon dort
        }
        if ((flags & NETHER_LIGHT) != 0 && dimension == Dimension.NETHER) {
            return false;
        }
        return !(dimension == Dimension.END && state.getBlock() == Blocks.END_ROD);
    }
}
