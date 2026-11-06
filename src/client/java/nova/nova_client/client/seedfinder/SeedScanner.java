package nova.nova_client.client.seedfinder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashSet;
import java.util.Set;

/**
 * Sucht in den geladenen Chunks nach Strukturen, deren Startchunk sich eindeutig ablesen laesst:
 * <ul>
 *     <li>Wuestentempel: blaue Terrakotta mit orangem Stern liegt immer bei Chunk-Position (10, 10).</li>
 *     <li>Dschungeltempel (Klebekolben, Moos-Bruchstein, gemeisselte Steinziegel) und Hexenhuette (Kessel, Werkbank,
 *     Topf mit rotem Pilz) liegen komplett in ihrem Startchunk.</li>
 *     <li>Vergrabener Schatz: Truhe bei Chunk-Position (9, 9) mit Herz des Meeres (beim Oeffnen erkannt).</li>
 *     <li>End-Saeulen: Hoehe des Grundgesteins oben auf jeder der zehn Saeulen.</li>
 * </ul>
 * Jeder Chunk wird nur einmal angeschaut; ueber die Paletten der Chunk-Abschnitte werden leere Abschnitte ohne
 * Block-fuer-Block-Suche uebersprungen.
 */
public final class SeedScanner {
    /** So viele neue Chunks pro Tick (verteilt die Arbeit). */
    private static final int CHUNKS_PER_TICK = 12;
    private static final int[][] PYRAMID_STAR = {{10, 7}, {10, 8}, {9, 9}, {11, 9}, {8, 10}, {12, 10}, {7, 10}, {13, 10},
            {9, 11}, {11, 11}, {10, 12}, {10, 13}};

    private static final Set<Long> SCANNED = new HashSet<>();
    private static ClientLevel lastLevel;
    private static BlockPos openedChest;

    private SeedScanner() {
    }

    /** Ein Tick: neue Chunks anschauen, Truhe pruefen, End-Saeulen lesen. Meldet jeden Fund an listener. */
    public static void tick(Minecraft client, SeedData data, Listener listener) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        if (level != lastLevel) {
            lastLevel = level;
            SCANNED.clear();
            openedChest = null;
        }
        if (level.dimension() == Level.OVERWORLD) {
            scanChunks(client, level, data, listener);
            checkTreasureChest(client, level, data, listener);
        } else if (level.dimension() == Level.END) {
            scanPillars(level, data, listener);
        }
    }

    public interface Listener {
        void found(StructureKind kind, int chunkX, int chunkZ);

        void pillars(int seen);
    }

    public static void reset() {
        SCANNED.clear();
        lastLevel = null;
    }

    // ---------------------------------------------------------------- Chunks

    private static void scanChunks(Minecraft client, ClientLevel level, SeedData data, Listener listener) {
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;
        int radius = client.options.getEffectiveRenderDistance();
        int budget = CHUNKS_PER_TICK;
        // Von innen nach aussen, damit Nahes zuerst drankommt
        for (int ring = 0; ring <= radius && budget > 0; ring++) {
            for (int dx = -ring; dx <= ring && budget > 0; dx++) {
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int cx = centerX + dx;
                    int cz = centerZ + dz;
                    long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
                    if (SCANNED.contains(key) || !level.hasChunk(cx, cz)) {
                        continue;
                    }
                    SCANNED.add(key);
                    budget--;
                    StructureKind kind = scanChunk(level, level.getChunk(cx, cz));
                    if (kind != null && data.add(kind, cx, cz, false)) {
                        listener.found(kind, cx, cz);
                    }
                }
            }
        }
    }

    /** Welche Struktur in diesem Chunk startet (oder null). */
    static StructureKind scanChunk(ClientLevel level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int minY = level.getMinY();
        if (pyramid(sections, minY)) {
            return StructureKind.DESERT_PYRAMID;
        }
        if (anySection(sections, Blocks.STICKY_PISTON)) {
            int[] counts = count(sections, Blocks.STICKY_PISTON, Blocks.MOSSY_COBBLESTONE, Blocks.CHISELED_STONE_BRICKS,
                    Blocks.TRIPWIRE_HOOK);
            if (counts[0] >= 2 && counts[1] >= 6 && counts[2] >= 1 && counts[3] >= 1) {
                return StructureKind.JUNGLE_TEMPLE;
            }
        }
        if (anySection(sections, Blocks.POTTED_RED_MUSHROOM)) {
            int[] counts = count(sections, Blocks.POTTED_RED_MUSHROOM, Blocks.CAULDRON, Blocks.CRAFTING_TABLE,
                    Blocks.SPRUCE_PLANKS);
            if (counts[0] >= 1 && counts[1] >= 1 && counts[2] >= 1 && counts[3] >= 8) {
                return StructureKind.SWAMP_HUT;
            }
        }
        return null;
    }

    /** Blaue Terrakotta (oder der orange Stern drumherum) bei Chunk-Position (10, 10). */
    private static boolean pyramid(LevelChunkSection[] sections, int minY) {
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()
                    || !section.maybeHas(s -> s.is(Blocks.DYED_TERRACOTTA.blue()) || s.is(Blocks.DYED_TERRACOTTA.orange()))) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                boolean blue = section.getBlockState(10, y, 10).is(Blocks.DYED_TERRACOTTA.blue());
                int orange = 0;
                for (int[] p : PYRAMID_STAR) {
                    if (section.getBlockState(p[0], y, p[1]).is(Blocks.DYED_TERRACOTTA.orange())) {
                        orange++;
                    }
                }
                if (blue && orange >= 6 || orange >= 10) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anySection(LevelChunkSection[] sections, Block block) {
        for (LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir() && section.maybeHas(s -> s.is(block))) {
                return true;
            }
        }
        return false;
    }

    /** Wie oft jeder der Bloecke im Chunk vorkommt (nur Abschnitte, deren Palette einen davon enthalten kann). */
    private static int[] count(LevelChunkSection[] sections, Block... blocks) {
        int[] counts = new int[blocks.length];
        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> {
                for (Block block : blocks) {
                    if (s.is(block)) {
                        return true;
                    }
                }
                return false;
            })) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        for (int b = 0; b < blocks.length; b++) {
                            if (state.is(blocks[b])) {
                                counts[b]++;
                            }
                        }
                    }
                }
            }
        }
        return counts;
    }

    // ---------------------------------------------------------------- Vergrabener Schatz

    /** Truhe bei (9, 9) im Chunk geoeffnet und ein Herz des Meeres drin -> vergrabener Schatz. */
    private static void checkTreasureChest(Minecraft client, ClientLevel level, SeedData data, Listener listener) {
        if (!(client.gui.screen() instanceof AbstractContainerScreen<?> screen) || !(screen.getMenu() instanceof ChestMenu menu)) {
            // Beim Oeffnen zeigt das Fadenkreuz noch auf die Truhe
            HitResult hit = client.hitResult;
            openedChest = hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                    && level.getBlockState(blockHit.getBlockPos()).is(Blocks.CHEST) ? blockHit.getBlockPos() : null;
            return;
        }
        BlockPos pos = openedChest;
        if (pos == null || (pos.getX() & 15) != 9 || (pos.getZ() & 15) != 9) {
            return;
        }
        Container container = menu.getContainer();
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).is(Items.HEART_OF_THE_SEA)) {
                if (data.add(StructureKind.BURIED_TREASURE, pos.getX() >> 4, pos.getZ() >> 4, false)) {
                    listener.found(StructureKind.BURIED_TREASURE, pos.getX() >> 4, pos.getZ() >> 4);
                }
                openedChest = null;
                return;
            }
        }
    }

    // ---------------------------------------------------------------- End-Saeulen

    /** Grundgestein oben auf jeder Saeule: Hoehe = 76 + 3 * Groesse. */
    private static void scanPillars(ClientLevel level, SeedData data, Listener listener) {
        boolean changed = false;
        for (int i = 0; i < 10; i++) {
            if (data.pillars[i] >= 0) {
                continue;
            }
            int[] center = SeedCracker.pillarCenter(i);
            if (!level.hasChunk(center[0] >> 4, center[1] >> 4)) {
                continue;
            }
            for (int y = 76 + 27; y >= 76; y--) {
                BlockPos pos = new BlockPos(center[0], y, center[1]);
                if (level.getBlockState(pos).is(Blocks.BEDROCK) && level.getBlockState(pos.below()).is(Blocks.OBSIDIAN)) {
                    if ((y - 76) % 3 == 0) {
                        data.pillars[i] = (y - 76) / 3;
                        changed = true;
                    }
                    break;
                }
            }
        }
        if (changed) {
            data.changed();
            listener.pillars(data.pillarsSeen());
        }
    }
}
