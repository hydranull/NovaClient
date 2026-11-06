package nova.nova_client.client.farmbuilder;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Werkzeuge, die der Bot zum Abbauen braucht (Hilfsbloecke, Bloecke im Weg, Ackerboden). Er nimmt sich aus den
 * Kisten jeweils das schnellste und benutzt beim Abbauen automatisch das passende.
 */
public final class Tools {
    public enum Kind {
        PICKAXE("Pickaxe", ItemTags.PICKAXES, Items.IRON_PICKAXE, Blocks.COBBLESTONE.defaultBlockState(),
                "breaks cobble/stone helper blocks and stone in the way"),
        SHOVEL("Shovel", ItemTags.SHOVELS, Items.IRON_SHOVEL, Blocks.DIRT.defaultBlockState(),
                "breaks dirt helper blocks and dirt/sand in the way"),
        AXE("Axe", ItemTags.AXES, Items.IRON_AXE, Blocks.OAK_LOG.defaultBlockState(),
                "breaks wood in the way"),
        HOE("Hoe", ItemTags.HOES, Items.IRON_HOE, Blocks.HAY_BLOCK.defaultBlockState(),
                "turns dirt into farmland");

        public final String label;
        public final TagKey<Item> tag;
        public final Item icon;
        public final BlockState sample;
        public final String purpose;

        Kind(String label, TagKey<Item> tag, Item icon, BlockState sample, String purpose) {
            this.label = label;
            this.tag = tag;
            this.icon = icon;
            this.sample = sample;
            this.purpose = purpose;
        }

        public boolean matches(ItemStack stack) {
            return !stack.isEmpty() && stack.is(tag) && usable(stack);
        }
    }

    /** Ab so wenig Rest-Haltbarkeit wird ein Werkzeug nicht mehr benutzt (sonst geht es kaputt). */
    public static final int MIN_DURABILITY = 8;

    private Tools() {
    }

    /** Darf das Item benutzt werden, ohne dass es kaputtgeht? (Nicht-Werkzeuge immer.) */
    public static boolean usable(ItemStack stack) {
        return !stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > MIN_DURABILITY;
    }

    /** Welche Werkzeuge braucht dieser Bau? Spitzhacke und Schaufel immer (Hilfsbloecke, Aufraeumen). */
    public static List<Kind> needed(BuildPlan plan) {
        List<Kind> kinds = new ArrayList<>();
        kinds.add(Kind.PICKAXE);
        kinds.add(Kind.SHOVEL);
        if (plan != null && plan.needsHoe) {
            kinds.add(Kind.HOE);
        }
        return kinds;
    }

    public static boolean inInventory(LocalPlayer player, Kind kind) {
        for (int i = 0; i < 36; i++) {
            if (kind.matches(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /** Schnellstes Werkzeug dieser Art in den Kisten (null = keins gesehen). */
    public static Item bestInChests(ChestStore store, Kind kind) {
        Item best = null;
        float bestSpeed = 0.0F;
        for (Item item : store.allItems()) {
            ItemStack stack = new ItemStack(item);
            if (!kind.matches(stack)) {
                continue;
            }
            float speed = stack.getDestroySpeed(kind.sample);
            if (best == null || speed > bestSpeed) {
                best = item;
                bestSpeed = speed;
            }
        }
        return best;
    }
}
