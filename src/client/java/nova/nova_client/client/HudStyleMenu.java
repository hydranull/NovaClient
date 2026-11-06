package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.function.Supplier;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Aussehen eines HUD-Elements anpassen (fuer jede HUD-Mod wiederverwendbar): Layout, Akzentleiste,
 * Abstaende, Schrift-Optionen und Farben mit Live-Vorschau.
 */
public class HudStyleMenu extends NovaSettingsScreen {
    private static final int[] PRESETS = {
            0xFFFFFF, 0xF2F3F5, 0xA4A9B4, 0x5A5F6B, 0x0E0E12, 0x000000,
            0xFF4D6A, 0xFFB000, 0x5DFF6A, 0x3DB8FF, 0x8B5CF6, 0xFF5FD2,
    };
    private static final String[] TARGETS = {"Label", "Value", "BG", "Accent"};
    private static final String[] STEPPERS = {"Padding", "Row spacing", "Corner radius", "BG opacity"};
    private static final String[] TOGGLES = {
            "Background", "Text shadow", "Bold text", "Uppercase labels", "Dividers", "Status colors", "Accent color",
    };
    private static final String[] SLIDERS = {"Hue", "Saturation", "Brightness"};
    private static final int CHOICE_LABEL = 44;
    private static final int SLIDER_LABEL = 58;

    private final HudStyle style;
    private final Supplier<List<HudStyle.Row>> preview;
    private final int headerRows;
    /** Welche Zahlenwerte angezeigt werden (Index in STEPPERS) - z.B. ohne "Corner radius". */
    private final int[] stepperIds;

    /** Welche Farbe gerade bearbeitet wird (Index in TARGETS). */
    private int target;
    private float[] hsv;
    private int dragging = -1;
    private final float[] toggleAnim = new float[TOGGLES.length];

    /**
     * @param preview    Beispielzeilen fuer die Vorschau (am besten die echten Zeilen der Mod)
     * @param headerRows Anzahl Kopfzeilen wie in der Mod
     */
    public HudStyleMenu(Screen parent, String title, HudStyle style, Supplier<List<HudStyle.Row>> preview, int headerRows) {
        this(parent, title, style, preview, headerRows, true);
    }

    /** @param showRadius false = ohne "Corner radius" (z.B. Adminlist) */
    public HudStyleMenu(Screen parent, String title, HudStyle style, Supplier<List<HudStyle.Row>> preview, int headerRows,
                        boolean showRadius) {
        super(parent, title + " Look");
        this.style = style;
        this.preview = preview;
        this.headerRows = headerRows;
        this.stepperIds = showRadius ? new int[]{0, 1, 2, 3} : new int[]{0, 1, 3};
        this.hsv = toHsv(color(target));
    }

    @Override
    protected void init() {
        super.init();
        setPanel(480, 244);
        for (int i = 0; i < TOGGLES.length; i++) {
            toggleAnim[i] = toggle(i) ? 1.0F : 0.0F;
        }
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    // ---------------------------------------------------------------- Werte

    private boolean toggle(int index) {
        return switch (index) {
            case 0 -> style.background;
            case 1 -> style.shadow;
            case 2 -> style.bold;
            case 3 -> style.uppercase;
            case 4 -> style.dividers;
            case 5 -> style.statusColors;
            default -> style.themeAccent;
        };
    }

    private void flip(int index) {
        switch (index) {
            case 0 -> {
                style.background = !style.background;
                // Mit Hintergrund braucht der Text etwas Luft zum Rand
                if (style.background && style.padding < 5) {
                    style.padding = 6;
                }
            }
            case 1 -> style.shadow = !style.shadow;
            case 2 -> style.bold = !style.bold;
            case 3 -> style.uppercase = !style.uppercase;
            case 4 -> style.dividers = !style.dividers;
            case 5 -> style.statusColors = !style.statusColors;
            default -> style.themeAccent = !style.themeAccent;
        }
    }

    private int stepper(int index) {
        return switch (index) {
            case 0 -> style.padding;
            case 1 -> style.rowGap;
            case 2 -> style.radius;
            default -> style.backgroundOpacity;
        };
    }

    private void step(int index, int direction) {
        switch (index) {
            case 0 -> style.padding = HudStyle.clamp(style.padding + direction, 0, 12);
            case 1 -> style.rowGap = HudStyle.clamp(style.rowGap + direction, 0, 12);
            case 2 -> style.radius = HudStyle.clamp(style.radius + direction, 0, 8);
            default -> style.backgroundOpacity = HudStyle.clamp(style.backgroundOpacity + direction * 5, 0, 100);
        }
    }

    private String stepperText(int index) {
        return index == 3 ? stepper(index) + "%" : String.valueOf(stepper(index));
    }

    private int color(int t) {
        return switch (t) {
            case 0 -> style.labelColor;
            case 1 -> style.valueColor;
            case 2 -> style.backgroundColor;
            default -> style.themeAccent ? NovaGui.BRAND & 0xFFFFFF : style.accentColor;
        };
    }

    private void setColor(int rgb) {
        switch (target) {
            case 0 -> style.labelColor = rgb;
            case 1 -> style.valueColor = rgb;
            case 2 -> style.backgroundColor = rgb;
            default -> {
                style.accentColor = rgb;
                style.themeAccent = false;
            }
        }
    }

    private static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue = 0.0F;
        if (delta > 0.0F) {
            if (max == r) {
                hue = ((g - b) / delta) / 6.0F;
            } else if (max == g) {
                hue = ((b - r) / delta + 2.0F) / 6.0F;
            } else {
                hue = ((r - g) / delta + 4.0F) / 6.0F;
            }
            if (hue < 0.0F) {
                hue += 1.0F;
            }
        }
        return new float[]{hue, max <= 0.0F ? 0.0F : delta / max, max};
    }

    private void setSlider(int slider, float value) {
        float v = Math.max(0.0F, Math.min(1.0F, value));
        hsv[slider] = slider == 0 ? Math.min(v, 0.999F) : v;
        setColor(Mth.hsvToRgb(hsv[0] % 1.0F, hsv[1], hsv[2]) & 0xFFFFFF);
    }

    private int trackColor(int slider, float t) {
        return 0xFF000000 | switch (slider) {
            case 0 -> Mth.hsvToRgb(Math.min(t, 0.999F), 1.0F, 1.0F);
            case 1 -> Mth.hsvToRgb(hsv[0], t, Math.max(0.35F, hsv[2]));
            default -> Mth.hsvToRgb(hsv[0], hsv[1], t);
        };
    }

    // ---------------------------------------------------------------- Geometrie

    private int leftX1() {
        return (cx0 + cx1) / 2 - 8;
    }

    private int rightX0() {
        return (cx0 + cx1) / 2 + 8;
    }

    private int layoutY() {
        return cy0;
    }

    private int barY() {
        return cy0 + 20;
    }

    private int stepperY(int index) {
        return cy0 + 44 + index * 18;
    }

    private int toggleY(int index) {
        return stepperY(stepperIds.length) + 6 + index * 15;
    }

    private int choiceX(int index, int count) {
        return cx0 + CHOICE_LABEL + index * (choiceWidth(count) + 4);
    }

    private int choiceWidth(int count) {
        return (leftX1() - cx0 - CHOICE_LABEL - 4 * (count - 1)) / count;
    }

    private int previewY1() {
        return cy0 + 86;
    }

    private int targetY() {
        return previewY1() + 8;
    }

    private int targetX(int index) {
        return rightX0() + index * (targetWidth() + 4);
    }

    private int targetWidth() {
        return (cx1 - rightX0() - 4 * (TARGETS.length - 1)) / TARGETS.length;
    }

    private int presetY() {
        return targetY() + 24;
    }

    private int presetSize() {
        return Math.min(14, (cx1 - rightX0() - 3 * (PRESETS.length - 1)) / PRESETS.length);
    }

    private int presetX(int index) {
        return rightX0() + index * (presetSize() + 3);
    }

    private int sliderY(int slider) {
        return presetY() + presetSize() + 10 + slider * 18;
    }

    private int trackX0() {
        return rightX0() + SLIDER_LABEL;
    }

    private int trackX1() {
        return cx1 - 32;
    }

    private int resetY() {
        return sliderY(SLIDERS.length) + 4;
    }

    private boolean sliderHovered(int slider, double mouseX, double mouseY) {
        int y = sliderY(slider);
        return isHovering((int) mouseX, (int) mouseY, trackX0() - 6, y - 3, trackX1() + 6, y + 11);
    }

    private void setFromMouse(int slider, double mouseX) {
        setSlider(slider, (float) ((mouseX - trackX0()) / (trackX1() - trackX0())));
    }

    private boolean minusHovered(int index, int mx, int my) {
        int x = leftX1() - 60;
        return isHovering(mx, my, x, stepperY(index), x + 14, stepperY(index) + 14);
    }

    private boolean plusHovered(int index, int mx, int my) {
        int x = leftX1() - 14;
        return isHovering(mx, my, x, stepperY(index), x + 14, stepperY(index) + 14);
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            HudStyle.Layout[] layouts = HudStyle.Layout.values();
            for (int i = 0; i < layouts.length; i++) {
                int x = choiceX(i, layouts.length);
                if (isHovering(mx, my, x, layoutY(), x + choiceWidth(layouts.length), layoutY() + 16)) {
                    style.layout = layouts[i];
                    changed();
                    return true;
                }
            }
            HudStyle.Bar[] bars = HudStyle.Bar.values();
            for (int i = 0; i < bars.length; i++) {
                int x = choiceX(i, bars.length);
                if (isHovering(mx, my, x, barY(), x + choiceWidth(bars.length), barY() + 16)) {
                    style.bar = bars[i];
                    changed();
                    return true;
                }
            }
            for (int i = 0; i < stepperIds.length; i++) {
                if (minusHovered(i, mx, my) || plusHovered(i, mx, my)) {
                    step(stepperIds[i], plusHovered(i, mx, my) ? 1 : -1);
                    changed();
                    return true;
                }
            }
            for (int i = 0; i < TOGGLES.length; i++) {
                if (isHovering(mx, my, cx0, toggleY(i), leftX1(), toggleY(i) + 14)) {
                    flip(i);
                    if (i == TOGGLES.length - 1 && target == 3) {
                        hsv = toHsv(color(target));
                    }
                    changed();
                    return true;
                }
            }
            for (int i = 0; i < TARGETS.length; i++) {
                if (isHovering(mx, my, targetX(i), targetY(), targetX(i) + targetWidth(), targetY() + 16)) {
                    target = i;
                    hsv = toHsv(color(target));
                    NovaGui.clickSound();
                    return true;
                }
            }
            for (int i = 0; i < PRESETS.length; i++) {
                int x = presetX(i);
                if (isHovering(mx, my, x, presetY(), x + presetSize(), presetY() + presetSize())) {
                    setColor(PRESETS[i]);
                    hsv = toHsv(PRESETS[i]);
                    changed();
                    return true;
                }
            }
            for (int slider = 0; slider < SLIDERS.length; slider++) {
                if (sliderHovered(slider, event.x(), event.y())) {
                    dragging = slider;
                    setFromMouse(slider, event.x());
                    return true;
                }
            }
            if (isHovering(mx, my, rightX0(), resetY(), cx1, resetY() + 16)) {
                style.reset();
                hsv = toHsv(color(target));
                for (int i = 0; i < TOGGLES.length; i++) {
                    toggleAnim[i] = toggle(i) ? 1.0F : 0.0F;
                }
                changed();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging >= 0) {
            setFromMouse(dragging, event.x());
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        for (int i = 0; i < stepperIds.length; i++) {
            if (isHovering(mx, my, cx0, stepperY(i), leftX1(), stepperY(i) + 14)) {
                step(stepperIds[i], scrollY > 0 ? 1 : -1);
                ModConfig.save();
                return true;
            }
        }
        for (int slider = 0; slider < SLIDERS.length; slider++) {
            if (sliderHovered(slider, mouseX, mouseY)) {
                float stepSize = slider == 0 ? 1.0F / 72.0F : 0.05F;
                setSlider(slider, hsv[slider] + (scrollY > 0 ? stepSize : -stepSize));
                ModConfig.save();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void changed() {
        ModConfig.save();
        NovaGui.clickSound();
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        int lx1 = leftX1();

        // Layout + Akzentleiste
        HudStyle.Layout[] layouts = HudStyle.Layout.values();
        NovaGui.text(graphics, font, "Layout", cx0, layoutY() + 4, NovaGui.TEXT_DIM);
        for (int i = 0; i < layouts.length; i++) {
            int x = choiceX(i, layouts.length);
            int x1 = x + choiceWidth(layouts.length);
            boolean hover = isHovering(mouseX, mouseY, x, layoutY(), x1, layoutY() + 16);
            pointer |= hover;
            NovaControls.choice(graphics, font, x, layoutY(), x1, layoutY() + 16, layouts[i].label, hover, style.layout == layouts[i], accent);
        }
        HudStyle.Bar[] bars = HudStyle.Bar.values();
        NovaGui.text(graphics, font, "Bar", cx0, barY() + 4, NovaGui.TEXT_DIM);
        for (int i = 0; i < bars.length; i++) {
            int x = choiceX(i, bars.length);
            int x1 = x + choiceWidth(bars.length);
            boolean hover = isHovering(mouseX, mouseY, x, barY(), x1, barY() + 16);
            pointer |= hover;
            NovaControls.choice(graphics, font, x, barY(), x1, barY() + 16, bars[i].label, hover, style.bar == bars[i], accent);
        }

        // Zahlenwerte mit - / +
        for (int i = 0; i < stepperIds.length; i++) {
            int y = stepperY(i);
            boolean minus = minusHovered(i, mouseX, mouseY);
            boolean plus = plusHovered(i, mouseX, mouseY);
            pointer |= minus || plus;
            NovaGui.text(graphics, font, STEPPERS[stepperIds[i]], cx0, y + 3, NovaGui.TEXT_DIM);
            NovaControls.choice(graphics, font, lx1 - 60, y, lx1 - 46, y + 14, "-", minus, false, accent);
            NovaGui.centered(graphics, font, stepperText(stepperIds[i]), lx1 - 30, y + 3, NovaGui.TEXT);
            NovaControls.choice(graphics, font, lx1 - 14, y, lx1, y + 14, "+", plus, false, accent);
        }

        // Schalter
        graphics.fill(cx0, toggleY(0) - 4, lx1, toggleY(0) - 3, NovaGui.BORDER);
        for (int i = 0; i < TOGGLES.length; i++) {
            int y = toggleY(i);
            boolean hover = isHovering(mouseX, mouseY, cx0, y, lx1, y + 14);
            pointer |= hover;
            toggleAnim[i] = NovaGui.approach(toggleAnim[i], toggle(i) ? 1.0F : 0.0F, 14.0F, dt);
            NovaGui.text(graphics, font, TOGGLES[i], cx0, y + 3, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            NovaControls.toggle(graphics, lx1 - 22, y + 1, toggleAnim[i], false, 1.0F, accent);
        }

        // Trennlinie zwischen den Spalten
        int mid = (cx0 + cx1) / 2;
        graphics.fill(mid, cy0, mid + 1, cy1, NovaGui.BORDER);

        drawPreview(graphics);

        // Farbe: Ziel waehlen
        for (int i = 0; i < TARGETS.length; i++) {
            int x = targetX(i);
            int x1 = x + targetWidth();
            boolean hover = isHovering(mouseX, mouseY, x, targetY(), x1, targetY() + 16);
            pointer |= hover;
            NovaControls.choice(graphics, font, x, targetY(), x1, targetY() + 16, TARGETS[i], hover, target == i, accent);
        }

        // Vorlagen
        int current = color(target);
        for (int i = 0; i < PRESETS.length; i++) {
            int x = presetX(i);
            int y = presetY();
            int size = presetSize();
            boolean hover = isHovering(mouseX, mouseY, x, y, x + size, y + size);
            pointer |= hover;
            NovaShapes.rect(graphics, x, y, x + size, y + size, 3, 0xFF000000 | PRESETS[i]);
            if (current == PRESETS[i] || hover) {
                NovaShapes.outline(graphics, x - 2, y - 2, x + size + 2, y + size + 2, 4, current == PRESETS[i] ? NovaGui.TEXT : NovaGui.TEXT_MUTED);
            }
        }

        // HSV-Regler
        for (int slider = 0; slider < SLIDERS.length; slider++) {
            int y = sliderY(slider);
            int x0 = trackX0();
            int x1 = trackX1();
            boolean hover = sliderHovered(slider, mouseX, mouseY) || dragging == slider;
            pointer |= hover;
            NovaGui.text(graphics, font, SLIDERS[slider], rightX0(), y + 1, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            for (int i = 0; i < NovaControls.TRACK_PIECES; i++) {
                NovaControls.trackPiece(graphics, x0, y + 2, x1, y + 8, i, trackColor(slider, (float) i / NovaControls.TRACK_PIECES),
                        trackColor(slider, (float) (i + 1) / NovaControls.TRACK_PIECES));
            }
            NovaShapes.outline(graphics, x0 - 1, y + 1, x1 + 1, y + 9, 2, NovaGui.BORDER);
            int kx = x0 + Math.round((x1 - x0) * hsv[slider]);
            NovaControls.knob(graphics, kx, y + 5, hover, accent);
            String value = slider == 0 ? Math.round(hsv[0] * 360.0F) + "°" : Math.round(hsv[slider] * 100.0F) + "%";
            NovaGui.text(graphics, font, value, cx1 - NovaGui.width(font, value), y + 1, NovaGui.TEXT);
        }

        boolean resetHover = isHovering(mouseX, mouseY, rightX0(), resetY(), cx1, resetY() + 16);
        pointer |= resetHover;
        NovaControls.choice(graphics, font, rightX0(), resetY(), cx1, resetY() + 16, "Reset to default", resetHover, false, accent);
        return pointer;
    }

    /** Vorschau auf einem "Spielwelt"-Hintergrund, damit man sieht, wie es ohne Kasten wirkt. */
    private void drawPreview(GuiGraphicsExtractor graphics) {
        int x0 = rightX0();
        int x1 = cx1;
        int y0 = cy0;
        int y1 = previewY1();
        graphics.enableScissor(x0, y0, x1, y1);
        graphics.fillGradient(x0, y0, x1, (y0 + y1) / 2, 0xFF6FA8DC, 0xFFA9CCEB);
        graphics.fillGradient(x0, (y0 + y1) / 2, x1, y1, 0xFF5E8C3A, 0xFF3F5F28);

        List<HudStyle.Row> rows = preview.get();
        int w = style.width(font, rows);
        int h = style.height(rows, headerRows);
        float scale = Math.min(1.0F, Math.min((x1 - x0 - 8) / (float) Math.max(1, w), (y1 - y0 - 8) / (float) Math.max(1, h)));
        graphics.pose().pushMatrix();
        graphics.pose().translate(x0 + 4, y0 + 4);
        graphics.pose().scale(scale, scale);
        style.draw(graphics, font, rows, headerRows, 1.0F);
        graphics.pose().popMatrix();
        graphics.disableScissor();
        NovaShapes.outline(graphics, x0 - 1, y0 - 1, x1 + 1, y1 + 1, 3, NovaGui.BORDER);
    }
}
