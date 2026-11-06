package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.ReachMod;

import java.util.List;
import java.util.Locale;

/**
 * Einstellungen fuer Reach: Reichweite per Regler (3,0 - 6,0 Bloecke), Voreinstellungen und "nur mit Waffe".
 */
public class ReachMenu extends ModOptionsMenu {
    private static final float[] PRESETS = {3.5F, 4.0F, 4.5F, 5.0F, 5.5F, 6.0F};
    private static final String[] LABELS = {"3.5", "4.0", "4.5", "5.0", "5.5", "6.0"};

    public ReachMenu(Screen parent) {
        super(parent, "Reach", List.of(
                new Slider("Attack range", ReachMod.MIN, ReachMod.MAX, 0.1D, () -> ReachMod.distance,
                        v -> ReachMod.setDistance((float) v), v -> String.format(Locale.ROOT, "%.1f blocks", v)),
                new Segmented("", LABELS, ReachMenu::preset, i -> ReachMod.setDistance(PRESETS[i])),
                new Toggle("Weapon only", () -> ReachMod.weaponOnly, v -> ReachMod.weaponOnly = v)));
    }

    /** Gewaehlte Voreinstellung (-1 = keine passt). */
    private static int preset() {
        for (int i = 0; i < PRESETS.length; i++) {
            if (Math.abs(ReachMod.distance - PRESETS[i]) < 0.05F) {
                return i;
            }
        }
        return -1;
    }
}
