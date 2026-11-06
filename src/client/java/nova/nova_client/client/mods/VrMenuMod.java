package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.VrMenuMenu;

import java.util.Locale;
import java.util.Properties;

/**
 * VR Menu: alle Nova-Menues schweben als Glasfenster (wie bei einer VR-/AR-Brille) vor der Spielwelt,
 * statt den ganzen Bildschirm zu verdecken. Gezeichnet wird in {@link nova.nova_client.client.VrMenu},
 * die Einstellungen (Neigung, Groesse, Head-Tracking, Schweben) hier.
 */
public class VrMenuMod extends GameTickMod {
    public static final float MIN_TILT = -25.0F;
    public static final float MAX_TILT = 25.0F;
    public static final float MIN_SIZE = 0.55F;
    public static final float MAX_SIZE = 0.95F;

    /** Drehung um die Hochachse in Grad; positiv = rechte Seite weiter weg (wie auf dem Vision-Pro-Bild). */
    public static float tilt = 9.0F;
    /** Anteil der Bildschirmgroesse, den das Fenster ohne Neigung einnimmt. */
    public static float size = 0.74F;
    /** Wie stark das Fenster der Maus folgt (0 = starr, 1 = voll). */
    public static float headTracking = 0.5F;
    /** Sanftes Schweben (Auf und Ab, leichtes Wiegen). */
    public static boolean floating = true;
    /** Fenster-Leiste unter dem Fenster (zum Verschieben ziehen). */
    public static boolean windowBar = true;
    /** Verschiebung des Fensters als Anteil der Bildschirmbreite/-hoehe (ueber die Leiste gezogen). */
    public static float offsetX;
    public static float offsetY;

    private static volatile boolean enabled;

    public VrMenuMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        VrMenuMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setTilt(float value) {
        tilt = Math.round(clamp(value, MIN_TILT, MAX_TILT));
    }

    public static void setSize(float value) {
        size = Math.round(clamp(value, MIN_SIZE, MAX_SIZE) * 100.0F) / 100.0F;
    }

    public static void setHeadTracking(float value) {
        headTracking = Math.round(clamp(value, 0.0F, 1.0F) * 20.0F) / 20.0F;
    }

    public static void setOffset(float x, float y) {
        offsetX = clamp(x, -0.4F, 0.4F);
        offsetY = clamp(y, -0.35F, 0.35F);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new VrMenuMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "tilt", String.format(Locale.ROOT, "%.0f", tilt));
        properties.setProperty(prefix + "size", String.format(Locale.ROOT, "%.2f", size));
        properties.setProperty(prefix + "head_tracking", String.format(Locale.ROOT, "%.2f", headTracking));
        properties.setProperty(prefix + "floating", String.valueOf(floating));
        properties.setProperty(prefix + "window_bar", String.valueOf(windowBar));
        properties.setProperty(prefix + "offset_x", String.format(Locale.ROOT, "%.4f", offsetX));
        properties.setProperty(prefix + "offset_y", String.format(Locale.ROOT, "%.4f", offsetY));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        setTilt(parse(properties.getProperty(prefix + "tilt"), tilt));
        setSize(parse(properties.getProperty(prefix + "size"), size));
        setHeadTracking(parse(properties.getProperty(prefix + "head_tracking"), headTracking));
        String value = properties.getProperty(prefix + "floating");
        if (value != null) {
            floating = Boolean.parseBoolean(value.trim());
        }
        value = properties.getProperty(prefix + "window_bar");
        if (value != null) {
            windowBar = Boolean.parseBoolean(value.trim());
        }
        setOffset(parse(properties.getProperty(prefix + "offset_x"), offsetX),
                parse(properties.getProperty(prefix + "offset_y"), offsetY));
    }

    private static float parse(String value, float fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
