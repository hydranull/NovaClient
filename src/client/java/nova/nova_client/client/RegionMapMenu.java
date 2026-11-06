package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.RegionMapMod;

import java.util.List;

/**
 * Einstellungen fuer Region Map: Regler fuer die Groesse, Koordinaten und Legende an/aus und "Move on screen" (HUD-Editor).
 */
public class RegionMapMenu extends ModOptionsMenu {
    public RegionMapMenu(Screen parent) {
        super(parent, "Region Map", List.of(
                new Slider("Size", RegionMapMod.MIN_SCALE, RegionMapMod.MAX_SCALE, 0.05D, () -> RegionMapMod.scale,
                        v -> RegionMapMod.scale = (float) v, v -> Math.round(v * 100.0D) + "%"),
                new Toggle("Show coordinates", () -> RegionMapMod.showCoords, v -> RegionMapMod.showCoords = v),
                new Toggle("Show legend", () -> RegionMapMod.showLegend, v -> RegionMapMod.showLegend = v),
                new Actions(List.of(open("Move on screen", HudEditorScreen::new)))));
    }
}
