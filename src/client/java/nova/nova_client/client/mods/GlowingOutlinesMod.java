package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import nova.nova_client.client.GlowingOutlinesMenu;
import nova.nova_client.client.ModCategory;

import java.util.Locale;
import java.util.Properties;

/**
 * Glowing Outlines: leuchtende Kontur um die Items in Haupt- und Zweithand (Ego-Sicht).
 * Farbe, Glow-Groesse, Glow-Staerke und Konturbreite sind einstellbar.
 * Gezeichnet wird in HandGlowRenderer (Outline-Buffer + eigener Shader-Pass).
 */
public class GlowingOutlinesMod extends GameTickMod {
    public static final float MAX_GLOW = 24.0F;
    public static final float MAX_INTENSITY = 4.0F;
    public static final float MAX_WIDTH = 4.0F;

    // Einstellungen
    public static float hue = 0.55F;
    public static float saturation = 0.85F;
    public static float brightness = 1.0F;
    /** Farbe laeuft langsam durch alle Farbtoene. */
    public static boolean rainbow = false;
    /** Weichzeichner-Radius des Glows in Pixeln, 0 = kein Glow. */
    public static float glow = 10.0F;
    /** Staerke des Glows. */
    public static float intensity = 1.6F;
    /** Breite der scharfen Kontur in Pixeln, 0 = keine. */
    public static float width = 1.0F;

    private static boolean enabled;

    public GlowingOutlinesMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        GlowingOutlinesMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Aktuelle Konturfarbe (ARGB, voll deckend). */
    public static int color() {
        float h = rainbow ? (Util.getMillis() % 6000L) / 6000.0F : hue;
        return 0xFF000000 | Mth.hsvToRgb(Math.min(h, 0.999F), saturation, brightness);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new GlowingOutlinesMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "hue", format(hue));
        properties.setProperty(prefix + "saturation", format(saturation));
        properties.setProperty(prefix + "brightness", format(brightness));
        properties.setProperty(prefix + "rainbow", String.valueOf(rainbow));
        properties.setProperty(prefix + "glow", format(glow));
        properties.setProperty(prefix + "intensity", format(intensity));
        properties.setProperty(prefix + "width", format(width));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        hue = number(properties, prefix + "hue", hue, 0.0F, 1.0F);
        saturation = number(properties, prefix + "saturation", saturation, 0.0F, 1.0F);
        brightness = number(properties, prefix + "brightness", brightness, 0.0F, 1.0F);
        rainbow = Boolean.parseBoolean(properties.getProperty(prefix + "rainbow", String.valueOf(rainbow)));
        glow = number(properties, prefix + "glow", glow, 0.0F, MAX_GLOW);
        intensity = number(properties, prefix + "intensity", intensity, 0.0F, MAX_INTENSITY);
        width = number(properties, prefix + "width", width, 0.0F, MAX_WIDTH);
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static float number(Properties properties, String key, float fallback, float min, float max) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Mth.clamp(Float.parseFloat(value.trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
