package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;
import nova.nova_client.client.mods.GlowingOutlinesMod;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer Glowing Outlines: links die Farbe (Farbton, Saettigung, Helligkeit, Rainbow),
 * rechts der Glow (Groesse, Staerke, Konturbreite) mit Vorschau.
 */
public class GlowingOutlinesMenu extends NovaSettingsScreen {
    private static final int ROW = 26;

    private record Slider(String label, float min, float max, float step, Supplier<Float> get, Consumer<Float> set,
                          Function<Float, String> format, int kind) {
        static final int PLAIN = 0;
        static final int HUE = 1;
        static final int SATURATION = 2;
        static final int BRIGHTNESS = 3;

        float t() {
            return (get.get() - min) / (max - min);
        }

        void setT(float t) {
            float v = min + (max - min) * Mth.clamp(t, 0.0F, 1.0F);
            set.accept(Mth.clamp(Math.round(v / step) * step, min, max));
        }
    }

    private static String percent(float v) {
        return Math.round(v * 100.0F) + "%";
    }

    private static String pixels(float v) {
        return Math.round(v) + " px";
    }

    private final List<Slider> colors = List.of(
            new Slider("Color", 0.0F, 1.0F, 0.001F, () -> GlowingOutlinesMod.hue,
                    v -> GlowingOutlinesMod.hue = Math.min(v, 0.999F), v -> Math.round(v * 360.0F) + "°", Slider.HUE),
            new Slider("Saturation", 0.0F, 1.0F, 0.01F, () -> GlowingOutlinesMod.saturation,
                    v -> GlowingOutlinesMod.saturation = v, GlowingOutlinesMenu::percent, Slider.SATURATION),
            new Slider("Brightness", 0.1F, 1.0F, 0.01F, () -> GlowingOutlinesMod.brightness,
                    v -> GlowingOutlinesMod.brightness = v, GlowingOutlinesMenu::percent, Slider.BRIGHTNESS));

    private final List<Slider> glow = List.of(
            new Slider("Glow", 0.0F, GlowingOutlinesMod.MAX_GLOW, 1.0F, () -> GlowingOutlinesMod.glow,
                    v -> GlowingOutlinesMod.glow = v, v -> v < 0.5F ? "Off" : pixels(v), Slider.PLAIN),
            new Slider("Intensity", 0.0F, GlowingOutlinesMod.MAX_INTENSITY, 0.05F, () -> GlowingOutlinesMod.intensity,
                    v -> GlowingOutlinesMod.intensity = v, v -> String.format(Locale.ROOT, "%.2fx", v), Slider.PLAIN),
            new Slider("Outline width", 0.0F, GlowingOutlinesMod.MAX_WIDTH, 1.0F, () -> GlowingOutlinesMod.width,
                    v -> GlowingOutlinesMod.width = v, v -> v < 0.5F ? "Off" : pixels(v), Slider.PLAIN));

    private Slider dragging;

    public GlowingOutlinesMenu(Screen parent) {
        super(parent, "Glowing Outlines");
    }

    @Override
    protected void init() {
        super.init();
        setPanel(420, 144);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    // ---------------------------------------------------------------- Geometrie

    private int midX() {
        return (cx0 + cx1) / 2;
    }

    private int columnX0(boolean right) {
        return right ? midX() + 8 : cx0;
    }

    private int columnX1(boolean right) {
        return right ? cx1 : midX() - 8;
    }

    private int rowY(int index) {
        return cy0 + 2 + index * ROW;
    }

    private int rainbowY() {
        return rowY(colors.size()) + 2;
    }

    private int previewTop() {
        return rainbowY() + 22;
    }

    private boolean overSlider(int mx, int my, boolean right, int index) {
        int y = rowY(index) + 13;
        return isHovering(mx, my, columnX0(right) - 2, y - 5, columnX1(right) + 2, y + 9);
    }

    private void setFromMouse(Slider slider, double mouseX) {
        boolean right = glow.contains(slider);
        int x0 = columnX0(right) + 4;
        int x1 = columnX1(right) - 4;
        slider.setT((float) ((mouseX - x0) / (x1 - x0)));
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            for (int side = 0; side < 2; side++) {
                List<Slider> list = side == 0 ? colors : glow;
                for (int i = 0; i < list.size(); i++) {
                    if (overSlider(mx, my, side == 1, i)) {
                        dragging = list.get(i);
                        setFromMouse(dragging, event.x());
                        return true;
                    }
                }
            }
            if (isHovering(mx, my, columnX0(false), rainbowY(), columnX1(false), rainbowY() + 14)) {
                GlowingOutlinesMod.rainbow = !GlowingOutlinesMod.rainbow;
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging != null) {
            setFromMouse(dragging, event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging = null;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        for (int side = 0; side < 2; side++) {
            List<Slider> list = side == 0 ? colors : glow;
            for (int i = 0; i < list.size(); i++) {
                if (isHovering(mx, my, columnX0(side == 1), rowY(i), columnX1(side == 1), rowY(i) + ROW)) {
                    Slider s = list.get(i);
                    float step = Math.max(s.step(), (s.max() - s.min()) / 100.0F);
                    s.setT(s.t() + (scrollY > 0 ? step : -step) / (s.max() - s.min()));
                    ModConfig.save();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---------------------------------------------------------------- Rendering

    private static int trackColor(Slider slider, float t) {
        return 0xFF000000 | switch (slider.kind()) {
            case Slider.HUE -> Mth.hsvToRgb(Math.min(t, 0.999F), 1.0F, 1.0F);
            case Slider.SATURATION -> Mth.hsvToRgb(GlowingOutlinesMod.hue, t, Math.max(0.35F, GlowingOutlinesMod.brightness));
            case Slider.BRIGHTNESS -> Mth.hsvToRgb(GlowingOutlinesMod.hue, GlowingOutlinesMod.saturation, t);
            default -> 0;
        };
    }

    private boolean drawSlider(GuiGraphicsExtractor graphics, Slider slider, boolean right, int index, int mouseX, int mouseY) {
        int x0 = columnX0(right) + 4;
        int x1 = columnX1(right) - 4;
        int labelY = rowY(index);
        int y = labelY + 13;
        boolean hover = overSlider(mouseX, mouseY, right, index) || dragging == slider;

        NovaGui.text(graphics, font, slider.label(), x0 - 4, labelY, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        String value = slider.format().apply(slider.get().get());
        NovaGui.text(graphics, font, value, x1 + 4 - NovaGui.width(font, value), labelY, NovaGui.TEXT_MUTED);

        float t = Mth.clamp(slider.t(), 0.0F, 1.0F);
        int kx = x0 + Math.round((x1 - x0) * t);
        if (slider.kind() == Slider.PLAIN) {
            NovaShapes.rect(graphics, x0, y + 1, x1, y + 3, 1, NovaGui.TRACK);
            NovaShapes.rect(graphics, x0, y + 1, kx, y + 3, 1, accent);
        } else {
            for (int i = 0; i < NovaControls.TRACK_PIECES; i++) {
                NovaControls.trackPiece(graphics, x0, y, x1, y + 4, i, trackColor(slider, (float) i / NovaControls.TRACK_PIECES),
                        trackColor(slider, (float) (i + 1) / NovaControls.TRACK_PIECES));
            }
        }
        int knob = slider.kind() == Slider.PLAIN ? NovaGui.TEXT : trackColor(slider, t);
        NovaControls.knob(graphics, kx, y + 2, hover, accent);
        return hover;
    }

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        graphics.fill(midX(), cy0, midX() + 1, cy1 - 4, NovaGui.BORDER);

        for (int i = 0; i < colors.size(); i++) {
            pointer |= drawSlider(graphics, colors.get(i), false, i, mouseX, mouseY);
        }
        for (int i = 0; i < glow.size(); i++) {
            pointer |= drawSlider(graphics, glow.get(i), true, i, mouseX, mouseY);
        }

        // Rainbow
        int tx0 = columnX0(false);
        int tx1 = columnX1(false);
        int ty = rainbowY();
        boolean hover = isHovering(mouseX, mouseY, tx0, ty, tx1, ty + 14);
        pointer |= hover;
        NovaGui.text(graphics, font, "Rainbow", tx0, ty + 3, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(graphics, tx1 - 22, ty + 1, GlowingOutlinesMod.rainbow ? 1.0F : 0.0F, false, 1.0F, accent);

        // Vorschau: Farbfeld mit Kontur und Glow
        int top = previewTop();
        int bottom = cy1 - 4;
        if (bottom - top > 12) {
            NovaShapes.rect(graphics, tx0, top, tx1, bottom, 5, NovaGui.CARD);
            NovaShapes.outline(graphics, tx0, top, tx1, bottom, 5, NovaGui.BORDER);
            int color = GlowingOutlinesMod.color();
            int cx = (tx0 + tx1) / 2;
            int cy = (top + bottom) / 2;
            int half = Math.min(10, (bottom - top) / 2 - 4);
            float glowAmount = Mth.clamp(GlowingOutlinesMod.glow / GlowingOutlinesMod.MAX_GLOW
                    * GlowingOutlinesMod.intensity / 2.0F, 0.0F, 1.0F);
            int w = Math.round(GlowingOutlinesMod.width);
            if (w > 0) {
                graphics.fill(cx - half - w, cy - half - w, cx + half + w, cy + half + w, color);
            }
            graphics.fill(cx - half, cy - half, cx + half, cy + half, 0xFF8B8B8B);
        }
        return pointer;
    }
}
