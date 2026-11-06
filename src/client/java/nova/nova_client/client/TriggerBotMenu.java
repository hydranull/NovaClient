package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.TriggerBotMod;

import java.util.List;

/**
 * Einstellungen fuer Trigger Bot: Modus (mit / ohne Cooldown) und Schalter.
 */
public class TriggerBotMenu extends ModOptionsMenu {
    private static final String[] MODES = {"Cooldown", "No cooldown"};

    public TriggerBotMenu(Screen parent) {
        super(parent, "Trigger Bot", List.of(
                new Segmented("Mode", MODES, () -> TriggerBotMod.useCooldown ? 0 : 1, i -> TriggerBotMod.useCooldown = i == 0),
                new Info(() -> TriggerBotMod.useCooldown
                        ? "Waits for a full charge: max damage, crits and sweeps"
                        : "Hits every tick, ignoring the attack cooldown"),
                new Toggle("Only with weapon (sword, axe, mace, trident)", () -> TriggerBotMod.weaponOnly,
                        v -> TriggerBotMod.weaponOnly = v),
                new Toggle("Ignore teammates", () -> TriggerBotMod.ignoreTeam, v -> TriggerBotMod.ignoreTeam = v)));
    }
}
