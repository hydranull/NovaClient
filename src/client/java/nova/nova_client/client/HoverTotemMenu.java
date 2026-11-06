package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.HoverTotemMod;

import java.util.List;

/**
 * Einstellungen fuer HoverTotem: wie lange die Maus auf dem Totem sein muss, bevor es in die Hotbar geht.
 */
public class HoverTotemMenu extends ModOptionsMenu {
    public HoverTotemMenu(Screen parent) {
        super(parent, "HoverTotem", List.of(
                new Slider("Hover delay", 0.0D, HoverTotemMod.MAX_DELAY, 10.0D, () -> HoverTotemMod.delay,
                        v -> HoverTotemMod.delay = (int) Math.round(v), v -> Math.round(v) == 0 ? "Instant" : Math.round(v) + " ms")));
    }
}
