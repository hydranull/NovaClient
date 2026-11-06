package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import nova.nova_client.client.AuctionSniperMenu;
import nova.nova_client.client.ModCategory;

import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auction-House-Sniper fuer DonutSMP: oeffnet regelmaessig "/ah <Item>", sucht das guenstigste passende
 * Angebot und kauft es sofort, wenn der Preis unter dem eingestellten Maximum liegt.
 *
 * Ablauf: /ah senden -> warten bis das Menue geladen ist -> guenstigstes Angebot anklicken
 * -> im Bestaetigungsmenue den Bestaetigen-Knopf klicken -> Menue schliessen und wieder von vorn.
 */
public class AuctionSniperMod extends GameTickMod {
    public static final int[] INTERVAL_PRESETS = {20, 40, 60, 100};

    /** Gesuchtes Item (Name oder ID, z.B. "elytra" oder "diamond sword"). */
    public static String item = "";
    /** Hoechstpreis, zu dem gekauft wird. */
    public static double maxPrice = 0;
    /** Ticks zwischen zwei Suchen. */
    public static int interval = 60;
    /** Nach dem ersten Kauf ausschalten. */
    public static boolean stopAfterBuy = true;
    /** Kauf selbst bestaetigen; aus = Bestaetigungsmenue fuer den Spieler offen lassen. */
    public static boolean autoConfirm = true;

    /** Letzte Statusmeldung fuer das Einstellungsmenue. */
    public static String status = "Idle";

    /** Ticks nach dem Oeffnen eines Menues, bevor der Inhalt gelesen wird. */
    private static final int LOAD_DELAY = 4;
    /** Nach so vielen Ticks ohne Menue wird abgebrochen und neu gesucht. */
    private static final int TIMEOUT = 60;
    /** So lange wird nach einem Bestaetigungsklick auf ein weiteres Fenster ("Yes") gewartet. */
    private static final int FOLLOW_UP = 20;
    /** Hoechstens so viele Bestaetigungsfenster nacheinander. */
    private static final int MAX_CONFIRMS = 3;

    private static final Pattern PRICE = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> CONFIRM_ITEMS = Set.of(
            "lime_stained_glass_pane", "green_stained_glass_pane", "lime_stained_glass", "green_stained_glass",
            "lime_concrete", "green_concrete", "lime_wool", "green_wool", "lime_terracotta", "green_terracotta");

    /** Namen von Bestaetigen-Knoepfen (klein geschrieben). */
    private static final String[] CONFIRM_WORDS = {"confirm", "purchase", "buy", "accept", "yes", "bestätigen", "kaufen"};
    /** Knoepfe, die nie der Bestaetigen-Knopf sind. */
    private static final String[] NOT_CONFIRM_WORDS = {"quick", "cancel", "back", "abbrechen", "zurück"};

    private enum State { IDLE, WAIT_AH, WAIT_CONFIRM, MANUAL }

    private State state = State.IDLE;
    private int cooldown;
    private int waitTicks;
    private int menuId = -1;
    private int menuTicks;
    private boolean warnedNoPrices;
    private boolean loggedConfirm;
    private String pendingName = "";
    private double pendingPrice;
    /** Auction-House-Fenster und angeklickter Slot, um das Bestaetigungsmenue davon zu unterscheiden. */
    private int ahContainerId = -1;
    private int clickedSlot = -1;
    private ItemStack clickedStack = ItemStack.EMPTY;
    /** Anzahl bereits geklickter Bestaetigungsknoepfe fuer den aktuellen Kauf. */
    private int confirmClicks;

    public AuctionSniperMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        state = State.IDLE;
        cooldown = 0;
        warnedNoPrices = false;
        loggedConfirm = false;
        if (enabled && (item.isBlank() || maxPrice <= 0)) {
            message(client, "Set an item and a max price first.");
            active = false;
            status = "Missing item or price";
            return;
        }
        status = enabled ? "Searching \"" + item + "\" under $" + formatPrice(maxPrice) : "Idle";
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            state = State.IDLE;
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        boolean inContainer = menu != player.inventoryMenu;
        if (inContainer && menu.containerId != menuId) {
            menuId = menu.containerId;
            menuTicks = 0;
        }
        menuTicks++;

        switch (state) {
            case IDLE -> {
                if (cooldown > 0) {
                    cooldown--;
                    return;
                }
                // Nur starten, wenn kein anderes Menue offen ist (Chat, Kiste, ...)
                if (client.gui.screen() != null) {
                    return;
                }
                player.connection.sendCommand("ah " + item.trim());
                state = State.WAIT_AH;
                waitTicks = 0;
                menuId = -1;
            }
            case WAIT_AH -> {
                if (++waitTicks > TIMEOUT) {
                    restart(player);
                    return;
                }
                if (!inContainer || menuTicks < LOAD_DELAY) {
                    return;
                }
                scanAndClick(client, player, menu);
            }
            case WAIT_CONFIRM -> {
                waitTicks++;
                // Schon bestaetigt: fertig, wenn das Menue zu ist, kein weiteres Fenster kommt oder genug geklickt wurde
                if (confirmClicks > 0 && (!inContainer || waitTicks > FOLLOW_UP || confirmClicks >= MAX_CONFIRMS)) {
                    finishPurchase(client, player);
                    return;
                }
                if (waitTicks > TIMEOUT) {
                    status = "No confirm button found";
                    if (inContainer && !loggedConfirm) {
                        loggedConfirm = true;
                        message(client, "Could not find the confirm button. See the log for the menu content.");
                        logMenu(player, menu);
                    }
                    restart(player);
                    return;
                }
                // auch warten, falls der Server das Bestaetigungsmenue im selben Fenster anzeigt
                if (!inContainer || menuTicks < LOAD_DELAY || waitTicks < LOAD_DELAY) {
                    return;
                }
                confirm(client, player, menu);
            }
            case MANUAL -> {
                // Spieler bestaetigt selbst; weitersuchen, sobald das Menue geschlossen ist
                if (client.gui.screen() == null && !inContainer) {
                    state = State.IDLE;
                    cooldown = interval;
                }
            }
        }
    }

    /** Sucht das guenstigste passende Angebot und klickt es an. */
    private void scanAndClick(Minecraft client, LocalPlayer player, AbstractContainerMenu menu) {
        String query = item.trim().toLowerCase(Locale.ROOT);
        int bestSlot = -1;
        double bestPrice = Double.MAX_VALUE;
        String bestName = "";
        int priced = 0;

        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            double price = readPrice(stack);
            if (price < 0) {
                continue;
            }
            priced++;
            if (matches(stack, query) && price < bestPrice) {
                bestPrice = price;
                bestSlot = slot.index;
                bestName = stack.getHoverName().getString();
            }
        }

        // Angebote kommen oft etwas spaeter als die Knoepfe unten -> noch etwas warten
        if (priced == 0 && waitTicks < TIMEOUT / 2) {
            return;
        }
        if (priced == 0 && !warnedNoPrices) {
            // Wahrscheinlich hat sich das Lore-Format geaendert -> Hinweis und Lore ins Log
            warnedNoPrices = true;
            message(client, "Could not read any prices in this menu. See the log for the item lore.");
            logLore(player, menu);
        }

        if (bestSlot < 0 || bestPrice > maxPrice) {
            status = bestSlot < 0 ? "No offers found" : "Cheapest: $" + formatPrice(bestPrice);
            restart(player);
            return;
        }

        pendingName = bestName;
        pendingPrice = bestPrice;
        ahContainerId = menu.containerId;
        clickedSlot = bestSlot;
        clickedStack = menu.getSlot(bestSlot).getItem().copy();
        client.gameMode.handleContainerInput(menu.containerId, bestSlot, 0, ContainerInput.PICKUP, player);
        waitTicks = 0;
        confirmClicks = 0;

        if (autoConfirm) {
            status = "Buying " + bestName + " for $" + formatPrice(bestPrice);
            state = State.WAIT_CONFIRM;
        } else {
            status = "Found " + bestName + " for $" + formatPrice(bestPrice) + " - confirm it yourself";
            message(client, "Found " + bestName + " for $" + formatPrice(bestPrice) + " - confirm it yourself!");
            state = State.MANUAL;
            if (stopAfterBuy) {
                active = false;
            }
        }
    }

    /**
     * Klickt im Bestaetigungsfenster auf "Confirm"/"Yes". Kommt danach noch ein Fenster (z.B. "Are you sure?"),
     * wird dort ebenfalls bestaetigt - bis zu {@link #MAX_CONFIRMS} Fenster nacheinander.
     */
    private void confirm(Minecraft client, LocalPlayer player, AbstractContainerMenu menu) {
        // Fenster hat sich seit dem letzten Klick nicht veraendert -> naechstes Fenster noch nicht da
        if (menu.containerId == ahContainerId && clickedSlot < menu.slots.size()
                && ItemStack.matches(menu.getSlot(clickedSlot).getItem(), clickedStack)) {
            return;
        }

        int confirmSlot = -1;
        int colorSlot = -1;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
            // Sicherheitscheck: Preis des gekauften Items im Bestaetigungsmenue erneut pruefen
            double price = readPrice(stack);
            if (confirmClicks == 0 && price > maxPrice && name.equals(pendingName.toLowerCase(Locale.ROOT))) {
                status = "Price changed to $" + formatPrice(price) + ", cancelled";
                restart(player);
                return;
            }
            if (confirmSlot < 0 && containsAny(name, CONFIRM_WORDS) && !containsAny(name, NOT_CONFIRM_WORDS)) {
                confirmSlot = slot.index;
            }
            if (colorSlot < 0 && CONFIRM_ITEMS.contains(itemId(stack)) && !containsAny(name, NOT_CONFIRM_WORDS)) {
                colorSlot = slot.index;
            }
        }
        if (confirmSlot < 0) {
            confirmSlot = colorSlot;
        }
        if (confirmSlot < 0) {
            return; // Menue evtl. noch nicht geladen, weiter warten bis Timeout
        }

        logMenu(player, menu);
        System.out.println("[Nova] -> clicking slot " + confirmSlot);
        // Merken, was geklickt wurde, damit das naechste Fenster davon unterschieden werden kann
        ahContainerId = menu.containerId;
        clickedSlot = confirmSlot;
        clickedStack = menu.getSlot(confirmSlot).getItem().copy();
        client.gameMode.handleContainerInput(menu.containerId, confirmSlot, 0, ContainerInput.PICKUP, player);
        confirmClicks++;
        waitTicks = 0;
        status = "Confirming (" + confirmClicks + ")...";
    }

    /** Kauf ist bestaetigt: Meldung, Menue schliessen, ggf. ausschalten. */
    private void finishPurchase(Minecraft client, LocalPlayer player) {
        message(client, "Bought " + pendingName + " for $" + formatPrice(pendingPrice));
        status = "Bought " + pendingName + " for $" + formatPrice(pendingPrice);
        confirmClicks = 0;
        restart(player);
        if (stopAfterBuy) {
            active = false;
        }
    }

    private void restart(LocalPlayer player) {
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        state = State.IDLE;
        cooldown = interval;
    }

    // ---------------------------------------------------------------- Hilfen

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** Schreibt alle Items des Menues mit Slot, ID, Name und Lore ins Log. */
    private static void logMenu(LocalPlayer player, AbstractContainerMenu menu) {
        System.out.println("[Nova] Confirm menu content:");
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() || slot.getItem().isEmpty()) {
                continue;
            }
            ItemStack stack = slot.getItem();
            System.out.println("[Nova] slot " + slot.index + " " + itemId(stack) + ": " + stack.getHoverName().getString());
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    System.out.println("[Nova]   " + line.getString());
                }
            }
        }
    }

    private static boolean matches(ItemStack stack, String query) {
        if (query.isEmpty()) {
            return false;
        }
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        String id = itemId(stack).replace('_', ' ');
        return name.contains(query) || id.contains(query.replace('_', ' '));
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** Liest den Preis aus der Lore (DonutSMP: "$ 1.5K", sonst "Price: $1.5K"), -1 wenn keiner gefunden. */
    static double readPrice(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return -1;
        }
        for (Component line : lore.lines()) {
            String text = line.getString().trim();
            int index = text.toLowerCase(Locale.ROOT).indexOf("price");
            String rest = index >= 0 ? text.substring(index + 5) : text.startsWith("$") ? text.substring(1) : null;
            if (rest != null) {
                double price = parsePrice(rest);
                if (price >= 0) {
                    return price;
                }
            }
        }
        return -1;
    }

    /** "1,500", "$1.5K", "2m" -> Zahl; -1 wenn ungueltig. */
    public static double parsePrice(String text) {
        Matcher matcher = PRICE.matcher(text);
        if (!matcher.find()) {
            return -1;
        }
        try {
            double value = Double.parseDouble(matcher.group(1).replace(",", ""));
            String suffix = matcher.group(2);
            if (suffix != null) {
                value *= switch (suffix.toLowerCase(Locale.ROOT)) {
                    case "k" -> 1e3;
                    case "m" -> 1e6;
                    case "b" -> 1e9;
                    default -> 1e12;
                };
            }
            return value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String formatPrice(double value) {
        String[] suffixes = {"", "K", "M", "B", "T"};
        int i = 0;
        while (value >= 1000 && i < suffixes.length - 1) {
            value /= 1000;
            i++;
        }
        String number = value == Math.floor(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        return number + suffixes[i];
    }

    private static void logLore(LocalPlayer player, AbstractContainerMenu menu) {
        int shown = 0;
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() || slot.getItem().isEmpty() || shown >= 5) {
                continue;
            }
            shown++;
            System.out.println("[Nova] AH item: " + slot.getItem().getHoverName().getString());
            ItemLore lore = slot.getItem().get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    System.out.println("[Nova]   " + line.getString());
                }
            }
        }
    }

    private static void message(Minecraft client, String text) {
        client.gui.chatListener().handleSystemMessage(Component.literal("§d[Sniper]§r " + text), false);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new AuctionSniperMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "item", item);
        properties.setProperty(prefix + "maxPrice", String.valueOf(maxPrice));
        properties.setProperty(prefix + "interval", String.valueOf(interval));
        properties.setProperty(prefix + "stopAfterBuy", String.valueOf(stopAfterBuy));
        properties.setProperty(prefix + "autoConfirm", String.valueOf(autoConfirm));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        item = properties.getProperty(prefix + "item", item);
        try {
            maxPrice = Double.parseDouble(properties.getProperty(prefix + "maxPrice", String.valueOf(maxPrice)));
            interval = Math.max(10, Integer.parseInt(properties.getProperty(prefix + "interval", String.valueOf(interval))));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        stopAfterBuy = Boolean.parseBoolean(properties.getProperty(prefix + "stopAfterBuy", String.valueOf(stopAfterBuy)));
        autoConfirm = Boolean.parseBoolean(properties.getProperty(prefix + "autoConfirm", String.valueOf(autoConfirm)));
    }
}
