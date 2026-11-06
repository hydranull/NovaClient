package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.EspMod;

import java.util.List;

/**
 * ESP-Einstellungen: Umriss und Linien getrennt schaltbar, dazu welche Ziele markiert werden (mit ihrer Farbe).
 */
public class EspMenu extends ModOptionsMenu {
    public EspMenu(Screen parent) {
        super(parent, "ESP", List.of(
                new Section("Display"),
                new Toggle("Outline", () -> EspMod.outline, v -> EspMod.outline = v),
                new Toggle("Tracers", () -> EspMod.tracers, v -> EspMod.tracers = v),
                new Toggle("Player names + distance", () -> EspMod.names, v -> EspMod.names = v),
                new Section("Targets"),
                new Toggle("Players", () -> EspMod.players, v -> EspMod.players = v, EspMod.PLAYER_COLOR),
                new Toggle("Monsters", () -> EspMod.hostiles, v -> EspMod.hostiles = v, EspMod.HOSTILE_COLOR),
                new Toggle("Other mobs", () -> EspMod.others, v -> EspMod.others = v, EspMod.PASSIVE_COLOR),
                new Info(EspMenu::range)));
    }

    /** Der Server schickt Spieler hoechstens bis (Sichtweite - 1) * 16 Bloecke (dazu seine entity-tracking-range). */
    private static String range() {
        int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        return "View distance " + chunks + " chunks: players max ~" + Math.max(0, chunks - 1) * 16 + " blocks";
    }
}
