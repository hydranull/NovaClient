package nova.nova_client.client.mods;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Zeigt lange Ranken. Ranken wachsen nur langsam nach unten, wenn der Chunk geladen ist:
 * sehr lange Ranken deuten auf viel Spieleraktivitaet in der Gegend hin.
 */
public class VineEspMod extends ColumnEspMod {
    public VineEspMod(String name) {
        super(name, "Long grown vines = player activity", 0x8BD450, 10, new int[]{6, 10, 15, 20, 30});
    }

    @Override
    protected boolean isPart(BlockState state) {
        return state.getBlock() == Blocks.VINE;
    }
}
