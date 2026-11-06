package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.KnifeRenderer;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Knives (CS-Messer): Schwerter werden in der Ego-Sicht als Shadow Daggers (3D-Modell) gezeichnet, je einer liegt auf
 * dem Handruecken beider Arme. Schlagen = beide Arme stossen nach vorne, Rechtsklick oder eigene Inspect-Taste = Inspect
 * wie in CS (Dolch dreht sich einmal um den Griff und wird seitlich angeschaut). Gezeichnet wird in KnivesHandMixin ueber KnifeRenderer.
 * Modell: "Shadow Daggers" von Reysoft (sketchfab.com/zFabby), CC BY 4.0.
 */
public class KnivesMod extends GameTickMod {
    public static final String[] ITEM_MODES = {"Swords", "Swords + Axes"};

    private static volatile boolean enabled;

    public static boolean bothHands = true;
    public static boolean inspectOnUse = true;
    public static boolean drawAnimation = true;
    public static int itemMode = 0;
    public static float size = 1.0F;
    public static float offsetX = 0.0F;
    public static float offsetY = 0.0F;
    public static float offsetZ = 0.0F;
    /** Eigene Inspect-Taste wie in CS (zusaetzlich zum Rechtsklick). */
    public static int inspectKey = NO_KEY;
    public static float inspectSpeed = 1.0F;

    private boolean inspectDown;

    public KnivesMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        boolean down = inspectKey != NO_KEY && client.gui.screen() == null
                && com.mojang.blaze3d.platform.InputConstants.isKeyDown(inspectKey);
        if (down && !inspectDown && client.player != null && isKnife(client.player.getMainHandItem())) {
            KnifeRenderer.startInspect();
        }
        inspectDown = down;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        KnivesMod.enabled = enabled;
        KnifeRenderer.reset();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Wird dieses Item als Messer gezeichnet? */
    public static boolean isKnife(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.is(ItemTags.SWORDS) || (itemMode == 1 && stack.is(ItemTags.AXES));
    }

    /** Rechtsklick (Minecraft.startUseItem): mit Messer in der Hand inspecten. */
    public static void onUse(Minecraft client) {
        if (enabled && inspectOnUse && client.player != null && isKnife(client.player.getMainHandItem())) {
            KnifeRenderer.startInspect();
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, "Knives", "CS-style knives with inspect", List.of(
                new ModOptionsMenu.Toggle("Both Hands", () -> bothHands, value -> bothHands = value),
                new ModOptionsMenu.KeyBind("Inspect Key", () -> inspectKey, value -> inspectKey = value),
                new ModOptionsMenu.Toggle("Inspect on Right-Click", () -> inspectOnUse, value -> inspectOnUse = value),
                new ModOptionsMenu.Slider("Inspect Speed", 0.5, 2.0, 0.05, () -> inspectSpeed,
                        value -> inspectSpeed = (float) value, value -> String.format(Locale.ROOT, "%.2fx", value)),
                new ModOptionsMenu.Toggle("Draw Animation", () -> drawAnimation, value -> drawAnimation = value),
                new ModOptionsMenu.Choice("Items", ITEM_MODES, () -> itemMode, value -> itemMode = value),
                new ModOptionsMenu.Slider("Size", 0.6, 1.5, 0.05, () -> size, value -> size = (float) value,
                        value -> String.format(Locale.ROOT, "%.2fx", value)),
                new ModOptionsMenu.Slider("Offset X", -0.3, 0.3, 0.01, () -> offsetX, value -> offsetX = (float) value,
                        KnivesMod::formatOffset),
                new ModOptionsMenu.Slider("Offset Y", -0.3, 0.3, 0.01, () -> offsetY, value -> offsetY = (float) value,
                        KnivesMod::formatOffset),
                new ModOptionsMenu.Slider("Offset Z", -0.3, 0.3, 0.01, () -> offsetZ, value -> offsetZ = (float) value,
                        KnivesMod::formatOffset),
                new ModOptionsMenu.Action(() -> "Preview Inspect", KnifeRenderer::startInspect),
                new ModOptionsMenu.Info(() -> "Model: Shadow Daggers by Reysoft (CC BY 4.0)")));
    }

    private static String formatOffset(double value) {
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "bothHands", String.valueOf(bothHands));
        properties.setProperty(prefix + "inspect", String.valueOf(inspectOnUse));
        properties.setProperty(prefix + "draw", String.valueOf(drawAnimation));
        properties.setProperty(prefix + "items", String.valueOf(itemMode));
        properties.setProperty(prefix + "size", String.format(Locale.ROOT, "%.2f", size));
        properties.setProperty(prefix + "offsetX", String.format(Locale.ROOT, "%.2f", offsetX));
        properties.setProperty(prefix + "offsetY", String.format(Locale.ROOT, "%.2f", offsetY));
        properties.setProperty(prefix + "offsetZ", String.format(Locale.ROOT, "%.2f", offsetZ));
        properties.setProperty(prefix + "inspectKey", String.valueOf(inspectKey));
        properties.setProperty(prefix + "inspectSpeed", String.format(Locale.ROOT, "%.2f", inspectSpeed));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        bothHands = Boolean.parseBoolean(properties.getProperty(prefix + "bothHands", String.valueOf(bothHands)));
        inspectOnUse = Boolean.parseBoolean(properties.getProperty(prefix + "inspect", String.valueOf(inspectOnUse)));
        drawAnimation = Boolean.parseBoolean(properties.getProperty(prefix + "draw", String.valueOf(drawAnimation)));
        itemMode = Math.round(number(properties, prefix + "items", itemMode, 0, ITEM_MODES.length - 1));
        size = number(properties, prefix + "size", size, 0.6F, 1.5F);
        offsetX = number(properties, prefix + "offsetX", offsetX, -0.3F, 0.3F);
        offsetY = number(properties, prefix + "offsetY", offsetY, -0.3F, 0.3F);
        offsetZ = number(properties, prefix + "offsetZ", offsetZ, -0.3F, 0.3F);
        inspectKey = Math.round(number(properties, prefix + "inspectKey", inspectKey, Integer.MIN_VALUE, Integer.MAX_VALUE));
        inspectSpeed = number(properties, prefix + "inspectSpeed", inspectSpeed, 0.5F, 2.0F);
    }

    private static float number(Properties properties, String key, float fallback, float min, float max) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Float.parseFloat(value.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
