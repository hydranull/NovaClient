package nova.nova_client.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Album-Cover von Spotify als Textur, dazu eine stark verkleinerte Version als weicher Farbschleier
 * ("Ambient Light") und die kraeftigste Farbe des Covers als Akzent. Nur im Render-Thread benutzen.
 */
public final class SpotifyArt {
    private static final int AMBIENT = 6;

    private static DynamicTexture cover;
    private static DynamicTexture ambient;
    private static String loadedPath = "";
    /** Zeitpunkt, an dem das aktuelle Cover geladen wurde (fuer die Einblend-Animation). */
    private static long loadedAt;

    private static int targetAccent;
    private static float accentR = -1.0F;
    private static float accentG;
    private static float accentB;
    private static long lastFrame = Util.getMillis();

    private SpotifyArt() {
    }

    /** Neues Cover laden, falls sich der Pfad geaendert hat. Einmal pro Frame aufrufen. */
    public static void update() {
        String path = Spotify.coverPath;
        if (!path.equals(loadedPath)) {
            loadedPath = path;
            load(path);
        }
        // Akzentfarbe weich zur Zielfarbe blenden
        long now = Util.getMillis();
        float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        int target = targetAccent != 0 ? targetAccent : NovaGui.BRAND;
        if (accentR < 0.0F) {
            accentR = (target >> 16) & 0xFF;
            accentG = (target >> 8) & 0xFF;
            accentB = target & 0xFF;
        }
        accentR = NovaGui.approach(accentR, (target >> 16) & 0xFF, 4.0F, dt);
        accentG = NovaGui.approach(accentG, (target >> 8) & 0xFF, 4.0F, dt);
        accentB = NovaGui.approach(accentB, target & 0xFF, 4.0F, dt);
    }

    private static void load(String path) {
        if (cover != null) {
            cover.close();
            cover = null;
        }
        if (ambient != null) {
            ambient.close();
            ambient = null;
        }
        targetAccent = 0;
        if (path.isEmpty()) {
            return;
        }
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            NativeImage image = NativeImage.read(in);
            targetAccent = dominantColor(image);
            ambient = createAmbient(image);
            cover = new DynamicTexture(() -> "nova spotify cover", image);
            cover.upload();
            loadedAt = Util.getMillis();
        } catch (Exception e) {
            System.err.println("[Nova] Could not load Spotify cover: " + e.getMessage());
        }
    }

    public static boolean hasCover() {
        return cover != null;
    }

    /** 0..1: wie weit das aktuelle Cover schon eingeblendet ist. */
    public static float reveal() {
        return cover == null ? 0.0F : NovaGui.easeOutCubic((Util.getMillis() - loadedAt) / 450.0F);
    }

    /** Akzentfarbe (kraeftigste Farbe des Covers, sonst die Akzentfarbe). */
    public static int accent() {
        if (accentR < 0.0F) {
            return NovaGui.BRAND;
        }
        return 0xFF000000 | (Math.round(accentR) << 16) | (Math.round(accentG) << 8) | Math.round(accentB);
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Cover mit runden Ecken. Ohne Cover: Platzhalter mit Note im Akzent-Verlauf. */
    public static void drawCover(GuiGraphicsExtractor g, int x0, int y0, int size, int radius) {
        if (cover == null) {
            drawPlaceholder(g, x0, y0, size, radius);
            return;
        }
        roundedBlit(g, cover, x0, y0, x0 + size, y0 + size, radius);
        // Einblenden: heller Schleier, der nach dem Laden verschwindet
        float r = reveal();
        if (r < 1.0F) {
            NovaShapes.rect(g, x0, y0, x0 + size, y0 + size, radius, NovaGui.alpha(0xFFFFFFFF, (1.0F - r) * 0.8F));
        }
        // leichter Glanz oben
        g.fillGradient(x0 + radius, y0 + 1, x0 + size - radius, y0 + size / 3, 0x18FFFFFF, 0x00FFFFFF);
    }

    /** Weicher Farbschleier aus dem Cover ueber eine Flaeche (runde Ecken). */
    public static void drawAmbient(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius) {
        if (ambient != null) {
            roundedBlit(g, ambient, x0, y0, x1, y1, radius);
        }
    }

    private static void drawPlaceholder(GuiGraphicsExtractor g, int x0, int y0, int size, int radius) {
        int x1 = x0 + size;
        int y1 = y0 + size;
        NovaShapes.rect(g, x0, y0, x1, y1, radius, NovaGui.mix(NovaGui.BRAND, 0xFF000000, 0.55F));
        g.fillGradient(x0 + 1, y0 + radius, x1 - 1, y1 - radius, NovaGui.alpha(NovaGui.BRAND_2, 0.35F), 0x00000000);
        // Achtelnote
        float s = size / 32.0F;
        int cx = x0 + size / 2;
        int cy = y0 + size / 2;
        int color = NovaGui.alpha(0xFFFFFFFF, 0.85F);
        int stemX = cx + Math.round(2 * s);
        g.fill(stemX, cy - Math.round(9 * s), stemX + Math.max(1, Math.round(1.5F * s)), cy + Math.round(5 * s), color);
        g.fill(stemX, cy - Math.round(9 * s), stemX + Math.round(6 * s), cy - Math.round(7 * s), color);
        NovaTextures.circle(g, cx - Math.round(1 * s), cy + Math.round(6 * s), Math.max(2, Math.round(3.5F * s)), color);
    }

    /** Textur mit geglaetteten runden Ecken zeichnen (zeilenweise). */
    private static void roundedBlit(GuiGraphicsExtractor g, DynamicTexture texture, int x0, int y0, int x1, int y1, int radius) {
        int w = x1 - x0;
        int h = y1 - y0;
        if (w <= 0 || h <= 0) {
            return;
        }
        var view = texture.getTextureView();
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        int r = Math.max(0, Math.min(radius, Math.min(w / 2, h / 2)));
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5D;
            int in = (int) Math.ceil(r - Math.sqrt(Math.max(0.0D, r * r - dy * dy)));
            float u0 = in / (float) w;
            float u1 = 1.0F - u0;
            Compat.blit(g, view, sampler, x0 + in, y0 + i, x1 - in, y0 + i + 1, u0, u1, i / (float) h, (i + 1) / (float) h);
            Compat.blit(g, view, sampler, x0 + in, y1 - i - 1, x1 - in, y1 - i, u0, u1, (h - i - 1) / (float) h, (h - i) / (float) h);
        }
        Compat.blit(g, view, sampler, x0, y0 + r, x1, y1 - r, 0.0F, 1.0F, r / (float) h, (h - r) / (float) h);
    }

    // ---------------------------------------------------------------- Farben

    /** Kraeftigste Farbe: Durchschnitt der Pixel, gewichtet nach Saettigung und Helligkeit. */
    private static int dominantColor(NativeImage image) {
        double r = 0;
        double gr = 0;
        double b = 0;
        double total = 0;
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y += 4) {
            for (int x = 0; x < w; x += 4) {
                int c = image.getPixel(x, y);
                int pr = (c >> 16) & 0xFF;
                int pg = (c >> 8) & 0xFF;
                int pb = c & 0xFF;
                int max = Math.max(pr, Math.max(pg, pb));
                int min = Math.min(pr, Math.min(pg, pb));
                float saturation = max == 0 ? 0.0F : (max - min) / (float) max;
                float value = max / 255.0F;
                double weight = saturation * saturation * value + 0.01D;
                r += pr * weight;
                gr += pg * weight;
                b += pb * weight;
                total += weight;
            }
        }
        if (total <= 0) {
            return 0;
        }
        int cr = (int) (r / total);
        int cg = (int) (gr / total);
        int cb = (int) (b / total);
        // aufhellen, damit die Farbe auf dunklem Grund leuchtet
        int max = Math.max(cr, Math.max(cg, cb));
        if (max > 0 && max < 200) {
            float boost = 200.0F / max;
            cr = Math.min(255, Math.round(cr * boost));
            cg = Math.min(255, Math.round(cg * boost));
            cb = Math.min(255, Math.round(cb * boost));
        }
        return 0xFF000000 | (cr << 16) | (cg << 8) | cb;
    }

    /** Winzige, abgedunkelte Version des Covers: hochskaliert mit linearer Filterung ein weicher Farbverlauf. */
    private static DynamicTexture createAmbient(NativeImage image) {
        DynamicTexture texture = new DynamicTexture(() -> "nova spotify ambient", AMBIENT, AMBIENT, false);
        NativeImage pixels = texture.getPixels();
        int w = image.getWidth();
        int h = image.getHeight();
        for (int ty = 0; ty < AMBIENT; ty++) {
            for (int tx = 0; tx < AMBIENT; tx++) {
                long r = 0;
                long gr = 0;
                long b = 0;
                int n = 0;
                for (int y = ty * h / AMBIENT; y < (ty + 1) * h / AMBIENT; y += 2) {
                    for (int x = tx * w / AMBIENT; x < (tx + 1) * w / AMBIENT; x += 2) {
                        int c = image.getPixel(x, y);
                        r += (c >> 16) & 0xFF;
                        gr += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                n = Math.max(1, n);
                int color = (0xFF << 24) | ((int) (r / n) << 16) | ((int) (gr / n) << 8) | (int) (b / n);
                pixels.setPixel(tx, ty, color);
            }
        }
        texture.upload();
        return texture;
    }
}
