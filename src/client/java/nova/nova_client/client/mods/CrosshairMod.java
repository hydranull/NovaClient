package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.CrosshairMenu;
import nova.nova_client.client.ModCategory;

import java.util.Arrays;
import java.util.Properties;

/**
 * Crosshair: ersetzt das Vanilla-Fadenkreuz durch ein selbst gestaltetes (Form, Groesse, Abstand, Farbe, ...)
 * oder ein Pixel fuer Pixel selbst gezeichnetes ("Custom"). "Default" = normales Minecraft-Fadenkreuz.
 * Alle Masse sind echte Bildschirm-Pixel, unabhaengig von der GUI-Skalierung.
 */
public class CrosshairMod extends GameTickMod {
    public enum Style {
        DEFAULT("Default"), CROSS("Cross"), T("T"), DOT("Dot"), CIRCLE("Circle"), BOX("Box"), CUSTOM("Custom");

        public final String label;

        Style(String label) {
            this.label = label;
        }
    }

    public static final int[] COLORS = {
            0xFFFFFF, 0xFF3B3B, 0x3DDC97, 0x00E5FF, 0xFFE66D, 0xFF3DD8, 0xB45CF6, 0xFF8C42
    };

    /** Groesse des Pixel-Rasters fuer "Custom" (ungerade, damit es eine Mitte gibt). */
    public static final int GRID = 21;
    private static final int HALF = GRID / 2;

    // Einstellungen
    public static Style style = Style.DEFAULT;
    public static int length = 6;
    public static int thickness = 2;
    public static int gap = 3;
    public static int opacity = 100;
    public static boolean dot = false;
    public static boolean outline = true;
    public static int color = 0x00E5FF;
    /** Selbst gezeichnete Pixel (Zeile fuer Zeile, GRID x GRID). */
    public static final boolean[] PIXELS = new boolean[GRID * GRID];

    private static CrosshairMod instance;

    /** Nimmt Rechtecke entgegen (Pixel-Koordinaten um die Mitte, x1/y1 exklusiv). */
    private interface RectSink {
        void rect(int x0, int y0, int x1, int y1);
    }

    static {
        defaultPixels();
    }

    public CrosshairMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    @Override
    public void execute(Minecraft client) {
    }

    /** Eigenes Fadenkreuz zeichnen? (Bei "Default" bleibt das Vanilla-Fadenkreuz.) */
    public static boolean isOn() {
        return instance != null && instance.active && style != Style.DEFAULT;
    }

    /** Zurueck zum normalen Minecraft-Fadenkreuz (und alle Werte auf Standard). */
    public static void reset() {
        style = Style.DEFAULT;
        length = 6;
        thickness = 2;
        gap = 3;
        opacity = 100;
        dot = false;
        outline = true;
        color = 0x00E5FF;
        defaultPixels();
    }

    // ---------------------------------------------------------------- Pixel-Raster

    public static boolean pixel(int x, int y) {
        return x >= 0 && y >= 0 && x < GRID && y < GRID && PIXELS[y * GRID + x];
    }

    public static void setPixel(int x, int y, boolean on) {
        if (x >= 0 && y >= 0 && x < GRID && y < GRID) {
            PIXELS[y * GRID + x] = on;
        }
    }

    public static void clearPixels() {
        Arrays.fill(PIXELS, false);
    }

    /** Startbild fuer "Custom": einfaches Kreuz. */
    private static void defaultPixels() {
        clearPixels();
        for (int i = HALF - 5; i <= HALF + 5; i++) {
            if (Math.abs(i - HALF) >= 2) {
                setPixel(i, HALF, true);
                setPixel(HALF, i, true);
            }
        }
        setPixel(HALF, HALF, true);
    }

    /** Die aktuelle Form (Cross, Circle, ...) ins Raster uebernehmen, um sie dann von Hand zu bearbeiten. */
    public static void rasterize(Style from) {
        clearPixels();
        shapes(from, (x0, y0, x1, y1) -> {
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    setPixel(x + HALF, y + HALF, true);
                }
            }
        });
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Im Spiel: Fadenkreuz in der Bildschirmmitte, 1 Einheit = 1 echter Pixel. */
    public static void drawHud(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        draw(g, g.guiWidth() / 2.0F, g.guiHeight() / 2.0F, 1.0F / client.getWindow().getGuiScale());
    }

    /**
     * Zeichnet das Fadenkreuz um (cx, cy) in GUI-Koordinaten; unit = GUI-Einheiten pro Fadenkreuz-Pixel
     * (im Spiel 1/GUI-Skalierung, in der Vorschau groesser zum Vergroessern).
     */
    public static void draw(GuiGraphicsExtractor g, float cx, float cy, float unit) {
        if (style == Style.DEFAULT) {
            return;
        }
        int alpha = Math.round(opacity * 2.55F);
        int main = (alpha << 24) | (color & 0xFFFFFF);
        int shadow = Math.round(alpha * 0.85F) << 24;

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(unit, unit);
        if (outline) {
            shapes(style, (x0, y0, x1, y1) -> g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, shadow));
        }
        shapes(style, (x0, y0, x1, y1) -> g.fill(x0, y0, x1, y1, main));
        g.pose().popMatrix();
    }

    /** Alle Teile einer Form als Rechtecke um die Mitte (0, 0). */
    private static void shapes(Style shape, RectSink sink) {
        int t = Math.max(1, thickness);
        int a = -t / 2;
        int b = a + t;

        switch (shape) {
            case CROSS, T -> {
                rect(sink, b + gap, a, b + gap + length, b);            // rechts
                rect(sink, a - gap - length, a, a - gap, b);            // links
                rect(sink, a, b + gap, b, b + gap + length);            // unten
                if (shape == Style.CROSS) {
                    rect(sink, a, a - gap - length, b, a - gap);        // oben
                }
            }
            case CIRCLE -> ring(sink, gap + length / 2.0F + t / 2.0F, t);
            case BOX -> {
                int outer = gap + length;
                rect(sink, -outer, -outer, outer, -outer + t);
                rect(sink, -outer, outer - t, outer, outer);
                rect(sink, -outer, -outer + t, -outer + t, outer - t);
                rect(sink, outer - t, -outer + t, outer, outer - t);
            }
            case DOT -> {
                int size = Math.max(1, length / 2 + 1);
                int s0 = -size / 2;
                rect(sink, s0, s0, s0 + size, s0 + size);
            }
            case CUSTOM -> {
                // Pro Zeile zusammenhaengende Pixel als ein Rechteck
                for (int y = 0; y < GRID; y++) {
                    int start = -1;
                    for (int x = 0; x <= GRID; x++) {
                        boolean on = x < GRID && PIXELS[y * GRID + x];
                        if (on && start < 0) {
                            start = x;
                        } else if (!on && start >= 0) {
                            sink.rect(start - HALF, y - HALF, x - HALF, y - HALF + 1);
                            start = -1;
                        }
                    }
                }
                return; // eigener Mittelpunkt ist Teil des Rasters
            }
            case DEFAULT -> {
                return;
            }
        }
        if (dot && shape != Style.DOT) {
            rect(sink, a, a, b, b);
        }
    }

    private static void rect(RectSink sink, int x0, int y0, int x1, int y1) {
        if (x1 > x0 && y1 > y0) {
            sink.rect(x0, y0, x1, y1);
        }
    }

    /** Ring aus waagrechten Streifen. */
    private static void ring(RectSink sink, float radius, int t) {
        float inner = Math.max(0.0F, radius - t / 2.0F);
        float outer = radius + t / 2.0F;
        int r = (int) Math.ceil(outer);
        for (int y = -r; y < r; y++) {
            float py = y + 0.5F;
            int start = Integer.MIN_VALUE;
            for (int x = -r; x <= r; x++) {
                float px = x + 0.5F;
                float d = (float) Math.sqrt(px * px + py * py);
                boolean inside = x < r && d >= inner && d <= outer;
                if (inside && start == Integer.MIN_VALUE) {
                    start = x;
                } else if (!inside && start != Integer.MIN_VALUE) {
                    sink.rect(start, y, x, y + 1);
                    start = Integer.MIN_VALUE;
                }
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new CrosshairMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "style", style.name());
        properties.setProperty(prefix + "length", String.valueOf(length));
        properties.setProperty(prefix + "thickness", String.valueOf(thickness));
        properties.setProperty(prefix + "gap", String.valueOf(gap));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
        properties.setProperty(prefix + "dot", String.valueOf(dot));
        properties.setProperty(prefix + "outline", String.valueOf(outline));
        properties.setProperty(prefix + "color", Integer.toHexString(color & 0xFFFFFF));
        StringBuilder pixels = new StringBuilder(PIXELS.length);
        for (boolean on : PIXELS) {
            pixels.append(on ? '1' : '0');
        }
        properties.setProperty(prefix + "pixels", pixels.toString());
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            style = Style.valueOf(properties.getProperty(prefix + "style", style.name()));
        } catch (IllegalArgumentException ignored) {
            // Standard behalten
        }
        length = number(properties, prefix + "length", length, 1, 30);
        thickness = number(properties, prefix + "thickness", thickness, 1, 8);
        gap = number(properties, prefix + "gap", gap, 0, 20);
        opacity = number(properties, prefix + "opacity", opacity, 10, 100);
        dot = Boolean.parseBoolean(properties.getProperty(prefix + "dot", String.valueOf(dot)));
        outline = Boolean.parseBoolean(properties.getProperty(prefix + "outline", String.valueOf(outline)));
        try {
            color = Integer.parseInt(properties.getProperty(prefix + "color", Integer.toHexString(color)), 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        String pixels = properties.getProperty(prefix + "pixels");
        if (pixels != null && pixels.length() == PIXELS.length) {
            for (int i = 0; i < PIXELS.length; i++) {
                PIXELS[i] = pixels.charAt(i) == '1';
            }
        }
    }

    private static int number(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
