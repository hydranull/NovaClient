package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.TpaHereMacroMod;

import java.util.List;
import java.util.Locale;

/**
 * Einstellungen fuer TpaHere Macro: Spielername (frei eintippen) und Abstand zwischen den Befehlen per Regler.
 */
public class TpaHereMacroMenu extends ModOptionsMenu {
    public TpaHereMacroMenu(Screen parent) {
        super(parent, "TpaHere Macro", List.of(
                new Text("Player", () -> TpaHereMacroMod.target, v -> TpaHereMacroMod.target = TpaHereMacroMod.cleanName(v), 16),
                new Slider("Cooldown", TpaHereMacroMod.MIN, TpaHereMacroMod.MAX, 0.05D, () -> TpaHereMacroMod.cooldown,
                        v -> TpaHereMacroMod.setCooldown((float) v), TpaHereMacroMenu::seconds),
                new Info(TpaHereMacroMenu::hint)));
    }

    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.2fs", value);
    }

    private static String hint() {
        return TpaHereMacroMod.target.isEmpty() ? "Enter a player name first"
                : "Sends /tpahere " + TpaHereMacroMod.target + " every " + seconds(TpaHereMacroMod.cooldown);
    }
}
