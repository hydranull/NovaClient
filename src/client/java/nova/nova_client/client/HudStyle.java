package nova.nova_client.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Frei einstellbares Aussehen fuer HUD-Elemente (HudInfo, Adminlist, ...): Hintergrund, Akzentleiste,
 * Farben, Schrift und Abstaende. Standard ist eine ruhige, dunkle Karte mit feinem Rand: Labels gedaempft in normaler
 * Schreibweise, Werte hell, ohne Textschatten. Jede HUD-Mod hat eine eigene Instanz, gespeichert unter "&lt;prefix&gt;style.".
 * Einstellbar ueber {@link HudStyleMenu}. Neue HUD-Mods sollten ihre Zeilen ebenfalls hierueber zeichnen.
 */
public final class HudStyle {
    public enum Bar {
        NONE("None"), TOP("Top"), LEFT("Left"), BOTTOM("Bottom");

        public final String label;

        Bar(String label) {
            this.label = label;
        }
    }

    public enum Layout {
        SPLIT("Split"), INLINE("Inline");

        public final String label;

        Layout(String label) {
            this.label = label;
        }
    }

    /** Kleines Symbol (8x8) links vor dem Label, z.B. Spielerkopf. intro = Einblend-Faktor (0-1). */
    @FunctionalInterface
    public interface Icon {
        void draw(GuiGraphicsExtractor g, int x, int y, float intro);
    }

    /**
     * Eine Zeile: Label + Wert, optional mit Symbol davor. Farbe 0 = Stilfarbe; sonst Status-/Rangfarbe
     * (nur wenn "Status colors" an).
     */
    public record Row(String label, String value, int labelColor, int valueColor, Icon icon) {
        public Row(String label, String value) {
            this(label, value, 0, 0, null);
        }

        public Row(String label, String value, int labelColor, int valueColor) {
            this(label, value, labelColor, valueColor, null);
        }
    }

    public static final int LINE_H = 8;
    private static final int SPLIT_GAP = 14;
    private static final int INLINE_GAP = 4;
    private static final int HEADER_GAP = 3;
    private static final int BAR_W = 2;
    /** Platz fuer ein Symbol vor dem Label (8 px + Abstand). */
    private static final int ICON_SPACE = 11;
    /** Version des Standard-Aussehens: aeltere gespeicherte Stile werden einmal auf den neuen Standard gesetzt. */
    private static final int VERSION = 3;

    // Farben als RGB (ohne Alpha)
    public boolean background = true;
    public int backgroundColor = 0x0E0F12;
    /** 0..100 */
    public int backgroundOpacity = 72;
    public int radius = NovaGui.HUD_RADIUS;
    public Bar bar = Bar.NONE;
    /** Akzentleiste in der Akzentfarbe statt eigener Farbe. */
    public boolean themeAccent = true;
    public int accentColor = 0x8B5CF6;
    public int labelColor = 0xA0A5AF;
    public int valueColor = 0xECEDF0;
    public boolean shadow = false;
    public boolean bold = false;
    public boolean uppercase = false;
    public boolean dividers = false;
    /** Warn-/Gefahrfarben (FPS, Ping, ...) und Rangfarben verwenden. */
    public boolean statusColors = true;
    public Layout layout = Layout.SPLIT;
    public int padding = 6;
    public int rowGap = 4;

    public void reset() {
        HudStyle d = new HudStyle();
        background = d.background;
        backgroundColor = d.backgroundColor;
        backgroundOpacity = d.backgroundOpacity;
        radius = d.radius;
        bar = d.bar;
        themeAccent = d.themeAccent;
        accentColor = d.accentColor;
        labelColor = d.labelColor;
        valueColor = d.valueColor;
        shadow = d.shadow;
        bold = d.bold;
        uppercase = d.uppercase;
        dividers = d.dividers;
        statusColors = d.statusColors;
        layout = d.layout;
        padding = d.padding;
        rowGap = d.rowGap;
    }

    // ---------------------------------------------------------------- Masse

    public String labelText(String label) {
        return uppercase ? label.toUpperCase(Locale.ROOT) : label;
    }

    private int textWidth(Font font, String text) {
        return text.isEmpty() ? 0 : NovaGui.width(font, text) + (bold ? 1 : 0);
    }

    private int barSpace() {
        return bar == Bar.LEFT ? BAR_W + 2 : 0;
    }

    public int width(Font font, List<Row> rows) {
        int content = 0;
        for (Row row : rows) {
            int valueW = textWidth(font, row.value());
            int gap = valueW == 0 ? 0 : layout == Layout.SPLIT ? SPLIT_GAP : INLINE_GAP;
            int iconW = row.icon() != null ? ICON_SPACE : 0;
            content = Math.max(content, iconW + textWidth(font, labelText(row.label())) + gap + valueW);
        }
        return content + padding * 2 + barSpace();
    }

    /** @param headerRows Anzahl Kopfzeilen am Anfang (danach etwas mehr Abstand), meist 0 oder 1. */
    public int height(List<Row> rows, int headerRows) {
        int n = rows.size();
        int h = padding * 2 + n * (LINE_H + rowGap) - (n > 0 ? rowGap : 0);
        return headerRows > 0 && n > headerRows ? h + HEADER_GAP : h;
    }

    // ---------------------------------------------------------------- Zeichnen

    private static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /**
     * Zeichnet den Block mit linker oberer Ecke bei (0, 0) im aktuellen Pose-Stack: ruhige dunkle Karte mit feiner
     * Kante, Labels gedaempft, Werte hell; Listen ohne Werte (Namen) hell, Kopfzeilen gedaempft. Farbe nur fuer
     * Warnungen (FPS, Ping ...), wenn "Status colors" an ist.
     */
    public void draw(GuiGraphicsExtractor g, Font font, List<Row> rows, int headerRows, float intro) {
        int w = width(font, rows);
        int h = height(rows, headerRows);
        int r = clamp(radius, 0, 8);
        if (background) {
            int a = Math.round(Math.max(0, Math.min(100, backgroundOpacity)) * 2.55F);
            NovaShapes.rect(g, 0, 0, w, h, r, NovaGui.alpha((a << 24) | (backgroundColor & 0xFFFFFF), intro));
            NovaShapes.outline(g, 0, 0, w, h, r, NovaGui.alpha(NovaGui.HUD_BORDER, intro));
        }
        int inset = Math.min(r, 3);
        int accent = NovaGui.alpha(NovaGui.BRAND, intro);
        switch (bar) {
            case TOP -> g.fill(inset, 0, w - inset, 1, accent);
            case BOTTOM -> g.fill(inset, h - 1, w - inset, h, accent);
            case LEFT -> g.fill(0, inset, BAR_W, h - inset, accent);
            default -> {
            }
        }

        int x0 = padding + barSpace();
        int x1 = w - padding;
        int y = padding;
        int prevBottom = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (headerRows > 0 && i == headerRows) {
                y += HEADER_GAP;
            }
            if (dividers && prevBottom >= 0) {
                int mid = (prevBottom + y) / 2;
                g.fill(x0, mid, x1, mid + 1, NovaGui.alpha(opaque(labelColor) & 0x30FFFFFF, intro));
            }
            Row row = rows.get(i);
            String label = labelText(row.label());
            boolean header = i < headerRows;
            int lc = header || !row.value().isEmpty() ? NovaGui.TEXT_DIM : NovaGui.TEXT;
            int vc = statusColors && row.valueColor() != 0 ? row.valueColor() : NovaGui.TEXT;
            int lx = x0;
            if (row.icon() != null) {
                row.icon().draw(g, x0, y, intro);
                lx += ICON_SPACE;
            }
            text(g, font, label, lx, y, opaque(lc), intro);
            if (!row.value().isEmpty()) {
                int vx = layout == Layout.SPLIT
                        ? x1 - textWidth(font, row.value())
                        : lx + textWidth(font, label) + INLINE_GAP;
                text(g, font, row.value(), vx, y, opaque(vc), intro);
            }
            prevBottom = y + LINE_H;
            y += LINE_H + rowGap;
        }
    }

    private void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, float intro) {
        int c = NovaGui.alpha(color, intro);
        if (((c >>> 24) & 0xFF) < 8 || text.isEmpty()) {
            return;
        }
        NovaGui.text(g, font, text, x, y, c, shadow);
        if (bold) {
            NovaGui.text(g, font, text, x + 1, y, c, false);
        }
    }

    // ---------------------------------------------------------------- Speichern

    public void save(Properties p, String prefix) {
        String k = prefix + "style.";
        p.setProperty(k + "v", String.valueOf(VERSION));
        p.setProperty(k + "background", String.valueOf(background));
        p.setProperty(k + "backgroundColor", hex(backgroundColor));
        p.setProperty(k + "backgroundOpacity", String.valueOf(backgroundOpacity));
        p.setProperty(k + "radius", String.valueOf(radius));
        p.setProperty(k + "bar", bar.name());
        p.setProperty(k + "themeAccent", String.valueOf(themeAccent));
        p.setProperty(k + "accentColor", hex(accentColor));
        p.setProperty(k + "labelColor", hex(labelColor));
        p.setProperty(k + "valueColor", hex(valueColor));
        p.setProperty(k + "shadow", String.valueOf(shadow));
        p.setProperty(k + "bold", String.valueOf(bold));
        p.setProperty(k + "uppercase", String.valueOf(uppercase));
        p.setProperty(k + "dividers", String.valueOf(dividers));
        p.setProperty(k + "statusColors", String.valueOf(statusColors));
        p.setProperty(k + "layout", layout.name());
        p.setProperty(k + "padding", String.valueOf(padding));
        p.setProperty(k + "rowGap", String.valueOf(rowGap));
    }

    public void load(Properties p, String prefix) {
        String k = prefix + "style.";
        if (integer(p, k + "v", p.getProperty(k + "background") == null ? VERSION : 1) < VERSION) {
            return; // alter Stil: einmal auf den neuen, aufgeraeumten Standard
        }
        background = bool(p, k + "background", background);
        backgroundColor = color(p, k + "backgroundColor", backgroundColor);
        backgroundOpacity = clamp(integer(p, k + "backgroundOpacity", backgroundOpacity), 0, 100);
        radius = clamp(integer(p, k + "radius", radius), 0, 8);
        try {
            bar = Bar.valueOf(p.getProperty(k + "bar", bar.name()));
        } catch (IllegalArgumentException ignored) {
            // Standard behalten
        }
        themeAccent = bool(p, k + "themeAccent", themeAccent);
        accentColor = color(p, k + "accentColor", accentColor);
        labelColor = color(p, k + "labelColor", labelColor);
        valueColor = color(p, k + "valueColor", valueColor);
        shadow = bool(p, k + "shadow", shadow);
        bold = bool(p, k + "bold", bold);
        uppercase = bool(p, k + "uppercase", uppercase);
        dividers = bool(p, k + "dividers", dividers);
        statusColors = bool(p, k + "statusColors", statusColors);
        try {
            layout = Layout.valueOf(p.getProperty(k + "layout", layout.name()));
        } catch (IllegalArgumentException ignored) {
            // Standard behalten
        }
        padding = clamp(integer(p, k + "padding", padding), 0, 12);
        rowGap = clamp(integer(p, k + "rowGap", rowGap), 0, 12);
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb & 0xFFFFFF);
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        return Boolean.parseBoolean(p.getProperty(key, String.valueOf(fallback)));
    }

    private static int integer(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int color(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, hex(fallback)).trim(), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
