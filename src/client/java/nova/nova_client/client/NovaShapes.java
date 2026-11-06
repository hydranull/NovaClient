package nova.nova_client.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Schnelle, geglaettete Formen fuer die Nova-Menues. Runde Ecken, Kreise und Ringe kommen aus kleinen,
 * vorberechneten Texturen in echter Pixelgroesse (je Radius einmal erzeugt), die geraden Stuecke sind einfache
 * Rechtecke. Ein abgerundetes Rechteck kostet so immer 7 Zeichen-Aufrufe, ein Rahmen 8 - egal wie gross der Radius
 * ist - und die Kanten sind auf echten Pixeln glatt.
 *
 * Reihenfolge: Minecraft zeichnet innerhalb einer Ebene erst alle einfarbigen Flaechen, dann die Texturen (nach Textur
 * sortiert), dann den Text. Formen, die exakt uebereinander liegen sollen (z.B. Schalter-Knopf auf der Schalter-Bahn),
 * kommen deshalb in eine eigene Ebene (nextStratum).
 */
public final class NovaShapes {
    /** Verlauf links -> rechts (voll -> leer). */
    public static final int FADE_LEFT = 0;
    /** Verlauf zur Mitte hin hell, an beiden Enden leer. */
    public static final int FADE_CENTER = 1;
    /** Verlauf rechts -> links (voll -> leer). */
    public static final int FADE_RIGHT = 2;

    private static final int DISC = 0;
    private static final int RING = 1;
    private static final int FADE = 2;
    private static final int STREAK = 3;
    private static final int BEAM = 4;
    private static final int TRIANGLE = 7;
    private static final int TRIANGLE_LINE = 8;
    private static final int LENS = 9;
    private static final int LENS_LINE = 10;
    private static final int TRAPEZOID = 11;
    private static final int ATLAS = 12;
    private static final int HUE = 13;
    private static final int DOTS = 14;
    private static final int VIGNETTE = 15;
    private static final int VIGNETTE_SIZE = 128;

    private static final int FADE_W = 128;
    private static final int HUE_W = 128;
    private static final int STREAK_W = 8;
    private static final int STREAK_H = 48;
    private static final int BEAM_H = 32;

    private static final Map<Long, Identifier> TEXTURES = new HashMap<>();

    private interface AlphaFunction {
        float alpha(float x, float y);
    }

    private NovaShapes() {
    }

    /** Echte Pixel je GUI-Pixel. */
    public static int scale() {
        return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
    }

    // ---------------------------------------------------------------- Formen

    /** Gefuelltes Rechteck mit runden, geglaetteten Ecken. */
    public static void rect(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int color) {
        if (x1 <= x0 || y1 <= y0 || (color >>> 24) < 2) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
        if (r <= 0) {
            g.fill(x0, y0, x1, y1, color);
            return;
        }
        if (x1 - x0 > 2 * r) {
            g.fill(x0 + r, y0, x1 - r, y1, color);
        }
        if (y1 - y0 > 2 * r) {
            g.fill(x0, y0 + r, x0 + r, y1 - r, color);
            g.fill(x1 - r, y0 + r, x1, y1 - r, color);
        }
        int s = scale();
        corners(g, texture(DISC, r * s, 0), x0, y0, x1, y1, r * s, s, color);
    }

    /** Pille: Rechteck mit halbrunden Enden. */
    public static void pill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        rect(g, x0, y0, x1, y1, (y1 - y0) / 2, color);
    }

    /** 1px-Rahmen mit runden, geglaetteten Ecken. */
    public static void outline(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int color) {
        if (x1 <= x0 || y1 <= y0 || (color >>> 24) < 2) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min((x1 - x0) / 2, (y1 - y0) / 2)));
        if (r <= 0) {
            g.fill(x0, y0, x1, y0 + 1, color);
            g.fill(x0, y1 - 1, x1, y1, color);
            g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
            g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
            return;
        }
        if (x1 - x0 > 2 * r) {
            g.fill(x0 + r, y0, x1 - r, y0 + 1, color);
            g.fill(x0 + r, y1 - 1, x1 - r, y1, color);
        }
        if (y1 - y0 > 2 * r) {
            g.fill(x0, y0 + r, x0 + 1, y1 - r, color);
            g.fill(x1 - 1, y0 + r, x1, y1 - r, color);
        }
        int s = scale();
        corners(g, texture(RING, r * s, s), x0, y0, x1, y1, r * s, s, color);
    }

    /** Gefuellter, geglaetteter Kreis; Mitte und Radius in GUI-Pixeln, auf echte Pixel genau (weiche Bewegung). */
    public static void disc(GuiGraphicsExtractor g, float cx, float cy, float radius, int color) {
        round(g, DISC, cx, cy, radius, 0.0F, color);
    }

    /** Geglaetteter Ring; thickness in GUI-Pixeln. */
    public static void ring(GuiGraphicsExtractor g, float cx, float cy, float radius, float thickness, int color) {
        round(g, RING, cx, cy, radius, thickness, color);
    }

    private static void round(GuiGraphicsExtractor g, int kind, float cx, float cy, float radius, float thickness, int color) {
        if (radius <= 0.0F || (color >>> 24) < 2) {
            return;
        }
        int s = scale();
        int r = Math.max(1, Math.round(radius * s));
        int t = kind == RING ? Math.max(1, Math.min(r, Math.round(thickness * s))) : 0;
        Identifier tex = texture(kind, r, t);
        int d = r * 2;
        g.pose().pushMatrix();
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, Math.round(cx * s) - r, Math.round(cy * s) - r, 0, 0, d, d, d, d, d, d,
                color);
        g.pose().popMatrix();
    }

    /** Waagerechter Verlauf (siehe FADE_LEFT/CENTER/RIGHT), z.B. fuer Lichtkanten oder Leuchtbalken. */
    public static void fade(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int mode, int color) {
        if (x1 <= x0 || y1 <= y0 || (color >>> 24) < 2) {
            return;
        }
        Identifier tex = texture(FADE, mode, 0);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x0, y0, 0, 0, x1 - x0, y1 - y0, FADE_W, 1, FADE_W, 1, color);
    }

    /** Farbton-Spur (Regenbogen, Farbton 0..1 von links nach rechts) als eine Textur - fuer Farbregler. */
    public static void hue(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, texture(HUE, 0, 0), x0, y0, 0, 0, x1 - x0, y1 - y0, HUE_W, 1, HUE_W, 1,
                0xFFFFFFFF);
    }

    /**
     * Feines Punkt-Raster ueber die Flaeche: alle cell GUI-Pixel ein kleiner Punkt. Eine kachelnde Textur in echter
     * Pixelgroesse - ein einziger Zeichen-Aufruf, egal wie gross die Flaeche ist.
     */
    public static void dots(GuiGraphicsExtractor g, int width, int height, int cell, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) < 2) {
            return;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, texture(DOTS, scale(), cell), 0, 0, 0, 0, width, height, width, height, cell, cell,
                color);
    }

    /** Abdunklung zu den Raendern hin (Mitte frei), weich ueber den ganzen Bildschirm gezogen. */
    public static void vignette(GuiGraphicsExtractor g, int width, int height, int color) {
        if ((color >>> 24) < 2) {
            return;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, texture(VIGNETTE, 0, 0), 0, 0, 0, 0, width, height, VIGNETTE_SIZE,
                VIGNETTE_SIZE, VIGNETTE_SIZE, VIGNETTE_SIZE, color);
    }

    /** Regenstrich (unten hell, oben auslaufend, leicht schraeg), w x h in GUI-Pixeln ab x/y (oben links). */
    public static void streak(GuiGraphicsExtractor g, float x, float y, float w, float h, int color) {
        if ((color >>> 24) < 2) {
            return;
        }
        int s = scale();
        int px = Math.round(x * s);
        int py = Math.round(y * s);
        int pw = Math.max(1, Math.round(w * s));
        int ph = Math.max(1, Math.round(h * s));
        g.pose().pushMatrix();
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, texture(STREAK, 0, 0), px, py, 0, 0, pw, ph, STREAK_W, STREAK_H,
                STREAK_W, STREAK_H, color);
        g.pose().popMatrix();
    }

    /**
     * Leuchtender Strahl von (x0, y0) nach (x1, y1): weiche Kanten quer zur Richtung, width = Breite des Scheins in
     * GUI-Pixeln (fuer Blitze).
     */
    public static void beam(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float width, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.5F || (color >>> 24) < 2) {
            return;
        }
        int s = scale();
        int pl = Math.max(1, Math.round(length * s));
        int pw = Math.max(1, Math.round(width * s));
        g.pose().pushMatrix();
        g.pose().translate(x0, y0);
        g.pose().rotate((float) Math.atan2(dy, dx));
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, texture(BEAM, 0, 0), 0, -pw / 2, 0, 0, pl, pw, 1, BEAM_H, 1, BEAM_H, color);
        g.pose().popMatrix();
    }

    /** Dreieck mit Spitze oben (gefuellt, geglaettet) im Rechteck x0/y0 - x1/y1. */
    public static void triangle(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, int color) {
        polygon(g, TRIANGLE, x0, y0, x1, y1, 0.0F, color);
    }

    /** Umriss eines Dreiecks mit Spitze oben; thickness in GUI-Pixeln. */
    public static void triangleOutline(GuiGraphicsExtractor g, float x0, float y0, float x1, float y1, float thickness, int color) {
        polygon(g, TRIANGLE_LINE, x0, y0, x1, y1, thickness, color);
    }

    /** Augenform (zwei Kreisboegen, spitze Ecken links/rechts), gefuellt, um cx/cy. */
    public static void lens(GuiGraphicsExtractor g, float cx, float cy, float w, float h, int color) {
        polygon(g, LENS, cx - w / 2.0F, cy - h / 2.0F, cx + w / 2.0F, cy + h / 2.0F, 0.0F, color);
    }

    /** Umriss der Augenform; thickness in GUI-Pixeln. */
    public static void lensOutline(GuiGraphicsExtractor g, float cx, float cy, float w, float h, float thickness, int color) {
        polygon(g, LENS_LINE, cx - w / 2.0F, cy - h / 2.0F, cx + w / 2.0F, cy + h / 2.0F, thickness, color);
    }

    /**
     * Trapez (oben schmaler, symmetrisch um cx), gefuellt und geglaettet - z.B. eine Steinlage einer Pyramide. Breiten
     * und Hoehe in GUI-Pixeln.
     */
    public static void trapezoid(GuiGraphicsExtractor g, float cx, float y0, float topWidth, float bottomWidth, float height,
                                 int color) {
        if (bottomWidth <= 0.0F || height <= 0.0F || (color >>> 24) < 2) {
            return;
        }
        int s = scale();
        int pw = Math.max(2, Math.min(4095, Math.round(bottomWidth * s)));
        int pt = Math.max(0, Math.min(pw, Math.round(topWidth * s)));
        int ph = Math.max(2, Math.round(height * s));
        Identifier tex = texture(TRAPEZOID, pt | (pw << 12), ph);
        g.pose().pushMatrix();
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, Math.round(cx * s) - pw / 2, Math.round(y0 * s), 0, 0, pw, ph, pw, ph, pw, ph,
                color);
        g.pose().popMatrix();
    }

    private static void polygon(GuiGraphicsExtractor g, int kind, float x0, float y0, float x1, float y1, float thickness, int color) {
        if (x1 <= x0 || y1 <= y0 || (color >>> 24) < 2) {
            return;
        }
        int s = scale();
        int px = Math.round(x0 * s);
        int py = Math.round(y0 * s);
        int pw = Math.max(2, Math.round(x1 * s) - px);
        int ph = Math.max(2, Math.round(y1 * s) - py);
        int t = thickness > 0.0F ? Math.max(1, Math.round(thickness * s)) : 0;
        Identifier tex = texture(kind, pw | (t << 14), ph);
        g.pose().pushMatrix();
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, px, py, 0, 0, pw, ph, pw, ph, pw, ph, color);
        g.pose().popMatrix();
    }

    // ---------------------------------------------------------------- Schalter und Regler-Knopf

    /** Groesse eines Schalters in GUI-Pixeln. */
    public static final int SWITCH_W = 22;
    public static final int SWITCH_H = 12;
    private static final int KNOB = 8;
    private static final int SLIDER_KNOB = 10;

    /**
     * Bahn eines Schalters (22x12 GUI-Pixel) bei x/y. Bahn und Knoepfe liegen in EINER Textur: innerhalb einer Ebene
     * sortiert Minecraft nach Textur - mit derselben Textur bleibt die Reihenfolge, der Knopf liegt sicher oben.
     */
    public static void switchTrack(GuiGraphicsExtractor g, int x, int y, int color) {
        int s = scale();
        atlas(g, s, x * s, y * s, 0, SWITCH_W * s, SWITCH_H * s, color);
    }

    /** Knopf eines Schalters (8 GUI-Pixel) mittig bei cx/cy. */
    public static void switchKnob(GuiGraphicsExtractor g, float cx, float cy, int color) {
        int s = scale();
        int d = KNOB * s;
        atlas(g, s, Math.round(cx * s) - d / 2, Math.round(cy * s) - d / 2, SWITCH_W * s + 2, d, d, color);
    }

    /** Runder Regler-Knopf (10 GUI-Pixel) mittig bei cx/cy. */
    public static void sliderKnob(GuiGraphicsExtractor g, float cx, float cy, int color) {
        int s = scale();
        int d = SLIDER_KNOB * s;
        atlas(g, s, Math.round(cx * s) - d / 2, Math.round(cy * s) - d / 2, (SWITCH_W + KNOB) * s + 4, d, d, color);
    }

    private static void atlas(GuiGraphicsExtractor g, int s, int px, int py, int u, int w, int h, int color) {
        if ((color >>> 24) < 2) {
            return;
        }
        int atlasW = (SWITCH_W + KNOB + SLIDER_KNOB) * s + 4;
        int atlasH = SWITCH_H * s;
        g.pose().pushMatrix();
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, texture(ATLAS, s, 0), px, py, u, 0, w, h, w, h, atlasW, atlasH, color);
        g.pose().popMatrix();
    }

    /** Die vier Viertelkreise einer Rundung (Textur 2r x 2r, echte Pixel). */
    private static void corners(GuiGraphicsExtractor g, Identifier tex, int x0, int y0, int x1, int y1, int r, int s, int color) {
        int d = r * 2;
        int px0 = x0 * s;
        int py0 = y0 * s;
        int px1 = x1 * s;
        int py1 = y1 * s;
        g.pose().pushMatrix();
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, px0, py0, 0, 0, r, r, r, r, d, d, color);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, px1 - r, py0, r, 0, r, r, r, r, d, d, color);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, px0, py1 - r, 0, r, r, r, r, r, d, d, color);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, px1 - r, py1 - r, r, r, r, r, r, r, d, d, color);
        g.pose().popMatrix();
    }

    // ---------------------------------------------------------------- Texturen

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static float smooth(float t) {
        float x = clamp01(t);
        return x * x * (3.0F - 2.0F * x);
    }

    /** Textur je Art und Groesse, einmal berechnet. */
    private static Identifier texture(int kind, int a, int b) {
        long key = ((long) kind << 48) | ((long) a << 24) | b;
        Identifier id = TEXTURES.get(key);
        if (id != null) {
            return id;
        }
        if (TEXTURES.size() >= 192) {
            for (Identifier old : TEXTURES.values()) {
                Minecraft.getInstance().getTextureManager().release(old);
            }
            TEXTURES.clear();
        }
        Identifier created = Identifier.fromNamespaceAndPath("nova_client", "dynamic/shape_" + kind + "_" + a + "_" + b);
        switch (kind) {
            case DISC -> {
                float c = a;
                register(created, a * 2, a * 2, (x, y) -> {
                    float d = (float) Math.sqrt((x - c) * (x - c) + (y - c) * (y - c));
                    return clamp01(c - d + 0.5F);
                });
            }
            case RING -> {
                float c = a;
                float inner = a - b;
                register(created, a * 2, a * 2, (x, y) -> {
                    float d = (float) Math.sqrt((x - c) * (x - c) + (y - c) * (y - c));
                    return Math.min(clamp01(c - d + 0.5F), clamp01(d - inner + 0.5F));
                });
            }
            case HUE -> {
                DynamicTexture texture = new DynamicTexture(created::toString, HUE_W, 1, false) {
                    {
                        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                    }
                };
                for (int x = 0; x < HUE_W; x++) {
                    float hue = Math.min(0.999F, x / (float) (HUE_W - 1));
                    texture.getPixels().setPixel(x, 0, 0xFF000000 | (net.minecraft.util.Mth.hsvToRgb(hue, 1.0F, 1.0F) & 0xFFFFFF));
                }
                texture.upload();
                Minecraft.getInstance().getTextureManager().register(created, texture);
            }
            case DOTS -> {
                // a = GUI-Scale, b = Zellgroesse in GUI-Pixeln: ein runder Punkt mitten in der Zelle, kachelnd
                int size = a * b;
                float c = size / 2.0F;
                float r = Math.max(0.7F, 0.75F * a);
                DynamicTexture texture = new DynamicTexture(created::toString, size, size, false) {
                    {
                        sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST);
                    }
                };
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        float d = (float) Math.sqrt((x + 0.5F - c) * (x + 0.5F - c) + (y + 0.5F - c) * (y + 0.5F - c));
                        int alpha = Math.round(clamp01(r - d + 0.5F) * 255.0F);
                        texture.getPixels().setPixel(x, y, (alpha << 24) | 0xFFFFFF);
                    }
                }
                texture.upload();
                Minecraft.getInstance().getTextureManager().register(created, texture);
            }
            case FADE -> register(created, FADE_W, 1, (x, y) -> {
                float u = x / FADE_W;
                return switch (a) {
                    case FADE_LEFT -> smooth(1.0F - u);
                    case FADE_RIGHT -> smooth(u);
                    default -> smooth(1.0F - Math.abs(u * 2.0F - 1.0F));
                };
            });
            case STREAK -> register(created, STREAK_W, STREAK_H, (x, y) -> {
                // schraege Linie von oben rechts nach unten links, unten am hellsten
                float v = y / STREAK_H;
                float lineX = (STREAK_W - 1.5F) - v * (STREAK_W - 3.0F);
                float across = Math.abs(x - lineX);
                return clamp01(1.0F - across / 1.1F) * v * v;
            });
            case BEAM -> register(created, 1, BEAM_H, (x, y) -> {
                float v = (y - BEAM_H / 2.0F) / (BEAM_H / 2.0F);
                return (float) Math.exp(-v * v * 5.0F);
            });
            case TRIANGLE, TRIANGLE_LINE -> {
                int w = a & 0x3FFF;
                int t = a >>> 14;
                float half = w / 2.0F;
                float norm = (float) Math.sqrt(b * b + half * half);
                boolean line = kind == TRIANGLE_LINE;
                register(created, w, b, (x, y) -> {
                    float bottom = b - y;
                    float left = (b * (x - half) + half * y) / norm;
                    float right = (-b * (x - half) + half * y) / norm;
                    float d = Math.min(bottom, Math.min(left, right));
                    float fill = clamp01(d + 0.5F);
                    return line ? fill - clamp01(d - t + 0.5F) : fill;
                });
            }
            case LENS, LENS_LINE -> {
                int w = a & 0x3FFF;
                int t = a >>> 14;
                float half = w / 2.0F;
                float hb = b / 2.0F;
                float radius = (half * half + hb * hb) / (2.0F * hb);
                boolean line = kind == LENS_LINE;
                register(created, w, b, (x, y) -> {
                    float d1 = radius - (float) Math.sqrt((x - half) * (x - half) + (y - radius) * (y - radius));
                    float d2 = radius - (float) Math.sqrt((x - half) * (x - half) + (y - (b - radius)) * (y - (b - radius)));
                    float d = Math.min(d1, d2);
                    float fill = clamp01(d + 0.5F);
                    return line ? fill - clamp01(d - t + 0.5F) : fill;
                });
            }
            case ATLAS -> {
                // a = GUI-Scale: Schalter-Bahn (Pille), daneben Schalter-Knopf und Regler-Knopf (Kreise)
                int s = a;
                float trackR = SWITCH_H * s / 2.0F;
                float knobX = SWITCH_W * s + 2;
                float sliderX = (SWITCH_W + KNOB) * s + 4;
                register(created, (SWITCH_W + KNOB + SLIDER_KNOB) * s + 4, SWITCH_H * s, (x, y) -> {
                    if (x < knobX) {
                        float cx = Math.max(trackR, Math.min(SWITCH_W * s - trackR, x));
                        float d = (float) Math.sqrt((x - cx) * (x - cx) + (y - trackR) * (y - trackR));
                        return clamp01(trackR - d + 0.5F);
                    }
                    float r = x < sliderX ? KNOB * s / 2.0F : SLIDER_KNOB * s / 2.0F;
                    float cx = (x < sliderX ? knobX : sliderX) + r;
                    float d = (float) Math.sqrt((x - cx) * (x - cx) + (y - r) * (y - r));
                    return clamp01(r - d + 0.5F);
                });
            }
            case TRAPEZOID -> {
                int top = a & 0xFFF;
                int w = a >>> 12;
                float cx = w / 2.0F;
                float slope = (w - top) / 2.0F;
                float norm = (float) Math.sqrt(b * b + slope * slope);
                register(created, w, b, (x, y) -> {
                    float left = (b * (x - (cx - top / 2.0F)) + slope * y) / norm;
                    float right = (-b * (x - (cx + top / 2.0F)) + slope * y) / norm;
                    float d = Math.min(Math.min(y, b - y), Math.min(left, right));
                    return clamp01(d + 0.5F);
                });
            }
            case VIGNETTE -> register(created, VIGNETTE_SIZE, VIGNETTE_SIZE, (x, y) -> {
                float u = x / VIGNETTE_SIZE * 2.0F - 1.0F;
                float v = y / VIGNETTE_SIZE * 2.0F - 1.0F;
                return smooth((float) Math.sqrt(u * u * 0.75F + v * v * 0.75F) - 0.35F);
            });
            default -> throw new IllegalArgumentException("unknown shape " + kind);
        }
        TEXTURES.put(key, created);
        return created;
    }

    private static void register(Identifier id, int w, int h, AlphaFunction function) {
        DynamicTexture texture = new DynamicTexture(id::toString, w, h, false) {
            {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        };
        NativeImage image = texture.getPixels();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = Math.round(clamp01(function.alpha(x + 0.5F, y + 0.5F)) * 255.0F);
                image.setPixel(x, y, (alpha << 24) | 0xFFFFFF);
            }
        }
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }
}
