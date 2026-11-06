package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ShieldBreakerMenu;

import java.util.Locale;
import java.util.Properties;

/**
 * Shield Breaker: schlaegst du auf ein Ziel, das gerade mit dem Schild blockt, wird fuer den Schlag auf eine Axt
 * aus der Hotbar gewechselt (Axt-Treffer schalten das Schild ab) und danach zurueck - sofort oder nach der
 * eingestellten Zeit. Hook in MultiPlayerGameModeMixin (attack): Wechsel vor dem Angriffs-Paket.
 */
public class ShieldBreakerMod extends GameTickMod {
    public static final float MAX_DELAY = 1.5F;

    /** Wie lange die Axt nach dem Schlag in der Hand bleibt (Sekunden), 0 = sofort zurueck. */
    public static float delay = 0.0F;

    private static volatile boolean enabled;
    /** Slot vor dem Wechsel zur Axt, -1 = kein Wechsel aktiv. */
    private static int previousSlot = -1;
    private static int axeSlot = -1;
    /** Zeitpunkt fuers Zurueckwechseln; 0 = noch nicht geschlagen. */
    private static long restoreAt;

    public ShieldBreakerMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        ShieldBreakerMod.enabled = enabled;
        if (!enabled && client.player != null) {
            restore(client.player);
        }
    }

    /** Verzoegertes Zurueckwechseln. */
    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (previousSlot < 0 || restoreAt == 0L || player == null) {
            return;
        }
        // Selbst auf einen anderen Slot gewechselt -> nichts mehr zuruecksetzen
        if (player.getInventory().getSelectedSlot() != axeSlot) {
            previousSlot = -1;
            return;
        }
        if (Util.getMillis() >= restoreAt) {
            restore(player);
        }
    }

    /** Vor dem Angriff: blockt das Ziel mit einem Schild, zur Axt wechseln. */
    public static void beforeAttack(LocalPlayer player, Entity target) {
        if (!enabled || !(target instanceof LivingEntity living) || !living.isBlocking()) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (player.getMainHandItem().is(ItemTags.AXES)) {
            if (previousSlot >= 0) {
                // Noch auf der Axt vom letzten Schlag: Wartezeit neu starten
                restoreAt = 0L;
            }
            return;
        }
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(ItemTags.AXES)) {
                previousSlot = inventory.getSelectedSlot();
                axeSlot = slot;
                restoreAt = 0L;
                inventory.setSelectedSlot(slot);
                return;
            }
        }
    }

    /** Nach dem Angriff: sofort zurueck (true) oder Zeitpunkt fuers Zurueckwechseln setzen. */
    public static boolean afterAttack(LocalPlayer player) {
        if (previousSlot < 0 || restoreAt != 0L) {
            return false;
        }
        if (delay <= 0.0F) {
            return restore(player);
        }
        restoreAt = Util.getMillis() + Math.round(delay * 1000.0F);
        return false;
    }

    private static boolean restore(LocalPlayer player) {
        if (previousSlot < 0) {
            return false;
        }
        player.getInventory().setSelectedSlot(previousSlot);
        previousSlot = -1;
        restoreAt = 0L;
        return true;
    }

    public static void setDelay(float value) {
        delay = Math.round(Math.max(0.0F, Math.min(MAX_DELAY, value)) * 20.0F) / 20.0F;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ShieldBreakerMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "delay", String.format(Locale.ROOT, "%.2f", delay));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "delay");
        if (value != null) {
            try {
                setDelay(Float.parseFloat(value.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
