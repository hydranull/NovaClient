package nova.nova_client.client.mods;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Markiert Chunks mit Kelp, das (fast) bis zum Meeresspiegel gewachsen ist. Kelp waechst nur, solange der Chunk
 * geladen ist - Kelp bis an die Oberflaeche verraet also, dass hier lange Spieler in der Naehe waren.
 * Gezeigt wird wie beim Chunk Finder der ganze Chunk: Flaeche auf Hoehe der hoechsten Kelp-Spitze.
 */
public class KelpEspMod extends ColumnEspMod {
    /** So viele Wasserbloecke duerfen hoechstens noch bis zur Oberflaeche fehlen (0 = ganz oben). */
    private static final int MAX_MISSING = 2;

    public KelpEspMod(String name) {
        super(name, "Kelp grown up to the surface = player activity", 0x3DDC97, 1, new int[]{1, 5, 10, 15, 20});
    }

    @Override
    protected boolean isPart(BlockState state) {
        return state.getBlock() == Blocks.KELP || state.getBlock() == Blocks.KELP_PLANT;
    }

    @Override
    protected boolean chunkView() {
        return true;
    }

    /** Ueber der Spitze hoechstens MAX_MISSING Wasserbloecke, danach Luft (Wasseroberflaeche). */
    @Override
    protected boolean accepts(ClientLevel level, BlockPos top) {
        BlockPos.MutableBlockPos cursor = top.mutable();
        for (int missing = 0; missing <= MAX_MISSING; missing++) {
            BlockState above = level.getBlockState(cursor.move(0, 1, 0));
            if (above.isAir()) {
                return true;
            }
            if (above.getFluidState().isEmpty()) {
                return false; // Eis, Stein o.ae. darueber: keine freie Oberflaeche
            }
        }
        return false;
    }
}
