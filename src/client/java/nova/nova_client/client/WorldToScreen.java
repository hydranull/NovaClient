package nova.nova_client.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Rechnet Welt-Positionen auf den Bildschirm (GUI-Pixel) um, fuer HUD-Elemente, die an Dingen in der Welt haengen
 * (Damage Numbers, Pop Counter). Einmal pro Frame mit {@link #begin()} vorbereiten.
 */
public final class WorldToScreen {
    private static final Matrix4f MATRIX = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static int screenW;
    private static int screenH;

    private WorldToScreen() {
    }

    public static void begin() {
        Minecraft client = Minecraft.getInstance();
        Camera cam = client.gameRenderer.mainCamera();
        camera = cam.position();
        Compat.viewProjection(MATRIX);
        screenW = client.getWindow().getGuiScaledWidth();
        screenH = client.getWindow().getGuiScaledHeight();
    }

    public static Vec3 camera() {
        return camera;
    }

    /** Bildschirm-Position {x, y}, oder null wenn hinter der Kamera oder weit ausserhalb. */
    public static float[] project(Vec3 world) {
        Vec3 relative = world.subtract(camera);
        Vector4f v = new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0F);
        MATRIX.transform(v);
        if (v.w <= 0.05F) {
            return null;
        }
        float nx = v.x / v.w;
        float ny = v.y / v.w;
        if (Math.abs(nx) > 1.2F || Math.abs(ny) > 1.2F) {
            return null;
        }
        return new float[]{(nx + 1.0F) * 0.5F * screenW, (1.0F - ny) * 0.5F * screenH};
    }
}
