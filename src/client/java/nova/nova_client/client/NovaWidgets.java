package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Nova-Look fuer Minecrafts eigene Menues (Optionen, Grafik, Steuerung, Serverliste, ...): Knoepfe, Regler, Checkboxen
 * und Listen werden statt mit den Minecraft-Texturen als ruhige, dunkle Flaechen mit feinem Rand gezeichnet, beim
 * Drueberfahren eine Stufe heller - wie NovaControls. Text und Bedienung bleiben von Minecraft. Mit "Classic menu" ist
 * alles wieder Vanilla.
 */
public final class NovaWidgets {
    private static final int FILL = NovaGui.alpha(NovaGui.CARD, 0.92F);
    private static final int FILL_HOVER = NovaGui.RAISED;
    private static final int FILL_OFF = NovaGui.alpha(NovaGui.CARD, 0.5F);

    private NovaWidgets() {
    }

    public static boolean enabled() {
        return UiSettings.novaScreens;
    }

    /** Knopf (auch Auswahl-Knoepfe wie "Grafik: Schnell"). */
    public static void button(GuiGraphicsExtractor g, AbstractWidget widget, boolean hovered) {
        int x0 = widget.getX();
        int y0 = widget.getY();
        int x1 = x0 + widget.getWidth();
        int y1 = y0 + widget.getHeight();
        int r = Math.min(NovaGui.RADIUS, widget.getHeight() / 2);
        boolean lit = hovered && widget.active;
        NovaShapes.rect(g, x0, y0, x1, y1, r, !widget.active ? FILL_OFF : lit ? FILL_HOVER : FILL);
        NovaShapes.outline(g, x0, y0, x1, y1, r, lit ? NovaGui.BORDER_STRONG : NovaGui.BORDER);
    }

    /** Regler-Bahn: wie ein Knopf, bis zum Wert leicht in der Akzentfarbe gefuellt. */
    public static void sliderTrack(GuiGraphicsExtractor g, AbstractWidget widget, double value, boolean hovered) {
        int x0 = widget.getX();
        int y0 = widget.getY();
        int x1 = x0 + widget.getWidth();
        int y1 = y0 + widget.getHeight();
        int r = Math.min(NovaGui.RADIUS, widget.getHeight() / 2);
        boolean lit = hovered && widget.active;
        NovaShapes.rect(g, x0, y0, x1, y1, r, !widget.active ? FILL_OFF : lit ? FILL_HOVER : FILL);
        int fill = x0 + (int) Math.round(Math.max(0.0D, Math.min(1.0D, value)) * (x1 - x0));
        if (fill > x0 + 1 && widget.active) {
            NovaShapes.rect(g, x0, y0, fill, y1, r, NovaGui.alpha(NovaGui.BRAND, lit ? 0.32F : 0.22F));
        }
        NovaShapes.outline(g, x0, y0, x1, y1, r, lit ? NovaGui.BORDER_STRONG : NovaGui.BORDER);
    }

    /** Regler-Griff: schmale helle Pille an der Wert-Position. */
    public static void sliderHandle(GuiGraphicsExtractor g, int x, int y, int width, int height, boolean hovered) {
        int cx = x + width / 2;
        int color = hovered ? 0xFFFFFFFF : 0xFFE2E5EA;
        NovaShapes.rect(g, cx - 2, y + 4, cx + 2, y + height - 4, 2, color);
    }

    /** Checkbox: wie in den Nova-Fenstern (NovaControls.checkbox). */
    public static void checkbox(GuiGraphicsExtractor g, int x, int y, int size, boolean selected, boolean highlighted) {
        NovaControls.checkbox(g, x, y, size, selected, highlighted, NovaGui.BRAND);
    }

    /** Hintergrund einer Liste (z.B. Grafik-Optionen): leicht abgedunkelt statt Minecraft-Textur. */
    public static void listBackground(GuiGraphicsExtractor g, AbstractWidget list) {
        g.fill(list.getX(), list.getY(), list.getX() + list.getWidth(), list.getY() + list.getHeight(), NovaGui.alpha(NovaGui.BG, 0.45F));
    }

    /** Trennlinien ueber und unter einer Liste: feine Haarlinien. */
    public static void listSeparators(GuiGraphicsExtractor g, AbstractWidget list) {
        int x0 = list.getX();
        int x1 = x0 + list.getWidth();
        int top = list.getY();
        int bottom = top + list.getHeight();
        g.fill(x0, top - 1, x1, top, NovaGui.BORDER);
        g.fill(x0, bottom, x1, bottom + 1, NovaGui.BORDER);
    }
}
