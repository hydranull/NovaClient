package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.Compat;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Chat Macro: bis zu 5 Tasten, die je eine Nachricht oder einen Befehl schicken ("/home", "gg", ...) - oder den Text
 * nur in den Chat legen, um ihn noch zu aendern. Eine kurze Sperre verhindert, dass man aus Versehen spammt.
 * (Laengere Ablaeufe mit Warten und Tasten: Modul "Macros".)
 */
public class ChatMacroMod extends GameTickMod {
    public static final int SLOTS = 5;
    public static final String[] ACTIONS = {"Send", "Open in chat"};

    public static final int[] KEYS = new int[SLOTS];
    public static final String[] TEXTS = new String[SLOTS];
    public static int action = 0;
    /** Mindestabstand zwischen zwei gesendeten Nachrichten in Sekunden. */
    public static double cooldown = 1.0D;

    static {
        Arrays.fill(KEYS, NO_KEY);
        Arrays.fill(TEXTS, "");
    }

    private final boolean[] down = new boolean[SLOTS];
    private long lastSent;

    public ChatMacroMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        for (int i = 0; i < SLOTS; i++) {
            boolean pressed = KEYS[i] != NO_KEY && client.gui.screen() == null
                    && InputConstants.isKeyDown(KEYS[i]);
            if (pressed && !down[i]) {
                run(client, TEXTS[i].trim());
            }
            down[i] = pressed;
        }
    }

    private void run(Minecraft client, String text) {
        ClientPacketListener connection = client.getConnection();
        if (text.isEmpty() || text.equals("/") || connection == null || client.player == null) {
            return;
        }
        if (action == 1) {
            client.gui.setScreen(new ChatScreen(text, false));
            return;
        }
        long now = System.currentTimeMillis();
        long left = lastSent + Math.round(cooldown * 1000.0D) - now;
        if (left > 0L) {
            Compat.actionBar(client.player, Component.literal(String.format(Locale.ROOT, "§7Chat Macro: wait §f%.1fs", left / 1000.0D)));
            return;
        }
        lastSent = now;
        if (text.startsWith("/")) {
            connection.sendCommand(text.substring(1));
        } else {
            connection.sendChat(text);
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        List<ModOptionsMenu.Option> options = new ArrayList<>();
        options.add(new ModOptionsMenu.Choice("On key press", ACTIONS, () -> action, v -> action = v));
        options.add(new ModOptionsMenu.Slider("Cooldown", 0.5D, 10.0D, 0.5D, () -> cooldown, v -> cooldown = v,
                v -> String.format(Locale.ROOT, "%.1f s", v)));
        for (int i = 0; i < SLOTS; i++) {
            int slot = i;
            options.add(new ModOptionsMenu.Text("Macro " + (slot + 1), () -> TEXTS[slot], v -> TEXTS[slot] = v, 256));
            options.add(new ModOptionsMenu.KeyBind("Macro " + (slot + 1) + " key", () -> KEYS[slot], v -> KEYS[slot] = v));
        }
        return new ModOptionsMenu(parent, name, "Messages and commands on a key", options);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "action", String.valueOf(action));
        properties.setProperty(prefix + "cooldown", String.valueOf(cooldown));
        for (int i = 0; i < SLOTS; i++) {
            properties.setProperty(prefix + "key" + i, String.valueOf(KEYS[i]));
            properties.setProperty(prefix + "text" + i, TEXTS[i]);
        }
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            action = Math.floorMod(Integer.parseInt(properties.getProperty(prefix + "action", String.valueOf(action)).trim()), ACTIONS.length);
            cooldown = Math.max(0.5D, Math.min(10.0D, Double.parseDouble(properties.getProperty(prefix + "cooldown", String.valueOf(cooldown)).trim())));
            for (int i = 0; i < SLOTS; i++) {
                KEYS[i] = Integer.parseInt(properties.getProperty(prefix + "key" + i, String.valueOf(KEYS[i])).trim());
            }
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        for (int i = 0; i < SLOTS; i++) {
            TEXTS[i] = properties.getProperty(prefix + "text" + i, TEXTS[i]);
        }
    }
}
