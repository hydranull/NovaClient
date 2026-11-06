package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import nova.nova_client.client.mods.KillEffectsMod;

import java.util.HashMap;
import java.util.Map;

/**
 * Kampf-Ereignisse vom Server (aus ClientPacketListenerMixin, nur auf dem Haupt-Thread): wen hast du getroffen,
 * war es ein Crit, wer ist gestorben. Damage Numbers und Kill Effects lesen das hier.
 */
public final class CombatEvents {
    private static final byte EVENT_DEATH = 3;

    /** Entity-ID -> Zeitpunkt (ms) des letzten Treffers durch dich. */
    private static final Map<Integer, Long> HIT_BY_ME = new HashMap<>();
    /** Entity-ID -> Zeitpunkt (ms) des letzten Crits auf dieses Entity. */
    private static final Map<Integer, Long> CRITS = new HashMap<>();
    private static long lastCleanup;

    private CombatEvents() {
    }

    /** Ein Entity hat Schaden bekommen; causeId = wer ihn verursacht hat (-1 = niemand). */
    public static void onDamage(int entityId, int causeId) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && causeId == client.player.getId() && entityId != causeId) {
            HIT_BY_ME.put(entityId, System.currentTimeMillis());
        }
        cleanup();
    }

    /** Animations-Paket: Crit-Partikel auf einem Entity. */
    public static void onAnimate(int entityId, int action) {
        if (action == ClientboundAnimatePacket.CRITICAL_HIT || action == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT) {
            CRITS.put(entityId, System.currentTimeMillis());
            cleanup(); // auch beim Zuschauen fremder Kaempfe (sonst nur bei eigenen Treffern)
        }
    }

    /** Entity-Ereignis: 3 = gestorben. */
    public static void onEntityEvent(Entity entity, byte eventId) {
        Minecraft client = Minecraft.getInstance();
        if (entity == null || client.player == null) {
            return;
        }
        if (eventId == EVENT_DEATH && entity instanceof LivingEntity living && living != client.player) {
            if (hitByMeRecently(living.getId(), 5000L)) {
                KillEffectsMod.onKill(living);
            }
            HIT_BY_ME.remove(living.getId());
        }
    }

    public static boolean hitByMeRecently(int entityId, long withinMs) {
        Long time = HIT_BY_ME.get(entityId);
        return time != null && System.currentTimeMillis() - time <= withinMs;
    }

    public static boolean critRecently(int entityId, long withinMs) {
        Long time = CRITS.get(entityId);
        return time != null && System.currentTimeMillis() - time <= withinMs;
    }

    /** Alte Eintraege ab und zu wegraeumen. */
    private static void cleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < 10_000L) {
            return;
        }
        lastCleanup = now;
        HIT_BY_ME.values().removeIf(time -> now - time > 10_000L);
        CRITS.values().removeIf(time -> now - time > 10_000L);
    }
}
