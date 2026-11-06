package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.Rect;

import java.util.List;
import java.util.Properties;

/**
 * ArmourView: links und rechts neben der Hotbar je ein kleiner Stapel, schlicht wie ein Totem-Zaehler - Symbol und
 * Zahl, ohne Kaesten, die Zahl immer zur Hotbar hin. Links von oben: Neben-Hand, Helm, Brust; rechts von oben: Item in
 * der Hand, Hose, Schuhe. Haltbarkeit in Prozent oder Restpunkten (Farbe von Gruen ueber Gelb nach Rot; fast kaputt =
 * blinkt), stapelbare Items zeigen, wie viele davon im Inventar sind. In der Hoehe der Hotbar rechts: wie viele Totems
 * du dabeihast.
 */
public class ArmourViewMod extends RenderMod {
    private static final int ROW = 18;
    /** Abstand der Symbole zur Hotbar: links bis zum Symbol, rechts ab dem Rand der Leiste. */
    private static final int LEFT_ICON = 46;
    private static final int RIGHT_ICON = 30;
    private static final int WHITE = 0xFFF2F2F2;

    // Einstellungen
    public static boolean totems = true;
    public static boolean hands = true;
    public static boolean percent = true;
    public static boolean hideEmpty = true;

    /** Beispiel-Ruestung fuer die Vorschau (beim ersten Gebrauch angelegt). */
    private static ItemStack[] samples;

    public ArmourViewMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        // F1 (HUD aus) ruft das HUD gar nicht erst auf
        if (player == null || player.isSpectator()) {
            return;
        }
        Font font = client.font;
        int barLeft = graphics.guiWidth() / 2 - 91;
        int barRight = barLeft + 182;
        int y = graphics.guiHeight() - 19;

        // Stapel von unten nach oben
        stack(graphics, font, player, barLeft - LEFT_ICON, y, false,
                player.getItemBySlot(EquipmentSlot.CHEST), player.getItemBySlot(EquipmentSlot.HEAD),
                hands ? player.getOffhandItem() : ItemStack.EMPTY);
        stack(graphics, font, player, barRight + RIGHT_ICON, y, true,
                player.getItemBySlot(EquipmentSlot.FEET), player.getItemBySlot(EquipmentSlot.LEGS),
                hands ? player.getMainHandItem() : ItemStack.EMPTY);

        if (totems) {
            int count = countSame(player.getInventory(), new ItemStack(Items.TOTEM_OF_UNDYING));
            if (count > 0) {
                // Linkshaender: dort sitzt das Feld der Neben-Hand, die Zahl kommt dann hinter das Symbol
                boolean slotRight = player.getMainArm() == HumanoidArm.LEFT && !player.getOffhandItem().isEmpty();
                entry(graphics, font, barRight + RIGHT_ICON, y, new ItemStack(Items.TOTEM_OF_UNDYING),
                        String.valueOf(count), WHITE, !slotRight);
            }
        }
    }

    /** Ein Stapel ueber der Hotbar-Zeile, items von unten nach oben; leere Plaetze ruecken nach (hideEmpty). */
    private static void stack(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, int x, int y, boolean textLeft,
            ItemStack... items) {
        int row = 1;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                if (!hideEmpty) {
                    row++;
                }
                continue;
            }
            String text = "";
            int color = WHITE;
            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                int max = stack.getMaxDamage();
                int left = Math.max(0, max - stack.getDamageValue());
                float ratio = left / (float) max;
                text = percent ? Math.round(ratio * 100.0F) + "%" : String.valueOf(left);
                color = durabilityColor(ratio);
            } else if (stack.getMaxStackSize() > 1) {
                text = String.valueOf(countSame(player.getInventory(), stack));
            }
            entry(graphics, font, x, y - row * ROW, stack, text, color, textLeft);
            row++;
        }
    }

    /**
     * Vorschau im Nova-Menue: links Helm und Brust, rechts Hose und Schuhe (Beispiel-Ruestung, unterschiedlich
     * abgenutzt) um ein Stueck Hotbar, die Zahlen zur Hotbar hin wie im Spiel; linke obere Ecke bei (x, y).
     */
    public static Rect drawPreview(GuiGraphicsExtractor graphics, Font font, int x, int y) {
        if (samples == null) {
            samples = new ItemStack[]{worn(Items.DIAMOND_HELMET, 0.92F), worn(Items.DIAMOND_CHESTPLATE, 0.64F),
                    worn(Items.DIAMOND_LEGGINGS, 0.81F), worn(Items.DIAMOND_BOOTS, 0.18F)};
        }
        int right = x + 102;
        int barY = y + 2 * ROW;
        NovaShapes.rect(graphics, x + 44, barY, x + 74, barY + 16, 3, NovaGui.HUD_BACKGROUND);
        sample(graphics, font, x, y, samples[0], false);
        sample(graphics, font, x, y + ROW, samples[1], false);
        sample(graphics, font, right, y, samples[2], true);
        sample(graphics, font, right, y + ROW, samples[3], true);
        return new Rect(x, y, right + 16, barY + 16);
    }

    private static void sample(GuiGraphicsExtractor graphics, Font font, int x, int y, ItemStack stack, boolean textLeft) {
        int max = stack.getMaxDamage();
        float ratio = 1.0F - stack.getDamageValue() / (float) max;
        String text = percent ? Math.round(ratio * 100.0F) + "%" : String.valueOf(max - stack.getDamageValue());
        entry(graphics, font, x, y, stack, text, durabilityColor(ratio), textLeft);
    }

    private static ItemStack worn(Item item, float left) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(Math.round(stack.getMaxDamage() * (1.0F - left)));
        return stack;
    }

    /** Symbol bei x, die Zahl links davor (rechtsbuendig) oder rechts dahinter. */
    private static void entry(GuiGraphicsExtractor graphics, Font font, int x, int y, ItemStack stack, String text, int color,
            boolean textLeft) {
        graphics.item(stack, x, y);
        if (text.isEmpty()) {
            return;
        }
        int textX = textLeft ? x - 2 - NovaGui.width(font, text) : x + 18;
        NovaGui.text(graphics, font, text, textX, y + 4, color, true);
    }

    /** Wie viele Items dieser Art (gleiches Item, gleiche Daten) im ganzen Inventar sind, Neben-Hand eingeschlossen. */
    private static int countSame(Inventory inventory, ItemStack like) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, like)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Gruen -> Gelb -> Orange -> Rot; unter 10 % blinkt es. */
    private static int durabilityColor(float ratio) {
        int green = 0xFF5ADB7A;
        int yellow = 0xFFF2C94E;
        int orange = 0xFFFF8A3D;
        int red = 0xFFFF4D4D;
        int color;
        if (ratio > 0.5F) {
            color = NovaGui.mix(yellow, green, (ratio - 0.5F) / 0.5F);
        } else if (ratio > 0.2F) {
            color = NovaGui.mix(orange, yellow, (ratio - 0.2F) / 0.3F);
        } else {
            color = NovaGui.mix(red, orange, ratio / 0.2F);
        }
        if (ratio < 0.1F && (Util.getMillis() / 300L) % 2L == 0L) {
            color = NovaGui.mix(color, 0xFFFFFFFF, 0.45F);
        }
        return color;
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Armour, hands and totems next to the hotbar", List.of(
                new ModOptionsMenu.Toggle("Totem count", () -> totems, v -> totems = v),
                new ModOptionsMenu.Toggle("Offhand and held item", () -> hands, v -> hands = v),
                new ModOptionsMenu.Toggle("Durability in percent (off = points left)", () -> percent, v -> percent = v),
                new ModOptionsMenu.Toggle("Hide empty armour slots", () -> hideEmpty, v -> hideEmpty = v)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "totems", String.valueOf(totems));
        properties.setProperty(prefix + "hands", String.valueOf(hands));
        properties.setProperty(prefix + "percent", String.valueOf(percent));
        properties.setProperty(prefix + "hideEmpty", String.valueOf(hideEmpty));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        totems = Boolean.parseBoolean(properties.getProperty(prefix + "totems", String.valueOf(totems)));
        hands = Boolean.parseBoolean(properties.getProperty(prefix + "hands", String.valueOf(hands)));
        percent = Boolean.parseBoolean(properties.getProperty(prefix + "percent", String.valueOf(percent)));
        hideEmpty = Boolean.parseBoolean(properties.getProperty(prefix + "hideEmpty", String.valueOf(hideEmpty)));
    }
}
