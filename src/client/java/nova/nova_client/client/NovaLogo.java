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
 * Das Nova-Zeichen (N mit Verlauf und Stern, wie logo/nova-mark.svg) als Textur in echter Pixelgroesse: je Groesse
 * einmal berechnet (4x4 Abtastung je Pixel), dadurch bei jedem GUI-Scale scharf.
 */
public final class NovaLogo {
    /** Quadratischer Ausschnitt aus dem 512er SVG um N und Stern. */
    private static final float BOX_X = 119.5F;
    private static final float BOX_Y = 89.0F;
    private static final float BOX = 292.0F;
    /** Umriss des N (x, y im SVG). */
    private static final float[] N = {136, 376, 136, 136, 190, 136, 322, 290, 322, 196, 376, 196, 376, 376, 322, 376,
            190, 222, 190, 376};
    private static final float STAR_X = 349.0F;
    private static final float STAR_Y = 140.0F;
    private static final float STAR_R = 46.0F;
    private static final float GLOW_R = 62.0F;
    /** Verlauf des N: Cyan -> Blau -> Lila. */
    private static final int[] GRADIENT = {0x5CE1FF, 0x6E7BFF, 0x9146FF};
    private static final int SAMPLES = 4;
    private static final Map<Integer, Identifier> TEXTURES = new HashMap<>();

    private NovaLogo() {
    }

    /** Zeichen als size x size GUI-Pixel grosses Quadrat ab (x, y). */
    public static void draw(GuiGraphicsExtractor g, int x, int y, int size, float a) {
        if (a <= 0.02F || size <= 0) {
            return;
        }
        int s = NovaShapes.scale();
        int px = size * s;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1.0F / s, 1.0F / s);
        g.blit(RenderPipelines.GUI_TEXTURED, texture(px), 0, 0, 0, 0, px, px, px, px, px, px,
                NovaGui.alpha(0xFFFFFFFF, a));
        g.pose().popMatrix();
    }

    private static Identifier texture(int px) {
        Identifier id = TEXTURES.get(px);
        if (id != null) {
            return id;
        }
        id = Identifier.fromNamespaceAndPath("nova_client", "dynamic/logo_" + px);
        DynamicTexture texture = new DynamicTexture(id::toString, px, px, false) {
            {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        };
        NativeImage image = texture.getPixels();
        float unit = BOX / px;
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                image.setPixel(x, y, pixel(x, y, unit));
            }
        }
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(id, texture);
        TEXTURES.put(px, id);
        return id;
    }

    /** Mittelwert der Abtastpunkte eines Pixels (vormultipliziert gemischt, als ARGB zurueck). */
    private static int pixel(int x, int y, float unit) {
        float r = 0.0F;
        float g = 0.0F;
        float b = 0.0F;
        float a = 0.0F;
        for (int sy = 0; sy < SAMPLES; sy++) {
            for (int sx = 0; sx < SAMPLES; sx++) {
                float vx = BOX_X + (x + (sx + 0.5F) / SAMPLES) * unit;
                float vy = BOX_Y + (y + (sy + 0.5F) / SAMPLES) * unit;
                int color = sample(vx, vy);
                float sa = (color >>> 24) / 255.0F;
                r += ((color >> 16) & 0xFF) * sa;
                g += ((color >> 8) & 0xFF) * sa;
                b += (color & 0xFF) * sa;
                a += sa;
            }
        }
        if (a <= 0.0F) {
            return 0;
        }
        int alpha = Math.round(a / (SAMPLES * SAMPLES) * 255.0F);
        return alpha << 24 | Math.round(r / a) << 16 | Math.round(g / a) << 8 | Math.round(b / a);
    }

    /** Farbe an einem Punkt im SVG: Stern ueber N ueber Schein. */
    private static int sample(float x, float y) {
        float u = Math.abs(x - STAR_X) / STAR_R;
        float v = Math.abs(y - STAR_Y) / STAR_R;
        // Stern: vier eingezogene Spitzen
        if (Math.sqrt(u) + Math.sqrt(v) <= 1.0D) {
            return 0xFFFFFFFF;
        }
        if (insideN(x, y)) {
            float t = Math.max(0.0F, Math.min(1.0F, (x - 136.0F + y - 136.0F) / 480.0F));
            return 0xFF000000 | gradient(t);
        }
        float d = (float) Math.sqrt((x - STAR_X) * (x - STAR_X) + (y - STAR_Y) * (y - STAR_Y)) / GLOW_R;
        if (d < 1.0F) {
            int alpha = Math.round(0.5F * (1.0F - d) * (1.0F - d) * 255.0F);
            return alpha << 24 | GRADIENT[0];
        }
        return 0;
    }

    private static boolean insideN(float x, float y) {
        boolean inside = false;
        for (int i = 0, j = N.length - 2; i < N.length; j = i, i += 2) {
            float xi = N[i];
            float yi = N[i + 1];
            float xj = N[j];
            float yj = N[j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static int gradient(float t) {
        int from = t < 0.5F ? GRADIENT[0] : GRADIENT[1];
        int to = t < 0.5F ? GRADIENT[1] : GRADIENT[2];
        float k = t < 0.5F ? t * 2.0F : (t - 0.5F) * 2.0F;
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return r << 16 | g << 8 | b;
    }
}
