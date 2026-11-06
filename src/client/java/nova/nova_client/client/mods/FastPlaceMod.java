package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.BlockItem;
import nova.nova_client.client.ModCategory;
import nova.nova_client.mixin.client.MinecraftAccessor;

/**
 * Fast Place: Mit gedrueckter rechter Maustaste und einem Block in der Hand werden Bloecke ohne die normale
 * Wartezeit (4 Ticks) gesetzt - also jeden Tick einer.
 */
public class FastPlaceMod extends GameTickMod {
    public FastPlaceMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        if (client.player == null) {
            return;
        }
        boolean holdingBlock = client.player.getMainHandItem().getItem() instanceof BlockItem
                || client.player.getOffhandItem().getItem() instanceof BlockItem;
        if (holdingBlock) {
            ((MinecraftAccessor) client).nova_client$setRightClickDelay(0);
        }
    }
}
