package nova.nova_client.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.IdentityHashMap;
import java.util.Map;

import static nova.nova_client.client.NovaGui.*;

/**
 * Die gemeinsamen Bedienelemente aller Nova-Fenster: Schalter, Regler (Wert in Mono), Segmente, Auswahl, Knoepfe,
 * Tasten-Feld, Textfeld-Rahmen, Farbkreise und Farbregler, Listenzeilen, Sektionen und Scrollbar. Alle zeichnen nur -
 * Layout und Eingabe bleiben beim Fenster, die Masse stehen hier (4-px-Raster), damit jedes Fenster gleich aussieht.
 * Zustaende sind ueberall gleich: Hover = eine Stufe heller, aktiv/gewaehlt = Akzent, deaktiviert = blass, Fokus =
 * Akzentring, "hoert auf Taste" = Akzentrand.
 */
public final class NovaControls {
    /** Zeilen-Hover ragt so weit links und rechts ueber den Inhalt hinaus. */
    public static final int ROW_PAD = 6;
    /** Hoehe eines Reglers mit Beschriftung darueber. */
    public static final int SLIDER_STACK_H = 28;
    /** Breite der Wert-Spalte eines Reglers in einer Zeile. */
    public static final int VALUE_W = 48;
    /** Farbkreis: Durchmesser und Abstand. */
    public static final int SWATCH = 12;
    public static final int SWATCH_GAP = 6;
    /** Hoehe einer Sektions-Ueberschrift inkl. Abstand darunter. */
    public static final int SECTION_H = 16;
    /** Hoehe einer Listenzeile. */
    public static final int LIST_ROW_H = 22;

    public enum Variant {
        /** Normaler Knopf (Karte mit Haarlinie). */
        SECONDARY,
        /** Hauptaktion in der Akzentfarbe. */
        PRIMARY,
        /** Nur Text, Flaeche erst beim Drueberfahren. */
        GHOST,
        /** Loeschen und Co. */
        DANGER
    }

    private NovaControls() {
    }

    // ---------------------------------------------------------------- Geometrie

    /** Liegt die Maus auf der Zeile (inkl. Hover-Rand)? */
    public static boolean rowHit(double mx, double my, int x0, int y, int x1, int height) {
        return mx >= x0 - ROW_PAD && mx < x1 + ROW_PAD && my >= y && my < y + height;
    }

    public static boolean hit(double mx, double my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    /** Leichte Flaeche hinter einer Zeile (Hover oder Fokus). */
    public static void rowHover(GuiGraphicsExtractor g, int x0, int y, int x1, int height, float amount) {
        if (amount > 0.01F) {
            NovaShapes.rect(g, x0 - ROW_PAD, y, x1 + ROW_PAD, y + height, RADIUS, alpha(HOVER, amount));
        }
    }

    // ---------------------------------------------------------------- Schalter

    /** Schalter 22x12 bei x/y; progress 0 = aus, 1 = an. Zwei Zeichen-Aufrufe aus einer Textur. */
    public static void toggle(GuiGraphicsExtractor g, int x, int y, float progress, boolean hover, float opacity, int accent) {
        float p = Math.max(0.0F, Math.min(1.0F, progress));
        int off = hover ? 0xFF353A44 : SWITCH_OFF;
        NovaShapes.switchTrack(g, x, y, alpha(mix(off, 0xFF000000 | accent, p), opacity));
        float knobX = x + 6.0F + 10.0F * p;
        NovaShapes.switchKnob(g, knobX, y + 6.0F, alpha(mix(0xFFBFC4CC, 0xFFFFFFFF, p), opacity));
    }

    /**
     * Zeile: Beschriftung links, Schalter rechts (Inhalt x0..x1, Hoehe ROW_H); swatch != 0 = kleines Farbfeld vor der
     * Beschriftung (z.B. die Farbe eines ESP-Ziels).
     */
    public static void toggleRow(GuiGraphicsExtractor g, Font font, int x0, int y, int x1, String label, int swatch,
                                 float progress, float hover, boolean enabled, int accent) {
        rowHover(g, x0, y, x1, ROW_H, enabled ? hover : 0.0F);
        int color = !enabled ? alpha(TEXT_MUTED, 0.6F) : mix(mix(TEXT_DIM, TEXT, progress), TEXT, hover);
        int textX = x0;
        if (swatch != 0) {
            NovaShapes.rect(g, x0, y + ROW_H / 2 - 4, x0 + 8, y + ROW_H / 2 + 4, 2, 0xFF000000 | swatch);
            textX += 14;
        }
        int room = x1 - textX - NovaShapes.SWITCH_W - 8;
        NovaType.draw(g, font, NovaType.Face.BODY, NovaType.ellipsize(font, NovaType.Face.BODY, label, room), textX,
                NovaType.centerY(NovaType.Face.BODY, y, ROW_H), color);
        toggle(g, x1 - NovaShapes.SWITCH_W, y + (ROW_H - NovaShapes.SWITCH_H) / 2, progress, hover > 0.5F,
                enabled ? 1.0F : 0.45F, accent);
    }

    // ---------------------------------------------------------------- Regler

    /** Wert 0..1 aus der Mausposition ueber der Spur x0..x1. */
    public static double sliderValue(double mouseX, int x0, int x1) {
        return Math.max(0.0D, Math.min(1.0D, (mouseX - x0) / Math.max(1.0D, x1 - x0)));
    }

    /** Spur x0..x1 mittig bei cy; t 0..1. */
    public static void sliderTrack(GuiGraphicsExtractor g, int x0, int x1, int cy, float t, boolean active, int accent) {
        int kx = x0 + Math.round((x1 - x0) * Math.max(0.0F, Math.min(1.0F, t)));
        NovaShapes.rect(g, x0, cy - 1, x1, cy + 1, 1, TRACK);
        if (kx > x0) {
            NovaShapes.rect(g, x0, cy - 1, kx, cy + 1, 1, 0xFF000000 | accent);
        }
        knob(g, kx, cy, active, accent);
    }

    /** Runder Regler-Knopf; aktiv mit feinem Akzentring. */
    public static void knob(GuiGraphicsExtractor g, float cx, float cy, boolean active, int accent) {
        if (active) {
            NovaShapes.ring(g, cx, cy, 7.5F, 2.0F, alpha(accent, 0.3F));
        }
        NovaShapes.sliderKnob(g, cx, cy, active ? 0xFFFFFFFF : 0xFFE6E8EC);
    }

    /** Regler in einer Zeile: Beschriftung links, Spur ab trackX0, Wert (Mono) rechtsbuendig an x1. */
    public static void sliderRow(GuiGraphicsExtractor g, Font font, int x0, int y, int x1, int trackX0, String label,
                                 float t, String value, boolean active, int accent) {
        int labelColor = active ? TEXT : TEXT_DIM;
        NovaType.draw(g, font, NovaType.Face.BODY, NovaType.ellipsize(font, NovaType.Face.BODY, label, trackX0 - x0 - 10), x0,
                NovaType.centerY(NovaType.Face.BODY, y, ROW_H), labelColor);
        value(g, font, value, x1, y, ROW_H, active);
        sliderTrack(g, trackX0, x1 - VALUE_W - 8, y + ROW_H / 2, t, active, accent);
    }

    /** Regler mit Beschriftung und Wert darueber (Hoehe SLIDER_STACK_H), Spur ueber die ganze Breite. */
    public static void sliderStacked(GuiGraphicsExtractor g, Font font, int x0, int y, int x1, String label, float t,
                                     String value, boolean active, int accent) {
        int valueW = NovaType.width(font, NovaType.Face.MONO, value);
        NovaType.draw(g, font, NovaType.Face.BODY, NovaType.ellipsize(font, NovaType.Face.BODY, label, x1 - x0 - valueW - 8),
                x0, NovaType.centerY(NovaType.Face.BODY, y, 12), active ? TEXT : TEXT_DIM);
        NovaType.draw(g, font, NovaType.Face.MONO, value, x1 - valueW, NovaType.centerY(NovaType.Face.MONO, y, 12),
                active ? TEXT : TEXT_DIM);
        sliderTrack(g, x0 + 5, x1 - 5, y + 20, t, active, accent);
    }

    /** Spur-Bereich eines gestapelten Reglers (fuer Treffer und Wert aus der Maus). */
    public static int stackedTrackX0(int x0) {
        return x0 + 5;
    }

    public static int stackedTrackX1(int x1) {
        return x1 - 5;
    }

    /** Wert in Mono, rechtsbuendig an x1 und mittig in der Zeile. */
    public static void value(GuiGraphicsExtractor g, Font font, String value, int x1, int y, int height, boolean active) {
        NovaType.right(g, font, NovaType.Face.MONO, value, x1, NovaType.centerY(NovaType.Face.MONO, y, height),
                active ? TEXT : TEXT_DIM);
    }

    /** Eingabefeld fuer eine getippte Zahl (statt des Werts), rechtsbuendig an x1. */
    public static void valueEditor(GuiGraphicsExtractor g, Font font, String typed, int x1, int y, int height, int accent) {
        String shown = typed + (blink() ? "|" : " ");
        int w = Math.max(40, NovaType.width(font, NovaType.Face.MONO, shown) + 12);
        int y0 = y + (height - CONTROL_H) / 2;
        field(g, x1 - w, y0, x1, y0 + CONTROL_H, true, false, accent);
        NovaType.draw(g, font, NovaType.Face.MONO, shown, x1 - w + 6, NovaType.centerY(NovaType.Face.MONO, y0, CONTROL_H), TEXT);
    }

    /** Stuecke einer frei berechneten Farbspur (statt einer Flaeche je Pixel). */
    public static final int TRACK_PIECES = 16;

    /**
     * Stueck i (von TRACK_PIECES) einer Farbspur x0..x1: Grundfarbe from, weich nach to verlaufend. Der Aufrufer
     * rechnet from/to an den Stueckgrenzen aus - so kostet eine ganze Spur nur 2 x TRACK_PIECES Zeichen-Aufrufe.
     */
    public static void trackPiece(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int i, int from, int to) {
        int a = x0 + (x1 - x0) * i / TRACK_PIECES;
        int b = x0 + (x1 - x0) * (i + 1) / TRACK_PIECES;
        if (b > a) {
            g.fill(a, y0, b, y1, from);
            NovaShapes.fade(g, a, y0, b, y1, NovaShapes.FADE_RIGHT, to);
        }
    }

    /** Farbspur (Farbton = Regenbogen): Spur x0..x1 (4 hoch) mittig bei cy, Knopf bei t. */
    public static void hueTrack(GuiGraphicsExtractor g, int x0, int x1, int cy, float t, boolean active, int accent) {
        NovaShapes.hue(g, x0, cy - 2, x1, cy + 2);
        knob(g, x0 + Math.round((x1 - x0) * Math.max(0.0F, Math.min(1.0F, t))), cy, active, accent);
    }

    /** Farbspur von from nach to (z.B. Saettigung, Helligkeit). */
    public static void gradientTrack(GuiGraphicsExtractor g, int x0, int x1, int cy, int from, int to, float t, boolean active,
                                     int accent) {
        g.fill(x0, cy - 2, x1, cy + 2, 0xFF000000 | from);
        NovaShapes.fade(g, x0, cy - 2, x1, cy + 2, NovaShapes.FADE_RIGHT, 0xFF000000 | to);
        knob(g, x0 + Math.round((x1 - x0) * Math.max(0.0F, Math.min(1.0F, t))), cy, active, accent);
    }

    // ---------------------------------------------------------------- Knoepfe und Auswahl

    public static void button(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String label, Variant variant,
                              boolean hover, boolean enabled) {
        boolean lit = hover && enabled;
        float o = enabled ? 1.0F : 0.45F;
        int fill;
        int border;
        int text;
        switch (variant) {
            case PRIMARY -> {
                fill = lit ? mix(BRAND, 0xFFFFFFFF, 0.1F) : BRAND;
                border = 0;
                text = ON_ACCENT;
            }
            case GHOST -> {
                fill = lit ? HOVER : 0;
                border = 0;
                text = lit ? TEXT : TEXT_DIM;
            }
            case DANGER -> {
                fill = mix(CARD, DANGER, lit ? 0.22F : 0.13F);
                border = alpha(DANGER, lit ? 0.6F : 0.35F);
                text = mix(DANGER, 0xFFFFFFFF, 0.3F);
            }
            default -> {
                fill = lit ? RAISED : CARD;
                border = lit ? BORDER_STRONG : BORDER;
                text = lit ? TEXT : TEXT_DIM;
            }
        }
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, alpha(fill, o));
        if (border != 0) {
            NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, alpha(border, o));
        }
        label(g, font, x0, y0, x1, y1, label, alpha(text, o));
    }

    /** Auswahl-Knopf (z.B. Voreinstellung oder Modus); gewaehlt = Akzent. */
    public static void choice(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String label, boolean hover,
                              boolean selected, int accent) {
        int color = 0xFF000000 | accent;
        int fill = selected ? mix(CARD, color, hover ? 0.26F : 0.2F) : hover ? RAISED : CARD;
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, fill);
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, selected ? alpha(color, 0.55F) : hover ? BORDER_STRONG : BORDER);
        label(g, font, x0, y0, x1, y1, label, selected || hover ? TEXT : TEXT_DIM);
    }

    private static void label(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String label, int color) {
        // Schmale Knoepfe (z.B. "+" / "-" oder kurze Auswahl) brauchen kaum Rand
        int w = x1 - x0;
        String shown = NovaType.ellipsize(font, NovaType.Face.BODY, label, w >= 48 ? w - 10 : w - 4);
        NovaType.centered(g, font, NovaType.Face.BODY, shown, (x0 + x1) / 2, NovaType.centerY(NovaType.Face.BODY, y0, y1 - y0),
                color);
    }

    /** Segment-Leiste: gleich breite Segmente, selected gewaehlt, hover = Segment unter der Maus (-1 = keins). */
    public static void segmented(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String[] labels, int selected,
                                 int hover, int accent) {
        int n = Math.max(1, labels.length);
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, INSET);
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, BORDER);
        int color = 0xFF000000 | accent;
        for (int i = 0; i < labels.length; i++) {
            int sx0 = segmentX(x0, x1, n, i) + 2;
            int sx1 = segmentX(x0, x1, n, i + 1) - 2;
            if (i == selected) {
                NovaShapes.rect(g, sx0, y0 + 2, sx1, y1 - 2, RADIUS_SMALL, mix(CARD, color, 0.22F));
                NovaShapes.outline(g, sx0, y0 + 2, sx1, y1 - 2, RADIUS_SMALL, alpha(color, 0.5F));
            } else if (i == hover) {
                NovaShapes.rect(g, sx0, y0 + 2, sx1, y1 - 2, RADIUS_SMALL, HOVER);
            }
            String shown = NovaType.ellipsize(font, NovaType.Face.BODY, labels[i], sx1 - sx0 - 6);
            NovaType.centered(g, font, NovaType.Face.BODY, shown, (sx0 + sx1) / 2, NovaType.centerY(NovaType.Face.BODY, y0, y1 - y0),
                    i == selected || i == hover ? TEXT : TEXT_DIM);
        }
    }

    private static int segmentX(int x0, int x1, int n, int i) {
        return x0 + (x1 - x0) * i / n;
    }

    /** Segment unter der Maus (-1 = keins). */
    public static int segmentAt(double mx, double my, int x0, int y0, int x1, int y1, int count) {
        if (count <= 0 || !hit(mx, my, x0, y0, x1, y1)) {
            return -1;
        }
        return Math.max(0, Math.min(count - 1, (int) ((mx - x0) * count / Math.max(1, x1 - x0))));
    }

    /** Auswahlfeld: Wert in der Mitte, kleine Pfeile links und rechts (Klick = naechster, Rechtsklick = vorheriger). */
    public static void select(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String value, boolean hover) {
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, hover ? RAISED : CARD);
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, hover ? BORDER_STRONG : BORDER);
        int textY = NovaType.centerY(NovaType.Face.BODY, y0, y1 - y0);
        int arrow = hover ? TEXT_DIM : TEXT_MUTED;
        NovaType.draw(g, font, NovaType.Face.BODY, "‹", x0 + 7, textY, arrow);
        NovaType.right(g, font, NovaType.Face.BODY, "›", x1 - 7, textY, arrow);
        String shown = NovaType.ellipsize(font, NovaType.Face.BODY, value, x1 - x0 - 30);
        NovaType.centered(g, font, NovaType.Face.BODY, shown, (x0 + x1) / 2, textY, TEXT);
    }

    // ---------------------------------------------------------------- Tasten und Felder

    /** Name einer Taste fuer ein Tasten-Feld. */
    public static String keyName(int key) {
        return key == nova.nova_client.client.mods.Mod.NO_KEY ? "None"
                : com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
    }

    /** Tasten-Feld (Mono) rechtsbuendig an x1, Hoehe CONTROL_H ab y0; gibt die linke Kante zurueck. */
    public static int keybind(GuiGraphicsExtractor g, Font font, int x1, int y0, String key, boolean listening, boolean none,
                              boolean hover, int accent) {
        String text = listening ? "Press a key…" : key;
        int w = Math.max(48, NovaType.width(font, NovaType.Face.MONO, text) + 16);
        int x0 = x1 - w;
        int y1 = y0 + CONTROL_H;
        int color = 0xFF000000 | accent;
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, listening ? mix(INSET, color, 0.16F) : hover ? CARD : INSET);
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, listening ? alpha(color, blink() ? 0.8F : 0.5F)
                : hover ? BORDER_STRONG : BORDER);
        NovaType.centered(g, font, NovaType.Face.MONO, text, (x0 + x1) / 2, NovaType.centerY(NovaType.Face.MONO, y0, CONTROL_H),
                listening ? TEXT : none ? TEXT_MUTED : TEXT_DIM);
        return x0;
    }

    /** Kleiner Tasten-Chip (Mono), rechtsbuendig an x1, mittig in einer Zeile y..y+height; gibt die linke Kante zurueck. */
    public static int keyChip(GuiGraphicsExtractor g, Font font, String key, int x1, int y, int height, int color, int fill) {
        int w = NovaType.width(font, NovaType.Face.MONO, key) + 8;
        int x0 = x1 - w;
        int top = y + (height - 11) / 2;
        NovaShapes.rect(g, x0, top, x1, top + 11, RADIUS_SMALL, fill);
        NovaType.draw(g, font, NovaType.Face.MONO, key, x0 + 4, NovaType.centerY(NovaType.Face.MONO, top, 11), color);
        return x0;
    }

    /** Rahmen eines Eingabefelds. */
    public static void field(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, boolean focused, boolean hover, int accent) {
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, INSET);
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, focused ? alpha(accent, 0.7F) : hover ? BORDER_STRONG : BORDER);
    }

    /** Platzhalter in einem leeren Feld. */
    public static void placeholder(GuiGraphicsExtractor g, Font font, String text, int x, int y, int maxWidth) {
        NovaType.draw(g, font, NovaType.Face.BODY, NovaType.ellipsize(font, NovaType.Face.BODY, text, maxWidth), x, y, TEXT_MUTED);
    }

    // ---------------------------------------------------------------- Farben

    /** Mitte des Farbkreises c von count, rechtsbuendig an x1. */
    public static int swatchX(int count, int c, int x1) {
        return x1 - SWATCH / 2 - (count - 1 - c) * (SWATCH + SWATCH_GAP);
    }

    /** Farbkreise rechtsbuendig an x1, mittig bei cy; selected mit Ring. */
    public static void swatches(GuiGraphicsExtractor g, int[] colors, int x1, float cy, int selected, int hover) {
        for (int c = 0; c < colors.length; c++) {
            float cx = swatchX(colors.length, c, x1);
            swatch(g, cx, cy, colors[c], c == selected, c == hover);
        }
    }

    /** Ein Farbkreis; gewaehlt mit feinem Ring. */
    public static void swatch(GuiGraphicsExtractor g, float cx, float cy, int color, boolean selected, boolean hover) {
        NovaShapes.disc(g, cx, cy, hover || selected ? SWATCH / 2.0F : SWATCH / 2.0F - 1.0F, 0xFF000000 | color);
        if (selected) {
            NovaShapes.ring(g, cx, cy, SWATCH / 2.0F + 2.5F, 1.25F, TEXT);
        }
    }

    /** Farbkreis unter der Maus (-1 = keiner). */
    public static int swatchAt(double mx, double my, int count, int x1, float cy) {
        if (Math.abs(my - cy) > SWATCH / 2.0D + 2) {
            return -1;
        }
        for (int c = 0; c < count; c++) {
            if (Math.abs(mx - swatchX(count, c, x1)) <= SWATCH / 2.0D + 2) {
                return c;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Struktur

    /** Sektions-Ueberschrift: kleiner gedaempfter Titel, dahinter eine Haarlinie bis x1. */
    public static void section(GuiGraphicsExtractor g, Font font, int x0, int y, int x1, String title) {
        int textY = NovaType.centerY(NovaType.Face.CAPTION, y, 10);
        NovaType.draw(g, font, NovaType.Face.CAPTION, title, x0, textY, TEXT_MUTED);
        int lineX = x0 + NovaType.width(font, NovaType.Face.CAPTION, title) + 8;
        if (lineX < x1) {
            g.fill(lineX, y + 5, x1, y + 6, BORDER);
        }
    }

    /** Listenzeile: Titel links, Detail (Mono, gedaempft) rechts; gewaehlt = Flaeche + Akzentstrich links. */
    public static void listRow(GuiGraphicsExtractor g, Font font, int x0, int y0, int x1, int y1, String title, String detail,
                               boolean selected, float hover, int accent) {
        if (selected) {
            NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, RAISED);
            NovaShapes.rect(g, x0 + 3, y0 + 5, x0 + 5, y1 - 5, 1, 0xFF000000 | accent);
        } else if (hover > 0.01F) {
            NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, alpha(HOVER, hover));
        }
        int detailW = detail == null || detail.isEmpty() ? 0 : NovaType.width(font, NovaType.Face.MONO, detail) + 8;
        if (detailW > 0) {
            NovaType.right(g, font, NovaType.Face.MONO, detail, x1 - 8, NovaType.centerY(NovaType.Face.MONO, y0, y1 - y0), TEXT_MUTED);
        }
        String shown = NovaType.ellipsize(font, NovaType.Face.BODY, title, x1 - x0 - 18 - detailW);
        NovaType.draw(g, font, NovaType.Face.BODY, shown, x0 + 10, NovaType.centerY(NovaType.Face.BODY, y0, y1 - y0),
                selected ? TEXT : mix(TEXT_DIM, TEXT, hover));
    }

    /** Checkbox (size x size) bei x/y, angehakt in der Akzentfarbe. */
    public static void checkbox(GuiGraphicsExtractor g, int x, int y, int size, boolean checked, boolean hover, int accent) {
        int r = Math.max(2, Math.min(RADIUS_SMALL, size / 3));
        if (checked) {
            NovaShapes.rect(g, x, y, x + size, y + size, r, 0xFF000000 | accent);
            float s = size / 16.0F;
            int on = ON_ACCENT;
            NovaShapes.beam(g, x + 4.0F * s, y + 8.5F * s, x + 7.0F * s, y + 11.5F * s, 2.0F * s, on);
            NovaShapes.beam(g, x + 7.0F * s, y + 11.5F * s, x + 12.5F * s, y + 4.5F * s, 2.0F * s, on);
        } else {
            NovaShapes.rect(g, x, y, x + size, y + size, r, hover ? RAISED : INSET);
            NovaShapes.outline(g, x, y, x + size, y + size, r, hover ? BORDER_STRONG : BORDER);
        }
    }

    /** Kleines Abzeichen (Mono) ab x, mittig in y..y+height; gibt die rechte Kante zurueck. */
    public static int badge(GuiGraphicsExtractor g, Font font, int x, int y, int height, String text, int color) {
        int w = NovaType.width(font, NovaType.Face.MONO, text) + 8;
        int top = y + (height - 11) / 2;
        NovaShapes.rect(g, x, top, x + w, top + 11, RADIUS_SMALL, alpha(color, 0.16F));
        NovaType.draw(g, font, NovaType.Face.MONO, text, x + 4, NovaType.centerY(NovaType.Face.MONO, top, 11), color);
        return x + w;
    }

    /** Fokusring (Tastatur) um eine Flaeche. */
    public static void focusRing(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int accent) {
        NovaShapes.outline(g, x0 - 2, y0 - 2, x1 + 2, y1 + 2, radius + 2, alpha(accent, 0.6F));
    }

    /** Duenne Scrollbar an der rechten Kante x1 (nur bei Bedarf). */
    public static void scrollbar(GuiGraphicsExtractor g, int x1, int top, int bottom, float scroll, int max, boolean hover) {
        if (max <= 0) {
            return;
        }
        int view = bottom - top;
        int thumb = Math.max(16, view * view / (view + max));
        int ty = top + Math.round((view - thumb) * Math.max(0.0F, Math.min(1.0F, scroll / max)));
        NovaShapes.rect(g, x1 - 3, ty, x1, ty + thumb, 1, hover ? 0x55FFFFFF : 0x30FFFFFF);
    }

    // ---------------------------------------------------------------- Weiche Werte

    /**
     * Weiche Werte (0..1) je Schluessel - fuer Schalter, Hover und Co. Schluessel nach Identitaet (z.B. die Option
     * selbst oder ein Label-Text), angelegt wird nur beim ersten Mal.
     */
    public static final class Springs {
        private final Map<Object, float[]> values = new IdentityHashMap<>();

        /** Naechster Wert Richtung target (0 oder 1); start = Anfangswert beim ersten Aufruf. */
        public float get(Object key, boolean target, float dt) {
            return get(key, target ? 1.0F : 0.0F, SPEED, dt);
        }

        public float get(Object key, float target, float speed, float dt) {
            float[] v = values.get(key);
            if (v == null) {
                v = new float[]{target};
                values.put(key, v);
                return target;
            }
            v[0] = approach(v[0], target, speed, dt);
            return v[0];
        }
    }
}
