package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.TpaHereMacroMenu;

import java.util.Locale;
import java.util.Properties;

/**
 * Schickt waehrend das Modul an ist immer wieder "/tpahere Name" an den eingestellten Spieler.
 * Abstand zwischen zwei Befehlen per Regler (0,3 - 1,0 Sekunden).
 */
public class TpaHereMacroMod extends GameTickMod {
    public static final float MIN = 0.3F;
    public static final float MAX = 1.0F;
    public static final float DEFAULT = 0.5F;

    /** Zielspieler (nur Zeichen, die in Minecraft-Namen erlaubt sind). */
    public static String target = "";
    /** Abstand zwischen zwei Befehlen in Sekunden. */
    public static float cooldown = DEFAULT;

    private long lastSent;

    public TpaHereMacroMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        // Beim Einschalten sofort den ersten Befehl schicken
        lastSent = 0L;
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || target.isEmpty()) {
            return;
        }
        long now = Util.getMillis();
        if (now - lastSent >= Math.round(cooldown * 1000.0F)) {
            lastSent = now;
            player.connection.sendCommand("tpahere " + target);
        }
    }

    /** Nur A-Z, a-z, 0-9 und _ (max. 16 Zeichen), damit kein anderer Befehl entstehen kann. */
    public static String cleanName(String value) {
        String clean = value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "");
        return clean.length() > 16 ? clean.substring(0, 16) : clean;
    }

    public static void setCooldown(float value) {
        cooldown = Math.round(Math.max(MIN, Math.min(MAX, value)) * 20.0F) / 20.0F;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new TpaHereMacroMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "target", target);
        properties.setProperty(prefix + "cooldown", String.format(Locale.ROOT, "%.2f", cooldown));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        target = cleanName(properties.getProperty(prefix + "target", target));
        String value = properties.getProperty(prefix + "cooldown");
        if (value != null) {
            try {
                setCooldown(Float.parseFloat(value.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
