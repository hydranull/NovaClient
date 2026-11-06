package nova.nova_client.client.farmbuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die ausgewaehlten Material-Kisten und was zuletzt darin gesehen wurde. Der Inhalt wird jedes Mal aktualisiert,
 * wenn eine der Kisten offen ist (egal ob vom Bot oder von dir).
 */
public final class ChestStore {
    private final List<BlockPos> chests = new ArrayList<>();
    private final Map<BlockPos, Map<Item, Integer>> contents = new HashMap<>();

    public List<BlockPos> chests() {
        return chests;
    }

    public boolean isEmpty() {
        return chests.isEmpty();
    }

    public void clear() {
        chests.clear();
        contents.clear();
    }

    /** Doppelkisten zaehlen nur einmal: immer die kleinere der beiden Positionen benutzen. */
    public static BlockPos canonical(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            return other.asLong() < pos.asLong() ? other : pos;
        }
        return pos;
    }

    public static boolean isStorage(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof Container;
    }

    /** Fuegt die Kiste hinzu (true) oder entfernt sie, wenn sie schon drin war (false). */
    public boolean add(Level level, BlockPos pos) {
        BlockPos key = canonical(level, pos).immutable();
        if (chests.contains(key)) {
            return false;
        }
        chests.add(key);
        return true;
    }

    public void remove(BlockPos pos) {
        chests.remove(pos);
        contents.remove(pos);
    }

    public boolean contains(Level level, BlockPos pos) {
        return chests.contains(canonical(level, pos));
    }

    public boolean indexed(BlockPos pos) {
        return contents.containsKey(pos);
    }

    /** Inhalt der gerade offenen Kiste merken. */
    public void snapshot(Level level, BlockPos pos, AbstractContainerMenu menu, LocalPlayer player) {
        BlockPos key = canonical(level, pos);
        if (!chests.contains(key)) {
            return;
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        contents.put(key, counts);
    }

    public int available(Item item) {
        int total = 0;
        for (Map<Item, Integer> map : contents.values()) {
            total += map.getOrDefault(item, 0);
        }
        return total;
    }

    /** Alle Items, die zuletzt in irgendeiner der Kisten lagen. */
    public java.util.Set<Item> allItems() {
        java.util.Set<Item> items = new java.util.HashSet<>();
        for (Map<Item, Integer> map : contents.values()) {
            for (Map.Entry<Item, Integer> e : map.entrySet()) {
                if (e.getValue() > 0) {
                    items.add(e.getKey());
                }
            }
        }
        return items;
    }

    public int count(BlockPos chest, Item item) {
        Map<Item, Integer> map = contents.get(chest);
        return map == null ? 0 : map.getOrDefault(item, 0);
    }

    public int unindexedCount() {
        int n = 0;
        for (BlockPos pos : chests) {
            if (!contents.containsKey(pos)) {
                n++;
            }
        }
        return n;
    }

    /** Positionen als Text fuer die Config ("x,y,z;x,y,z"). */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (BlockPos pos : chests) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        return sb.toString();
    }

    public void deserialize(String text) {
        clear();
        if (text == null || text.isBlank()) {
            return;
        }
        for (String part : text.split(";")) {
            String[] xyz = part.split(",");
            if (xyz.length == 3) {
                try {
                    chests.add(new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()),
                            Integer.parseInt(xyz[2].trim())));
                } catch (NumberFormatException ignored) {
                    // kaputter Eintrag
                }
            }
        }
    }

    /** Naechste Kiste zur Position (fuer Entfernungen in der Zeitschaetzung). */
    public double averageDistance(BlockPos from) {
        if (chests.isEmpty() || from == null) {
            return 12.0D;
        }
        double sum = 0.0D;
        for (BlockPos pos : chests) {
            sum += Math.sqrt(pos.distSqr(from));
        }
        return sum / chests.size();
    }

    public static boolean playerHasScreen(Minecraft client) {
        return client.gui.screen() != null;
    }
}
