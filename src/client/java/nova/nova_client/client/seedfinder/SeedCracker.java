package nova.nova_client.client.seedfinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * Rechnet den Welt-Seed aus dem zurueck, was der Client sehen kann - funktioniert also auch auf Servern.
 * <p>
 * Minecraft verteilt Strukturen (Tempel, Hexenhuetten, ...) mit einem 48-Bit-Zufallsgenerator (java.util.Random-LCG):
 * pro Region (32x32 Chunks) wird er mit {@code regionX * 341873128712 + regionZ * 132897987541 + seed + salt} gestartet
 * und zieht zweimal nextInt(24) fuer die Chunk-Position in der Region. Jede gefundene Struktur verraet so ~9 Bit
 * des Seeds. Ablauf:
 * <ol>
 *     <li>Untere 18-20 Bit durchprobieren: nextInt(24) = bits % 24, und weil 24 = 8 * 3 haengen die unteren 3 Bit
 *     des Ergebnisses nur an den unteren 20 Bit des Seeds - 2^20 Versuche statt 2^48.</li>
 *     <li>Optional End-Saeulen: ihre Hoehen ergeben {@code new Random(seed).nextLong() & 0xFFFF}, das haengt nur an
 *     den unteren 32 Bit.</li>
 *     <li>Restliche Bit bis 48 durchprobieren und jede Struktur (und vergrabene Schaetze) exakt pruefen.</li>
 *     <li>Die oberen 16 Bit (65536 Moeglichkeiten) mit dem SHA-256-Hash des Seeds pruefen, den jeder Server beim
 *     Betreten mitschickt (fuer die Biom-Uebergaenge).</li>
 * </ol>
 * Reines Java, laeuft in einem Hintergrund-Thread auf allen Kernen.
 */
public final class SeedCracker {
    static final long MULT = 0x5DEECE66DL;
    static final long ADD = 0xBL;
    static final long MASK48 = (1L << 48) - 1;
    static final long REGION_X = 341873128712L;
    static final long REGION_Z = 132897987541L;
    /** Salz der Wahrscheinlichkeits-Pruefung vergrabener Schaetze (legacy_type_2). */
    static final int TREASURE_SALT = 10387320;
    static final float TREASURE_CHANCE = 0.01F;

    /** Hoechstens so viele Pruefungen (sonst dauert es zu lange - erst mehr Strukturen sammeln). */
    public static final double MAX_WORK = Math.pow(2, 38);
    /** Erwartete Kandidaten fuer einen Versuch (je 65536 SHA-256, ~500 pro Sekunde); bis zum Vierfachen wird geprueft. */
    public static final int MAX_CANDIDATES = 8192;

    private SeedCracker() {
    }

    /** Eine Struktur mit zufaelliger Verteilung: Startchunk und die Verteilungs-Parameter ihres Typs. */
    public record Structure(int chunkX, int chunkZ, int salt, int spacing, int separation) {
        int regionX() {
            return Math.floorDiv(chunkX, spacing);
        }

        int regionZ() {
            return Math.floorDiv(chunkZ, spacing);
        }

        int offsetX() {
            return chunkX - regionX() * spacing;
        }

        int offsetZ() {
            return chunkZ - regionZ() * spacing;
        }

        int limit() {
            return spacing - separation;
        }

        /** Liegt der Chunk ueberhaupt im erlaubten Bereich seiner Region? (sonst ist die Angabe falsch) */
        public boolean plausible() {
            return offsetX() < limit() && offsetZ() < limit();
        }

        /** Informationsgehalt in Bit. */
        public double bits() {
            return 2.0 * Math.log(limit()) / Math.log(2.0);
        }
    }

    /** Alles, was ueber eine Welt bekannt ist. */
    public record Input(List<Structure> structures, List<long[]> treasures, int[] pillarSizes, Long hashedSeed) {
    }

    public interface Listener {
        void progress(double fraction, String stage);

        boolean cancelled();
    }

    public record Result(List<Long> worldSeeds, List<Long> structureSeeds, boolean truncated, String message) {
    }

    // ---------------------------------------------------------------- Abschaetzung (fuer das Menue)

    public static double treasureBits() {
        return -Math.log(TREASURE_CHANCE) / Math.log(2.0);
    }

    /**
     * Wie viele Bit des 48-Bit-Struktur-Seeds die Daten festlegen (48 = eindeutig). Die Zweierpotenz-Anteile
     * (z.B. die 8 in 24 = 8 * 3) sagen nur etwas ueber die unteren 20 Bit - mehr als 20 Bit kommen von dort nie,
     * egal wie viele Strukturen. Ebenso die End-Saeulen fuer die unteren 32 Bit.
     */
    public static double informationBits(Input input) {
        double lift = 0.0;
        double rest = 0.0;
        for (Structure s : input.structures()) {
            int k = liftable(s.limit());
            lift += 2 * k;
            rest += 2.0 * log2(s.limit() / (double) (1 << k));
        }
        double low = Math.min(lowBits(input.structures()), lift);
        int[] keys = pillarKeys(input.pillarSizes());
        if (keys != null && keys.length > 0) {
            low = Math.min(32.0, low + 16.0 - log2(keys.length));
        }
        rest += input.treasures().size() * treasureBits();
        return Math.min(48.0, low + rest);
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    /** Geschaetzte Anzahl Pruefungen fuer Schritt 1-3. */
    public static double estimateWork(Input input) {
        int lowBits = lowBits(input.structures());
        double liftBits = 0.0;
        for (Structure s : input.structures()) {
            liftBits += 2 * liftable(s.limit());
        }
        double work = lowBits > 0 ? Math.pow(2, lowBits) : 0.0;
        double survivors = Math.max(1.0, Math.pow(2, lowBits - liftBits));
        int known = lowBits;
        int[] keys = pillarKeys(input.pillarSizes());
        if (keys != null && keys.length > 0) {
            work += survivors * Math.pow(2, 32 - known);
            survivors = Math.max(1.0, survivors * Math.pow(2, 32 - known) * keys.length / 65536.0);
            known = 32;
        }
        return work + survivors * Math.pow(2, 48 - known);
    }

    /** Erwartete Anzahl 48-Bit-Kandidaten am Ende. */
    public static double expectedCandidates(Input input) {
        return Math.max(1.0, Math.pow(2, 48 - informationBits(input)));
    }

    // ---------------------------------------------------------------- Hauptablauf

    public static Result crack(Input input, Listener listener) {
        List<Structure> structures = input.structures();
        for (Structure s : structures) {
            if (!s.plausible()) {
                return new Result(List.of(), List.of(), false, "Structure at chunk " + s.chunkX() + ", " + s.chunkZ()
                        + " cannot be a real structure start - remove it");
            }
        }
        if (estimateWork(input) > MAX_WORK || expectedCandidates(input) > MAX_CANDIDATES) {
            return new Result(List.of(), List.of(), false, "Not enough data yet - find more structures");
        }
        int[] keys = pillarKeys(input.pillarSizes());
        if (keys != null && keys.length == 0) {
            return new Result(List.of(), List.of(), false, "End pillar heights match no seed - re-scan the End");
        }

        // Pruefungen vorbereiten (die mit den meisten Bit zuerst, dann fliegt Falsches frueh raus)
        List<Structure> sorted = new ArrayList<>(structures);
        sorted.sort((a, b) -> Integer.compare(b.limit(), a.limit()));
        int n = sorted.size();
        long[] c = new long[n];
        int[] limit = new int[n];
        int[] offX = new int[n];
        int[] offZ = new int[n];
        int[] lift = new int[n];
        for (int i = 0; i < n; i++) {
            Structure s = sorted.get(i);
            c[i] = (long) s.regionX() * REGION_X + (long) s.regionZ() * REGION_Z + s.salt();
            limit[i] = s.limit();
            offX[i] = s.offsetX();
            offZ[i] = s.offsetZ();
            lift[i] = liftable(s.limit());
        }
        long[] t = new long[input.treasures().size()];
        for (int i = 0; i < t.length; i++) {
            long[] chunk = input.treasures().get(i);
            t[i] = chunk[0] * REGION_X + chunk[1] * REGION_Z + TREASURE_SALT;
        }
        Checker checker = new Checker(c, limit, offX, offZ, t);

        // 1) Untere Bit
        int lowBits = lowBits(structures);
        long[] low;
        if (lowBits == 0) {
            low = new long[]{0L};
        } else {
            listener.progress(0.0, "Lower bits");
            final int bitsFinal = lowBits;
            low = LongStream.range(0, 1L << lowBits).parallel()
                    .filter(l -> liftOk(l, bitsFinal, c, limit, offX, offZ, lift))
                    .toArray();
        }
        if (listener.cancelled()) {
            return cancelled();
        }
        int known = lowBits;

        // 2) End-Saeulen: untere 32 Bit
        if (keys != null) {
            listener.progress(0.05, "End pillars");
            boolean[] keySet = new boolean[65536];
            for (int key : keys) {
                keySet[key] = true;
            }
            final int from = known;
            final long[] base = low;
            low = LongStream.range(0, (long) base.length << (32 - from)).parallel()
                    .map(i -> base[(int) (i >>> (32 - from))] | (i & ((1L << (32 - from)) - 1)) << from)
                    .filter(l -> keySet[pillarKey(l)])
                    .toArray();
            known = 32;
            if (listener.cancelled()) {
                return cancelled();
            }
        }

        // 3) Restliche Bit bis 48 komplett pruefen
        ConcurrentLinkedQueue<Long> found = new ConcurrentLinkedQueue<>();
        AtomicLong done = new AtomicLong();
        int high = 48 - known;
        long perLow = 1L << high;
        long blocks = Math.max(1L, perLow >>> 16);
        long total = (long) low.length * blocks;
        final int shift = known;
        final long[] lowFinal = low;
        LongStream.range(0, total).parallel().forEach(job -> {
            if (listener.cancelled() || found.size() > MAX_CANDIDATES * 4) {
                return;
            }
            long base = lowFinal[(int) (job / blocks)];
            long start = (job % blocks) << 16;
            long end = Math.min(perLow, start + 65536L);
            for (long h = start; h < end; h++) {
                long seed = base | h << shift;
                if (checker.test(seed)) {
                    found.add(seed);
                }
            }
            long d = done.incrementAndGet();
            if ((d & 63) == 0) {
                listener.progress(0.1 + 0.6 * d / (double) total, "Structure seed");
            }
        });
        if (listener.cancelled()) {
            return cancelled();
        }
        List<Long> structureSeeds = new ArrayList<>(found);
        structureSeeds.sort(Long::compare);
        boolean truncated = structureSeeds.size() > MAX_CANDIDATES * 4;
        if (structureSeeds.isEmpty()) {
            return new Result(List.of(), List.of(), false,
                    "No seed matches - a structure may be wrong, or the server changed its structure seeds");
        }

        // 4) Obere 16 Bit ueber den Hash vom Server
        if (input.hashedSeed() == null || truncated) {
            return new Result(List.of(), structureSeeds, truncated, input.hashedSeed() == null
                    ? "Server sent no seed hash - only the lower 48 bits are known"
                    : "Too many candidates - find more structures");
        }
        long hashed = input.hashedSeed();
        ConcurrentLinkedQueue<Long> matches = new ConcurrentLinkedQueue<>();
        AtomicLong hashedDone = new AtomicLong();
        int candidates = structureSeeds.size();
        structureSeeds.parallelStream().forEach(structureSeed -> {
            if (listener.cancelled() || !matches.isEmpty()) {
                return;
            }
            int[] w = new int[64];
            for (long upper = 0; upper < 65536; upper++) {
                long seed = upper << 48 | structureSeed;
                if (obfuscate(seed, w) == hashed) {
                    matches.add(seed);
                }
            }
            listener.progress(0.7 + 0.3 * hashedDone.incrementAndGet() / candidates, "Upper 16 bits");
        });
        if (listener.cancelled()) {
            return cancelled();
        }
        List<Long> worldSeeds = new ArrayList<>(matches);
        if (worldSeeds.isEmpty()) {
            return new Result(List.of(), structureSeeds, false,
                    "Seed hash matches no candidate - the server probably fakes it");
        }
        return new Result(worldSeeds, structureSeeds, false, "Seed found");
    }

    private static Result cancelled() {
        return new Result(List.of(), List.of(), false, "Cancelled");
    }

    // ---------------------------------------------------------------- Bausteine

    /** Wie viele untere Ergebnis-Bit von nextInt(limit) nur an unteren Seed-Bit haengen (Zweierpotenz-Anteil). */
    static int liftable(int limit) {
        if (limit <= 1 || (limit & limit - 1) == 0) {
            return 0; // Zweierpotenz: Ergebnis kommt aus den oberen Bit
        }
        return Integer.numberOfTrailingZeros(limit);
    }

    /** Wie viele untere Bit Schritt 1 durchprobiert (17 + groesster Zweierpotenz-Anteil), 0 = nichts zu holen. */
    static int lowBits(List<Structure> structures) {
        int max = 0;
        for (Structure s : structures) {
            max = Math.max(max, liftable(s.limit()));
        }
        return max == 0 ? 0 : 17 + max;
    }

    /** Pruefung nur der unteren bits Bit: passen die unteren Ergebnis-Bit aller Strukturen? */
    static boolean liftOk(long low, int bits, long[] c, int[] limit, int[] offX, int[] offZ, int[] lift) {
        long mask = (1L << bits) - 1;
        for (int i = 0; i < c.length; i++) {
            int k = Math.min(lift[i], bits - 17);
            if (k <= 0) {
                continue;
            }
            long resultMask = (1L << k) - 1;
            long s = (low + c[i] ^ MULT) & mask;
            s = s * MULT + ADD & mask;
            if ((s >>> 17 & resultMask) != (offX[i] & resultMask)) {
                return false;
            }
            s = s * MULT + ADD & mask;
            if ((s >>> 17 & resultMask) != (offZ[i] & resultMask)) {
                return false;
            }
        }
        return true;
    }

    /** {@code new Random(seed).nextLong() & 0xFFFF} - braucht nur die unteren 32 Bit des Seeds. */
    static int pillarKey(long seed) {
        long s = (seed ^ MULT) & 0xFFFFFFFFL;
        s = s * MULT + ADD & 0xFFFFFFFFL;
        s = s * MULT + ADD & 0xFFFFFFFFL;
        return (int) (s >>> 16 & 0xFFFF);
    }

    /** Alle Schluessel (0..65535), deren Saeulen-Reihenfolge zu den gesehenen Hoehen passt; null = keine Daten. */
    public static int[] pillarKeys(int[] sizes) {
        if (sizes == null) {
            return null;
        }
        int known = 0;
        for (int size : sizes) {
            if (size >= 0) {
                known++;
            }
        }
        if (known < 3) {
            return null; // zu wenig, um etwas zu sagen
        }
        int[] matches = new int[65536];
        int count = 0;
        for (int key = 0; key < 65536; key++) {
            int[] order = pillarOrder(key);
            boolean ok = true;
            for (int i = 0; i < 10 && ok; i++) {
                ok = sizes[i] < 0 || sizes[i] == order[i];
            }
            if (ok) {
                matches[count++] = key;
            }
        }
        return Arrays.copyOf(matches, count);
    }

    /** Groessen-Index (0..9) jeder der zehn Saeulen fuer einen Schluessel (wie EndSpikeFeature). */
    public static int[] pillarOrder(long key) {
        int[] list = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Lcg random = new Lcg(key);
        for (int i = list.length; i > 1; i--) {
            int swapTo = random.nextInt(i);
            int tmp = list[i - 1];
            list[i - 1] = list[swapTo];
            list[swapTo] = tmp;
        }
        return list;
    }

    /** Mitte der Saeule i (Block-Koordinaten x, z) - wie EndSpikeFeature. */
    public static int[] pillarCenter(int i) {
        double angle = 2.0 * (-Math.PI + Math.PI / 10.0 * i);
        return new int[]{(int) Math.floor(42.0 * Math.cos(angle)), (int) Math.floor(42.0 * Math.sin(angle))};
    }

    /** Vollstaendige Pruefung eines 48-Bit-Seeds. */
    private record Checker(long[] c, int[] limit, int[] offX, int[] offZ, long[] treasure) {
        boolean test(long seed) {
            for (int i = 0; i < c.length; i++) {
                long s = (seed + c[i] ^ MULT) & MASK48;
                int n = limit[i];
                // nextInt(n) zweimal, exakt wie java.util.Random (inkl. seltener Wiederholung)
                s = s * MULT + ADD & MASK48;
                int value;
                if ((n & n - 1) == 0) {
                    value = (int) ((long) n * (s >>> 17) >> 31);
                } else {
                    int bits = (int) (s >>> 17);
                    value = bits % n;
                    while (bits - value + (n - 1) < 0) {
                        s = s * MULT + ADD & MASK48;
                        bits = (int) (s >>> 17);
                        value = bits % n;
                    }
                }
                if (value != offX[i]) {
                    return false;
                }
                s = s * MULT + ADD & MASK48;
                if ((n & n - 1) == 0) {
                    value = (int) ((long) n * (s >>> 17) >> 31);
                } else {
                    int bits = (int) (s >>> 17);
                    value = bits % n;
                    while (bits - value + (n - 1) < 0) {
                        s = s * MULT + ADD & MASK48;
                        bits = (int) (s >>> 17);
                        value = bits % n;
                    }
                }
                if (value != offZ[i]) {
                    return false;
                }
            }
            for (long t : treasure) {
                long s = (seed + t ^ MULT) & MASK48;
                s = s * MULT + ADD & MASK48;
                if ((float) (int) (s >>> 24) * 5.9604645E-8F >= TREASURE_CHANCE) {
                    return false;
                }
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- Seed-Hash

    private static final int[] K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

    /**
     * Was der Server statt des Seeds schickt: {@code BiomeManager.obfuscateSeed} = die ersten 8 Byte von SHA-256 der
     * 8 Seed-Bytes (little endian). Eigene SHA-256 nur fuer genau diesen einen 64-Byte-Block - mehrfach schneller als
     * MessageDigest, und hier laufen bis zu einer halben Milliarde Hashes.
     */
    public static long obfuscate(long seed) {
        return obfuscate(seed, new int[64]);
    }

    /** Wie {@link #obfuscate(long)}, mit wiederverwendetem Puffer (64 ints, wird ueberschrieben). */
    static long obfuscate(long seed, int[] w) {
        java.util.Arrays.fill(w, 3, 15, 0);
        w[0] = Integer.reverseBytes((int) seed);
        w[1] = Integer.reverseBytes((int) (seed >>> 32));
        w[2] = 0x80000000;
        w[15] = 64;
        for (int i = 16; i < 64; i++) {
            int a = w[i - 15];
            int b = w[i - 2];
            int s0 = Integer.rotateRight(a, 7) ^ Integer.rotateRight(a, 18) ^ a >>> 3;
            int s1 = Integer.rotateRight(b, 17) ^ Integer.rotateRight(b, 19) ^ b >>> 10;
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        int a = 0x6a09e667;
        int b = 0xbb67ae85;
        int c = 0x3c6ef372;
        int d = 0xa54ff53a;
        int e = 0x510e527f;
        int f = 0x9b05688c;
        int g = 0x1f83d9ab;
        int h = 0x5be0cd19;
        for (int i = 0; i < 64; i++) {
            int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
            int ch = e & f ^ ~e & g;
            int t1 = h + s1 + ch + K[i] + w[i];
            int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
            int maj = a & b ^ a & c ^ b & c;
            int t2 = s0 + maj;
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        int h0 = 0x6a09e667 + a;
        int h1 = 0xbb67ae85 + b;
        return Integer.reverseBytes(h0) & 0xFFFFFFFFL | (long) Integer.reverseBytes(h1) << 32;
    }

    /** java.util.Random-Generator (identisch zu Minecrafts LegacyRandomSource). */
    static final class Lcg {
        private long seed;

        Lcg(long seed) {
            this.seed = (seed ^ MULT) & MASK48;
        }

        int next(int bits) {
            seed = seed * MULT + ADD & MASK48;
            return (int) (seed >>> 48 - bits);
        }

        int nextInt(int bound) {
            if ((bound & bound - 1) == 0) {
                return (int) ((long) bound * next(31) >> 31);
            }
            int bits;
            int value;
            do {
                bits = next(31);
                value = bits % bound;
            } while (bits - value + (bound - 1) < 0);
            return value;
        }
    }
}
