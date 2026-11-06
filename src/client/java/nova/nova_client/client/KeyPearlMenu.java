package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.KeyPearlMod;

import java.util.List;

/**
 * Einstellungen fuer Key Pearl: Taste zum Werfen festlegen (anklicken, dann Taste druecken; Entf/Backspace loescht).
 */
public class KeyPearlMenu extends ModOptionsMenu {
    public KeyPearlMenu(Screen parent) {
        super(parent, "Key Pearl", List.of(
                new KeyBind("Throw key", () -> KeyPearlMod.throwKey, v -> KeyPearlMod.throwKey = v),
                new Info(() -> "Delete / Backspace removes the key")));
    }
}
