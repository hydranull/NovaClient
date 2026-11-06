package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;

/**
 * Stream Protect: versteckt beim Streamen/Bildschirm teilen, was du dabeihast. Hotbar, Inventar (auch in Kisten-Menues,
 * nur deine eigenen Plaetze), das Item an der Maus und die Neben-Hand zeigen stattdessen Totems, eine zufaellige
 * harmlose Ausruestung oder nichts. Dazu lassen sich die Tooltips deiner Items, der Item-Name ueber der Hotbar und die
 * Markierung des gewaehlten Hotbar-Platzes ausblenden. Gespielt wird ganz normal mit den echten Items.
 */
public class StreamProtectMod extends GameTickMod {
    public static final String[] STYLES = {"Totem", "Random", "Empty"};
    /** Slot-Nummer fuer das Item an der Maus (kein echter Inventarplatz). */
    private static final int CARRIED = 41;

    private static final Item[] HOTBAR = {
            Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.STONE_SWORD, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE,
            Items.STONE_PICKAXE, Items.IRON_AXE, Items.STONE_AXE, Items.IRON_SHOVEL, Items.BOW, Items.WATER_BUCKET,
            Items.COBBLESTONE, Items.TORCH, Items.COOKED_BEEF, Items.BREAD, Items.OAK_PLANKS, Items.DIRT,
            Items.FLINT_AND_STEEL, Items.CRAFTING_TABLE};
    private static final Item[] MAIN = {
            Items.COBBLESTONE, Items.STONE, Items.DIRT, Items.OAK_LOG, Items.OAK_PLANKS, Items.STICK, Items.COAL,
            Items.IRON_INGOT, Items.RAW_IRON, Items.REDSTONE, Items.ARROW, Items.STRING, Items.BONE, Items.ROTTEN_FLESH,
            Items.GUNPOWDER, Items.SAND, Items.GRAVEL, Items.WHEAT_SEEDS, Items.WHEAT, Items.LADDER, Items.GLASS,
            Items.LEATHER, Items.FEATHER, Items.FLINT, Items.APPLE, Items.BUCKET, Items.PAPER, Items.SUGAR_CANE,
            Items.FURNACE, Items.CHEST, Items.ANDESITE, Items.GRANITE, Items.DIORITE};
    private static final Item[] OFFHAND = {Items.SHIELD, Items.TORCH, Items.BREAD, Items.ARROW, Items.COOKED_BEEF};
    /** Ruestung: Fuesse, Beine, Brust, Kopf (Inventarplatz 36..39). */
    private static final Item[][] ARMOR = {
            {Items.IRON_BOOTS, Items.CHAINMAIL_BOOTS, Items.LEATHER_BOOTS},
            {Items.IRON_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.LEATHER_LEGGINGS},
            {Items.IRON_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.LEATHER_CHESTPLATE},
            {Items.IRON_HELMET, Items.CHAINMAIL_HELMET, Items.LEATHER_HELMET}};

    private static final String[] RECORDER_PROCS = {
            "obs64.exe", "obs32.exe", "obs.exe",
            "bdcam.exe", "fraps.exe",
            "action.exe", "d3dgear.exe", "captura.exe",
            "gamebar.exe", "gamebarftserver.exe",
            "medal.exe", "xsplitbroadcaster.exe", "streamlabs.exe"
    };

    // Einstellungen
    public static int style = 0;
    public static boolean fillEmpty = true;
    public static boolean hideTooltips = true;
    public static boolean hideItemName = true;
    public static boolean hideSelectedSlot = true;
    public static boolean hideCoords = true;
    public static boolean hideCrosshair = false;
    public static boolean autoDetect = false;

    private static volatile boolean enabled;
    private static volatile boolean autoActive;
    private int detectTick;
    /** Erst beim ersten Zeichnen anlegen (beim Laden der Mods sind die Items noch nicht fertig). */
    private static ItemStack totem;
    private static final ItemStack[] LOADOUT = new ItemStack[CARRIED + 1];

    public StreamProtectMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        if (!on) {
            autoActive = false;
        }
        if (on) {
            reseed();
        }
    }

    @Override
    public void execute(Minecraft client) {
        if (!autoDetect) {
            autoActive = false;
            return;
        }
        // Prozess-Scan einmal pro Sekunde (20 Ticks)
        if (++detectTick < 20) {
            return;
        }
        detectTick = 0;
        boolean found = ProcessHandle.allProcesses()
                .map(p -> p.info().command().orElse("").toLowerCase(Locale.ROOT))
                .anyMatch(cmd -> {
                    for (String proc : RECORDER_PROCS) {
                        if (cmd.endsWith(proc) || cmd.endsWith(proc.replace(".exe", ""))) {
                            return true;
                        }
                    }
                    return false;
                });
        if (found != autoActive) {
            autoActive = found;
            if (found) {
                reseed();
            }
        }
    }

    /** Neue zufaellige Ausruestung (fest, bis das Modul neu eingeschaltet wird - sonst flackert es). */
    private static void reseed() {
        Random random = new Random();
        for (int slot = 0; slot < LOADOUT.length; slot++) {
            Item item;
            if (slot < 9) {
                item = HOTBAR[random.nextInt(HOTBAR.length)];
            } else if (slot < 36) {
                item = MAIN[random.nextInt(MAIN.length)];
            } else if (slot < 40) {
                Item[] options = ARMOR[slot - 36];
                item = options[random.nextInt(options.length)];
            } else if (slot == 40) {
                item = OFFHAND[random.nextInt(OFFHAND.length)];
            } else {
                item = MAIN[random.nextInt(MAIN.length)];
            }
            ItemStack stack = new ItemStack(item);
            if (stack.getMaxStackSize() > 1) {
                stack.setCount(1 + random.nextInt(stack.getMaxStackSize()));
            }
            LOADOUT[slot] = stack;
        }
    }

    private static ItemStack fake(int slot) {
        return switch (style) {
            case 1 -> {
                ItemStack stack = LOADOUT[Math.max(0, Math.min(CARRIED, slot))];
                yield stack == null ? totem() : stack;
            }
            case 2 -> ItemStack.EMPTY;
            default -> totem();
        };
    }

    private static ItemStack totem() {
        if (totem == null) {
            totem = new ItemStack(Items.TOTEM_OF_UNDYING);
        }
        return totem;
    }

    private static ItemStack shown(ItemStack stack, int slot) {
        if (stack.isEmpty() && (!fillEmpty || style == 2)) {
            return stack;
        }
        return fake(slot);
    }

    // ---------------------------------------------------------------- Fuer die Mixins

    private static boolean isActive() {
        return enabled || autoActive;
    }

    /** Hotbar-Platz im HUD; seed 1..9 = Hotbar, 10 = Neben-Hand. */
    public static ItemStack hotbar(ItemStack stack, int seed) {
        if (!isActive() || stack == null) {
            return stack;
        }
        int slot = seed == 10 ? 40 : seed >= 1 && seed <= 9 ? seed - 1 : CARRIED;
        return shown(stack, slot);
    }

    /** Platz in einem Menue: nur deine eigenen Plaetze werden versteckt. */
    public static ItemStack container(ItemStack stack, Slot slot) {
        if (!isActive() || stack == null || !(slot.container instanceof Inventory)) {
            return stack;
        }
        return shown(stack, slot.getContainerSlot());
    }

    /** Item an der Maus. */
    public static ItemStack carried(ItemStack stack) {
        if (!isActive() || stack == null || stack.isEmpty()) {
            return stack;
        }
        return fake(CARRIED);
    }

    /** Tooltip fuer diesen Platz unterdruecken? */
    public static boolean hidesTooltip(Slot slot) {
        return isActive() && hideTooltips && slot != null && slot.container instanceof Inventory;
    }

    public static boolean hidesItemName() {
        return isActive() && hideItemName;
    }

    public static boolean hidesSelectedSlot() {
        return isActive() && hideSelectedSlot;
    }

    public static boolean hidesCoords() {
        return isActive() && hideCoords;
    }

    public static boolean hidesCrosshair() {
        return isActive() && hideCrosshair;
    }

    /** Wird gerade automatisch durch einen erkannten Recorder aktiv? */
    public static boolean isAutoActive() {
        return autoActive;
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Toggle("Auto-Detect recording", () -> autoDetect, v -> autoDetect = v),
                new ModOptionsMenu.Info(() -> autoDetect
                        ? (autoActive ? "Recording detected - protection active" : "Watching for OBS, Bandicam, Medal ...")
                        : "Turn on to activate automatically when a recorder is detected"),
                new ModOptionsMenu.Choice("Style", STYLES, () -> style, v -> {
                    style = v;
                    reseed();
                }),
                new ModOptionsMenu.Info(() -> switch (style) {
                    case 1 -> "Random harmless gear (new every time you turn it on)";
                    case 2 -> "Your items are simply not drawn";
                    default -> "Every item shows as a totem";
                }),
                new ModOptionsMenu.Toggle("Fill empty slots", () -> fillEmpty, v -> fillEmpty = v),
                new ModOptionsMenu.Toggle("Hide tooltips", () -> hideTooltips, v -> hideTooltips = v),
                new ModOptionsMenu.Toggle("Hide item name above hotbar", () -> hideItemName, v -> hideItemName = v),
                new ModOptionsMenu.Toggle("Hide selected hotbar slot", () -> hideSelectedSlot, v -> hideSelectedSlot = v),
                new ModOptionsMenu.Toggle("Hide coordinates (HUD)", () -> hideCoords, v -> hideCoords = v),
                new ModOptionsMenu.Toggle("Hide crosshair", () -> hideCrosshair, v -> hideCrosshair = v),
                new ModOptionsMenu.Info(() -> "Only changes what is drawn - you play with your real items")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "style", String.valueOf(style));
        properties.setProperty(prefix + "fillEmpty", String.valueOf(fillEmpty));
        properties.setProperty(prefix + "hideTooltips", String.valueOf(hideTooltips));
        properties.setProperty(prefix + "hideItemName", String.valueOf(hideItemName));
        properties.setProperty(prefix + "hideSelectedSlot", String.valueOf(hideSelectedSlot));
        properties.setProperty(prefix + "hideCoords", String.valueOf(hideCoords));
        properties.setProperty(prefix + "hideCrosshair", String.valueOf(hideCrosshair));
        properties.setProperty(prefix + "autoDetect", String.valueOf(autoDetect));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            style = Math.max(0, Math.min(STYLES.length - 1, Integer.parseInt(properties.getProperty(prefix + "style", String.valueOf(style)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        fillEmpty = Boolean.parseBoolean(properties.getProperty(prefix + "fillEmpty", String.valueOf(fillEmpty)));
        hideTooltips = Boolean.parseBoolean(properties.getProperty(prefix + "hideTooltips", String.valueOf(hideTooltips)));
        hideItemName = Boolean.parseBoolean(properties.getProperty(prefix + "hideItemName", String.valueOf(hideItemName)));
        hideSelectedSlot = Boolean.parseBoolean(properties.getProperty(prefix + "hideSelectedSlot", String.valueOf(hideSelectedSlot)));
        hideCoords = Boolean.parseBoolean(properties.getProperty(prefix + "hideCoords", String.valueOf(hideCoords)));
        hideCrosshair = Boolean.parseBoolean(properties.getProperty(prefix + "hideCrosshair", String.valueOf(hideCrosshair)));
        autoDetect = Boolean.parseBoolean(properties.getProperty(prefix + "autoDetect", String.valueOf(autoDetect)));
    }
}
