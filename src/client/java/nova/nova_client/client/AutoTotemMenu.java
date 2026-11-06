package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.AutoTotemMod;

import java.util.List;

/**
 * Einstellungen fuer AutoTotem: Regler fuer die Verzoegerung und den Zufall.
 */
public class AutoTotemMenu extends ModOptionsMenu {
    public AutoTotemMenu(Screen parent) {
        super(parent, "AutoTotem", List.of(
                new Slider("Delay", 0.0D, AutoTotemMod.MAX_DELAY, 5.0D, () -> AutoTotemMod.delay,
                        v -> AutoTotemMod.delay = (int) Math.round(v), v -> v < 0.5D ? "Instant" : Math.round(v) + " ms"),
                new Slider("Randomization", 0.0D, 0.5D, 0.01D, () -> AutoTotemMod.randomization,
                        v -> AutoTotemMod.randomization = (float) v, v -> "±" + Math.round(v * 100.0D) + "%"),
                new Info(() -> "Delay before the new totem goes to your offhand")));
    }
}
