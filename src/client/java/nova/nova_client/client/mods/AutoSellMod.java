package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.AutoSellMenu;
import nova.nova_client.client.ModCategory;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Auto Sell (DonutSMP): oeffnet /sell, legt passende Items per Shift-Klick in das Verkaufsmenue und schliesst es -
 * beim Schliessen verkauft der Server den Inhalt. Ist das Menue voll, wird es fuer die naechste Runde neu geoeffnet.
 * Wenn nichts mehr zu verkaufen ist (oder es nicht weitergeht), schaltet sich das Modul selbst aus.
 */
public class AutoSellMod extends GameTickMod {
    private static final String COMMAND = "sell";
    /** So lange (Ticks) auf das Verkaufsmenue warten, bevor abgebrochen wird. */
    private static final int MENU_TIMEOUT = 60;
    /** Pause nach dem Oeffnen/Schliessen, damit der Server hinterherkommt. */
    private static final int SETTLE_TICKS = 20;
    public static final int[] DELAYS = {0, 1, 2, 5, 10};

    // Einstellungen
    /** true = nur die Items der Liste verkaufen, false = alles ausser den Items der Liste. */
    public static boolean whitelist = true;
    /** Item-IDs, durch Komma getrennt (z.B. "minecraft:sea_pickle, cactus"). */
    public static String items = "minecraft:sea_pickle";
    public static int delayTicks = 1;
    public static boolean chatMessages = true;
    /** Die Hotbar nie verkaufen (schuetzt Werkzeug/Waffen, vor allem im Blacklist-Modus). */
    public static boolean keepHotbar = true;

    private enum Phase {
        OPEN, WAIT_MENU, DEPOSIT
    }

    private static Set<String> itemIds = parse(items);
    private static String parsedFrom = items;

    private Phase phase = Phase.OPEN;
    private int cooldown;
    private int waited;
    private int soldTotal;
    private int movedThisRound;

    public AutoSellMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        phase = Phase.OPEN;
        cooldown = 0;
        waited = 0;
        soldTotal = 0;
        movedThisRound = 0;
    }

    // ---------------------------------------------------------------- Item-Auswahl

    /** "sea_pickle, minecraft:cactus" -> {"minecraft:sea_pickle", "minecraft:cactus"} */
    public static Set<String> parse(String text) {
        Set<String> ids = new LinkedHashSet<>();
        for (String part : text.split("[,;\\s]+")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                ids.add(id.contains(":") ? id : "minecraft:" + id);
            }
        }
        return ids;
    }

    public static Set<String> itemIds() {
        if (!parsedFrom.equals(items)) {
            parsedFrom = items;
            itemIds = parse(items);
        }
        return itemIds;
    }

    public static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean shouldSell(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        boolean listed = itemIds().contains(idOf(stack));
        return whitelist == listed;
    }

    /** Darf dieser Slot (aus dem Spieler-Inventar) verkauft werden? */
    private static boolean sellable(LocalPlayer player, Slot slot) {
        if (slot.container != player.getInventory()) {
            return false;
        }
        if (keepHotbar && slot.getContainerSlot() < 9) {
            return false;
        }
        return shouldSell(slot.getItem());
    }

    private static boolean hasSomethingToSell(LocalPlayer player) {
        for (Slot slot : player.inventoryMenu.slots) {
            if (sellable(player, slot)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Ablauf

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        switch (phase) {
            case OPEN -> {
                if (!hasSomethingToSell(player)) {
                    finish(client, soldTotal > 0 ? "Sold " + soldTotal + " items." : "Nothing to sell.", false);
                    return;
                }
                player.connection.sendCommand(COMMAND);
                phase = Phase.WAIT_MENU;
                waited = 0;
                movedThisRound = 0;
            }
            case WAIT_MENU -> {
                if (player.containerMenu instanceof ChestMenu) {
                    phase = Phase.DEPOSIT;
                    cooldown = Math.max(2, delayTicks); // Inhalt vom Server abwarten
                } else if (++waited > MENU_TIMEOUT) {
                    finish(client, "The /sell menu did not open.", true);
                }
            }
            case DEPOSIT -> deposit(client, player);
        }
    }

    private void deposit(Minecraft client, LocalPlayer player) {
        if (!(player.containerMenu instanceof ChestMenu menu)) {
            // Menue wurde geschlossen (vom Server oder von Hand): naechste Runde
            nextRound();
            return;
        }
        int sellSlots = menu.getRowCount() * 9;
        boolean hasRoom = false;
        for (int i = 0; i < sellSlots; i++) {
            if (menu.getSlot(i).getItem().isEmpty()) {
                hasRoom = true;
                break;
            }
        }
        if (hasRoom) {
            for (int i = sellSlots; i < menu.slots.size(); i++) {
                Slot slot = menu.getSlot(i);
                if (sellable(player, slot)) {
                    int count = slot.getItem().getCount();
                    client.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, player);
                    soldTotal += count;
                    movedThisRound++;
                    cooldown = delayTicks;
                    return;
                }
            }
        }
        // Menue voll oder nichts mehr zum Einlegen: schliessen = verkaufen
        player.closeContainer();
        if (movedThisRound == 0) {
            finish(client, hasRoom ? "Sold " + soldTotal + " items." : "The sell menu has no free slots.", !hasRoom);
            return;
        }
        nextRound();
    }

    private void nextRound() {
        phase = Phase.OPEN;
        cooldown = SETTLE_TICKS;
    }

    private void finish(Minecraft client, String message, boolean problem) {
        if (chatMessages && client.player != null) {
            Compat.chat(client.player, Component.literal("[Nova] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("Auto Sell: " + message)
                            .withStyle(problem ? ChatFormatting.RED : ChatFormatting.GRAY)));
        }
        if (active) {
            toggle(client);
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new AutoSellMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "whitelist", String.valueOf(whitelist));
        properties.setProperty(prefix + "items", items);
        properties.setProperty(prefix + "delay", String.valueOf(delayTicks));
        properties.setProperty(prefix + "chat", String.valueOf(chatMessages));
        properties.setProperty(prefix + "keepHotbar", String.valueOf(keepHotbar));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        whitelist = Boolean.parseBoolean(properties.getProperty(prefix + "whitelist", String.valueOf(whitelist)));
        items = properties.getProperty(prefix + "items", items);
        try {
            delayTicks = Math.max(0, Math.min(20, Integer.parseInt(properties.getProperty(prefix + "delay",
                    String.valueOf(delayTicks)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        chatMessages = Boolean.parseBoolean(properties.getProperty(prefix + "chat", String.valueOf(chatMessages)));
        keepHotbar = Boolean.parseBoolean(properties.getProperty(prefix + "keepHotbar", String.valueOf(keepHotbar)));
    }
}
