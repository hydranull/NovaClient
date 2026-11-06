package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.RadarMod;

import java.util.List;

/**
 * Einstellungen fuer das Radar: Groesse, Reichweite, was angezeigt wird und Position.
 */
public class RadarMenu extends ModOptionsMenu {
    public RadarMenu(Screen parent) {
        super(parent, "Radar", List.of(
                new Segmented("Size", percent(RadarMod.SCALES), () -> RadarMod.scaleIndex, i -> RadarMod.scaleIndex = i),
                new Segmented("Range", ranges(), () -> RadarMod.rangeIndex, i -> RadarMod.rangeIndex = i),
                new Section("Show"),
                new Toggle("Rotate with view", () -> RadarMod.rotate, v -> RadarMod.rotate = v),
                new Toggle("Players", () -> RadarMod.players, v -> RadarMod.players = v),
                new Toggle("Hostile mobs", () -> RadarMod.hostiles, v -> RadarMod.hostiles = v),
                new Toggle("Animals", () -> RadarMod.animals, v -> RadarMod.animals = v),
                new Toggle("Player names", () -> RadarMod.names, v -> RadarMod.names = v),
                new Toggle("Coordinates", () -> RadarMod.coordinates, v -> RadarMod.coordinates = v),
                new Actions(List.of(open("Move on screen", HudEditorScreen::new)))));
    }

    private static String[] ranges() {
        String[] labels = new String[RadarMod.RANGES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = RadarMod.RANGES[i] + "m";
        }
        return labels;
    }
}
