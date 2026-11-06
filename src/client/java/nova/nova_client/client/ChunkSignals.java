package nova.nova_client.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Schnelle Chunk-Abfragen fuer die Base-Finding-Module. Jeder Abschnitt (16x16x16) hat eine Palette der Blockstates,
 * die darin vorkommen: {@link LevelChunkSection#maybeHas} prueft nur diese Palette. Erst wenn ein Treffer moeglich
 * ist, wird der Abschnitt Block fuer Block durchsucht - so kostet ein Chunk ohne Treffer fast nichts.
 */
public final class ChunkSignals {
    private static final Predicate<BlockState> FLOWING = state -> {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !fluid.isSource();
    };

    private ChunkSignals() {
    }

    /**
     * Enthaelt der Chunk fliessendes Wasser/Lava? Frisch generierte Chunks haben noch keine (Fluesse fangen erst an zu
     * fliessen, wenn ein Spieler in der Naehe war und der Chunk getickt wurde) - fliessende Fluessigkeit beim ersten
     * Laden heisst also: hier war schon jemand.
     */
    public static boolean hasFlowingFluid(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir() || !section.maybeHas(FLOWING)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        FluidState fluid = section.getFluidState(x, y, z);
                        if (!fluid.isEmpty() && !fluid.isSource()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Alle Bloecke im Chunk, auf die match passt (hoechstens limit Stueck). */
    public static List<BlockPos> find(LevelChunk chunk, Predicate<BlockState> match, int limit) {
        List<BlockPos> found = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(match)) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (match.test(section.getBlockState(x, y, z))) {
                            found.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                            if (found.size() >= limit) {
                                return found;
                            }
                        }
                    }
                }
            }
        }
        return found;
    }
}
