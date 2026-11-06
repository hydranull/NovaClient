package nova.nova_client.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * Vorberechnete Texturen fuer schnelle, weiche Effekte:
 * - Schattenkante um runde Flaechen (wenige Draw-Calls statt hunderter Ringe)
 * - geglaettete Kreise/Ringe und weiche Lichter fuer Effekte in der Welt
 */
public final class NovaTextures {
    private static final int CIRCLE_SIZE = 64;

    private static final Identifier CIRCLE = Identifier.fromNamespaceAndPath("nova_client", "dynamic/circle");
    private static final Identifier RING = Identifier.fromNamespaceAndPath("nova_client", "dynamic/ring");

    private static boolean ready;

    private interface AlphaFunction {
        float alpha(float x, float y);
    }

    private NovaTextures() {
    }

    private static void ensure() {
        if (ready) {
            return;
        }
        ready = true;
        TextureManager manager = Minecraft.getInstance().getTextureManager();

        float center = CIRCLE_SIZE / 2.0F;
        float outer = center - 0.5F;
        manager.register(CIRCLE, create("nova circle", CIRCLE_SIZE, (x, y) -> {
            float d = (float) Math.sqrt((x - center) * (x - center) + (y - center) * (y - center));
            return clamp01(outer - d + 0.5F);
        }));
        manager.register(RING, create("nova ring", CIRCLE_SIZE, (x, y) -> {
            float d = (float) Math.sqrt((x - center) * (x - center) + (y - center) * (y - center));
            return Math.min(clamp01(outer - d + 0.5F), clamp01(d - (outer - 2.0F) + 0.5F));
        }));

        // Grosse, weiche Lichter (hoch aufgeloest, damit sie auch gross gezogen keine Stufen bekommen)
        float half = SOFT_SIZE / 2.0F;
        manager.register(RADIAL, create("nova radial", SOFT_SIZE, (x, y) -> {
            float d = (float) Math.sqrt((x - half) * (x - half) + (y - half) * (y - half)) / half;
            float t = clamp01(1.0F - d);
            return t * t * (3.0F - 2.0F * t) * t;
        }));
        manager.register(HALO, create("nova halo", SOFT_SIZE, (x, y) -> {
            float d = (float) Math.sqrt((x - half) * (x - half) + (y - half) * (y - half)) / half;
            float line = (d - 0.9F) / 0.028F;
            float wide = (d - 0.9F) / 0.11F;
            return clamp01((float) Math.exp(-line * line) + 0.28F * (float) Math.exp(-wide * wide));
        }));
    }

    private static final int SOFT_SIZE = 256;
    private static final Identifier RADIAL = Identifier.fromNamespaceAndPath("nova_client", "dynamic/radial");
    private static final Identifier HALO = Identifier.fromNamespaceAndPath("nova_client", "dynamic/halo");

    /** Weiches, rundes Licht (Mitte hell, nach aussen auslaufend), auch als Ellipse. */
    public static void radial(GuiGraphicsExtractor g, float cx, float cy, float rx, float ry, int color) {
        int w = Math.round(rx * 2.0F);
        int h = Math.round(ry * 2.0F);
        if (w <= 0 || h <= 0 || ((color >>> 24) & 0xFF) < 2) {
            return;
        }
        ensure();
        g.blit(RenderPipelines.GUI_TEXTURED, RADIAL, Math.round(cx - rx), Math.round(cy - ry), 0, 0, w, h,
                SOFT_SIZE, SOFT_SIZE, SOFT_SIZE, SOFT_SIZE, color);
    }

    /** Feiner, leuchtender Ring (auch als Ellipse), Radius = Mitte der Linie. */
    public static void halo(GuiGraphicsExtractor g, float cx, float cy, float rx, float ry, int color) {
        float sx = rx / 0.9F;
        float sy = ry / 0.9F;
        int w = Math.round(sx * 2.0F);
        int h = Math.round(sy * 2.0F);
        if (w <= 0 || h <= 0 || ((color >>> 24) & 0xFF) < 2) {
            return;
        }
        ensure();
        g.blit(RenderPipelines.GUI_TEXTURED, HALO, Math.round(cx - sx), Math.round(cy - sy), 0, 0, w, h,
                SOFT_SIZE, SOFT_SIZE, SOFT_SIZE, SOFT_SIZE, color);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static DynamicTexture create(String name, int size, AlphaFunction function) {
        DynamicTexture texture = new DynamicTexture(() -> name, size, size, false);
        NativeImage image = texture.getPixels();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int a = Math.round(clamp01(function.alpha(x + 0.5F, y + 0.5F)) * 255.0F);
                image.setPixel(x, y, (a << 24) | 0xFFFFFF);
            }
        }
        texture.upload();
        return texture;
    }

    // ---------------------------------------------------------------- Leuchten / Schatten

    /**
     * Weicher Schein um ein abgerundetes Rechteck (radius, spread in GUI-Pixeln): ueberall gleich weit vom Rand - auch
     * an den runden Ecken - und ohne harte Innenkante. filled: innen deckend (Schatten), sonst innen leer (Leuchten;
     * darf nicht durch halb durchsichtige Knoepfe scheinen). Die Textur hat echte Pixelgroesse: vier Ecken daraus,
     * die Raender sind ihre mittlere Zeile/Spalte in die Laenge gezogen.
     */
    public static void roundGlow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, float spread,
                                 int color, boolean filled) {
        if (((color >>> 24) & 0xFF) < 2 || x1 <= x0 || y1 <= y0 || spread <= 0.0F) {
            return;
        }
        int s = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        int r = Math.max(0, Math.min(radius, Math.min((x1 - x0) / 2, (y1 - y0) / 2))) * s;
        int sp = Math.max(1, Math.round(spread * s));
        int half = r + sp;
        Identifier tex = roundGlowTexture(r, sp, filled);
        int px0 = x0 * s;
        int py0 = y0 * s;
        int px1 = x1 * s;
        int py1 = y1 * s;
        int w = px1 - px0 - 2 * r;
        int h = py1 - py0 - 2 * r;
        int d = half * 2;
        g.pose().pushMatrix();
        g.pose().scale(1.0F / s, 1.0F / s);
        // Ecken
        roundBlit(g, tex, px0 - sp, py0 - sp, 0, 0, half, half, half, half, d, color);
        roundBlit(g, tex, px1 - r, py0 - sp, half, 0, half, half, half, half, d, color);
        roundBlit(g, tex, px0 - sp, py1 - r, 0, half, half, half, half, half, d, color);
        roundBlit(g, tex, px1 - r, py1 - r, half, half, half, half, half, half, d, color);
        // Raender: mittlere Spalte/Zeile gestreckt
        if (w > 0) {
            roundBlit(g, tex, px0 + r, py0 - sp, half, 0, w, half, 1, half, d, color);
            roundBlit(g, tex, px0 + r, py1 - r, half, half, w, half, 1, half, d, color);
        }
        if (h > 0) {
            roundBlit(g, tex, px0 - sp, py0 + r, 0, half, half, h, half, 1, d, color);
            roundBlit(g, tex, px1 - r, py0 + r, half, half, half, h, half, 1, d, color);
        }
        if (filled && w > 0 && h > 0) {
            g.fill(px0 + r, py0 + r, px1 - r, py1 - r, color);
        }
        g.pose().popMatrix();
    }

    private static void roundBlit(GuiGraphicsExtractor g, Identifier tex, int x, int y, int u, int v, int w, int h,
                                  int uw, int vh, int size, int color) {
        if (w > 0 && h > 0) {
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, uw, vh, size, size, color);
        }
    }

    private static final java.util.Map<Long, Identifier> ROUND_GLOWS = new java.util.HashMap<>();

    /** Kreis-Schein: voll am Radius r (echte Pixel), nach aussen in sp Pixeln weich auslaufend. */
    private static Identifier roundGlowTexture(int r, int sp, boolean filled) {
        long key = ((long) r << 32) | ((long) sp << 1) | (filled ? 1L : 0L);
        Identifier id = ROUND_GLOWS.get(key);
        if (id != null) {
            return id;
        }
        if (ROUND_GLOWS.size() >= 96) {
            for (Identifier old : ROUND_GLOWS.values()) {
                Minecraft.getInstance().getTextureManager().release(old);
            }
            ROUND_GLOWS.clear();
        }
        Identifier created = Identifier.fromNamespaceAndPath("nova_client",
                "dynamic/round_glow_" + r + "_" + sp + (filled ? "_f" : ""));
        int size = 2 * (r + sp);
        float center = size / 2.0F;
        DynamicTexture texture = new DynamicTexture(created::toString, size, size, false) {
            {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        };
        NativeImage image = texture.getPixels();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = x + 0.5F - center;
                float dy = y + 0.5F - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float t = clamp01(1.0F - (dist - r) / sp);
                float a = dist >= r ? t * t : filled ? 1.0F : clamp01(dist - (r - 1.0F));
                image.setPixel(x, y, (Math.round(a * 255.0F) << 24) | 0xFFFFFF);
            }
        }
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(created, texture);
        ROUND_GLOWS.put(key, created);
        return created;
    }

    // ---------------------------------------------------------------- Kreise

    /** Gefuellter, geglaetteter Kreis. */
    public static void circle(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
        if (radius <= 0) {
            return;
        }
        ensure();
        g.blit(RenderPipelines.GUI_TEXTURED, CIRCLE, cx - radius, cy - radius, 0, 0, radius * 2, radius * 2,
                CIRCLE_SIZE, CIRCLE_SIZE, CIRCLE_SIZE, CIRCLE_SIZE, color);
    }

    /** Duenner, geglaetteter Ring. */
    public static void ring(GuiGraphicsExtractor g, int cx, int cy, int radius, int color) {
        if (radius <= 0) {
            return;
        }
        ensure();
        g.blit(RenderPipelines.GUI_TEXTURED, RING, cx - radius, cy - radius, 0, 0, radius * 2, radius * 2,
                CIRCLE_SIZE, CIRCLE_SIZE, CIRCLE_SIZE, CIRCLE_SIZE, color);
    }
}
