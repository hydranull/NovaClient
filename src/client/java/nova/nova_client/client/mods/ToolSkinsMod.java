package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.toolskins.SaberMoves;
import nova.nova_client.client.toolskins.ToolSkins;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Hyper Tools: alle Schwerter als Laserschwerter. Die Klingenfarbe zeigt die Seltenheit (Holz weiss, Stein gruen,
 * Kupfer tuerkis, Eisen blau, Gold gelb, Diamant lila, Netherite rot), der Griff das Material. Beim Ziehen zuendet die
 * Klinge, jeder Schlag ist ein anderer Move mit Schweif, und eine eigene Taste spielt drei Inspect-Animationen.
 * Hotbar und Inventar bleiben Vanilla. Gezeichnet wird in nova.nova_client.client.toolskins.
 */
public class ToolSkinsMod extends GameTickMod {
    /** Ab dieser Entfernung zeichnen fremde Schwerter (andere Spieler, Boden) wieder flach - spart Leistung. */
    private static final double FAR = 40.0D;

    private static volatile boolean enabled;
    /** Menue-Vorschau: 1 = Hyper-Modell erzwingen, 2 = Vanilla erzwingen, 0 = normal. */
    public static int previewForce;

    public static boolean drawAnimation = true;
    public static float drawSpeed = 1.0F;
    public static boolean shine = true;
    public static boolean swingMoves = true;
    public static boolean swingTrail = true;
    /** Inspect-Taste (wie in CS): Wirbeln, Werfen & Fangen, Anschauen - der Reihe nach. */
    public static int inspectKey = InputConstants.KEY_G;
    public static float inspectSpeed = 1.0F;
    public static boolean glow = true;
    /** Groesse des Leuchtens um die Klinge. */
    public static float glowSize = 1.0F;
    public static boolean others = true;

    private boolean inspectDown;

    public ToolSkinsMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        boolean down = inspectKey != NO_KEY && client.gui.screen() == null && InputConstants.isKeyDown(inspectKey);
        if (down && !inspectDown) {
            SaberMoves.startInspect();
        }
        inspectDown = down;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        ToolSkinsMod.enabled = enabled;
        ToolSkins.onToggle(enabled);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Soll ein Schwert in dieser Ansicht als Laserschwert erscheinen? */
    public static boolean shows(ItemDisplayContext context, ItemOwner owner) {
        if (previewForce != 0 && context == ItemDisplayContext.GUI) {
            return previewForce == 1;
        }
        if (!enabled) {
            return false;
        }
        if (context == ItemDisplayContext.GUI) {
            // Hotbar und Inventar zeigen die normalen Vanilla-Symbole
            return false;
        }
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        if (owner != null && owner == client.player) {
            return true;
        }
        if (!others) {
            return false;
        }
        return owner == null || client.player == null || owner.position().distanceToSqr(client.player.position()) < FAR * FAR;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, "Hyper Tools", "Laser swords in rarity colors", List.of(
                new ModOptionsMenu.Section("Combat"),
                new ModOptionsMenu.Toggle("Swing Moves", () -> swingMoves, value -> swingMoves = value),
                new ModOptionsMenu.Toggle("Swing Trail", () -> swingTrail, value -> swingTrail = value),
                new ModOptionsMenu.Section("Inspect"),
                new ModOptionsMenu.KeyBind("Inspect Key", () -> inspectKey, value -> inspectKey = value),
                new ModOptionsMenu.Slider("Inspect Speed", 0.5, 2.0, 0.05, () -> inspectSpeed,
                        value -> inspectSpeed = (float) value, value -> String.format(Locale.ROOT, "%.2fx", value)),
                new ModOptionsMenu.Action(() -> "Preview Inspect", SaberMoves::startInspect),
                new ModOptionsMenu.Section("Draw"),
                new ModOptionsMenu.Toggle("Draw Animation", () -> drawAnimation, value -> drawAnimation = value),
                new ModOptionsMenu.Slider("Draw Speed", 0.5, 2.0, 0.05, () -> drawSpeed, value -> drawSpeed = (float) value,
                        value -> String.format(Locale.ROOT, "%.2fx", value)),
                new ModOptionsMenu.Toggle("Shine While Drawing", () -> shine, value -> shine = value),
                new ModOptionsMenu.Action(() -> "Preview Draw", ToolSkins::replayDraw),
                new ModOptionsMenu.Section("Look"),
                new ModOptionsMenu.Slider("Glow Size", 0.6, 1.6, 0.05, () -> glowSize, value -> glowSize = (float) value,
                        value -> Math.round(value * 100.0) + "%"),
                new ModOptionsMenu.Toggle("Glowing Accents", () -> glow, value -> glow = value),
                new ModOptionsMenu.Toggle("Other Players & Dropped", () -> others, value -> others = value),
                new ModOptionsMenu.Info(() -> "Every sword, colored by its material")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "draw", String.valueOf(drawAnimation));
        properties.setProperty(prefix + "drawSpeed", String.format(Locale.ROOT, "%.2f", drawSpeed));
        properties.setProperty(prefix + "shine", String.valueOf(shine));
        properties.setProperty(prefix + "moves", String.valueOf(swingMoves));
        properties.setProperty(prefix + "trail", String.valueOf(swingTrail));
        properties.setProperty(prefix + "inspectKey", String.valueOf(inspectKey));
        properties.setProperty(prefix + "inspectSpeed", String.format(Locale.ROOT, "%.2f", inspectSpeed));
        properties.setProperty(prefix + "glow", String.valueOf(glow));
        properties.setProperty(prefix + "glowSize", String.format(Locale.ROOT, "%.2f", glowSize));
        properties.setProperty(prefix + "others", String.valueOf(others));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        drawAnimation = Boolean.parseBoolean(properties.getProperty(prefix + "draw", String.valueOf(drawAnimation)));
        drawSpeed = number(properties, prefix + "drawSpeed", drawSpeed, 0.5F, 2.0F);
        shine = Boolean.parseBoolean(properties.getProperty(prefix + "shine", String.valueOf(shine)));
        swingMoves = Boolean.parseBoolean(properties.getProperty(prefix + "moves", String.valueOf(swingMoves)));
        swingTrail = Boolean.parseBoolean(properties.getProperty(prefix + "trail", String.valueOf(swingTrail)));
        try {
            inspectKey = Integer.parseInt(properties.getProperty(prefix + "inspectKey", String.valueOf(inspectKey)).trim());
        } catch (NumberFormatException e) {
            inspectKey = InputConstants.KEY_G;
        }
        inspectSpeed = number(properties, prefix + "inspectSpeed", inspectSpeed, 0.5F, 2.0F);
        glow = Boolean.parseBoolean(properties.getProperty(prefix + "glow", String.valueOf(glow)));
        glowSize = number(properties, prefix + "glowSize", glowSize, 0.6F, 1.6F);
        others = Boolean.parseBoolean(properties.getProperty(prefix + "others", String.valueOf(others)));
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
