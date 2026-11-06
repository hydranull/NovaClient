package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * Sichtfeld dieses Frames fuer die Welt-Markierungen (ESP-Boxen, Chunk-Flaechen): was ausserhalb des Bildes liegt, wird
 * gar nicht erst als Gizmo erzeugt. Sieht genauso aus (es waere ohnehin nicht zu sehen), spart aber bei Tausenden
 * Kisten pro Frame zehntausende Linien, Flaechen und Objekte. Tracer bleiben ungefiltert - die laufen ja zum Bildrand.
 */
public final class ViewCull {
    /** Bloecke je Bildpunkt je Block Abstand, grosszuegig (1080p, bis ~110 Grad Sichtfeld). */
    private static final double BLOCKS_PER_PIXEL = 0.003D;

    //? if <26.1 {
    /*private static Frustum captured;
    *///?}

    private ViewCull() {
    }

    /** 1.21.11: das Sichtfeld kommt aus LevelRendererMixin (prepareCullFrustum). */
    public static void capture(Frustum frustum) {
        //? if <26.1 {
        /*captured = frustum;
        *///?}
    }

    /** Sichtfeld fuer diesen Frame, oder null (dann wird alles gezeichnet). */
    public static @Nullable Frustum frustum() {
        //? if >=26.1 {
        return Minecraft.getInstance().gameRenderer.mainCamera().getCullFrustum();
        //?} else {
        /*return captured;
        *///?}
    }

    /** Block-Box mit Umriss (Linien bis ~2.5 Pixel breit). */
    public static boolean visible(@Nullable Frustum frustum, BlockPos pos) {
        return frustum == null || visible(frustum, pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0D,
                pos.getY() + 1.0D, pos.getZ() + 1.0D, 3.0F);
    }

    /**
     * Box im Bild? linePixels: breiteste Linie am Rand (in Bildpunkten) - so weit darf die Box ausserhalb liegen und
     * trotzdem noch ein Stueck Linie zu sehen sein.
     */
    public static boolean visible(@Nullable Frustum frustum, double minX, double minY, double minZ, double maxX,
                                  double maxY, double maxZ, float linePixels) {
        if (frustum == null) {
            return true;
        }
        // weitester Punkt der Box: dort ist eine Linie (in Bloecken) am breitesten
        double dx = Math.max(Math.abs(minX - frustum.getCamX()), Math.abs(maxX - frustum.getCamX()));
        double dy = Math.max(Math.abs(minY - frustum.getCamY()), Math.abs(maxY - frustum.getCamY()));
        double dz = Math.max(Math.abs(minZ - frustum.getCamZ()), Math.abs(maxZ - frustum.getCamZ()));
        double pad = 0.05D + Math.sqrt(dx * dx + dy * dy + dz * dz) * BLOCKS_PER_PIXEL * linePixels;
        return frustum.isVisible(new AABB(minX - pad, minY - pad, minZ - pad, maxX + pad, maxY + pad, maxZ + pad));
    }
}
