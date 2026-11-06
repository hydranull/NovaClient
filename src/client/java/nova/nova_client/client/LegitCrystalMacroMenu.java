package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.LegitCrystalMacroMod;

import java.util.List;

/**
 * Einstellungen fuer LegitCrystalMacro: Voreinstellungen (Blatant/Fast/Legit/Slow) und Regler fuer
 * Place-Delay, Break-Delay und zufaellige Abweichung.
 */
public class LegitCrystalMacroMenu extends ModOptionsMenu {
    public LegitCrystalMacroMenu(Screen parent) {
        super(parent, "LegitCrystalMacro", List.of(
                new Segmented("", names(), LegitCrystalMacroMenu::preset,
                        i -> LegitCrystalMacroMod.apply(LegitCrystalMacroMod.PRESETS[i])),
                new Section("Timing"),
                new Slider("Place delay", 0.0D, LegitCrystalMacroMod.MAX_DELAY, 5.0D, () -> LegitCrystalMacroMod.placeDelay,
                        v -> LegitCrystalMacroMod.placeDelay = (int) Math.round(v), LegitCrystalMacroMenu::ms),
                new Slider("Break delay", 0.0D, LegitCrystalMacroMod.MAX_DELAY, 5.0D, () -> LegitCrystalMacroMod.breakDelay,
                        v -> LegitCrystalMacroMod.breakDelay = (int) Math.round(v), LegitCrystalMacroMenu::ms),
                new Slider("Randomization", 0.0D, 0.5D, 0.01D, () -> LegitCrystalMacroMod.randomization,
                        v -> LegitCrystalMacroMod.randomization = (float) v, v -> "±" + Math.round(v * 100.0D) + "%")));
    }

    private static String ms(double v) {
        return v < 0.5D ? "Instant" : Math.round(v) + " ms";
    }

    private static String[] names() {
        String[] names = new String[LegitCrystalMacroMod.PRESETS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = LegitCrystalMacroMod.PRESETS[i].name();
        }
        return names;
    }

    /** Gewaehlte Voreinstellung (-1 = eigene Werte). */
    private static int preset() {
        for (int i = 0; i < LegitCrystalMacroMod.PRESETS.length; i++) {
            LegitCrystalMacroMod.Preset p = LegitCrystalMacroMod.PRESETS[i];
            if (LegitCrystalMacroMod.placeDelay == p.place() && LegitCrystalMacroMod.breakDelay == p.breakDelay()
                    && Math.abs(LegitCrystalMacroMod.randomization - p.random()) < 0.005F) {
                return i;
            }
        }
        return -1;
    }
}
