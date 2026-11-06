package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/**
 * Der Nova-Sternenhimmel ueber den ganzen Bildschirm - derselbe wie in den Karten des Modul-Menues (NovaMenu): feine
 * Sterne treiben langsam nach oben und funkeln, ein paar hellere dazwischen, und ab und zu fliegt eine Sternschnuppe
 * schraeg durchs Bild. Hintergrund fuer Lade- und Titelbildschirm (statt des frueheren Punktrasters).
 * <p>
 * Laeuft auch beim Spielstart, bevor Einstellungen geladen sind: die Zeit kommt deshalb direkt aus der Uhr.
 */
public final class NovaStars {
    private static final long EPOCH = Util.getMillis();

    private NovaStars() {
    }

    /** Sterne ueber die Flaeche x0/y0 - x1/y1, Deckkraft alpha (0..1). */
    public static void draw(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float alpha) {
        if (alpha <= 0.004F) {
            return;
        }
        float t = (Util.getMillis() - EPOCH) / 1000.0F;
        float w = x1 - x0;
        float h = y1 - y0;
        // gleiche Dichte wie in den Menue-Karten, unabhaengig von der Fenstergroesse
        int count = Math.max(24, Math.round(w * h / 2300.0F));
        for (int i = 0; i < count; i++) {
            float speed = 1.5F + 3.5F * hash(i * 7 + 3);
            float sx = x0 + w * hash(i * 7);
            float sy = y0 + ((h * hash(i * 7 + 1) - t * speed) % h + h) % h;
            boolean bright = hash(i * 7 + 6) > 0.93F;
            float size = bright ? 0.75F + 0.35F * hash(i * 7 + 2) : 0.4F + 0.35F * hash(i * 7 + 2);
            float twinkle = 0.5F + 0.5F * (float) Math.sin(t * (0.8F + 1.6F * hash(i * 7 + 4)) + 6.28F * hash(i * 7 + 5));
            float a = (bright ? 0.22F + 0.3F * twinkle : 0.05F + 0.17F * twinkle) * alpha;
            NovaShapes.disc(g, sx, sy, size, NovaGui.alpha(0xFFFFFFFF, Math.min(1.0F, a)));
        }
        shootingStar(g, x0, y0, w, h, t, 0, alpha);
        if (w > 600.0F) {
            shootingStar(g, x0, y0, w, h, t + 2.7F, 1, alpha);
        }
    }

    /** Alle 5-8 Sekunden eine Sternschnuppe, schraeg von oben nach unten, mit ausblendendem Schweif. */
    private static void shootingStar(GuiGraphicsExtractor g, int x0, int y0, float w, float h, float time, int lane,
                                     float alpha) {
        float period = 5.0F + 3.0F * hash(1000 + lane);
        float clock = time + period * hash(1001 + lane);
        int cycle = (int) Math.floor(clock / period);
        float local = clock - cycle * period;
        float duration = 1.1F;
        if (local >= duration) {
            return;
        }
        float p = local / duration;
        boolean leftward = hash(cycle * 3 + 7 + lane * 97) < 0.5F;
        float startX = x0 + w * (leftward ? 0.55F + 0.4F * hash(cycle * 3 + 8 + lane * 97)
                : 0.05F + 0.4F * hash(cycle * 3 + 8 + lane * 97));
        float startY = y0 + h * 0.5F * hash(cycle * 3 + 9 + lane * 97);
        float dirX = leftward ? -0.8F : 0.8F;
        float dirY = 0.6F;
        float travel = Math.min(w * 0.45F, 220.0F);
        float headX = startX + dirX * travel * p;
        float headY = startY + dirY * travel * p;
        float tail = 46.0F;
        float fade = (float) Math.sin(Math.PI * p);
        for (int k = 0; k < 3; k++) {
            float from = tail * (1.0F - k / 3.0F);
            float to = tail * (1.0F - (k + 1) / 3.0F);
            NovaShapes.beam(g, headX - dirX * from, headY - dirY * from, headX - dirX * to, headY - dirY * to, 1.6F,
                    NovaGui.alpha(0xFFFFFFFF, (0.08F + 0.22F * k) * fade * alpha));
        }
        NovaShapes.disc(g, headX, headY, 1.2F, NovaGui.alpha(0xFFFFFFFF, 0.85F * fade * alpha));
    }

    private static float hash(int n) {
        int h = n * 374761393 + 0x5bd1e995;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }
}
