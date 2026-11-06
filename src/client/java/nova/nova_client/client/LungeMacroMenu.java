package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.LungeMacroMod;

import java.util.List;

/**
 * Einstellungen fuer LungeMacro: Taste fuer den Lunge festlegen (anklicken, dann Taste druecken; Entf/Backspace loescht).
 */
public class LungeMacroMenu extends ModOptionsMenu {
    public LungeMacroMenu(Screen parent) {
        super(parent, "LungeMacro", List.of(
                new KeyBind("Lunge key", () -> LungeMacroMod.lungeKey, v -> LungeMacroMod.lungeKey = v),
                new Info(() -> "Needs a spear with Lunge in your hotbar")));
    }
}
