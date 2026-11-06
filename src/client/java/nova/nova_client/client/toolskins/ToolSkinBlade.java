package nova.nova_client.client.toolskins;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Iterator;

/**
 * Hyper Tools: Geometrie der Laserschwert-Klinge fuer den Shader saber_blade. Die Klinge ist eine Strecke vom Emitter
 * zur Spitze mit einem Leuchtradius; gezeichnet wird ein Viereck in der Ebene durch die Klingenachse, das zur Kamera
 * schaut, plus je eine runde Kappe an den Enden. Der Shader rechnet aus den Koordinaten den Abstand zur Achse - der
 * Kern bleibt dadurch in jeder Groesse glatt. Zeigt die Klinge fast genau auf die Kamera, wird das Viereck duenn; dann
 * blendet ein rundes Leuchten am vorderen Ende ein.
 * <p>
 * Alle Punkte werden hier schon in den Pose-Raum gerechnet (Kamera im Ursprung, ausser im GUI: Blick entlang -Z).
 */
final class ToolSkinBlade {
    private ToolSkinBlade() {
    }

    /** Emitter und Spitze im Pose-Raum. */
    static void place(Matrix4f pose, float[] start, float[] end, float extend, Vector3f emitter, Vector3f tip) {
        emitter.set(start[0], start[1], start[2]);
        tip.set(Mth.lerp(extend, start[0], end[0]), Mth.lerp(extend, start[1], end[1]), Mth.lerp(extend, start[2], end[2]));
        pose.transformPosition(emitter);
        pose.transformPosition(tip);
    }

    /** Groesse eines Werkzeug-Pixels im Pose-Raum (gleichmaessige Skalierung angenommen). */
    static float scale(Matrix4f pose) {
        return pose.transformDirection(1.0F, 0.0F, 0.0F, new Vector3f()).length();
    }

    /**
     * Klinge einreichen.
     *
     * @param radius Leuchtradius im Pose-Raum
     * @param color  Klingenfarbe (RGB 0..1)
     * @param bright Helligkeit 0..1
     * @param gui    orthografisch (Blick entlang -Z)
     */
    static void emit(VertexConsumer buffer, Vector3f emitter, Vector3f tip, float radius, float[] color, float bright,
                     boolean gui) {
        if (bright <= 0.004F || radius <= 0.0F) {
            return;
        }
        int r = (int) (Mth.clamp(color[0], 0.0F, 1.0F) * 255.0F);
        int g = (int) (Mth.clamp(color[1], 0.0F, 1.0F) * 255.0F);
        int b = (int) (Mth.clamp(color[2], 0.0F, 1.0F) * 255.0F);

        Vector3f axis = new Vector3f(tip).sub(emitter);
        float length = axis.length();
        Vector3f view = gui ? new Vector3f(0.0F, 0.0F, 1.0F)
                : new Vector3f(emitter).add(tip).mul(-0.5F).normalize();
        if (!Float.isFinite(view.x)) {
            view.set(0.0F, 0.0F, 1.0F);
        }
        float endOn = 1.0F;
        if (length > 1.0E-5F) {
            axis.div(length);
            endOn = Math.abs(axis.dot(view));
        }
        // Seitenansicht: Viereck durch die Achse, zur Kamera gedreht, mit runden Kappen
        float side = bright * (1.0F - smooth(0.9F, 0.995F, endOn));
        if (length > 1.0E-5F && side > 0.004F) {
            Vector3f across = new Vector3f(axis).cross(view);
            if (across.lengthSquared() > 1.0E-10F) {
                across.normalize(radius);
                Vector3f along = new Vector3f(axis).mul(radius);
                int a = (int) (side * 255.0F);
                // Kappe am Emitter
                quad(buffer, sub(emitter, along, across, -1), sub(emitter, along, across, 1), add(emitter, across, 1),
                        add(emitter, across, -1), 1.0F, 0.0F, r, g, b, a);
                // Klinge
                quad(buffer, add(emitter, across, -1), add(emitter, across, 1), add(tip, across, 1), add(tip, across, -1),
                        0.0F, 0.0F, r, g, b, a);
                // Kappe an der Spitze
                quad(buffer, add(tip, across, -1), add(tip, across, 1), sum(tip, along, across, 1),
                        sum(tip, along, across, -1), 0.0F, 1.0F, r, g, b, a);
            }
        }
        // Blick fast entlang der Klinge: rundes Leuchten am vorderen Ende
        float spot = bright * smooth(0.86F, 0.97F, endOn);
        if (spot > 0.004F || length <= 1.0E-5F) {
            Vector3f center = gui || emitter.lengthSquared() < tip.lengthSquared() ? emitter : tip;
            Vector3f right = new Vector3f(0.0F, 1.0F, 0.0F).cross(view);
            if (right.lengthSquared() < 1.0E-6F) {
                right.set(1.0F, 0.0F, 0.0F);
            }
            right.normalize(radius);
            Vector3f up = new Vector3f(view).cross(right).normalize(radius);
            int a = (int) (Math.max(spot, length <= 1.0E-5F ? bright : 0.0F) * 255.0F);
            disc(buffer, center, right, up, r, g, b, a);
        }
    }

    /** Schweif: Flaeche zwischen den gemerkten Lagen (aelteste zuerst). */
    static void emitTrail(VertexConsumer buffer, Iterator<float[]> samples, float[] color, float bright) {
        int r = (int) (Mth.clamp(color[0], 0.0F, 1.0F) * 255.0F);
        int g = (int) (Mth.clamp(color[1], 0.0F, 1.0F) * 255.0F);
        int b = (int) (Mth.clamp(color[2], 0.0F, 1.0F) * 255.0F);
        int a = (int) (Mth.clamp(bright, 0.0F, 1.0F) * 255.0F);
        float[] prev = null;
        while (samples.hasNext()) {
            float[] s = samples.next();
            if (prev != null) {
                vertex(buffer, prev[0], prev[1], prev[2], 0.0F, prev[6], r, g, b, a);
                vertex(buffer, prev[3], prev[4], prev[5], 1.0F, prev[6], r, g, b, a);
                vertex(buffer, s[3], s[4], s[5], 1.0F, s[6], r, g, b, a);
                vertex(buffer, s[0], s[1], s[2], 0.0F, s[6], r, g, b, a);
            }
            prev = s;
        }
    }

    // ---------------------------------------------------------------- Hilfen

    private static void quad(VertexConsumer buffer, Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3, float v01,
                             float v23, int r, int g, int b, int a) {
        vertex(buffer, p0.x, p0.y, p0.z, -1.0F, v01, r, g, b, a);
        vertex(buffer, p1.x, p1.y, p1.z, 1.0F, v01, r, g, b, a);
        vertex(buffer, p2.x, p2.y, p2.z, 1.0F, v23, r, g, b, a);
        vertex(buffer, p3.x, p3.y, p3.z, -1.0F, v23, r, g, b, a);
    }

    private static void disc(VertexConsumer buffer, Vector3f c, Vector3f right, Vector3f up, int r, int g, int b, int a) {
        vertex(buffer, c.x - right.x - up.x, c.y - right.y - up.y, c.z - right.z - up.z, -1.0F, -1.0F, r, g, b, a);
        vertex(buffer, c.x + right.x - up.x, c.y + right.y - up.y, c.z + right.z - up.z, 1.0F, -1.0F, r, g, b, a);
        vertex(buffer, c.x + right.x + up.x, c.y + right.y + up.y, c.z + right.z + up.z, 1.0F, 1.0F, r, g, b, a);
        vertex(buffer, c.x - right.x + up.x, c.y - right.y + up.y, c.z - right.z + up.z, -1.0F, 1.0F, r, g, b, a);
    }

    private static void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, int r, int g, int b,
                               int a) {
        buffer.addVertex(x, y, z).setUv(u, v).setColor(r, g, b, a);
    }

    /** p + k * across */
    private static Vector3f add(Vector3f p, Vector3f across, float k) {
        return new Vector3f(across).mul(k).add(p);
    }

    /** p - along + k * across */
    private static Vector3f sub(Vector3f p, Vector3f along, Vector3f across, float k) {
        return new Vector3f(across).mul(k).add(p).sub(along);
    }

    /** p + along + k * across */
    private static Vector3f sum(Vector3f p, Vector3f along, Vector3f across, float k) {
        return new Vector3f(across).mul(k).add(p).add(along);
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
