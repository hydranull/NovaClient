package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Gemeinsames Einstellungs-Menue fuer leuchtende Effekte (Fireflies): Farbmodus (Nova, Rainbow, Custom),
 * Farbton-Regler mit Regenbogen, Vorschau-Leiste der Farben und beliebige weitere Regler (z.B. Deckkraft, Anzahl).
 * Zieht man am Farbton, wird automatisch auf Custom umgestellt.
 */
public class NovaFxMenu extends NovaSettingsScreen {
    /** Ein zusaetzlicher Regler. */
    public record Slider(String label, float min, float max, float step, Supplier<Float> get, Consumer<Float> set,
                         Function<Float, String> format) {
        void setClamped(float value) {
            float stepped = Math.round((value - min) / step) * step + min;
            set.accept(Mth.clamp(stepped, min, max));
        }
    }

    private static final NovaFx.ColorMode[] MODES = NovaFx.ColorMode.values();
    private static final int HUE = 0;

    private final String subtitle;
    private final Supplier<NovaFx.ColorMode> getMode;
    private final Consumer<NovaFx.ColorMode> setMode;
    private final Supplier<Float> getHue;
    private final Consumer<Float> setHue;
    private final List<Slider> sliders;
    /** -1 = nichts, 0 = Farbton, 1.. = weitere Regler. */
    private int dragging = -1;

    public NovaFxMenu(Screen parent, String title, String subtitle, Supplier<NovaFx.ColorMode> getMode,
                      Consumer<NovaFx.ColorMode> setMode, Supplier<Float> getHue, Consumer<Float> setHue,
                      Slider... sliders) {
        super(parent, title);
        this.subtitle = subtitle;
        this.getMode = getMode;
        this.setMode = setMode;
        this.getHue = getHue;
        this.setHue = setHue;
        this.sliders = List.of(sliders);
    }

    @Override
    protected void init() {
        super.init();
        setPanel(320, 90 + sliders.size() * 32 + 4);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        return subtitle;
    }

    // ---------------------------------------------------------------- Geometrie

    private int modeWidth() {
        return (cx1 - cx0 - 4 * (MODES.length - 1)) / MODES.length;
    }

    private int modeX(int index) {
        return cx0 + index * (modeWidth() + 4);
    }

    private int modeY() {
        return cy0 + 12;
    }

    private int hueY() {
        return cy0 + 50;
    }

    private int previewY() {
        return cy0 + 66;
    }

    private int sliderLabelY(int index) {
        return cy0 + 82 + index * 32;
    }

    private int sliderY(int index) {
        return sliderLabelY(index) + 14;
    }

    private int trackX0() {
        return cx0 + 4;
    }

    private int trackX1() {
        return cx1 - 4;
    }

    private float fraction(double mouseX) {
        return (float) Mth.clamp((mouseX - trackX0()) / (trackX1() - trackX0()), 0.0D, 1.0D);
    }

    private void drag(double mouseX) {
        float t = fraction(mouseX);
        if (dragging == HUE) {
            setHue.accept(Math.min(0.999F, t));
            setMode.accept(NovaFx.ColorMode.CUSTOM);
        } else if (dragging > 0) {
            Slider s = sliders.get(dragging - 1);
            s.setClamped(s.min() + (s.max() - s.min()) * t);
        }
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            for (int i = 0; i < MODES.length; i++) {
                if (isHovering(mx, my, modeX(i), modeY(), modeX(i) + modeWidth(), modeY() + 18)) {
                    setMode.accept(MODES[i]);
                    ModConfig.save();
                    NovaGui.clickSound();
                    return true;
                }
            }
            if (isHovering(mx, my, trackX0() - 4, hueY() - 5, trackX1() + 4, hueY() + 13)) {
                dragging = HUE;
                drag(event.x());
                return true;
            }
            for (int i = 0; i < sliders.size(); i++) {
                if (isHovering(mx, my, trackX0() - 4, sliderY(i) - 6, trackX1() + 4, sliderY(i) + 10)) {
                    dragging = i + 1;
                    drag(event.x());
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging >= 0) {
            drag(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging >= 0) {
            dragging = -1;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        NovaFx.ColorMode mode = getMode.get();
        float hue = getHue.get();

        // Farbmodus
        NovaGui.text(graphics, font, "Color", cx0, cy0, NovaGui.TEXT_DIM);
        for (int i = 0; i < MODES.length; i++) {
            int x = modeX(i);
            boolean hover = isHovering(mouseX, mouseY, x, modeY(), x + modeWidth(), modeY() + 18);
            pointer |= hover;
            NovaControls.choice(graphics, font, x, modeY(), x + modeWidth(), modeY() + 18, MODES[i].label, hover, mode == MODES[i], accent);
        }

        // Farbton: Regenbogen-Leiste (nur fuer Custom kraeftig)
        int x0 = trackX0();
        int x1 = trackX1();
        int y = hueY();
        boolean custom = mode == NovaFx.ColorMode.CUSTOM;
        boolean hueHover = isHovering(mouseX, mouseY, x0 - 4, y - 5, x1 + 4, y + 13) || dragging == HUE;
        pointer |= hueHover;
        NovaGui.text(graphics, font, "Hue", cx0, y - 12, custom ? NovaGui.TEXT_DIM : NovaGui.TEXT_MUTED);
        float strength = custom ? 1.0F : 0.4F;
        for (int i = 0; i < NovaControls.TRACK_PIECES; i++) {
            NovaControls.trackPiece(graphics, x0, y, x1, y + 8, i, hueColor((float) i / NovaControls.TRACK_PIECES, strength),
                    hueColor((float) (i + 1) / NovaControls.TRACK_PIECES, strength));
        }
        NovaShapes.outline(graphics, x0 - 1, y - 1, x1 + 1, y + 9, 2, NovaGui.BORDER);
        int kx = x0 + Math.round((x1 - x0) * hue);
        int knob = 0xFF000000 | (Mth.hsvToRgb(hue, 0.65F, 1.0F) & 0xFFFFFF);
        NovaControls.knob(graphics, kx, y + 4, hueHover, accent);

        // Vorschau: so sehen die Farben gerade aus (bewegt sich wie im Spiel)
        int py = previewY();
        float time = (Util.getMillis() % 100_000L) / 1000.0F;
        for (int i = 0; i < NovaControls.TRACK_PIECES; i++) {
            float t = (float) i / NovaControls.TRACK_PIECES;
            float next = (float) (i + 1) / NovaControls.TRACK_PIECES;
            NovaControls.trackPiece(graphics, x0, py, x1, py + 5, i, 0xFF000000 | NovaFx.color(mode, hue, t + time * 0.08F),
                    0xFF000000 | NovaFx.color(mode, hue, next + time * 0.08F));
        }

        // weitere Regler
        for (int i = 0; i < sliders.size(); i++) {
            Slider s = sliders.get(i);
            int ly = sliderLabelY(i);
            NovaGui.text(graphics, font, s.label(), cx0, ly, NovaGui.TEXT_DIM);
            String value = s.format().apply(s.get().get());
            NovaGui.text(graphics, font, value, cx1 - NovaGui.width(font, value), ly, NovaGui.TEXT);
            int sy = sliderY(i);
            boolean hover = isHovering(mouseX, mouseY, x0 - 4, sy - 6, x1 + 4, sy + 10) || dragging == i + 1;
            pointer |= hover;
            float t = (s.get().get() - s.min()) / (s.max() - s.min());
            int knobX = x0 + Math.round((x1 - x0) * Mth.clamp(t, 0.0F, 1.0F));
            NovaShapes.rect(graphics, x0, sy + 1, x1, sy + 3, 1, NovaGui.TRACK);
            NovaShapes.rect(graphics, x0, sy + 1, knobX, sy + 3, 1, accent);
            NovaControls.knob(graphics, knobX, sy + 2, hover, accent);
        }
        return pointer;
    }

    /** Farbe der Farbton-Spur an der Stelle t (etwas entsaettigt, blass wenn nicht Custom). */
    private static int hueColor(float t, float strength) {
        return NovaGui.alpha(0xFF000000 | (Mth.hsvToRgb(Math.min(t, 0.999F), 0.65F, 1.0F) & 0xFFFFFF), strength);
    }
}
