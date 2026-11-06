package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pearl Trajectory: Mit einer Enderperle in der Hand zeigt eine Linie die Flugbahn, und am Aufschlagpunkt liegt ein
 * Kreis. Die Bahn wird mit derselben Physik wie in Minecraft vorausberechnet (Tempo 1.5, Schwerkraft 0.03,
 * Luftwiderstand 0.99); die kleine zufaellige Streuung beim Werfen kann man nicht vorhersagen.
 */
public class PearlTrajectoryMod extends GameTickMod {
    private static final int COLOR_START = 0xFF8A5CFF;
    private static final int COLOR_END = 0xFFFF4FD8;
    /** Die ersten Bloecke vor dem Gesicht nicht zeichnen, danach ueber diese Strecke einblenden. */
    private static final double FADE_SKIP = 1.2D;
    private static final double FADE_LENGTH = 3.0D;
    private static final long PULSE_MS = 1200L;
    private static final double SPEED = 1.5D;
    private static final double GRAVITY = 0.03D;
    private static final double DRAG = 0.99D;
    private static final int MAX_TICKS = 300;
    private static final float CIRCLE_RADIUS = 0.5F;
    private static final int CIRCLE_SEGMENTS = 32;

    private static volatile boolean enabled;

    public PearlTrajectoryMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        PearlTrajectoryMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Linie + Kreis; wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (!enabled || player == null || client.level == null
                || !player.getMainHandItem().is(Items.ENDER_PEARL) && !player.getOffhandItem().is(Items.ENDER_PEARL)) {
            return;
        }
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        // Startpunkt und Richtung wie beim echten Wurf (Projectile.shootFromRotation)
        Vec3 pos = player.getEyePosition(partialTick).subtract(0.0D, 0.1D, 0.0D);
        float pitch = player.getViewXRot(partialTick) * Mth.DEG_TO_RAD;
        float yaw = player.getViewYRot(partialTick) * Mth.DEG_TO_RAD;
        Vec3 velocity = new Vec3(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch))
                .scale(SPEED);
        Vec3 own = player.getDeltaMovement();
        velocity = velocity.add(own.x, player.onGround() ? 0.0D : own.y, own.z);

        List<Vec3> points = new ArrayList<>();
        points.add(pos);
        BlockHitResult landing = null;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            Vec3 next = pos.add(velocity);
            BlockHitResult hit = client.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                points.add(hit.getLocation());
                landing = hit;
                break;
            }
            points.add(next);
            pos = next;
            if (pos.y < client.level.getMinY() - 16) {
                break;
            }
            velocity = velocity.scale(DRAG).subtract(0.0D, GRAVITY, 0.0D);
        }

        drawPath(points);
        if (landing != null) {
            drawLanding(landing.getLocation(), landing.getDirection());
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Farbe entlang der Bahn: Violett am Anfang, Pink am Ende. */
    private static int gradient(float t, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (NovaGui.mix(COLOR_START, COLOR_END, t) & 0x00FFFFFF);
    }

    /** Dicke Linie mit weichem Leuchten, Farbverlauf und Einblenden vor der Kamera. */
    private static void drawPath(List<Vec3> points) {
        double total = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i).distanceTo(points.get(i - 1));
        }
        if (total <= 0.0D) {
            return;
        }
        double walked = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            Vec3 a = points.get(i - 1);
            Vec3 b = points.get(i);
            double length = b.distanceTo(a);
            walked += length;
            // Direkt vor dem Gesicht weglassen, danach sanft einblenden
            if (walked < FADE_SKIP) {
                continue;
            }
            float fade = (float) Mth.clamp((walked - FADE_SKIP) / FADE_LENGTH, 0.0D, 1.0D);
            float t = (float) (walked / total);
            Gizmos.line(a, b, gradient(t, Math.round(55 * fade)), 18.0F).setAlwaysOnTop();
            Gizmos.line(a, b, gradient(t, Math.round(255 * fade)), 9.0F).setAlwaysOnTop();
            Gizmos.line(a, b, (Math.round(150 * fade) << 24) | 0xFFFFFF, 2.5F).setAlwaysOnTop();
        }
    }

    /** Aufschlag: gefuellte Scheibe, leuchtender Ring und ein pulsierender Ring nach aussen. */
    private static void drawLanding(Vec3 center, Direction face) {
        Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
        Vec3 axisA = Math.abs(normal.y) > 0.5D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 axisB = normal.cross(axisA).normalize();
        axisA = axisB.cross(normal).normalize();
        // Minimal ueber der Oberflaeche, damit nichts im Block flackert
        Vec3 base = center.add(normal.scale(0.02D));

        Vec3[] ring = ring(base, axisA, axisB, CIRCLE_RADIUS);
        // Gefuellte Scheibe (beide Seiten, damit sie von oben und unten sichtbar ist)
        for (int i = 1; i < ring.length; i++) {
            Gizmos.rect(base, ring[i - 1], ring[i], base, GizmoStyle.fill(gradient(1.0F, 0x30))).setAlwaysOnTop();
            Gizmos.rect(base, ring[i], ring[i - 1], base, GizmoStyle.fill(gradient(1.0F, 0x30))).setAlwaysOnTop();
        }
        for (int i = 1; i < ring.length; i++) {
            float t = (float) i / (ring.length - 1);
            float along = 0.5F + 0.5F * Mth.sin(t * Mth.TWO_PI);
            Gizmos.line(ring[i - 1], ring[i], gradient(along, 0x50), 12.0F).setAlwaysOnTop();
            Gizmos.line(ring[i - 1], ring[i], gradient(along, 0xFF), 5.0F).setAlwaysOnTop();
        }

        // Pulsierender Ring, der nach aussen laeuft und verblasst
        float pulse = (Util.getMillis() % PULSE_MS) / (float) PULSE_MS;
        Vec3[] wave = ring(base, axisA, axisB, CIRCLE_RADIUS * (0.35F + 1.1F * pulse));
        int waveAlpha = Math.round(200 * (1.0F - pulse));
        for (int i = 1; i < wave.length; i++) {
            Gizmos.line(wave[i - 1], wave[i], gradient(1.0F, waveAlpha), 3.0F).setAlwaysOnTop();
        }
        Gizmos.point(base, 0xFFFFFFFF, 7.0F).setAlwaysOnTop();
    }

    private static Vec3[] ring(Vec3 base, Vec3 axisA, Vec3 axisB, double radius) {
        Vec3[] points = new Vec3[CIRCLE_SEGMENTS + 1];
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double angle = i * Math.PI * 2.0D / CIRCLE_SEGMENTS;
            points[i] = base.add(axisA.scale(Math.cos(angle) * radius)).add(axisB.scale(Math.sin(angle) * radius));
        }
        return points;
    }
}
