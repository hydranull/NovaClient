package nova.nova_client.client.mods;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import nova.nova_client.client.EspOptionsMenu;
import nova.nova_client.client.ModCategory;

import java.util.List;
import java.util.Properties;

/**
 * Player Detector: trennt die Verbindung sofort, sobald ein anderer Spieler in der eingestellten Naehe ist.
 * Danach schaltet sich der Mod selbst aus, damit man beim Wieder-Einloggen nicht gleich wieder rausfliegt.
 */
public class PlayerDetectorMod extends GameTickMod {
    private static final int[] RANGES = {16, 32, 48, 64, 128};

    // Einstellungen
    public static int range = 48;
    /** NPCs (Spieler-Entities, die nicht in der Tab-Liste stehen, z.B. Citizens) ignorieren. */
    public static boolean ignoreNpcs = true;

    public PlayerDetectorMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (client.level == null || client.player == null || connection == null) {
            return;
        }
        for (Player player : client.level.players()) {
            if (player == client.player || player.isSpectator()) {
                continue;
            }
            if (ignoreNpcs && connection.getPlayerInfo(player.getUUID()) == null) {
                continue;
            }
            double distance = player.distanceTo(client.player);
            if (distance <= range) {
                // Erst ausschalten, dann trennen: beim naechsten Login ist der Mod aus
                toggle(client);
                connection.getConnection().disconnect(Component.literal("Player Detector").withStyle(ChatFormatting.RED)
                        .append(Component.literal("\n\n" + player.getPlainTextName() + " was " + Math.round(distance)
                                + " blocks away.").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal("\nThe module is now off.").withStyle(ChatFormatting.GRAY)));
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        List<EspOptionsMenu.Toggle> toggles = List.of(
                new EspOptionsMenu.Toggle("Ignore NPCs (not in tab list)", () -> ignoreNpcs, () -> ignoreNpcs = !ignoreNpcs));
        EspOptionsMenu.Presets presets = new EspOptionsMenu.Presets("Disconnect when a player is within", RANGES,
                () -> range, value -> range = value, "m");
        return new EspOptionsMenu(parent, name, "", 0xFF5470, toggles, presets);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "range", String.valueOf(range));
        properties.setProperty(prefix + "ignoreNpcs", String.valueOf(ignoreNpcs));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            range = Math.max(1, Integer.parseInt(properties.getProperty(prefix + "range", String.valueOf(range)).trim()));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        ignoreNpcs = Boolean.parseBoolean(properties.getProperty(prefix + "ignoreNpcs", String.valueOf(ignoreNpcs)));
    }
}
