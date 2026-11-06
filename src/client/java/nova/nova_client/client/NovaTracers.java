package nova.nova_client.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Tracer-Linien wie bei Advanced Stash Finder: vom Bildmittelpunkt (knapp vor der Kamera) zum Ziel, je weiter weg desto
 * blasser. Aufruf nur waehrend eines Gizmo-Collectors.
 */
public final class NovaTracers {
    private NovaTracers() {
    }

    /** Startpunkt der Linien fuer dieses Bild. */
    public static Vec3 start() {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        Vector3fc forward = camera.forwardVector();
        return camera.position().add(forward.x() * 0.6D, forward.y() * 0.6D, forward.z() * 0.6D);
    }

    /** Linie von start nach target in rgb; fadeDistance = ab hier am blassesten. */
    public static void line(Vec3 start, Vec3 target, int rgb, double fadeDistance) {
        double distance = Math.sqrt(start.distanceToSqr(target));
        int alpha = (int) Math.round(230 - Math.min(1.0D, distance / fadeDistance) * 170);
        Gizmos.line(start, target, (alpha << 24) | (rgb & 0xFFFFFF), 1.5F).setAlwaysOnTop();
    }
}
