package nova.nova_client.client.activityinspector;

import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;

import java.util.BitSet;
import java.util.List;

/**
 * Activity Inspector: ordnet die Himmelslicht-Daten eines Licht-Pakets pro 16er-Sektion ein. Index 0 ist die Sektion
 * unter der Welt, Index sectionCount + 1 die ueber der Welt (so zaehlt auch das Paket).
 * <p>
 * Das Signal fuer eine Base: der Server schickt fuer eine Sektion unter der Erde ein echtes Licht-Array, das trotzdem
 * ueberall 0 ist ({@link Status#ZEROED}). Unberuehrter Untergrund kommt als "leer" (Empty-Mask) oder gar nicht -
 * ein explizites Null-Array gibt es nur, wenn der Server das Licht dort neu berechnet hat (gegraben, gebaut).
 */
public final class SkyLightSections {
    private static final int NIBBLES_PER_SECTION = 2048;

    public enum Status {
        /** Echte, gemischte Lichtwerte. */
        NORMAL,
        /** Als "leer" markiert (Empty-Mask), kein Array. */
        EMPTY,
        /** Echtes Array, aber ueberall 0 - das Base-Signal. */
        ZEROED,
        /** Echtes Array, ueberall volles Licht. */
        FULL,
        /** Array mit falscher Laenge. */
        UNKNOWN
    }

    private SkyLightSections() {
    }

    /** Ein Eintrag pro Sektion (sectionCount + 2), null = in diesem Paket nicht enthalten. */
    public static Status[] classify(ClientboundLightUpdatePacketData data, int sectionCount) {
        BitSet present = data.skyYMask();
        BitSet empty = data.emptySkyYMask();
        List<byte[]> arrays = data.skyUpdates();
        Status[] result = new Status[sectionCount + 2];
        int next = 0;
        for (int i = 0; i < result.length; i++) {
            if (present.get(i)) {
                result[i] = classify(next < arrays.size() ? arrays.get(next) : null);
                next++;
            } else if (empty.get(i)) {
                result[i] = Status.EMPTY;
            }
        }
        return result;
    }

    static Status classify(byte[] nibbles) {
        if (nibbles == null || nibbles.length != NIBBLES_PER_SECTION) {
            return Status.UNKNOWN;
        }
        boolean allZero = true;
        boolean allFull = true;
        for (byte b : nibbles) {
            allZero &= b == 0;
            allFull &= b == (byte) 0xFF;
            if (!allZero && !allFull) {
                return Status.NORMAL;
            }
        }
        return allZero ? Status.ZEROED : Status.FULL;
    }
}
