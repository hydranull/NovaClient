package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.Util;
import nova.nova_client.client.HudLayout;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaLogo;
import nova.nova_client.client.NovaPlusMenu;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.NovaType;
import nova.nova_client.client.Rect;

import java.time.LocalTime;
import java.util.Properties;

/**
 * Nova+: die Wortmarke "nova+" oben links (frei, ohne Kasten) und eine schlichte Info-Leiste oben in der Mitte
 * (Uhrzeit, FPS, Ping) als dunkler Kasten mit runden Ecken und feiner Kante wie die anderen HUDs.
 * Bossleisten und Tab-Liste rutschen unter die Leiste ({@link #topShift(int)}).
 */
public class NovaPlusMod extends RenderMod {
    private static final int X = 6;
    /** Hoehe der Leiste. */
    private static final int HEIGHT = 17;
    /** Hoehe der Wortmarke (eine Textzeile). */
    private static final int MARK_HEIGHT = 11;
    /** Feiner Schatten unter der Wortmarke: 1 echter Pixel, damit sie auch vor hellem Himmel lesbar bleibt. */
    private static final int MARK_SHADOW = 0x80000000;
    private static final int PAD = 7;
    /** Oberkante der Leiste (wie die HUD-Spalten). */
    private static final int BAR_TOP = 6;
    /** Abstand zwischen Leiste und dem, was darunter rutscht. */
    private static final int BELOW = 4;
    /** Nova-Zeichen vorne in der Leiste (Kantenlaenge). */
    private static final int LOGO = 11;
    /** Uhr-Symbol: Breite und Abstand zur Zeit. */
    private static final int ICON = 7;
    private static final int ICON_GAP = 4;
    private static final int WARN = 0xFFFFC857;
    private static final int DIVIDER = 0x1CFFFFFF;
    private static final NovaType.Face MARK = NovaType.Face.STRONG;
    private static final NovaType.Face VALUE = NovaType.Face.STRONG;
    private static final NovaType.Face UNIT = NovaType.Face.BODY;

    // Einstellungen
    public static boolean watermark = true;
    public static boolean bar = true;
    public static boolean showLogo = true;
    public static boolean showTime = true;
    public static boolean showFps = true;
    public static boolean showPing = true;

    /** Wann die Leiste zuletzt gezeichnet wurde und wo sie endet (fuer Bossleiste/Tab-Liste). */
    private static long barDrawnAt;
    private static int barBottom;

    public NovaPlusMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        if (watermark) {
            drawMark(g, mc.font, X, HudLayout.top(HudLayout.Slot.WATERMARK, MARK_HEIGHT), 1.0F);
        }
        if (bar) {
            Rect drawn = drawBar(g, mc.font, mc.getWindow().getGuiScaledWidth() / 2, BAR_TOP, ping(mc), 1.0F);
            if (drawn != null) {
                barDrawnAt = Util.getMillis();
                barBottom = drawn.y1;
            }
        }
    }

    /**
     * So weit muss ein Vanilla-Element oben in der Mitte nach unten, dessen Inhalt bei firstY beginnt, damit es unter
     * der Leiste steht (0 ohne Leiste).
     */
    public static int topShift(int firstY) {
        if (Util.getMillis() - barDrawnAt > 250L) {
            return 0;
        }
        return Math.max(0, barBottom + BELOW - firstY);
    }

    /** Vorschau im Nova-Menue: Leiste und darunter die Wortmarke, linke obere Ecke bei (x, y). */
    public static Rect drawPreview(GuiGraphicsExtractor g, Font font, int x, int y, float a) {
        int markW = markWidth(font);
        int barW = barWidth(font, 24);
        int w = Math.max(markW, barW);
        Rect drawn = drawBar(g, font, x + w / 2, y, 24, a);
        int bottom = drawn == null ? y : drawn.y1 + 6;
        drawMark(g, font, x + (w - markW) / 2, bottom, a);
        return new Rect(x, y, x + w, bottom + MARK_HEIGHT);
    }

    // ---------------------------------------------------------------- Wortmarke

    private static int markWidth(Font font) {
        return NovaType.width(font, MARK, "nova") + 1 + NovaType.width(font, MARK, "+");
    }

    /** "nova+" frei ab (x, top): erst der feine Schatten (eigene Ebene, damit er sicher darunter liegt), dann die Schrift. */
    private static void drawMark(GuiGraphicsExtractor g, Font font, int x, int top, float a) {
        int y = NovaType.centerY(MARK, top, MARK_HEIGHT);
        int word = NovaType.width(font, MARK, "nova");
        float pixel = 1.0F / NovaShapes.scale();
        g.pose().pushMatrix();
        g.pose().translate(pixel, pixel);
        NovaType.draw(g, font, MARK, "nova", x, y, NovaGui.alpha(MARK_SHADOW, a));
        NovaType.draw(g, font, MARK, "+", x + word + 1, y, NovaGui.alpha(MARK_SHADOW, a));
        g.pose().popMatrix();
        g.nextStratum();
        NovaType.draw(g, font, MARK, "nova", x, y, NovaGui.alpha(NovaGui.TEXT, a));
        NovaType.draw(g, font, MARK, "+", x + word + 1, y, NovaGui.alpha(NovaGui.BRAND, a));
    }

    private static void box(GuiGraphicsExtractor g, int x0, int y0, int x1, float a) {
        NovaShapes.rect(g, x0, y0, x1, y0 + HEIGHT, NovaGui.HUD_RADIUS, NovaGui.alpha(NovaGui.HUD_BACKGROUND, a));
        NovaShapes.outline(g, x0, y0, x1, y0 + HEIGHT, NovaGui.HUD_RADIUS, NovaGui.alpha(NovaGui.HUD_BORDER, a));
    }

    // ---------------------------------------------------------------- Info-Leiste

    /** Ping zum Server; -1 im Einzelspieler (dort gibt es keinen). */
    private static int ping(Minecraft mc) {
        if (mc.hasSingleplayerServer() || mc.getConnection() == null || mc.player == null) {
            return -1;
        }
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    /**
     * Breite einer Zahl: mindestens so breit wie sample, damit die Leiste nicht bei jeder Ziffer zuckt
     * (Zahlen stehen rechtsbuendig darin).
     */
    private static int slot(Font font, String value, String sample) {
        return Math.max(NovaType.width(font, VALUE, value), NovaType.width(font, VALUE, sample));
    }

    private static String clock() {
        LocalTime now = LocalTime.now();
        return (now.getHour() < 10 ? "0" : "") + now.getHour() + (now.getMinute() < 10 ? ":0" : ":") + now.getMinute();
    }

    private static String fps() {
        return String.valueOf(Minecraft.getInstance().getFps());
    }

    private static int barWidth(Font font, int ping) {
        int w = 0;
        int parts = 0;
        if (showLogo) {
            w += LOGO;
            parts++;
        }
        if (showTime) {
            w += ICON + ICON_GAP + slot(font, clock(), "00:00");
            parts++;
        }
        if (showFps) {
            w += slot(font, fps(), "000") + NovaType.width(font, UNIT, " fps");
            parts++;
        }
        if (showPing && ping >= 0) {
            w += slot(font, String.valueOf(ping), "00") + NovaType.width(font, UNIT, " ms");
            parts++;
        }
        return parts == 0 ? 0 : w + parts * 2 * PAD;
    }

    /** Leiste mittig ueber cx ab top; null, wenn nichts anzuzeigen ist. */
    private static Rect drawBar(GuiGraphicsExtractor g, Font font, int cx, int top, int ping, float a) {
        int w = barWidth(font, ping);
        if (w == 0) {
            return null;
        }
        int x0 = cx - w / 2;
        int x1 = x0 + w;
        box(g, x0, top, x1, a);
        int x = x0;
        boolean first = true;
        if (showLogo) {
            NovaLogo.draw(g, x + PAD, top + (HEIGHT - LOGO) / 2, LOGO, a);
            x += PAD + LOGO + PAD;
            first = false;
        }
        if (showTime) {
            if (!first) {
                divider(g, x, top, a);
            }
            String time = clock();
            int slot = slot(font, time, "00:00");
            int left = x + PAD;
            clockIcon(g, left, top, a);
            value(g, font, time, left + ICON + ICON_GAP + slot, top, NovaGui.TEXT, a);
            x = left + ICON + ICON_GAP + slot + PAD;
            first = false;
        }
        if (showFps) {
            if (!first) {
                divider(g, x, top, a);
            }
            int fps = Minecraft.getInstance().getFps();
            String text = String.valueOf(fps);
            int slot = slot(font, text, "000");
            int right = x + PAD + slot;
            value(g, font, text, right, top, fps >= 60 ? NovaGui.TEXT : fps >= 30 ? WARN : NovaGui.DANGER, a);
            unit(g, font, " fps", right, top, a);
            x = right + NovaType.width(font, UNIT, " fps") + PAD;
            first = false;
        }
        if (showPing && ping >= 0) {
            if (!first) {
                divider(g, x, top, a);
            }
            String text = String.valueOf(ping);
            int slot = slot(font, text, "00");
            int right = x + PAD + slot;
            value(g, font, text, right, top, ping < 120 ? NovaGui.TEXT : ping < 250 ? WARN : NovaGui.DANGER, a);
            unit(g, font, " ms", right, top, a);
        }
        return new Rect(x0, top, x1, top + HEIGHT);
    }

    /** Zahl rechtsbuendig an right. */
    private static void value(GuiGraphicsExtractor g, Font font, String text, int right, int top, int color, float a) {
        NovaType.right(g, font, VALUE, text, right, NovaType.centerY(VALUE, top, HEIGHT), NovaGui.alpha(color, a));
    }

    private static void unit(GuiGraphicsExtractor g, Font font, String text, int x, int top, float a) {
        NovaType.draw(g, font, UNIT, text, x, NovaType.centerY(UNIT, top, HEIGHT), NovaGui.alpha(NovaGui.TEXT_DIM, a));
    }

    /** Feiner senkrechter Strich zwischen zwei Abschnitten. */
    private static void divider(GuiGraphicsExtractor g, int x, int top, float a) {
        g.fill(x, top + 5, x + 1, top + HEIGHT - 5, NovaGui.alpha(DIVIDER, a));
    }

    /** Kleine Uhr: Ring mit zwei Zeigern (3 Uhr), links ab x. */
    private static void clockIcon(GuiGraphicsExtractor g, int x, int top, float a) {
        float cx = x + ICON / 2.0F;
        float cy = top + HEIGHT / 2.0F;
        int color = NovaGui.alpha(NovaGui.TEXT_DIM, a);
        NovaShapes.ring(g, cx, cy, ICON / 2.0F, 1.0F, color);
        int hx = Math.round(cx - 0.5F);
        int hy = Math.round(cy - 0.5F);
        g.fill(hx, hy - 2, hx + 1, hy + 1, color);
        g.fill(hx, hy, hx + 2, hy + 1, color);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new NovaPlusMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "watermark", String.valueOf(watermark));
        properties.setProperty(prefix + "bar", String.valueOf(bar));
        properties.setProperty(prefix + "logo", String.valueOf(showLogo));
        properties.setProperty(prefix + "time", String.valueOf(showTime));
        properties.setProperty(prefix + "fps", String.valueOf(showFps));
        properties.setProperty(prefix + "ping", String.valueOf(showPing));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        watermark = Boolean.parseBoolean(properties.getProperty(prefix + "watermark", String.valueOf(watermark)));
        bar = Boolean.parseBoolean(properties.getProperty(prefix + "bar", String.valueOf(bar)));
        showLogo = Boolean.parseBoolean(properties.getProperty(prefix + "logo", String.valueOf(showLogo)));
        showTime = Boolean.parseBoolean(properties.getProperty(prefix + "time", String.valueOf(showTime)));
        showFps = Boolean.parseBoolean(properties.getProperty(prefix + "fps", String.valueOf(showFps)));
        showPing = Boolean.parseBoolean(properties.getProperty(prefix + "ping", String.valueOf(showPing)));
    }
}
