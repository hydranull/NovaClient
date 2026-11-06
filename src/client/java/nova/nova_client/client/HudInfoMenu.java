package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.HudInfoMod;

import java.util.List;

/**
 * Einstellungen fuer HudInfo: Groesse, Position, welche Bereiche angezeigt werden und das Aussehen (HudStyleMenu).
 */
public class HudInfoMenu extends ModOptionsMenu {
    private static final String[] POSITIONS = {"Top left", "Top right"};

    public HudInfoMenu(Screen parent) {
        super(parent, "HUD Info", List.of(
                new Segmented("Size", percent(HudInfoMod.SCALES), () -> HudInfoMod.scaleIndex, i -> HudInfoMod.scaleIndex = i),
                new Segmented("Position", POSITIONS, () -> HudInfoMod.right ? 1 : 0, i -> HudInfoMod.right = i == 1),
                new Section("Show"),
                new Toggle("FPS", () -> HudInfoMod.showFps, v -> HudInfoMod.showFps = v),
                new Toggle("Coordinates", () -> HudInfoMod.showCoords, v -> HudInfoMod.showCoords = v),
                new Toggle("Direction & speed", () -> HudInfoMod.showCompass, v -> HudInfoMod.showCompass = v),
                new Toggle("Ping & TPS", () -> HudInfoMod.showNetwork, v -> HudInfoMod.showNetwork = v),
                new Toggle("Clock", () -> HudInfoMod.showClock, v -> HudInfoMod.showClock = v),
                new Actions(List.of(
                        open("Move on screen", HudEditorScreen::new),
                        open("Customize look", back -> new HudStyleMenu(back, "HUD Info", HudInfoMod.style,
                                HudInfoMod::previewRows, 0))))));
    }
}
