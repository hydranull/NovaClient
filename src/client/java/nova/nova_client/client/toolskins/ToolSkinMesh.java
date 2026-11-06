package nova.nova_client.client.toolskins;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Griff eines Laserschwerts im Werkzeug-Rahmen (Sprite-Pixel: Griffende bei y=0, Klinge Richtung +Y) und die Lage
 * seiner Klinge (Emitter, Spitze, Leuchtradius, Farbe) - die Klinge selbst zeichnet ToolSkinBlade.
 *
 * <p>Licht rechnet der Shader hyper_tool pro Pixel. Je Ecke gehen nur mit: Farbe R = eingebackene Verschattung,
 * G = Leuchtstaerke (Glut-Welle, Funkeln, sanftes Pulsieren je Material), B = Glanzstreifen beim Ziehen.
 */
public final class ToolSkinMesh {
    private static final int PULSE_WAVE = 1;
    private static final int PULSE_TWINKLE = 2;
    private static final int PULSE_SOFT = 3;

    final String id;
    final int type;
    final float[] bounds;
    private final float[] pos;
    private final float[] normal;
    private final float[] uv;
    private final byte[] mat;
    private final int[] ao;
    private final int[] quads;
    private final float[] materialGlow;
    private final byte[] materialPulse;
    private final float[][] morphPos;
    private final float[][] morphNormal;
    private final boolean glows;
    private final int[] color;
    private final float[] blendPos;
    private final float[] blendNormal;
    private Vector3fc[] extents;
    private final float[] energy;
    private final Path[] paths;
    private final Vector3f a = new Vector3f();
    private final Vector3f b = new Vector3f();
    private final Vector3f c = new Vector3f();

    /**
     * Leuchtband: Punkte (Werkzeug-Rahmen) mit Verschiebung fuer Morph 0, halber Breite und Position t (0 = am Emitter,
     * 1 = Spitze; beim Zuenden waechst das Band bis t). show: 0 immer, 1 nur gespannt, 2 nur mit Pfeil, 3 nur mit Rakete.
     */
    record Path(float[] points, float[] delta, float[] width, float[] t, float intensity, int show) {
    }

    ToolSkinMesh(String id, int type, float[] bounds, float[] pos, float[] normal, float[] uv, byte[] mat, byte[] ao,
                 int[] quads, float[] materialGlow, byte[] materialPulse, float[][] morphPos, float[][] morphNormal,
                 float[] energy, Path[] paths) {
        this.id = id;
        this.type = type;
        this.bounds = bounds;
        this.pos = pos;
        this.normal = normal;
        this.uv = uv;
        this.mat = mat;
        this.quads = quads;
        this.materialGlow = materialGlow;
        this.materialPulse = materialPulse;
        this.morphPos = morphPos;
        this.morphNormal = morphNormal;
        this.ao = new int[ao.length];
        boolean anyGlow = false;
        for (int v = 0; v < ao.length; v++) {
            this.ao[v] = ao[v] & 0xFF;
            anyGlow |= materialGlow[mat[v]] > 0.0F;
        }
        this.glows = anyGlow;
        this.color = new int[ao.length];
        this.blendPos = morphPos.length > 0 ? new float[pos.length] : pos;
        this.blendNormal = morphPos.length > 0 ? new float[normal.length] : normal;
        this.energy = energy;
        this.paths = paths;
        // Huelle: Griff plus Klinge samt Leuchten (fuer Boden, Rahmen und GUI-Groesse)
        if (paths.length > 0) {
            float r = paths[0].width()[0];
            float[] s = bladeStart();
            float[] e = bladeEnd();
            for (int j = 0; j < 3; j++) {
                bounds[j] = Math.min(bounds[j], Math.min(s[j], e[j]) - r);
                bounds[j + 3] = Math.max(bounds[j + 3], Math.max(s[j], e[j]) + r);
            }
        }
    }

    boolean hasBlade() {
        return paths.length > 0 && paths[0].t().length >= 2;
    }

    /** Emitter der Klinge (Werkzeug-Rahmen). */
    float[] bladeStart() {
        float[] p = paths[0].points();
        return new float[]{p[0], p[1], p[2]};
    }

    /** Spitze der ganz ausgefahrenen Klinge (Werkzeug-Rahmen). */
    float[] bladeEnd() {
        float[] p = paths[0].points();
        int n = p.length / 3 - 1;
        return new float[]{p[n * 3], p[n * 3 + 1], p[n * 3 + 2]};
    }

    /** Leuchtradius der Klinge (Werkzeug-Rahmen). */
    float bladeRadius() {
        return paths[0].width()[0];
    }

    /** Klingenfarbe (Seltenheit), RGB 0..1. */
    float[] bladeColor() {
        return energy;
    }

    float height() {
        return bounds[4];
    }

    int morphs() {
        return morphPos.length;
    }

    /** Huelle im Modellraum (einmal gerechnet). */
    Vector3fc[] extents(Matrix4f matrix) {
        if (extents == null) {
            extents = ToolSkinRenderer.corners(bounds, matrix);
        }
        return extents;
    }

    // ---------------------------------------------------------------- Zeichnen

    /**
     * Reicht alle Vierecke ein.
     *
     * @param shineAt Hoehe (Werkzeug-Rahmen) des Glanzstreifens beim Ziehen, NaN = keiner
     * @param weights Staerke je Morph-Ziel (0 = Grundform), darf null sein
     */
    void emit(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, float shineAt, float time,
              boolean glow, float[] weights) {
        float[] p = shape(weights);
        float[] n = blendNormal;
        boolean shine = !Float.isNaN(shineAt);
        for (int v = 0; v < color.length; v++) {
            int g = glow && glows ? glowAt(v, p[v * 3 + 1], time) : 0;
            int b = 0;
            if (shine) {
                float k = (p[v * 3 + 1] - shineAt) / 1.4F;
                b = (int) (255.0F * 0.85F * (float) Math.exp(-k * k));
            }
            color[v] = 0xFF000000 | ao[v] << 16 | g << 8 | b;
        }
        for (int q = 0; q < quads.length; q++) {
            int v = quads[q];
            buffer.addVertex(pose, p[v * 3], p[v * 3 + 1], p[v * 3 + 2])
                    .setColor(color[v])
                    .setUv(uv[v * 2], uv[v * 2 + 1])
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, n[v * 3], n[v * 3 + 1], n[v * 3 + 2]);
        }
    }

    /** Verzauberungs-Schimmer: gleiche Geometrie, Farbe/Licht macht der Render-Typ. */
    void emitPlain(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay, float[] weights) {
        float[] p = shape(weights);
        for (int q = 0; q < quads.length; q++) {
            int v = quads[q];
            buffer.addVertex(pose, p[v * 3], p[v * 3 + 1], p[v * 3 + 2])
                    .setColor(0xFFFFFFFF)
                    .setUv(uv[v * 2] * 8.0F, uv[v * 2 + 1] * 8.0F)
                    .setOverlay(overlay)
                    .setLight(light)
                    .setNormal(pose, normal[v * 3], normal[v * 3 + 1], normal[v * 3 + 2]);
        }
    }

    /** Ecken (und Normalen) mit eingeblendeten Morph-Zielen. */
    private float[] shape(float[] weights) {
        if (morphPos.length == 0) {
            return pos;
        }
        System.arraycopy(pos, 0, blendPos, 0, pos.length);
        System.arraycopy(normal, 0, blendNormal, 0, normal.length);
        if (weights != null) {
            for (int k = 0; k < morphPos.length && k < weights.length; k++) {
                float w = weights[k];
                if (w <= 0.0F) {
                    continue;
                }
                float[] tp = morphPos[k];
                float[] tn = morphNormal[k];
                for (int i = 0; i < pos.length; i++) {
                    blendPos[i] += w * (tp[i] - pos[i]);
                    blendNormal[i] += w * (tn[i] - normal[i]);
                }
            }
        }
        return blendPos;
    }

    private int glowAt(int v, float y, float time) {
        float strength = materialGlow[mat[v]];
        if (strength <= 0.0F) {
            return 0;
        }
        float pulse = switch (materialPulse[mat[v]]) {
            case PULSE_WAVE -> 0.70F + 0.30F * Mth.sin(time * 2.2F - y * 0.45F);
            case PULSE_TWINKLE -> {
                float s = 0.5F + 0.5F * Mth.sin(time * 5.0F + v * 1.7F);
                yield s * s * s;
            }
            case PULSE_SOFT -> 0.80F + 0.20F * Mth.sin(time * 1.6F + y * 0.3F);
            default -> 1.0F;
        };
        return (int) (Mth.clamp(strength * pulse, 0.0F, 1.0F) * 255.0F);
    }

}
