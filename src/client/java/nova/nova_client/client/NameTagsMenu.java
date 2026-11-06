package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.NameTagsMod;

import java.util.List;

/**
 * Einstellungen fuer Name tags: Regler fuer die Groesse.
 */
public class NameTagsMenu extends ModOptionsMenu {
    public NameTagsMenu(Screen parent) {
        super(parent, "Name tags", List.of(
                new Slider("Size", NameTagsMod.MIN_SIZE, NameTagsMod.MAX_SIZE, 0.05D, () -> NameTagsMod.size,
                        v -> NameTagsMod.size = (float) v, v -> Math.round(v * 100.0D) + "%")));
    }
}
