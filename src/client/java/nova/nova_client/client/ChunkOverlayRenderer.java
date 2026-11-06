package nova.nova_client.client;

import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Set;

/**
 * Chunk-Markierung von Sus Chunk Finder und ChunkFinder (gleiches Aussehen): halbtransparente Flaeche exakt auf den
 * 16x16-Chunk-Grenzen auf einer frei waehlbaren Hoehe, mit leuchtendem Rand. Direkt aneinander grenzende markierte
 * Chunks verschmelzen: der Rand wird nur an den Aussenkanten gezeichnet. Immer im Vordergrund, damit die Markierung
 * auch aus grosser Entfernung sichtbar ist. Jedes Modul hat eine eigene Instanz (eigener Stil-Cache).
 */
public final class ChunkOverlayRenderer {
    /** Leuchtrand von aussen (breit, schwach) nach innen (schmal, hell). */
    private static final int[] GLOW_ALPHAS = {0x14, 0x2C, 0x5C};
    private static final float[] GLOW_WIDTHS = {14.0F, 9.0F, 5.0F};
    private static final float CORE_WIDTH = 2.5F;

    // Farben/Breiten nur neu berechnen, wenn sich Farbe, Deckkraft oder Glow aendert
    private int styleColor = -1;
    private float styleOpacity = -1.0F;
    private float styleGlow = -1.0F;
    private GizmoStyle fill;
    private int[] lineColors = new int[0];
    private float[] lineWidths = new float[0];

    private void updateStyles(int color, float opacity, float glow) {
        if (color == styleColor && opacity == styleOpacity && glow == styleGlow) {
            return;
        }
        styleColor = color;
        styleOpacity = opacity;
        styleGlow = glow;
        int fillAlpha = Math.max(1, Math.min(255, Math.round(opacity * 255.0F)));
        fill = GizmoStyle.fill((fillAlpha << 24) | color);
        int layers = 0;
        int[] colors = new int[GLOW_ALPHAS.length + 1];
        float[] widths = new float[GLOW_ALPHAS.length + 1];
        for (int i = 0; i < GLOW_ALPHAS.length; i++) {
            int alpha = Math.min(255, Math.round(GLOW_ALPHAS[i] * glow));
            if (alpha > 0) {
                // staerkerer Glow = heller und etwas breiter
                colors[layers] = (alpha << 24) | color;
                widths[layers] = GLOW_WIDTHS[i] * (0.6F + 0.4F * Math.min(2.0F, glow));
                layers++;
            }
        }
        colors[layers] = 0xFF000000 | color;
        widths[layers] = CORE_WIDTH;
        layers++;
        lineColors = Arrays.copyOf(colors, layers);
        lineWidths = Arrays.copyOf(widths, layers);
    }

    /**
     * keys: markierte Chunks, marked: dieselben als Menge (fuer die Nachbar-Pruefung). color: RGB, opacity: Deckkraft
     * der Flaeche (0..1), glow: Staerke des Leuchtrands (1 = 100 %). Aufruf waehrend eines Gizmo-Collectors.
     */
    public void emit(long[] keys, Set<Long> marked, double y, int color, float opacity, float glow) {
        updateStyles(color & 0xFFFFFF, opacity, glow);
        net.minecraft.client.renderer.culling.Frustum view = ViewCull.frustum();
        float widest = lineWidths.length > 0 ? lineWidths[0] : CORE_WIDTH;
        for (long key : keys) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            double x0 = cx << 4;
            double z0 = cz << 4;
            double x1 = x0 + 16;
            double z1 = z0 + 16;
            // ausserhalb des Bildes (auch mit dem breitesten Leuchtrand): nichts zu sehen
            if (!ViewCull.visible(view, x0, y, z0, x1, y, z1, widest)) {
                continue;
            }
            Gizmos.rect(new Vec3(x0, y, z0), new Vec3(x1, y, z1), Direction.UP, fill).setAlwaysOnTop();
            // Leuchtrand nur an Kanten ohne markierten Nachbarn
            if (!marked.contains(ChunkPos.pack(cx, cz - 1))) {
                edge(x0, z0, x1, z0, y);
            }
            if (!marked.contains(ChunkPos.pack(cx, cz + 1))) {
                edge(x0, z1, x1, z1, y);
            }
            if (!marked.contains(ChunkPos.pack(cx - 1, cz))) {
                edge(x0, z0, x0, z1, y);
            }
            if (!marked.contains(ChunkPos.pack(cx + 1, cz))) {
                edge(x1, z0, x1, z1, y);
            }
        }
    }

    private void edge(double ax, double az, double bx, double bz, double y) {
        Vec3 a = new Vec3(ax, y, az);
        Vec3 b = new Vec3(bx, y, bz);
        for (int i = 0; i < lineColors.length; i++) {
            Gizmos.line(a, b, lineColors[i], lineWidths[i]).setAlwaysOnTop();
        }
    }
}
