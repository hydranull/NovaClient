package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Properties;

/**
 * Trident Util: Riptide-Dreizack auch ohne Wasser und Regen benutzen und schneller aufladen. Den Schwung berechnet der
 * eigene Client (Bewegung macht der Spieler selbst), deshalb reicht es, die Pruefungen im Dreizack fuer dich zu lockern
 * (siehe TridentUtilMixin). Vanilla braucht 10 Ticks (eine halbe Sekunde) Aufladen.
 */
public class TridentUtilMod extends GameTickMod {
    public static final int VANILLA_CHARGE = 10;

    /** Riptide ohne Wasser/Regen. */
    public static boolean anywhere = true;
    /** Aufladezeit in Ticks (1 = sofort, 10 = Vanilla). */
    public static int chargeTicks = 2;

    private static volatile boolean enabled;

    public TridentUtilMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean riptideAnywhere() {
        return enabled && anywhere;
    }

    /** Mindest-Aufladezeit statt der 10 Ticks von Vanilla. */
    public static int chargeTicks(int vanilla) {
        return enabled ? Math.max(1, Math.min(vanilla, chargeTicks)) : vanilla;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        TridentUtilMod.enabled = enabled;
    }

    @Override
    public void execute(Minecraft client) {
        // alles passiert im Dreizack selbst (TridentUtilMixin)
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Riptide anywhere, charge faster", List.of(
                new ModOptionsMenu.Toggle("Riptide without water", () -> anywhere, v -> anywhere = v),
                new ModOptionsMenu.Slider("Charge time", 1.0D, VANILLA_CHARGE, 1.0D, () -> chargeTicks,
                        v -> chargeTicks = (int) Math.round(v),
                        v -> Math.round(v) == VANILLA_CHARGE ? "Vanilla" : Math.round(v) * 50 + " ms")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "anywhere", String.valueOf(anywhere));
        properties.setProperty(prefix + "chargeTicks", String.valueOf(chargeTicks));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        anywhere = Boolean.parseBoolean(properties.getProperty(prefix + "anywhere", String.valueOf(anywhere)).trim());
        try {
            chargeTicks = Math.max(1, Math.min(VANILLA_CHARGE,
                    Integer.parseInt(properties.getProperty(prefix + "chargeTicks", String.valueOf(chargeTicks)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
