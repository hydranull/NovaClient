package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.SwingSpeedMod;

import java.util.List;
import java.util.Locale;

/**
 * Einstellungen fuer Swing Speed: Animationsgeschwindigkeit der Haupthand per Regler (0,2x - 3,0x) und Voreinstellungen.
 */
public class SwingSpeedMenu extends ModOptionsMenu {
    private static final float[] PRESETS = {0.3F, 0.5F, 0.7F, 1.0F, 1.5F, 2.0F};
    private static final String[] LABELS = {"0.3x", "0.5x", "0.7x", "1x", "1.5x", "2x"};

    public SwingSpeedMenu(Screen parent) {
        super(parent, "Swing Speed", List.of(
                new Slider("Speed", SwingSpeedMod.MIN, SwingSpeedMod.MAX, 0.1D, () -> SwingSpeedMod.speed,
                        v -> SwingSpeedMod.setSpeed((float) v), v -> String.format(Locale.ROOT, "%.1fx", v)),
                new Segmented("", LABELS, SwingSpeedMenu::preset, i -> SwingSpeedMod.setSpeed(PRESETS[i])),
                new Info(SwingSpeedMenu::describe)));
    }

    private static String describe() {
        if (Math.abs(SwingSpeedMod.speed - 1.0F) < 0.05F) {
            return "Vanilla speed";
        }
        return SwingSpeedMod.speed < 1.0F ? "Slower than vanilla" : "Faster than vanilla";
    }

    /** Gewaehlte Voreinstellung (-1 = keine passt). */
    private static int preset() {
        for (int i = 0; i < PRESETS.length; i++) {
            if (Math.abs(SwingSpeedMod.speed - PRESETS[i]) < 0.05F) {
                return i;
            }
        }
        return -1;
    }
}
