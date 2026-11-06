package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.SwingSpeedMenu;

import java.util.Locale;
import java.util.Properties;

/**
 * Aendert clientseitig die Geschwindigkeit der Schlag-Animation der Haupthand (Pickaxe, Schwert, ...).
 * Nur die Animation wird schneller oder langsamer, Abbau- und Angriffsgeschwindigkeit bleiben gleich.
 * Hook im LivingEntity-Mixin (getCurrentSwingDuration / updateSwingTime), nur fuer den eigenen Spieler.
 */
public class SwingSpeedMod extends GameTickMod {
    public static final float MIN = 0.2F;
    public static final float MAX = 3.0F;
    public static final float DEFAULT = 0.6F;

    /** Faktor auf die Animationsgeschwindigkeit. 1.0 = Vanilla, 0.5 = halb so schnell, 2.0 = doppelt so schnell. */
    public static float speed = DEFAULT;

    private static volatile boolean enabled;

    public SwingSpeedMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        SwingSpeedMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Gilt die geaenderte Geschwindigkeit fuer dieses Entity? (nur der eigene Spieler, nur Haupthand) */
    public static boolean applies(LivingEntity entity) {
        if (!enabled || entity != Minecraft.getInstance().player) {
            return false;
        }
        //? if >=26.1 {
        LivingEntity.SwingDescription swing = entity.getCurrentSwing();
        return swing == null || swing.hand() == InteractionHand.MAIN_HAND;
        //?} else {
        /*return entity.swingingArm == null || entity.swingingArm == InteractionHand.MAIN_HAND;
        *///?}
    }

    /** Vanilla-Dauer in Ticks -> neue Dauer in Ticks (mindestens 1). */
    public static float duration(int vanillaTicks) {
        return Math.max(1.0F, vanillaTicks / speed);
    }

    public static void setSpeed(float value) {
        speed = Math.round(Math.max(MIN, Math.min(MAX, value)) * 10.0F) / 10.0F;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new SwingSpeedMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "speed", String.format(Locale.ROOT, "%.1f", speed));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "speed");
        if (value != null) {
            try {
                setSpeed(Float.parseFloat(value.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
