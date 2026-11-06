package nova.nova_client.client.toolskins;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.ToolSkinsMod;

import java.util.HashSet;
import java.util.Set;

/**
 * Hyper Tools: alle Schwerter werden zu Laserschwertern (Farbe = Seltenheit). Haengt sich nach dem Modell-Backen um
 * die Vanilla-Modelle der Schwerter (ToolSkinItemModel). Ist das Modul aus, zeichnet das Original - Umschalten braucht
 * kein Neuladen der Ressourcen.
 */
public final class ToolSkins {
    private static final String[] MATERIALS = {"wooden", "stone", "copper", "iron", "golden", "diamond", "netherite"};
    private static final Set<String> ITEMS = new HashSet<>();

    static {
        for (String material : MATERIALS) {
            ITEMS.add(material + "_sword");
        }
    }

    private ToolSkins() {
    }

    public static void register() {
        ModelLoadingPlugin.register(plugin -> plugin.modifyItemModelAfterBake().register((model, context) -> {
            Identifier id = context.itemId();
            if (id != null && "minecraft".equals(id.getNamespace()) && ITEMS.contains(id.getPath())) {
                return new ToolSkinItemModel(model, id.getPath());
            }
            return model;
        }));
    }

    /** Ist das ein Schwert, das als Laserschwert erscheint (Modul an oder aus)? */
    public static boolean isSaber(ItemStack stack) {
        return !stack.isEmpty() && "minecraft".equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace())
                && ITEMS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
    }

    /** Soll diese Hand in der Ego-Sicht die Laserschwert-Bewegungen statt des Vanilla-Schwungs bekommen? */
    public static boolean moves(ItemStack stack, InteractionHand hand) {
        return ToolSkinsMod.isEnabled() && ToolSkinsMod.swingMoves && hand == InteractionHand.MAIN_HAND && isSaber(stack);
    }

    /**
     * Aus ToolSkinsHandMixin: Hoehe der Hand (0 = oben, 1 = ganz unten) fuer diese Hand. Beim Ziehen eines
     * Laserschwerts kommt sie aus der eigenen Animation, sonst bleibt der Vanilla-Wert.
     */
    public static float armHeight(AbstractClientPlayer player, InteractionHand hand, ItemStack visible, float attack,
                                  float inverseArmHeight) {
        if (!ToolSkinsMod.isEnabled() || player == null || player != Minecraft.getInstance().player) {
            return inverseArmHeight;
        }
        int side = (hand == InteractionHand.MAIN_HAND) == (player.getMainArm() == HumanoidArm.RIGHT) ? 0 : 1;
        ItemStack held = hand == InteractionHand.MAIN_HAND ? player.getMainHandItem() : player.getOffhandItem();
        boolean saber = isSaber(visible);
        return ToolSkinDraw.armHeight(side, saber && ItemStack.isSameItem(visible, held), attack, inverseArmHeight);
    }

    /** "Preview Draw" in den Einstellungen: Zieh-Animation beim naechsten Bild neu starten. */
    public static void replayDraw() {
        ToolSkinDraw.reset();
    }

    /** Modul an/aus: Animationen zuruecksetzen; Modelle schon mal laden. */
    public static void onToggle(boolean enabled) {
        ToolSkinDraw.reset();
        SaberMoves.reset();
        if (enabled) {
            ToolSkinAssets.mesh("iron_sword");
        }
    }
}
