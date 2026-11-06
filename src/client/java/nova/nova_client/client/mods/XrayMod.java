package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import nova.nova_client.client.ModCategory;

import java.util.Set;

public class XrayMod extends GameTickMod {
    private static final Set<Block> VISIBLE_BLOCKS = Set.of(
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.NETHER_QUARTZ_ORE, Blocks.ANCIENT_DEBRIS,
            Blocks.SPAWNER, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL
    );

    // volatile: wird von den Chunk-Builder-Threads gelesen
    private static volatile boolean enabled;

    public XrayMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        XrayMod.enabled = enabled;
        // Alle Chunks neu bauen, sonst wird die Aenderung erst beim naechsten Chunk-Update sichtbar
        if (client != null && client.level != null) {
            client.levelExtractor.allChanged();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean shouldRender(BlockState state) {
        return VISIBLE_BLOCKS.contains(state.getBlock());
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public net.minecraft.client.gui.screens.Screen createSettings(net.minecraft.client.gui.screens.Screen parent) {
        return new nova.nova_client.client.XrayMenu(parent);
    }

    @Override
    public void saveSettings(java.util.Properties properties, String prefix) {
        properties.setProperty(prefix + "hideFakes", String.valueOf(nova.nova_client.client.AntiXray.hideFakes));
        properties.setProperty(prefix + "minCaveSize", String.valueOf(nova.nova_client.client.AntiXray.minCaveSize));
    }

    @Override
    public void loadSettings(java.util.Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "hideFakes");
        if (value != null) {
            nova.nova_client.client.AntiXray.hideFakes = Boolean.parseBoolean(value);
        }
        String size = properties.getProperty(prefix + "minCaveSize");
        if (size != null) {
            try {
                nova.nova_client.client.AntiXray.minCaveSize = Math.max(nova.nova_client.client.AntiXray.MIN_CAVE_SIZE_MIN,
                        Math.min(nova.nova_client.client.AntiXray.MIN_CAVE_SIZE_MAX, Integer.parseInt(size.trim())));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
