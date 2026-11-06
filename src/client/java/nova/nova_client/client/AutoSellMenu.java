package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.AutoSellMod;

import java.util.List;
import java.util.Set;

/**
 * Einstellungen fuer Auto Sell: Modus (Whitelist/Blacklist), Item-Liste (IDs, Komma-getrennt, "Add held item"),
 * Pause zwischen den Klicks, Chat-Meldungen und Hotbar-Schutz.
 */
public class AutoSellMenu extends ModOptionsMenu {
    private static final String[] MODES = {"Whitelist", "Blacklist"};

    public AutoSellMenu(Screen parent) {
        super(parent, "Auto Sell", List.of(
                new Section("Items"),
                new Segmented("Mode", MODES, () -> AutoSellMod.whitelist ? 0 : 1, i -> AutoSellMod.whitelist = i == 0),
                new Text("Items", () -> AutoSellMod.items, v -> AutoSellMod.items = v, 1000),
                new Actions(List.of(
                        new Action(() -> "Add held item", AutoSellMenu::addHeldItem),
                        new Action(() -> "Clear", () -> AutoSellMod.items = ""))),
                new Info(AutoSellMenu::summary),
                new Section("Selling"),
                new Segmented("Delay", delays(), AutoSellMenu::delay, i -> AutoSellMod.delayTicks = AutoSellMod.DELAYS[i]),
                new Toggle("Chat messages", () -> AutoSellMod.chatMessages, v -> AutoSellMod.chatMessages = v),
                new Toggle("Keep hotbar (never sell it)", () -> AutoSellMod.keepHotbar, v -> AutoSellMod.keepHotbar = v),
                new Info(() -> "Turns itself off when everything is sold.")));
    }

    /** Item in der Haupthand an die Liste anhaengen (falls noch nicht drin). */
    private static void addHeldItem() {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        ItemStack held = Minecraft.getInstance().player.getMainHandItem();
        if (held.isEmpty()) {
            return;
        }
        String id = AutoSellMod.idOf(held);
        if (AutoSellMod.parse(AutoSellMod.items).contains(id)) {
            return;
        }
        String current = AutoSellMod.items.trim();
        AutoSellMod.items = current.isEmpty() ? id : current + ", " + id;
    }

    private static String summary() {
        Set<String> ids = AutoSellMod.parse(AutoSellMod.items);
        return AutoSellMod.whitelist
                ? (ids.isEmpty() ? "Whitelist is empty: nothing will be sold" : "Sells only " + ids.size() + " listed item(s)")
                : "Sells EVERYTHING except " + ids.size() + " listed item(s)";
    }

    private static String[] delays() {
        String[] labels = new String[AutoSellMod.DELAYS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = AutoSellMod.DELAYS[i] + "t";
        }
        return labels;
    }

    private static int delay() {
        for (int i = 0; i < AutoSellMod.DELAYS.length; i++) {
            if (AutoSellMod.delayTicks == AutoSellMod.DELAYS[i]) {
                return i;
            }
        }
        return -1;
    }
}
