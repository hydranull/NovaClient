package nova.nova_client.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Schlichte HUD-Karten (Hotkeys, PotionHUD): jede Zeile ein eigener, leicht durchsichtiger, runder Kasten mit feiner
 * Kante, untereinander mit kleinem Abstand, optional ein kleiner Zustands-Punkt. Alle Karten einer Liste sind gleich
 * breit.
 */
public final class HudCards {
    /** Hoehe einer Karte. */
    public static final int HEIGHT = 15;
    /** Abstand zwischen zwei Karten. */
    public static final int GAP = 2;
    /** Innenabstand links/rechts. */
    public static final int PAD = 5;
    /** Text-Oberkante innerhalb der Karte (mittig). */
    public static final int TEXT_Y = 4;
    /** Mitte des Zustands-Punkts; Text mit Punkt beginnt ab DOT_TEXT. */
    public static final int DOT_X = 6;
    public static final int DOT_TEXT = 11;
    private static final int RADIUS = NovaGui.HUD_RADIUS;
    /** Hintergrund: dunkel, gut die Haelfte deckend (etwas leichter als die anderen HUD-Kaesten). */
    private static final int BACKGROUND = 0xA80E0F12;
    private static final int BORDER = NovaGui.HUD_BORDER;
    private static final int KEYCAP_BACKGROUND = NovaGui.HOVER;
    private static final int KEYCAP_BORDER = NovaGui.BORDER_STRONG;

    private HudCards() {
    }

    /** Gesamthoehe von count Karten untereinander. */
    public static int stackHeight(int count) {
        return count <= 0 ? 0 : count * HEIGHT + (count - 1) * GAP;
    }

    /** Oberkante der Karte index. */
    public static int top(int index) {
        return index * (HEIGHT + GAP);
    }

    /** Kasten mit Rand; accent != 0: kleiner farbiger Punkt links (Zustand, RGB oder ARGB). */
    public static void card(GuiGraphicsExtractor g, int x0, int y0, int x1, int accent, float intro) {
        int y1 = y0 + HEIGHT;
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, NovaGui.alpha(BACKGROUND, intro));
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, NovaGui.alpha(BORDER, intro));
        if (accent != 0) {
            int color = (accent >>> 24) == 0 ? 0xFF000000 | accent : accent;
            NovaShapes.disc(g, x0 + DOT_X, y0 + HEIGHT / 2.0F, 1.6F, NovaGui.alpha(color, intro));
        }
    }

    /** Duenne Fortschrittsleiste unten in der Karte (fraction 0..1). */
    public static void progress(GuiGraphicsExtractor g, int x0, int y0, int x1, float fraction, int color, float intro) {
        int left = x0 + 3;
        int right = x1 - 3;
        int y = y0 + HEIGHT - 2;
        int end = left + Math.round((right - left) * Math.max(0.0F, Math.min(1.0F, fraction)));
        g.fill(left, y, right, y + 1, NovaGui.alpha(0x18FFFFFF, intro));
        if (end > left) {
            g.fill(left, y, end, y + 1, NovaGui.alpha(0xFF000000 | color, intro * 0.85F));
        }
    }

    /** Breite einer Tastenkappe fuer key (Taste in Mono). */
    public static int keycapWidth(Font font, String key) {
        return NovaType.width(font, NovaType.Face.MONO, key) + 8;
    }

    /** Taste als kleine Tastenkappe, rechtsbuendig an x1 (in der Karte ab y0). */
    public static void keycap(GuiGraphicsExtractor g, Font font, String key, int x1, int y0, int color, float intro) {
        int x0 = x1 - keycapWidth(font, key);
        NovaShapes.rect(g, x0, y0 + 2, x1, y0 + HEIGHT - 2, 3, NovaGui.alpha(KEYCAP_BACKGROUND, intro));
        NovaShapes.outline(g, x0, y0 + 2, x1, y0 + HEIGHT - 2, 3, NovaGui.alpha(KEYCAP_BORDER, intro));
        NovaType.draw(g, font, NovaType.Face.MONO, key, x0 + 4, NovaType.centerY(NovaType.Face.MONO, y0 + 2, HEIGHT - 4),
                NovaGui.alpha(color, intro));
    }
}
