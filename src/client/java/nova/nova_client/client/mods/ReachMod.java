package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ReachMenu;

import java.util.Locale;
import java.util.Properties;

/**
 * Hoehere Reichweite beim Schlagen. Die Reichweite ist einstellbar.
 * Vanilla-Server akzeptieren Treffer bis ca. 6 Bloecke (Standard-Reichweite 3 + 3 Toleranz),
 * darueber werden Schlaege vom Server ignoriert.
 */
public class ReachMod extends GameTickMod {
    public static final float VANILLA = 3.0F;
    public static final float MIN = 3.0F;
    public static final float MAX = 6.0F;
    public static final float DEFAULT = 4.5F;

    /** Eingestellte Reichweite in Bloecken. */
    public static float distance = DEFAULT;
    /** Nur wirken, wenn eine Waffe (Schwert, Axt, Streitkolben, Dreizack) in der Hand ist. */
    public static boolean weaponOnly = false;

    private static volatile boolean enabled;

    public ReachMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        ReachMod.enabled = enabled;
    }

    /** Gilt die erhoehte Reichweite gerade fuer diesen Spieler? (nur der eigene, clientseitig) */
    public static boolean applies(Player player) {
        if (!enabled || player != Minecraft.getInstance().player) {
            return false;
        }
        return !weaponOnly || player.getMainHandItem().has(DataComponents.WEAPON);
    }

    public static void setDistance(float value) {
        distance = Math.round(Math.max(MIN, Math.min(MAX, value)) * 10.0F) / 10.0F;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ReachMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "distance", String.format(Locale.ROOT, "%.1f", distance));
        properties.setProperty(prefix + "weaponOnly", String.valueOf(weaponOnly));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "distance");
        if (value != null) {
            try {
                setDistance(Float.parseFloat(value.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
        String weapon = properties.getProperty(prefix + "weaponOnly");
        if (weapon != null) {
            weaponOnly = Boolean.parseBoolean(weapon);
        }
    }
}
