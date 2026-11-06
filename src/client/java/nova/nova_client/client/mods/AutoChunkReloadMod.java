package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Properties;

/**
 * Auto Chunk Reload: die Sichtweite (Render Distance) bleibt, wie sie ist - egal ob du auf Y 60 oder sonstwo stehst.
 * Graebst du dich nach unten und kommst auf Y 2 (oder faellst in einem Tick durch Y 2), geht sie sofort ganz herunter;
 * gehst du weiter bis Y -2 (oder tiefer), ganz hoch. Dort bleibt sie, auch wenn du wieder nach oben fliegst - erst ein
 * paar Bloecke ueber Y 2 wird es neu scharf, und das naechste Runtergraben startet den naechsten Durchgang. Kehrst du
 * vor Y -2 wieder um, kommt die Sichtweite von vorher zurueck. Jede Aenderung geht auch an den Server, damit der die
 * Chunks wirklich entlaedt und neu schickt. Beim Ausschalten kommt die Sichtweite von vorher zurueck.
 */
public class AutoChunkReloadMod extends GameTickMod {
    /** Weiter als so viele Bloecke in einem Tick = Teleport (zaehlt nicht als Durchgang durch Y 2). */
    private static final double MAX_STEP = 8.0D;
    /** So viele Bloecke ueber upperY wird der naechste Durchgang scharf (kein Flackern beim Springen auf Y 2). */
    private static final int REARM = 5;

    // Einstellungen
    public static int upperY = 2;
    public static int lowerY = -2;
    public static int lowDistance = 2;
    /** 0 = so hoch wie Minecraft erlaubt. */
    public static int highDistance = 0;
    public static boolean restore = true;

    /** Sichtweite beim Einschalten (zum Zuruecksetzen), -1 = keine. */
    private static int saved = -1;
    /** 0 = wartet auf Y 2, 1 = runtergesetzt (wartet auf Y -2), 2 = hochgesetzt (wartet aufs Hochfliegen). */
    private static int state;
    /** Sichtweite direkt vor dem Runtersetzen (fuer Umkehren vor Y -2). */
    private static int beforeLow = -1;
    /** Hat das Modul die Sichtweite seit dem Einschalten geaendert? (Nur dann beim Ausschalten zuruecksetzen.) */
    private static boolean changed;
    /** Welt, in der der aktuelle Durchgang laeuft (neue Welt = neuer Durchgang). */
    private static Object level;
    /** Hoehe im letzten Tick (NaN = keine). */
    private static double lastY = Double.NaN;

    public AutoChunkReloadMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        OptionInstance<Integer> distance = client.options.renderDistance();
        if (on) {
            saved = distance.get();
            state = 0;
            changed = false;
            level = client.level;
            lastY = Double.NaN;
        } else {
            if (restore && saved > 0 && changed) {
                set(client, saved);
            }
            saved = -1;
            state = 0;
            changed = false;
        }
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        if (client.level != level) {
            level = client.level;
            state = 0;
            lastY = Double.NaN;
        }
        double y = player.getY();
        double previous = lastY;
        lastY = y;
        boolean above = y >= upperY + REARM;
        if (state == 0 && atUpper(previous, y)) {
            beforeLow = client.options.renderDistance().get();
            set(client, lowDistance);
            state = 1;
        } else if (state == 1 && y <= lowerY) {
            set(client, highDistance <= 0 ? maximum(client) : highDistance);
            state = 2;
        } else if (state == 1 && above) {
            // vor Y -2 wieder hoch: nichts neu geladen, alte Sichtweite zurueck
            if (beforeLow > 0) {
                set(client, beforeLow);
            }
            state = 0;
        } else if (state == 2 && above) {
            // wieder oben: Sichtweite bleibt hoch, das naechste Runtergraben startet neu
            state = 0;
        }
    }

    /**
     * Ist der Spieler auf Hoehe upperY (Fuesse im Block Y upperY) oder seit dem letzten Tick durch diese Hoehe
     * gegangen? Schnelles Fallen kann den Block in einem Tick ueberspringen, ein Teleport zaehlt nicht.
     */
    private static boolean atUpper(double previous, double y) {
        if (y >= upperY && y < upperY + 1) {
            return true;
        }
        if (Double.isNaN(previous) || Math.abs(y - previous) > MAX_STEP) {
            return false;
        }
        return Math.min(previous, y) < upperY + 1 && Math.max(previous, y) >= upperY;
    }

    private static int minimum(Minecraft client) {
        return client.options.renderDistance().values() instanceof OptionInstance.IntRange range ? range.minInclusive() : 2;
    }

    private static int maximum(Minecraft client) {
        return client.options.renderDistance().values() instanceof OptionInstance.IntRange range ? range.maxInclusive() : 32;
    }

    /**
     * Sichtweite setzen (auf den erlaubten Bereich begrenzt); Minecraft baut die Welt dann selbst neu auf. Danach dem
     * Server die neue Sichtweite schicken - nur dann entlaedt er die Chunks bzw. schickt sie neu (sonst passiert das
     * erst beim Schliessen der Optionen).
     */
    private static void set(Minecraft client, int chunks) {
        OptionInstance<Integer> distance = client.options.renderDistance();
        int value = Math.max(minimum(client), Math.min(maximum(client), chunks));
        if (distance.get() != value) {
            distance.set(value);
            client.options.broadcastOptions();
            changed = true;
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        int max = maximum(client);
        return new ModOptionsMenu(parent, name, "Render distance by height", List.of(
                new ModOptionsMenu.Slider("Low at Y", -10, 20, 1.0D, () -> upperY,
                        v -> upperY = Math.max(lowerY + 1, (int) Math.round(v)), v -> "Y " + Math.round(v)),
                new ModOptionsMenu.Slider("High at Y or lower", -20, 10, 1.0D, () -> lowerY,
                        v -> lowerY = Math.min(upperY - 1, (int) Math.round(v)), v -> "Y " + Math.round(v)),
                new ModOptionsMenu.Slider("Low distance", 2, max, 1.0D, () -> lowDistance,
                        v -> lowDistance = (int) Math.round(v), v -> Math.round(v) + " chunks"),
                new ModOptionsMenu.Slider("High distance", 1, max, 1.0D, () -> highDistance <= 0 ? 1 : highDistance,
                        v -> highDistance = v < 1.5D ? 0 : (int) Math.round(v),
                        v -> v < 1.5D ? "Max (" + max + ")" : Math.round(v) + " chunks"),
                new ModOptionsMenu.Toggle("Restore distance when turned off", () -> restore, v -> restore = v),
                new ModOptionsMenu.Action(() -> "Start again", () -> state = 0),
                new ModOptionsMenu.Info(() -> switch (state) {
                    case 0 -> "Ready - low when you dig down to Y " + upperY;
                    case 1 -> "Low now - waiting for Y " + lowerY + " (then high)";
                    default -> "High (" + client.options.renderDistance().get() + " chunks) - re-arms above Y "
                            + (upperY + REARM);
                })));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "upperY", String.valueOf(upperY));
        properties.setProperty(prefix + "lowerY", String.valueOf(lowerY));
        properties.setProperty(prefix + "lowDistance", String.valueOf(lowDistance));
        properties.setProperty(prefix + "highDistance", String.valueOf(highDistance));
        properties.setProperty(prefix + "restore", String.valueOf(restore));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        upperY = read(properties, prefix + "upperY", upperY);
        lowerY = read(properties, prefix + "lowerY", lowerY);
        if (lowerY >= upperY) {
            lowerY = upperY - 1;
        }
        lowDistance = Math.max(2, read(properties, prefix + "lowDistance", lowDistance));
        highDistance = Math.max(0, read(properties, prefix + "highDistance", highDistance));
        restore = Boolean.parseBoolean(properties.getProperty(prefix + "restore", String.valueOf(restore)));
    }

    private static int read(Properties properties, String key, int fallback) {
        try {
            String value = properties.getProperty(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
