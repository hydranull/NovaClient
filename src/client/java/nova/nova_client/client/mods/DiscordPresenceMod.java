package nova.nova_client.client.mods;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import nova.nova_client.client.DiscordPresence;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaLinks;

import java.util.List;
import java.util.Properties;

/**
 * Discord Presence: im Discord-Profil steht "Spielt Nova Client" mit Nova-Logo, Spielzeit, Server und einem Knopf zum
 * Nova-Discord. Die Verbindung laeuft im Hintergrund ({@link DiscordPresence}); ist Discord zu, passiert nichts.
 */
public class DiscordPresenceMod extends GameTickMod {
    public static boolean showServer = true;

    private static boolean enabled;
    private static int ticks;

    public DiscordPresenceMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        DiscordPresenceMod.enabled = enabled;
        DiscordPresence.setEnabled(enabled);
        ticks = 0;
    }

    @Override
    public void execute(Minecraft client) {
        // aktualisiert wird in tick() - auch im Hauptmenue
    }

    /** Jeden Tick (auch ohne Welt): alle 2 Sekunden die Anzeige auffrischen. */
    public static void tick(Minecraft client) {
        if (!enabled || ticks++ % 40 != 0) {
            return;
        }
        String version = FabricLoader.getInstance().getModContainer("nova_client")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        String details;
        if (client.level == null) {
            details = "In the menus";
        } else if (client.hasSingleplayerServer()) {
            details = "Singleplayer";
        } else {
            ServerData server = client.getCurrentServer();
            details = showServer && server != null ? "Playing on " + server.ip : "Playing multiplayer";
        }
        DiscordPresence.update(details, "Nova Client " + version);
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "\"Playing Nova Client\" on your Discord profile", List.of(
                new ModOptionsMenu.Toggle("Show server address", () -> showServer, v -> showServer = v),
                new ModOptionsMenu.Info(() -> NovaLinks.DISCORD_APP_ID.isBlank()
                        ? "Needs a Discord application ID (NovaLinks.DISCORD_APP_ID)"
                        : "Discord must be open on this PC")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "showServer", String.valueOf(showServer));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        showServer = Boolean.parseBoolean(properties.getProperty(prefix + "showServer", String.valueOf(showServer)));
    }
}
