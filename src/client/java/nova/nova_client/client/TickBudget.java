package nova.nova_client.client;

/**
 * Zeitbudget fuer schrittweise Chunk-Scans im Tick (BlockESP, Hole ESP, ...). Jeder Scanner behaelt sein Chunk-Limit
 * pro Tick, hoert aber auf, sobald er in diesem Tick schon {@link #SCAN_NANOS} gebraucht hat - mindestens ein Chunk
 * laeuft immer. Schnelle PCs erreichen vorher das Chunk-Limit (alles wie bisher), langsame Laptops scannen etwas
 * langsamer statt jeden Tick zu ruckeln. Die Ergebnisse sind dieselben.
 */
public final class TickBudget {
    /** Hoechstens so viel Zeit pro Scanner und Tick (3 ms von 50 ms). */
    public static final long SCAN_NANOS = 3_000_000L;

    private TickBudget() {
    }

    /** Ende des Budgets ab jetzt (System.nanoTime-Zeitpunkt). */
    public static long deadline() {
        return System.nanoTime() + SCAN_NANOS;
    }

    /** Noch einen Chunk scannen? Der erste (done == 0) immer, danach nur innerhalb des Budgets. */
    public static boolean more(int done, long deadline) {
        return done == 0 || System.nanoTime() - deadline < 0L;
    }
}
