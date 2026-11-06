package nova.nova_client.client.mods;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import nova.nova_client.client.EspOptionsMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Auto Reconnect: wird man von einem Server gekickt (Disconnect-Bildschirm), verbindet es nach der eingestellten Zeit
 * (0-5 Sekunden) automatisch neu mit demselben Server. Verlaesst man den Bildschirm vorher (z.B. "Zurueck"), passiert
 * nichts. Schlaegt das Verbinden fehl, erscheint wieder der Disconnect-Bildschirm und es wird erneut versucht.
 */
public class AutoReconnectMod extends GameTickMod {
    public static final int[] DELAYS = {0, 1, 2, 3, 4, 5};

    // Einstellungen
    public static int delaySeconds = 3;
    public static boolean showCountdown = true;

    private static volatile boolean enabled;
    /** Zuletzt verbundener Server (wird waehrend man verbunden ist laufend gemerkt). */
    private static ServerData lastServer;
    private static boolean registered;

    public AutoReconnectMod(String name, ModCategory category) {
        super(name, category);
        registerScreenHook();
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        AutoReconnectMod.enabled = enabled;
    }

    /** Laeuft nur, solange man in einer Welt ist: aktuellen Server merken (Einzelspieler: keiner). */
    @Override
    public void execute(Minecraft client) {
        if (client.isLocalServer()) {
            lastServer = null;
        } else if (client.getCurrentServer() != null) {
            lastServer = client.getCurrentServer();
        }
    }

    private static void registerScreenHook() {
        if (registered) {
            return;
        }
        registered = true;
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ServerData server = lastServer;
            if (!enabled || server == null || !(screen instanceof DisconnectedScreen)) {
                return;
            }
            long reconnectAt = Util.getMillis() + delaySeconds * 1000L;
            ScreenEvents.afterTick(screen).register(current -> {
                // Nur, wenn der Disconnect-Bildschirm noch offen ist (sonst hat man selbst abgebrochen)
                if (enabled && client.gui.screen() == current && Util.getMillis() >= reconnectAt) {
                    reconnect(client, server);
                }
            });
            ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, tickProgress) -> {
                if (!enabled || !showCountdown) {
                    return;
                }
                long left = Math.max(0L, reconnectAt - Util.getMillis());
                String text = left > 0
                        ? "Reconnecting to " + server.ip + " in " + String.format(Locale.ROOT, "%.1f", left / 1000.0F) + "s"
                        : "Reconnecting to " + server.ip + "...";
                NovaGui.centered(graphics, client.font, text, current.width / 2, 12, NovaGui.BRAND);
            });
        });
    }

    private static void reconnect(Minecraft client, ServerData server) {
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), client,
                ServerAddress.parseString(server.ip), server, false, null);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        List<EspOptionsMenu.Toggle> toggles = List.of(
                new EspOptionsMenu.Toggle("Show countdown", () -> showCountdown, () -> showCountdown = !showCountdown));
        EspOptionsMenu.Presets delay = new EspOptionsMenu.Presets("Reconnect after", DELAYS,
                () -> delaySeconds, value -> delaySeconds = value, "s");
        return new EspOptionsMenu(parent, name, "", 0x3DDC97,
                toggles, delay);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "delay", String.valueOf(delaySeconds));
        properties.setProperty(prefix + "countdown", String.valueOf(showCountdown));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            delaySeconds = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "delay",
                    String.valueOf(delaySeconds)).trim()), 0, 5);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        showCountdown = Boolean.parseBoolean(properties.getProperty(prefix + "countdown", String.valueOf(showCountdown)));
    }
}
