package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.AutoToolMod;

import java.util.List;

/**
 * Einstellungen fuer AutoTool: Verzoegerung vor dem Werkzeugwechsel per Regler (0 - 2000 ms) und Voreinstellungen.
 */
public class AutoToolMenu extends ModOptionsMenu {
    private static final int[] PRESETS = {0, 100, 250, 500, 1000, 2000};
    private static final String[] LABELS = {"0", "100", "250", "500", "1s", "2s"};

    public AutoToolMenu(Screen parent) {
        super(parent, "AutoTool", List.of(
                new Slider("Switch delay", AutoToolMod.MIN_DELAY, AutoToolMod.MAX_DELAY, AutoToolMod.DELAY_STEP,
                        () -> AutoToolMod.delay, v -> AutoToolMod.setDelay((int) Math.round(v)),
                        v -> Math.round(v) == 0 ? "Instant" : Math.round(v) + " ms"),
                new Segmented("", LABELS, AutoToolMenu::preset, i -> AutoToolMod.setDelay(PRESETS[i]))));
    }

    /** Gewaehlte Voreinstellung (-1 = keine passt). */
    private static int preset() {
        for (int i = 0; i < PRESETS.length; i++) {
            if (AutoToolMod.delay == PRESETS[i]) {
                return i;
            }
        }
        return -1;
    }
}
