package nova.nova_client.client.farmbuilder;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/** Was der Bot essen darf, wenn er bei grossen Bauten hungrig wird. */
public final class Food {
    /** Macht krank, teleportiert oder ist zu wertvoll zum Nebenbei-Essen. */
    private static final Set<Item> AVOID = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
            Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.CHICKEN, Items.GOLDEN_APPLE,
            Items.ENCHANTED_GOLDEN_APPLE);

    private Food() {
    }

    public static boolean edible(ItemStack stack) {
        return !stack.isEmpty() && !AVOID.contains(stack.getItem()) && stack.get(DataComponents.FOOD) != null;
    }

    public static boolean edible(Item item) {
        return edible(new ItemStack(item));
    }

    /** Hungerpunkte (0 = kein Essen). */
    public static int nutrition(ItemStack stack) {
        FoodProperties food = edible(stack) ? stack.get(DataComponents.FOOD) : null;
        return food == null ? 0 : food.nutrition();
    }
}
