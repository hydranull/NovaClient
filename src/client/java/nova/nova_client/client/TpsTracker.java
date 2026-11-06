package nova.nova_client.client;

import net.minecraft.util.Util;

/**
 * Schaetzt die Server-TPS aus dem Abstand der Zeit-Pakete (Server sendet sie alle 20 Ticks).
 */
public final class TpsTracker {
    private static final int SAMPLES = 5;
    /** Nach so langer Zeit ohne Paket gilt der Wert als unbekannt. */
    private static final long STALE_MS = 5000L;

    private static final float[] samples = new float[SAMPLES];
    private static int count;
    private static int next;
    private static long lastPacket = -1L;

    private TpsTracker() {
    }

    /** Vom Mixin bei jedem Zeit-Paket aufgerufen (Hauptthread). */
    public static void onTimePacket() {
        long now = Util.getMillis();
        if (lastPacket > 0) {
            long interval = now - lastPacket;
            if (interval > 50L) {
                samples[next] = Math.min(20.0F, 20.0F * 1000.0F / interval);
                next = (next + 1) % SAMPLES;
                count = Math.min(SAMPLES, count + 1);
            }
        }
        lastPacket = now;
    }

    /** Durchschnittliche TPS oder -1, wenn unbekannt. */
    public static float tps() {
        if (count == 0 || Util.getMillis() - lastPacket > STALE_MS) {
            return -1.0F;
        }
        float sum = 0.0F;
        for (int i = 0; i < count; i++) {
            sum += samples[i];
        }
        return sum / count;
    }

    /** Beim Verlassen eines Servers zuruecksetzen. */
    public static void reset() {
        count = 0;
        next = 0;
        lastPacket = -1L;
    }
}
