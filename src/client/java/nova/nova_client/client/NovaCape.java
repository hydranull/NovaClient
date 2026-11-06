package nova.nova_client.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Textur des Nova Capes: dieselben fliessenden Lichtadern wie der Nova-Tide-Himmel auf dunklem Navy, mit leuchtendem
 * Rand und einem Nova-Stern. Die Textur hat das normale Cape-Layout (64x32, hier 6-fach aufgeloest) und wird
 * laufend neu gemalt; Elytren nutzen dieselbe Textur. Gemalt wird in einem eigenen Hintergrund-Thread - der
 * Render-Thread laedt nur das fertige Bild hoch, die FPS bleiben davon unberuehrt.
 */
public final class NovaCape {
    public static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("nova_client", "dynamic/nova_cape");
    private static final ClientAsset.Texture ASSET = new ClientAsset.DownloadedTexture(TEXTURE_ID, "nova_client:nova_cape");
    /** Pixel je Einheit des 64x32-Layouts. */
    private static final int SCALE = 6;
    private static final int WIDTH = 64 * SCALE;
    private static final int HEIGHT = 32 * SCALE;

    // Bereiche im Layout (Einheiten): Cape-Quader 22x17 oben links, Elytra 24x22 daneben
    private static final int CAPE_W = 22;
    private static final int CAPE_H = 17;
    private static final int ELYTRA_X = 22;
    private static final int ELYTRA_W = 24;
    private static final int ELYTRA_H = 22;

    private static final int NAVY_TOP = 0x0A1230;
    private static final int NAVY_BOTTOM = 0x06091C;

    private static final ExecutorService PAINTER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NovaClient-Cape");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final AtomicBoolean PAINTING = new AtomicBoolean();
    /** Ein neues Bild ist fertig gemalt und wartet aufs Hochladen. */
    private static volatile boolean painted;

    private static DynamicTexture texture;
    private static PlayerSkin cachedOriginal;
    private static PlayerSkin cachedSkin;

    private NovaCape() {
    }

    /** Skin mit dem Nova Cape statt des eigenen Capes. */
    public static PlayerSkin withCape(PlayerSkin original) {
        if (texture == null) {
            return original;
        }
        if (original != cachedOriginal || cachedSkin == null) {
            cachedOriginal = original;
            cachedSkin = new PlayerSkin(original.body(), ASSET, null, original.model(), original.secure());
        }
        return cachedSkin;
    }

    /**
     * Fertig gemaltes Bild hochladen und das naechste im Hintergrund malen lassen (Render-Thread). Beim ersten Aufruf
     * wird einmal direkt gemalt, damit das Cape sofort da ist.
     */
    public static void paint() {
        if (texture == null) {
            texture = new DynamicTexture(() -> "nova cape", WIDTH, HEIGHT, true);
            Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, texture);
            NativeImage first = texture.getPixels();
            if (first != null) {
                paintImage(first, Util.getMillis());
                texture.upload();
            }
            return;
        }
        NativeImage image = texture.getPixels();
        if (image == null) {
            return;
        }
        if (painted) {
            painted = false;
            texture.upload();
        }
        if (PAINTING.compareAndSet(false, true)) {
            long now = Util.getMillis();
            PAINTER.execute(() -> {
                try {
                    paintImage(image, now);
                    painted = true;
                } finally {
                    PAINTING.set(false);
                }
            });
        }
    }

    private static void paintImage(NativeImage image, long millis) {
        float time = (millis % 3_600_000L) / 1000.0F;
        paintArea(image, 0, CAPE_W, CAPE_H, time);
        paintArea(image, ELYTRA_X, ELYTRA_W, ELYTRA_H, time + 7.0F);
        // Rand und Stern auf beiden grossen Flaechen des Capes (vorne 1..11, hinten 12..22)
        frame(image, 1, 1, 10, 16, time);
        frame(image, 12, 1, 10, 16, time);
        star(image, 6.0F, 6.0F, time);
        star(image, 17.0F, 6.0F, time);
    }

    /** Lichtadern ueber einen Bereich (in Einheiten) malen. */
    private static void paintArea(NativeImage image, int unitX, int unitW, int unitH, float time) {
        int x0 = unitX * SCALE;
        int w = unitW * SCALE;
        int h = unitH * SCALE;
        for (int py = 0; py < h; py++) {
            float v = py / (float) SCALE;
            int sky = NovaFx.mix(NAVY_TOP, NAVY_BOTTOM, py / (float) h);
            for (int px = 0; px < w; px++) {
                float u = px / (float) SCALE;
                float light = SkyPainters.tideLight(u * 0.42F, v * 0.42F, time);
                float hue = 0.5F + 0.5F * SkyPainters.fastSin(u * 0.25F + v * 0.18F + time * 0.4F);
                int tint = NovaFx.mix(NovaFx.MINT, NovaFx.VIOLET, hue);
                float vein = smooth(0.08F, 0.6F, light) * 0.85F + smooth(0.0F, 0.4F, light) * 0.2F;
                float core = smooth(0.55F, 1.1F, light) * 0.5F;
                int r = channel(red(sky) + red(tint) * vein + 255 * core);
                int g = channel(green(sky) + green(tint) * vein + 255 * core);
                int b = channel(blue(sky) + blue(tint) * vein + 255 * core);
                image.setPixel(x0 + px, py, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
    }

    /** Leuchtender Rand um eine Flaeche (Einheiten), Farbe wandert rundherum. */
    private static void frame(NativeImage image, int unitX, int unitY, int unitW, int unitH, float time) {
        int x0 = unitX * SCALE;
        int y0 = unitY * SCALE;
        int w = unitW * SCALE;
        int h = unitH * SCALE;
        int thickness = Math.max(2, SCALE / 2);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int edge = Math.min(Math.min(px, w - 1 - px), Math.min(py, h - 1 - py));
                if (edge >= thickness + 2) {
                    continue;
                }
                float along = (px + py) / (float) (w + h);
                int color = NovaFx.nova(along + time * 0.25F);
                float strength = edge < thickness ? 1.0F : 0.35F;
                blend(image, x0 + px, y0 + py, NovaFx.mix(color, 0xFFFFFF, edge == 0 ? 0.3F : 0.0F), strength);
            }
        }
    }

    /** Vierzackiger Nova-Stern mit Schein, pulsiert leicht. Mitte in Einheiten. */
    private static void star(NativeImage image, float centerU, float centerV, float time) {
        float pulse = 0.85F + 0.15F * SkyPainters.fastSin(time * 2.2F);
        float size = 2.4F * SCALE * pulse;
        int cx = Math.round(centerU * SCALE);
        int cy = Math.round(centerV * SCALE);
        int reach = Math.round(size * 1.6F);
        for (int dy = -reach; dy <= reach; dy++) {
            for (int dx = -reach; dx <= reach; dx++) {
                float ax = Math.abs(dx) / size;
                float ay = Math.abs(dy) / size;
                // Sternform: |x|^0.5 + |y|^0.5 < 1 (spitze Zacken), dazu runder Schein
                float shape = (float) (Math.sqrt(ax) + Math.sqrt(ay));
                float starAlpha = smooth(1.0F, 0.85F, shape);
                float glowAlpha = smooth(1.6F, 0.0F, (float) Math.sqrt(ax * ax + ay * ay)) * 0.35F;
                if (starAlpha <= 0.0F && glowAlpha <= 0.0F) {
                    continue;
                }
                blend(image, cx + dx, cy + dy, NovaFx.VIOLET, glowAlpha);
                blend(image, cx + dx, cy + dy, NovaFx.mix(NovaFx.LAVENDER, 0xFFFFFF, 0.4F), starAlpha);
            }
        }
    }

    private static void blend(NativeImage image, int x, int y, int rgb, float alpha) {
        if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT || alpha <= 0.0F) {
            return;
        }
        int old = image.getPixel(x, y);
        int mixed = NovaFx.mix(old & 0xFFFFFF, rgb, Math.min(1.0F, alpha));
        image.setPixel(x, y, 0xFF000000 | mixed);
    }

    private static float smooth(float edge0, float edge1, float value) {
        float t = Math.max(0.0F, Math.min(1.0F, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float red(int color) {
        return (color >> 16) & 0xFF;
    }

    private static float green(int color) {
        return (color >> 8) & 0xFF;
    }

    private static float blue(int color) {
        return color & 0xFF;
    }

    private static int channel(float value) {
        return value <= 0.0F ? 0 : value >= 255.0F ? 255 : (int) value;
    }
}
