package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.HotkeyListMod;

import java.util.List;

/**
 * Einstellungen fuer die Hotkey-Liste: Groesse, was angezeigt wird und Position.
 */
public class HotkeyListMenu extends ModOptionsMenu {
    public HotkeyListMenu(Screen parent) {
        super(parent, "Hotkeys", List.of(
                new Segmented("Size", percent(HotkeyListMod.SCALES), () -> HotkeyListMod.scaleIndex, i -> HotkeyListMod.scaleIndex = i),
                new Toggle("Show macros", () -> HotkeyListMod.showMacros, v -> HotkeyListMod.showMacros = v),
                new Actions(List.of(open("Move on screen", HudEditorScreen::new)))));
    }
}
