package nova.nova_client.client.toolskins;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import nova.nova_client.client.Compat;
import nova.nova_client.client.mods.ToolSkinsMod;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hyper Tools: Bewegungen des Laserschwerts in der Ego-Sicht.
 * <ul>
 *     <li><b>Ruhe</b>: hoeher und steiler als ein Vanilla-Schwert - der Griff ist im Bild, die Klinge zeigt nach oben.</li>
 *     <li><b>Schlaege</b>: statt des einen Vanilla-Schwungs bei jedem Schlag ein anderer Move (schraeg von oben, von
 *     unten, waagrecht hin und zurueck, von oben herab, Stich, Drehung) - nie zweimal derselbe hintereinander. Schnelle
 *     Klicks gehen fliessend ineinander ueber.</li>
 *     <li><b>Inspect</b> (eigene Taste): Wirbeln, Hochwerfen und Fangen, Anschauen mit Aus- und wieder Einschalten der
 *     Klinge - der Reihe nach.</li>
 * </ul>
 * Jedes Schluesselbild beschreibt, wo die Hand ist und wohin die Klinge zeigt - so, wie man es auf dem Bildschirm
 * sieht: Verschiebung der Hand (Bloecke; x rechts, y oben, z zur Kamera), Richtung der Klinge als Winkel auf dem
 * Bildschirm (0 = oben, 90 = rechts, -90 = links, 180 = unten) und Neigung in den Bildschirm (0 = flach im Bild,
 * 90 = genau nach vorne), dazu die Drehung um die eigene Achse und die Klingenlaenge. Dazwischen wird weich
 * interpoliert (kubisch, Catmull-Rom). Waehrend sich die Klinge bewegt, merkt sich {@link #recordTrail} ihre letzten
 * Lagen fuer den Schweif.
 */
public final class SaberMoves {
    /** Kanaele eines Schluesselbilds. */
    private static final int T = 0;
    private static final int TX = 1;
    private static final int TY = 2;
    private static final int TZ = 3;
    /** Richtung auf dem Bildschirm (Grad, 0 = oben, 90 = rechts). */
    private static final int SA = 4;
    /** Neigung in den Bildschirm (Grad, 90 = nach vorne). */
    private static final int DP = 5;
    /** Drehung um die Klingenachse (Grad). */
    private static final int ROLL = 6;
    private static final int EXT = 7;
    private static final int CHANNELS = 8;

    /**
     * Vanilla haelt Schwerter in der Ego-Sicht 20 Grad zur Kamera geneigt (handheld: firstperson_righthand) - diese
     * Drehung stellt die Klinge erst senkrecht, bevor die Richtung der Pose dazukommt.
     */
    private static final float DISPLAY_TILT = -20.0F;

    /** Ein Move: Dauer in Sekunden (bei Tempo 1) und Schluesselbilder {t, tx, ty, tz, sa, dp, roll, ext}. */
    private record Move(String name, float seconds, float[][] keys) {
    }

    /** Ruhe-Pose: Klinge leicht nach links oben und vom Spieler weg geneigt. */
    private static final float[] IDLE = {0.0F, -0.06F, 0.13F, 0.0F, -12.0F, -8.0F, 0.0F, 1.0F};

    /** Schluesselbild relativ zur Ruhe-Hand (Verschiebung), Richtung absolut. */
    private static float[] k(float t, float tx, float ty, float tz, float sa, float dp) {
        return k(t, tx, ty, tz, sa, dp, 0.0F, 1.0F);
    }

    private static float[] k(float t, float tx, float ty, float tz, float sa, float dp, float roll, float ext) {
        return new float[]{t, IDLE[TX] + tx, IDLE[TY] + ty, IDLE[TZ] + tz, sa, dp, roll, ext};
    }

    /** Ruhe-Pose zur Zeit t, Richtung auf dem Bildschirm um turns volle Umdrehungen weiter (fuer Wirbel). */
    private static float[] rest(float t, int turns) {
        float[] r = IDLE.clone();
        r[T] = t;
        r[SA] += 360.0F * turns;
        return r;
    }

    // ---------------------------------------------------------------- Schlaege (rechte Hand; links gespiegelt)

    private static final Move[] SWINGS = {
            // schraeg von rechts oben nach links unten
            new Move("Diagonal", 0.46F, new float[][]{rest(0, 0),
                    k(0.22F, 0.08F, 0.22F, 0.04F, 38, -26),
                    k(0.40F, -0.16F, 0.06F, -0.06F, -62, 40),
                    k(0.53F, -0.34F, -0.04F, -0.04F, -122, 30),
                    k(0.74F, -0.26F, -0.02F, -0.02F, -112, 22),
                    rest(1, 0)}),
            // von links unten nach rechts oben
            new Move("Rising", 0.46F, new float[][]{rest(0, 0),
                    k(0.22F, -0.30F, -0.02F, 0.0F, -126, 24),
                    k(0.42F, -0.10F, 0.08F, -0.05F, -40, 45),
                    k(0.56F, 0.08F, 0.18F, -0.02F, 40, 18),
                    k(0.76F, 0.06F, 0.12F, 0.0F, 28, 8),
                    rest(1, 0)}),
            // waagrecht von rechts nach links, auf Augenhoehe quer durchs Bild
            new Move("Sweep", 0.48F, new float[][]{rest(0, 0),
                    k(0.22F, 0.12F, 0.20F, 0.02F, 98, -14),
                    k(0.42F, -0.16F, 0.20F, -0.06F, 14, 78),
                    k(0.58F, -0.44F, 0.14F, -0.02F, -98, 10),
                    k(0.78F, -0.34F, 0.10F, 0.0F, -86, 6),
                    rest(1, 0)}),
            // von oben herab
            new Move("Overhead", 0.5F, new float[][]{rest(0, 0),
                    k(0.26F, -0.08F, 0.22F, 0.08F, -4, -42),
                    k(0.46F, -0.10F, 0.10F, -0.08F, -4, 40),
                    k(0.60F, -0.10F, -0.10F, -0.10F, -4, 100),
                    k(0.80F, -0.08F, -0.06F, -0.06F, -4, 78),
                    rest(1, 0)}),
            // Stich nach vorne, Richtung Bildmitte
            new Move("Thrust", 0.46F, new float[][]{rest(0, 0),
                    k(0.26F, -0.14F, 0.02F, 0.16F, -12, 66),
                    k(0.45F, -0.20F, 0.06F, -0.20F, -14, 72),
                    k(0.60F, -0.22F, 0.07F, -0.32F, -14, 72),
                    k(0.80F, -0.12F, 0.04F, -0.10F, -12, 48),
                    rest(1, 0)}),
            // waagrecht von links nach rechts (Rueckhand)
            new Move("Backhand", 0.48F, new float[][]{rest(0, 0),
                    k(0.22F, -0.42F, 0.16F, 0.02F, -98, -10),
                    k(0.42F, -0.16F, 0.20F, -0.06F, -14, 78),
                    k(0.58F, 0.10F, 0.18F, -0.02F, 98, 12),
                    k(0.78F, 0.08F, 0.12F, 0.0F, 86, 6),
                    rest(1, 0)}),
            // einmal in der Bildebene herumwirbeln, dann schraeg nach unten
            new Move("Spin", 0.62F, new float[][]{rest(0, 0),
                    k(0.14F, -0.12F, 0.10F, 0.0F, -70, 0),
                    k(0.42F, -0.16F, 0.12F, 0.0F, -300, 4),
                    k(0.58F, -0.30F, 0.02F, -0.04F, -420, 34),
                    k(0.78F, -0.20F, 0.02F, -0.02F, -396, 16),
                    rest(1, -1)}),
    };

    // ---------------------------------------------------------------- Inspect

    private static final Move[] INSPECTS = {
            // Wirbeln: in die Bildmitte holen, zweimal herumdrehen, zurueck
            new Move("Twirl", 2.0F, new float[][]{rest(0, 0),
                    k(0.14F, -0.26F, 0.22F, 0.04F, 0, 4),
                    k(0.24F, -0.27F, 0.24F, 0.04F, -40, 4),
                    k(0.62F, -0.27F, 0.24F, 0.04F, -650, 4),
                    k(0.74F, -0.26F, 0.22F, 0.04F, -718, 2),
                    k(0.86F, -0.14F, 0.12F, 0.03F, -728, 0),
                    rest(1, -2)}),
            // Hochwerfen, zweimal ueberschlagen, fangen
            new Move("Toss", 2.2F, new float[][]{rest(0, 0),
                    k(0.10F, -0.04F, -0.06F, 0.0F, -10, -8),
                    k(0.20F, -0.08F, 0.20F, 0.0F, -70, -6),
                    k(0.44F, -0.14F, 0.50F, -0.04F, -390, -4),
                    k(0.66F, -0.08F, 0.12F, -0.02F, -700, -6),
                    k(0.72F, -0.02F, -0.06F, 0.0F, -732, -8),
                    k(0.82F, 0.0F, 0.02F, 0.0F, -730, -8),
                    rest(1, -2)}),
            // Anschauen: senkrecht vor das Gesicht, um die eigene Achse drehen, Klinge aus und wieder an
            new Move("Showcase", 2.6F, new float[][]{rest(0, 0),
                    k(0.15F, -0.30F, 0.26F, 0.10F, -6, -16, 0, 1),
                    k(0.34F, -0.30F, 0.26F, 0.10F, -6, -16, 160, 1),
                    k(0.42F, -0.30F, 0.25F, 0.10F, -6, -16, 180, 0),
                    k(0.58F, -0.30F, 0.25F, 0.10F, -6, -16, 340, 0),
                    k(0.66F, -0.30F, 0.26F, 0.10F, -6, -16, 360, 1),
                    k(0.84F, -0.22F, 0.18F, 0.08F, -8, -12, 360, 1),
                    withRoll(rest(1, 0), 360)}),
    };

    private static float[] withRoll(float[] key, float roll) {
        key[ROLL] = roll;
        return key;
    }

    /** Wie lange sich der Schweif an eine Lage erinnert (Sekunden). */
    static final float TRAIL_SECONDS = 0.11F;
    /** Nach dem Zuenden blitzt die Klinge kurz auf (Sekunden). */
    private static final float FLASH_SECONDS = 0.18F;

    private static Move current;
    private static double startTime = -1.0D;
    private static boolean inspecting;
    /** Pose beim Start des aktuellen Moves (fuer weichen Uebergang, wenn ein Move einen anderen unterbricht). */
    private static final float[] from = IDLE.clone();
    private static float blendSeconds;
    private static int lastSwing = -1;
    private static int nextInspect;
    private static double ignitedAt = -10.0D;
    private static float lastExt = 1.0F;

    /** Letzte Lagen der Klinge (Pose-Raum der Ego-Sicht): Emitter, Spitze, Zeit. */
    private static final ArrayDeque<float[]> TRAIL = new ArrayDeque<>();

    private SaberMoves() {
    }

    private static double now() {
        return System.nanoTime() / 1.0E9D;
    }

    // ---------------------------------------------------------------- Starten

    /** Ein Vanilla-Schwung hat begonnen (LivingEntityMixin, nur eigener Spieler, Haupthand). */
    public static void onSwing() {
        if (!ToolSkinsMod.isEnabled() || !ToolSkinsMod.swingMoves || !holdingSaber()) {
            return;
        }
        // Inspect wird von einem Schlag abgebrochen; ein laufender Schlag erst ab der zweiten Haelfte
        if (current != null && !inspecting && progress() < 0.5F) {
            return;
        }
        int pick;
        do {
            pick = ThreadLocalRandom.current().nextInt(SWINGS.length);
        } while (pick == lastSwing && SWINGS.length > 1);
        lastSwing = pick;
        start(SWINGS[pick], false);
    }

    /** Inspect-Taste: die drei Animationen der Reihe nach. */
    public static void startInspect() {
        if (!holdingSaber()) {
            return;
        }
        start(INSPECTS[nextInspect], true);
        nextInspect = (nextInspect + 1) % INSPECTS.length;
    }

    private static void start(Move move, boolean inspect) {
        // aktuelle Pose als Ausgangspunkt (Winkel auf -180..180, sonst dreht sich der Uebergang einmal herum)
        float[] pose = current != null ? sample(current, progress()) : IDLE.clone();
        pose[SA] = Mth.wrapDegrees(pose[SA]);
        pose[ROLL] = Mth.wrapDegrees(pose[ROLL]);
        System.arraycopy(pose, 0, from, 0, CHANNELS);
        blendSeconds = current != null ? 0.09F : 0.0F;
        current = move;
        inspecting = inspect;
        startTime = now();
    }

    public static void reset() {
        current = null;
        startTime = -1.0D;
        TRAIL.clear();
    }

    private static boolean holdingSaber() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && ToolSkins.isSaber(client.player.getMainHandItem());
    }

    // ---------------------------------------------------------------- Abfragen

    private static float speed() {
        return inspecting ? ToolSkinsMod.inspectSpeed : 1.0F;
    }

    /** Fortschritt 0..1 des laufenden Moves (beendet ihn, wenn er vorbei ist). */
    private static float progress() {
        if (current == null) {
            return 1.0F;
        }
        float t = (float) ((now() - startTime) * speed() / current.seconds());
        if (t >= 1.0F) {
            current = null;
            return 1.0F;
        }
        return Math.max(0.0F, t);
    }

    public static boolean active() {
        return current != null && progress() < 1.0F;
    }

    /** Aktuelle Pose (mit Uebergang vom vorherigen Move); in Ruhe die Ruhe-Pose. */
    private static float[] pose() {
        if (current == null) {
            return IDLE;
        }
        float t = progress();
        if (current == null) {
            return IDLE;
        }
        float[] p = sample(current, t);
        float elapsed = (float) (now() - startTime);
        if (blendSeconds > 0.0F && elapsed < blendSeconds) {
            float w = smooth(elapsed / blendSeconds);
            for (int c = TX; c < CHANNELS; c++) {
                p[c] = Mth.lerp(w, from[c], p[c]);
            }
        }
        return p;
    }

    /** Pose eines Moves zur Zeit t (kubisch durch die Schluesselbilder). */
    private static float[] sample(Move move, float t) {
        float[][] keys = move.keys();
        int i = 0;
        while (i < keys.length - 2 && t > keys[i + 1][T]) {
            i++;
        }
        float[] a = keys[i];
        float[] b = keys[i + 1];
        float span = Math.max(1.0E-4F, b[T] - a[T]);
        float u = Mth.clamp((t - a[T]) / span, 0.0F, 1.0F);
        float[] out = new float[CHANNELS];
        out[T] = t;
        for (int c = TX; c < CHANNELS; c++) {
            float ma = tangent(keys, i, c);
            float mb = tangent(keys, i + 1, c);
            out[c] = hermite(a[c], b[c], ma * span, mb * span, u);
        }
        return out;
    }

    /** Catmull-Rom-Steigung (ungleichmaessige Zeiten); an Anfang und Ende 0 (weich an- und auslaufen). */
    private static float tangent(float[][] keys, int i, int c) {
        if (i <= 0 || i >= keys.length - 1) {
            return 0.0F;
        }
        float dt = keys[i + 1][T] - keys[i - 1][T];
        return dt > 1.0E-5F ? (keys[i + 1][c] - keys[i - 1][c]) / dt : 0.0F;
    }

    private static float hermite(float p0, float p1, float m0, float m1, float u) {
        float u2 = u * u;
        float u3 = u2 * u;
        return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1;
    }

    private static float smooth(float x) {
        float t = Mth.clamp(x, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    // ---------------------------------------------------------------- Anwenden

    /**
     * Hand-Raum (statt des Vanilla-Schwungs, jedes Bild): Hand verschieben, dann die Klinge in die Richtung der Pose
     * drehen (um die Hand). side = 1 rechts, -1 links (gespiegelt).
     */
    public static void applyHand(PoseStack pose, int side) {
        float[] p = pose();
        pose.translate(p[TX] * side, p[TY], p[TZ]);
        Compat.rotate(pose, new Quaternionf().rotationZ(-p[SA] * side * Mth.DEG_TO_RAD));
        Compat.rotate(pose, new Quaternionf().rotationX(-p[DP] * Mth.DEG_TO_RAD));
        Compat.rotate(pose, new Quaternionf().rotationY(p[ROLL] * side * Mth.DEG_TO_RAD));
        Compat.rotate(pose, new Quaternionf().rotationX(DISPLAY_TILT * Mth.DEG_TO_RAD));
    }

    /** Klingenlaenge 0..1 (Anschauen: aus- und wieder einschalten). */
    static float extension() {
        float ext = Mth.clamp(pose()[EXT], 0.0F, 1.0F);
        if (ext > 0.5F && lastExt <= 0.5F) {
            ignitedAt = now();
        }
        lastExt = ext;
        return ext;
    }

    /** Aufblitzen direkt nach dem Wieder-Einschalten (0..1). */
    static float flash() {
        float t = (float) (now() - ignitedAt) / FLASH_SECONDS;
        return t >= 0.0F && t < 1.0F ? (1.0F - t) * (1.0F - t) : 0.0F;
    }

    // ---------------------------------------------------------------- Schweif

    /** Lage der Klinge in diesem Bild merken (Ego-Sicht, Pose-Raum). Nur solange sich etwas bewegt. */
    static void recordTrail(Vector3f emitter, Vector3f tip) {
        double time = now();
        while (!TRAIL.isEmpty() && time - TRAIL.peekFirst()[6] > TRAIL_SECONDS) {
            TRAIL.pollFirst();
        }
        if (!active() || !ToolSkinsMod.swingTrail) {
            return;
        }
        float[] last = TRAIL.peekLast();
        if (last != null && time - last[6] < 0.004D) {
            return; // derselbe Frame (zweite Hand o.ae.)
        }
        TRAIL.addLast(new float[]{emitter.x, emitter.y, emitter.z, tip.x, tip.y, tip.z, (float) time});
    }

    /** Gemerkte Lagen, aelteste zuerst: {ex, ey, ez, tx, ty, tz, alter 0..1}. */
    static Iterator<float[]> trail() {
        double time = now();
        ArrayDeque<float[]> out = new ArrayDeque<>(TRAIL.size());
        for (float[] s : TRAIL) {
            float age = (float) ((time - s[6]) / TRAIL_SECONDS);
            if (age < 1.0F) {
                out.add(new float[]{s[0], s[1], s[2], s[3], s[4], s[5], Math.max(0.0F, age)});
            }
        }
        return out.iterator();
    }

    static int trailSize() {
        return TRAIL.size();
    }
}
