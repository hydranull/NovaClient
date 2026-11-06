package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.VrMenuMod;

import java.util.List;
import java.util.Locale;

/**
 * Einstellungen fuer VR Menu: Neigung, Groesse und Head-Tracking per Regler, Schweben und Fensterleiste
 * als Schalter, Position zuruecksetzen. Aenderungen sieht man sofort, weil dieses Menue selbst schwebt.
 */
public class VrMenuMenu extends ModOptionsMenu {
    public VrMenuMenu(Screen parent) {
        super(parent, "VR Menu", List.of(
                new Slider("Tilt", VrMenuMod.MIN_TILT, VrMenuMod.MAX_TILT, 1.0D, () -> VrMenuMod.tilt,
                        v -> VrMenuMod.setTilt((float) v), v -> String.format(Locale.ROOT, "%+.0f°", v)),
                new Slider("Size", VrMenuMod.MIN_SIZE, VrMenuMod.MAX_SIZE, 0.01D, () -> VrMenuMod.size,
                        v -> VrMenuMod.setSize((float) v), v -> Math.round(v * 100.0D) + "%"),
                new Slider("Head tracking", 0.0D, 1.0D, 0.01D, () -> VrMenuMod.headTracking,
                        v -> VrMenuMod.setHeadTracking((float) v), v -> Math.round(v * 100.0D) + "%"),
                new Toggle("Floating", () -> VrMenuMod.floating, v -> VrMenuMod.floating = v),
                new Toggle("Window bar", () -> VrMenuMod.windowBar, v -> VrMenuMod.windowBar = v),
                new Action(() -> "Reset position", () -> VrMenuMod.setOffset(0.0F, 0.0F))));
    }
}
