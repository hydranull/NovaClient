package nova.nova_client.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.util.Locale;

import static nova.nova_client.client.VanishAlert.clamp01;
import static nova.nova_client.client.VanishAlert.decay;
import static nova.nova_client.client.VanishAlert.easeInCubic;
import static nova.nova_client.client.VanishAlert.easeOutCubic;
import static nova.nova_client.client.VanishAlert.hash01;
import static nova.nova_client.client.VanishAlert.jitter;
import static nova.nova_client.client.VanishAlert.lerp;
import static nova.nova_client.client.VanishAlert.smooth;
import static nova.nova_client.client.VanishAlert.spaced;

/**
 * Anti-Vanish-Erscheinung im Classic-Design - "Nova" wie Supernova (gut 5 s, die Welt bleibt sichtbar):
 * <ol>
 *     <li>Sterne schiessen von allen Seiten in die Bildmitte (Sog wie im Hyperraum), dazu das klingelnde
 *     Hoehlengeraeusch.</li>
 *     <li>Dort zuendet ein Stern, sein Puls wird immer schneller; um ihn formt sich eine schraeg liegende Spiralgalaxie
 *     mit Nebelwolken in Akzentfarbe, Violett und Pink.</li>
 *     <li>Die Galaxie wird in den Stern gerissen, er zieht sich zu einem Punkt zusammen - Stille.</li>
 *     <li>Supernova: Lichtblitz, zwei Schockwellen, Truemmer fliegen mit Leuchtspur davon, ein Lichtstreifen quer durchs
 *     Bild, ein dumpfer Knall; ein farbiger Nebel bleibt zurueck.</li>
 *     <li>Aus dem Sternenstaub formt sich "NAME is here", ein Sternbild zeichnet sich darum, Sterne funkeln; dann
 *     verglimmt alles.</li>
 * </ol>
 */
final class CosmicAlert implements VanishAlert.Scene {
    static final CosmicAlert SCENE = new CosmicAlert();

    private static final int WHITE = 0xFFFFFFFF;
    private static final int STARLIGHT = 0xFFEDEBFF;
    private static final int PURPLE = 0xFF9146FF;
    private static final int PINK = 0xFFFF5FC8;

    // Ablauf in ms
    private static final int STAR = 450;
    private static final int GALAXY = 650;
    private static final int COLLAPSE_START = 1750;
    private static final int NOVA = 2050;
    private static final int TEXT = 2550;
    private static final int FADE = 4850;
    private static final int END = 5450;

    private static final int WARP_STARS = 90;
    private static final int GALAXY_STARS = 170;
    private static final int DEBRIS = 70;

    private static final Style STYLE = VanishAlert.font("poppins");

    private CosmicAlert() {
    }

    @Override
    public int length() {
        return END;
    }

    @Override
    public void sounds(float previous, float t) {
        if (previous < NOVA && t >= NOVA) {
            VanishAlert.play(SoundEvents.FIREWORK_ROCKET_LARGE_BLAST_FAR, 0.5F, 0.85F);
        }
    }

    @Override
    public void draw(GuiGraphicsExtractor g, Font font, int width, int height, float t, String name, String detail) {
        float cx = width / 2.0F;
        float cy = height / 2.0F;
        float shake = 4.0F * decay(t - NOVA, 170.0F);
        int brand = NovaGui.BRAND;

        // Ebene 1: Weltall
        g.nextStratum();
        float flash = 0.55F * decay(t - NOVA, 260.0F);
        if (flash > 0.01F) {
            g.fill(0, 0, width, height, NovaGui.alpha(WHITE, flash));
        }
        g.pose().pushMatrix();
        g.pose().translate(jitter(t, 0, shake), jitter(t, 1, shake));
        drawNebula(g, cx, cy, width, height, t, brand);
        drawWarp(g, cx, cy, width, height, t, brand);
        drawGalaxy(g, cx, cy, width, height, t, brand);
        drawStar(g, cx, cy, width, t, brand);
        drawSupernova(g, cx, cy, width, height, t, brand);
        g.pose().popMatrix();

        // Ebene 2: Text
        g.nextStratum();
        drawText(g, font, width, height, t, name, detail, brand);
    }

    /** Sterne schiessen von allen Seiten in die Mitte, mit Leuchtspur. */
    private static void drawWarp(GuiGraphicsExtractor g, float cx, float cy, int width, int height, float t, int brand) {
        if (t > 1100.0F) {
            return;
        }
        float far = Math.max(width, height) * 0.8F;
        for (int i = 0; i < WARP_STARS; i++) {
            float delay = hash01(i * 5 + 1) * 320.0F;
            float p = (t - delay) / 680.0F;
            if (p <= 0.0F || p >= 1.0F) {
                continue;
            }
            double angle = hash01(i * 5 + 2) * Math.PI * 2.0;
            float r0 = far * (0.55F + 0.45F * hash01(i * 5 + 3));
            float head = r0 * (1.0F - easeInCubic(p));
            float tail = r0 * (1.0F - easeInCubic(Math.max(0.0F, p - 0.09F)));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float a = smooth(p * 3.0F) * (1.0F - p * p * p * p);
            int color = NovaGui.mix(STARLIGHT, brand, hash01(i * 5 + 4) * 0.6F);
            NovaShapes.beam(g, cx + cos * tail, cy + sin * tail, cx + cos * head, cy + sin * head, 3.5F, NovaGui.alpha(brand, 0.25F * a));
            NovaShapes.beam(g, cx + cos * tail, cy + sin * tail, cx + cos * head, cy + sin * head, 1.1F, NovaGui.alpha(color, a));
        }
    }

    /** Der Stern in der Mitte: Puls wird schneller, Lichtkreuz; beim Kollaps ein greller Punkt. */
    private static void drawStar(GuiGraphicsExtractor g, float cx, float cy, int width, float t, int brand) {
        if (t < STAR || t > NOVA + 40.0F) {
            return;
        }
        float grow = easeOutCubic((t - STAR) / 500.0F);
        float collapse = easeInCubic((t - COLLAPSE_START) / (NOVA - COLLAPSE_START));
        double phase = (t - STAR) * 0.006 + (t - STAR) * (t - STAR) * 0.0000035;
        float pulse = 0.5F + 0.5F * (float) Math.sin(phase);
        float size = grow * (1.0F - collapse * 0.85F);
        NovaTextures.radial(g, cx, cy, 70.0F * size, 70.0F * size, NovaGui.alpha(brand, (0.45F + 0.2F * pulse) * grow));
        NovaTextures.radial(g, cx, cy, 24.0F * size + 6.0F * collapse, 24.0F * size + 6.0F * collapse,
                NovaGui.alpha(WHITE, 0.75F + 0.25F * collapse));
        NovaShapes.disc(g, cx, cy, (2.5F + 2.0F * pulse) * size + 1.0F, WHITE);
        // Lichtkreuz und waagerechter Streifen
        float flare = (0.5F + 0.5F * pulse) * grow + collapse;
        float arm = (16.0F + 14.0F * pulse) * size + 30.0F * collapse;
        double spin = t * 0.0006;
        for (int k = 0; k < 2; k++) {
            double a = spin + k * Math.PI / 2.0;
            float dx = (float) Math.cos(a) * arm;
            float dy = (float) Math.sin(a) * arm;
            NovaShapes.beam(g, cx - dx, cy - dy, cx + dx, cy + dy, 1.4F, NovaGui.alpha(WHITE, 0.8F * Math.min(1.0F, flare)));
        }
        float streak = width * (0.12F + 0.2F * collapse) * grow;
        NovaShapes.fade(g, Math.round(cx - streak), Math.round(cy), Math.round(cx + streak), Math.round(cy) + 1,
                NovaShapes.FADE_CENTER, NovaGui.alpha(NovaGui.mix(brand, WHITE, 0.5F), 0.7F * Math.min(1.0F, flare)));
    }

    /** Schraeg liegende Spiralgalaxie, die sich formt, dreht und beim Kollaps in den Stern gerissen wird. */
    private static void drawGalaxy(GuiGraphicsExtractor g, float cx, float cy, int width, int height, float t, int brand) {
        if (t < GALAXY || t > NOVA) {
            return;
        }
        float size = Math.min(width, height) * 0.32F;
        float collapse = easeInCubic((t - COLLAPSE_START) / (NOVA - COLLAPSE_START));
        float tilt = -0.38F;
        float cosT = (float) Math.cos(tilt);
        float sinT = (float) Math.sin(tilt);
        for (int j = 0; j < GALAXY_STARS; j++) {
            float u = hash01(j * 3 + 1);
            int arm = j % 2;
            float form = easeOutCubic((t - GALAXY - u * 350.0F) / 600.0F);
            if (form <= 0.0F) {
                continue;
            }
            float r = size * (0.12F + 0.88F * u) * (0.35F + 0.65F * form) * (1.0F - collapse);
            double angle = arm * Math.PI + u * 4.2 + (hash01(j * 3 + 2) - 0.5F) * 0.45
                    + (t - GALAXY) * 0.0011 * (1.25F - u * 0.6F) + collapse * 3.0;
            float px = (float) Math.cos(angle) * r;
            float py = (float) Math.sin(angle) * r * 0.42F;
            float x = cx + px * cosT - py * sinT;
            float y = cy + px * sinT + py * cosT;
            int outer = arm == 0 ? PURPLE : PINK;
            int color = u < 0.5F ? NovaGui.mix(WHITE, brand, u * 2.0F) : NovaGui.mix(brand, outer, (u - 0.5F) * 2.0F);
            float twinkle = 0.65F + 0.35F * (float) Math.sin(t * 0.008F + j);
            NovaShapes.disc(g, x, y, 0.55F + hash01(j * 3 + 3) * 0.9F, NovaGui.alpha(color, form * twinkle));
        }
    }

    /** Farbige Nebelwolken: um die Galaxie, nach der Supernova als groesserer Rest-Nebel. */
    private static void drawNebula(GuiGraphicsExtractor g, float cx, float cy, int width, int height, float t, int brand) {
        float before = smooth((t - GALAXY) / 700.0F) * (1.0F - smooth((t - COLLAPSE_START) / 300.0F));
        float after = smooth((t - NOVA) / 400.0F) * (1.0F - smooth((t - FADE) / (END - FADE)));
        float alpha = Math.max(before, after);
        if (alpha <= 0.01F) {
            return;
        }
        float spread = after > before ? 1.0F + 0.5F * smooth((t - NOVA) / 2500.0F) : 1.0F;
        float size = Math.min(width, height) * 0.36F * spread;
        int[] colors = {brand, PURPLE, PINK, brand};
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2.0 + t * 0.00012 * (k % 2 == 0 ? 1 : -1);
            float x = cx + (float) Math.cos(a) * size * 0.35F;
            float y = cy + (float) Math.sin(a) * size * 0.2F;
            NovaTextures.radial(g, x, y, size * 0.85F, size * 0.55F, NovaGui.alpha(colors[k], 0.13F * alpha));
        }
    }

    /** Supernova: Schockwellen, Truemmer mit Leuchtspur, Lichtstreifen quer durchs Bild. */
    private static void drawSupernova(GuiGraphicsExtractor g, float cx, float cy, int width, int height, float t, int brand) {
        float age = t - NOVA;
        if (age < 0.0F || age > 1600.0F) {
            return;
        }
        float far = Math.max(width, height);
        float q = clamp01(age / 900.0F);
        if (q < 1.0F) {
            float r = 10.0F + easeOutCubic(q) * far * 0.75F;
            NovaTextures.halo(g, cx, cy, r, r, NovaGui.alpha(brand, 0.75F * (1.0F - q) * (1.0F - q)));
            float r2 = 6.0F + easeOutCubic(q) * far * 0.5F;
            NovaTextures.halo(g, cx, cy, r2, r2 * 0.92F, NovaGui.alpha(PINK, 0.55F * (1.0F - q) * (1.0F - q)));
        }
        float core = decay(age, 280.0F);
        NovaTextures.radial(g, cx, cy, 40.0F + 160.0F * (1.0F - core), 40.0F + 120.0F * (1.0F - core), NovaGui.alpha(WHITE, core));
        // Lichtstreifen
        float streak = decay(age, 420.0F);
        float half = width * (0.25F + 0.45F * (1.0F - streak));
        NovaShapes.fade(g, Math.round(cx - half), Math.round(cy) - 1, Math.round(cx + half), Math.round(cy) + 1,
                NovaShapes.FADE_CENTER, NovaGui.alpha(NovaGui.mix(brand, WHITE, 0.6F), streak));
        // Truemmer
        float s = age / 1000.0F;
        float fade = 1.0F - age / 1600.0F;
        for (int i = 0; i < DEBRIS; i++) {
            double angle = hash01(i * 11 + 1) * Math.PI * 2.0;
            float speed = 120.0F + 300.0F * hash01(i * 11 + 2);
            float travel = speed * 0.5F * (1.0F - (float) Math.exp(-s / 0.5F));
            float back = speed * 0.5F * (1.0F - (float) Math.exp(-Math.max(0.0F, s - 0.06F) / 0.5F));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle) * 0.8F;
            int color = NovaGui.mix(WHITE, hash01(i * 11 + 3) < 0.5F ? brand : PINK, hash01(i * 11 + 4));
            NovaShapes.beam(g, cx + cos * back, cy + sin * back, cx + cos * travel, cy + sin * travel, 1.3F,
                    NovaGui.alpha(color, fade));
        }
    }

    /** Text: Buchstaben formen sich aus Sternenstaub, Sternbild darum, funkelnde Sterne. */
    private static void drawText(GuiGraphicsExtractor g, Font font, int width, int height, float t, String name, String detail,
                                 int brand) {
        float tp = t - TEXT;
        if (tp < 0.0F) {
            return;
        }
        float out = smooth((t - FADE) / (END - FADE));
        float alpha = 1.0F - out;
        if (alpha <= 0.01F) {
            return;
        }
        String rest = " is here";
        int nameWidth = VanishAlert.width(font, STYLE, name);
        int restWidth = VanishAlert.width(font, STYLE, rest);
        float scale = Math.max(1.3F, Math.min(2.6F, width * 0.8F / Math.max(1, nameWidth + restWidth)));
        float total = (nameWidth + restWidth) * scale;
        float x0 = width / 2.0F - total / 2.0F;
        float y0 = height / 2.0F - 4.5F * scale - 4.0F - out * 8.0F;
        float cx = width / 2.0F;
        float cy = y0 + 4.5F * scale;

        // Sternbild um den Text: Punkte auf einer Ellipse, Linien zeichnen sich nacheinander
        int points = 7;
        float rx = total / 2.0F + 34.0F;
        float ry = 9.0F * scale + 22.0F;
        float[] px = new float[points];
        float[] py = new float[points];
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2.0 * i / points + 0.4 + (hash01(i + 300) - 0.5F) * 0.5;
            float wobble = 0.85F + 0.3F * hash01(i + 400);
            px[i] = cx + (float) Math.cos(a) * rx * wobble;
            py[i] = cy + (float) Math.sin(a) * ry * wobble;
        }
        for (int i = 0; i < points; i++) {
            float grow = easeOutCubic((tp - 300.0F - i * 90.0F) / 260.0F);
            if (grow <= 0.0F) {
                continue;
            }
            int next = (i + 1) % points;
            NovaShapes.beam(g, px[i], py[i], lerp(px[i], px[next], grow), lerp(py[i], py[next], grow), 1.0F,
                    NovaGui.alpha(brand, 0.45F * alpha));
            NovaTextures.radial(g, px[i], py[i], 7.0F, 7.0F, NovaGui.alpha(brand, 0.6F * alpha * grow));
            NovaShapes.disc(g, px[i], py[i], 1.4F, NovaGui.alpha(WHITE, alpha * grow));
        }
        // funkelnde Sterne um den Text
        for (int i = 0; i < 9; i++) {
            float sx = cx + (hash01(i * 13 + 500) - 0.5F) * (total + 50.0F);
            float sy = cy + (hash01(i * 13 + 501) - 0.5F) * (18.0F * scale + 26.0F);
            float cycle = ((tp + hash01(i * 13 + 502) * 900.0F) % 900.0F) / 900.0F;
            float twinkle = (float) Math.pow(Math.sin(Math.PI * cycle), 2.0) * smooth(tp / 400.0F);
            float arm = 2.0F + 5.0F * twinkle;
            int color = NovaGui.alpha(NovaGui.mix(WHITE, brand, 0.3F), alpha * twinkle);
            NovaShapes.beam(g, sx - arm, sy, sx + arm, sy, 1.0F, color);
            NovaShapes.beam(g, sx, sy - arm, sx, sy + arm, 1.0F, color);
        }
        NovaTextures.radial(g, cx, cy, total * 0.7F, 24.0F * scale, NovaGui.alpha(brand, 0.12F * alpha));

        // Buchstaben: aus Sternenstaub - gross und hell, ziehen sich zusammen
        String all = name + rest;
        float cursor = 0.0F;
        for (int i = 0; i < all.length(); i++) {
            String ch = String.valueOf(all.charAt(i));
            int cw = VanishAlert.width(font, STYLE, ch);
            boolean isName = i < name.length();
            float local = tp - (isName ? i * 40.0F : 150.0F + name.length() * 40.0F + (i - name.length()) * 25.0F);
            float a = smooth(local / 260.0F) * alpha;
            if (a > 0.01F) {
                float grow = 1.0F + 0.8F * (1.0F - easeOutCubic(local / 320.0F));
                float shimmer = 0.5F + 0.5F * (float) Math.sin(t * 0.004F - i * 0.5F);
                int color = isName ? NovaGui.mix(NovaGui.mix(WHITE, brand, 0.45F), NovaGui.mix(WHITE, PINK, 0.35F), shimmer) : STARLIGHT;
                float lx = x0 + (cursor + cw / 2.0F) * scale;
                g.pose().pushMatrix();
                g.pose().translate(lx, cy);
                g.pose().scale(scale * grow, scale * grow);
                int x = Math.round(-cw / 2.0F);
                if (isName) {
                    int halo = NovaGui.alpha(brand, 0.25F * a);
                    VanishAlert.text(g, font, STYLE, ch, x - 1, -4, halo, false);
                    VanishAlert.text(g, font, STYLE, ch, x + 1, -4, halo, false);
                }
                VanishAlert.text(g, font, STYLE, ch, x, -4, NovaGui.alpha(color, a), true);
                g.pose().popMatrix();
            }
            cursor += cw;
        }

        if (!detail.isEmpty()) {
            float da = alpha * smooth((tp - 600.0F) / 400.0F);
            if (da > 0.01F) {
                String text = spaced(detail.toUpperCase(Locale.ROOT));
                float ds = 0.8F;
                int dw = VanishAlert.width(font, STYLE, text);
                g.pose().pushMatrix();
                g.pose().translate(cx - dw * ds / 2.0F, cy + 4.5F * scale + 8.0F);
                g.pose().scale(ds, ds);
                VanishAlert.text(g, font, STYLE, text, 0, 0, NovaGui.alpha(NovaGui.mix(brand, WHITE, 0.45F), 0.85F * da), true);
                g.pose().popMatrix();
            }
        }
    }
}
