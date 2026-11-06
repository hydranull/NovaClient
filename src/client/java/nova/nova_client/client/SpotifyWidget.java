package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nova.nova_client.client.mods.SpotifyMod;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Spotify-Oberflaeche:
 * - HUD-Karte oben rechts ("Now Playing" mit Cover, drehender Schallplatte, Equalizer, Fortschritt)
 * - Mini-Player unten rechts in den Nova-Menues (mit Knoepfen, Klick aufs Cover oeffnet den grossen Player)
 * - gemeinsame Zeichen-Bausteine fuer {@link SpotifyScreen}
 */
public final class SpotifyWidget {
    public static final int GREEN = 0xFF1DB954;

    // ---------------------------------------------------------------- Pixel-Icons

    public static final String[] ICON_PLAY = {
            "X.....",
            "XXX...",
            "XXXXX.",
            "XXXXXX",
            "XXXXX.",
            "XXX...",
            "X....."};
    public static final String[] ICON_PAUSE = {
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX",
            "XX.XX"};
    public static final String[] ICON_NEXT = {
            "X....XX",
            "XX...XX",
            "XXX..XX",
            "XXXX.XX",
            "XXX..XX",
            "XX...XX",
            "X....XX"};
    public static final String[] ICON_PREV = mirror(ICON_NEXT);
    public static final String[] ICON_SHUFFLE = {
            ".......X.",
            "XX...XXXX",
            "..X.X..X.",
            "...X.....",
            "..X.X..X.",
            "XX...XXXX",
            ".......X."};
    public static final String[] ICON_REPEAT = {
            "......X..",
            ".XXXXXXX.",
            "X.....X..",
            "X.......X",
            "..X.....X",
            ".XXXXXXX.",
            "..X......"};

    // ---------------------------------------------------------------- Animationen (zeitbasiert, mehrfach pro Frame egal)

    private static long lastFrame = Util.getMillis();
    /** Zeit seit dem letzten Frame (fuer weiche Uebergaenge ausserhalb von frame()). */
    private static float lastDt;
    private static float vinylAngle;
    private static float playAnim;
    private static final float[] hover = new float[8];

    private SpotifyWidget() {
    }

    private static String[] mirror(String[] rows) {
        String[] out = new String[rows.length];
        for (int i = 0; i < rows.length; i++) {
            out[i] = new StringBuilder(rows[i]).reverse().toString();
        }
        return out;
    }

    /** Einmal pro Frame (mehrfach ist unschaedlich): Cover laden, Schallplatte drehen, Play-Animation. */
    static float frame() {
        SpotifyArt.update();
        long now = Util.getMillis();
        float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        lastDt = dt;
        boolean spinning = Spotify.running && Spotify.playing;
        playAnim = NovaGui.approach(playAnim, spinning ? 1.0F : 0.0F, 5.0F, dt);
        if (UiSettings.animations) {
            vinylAngle = (vinylAngle + dt * 2.2F * playAnim) % 6.2831855F;
        }
        return dt;
    }

    /** 0 = pausiert, 1 = spielt (weich animiert). */
    static float playAnim() {
        return playAnim;
    }

    static float hoverAnim(int id, boolean hovered, float dt) {
        hover[id] = NovaGui.approach(hover[id], hovered ? 1.0F : 0.0F, 16.0F, dt);
        return hover[id];
    }

    // ---------------------------------------------------------------- Bausteine

    /** Pixel-Icon zentriert um (cx, cy). */
    public static void icon(GuiGraphicsExtractor g, String[] rows, int cx, int cy, int scale, int color) {
        int w = rows[0].length() * scale;
        int h = rows.length * scale;
        int x0 = cx - w / 2;
        int y0 = cy - h / 2;
        for (int y = 0; y < rows.length; y++) {
            String row = rows[y];
            int x = 0;
            while (x < row.length()) {
                if (row.charAt(x) != 'X') {
                    x++;
                    continue;
                }
                int start = x;
                while (x < row.length() && row.charAt(x) == 'X') {
                    x++;
                }
                g.fill(x0 + start * scale, y0 + y * scale, x0 + x * scale, y0 + (y + 1) * scale, color);
            }
        }
    }

    /** Schallplatte mit Rillen, Glanz und Label in der Albumfarbe; dreht sich beim Abspielen. */
    public static void vinyl(GuiGraphicsExtractor g, int cx, int cy, int radius, int labelColor, float opacity) {
        if (radius < 4 || opacity <= 0.01F) {
            return;
        }
        NovaTextures.circle(g, cx, cy, radius, NovaGui.alpha(0xFF0E0E12, opacity));
        NovaTextures.ring(g, cx, cy, radius, NovaGui.alpha(0x30FFFFFF, opacity));
        for (float f : new float[]{0.86F, 0.74F, 0.62F}) {
            NovaTextures.ring(g, cx, cy, Math.round(radius * f), NovaGui.alpha(0x16FFFFFF, opacity));
        }
        // Lichtreflex (steht still, wie bei einer echten Platte)
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(-0.6F);
        int inner = Math.round(radius * 0.46F);
        for (int k = -2; k <= 2; k++) {
            int c = NovaGui.alpha(0xFFFFFFFF, (0.14F - Math.abs(k) * 0.045F) * opacity);
            g.fill(k, -radius + 2, k + 1, -inner, c);
            g.fill(k, inner, k + 1, radius - 2, c);
        }
        g.pose().popMatrix();
        // Label dreht sich mit
        int label = Math.max(3, Math.round(radius * 0.38F));
        NovaTextures.circle(g, cx, cy, label, NovaGui.alpha(labelColor, opacity));
        NovaTextures.ring(g, cx, cy, label, NovaGui.alpha(0x40000000, opacity));
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(vinylAngle);
        int mark = Math.max(1, label / 3);
        g.fill(-1, -label + 1, 1, -label + 1 + mark, NovaGui.alpha(0xCCFFFFFF, opacity));
        g.fill(-label + 2, -1, -label + 2 + mark, 0, NovaGui.alpha(0x66000000, opacity));
        g.pose().popMatrix();
        NovaTextures.circle(g, cx, cy, Math.max(1, Math.round(radius * 0.06F)), NovaGui.alpha(0xFF0E0E12, opacity));
    }

    /** Animierte Equalizer-Balken; unten bei yBottom. Pausiert: flach. */
    public static void equalizer(GuiGraphicsExtractor g, int x, int yBottom, int bars, int barWidth, int gap, int maxHeight,
                                 int colorBottom, int colorTop) {
        float t = NovaGui.seconds();
        float p = playAnim;
        for (int i = 0; i < bars; i++) {
            float a = Math.abs((float) Math.sin(t * (4.3F + i * 1.1F) + i * 2.0F) * 0.6F
                    + (float) Math.sin(t * (7.1F + i * 0.7F) + i) * 0.4F);
            float level = 0.18F + 0.82F * Math.min(1.0F, a) * p;
            int h = Math.max(2, Math.round(maxHeight * level));
            int bx = x + i * (barWidth + gap);
            int color = NovaGui.mix(colorBottom, colorTop, h / (float) maxHeight);
            g.fillGradient(bx, yBottom - h, bx + barWidth, yBottom, color, colorBottom);
        }
    }

    /** Text, der bei Ueberlaenge hin- und herlaeuft (mit Pause am Anfang). */
    public static void marquee(GuiGraphicsExtractor g, Font font, String text, int x0, int y, int maxWidth, int color) {
        int width = NovaGui.width(font, text);
        if (width <= maxWidth) {
            NovaGui.text(g, font, text, x0, y, color);
            return;
        }
        int gap = 28;
        int cycle = width + gap;
        float travel = NovaGui.seconds() * 24.0F % (cycle + 40.0F);
        int offset = Math.round(Math.max(0.0F, travel - 40.0F));
        g.enableScissor(x0, y - 1, x0 + maxWidth, y + 10);
        NovaGui.text(g, font, text, x0 - offset, y, color);
        NovaGui.text(g, font, text, x0 - offset + cycle, y, color);
        g.disableScissor();
    }

    /** Fortschrittsbalken: Verlauf Albumfarbe -> Akzentfarbe, leuchtender Knopf am Ende. */
    public static void progressBar(GuiGraphicsExtractor g, int x0, int y, int x1, int height, float progress, int accent,
                                   float emphasis) {
        int h = height + Math.round(emphasis);
        int top = y - Math.round(emphasis / 2.0F);
        NovaShapes.pill(g, x0, top, x1, top + h, NovaGui.alpha(0xFFFFFFFF, 0.13F));
        int fill = x0 + Math.round((x1 - x0) * Math.max(0.0F, Math.min(1.0F, progress)));
        if (fill - x0 >= 2) {
            NovaShapes.pill(g, x0, top, fill, top + h, 0xFF000000 | accent);
        }
        if (emphasis > 0.01F || progress > 0.0F) {
            int r = Math.max(2, h / 2 + 2);
            NovaTextures.circle(g, fill, top + h / 2, Math.round(r * (0.6F + 0.4F * emphasis)), 0xFFFFFFFF);
        }
    }

    /** Spotify-Logo: gruener Kreis mit drei Schallboegen. */
    public static void logo(GuiGraphicsExtractor g, int cx, int cy, int radius) {
        NovaTextures.circle(g, cx, cy, radius, GREEN);
        float r = radius;
        for (int i = 0; i < 3; i++) {
            float half = r * (0.66F - i * 0.13F);
            float baseY = cy - r * 0.34F + i * r * 0.33F;
            int thickness = Math.max(1, Math.round(r * (0.16F - i * 0.02F)));
            for (int dx = Math.round(-half); dx <= Math.round(half); dx++) {
                float t = dx / half;
                int yy = Math.round(baseY + t * t * r * 0.14F - (1.0F - t * t) * r * 0.05F);
                g.fill(cx + dx, yy, cx + dx + 1, yy + thickness, 0xFF0B0B0B);
            }
        }
    }

    /** Hintergrund der Karten: Fenster wie alle Nova-Fenster, ruhig in den Farben des Covers getoent, feiner Rand. */
    static void cardBackground(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int accent, float glow) {
        NovaGui.shadow(g, x0, y0, x1, y1, radius);
        NovaShapes.rect(g, x0, y0, x1, y1, radius, NovaGui.PANEL);
        if (SpotifyArt.hasCover()) {
            SpotifyArt.drawAmbient(g, x0, y0, x1, y1, radius);
            NovaShapes.rect(g, x0, y0, x1, y1, radius, NovaGui.alpha(NovaGui.PANEL, 0.86F));
        }
        NovaShapes.outline(g, x0, y0, x1, y1, radius, NovaGui.mix(NovaGui.BORDER, NovaGui.alpha(accent, 0.5F), 0.3F));
    }

    /** Karte fuers HUD: derselbe schlichte Hintergrund wie alle anderen HUD-Anzeigen. */
    static void glassBackground(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int accent, float glow) {
        NovaShapes.rect(g, x0, y0, x1, y1, NovaGui.HUD_RADIUS, NovaGui.HUD_BACKGROUND);
    }

    /** Cover + herausgleitende Schallplatte. Gibt zurueck, wie weit die Platte rechts uebersteht. */
    static int coverWithVinyl(GuiGraphicsExtractor g, int x, int y, int size, int radius, int accent) {
        int slide = Math.round(size * 0.30F * playAnim);
        int vinylRadius = size / 2 - 2;
        vinyl(g, x + size / 2 + slide, y + size / 2, vinylRadius, accent, 0.35F + 0.65F * Math.max(playAnim, 0.25F));
        NovaGui.shadow(g, x, y, x + size, y + size, radius);
        SpotifyArt.drawCover(g, x, y, size, radius);
        return slide;
    }

    /** Status, wenn nichts angezeigt werden kann (sonst null). */
    static String[] statusLines() {
        if (!Spotify.SUPPORTED) {
            return new String[]{"Spotify", "Windows only"};
        }
        if (!Spotify.connected) {
            return new String[]{"Spotify", "Connecting..."};
        }
        if (!Spotify.running) {
            return new String[]{"Spotify", "Open the Spotify app"};
        }
        if (Spotify.title.isEmpty()) {
            return new String[]{"Spotify", Spotify.playing ? "Playing" : "Nothing playing"};
        }
        return null;
    }

    // ================================================================ HUD

    private static final int HUD_W = 200;
    private static final int HUD_H = 58;
    private static final int HUD_COVER = 44;
    /** Lyrics unter der Karte: Zeilenhoehe, Abstand zur Karte, Innenabstand des Panels. */
    private static final int LYRIC_LINE = 11;
    private static final int LYRIC_GAP = 4;
    private static final int LYRIC_PAD = 8;

    // Lyrics: beim Wechsel gleitet alles eine Zeile nach oben
    private static int lyricIndex = Integer.MIN_VALUE;
    private static long lyricChangedAt;
    /** Hoehe des Panels, weich angepasst (kein Springen bei 1 <-> 2 Zeilen). */
    private static float lyricPanelH;
    /** Gesamthoehe der HUD-Karte im letzten Bild (fuer die HUD-Anordnung). */
    private static int lastHeight;
    /** Schon umbrochene Zeilen (Text + Breite), damit nicht jeden Frame neu gemessen wird. */
    private static final Map<String, List<String>> WRAPPED = new HashMap<>();

    public static void drawHud(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        frame();
        Font font = client.font;
        int width = client.getWindow().getGuiScaledWidth();
        float scale = SpotifyMod.scale();

        // In Kartengroesse zeichnen und dann auf die eingestellte Groesse skalieren
        String[] status = statusLines();
        // Ohne laufenden Song keine Karte im Spiel (nervt sonst) - nur beim Verschieben im HUD-Editor
        if (status != null && !SpotifyMod.showWhenIdle && !(client.gui.screen() instanceof HudEditorScreen)) {
            SpotifyMod.bounds = null;
            return;
        }
        SpotifyLyrics.View lyrics = null;
        String lyricStatus = null;
        if (status == null && SpotifyMod.lyrics) {
            SpotifyLyrics.update();
            lyrics = SpotifyLyrics.view(SpotifyMod.lyricLines);
            lyricStatus = lyrics == null ? SpotifyLyrics.status() : null;
        }
        int cardW = status != null ? Math.max(120, NovaGui.width(font, status[1]) + 40) : HUD_W;
        int cardH = status != null ? 28 : HUD_H;
        int height = client.getWindow().getGuiScaledHeight();
        float w = cardW * scale;
        float h = cardH * scale;
        float left;
        float y;
        if (SpotifyMod.posX >= 0.0F) {
            // selbst verschoben (Anteil vom Bildschirm, damit es bei jeder Fenstergroesse passt)
            left = Math.max(0.0F, Math.min(width - w, SpotifyMod.posX * width));
            y = Math.max(0.0F, Math.min(height - h, SpotifyMod.posY * height));
        } else {
            // Standard: rechte HUD-Spalte (Hoehe inkl. Lyrics vom letzten Bild)
            left = width - 6 - w;
            y = HudLayout.top(HudLayout.Slot.SPOTIFY, Math.max(Math.round(h), lastHeight));
        }
        g.pose().pushMatrix();
        g.pose().translate(left, y);
        g.pose().scale(scale, scale);
        int lyricsH = 0;
        if (status != null) {
            glassBackground(g, 0, 0, cardW, 28, 7, GREEN, 0.25F);
            logo(g, 14, 14, 8);
            NovaGui.text(g, font, status[0], 28, 5, NovaGui.TEXT);
            NovaGui.text(g, font, status[1], 28, 15, NovaGui.TEXT_MUTED);
        } else {
            drawNowPlaying(g, font);
            if (lyrics != null || lyricStatus != null) {
                lyricsH = LYRIC_GAP + drawLyrics(g, font, lyrics, lyricStatus, HUD_H + LYRIC_GAP);
            } else {
                lyricPanelH = 0.0F;
            }
        }
        g.pose().popMatrix();
        SpotifyMod.bounds = new Rect(Math.round(left), Math.round(y), Math.round(left + w), Math.round(y + h + lyricsH * scale));
        lastHeight = Math.round(h + lyricsH * scale);
    }

    private static void drawNowPlaying(GuiGraphicsExtractor g, Font font) {
        int accent = SpotifyArt.accent();
        int x0 = 0;
        int y0 = 0;
        int x1 = x0 + HUD_W;
        int y1 = y0 + HUD_H;
        float beat = playAnim * (0.5F + 0.5F * (float) Math.sin(NovaGui.seconds() * 7.0F));
        glassBackground(g, x0, y0, x1, y1, 8, accent, 0.30F + 0.25F * beat);

        int coverX = x0 + 7;
        int coverY = y0 + 7;
        coverWithVinyl(g, coverX, coverY, HUD_COVER, 6, accent);

        int textX = coverX + HUD_COVER + Math.round(HUD_COVER * 0.30F) + 8;
        int eqW = 4 * 3 + 3 * 2;
        int textRight = x1 - 8;

        // Titel + Equalizer
        marquee(g, font, Spotify.title, textX, y0 + 8, textRight - textX - eqW - 6, NovaGui.TEXT);
        equalizer(g, textRight - eqW, y0 + 16, 4, 3, 2, 9, accent, NovaGui.BRAND_2);
        // Kuenstler
        NovaGui.text(g, font, NovaGui.ellipsize(font, Spotify.artist, textRight - textX), textX, y0 + 19, NovaGui.TEXT_DIM);

        if (Spotify.hasTimeline()) {
            progressBar(g, textX, y0 + 35, textRight, 3, Spotify.progress(), accent, 0.0F);
            String left = Spotify.time(Spotify.position());
            String right = Spotify.time(Spotify.duration());
            NovaGui.text(g, font, left, textX, y0 + 42, NovaGui.TEXT_MUTED);
            NovaGui.text(g, font, right, textRight - NovaGui.width(font, right), y0 + 42, NovaGui.TEXT_MUTED);
            String state = Spotify.playing ? "" : "PAUSED";
            if (!state.isEmpty()) {
                NovaGui.centered(g, font, state, (textX + textRight) / 2, y0 + 42, NovaGui.alpha(accent, 0.9F));
            }
        } else {
            String line = Spotify.album.isEmpty() ? (Spotify.playing ? "Playing" : "Paused") : Spotify.album;
            NovaGui.text(g, font, NovaGui.ellipsize(font, line, textRight - textX), textX, y0 + 32, NovaGui.TEXT_MUTED);
        }
    }

    /**
     * Lyrics in einem eigenen ruhigen Panel unter der Karte: die aktuelle Zeile hell (mit Karaoke fuellt sie sich beim
     * Singen von links), darunter die naechsten Zeilen gedaempft. Kommt eine neue Zeile, gleitet alles weich nach oben.
     * Pausen zeigen drei Punkte, die nacheinander aufleuchten. Gibt die Hoehe des Panels zurueck.
     */
    private static int drawLyrics(GuiGraphicsExtractor g, Font font, SpotifyLyrics.View view, String status, int y) {
        long now = Util.getMillis();
        int width = HUD_W - LYRIC_PAD * 2;
        int accent = SpotifyArt.accent();
        List<List<String>> rows = new ArrayList<>();
        if (view != null) {
            for (String line : view.lines()) {
                rows.add(line.isBlank() ? List.of() : wrap(font, line, width));
            }
            if (view.index() != lyricIndex) {
                lyricIndex = view.index();
                lyricChangedAt = now;
            }
        }
        int contentH = status != null ? LYRIC_LINE : 0;
        for (List<String> row : rows) {
            contentH += Math.max(1, row.size()) * LYRIC_LINE;
        }
        float targetH = contentH + LYRIC_PAD * 2 - 2;
        lyricPanelH = lyricPanelH <= 0.0F ? targetH : lyricPanelH + (targetH - lyricPanelH) * Math.min(1.0F, lastDt * 12.0F);
        int panelH = Math.round(lyricPanelH);
        glassBackground(g, 0, y, HUD_W, y + panelH, 8, accent, 0.12F);

        int x = LYRIC_PAD;
        int top = y + LYRIC_PAD - 1;
        g.enableScissor(0, y + 2, HUD_W, y + panelH - 2);
        if (status != null) {
            NovaGui.text(g, font, status, x, top, NovaGui.alpha(NovaGui.TEXT_MUTED, 0.8F + 0.2F * (float) Math.sin(NovaGui.seconds() * 3.0F)));
        } else if (view != null) {
            float t = NovaGui.easeOutCubic((now - lyricChangedAt) / 360.0F);
            float line = top + (1.0F - t) * LYRIC_LINE;
            for (int i = 0; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                if (i == 0) {
                    if (row.isEmpty()) {
                        pauseDots(g, x, Math.round(line) + 4, view.progress(), accent);
                        line += LYRIC_LINE;
                    } else {
                        line = currentLine(g, font, row, x, line, view.progress(), t, accent);
                    }
                } else {
                    int grey = NovaGui.alpha(0xFF8E8E96, (i == 1 ? 0.9F : 0.55F) * t);
                    for (String part : row) {
                        NovaGui.text(g, font, part, x, Math.round(line), grey, false);
                        line += LYRIC_LINE;
                    }
                }
            }
        }
        g.disableScissor();
        return panelH;
    }

    /** Aktuelle Zeile: gedaempft, darueber hell bis zum Karaoke-Fortschritt (auch ueber umbrochene Zeilen). */
    private static float currentLine(GuiGraphicsExtractor g, Font font, List<String> row, int x, float line, float progress,
                                     float appear, int accent) {
        int dim = NovaGui.alpha(0xFFB9B9C0, 0.55F + 0.45F * appear);
        int bright = NovaGui.alpha(0xFFFFFFFF, appear);
        if (!SpotifyMod.karaoke) {
            for (String part : row) {
                NovaGui.text(g, font, part, x, Math.round(line), bright, false);
                line += LYRIC_LINE;
            }
            return line;
        }
        int total = 0;
        for (String part : row) {
            total += NovaGui.width(font, part);
        }
        float filled = total * progress;
        for (String part : row) {
            int w = NovaGui.width(font, part);
            int y = Math.round(line);
            int fill = Math.round(Math.max(0.0F, Math.min(w, filled)));
            NovaGui.text(g, font, part, x, y, dim, false);
            if (fill > 0) {
                g.enableScissor(x, y - 1, x + fill, y + LYRIC_LINE);
                NovaGui.text(g, font, part, x, y, bright, false);
                g.disableScissor();
                // schmaler Schimmer an der Kante
                if (fill < w) {
                    g.fill(x + fill - 1, y, x + fill, y + 8, NovaGui.alpha(accent, 0.55F * appear));
                }
            }
            filled -= w;
            line += LYRIC_LINE;
        }
        return line;
    }

    /** Pause/Instrumental: drei Punkte, die mit dem Fortschritt nacheinander aufleuchten. */
    private static void pauseDots(GuiGraphicsExtractor g, int x, int cy, float progress, int accent) {
        float breath = 0.5F - 0.5F * (float) Math.cos(NovaGui.seconds() * 3.5F);
        for (int i = 0; i < 3; i++) {
            float lit = Math.max(0.0F, Math.min(1.0F, progress * 3.0F - i));
            int color = NovaGui.mix(0xFF5A5A62, NovaGui.mix(0xFFFFFFFF, accent, 0.25F), lit);
            float radius = 1.6F + 0.5F * lit + 0.2F * breath * lit;
            NovaShapes.disc(g, x + 2 + i * 7, cy, radius, color);
        }
    }

    /** Text an Wortgrenzen auf width umbrechen (zu lange Woerter werden gekuerzt). */
    private static List<String> wrap(Font font, String text, int width) {
        String key = width + "|" + NovaGui.menuFont() + "|" + text;
        List<String> cached = WRAPPED.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (NovaGui.width(font, candidate) <= width || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, NovaGui.ellipsize(font, lines.get(i), width));
        }
        if (WRAPPED.size() > 64) {
            WRAPPED.clear();
        }
        WRAPPED.put(key, lines);
        return lines;
    }


    // ================================================================ Knoepfe (Spotify-Fenster)

    /** Knopf-Aktion: 0 Shuffle, 1 Zurueck, 2 Play, 3 Weiter, 4 Repeat. */
    static void press(int index) {
        switch (index) {
            case 0 -> Spotify.toggleShuffle();
            case 1 -> Spotify.previous();
            case 2 -> Spotify.playPause();
            case 3 -> Spotify.next();
            default -> Spotify.cycleRepeat();
        }
    }
}
