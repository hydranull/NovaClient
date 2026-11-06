package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.TimeChangerMenu;

import java.util.Properties;

/**
 * Stellt die Tageszeit clientseitig fest ein. Sonne, Mond, Sterne und Himmelsfarbe
 * folgen der eingestellten Zeit (Hook im ClientClockManager). Der Server merkt davon nichts.
 */
public class TimeChangerMod extends GameTickMod {
    public static final int DAY_LENGTH = 24000;
    public static final int DEFAULT = 6000;

    /** Eingestellte Zeit in Ticks (0 - 23999). 0 = Sonnenaufgang, 6000 = Mittag, 18000 = Mitternacht. */
    public static int time = DEFAULT;

    // volatile Flag: wird bei jeder Zeitabfrage vom ClientClockManager-Mixin gelesen
    private static volatile boolean enabled;

    public TimeChangerMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        TimeChangerMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setTime(int value) {
        time = Math.floorMod(value, DAY_LENGTH);
    }

    /** Ersetzt die Uhrzeit, behaelt aber den Tageszaehler (fuer die Mondphase). */
    public static long apply(long totalTicks) {
        return Math.floorDiv(totalTicks, DAY_LENGTH) * DAY_LENGTH + time;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new TimeChangerMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "time", String.valueOf(time));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "time");
        if (value != null) {
            try {
                setTime(Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
