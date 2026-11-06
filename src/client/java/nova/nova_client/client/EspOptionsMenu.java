package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Einfaches Einstellungs-Menue fuer die Spezial-ESPs: ein paar Schalter (der erste mit der Farbe des ESPs) und optional
 * eine Reihe Voreinstellungen als Segmente.
 */
public class EspOptionsMenu extends ModOptionsMenu {
    public record Toggle(String label, BooleanSupplier get, Runnable flip) {
    }

    public record Presets(String label, int[] values, IntSupplier get, IntConsumer set, String suffix) {
    }

    public EspOptionsMenu(Screen parent, String title, String subtitle, int swatch, List<Toggle> toggles, Presets presets) {
        super(parent, title, subtitle, options(swatch, toggles, presets));
    }

    private static List<Option> options(int swatch, List<Toggle> toggles, Presets presets) {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < toggles.size(); i++) {
            Toggle toggle = toggles.get(i);
            options.add(new ModOptionsMenu.Toggle(toggle.label(), toggle.get(), v -> {
                if (v != toggle.get().getAsBoolean()) {
                    toggle.flip().run();
                }
            }, i == 0 ? swatch : 0));
        }
        if (presets != null) {
            String[] labels = new String[presets.values().length];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = presets.values()[i] + presets.suffix();
            }
            options.add(new Section(presets.label()));
            options.add(new Segmented("", labels, () -> {
                for (int i = 0; i < presets.values().length; i++) {
                    if (presets.values()[i] == presets.get().getAsInt()) {
                        return i;
                    }
                }
                return -1;
            }, i -> presets.set().accept(presets.values()[i])));
        }
        return options;
    }
}
