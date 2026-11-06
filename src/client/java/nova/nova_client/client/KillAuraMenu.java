package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.KillAuraMod;

import java.util.List;
import java.util.Locale;

/**
 * Einstellungen fuer KillAura: Zielen (Reichweite, FOV, Aufladung, Drehgeschwindigkeit, Prioritaet), welche Ziele und
 * wie angegriffen wird.
 */
public class KillAuraMenu extends ModOptionsMenu {
    public KillAuraMenu(Screen parent) {
        super(parent, "KillAura", List.of(
                new Section("Targeting"),
                new Slider("Range", 2.0D, 6.0D, 0.1D, () -> KillAuraMod.range, v -> KillAuraMod.range = (float) v,
                        v -> String.format(Locale.ROOT, "%.1f blocks", v)),
                new Slider("FOV", 30.0D, 360.0D, 5.0D, () -> KillAuraMod.fov, v -> KillAuraMod.fov = (float) v,
                        v -> Math.round(v) + "°"),
                new Slider("Attack at charge", 0.5D, 1.0D, 0.01D, () -> KillAuraMod.cooldown, v -> KillAuraMod.cooldown = (float) v,
                        v -> Math.round(v * 100.0D) + "%"),
                new Slider("Rotation speed", 5.0D, 180.0D, 5.0D, () -> KillAuraMod.rotationSpeed,
                        v -> KillAuraMod.rotationSpeed = (float) v, v -> v >= 179.5D ? "Instant" : Math.round(v) + "°/tick"),
                new Segmented("Priority", priorities(), () -> KillAuraMod.priority.ordinal(),
                        i -> KillAuraMod.priority = KillAuraMod.Priority.values()[i]),
                new Section("Targets"),
                new Toggle("Players", () -> KillAuraMod.players, v -> KillAuraMod.players = v),
                new Toggle("Hostile mobs", () -> KillAuraMod.hostiles, v -> KillAuraMod.hostiles = v),
                new Toggle("Passive mobs", () -> KillAuraMod.passives, v -> KillAuraMod.passives = v),
                new Toggle("Invisible entities", () -> KillAuraMod.invisibles, v -> KillAuraMod.invisibles = v),
                new Section("Behavior"),
                new Toggle("Through walls", () -> KillAuraMod.throughWalls, v -> KillAuraMod.throughWalls = v),
                new Toggle("Only with weapon", () -> KillAuraMod.weaponOnly, v -> KillAuraMod.weaponOnly = v),
                new Toggle("Turn to target", () -> KillAuraMod.rotate, v -> KillAuraMod.rotate = v),
                new Toggle("Ignore teammates", () -> KillAuraMod.ignoreTeam, v -> KillAuraMod.ignoreTeam = v)));
    }

    private static String[] priorities() {
        KillAuraMod.Priority[] values = KillAuraMod.Priority.values();
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i].label;
        }
        return labels;
    }
}
