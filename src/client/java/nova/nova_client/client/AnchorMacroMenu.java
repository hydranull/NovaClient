package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.AnchorMacroMod;

import java.util.List;

/**
 * Einstellungen fuer AnchorMacro: Voreinstellungen, Regler fuer Place/Charge/Explode-Delay und Zufall,
 * Schalter "Slot zurueck".
 */
public class AnchorMacroMenu extends ModOptionsMenu {
    public AnchorMacroMenu(Screen parent) {
        super(parent, "AnchorMacro", List.of(
                new Segmented("", names(), AnchorMacroMenu::preset, i -> AnchorMacroMod.apply(AnchorMacroMod.PRESETS[i])),
                new Section("Timing"),
                new Slider("Place delay", 0.0D, AnchorMacroMod.MAX_DELAY, 5.0D, () -> AnchorMacroMod.placeDelay,
                        v -> AnchorMacroMod.placeDelay = (int) Math.round(v), AnchorMacroMenu::ms),
                new Slider("Charge delay", 0.0D, AnchorMacroMod.MAX_DELAY, 5.0D, () -> AnchorMacroMod.chargeDelay,
                        v -> AnchorMacroMod.chargeDelay = (int) Math.round(v), AnchorMacroMenu::ms),
                new Slider("Explode delay", 0.0D, AnchorMacroMod.MAX_DELAY, 5.0D, () -> AnchorMacroMod.explodeDelay,
                        v -> AnchorMacroMod.explodeDelay = (int) Math.round(v), AnchorMacroMenu::ms),
                new Slider("Randomization", 0.0D, 0.5D, 0.01D, () -> AnchorMacroMod.randomization,
                        v -> AnchorMacroMod.randomization = (float) v, v -> "±" + Math.round(v * 100.0D) + "%"),
                new Section("Slots"),
                new Slider("Explode slot", 1.0D, 9.0D, 1.0D, () -> AnchorMacroMod.explodeSlot,
                        v -> AnchorMacroMod.explodeSlot = (int) Math.round(v), v -> "Slot " + Math.round(v)),
                new Toggle("Switch back to previous slot", () -> AnchorMacroMod.switchBack, v -> AnchorMacroMod.switchBack = v)));
    }

    private static String ms(double v) {
        return v < 0.5D ? "Instant" : Math.round(v) + " ms";
    }

    private static String[] names() {
        String[] names = new String[AnchorMacroMod.PRESETS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = AnchorMacroMod.PRESETS[i].name();
        }
        return names;
    }

    /** Gewaehlte Voreinstellung (-1 = eigene Werte). */
    private static int preset() {
        for (int i = 0; i < AnchorMacroMod.PRESETS.length; i++) {
            AnchorMacroMod.Preset p = AnchorMacroMod.PRESETS[i];
            if (AnchorMacroMod.placeDelay == p.place() && AnchorMacroMod.chargeDelay == p.charge()
                    && AnchorMacroMod.explodeDelay == p.explode() && Math.abs(AnchorMacroMod.randomization - p.random()) < 0.005F) {
                return i;
            }
        }
        return -1;
    }
}
