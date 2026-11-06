package nova.nova_client.client.farmbuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.Random;

/**
 * Dreht die Kamera wie eine Hand an der Maus: geschwungene Bewegung mit Beschleunigen/Abbremsen (Minimum-Jerk),
 * bei grossen Wegen leicht ueber das Ziel hinaus und zurueck, kleines Zittern beim Halten. Jede Bewegung wird
 * auf das Pixel-Raster der eingestellten Mausempfindlichkeit gerundet - genau wie echte Mausbewegungen.
 * Wird pro Frame (HUD) und pro Tick aufgerufen; der Verlauf haengt nur von der Zeit ab.
 */
public final class HumanAim {
    private final Random random = new Random();

    // Aktuelle Bewegung
    private float fromYaw, fromPitch;
    private float toYaw, toPitch;
    private float bendYaw, bendPitch;
    private long startMs;
    private long durationMs;
    /** Nach einem Ueberschiessen: das eigentliche Ziel fuer die Korrektur. */
    private boolean correcting;
    private float finalYaw, finalPitch;
    private boolean hasTarget;

    // Folgen (beim Laufen)
    private boolean following;
    private float followYaw, followPitch;
    private long lastUpdateMs;

    // Zittern
    private final float[] phase = new float[4];
    private float tremor = 0.08F;

    // Pixel-Rest (damit Rundung nicht driftet) und Erkennung fremder Mausbewegung
    private double restYaw, restPitch;
    private float expectYaw = Float.NaN, expectPitch = Float.NaN;
    private boolean userMoved;
    private long lastScreenMs;
    /** Geschwindigkeitsfaktor (Einstellung Langsam/Normal/Schnell). */
    private float speed = 1.0F;

    public HumanAim() {
        for (int i = 0; i < phase.length; i++) {
            phase[i] = random.nextFloat() * 100.0F;
        }
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public void reset() {
        hasTarget = false;
        following = false;
        correcting = false;
        expectYaw = Float.NaN;
        userMoved = false;
        restYaw = 0.0D;
        restPitch = 0.0D;
    }

    /** Hat der Spieler seit dem letzten Aufruf selbst die Maus bewegt? (wird dabei zurueckgesetzt) */
    public boolean consumeUserMoved() {
        boolean moved = userMoved;
        userMoved = false;
        return moved;
    }

    // ---------------------------------------------------------------- Ziele

    /** Gezielter Blick auf einen Punkt (Platzieren, Kiste oeffnen ...). */
    public void lookAt(LocalPlayer player, float yaw, float pitch) {
        following = false;
        float curYaw = player.getYRot();
        float curPitch = player.getXRot();
        float targetYaw = curYaw + Mth.wrapDegrees(yaw - curYaw);
        pitch = Mth.clamp(pitch, -89.5F, 89.5F);
        if (hasTarget && !correcting && Math.abs(Mth.wrapDegrees(targetYaw - finalYaw)) < 0.4F
                && Math.abs(pitch - finalPitch) < 0.4F) {
            return; // gleiches Ziel wie schon unterwegs
        }
        float dYaw = targetYaw - curYaw;
        float dPitch = pitch - curPitch;
        float distance = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        finalYaw = targetYaw;
        finalPitch = pitch;
        // Grosse Wege: oft etwas ueber das Ziel hinaus, dann kurze Korrektur
        if (distance > 22.0F && random.nextFloat() < 0.6F) {
            float over = 0.035F + random.nextFloat() * 0.06F;
            begin(curYaw, curPitch, targetYaw + dYaw * over, Mth.clamp(pitch + dPitch * over * 0.6F, -89.5F, 89.5F), distance);
            correcting = true;
        } else {
            begin(curYaw, curPitch, targetYaw, pitch, distance);
            correcting = false;
        }
        hasTarget = true;
    }

    /** Weiches Nachfuehren (Laufen): Kamera folgt dem Ziel mit etwas Verzoegerung. */
    public void follow(float yaw, float pitch) {
        following = true;
        hasTarget = false;
        correcting = false;
        followYaw = yaw;
        followPitch = Mth.clamp(pitch, -89.5F, 89.5F);
    }

    private void begin(float yaw0, float pitch0, float yaw1, float pitch1, float distance) {
        fromYaw = yaw0;
        fromPitch = pitch0;
        toYaw = yaw1;
        toPitch = pitch1;
        // Fitts: kurze Wege schnell, lange laenger; plus Zufall
        double ms = (95.0D + 85.0D * (Math.log(1.0D + distance / 5.0D) / Math.log(2.0D)))
                * (0.8D + random.nextDouble() * 0.45D) / speed;
        durationMs = Math.max(45L, Math.round(ms));
        startMs = Util.getMillis();
        // Leichte Kurve quer zur Bewegungsrichtung (Hand bewegt sich im Bogen)
        float bend = (random.nextFloat() - 0.5F) * 0.22F;
        bendYaw = -(pitch1 - pitch0) * bend;
        bendPitch = (yaw1 - yaw0) * bend * 0.5F;
        tremor = 0.04F + random.nextFloat() * 0.08F;
    }

    /** Ist die Kamera am Ziel angekommen (und die Korrektur fertig)? */
    public boolean settled(LocalPlayer player) {
        if (following || !hasTarget) {
            return !following;
        }
        if (correcting || Util.getMillis() < startMs + durationMs) {
            return false;
        }
        return Math.abs(Mth.wrapDegrees(player.getYRot() - finalYaw)) < 0.6F && Math.abs(player.getXRot() - finalPitch) < 0.6F;
    }

    /** Wie weit (Grad) ist die Kamera vom Blickziel entfernt? */
    public float error(LocalPlayer player) {
        float ty = following ? followYaw : finalYaw;
        float tp = following ? followPitch : finalPitch;
        return Math.abs(Mth.wrapDegrees(player.getYRot() - ty)) + Math.abs(player.getXRot() - tp);
    }

    // ---------------------------------------------------------------- Anwenden

    /** Kamera fuer den aktuellen Zeitpunkt setzen (jeden Frame und jeden Tick aufrufen). */
    public void update(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        long now = Util.getMillis();
        float dt = Math.min(0.1F, Math.max(0.0F, (now - lastUpdateMs) / 1000.0F));
        lastUpdateMs = now;

        // Hat jemand anderes (der Spieler) die Kamera bewegt? (Kurz nach einem Menue nicht pruefen)
        if (client.gui.screen() != null) {
            lastScreenMs = now;
        }
        if (!Float.isNaN(expectYaw) && client.gui.screen() == null && now - lastScreenMs > 350L
                && (Math.abs(player.getYRot() - expectYaw) > 0.05F || Math.abs(player.getXRot() - expectPitch) > 0.05F)) {
            userMoved = true;
        }

        float wantYaw;
        float wantPitch;
        if (following) {
            // Kritisch gedaempftes Nachziehen (~140 ms) mit Geschwindigkeitsgrenze
            float k = 1.0F - (float) Math.exp(-dt * 7.5F * speed);
            float dy = Mth.wrapDegrees(followYaw - player.getYRot());
            float maxStep = 260.0F * speed * dt;
            wantYaw = player.getYRot() + Mth.clamp(dy * k, -maxStep, maxStep);
            wantPitch = player.getXRot() + (followPitch - player.getXRot()) * k;
        } else if (hasTarget) {
            float t = durationMs <= 0 ? 1.0F : Mth.clamp((now - startMs) / (float) durationMs, 0.0F, 1.0F);
            float s = minimumJerk(t);
            float arc = (float) Math.sin(Math.PI * s);
            wantYaw = Mth.lerp(s, fromYaw, toYaw) + bendYaw * arc;
            wantPitch = Mth.lerp(s, fromPitch, toPitch) + bendPitch * arc;
            if (t >= 1.0F && correcting) {
                // Kurze Korrektur zum echten Ziel, nach einer winzigen Reaktionspause
                correcting = false;
                float d = (float) Math.hypot(finalYaw - toYaw, finalPitch - toPitch);
                begin(toYaw, toPitch, finalYaw, finalPitch, d);
                startMs = now + 30 + random.nextInt(60);
                durationMs = Math.max(durationMs, 80L);
            }
            if (t >= 1.0F && !correcting) {
                // Halten mit leichtem Zittern
                float time = now / 1000.0F;
                wantYaw += tremor * (float) (Math.sin(time * 1.7F + phase[0]) + 0.5F * Math.sin(time * 4.3F + phase[1]));
                wantPitch += tremor * 0.7F * (float) (Math.sin(time * 1.3F + phase[2]) + 0.5F * Math.sin(time * 3.7F + phase[3]));
            }
        } else {
            expectYaw = player.getYRot();
            expectPitch = player.getXRot();
            return;
        }
        applyTurn(client, player, Mth.wrapDegrees(wantYaw - player.getYRot()), wantPitch - player.getXRot());
        expectYaw = player.getYRot();
        expectPitch = player.getXRot();
    }

    /** Bewegung in ganzen "Maus-Pixeln" wie bei echter Eingabe (Entity.turn rechnet x0.15). */
    private void applyTurn(Minecraft client, LocalPlayer player, float dYaw, float dPitch) {
        double sensitivity = client.options.sensitivity().get() * 0.6D + 0.2D;
        double perPixel = sensitivity * sensitivity * sensitivity * 8.0D;
        double degreesPerPixel = perPixel * 0.15D;
        restYaw += dYaw;
        restPitch += dPitch;
        long px = Math.round(restYaw / degreesPerPixel);
        long py = Math.round(restPitch / degreesPerPixel);
        if (px == 0 && py == 0) {
            return;
        }
        restYaw -= px * degreesPerPixel;
        restPitch -= py * degreesPerPixel;
        player.turn(px * perPixel, py * perPixel);
    }

    private static float minimumJerk(float t) {
        return t * t * t * (10.0F + t * (-15.0F + t * 6.0F));
    }
}
