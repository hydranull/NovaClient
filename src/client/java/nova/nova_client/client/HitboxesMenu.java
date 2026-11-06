package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;
import nova.nova_client.client.mods.HitboxesMod;

import java.util.List;
import java.util.function.Supplier;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer Hitboxes. Links: Farbe von Kasten / Augenlinie / Blickrichtung (Vorlagen oder frei per
 * Farbton/Saettigung/Helligkeit), Teamfarben. Rechts: Deckkraft, Fuellung, Linienstaerke, Glow und
 * Schalter (durch Waende, Augenlinie, Blickrichtung, eigene Hitbox).
 */
public class HitboxesMenu extends NovaSettingsScreen {
    private static final int[] PRESETS = {
            0xFFFFFF, 0xFF4D6A, 0xFF7A1A, 0xFFE44D, 0x5DFF6A, 0x2EE6C8, 0x3DB8FF, 0x5B6BFF, 0xB45CF6, 0xFF5FD2,
    };
    private static final String[] TARGETS = {"Box", "Eye line", "Look"};
    private static final String[] SLIDERS = {"Hue", "Saturation", "Brightness", "Opacity", "Fill", "Line width",
            "Glow", "Glow size"};
    private static final int HUE = 0;
    private static final int SATURATION = 1;
    private static final int BRIGHTNESS = 2;
    private static final int OPACITY = 3;
    private static final int FILL = 4;
    private static final int WIDTH = 5;
    private static final int GLOW = 6;
    private static final int GLOW_SIZE = 7;
    private static final int COLOR_SLIDERS = 3;
    private static final int ROW = 22;
    private static final int TOGGLE_ROW = 16;

    private record Toggle(String label, boolean right, Supplier<Boolean> get, Runnable flip) {
    }

    private final List<Toggle> toggles = List.of(
            new Toggle("Team colors", false, () -> HitboxesMod.teamColors,
                    () -> HitboxesMod.teamColors = !HitboxesMod.teamColors),
            new Toggle("Through walls", true, () -> HitboxesMod.throughWalls,
                    () -> HitboxesMod.throughWalls = !HitboxesMod.throughWalls),
            new Toggle("Eye line", true, () -> HitboxesMod.showEye, () -> HitboxesMod.showEye = !HitboxesMod.showEye),
            new Toggle("Look direction", true, () -> HitboxesMod.showLook,
                    () -> HitboxesMod.showLook = !HitboxesMod.showLook),
            new Toggle("Own hitbox (F5)", true, () -> HitboxesMod.showSelf,
                    () -> HitboxesMod.showSelf = !HitboxesMod.showSelf));

    /** Welche Farbe gerade bearbeitet wird: 0 = Kasten, 1 = Augenlinie, 2 = Blickrichtung. */
    private int editing;
    /** HSV je Farbe (eigener Zustand, damit der Farbton bei Grau/Weiss nicht verloren geht). */
    private final float[][] hsv = new float[3][];
    private int dragging = -1;

    public HitboxesMenu(Screen parent) {
        super(parent, "Hitboxes");
        for (int i = 0; i < 3; i++) {
            hsv[i] = toHsv(color(i));
        }
    }

    @Override
    protected void init() {
        super.init();
        setPanel(460, 194);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    // ---------------------------------------------------------------- Farbe

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

    private static int color(int target) {
        return switch (target) {
            case 1 -> HitboxesMod.eyeColor;
            case 2 -> HitboxesMod.lookColor;
            default -> HitboxesMod.boxColor;
        };
    }

    private static void storeColor(int target, int rgb) {
        switch (target) {
            case 1 -> HitboxesMod.eyeColor = rgb;
            case 2 -> HitboxesMod.lookColor = rgb;
            default -> HitboxesMod.boxColor = rgb;
        }
    }

    private void setColor(int rgb) {
        hsv[editing] = toHsv(rgb);
        storeColor(editing, rgb);
    }

    // ---------------------------------------------------------------- Regler-Werte (0..1)

    private float sliderValue(int slider) {
        return switch (slider) {
            case HUE, SATURATION, BRIGHTNESS -> hsv[editing][slider];
            case OPACITY -> HitboxesMod.opacity / 100.0F;
            case FILL -> HitboxesMod.fillOpacity / 100.0F;
            case WIDTH -> (HitboxesMod.lineWidth - HitboxesMod.MIN_WIDTH) / (HitboxesMod.MAX_WIDTH - HitboxesMod.MIN_WIDTH);
            case GLOW -> HitboxesMod.glow / 100.0F;
            default -> (HitboxesMod.glowSize - HitboxesMod.MIN_GLOW_SIZE)
                    / (float) (HitboxesMod.MAX_GLOW_SIZE - HitboxesMod.MIN_GLOW_SIZE);
        };
    }

    private void setSliderValue(int slider, float value) {
        float v = Mth.clamp(value, 0.0F, 1.0F);
        switch (slider) {
            case HUE, SATURATION, BRIGHTNESS -> {
                hsv[editing][slider] = slider == HUE ? Math.min(v, 0.999F) : v;
                storeColor(editing, Mth.hsvToRgb(hsv[editing][HUE] % 1.0F, hsv[editing][SATURATION],
                        hsv[editing][BRIGHTNESS]) & 0xFFFFFF);
            }
            case OPACITY -> HitboxesMod.opacity = Math.round(v * 100.0F);
            case FILL -> HitboxesMod.fillOpacity = Math.round(v * 100.0F);
            case WIDTH -> HitboxesMod.lineWidth = Math.round((HitboxesMod.MIN_WIDTH
                    + v * (HitboxesMod.MAX_WIDTH - HitboxesMod.MIN_WIDTH)) * 10.0F) / 10.0F;
            case GLOW -> HitboxesMod.glow = Math.round(v * 100.0F);
            default -> HitboxesMod.glowSize = HitboxesMod.MIN_GLOW_SIZE
                    + Math.round(v * (HitboxesMod.MAX_GLOW_SIZE - HitboxesMod.MIN_GLOW_SIZE));
        }
    }

    private String sliderText(int slider) {
        return switch (slider) {
            case HUE -> Math.round(hsv[editing][HUE] * 360.0F) + "°";
            case SATURATION, BRIGHTNESS -> Math.round(hsv[editing][slider] * 100.0F) + "%";
            case OPACITY -> HitboxesMod.opacity + "%";
            case FILL -> HitboxesMod.fillOpacity == 0 ? "Off" : HitboxesMod.fillOpacity + "%";
            case WIDTH -> HitboxesMod.lineWidth + "px";
            case GLOW -> HitboxesMod.glow == 0 ? "Off" : HitboxesMod.glow + "%";
            default -> HitboxesMod.glowSize + "px";
        };
    }

    /** Farbe der Spur an Stelle t (0..1). */
    private int trackColor(int slider, float t) {
        float[] value = hsv[editing];
        return switch (slider) {
            case HUE -> 0xFF000000 | Mth.hsvToRgb(Math.min(t, 0.999F), 1.0F, 1.0F);
            case SATURATION -> 0xFF000000 | Mth.hsvToRgb(value[HUE], t, Math.max(0.35F, value[BRIGHTNESS]));
            case BRIGHTNESS -> 0xFF000000 | Mth.hsvToRgb(value[HUE], value[SATURATION], t);
            case OPACITY, FILL -> NovaGui.mix(NovaGui.SWITCH_OFF, 0xFF000000 | HitboxesMod.boxColor, t);
            default -> NovaGui.mix(NovaGui.SWITCH_OFF, accent, t);
        };
    }

    // ---------------------------------------------------------------- Geometrie

    private int mid() {
        return (cx0 + cx1) / 2;
    }

    private int columnX0(boolean right) {
        return right ? mid() + 10 : cx0;
    }

    private int columnX1(boolean right) {
        return right ? cx1 : mid() - 10;
    }

    private int targetWidth() {
        return (columnX1(false) - cx0 - 8) / 3;
    }

    private int targetX(int target) {
        return cx0 + target * (targetWidth() + 4);
    }

    private int presetY() {
        return cy0 + 32;
    }

    private int presetSize() {
        return Math.min(14, (columnX1(false) - cx0 - 4 * (PRESETS.length - 1)) / PRESETS.length);
    }

    private int presetX(int index) {
        return cx0 + index * (presetSize() + 4);
    }

    private boolean isRight(int slider) {
        return slider >= COLOR_SLIDERS;
    }

    private int sliderY(int slider) {
        return isRight(slider) ? cy0 + 2 + (slider - COLOR_SLIDERS) * ROW : cy0 + 56 + slider * ROW;
    }

    private int trackX0(int slider) {
        return columnX0(isRight(slider)) + 62;
    }

    private int trackX1(int slider) {
        return columnX1(isRight(slider)) - 36;
    }

    private int toggleY(int index) {
        Toggle toggle = toggles.get(index);
        int before = 0;
        for (int i = 0; i < index; i++) {
            if (toggles.get(i).right() == toggle.right()) {
                before++;
            }
        }
        int top = toggle.right() ? sliderY(GLOW_SIZE) + ROW + 4 : sliderY(BRIGHTNESS) + ROW + 4;
        return top + before * TOGGLE_ROW;
    }

    private boolean sliderHovered(int slider, double mouseX, double mouseY) {
        int y = sliderY(slider);
        return isHovering((int) mouseX, (int) mouseY, trackX0(slider) - 6, y - 3, trackX1(slider) + 6, y + 11);
    }

    private boolean toggleHovered(int index, int mouseX, int mouseY) {
        boolean right = toggles.get(index).right();
        int y = toggleY(index);
        return isHovering(mouseX, mouseY, columnX0(right), y, columnX1(right), y + 14);
    }

    private void setFromMouse(int slider, double mouseX) {
        setSliderValue(slider, (float) ((mouseX - trackX0(slider)) / (trackX1(slider) - trackX0(slider))));
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            for (int target = 0; target < TARGETS.length; target++) {
                int x = targetX(target);
                if (isHovering(mx, my, x, cy0, x + targetWidth(), cy0 + 24)) {
                    editing = target;
                    NovaGui.clickSound();
                    return true;
                }
            }
            for (int i = 0; i < PRESETS.length; i++) {
                int x = presetX(i);
                if (isHovering(mx, my, x, presetY(), x + presetSize(), presetY() + presetSize())) {
                    setColor(PRESETS[i]);
                    ModConfig.save();
                    NovaGui.clickSound();
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
            for (int i = 0; i < toggles.size(); i++) {
                if (toggleHovered(i, mx, my)) {
                    toggles.get(i).flip().run();
                    ModConfig.save();
                    NovaGui.clickSound();
                    return true;
                }
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
        for (int slider = 0; slider < SLIDERS.length; slider++) {
            if (sliderHovered(slider, mouseX, mouseY)) {
                float step = slider == HUE ? 1.0F / 72.0F : slider == GLOW_SIZE ? 1.0F / 11.0F : 0.05F;
                setSliderValue(slider, sliderValue(slider) + (scrollY > 0 ? step : -step));
                ModConfig.save();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;

        // Trennlinie zwischen den Spalten
        graphics.fill(mid(), cy0, mid() + 1, cy1, NovaGui.BORDER);

        // Welche Farbe wird bearbeitet?
        for (int target = 0; target < TARGETS.length; target++) {
            int x0 = targetX(target);
            int x1 = x0 + targetWidth();
            boolean hover = isHovering(mouseX, mouseY, x0, cy0, x1, cy0 + 24);
            boolean selected = editing == target;
            pointer |= hover;
            NovaShapes.rect(graphics, x0, cy0, x1, cy0 + 24, 5, hover ? NovaGui.RAISED : NovaGui.CARD);
            NovaShapes.outline(graphics, x0, cy0, x1, cy0 + 24, 5, selected ? accent : NovaGui.BORDER);
            int swatch = 0xFF000000 | color(target);
            NovaShapes.rect(graphics, x0 + 6, cy0 + 7, x0 + 16, cy0 + 17, 3, swatch);
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, TARGETS[target], x1 - x0 - 24), x0 + 20, cy0 + 8,
                    selected ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        }

        // Farb-Vorlagen
        int current = color(editing);
        for (int i = 0; i < PRESETS.length; i++) {
            int x = presetX(i);
            int y = presetY();
            int size = presetSize();
            boolean hover = isHovering(mouseX, mouseY, x, y, x + size, y + size);
            pointer |= hover;
            NovaShapes.rect(graphics, x, y, x + size, y + size, 4, 0xFF000000 | PRESETS[i]);
            if (current == PRESETS[i] || hover) {
                NovaShapes.outline(graphics, x - 2, y - 2, x + size + 2, y + size + 2, 5, current == PRESETS[i] ? NovaGui.TEXT : NovaGui.TEXT_MUTED);
            }
        }

        // Regler
        for (int slider = 0; slider < SLIDERS.length; slider++) {
            int y = sliderY(slider);
            int x0 = trackX0(slider);
            int x1 = trackX1(slider);
            boolean hover = sliderHovered(slider, mouseX, mouseY) || dragging == slider;
            pointer |= hover;
            NovaGui.text(graphics, font, SLIDERS[slider], columnX0(isRight(slider)), y + 1,
                    hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            for (int i = 0; i < NovaControls.TRACK_PIECES; i++) {
                NovaControls.trackPiece(graphics, x0, y + 2, x1, y + 8, i, trackColor(slider, (float) i / NovaControls.TRACK_PIECES),
                        trackColor(slider, (float) (i + 1) / NovaControls.TRACK_PIECES));
            }
            NovaShapes.outline(graphics, x0 - 1, y + 1, x1 + 1, y + 9, 2, NovaGui.BORDER);
            int kx = x0 + Math.round((x1 - x0) * sliderValue(slider));
            int knob = slider < COLOR_SLIDERS ? trackColor(slider, sliderValue(slider)) : NovaGui.TEXT;
            NovaControls.knob(graphics, kx, y + 5, hover, accent);
            String value = sliderText(slider);
            NovaGui.text(graphics, font, value, columnX1(isRight(slider)) - NovaGui.width(font, value), y + 1, NovaGui.TEXT);
        }

        // Schalter
        for (int i = 0; i < toggles.size(); i++) {
            Toggle toggle = toggles.get(i);
            int y = toggleY(i);
            boolean hover = toggleHovered(i, mouseX, mouseY);
            pointer |= hover;
            NovaGui.text(graphics, font, toggle.label(), columnX0(toggle.right()), y + 3,
                    hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            NovaControls.toggle(graphics, columnX1(toggle.right()) - 22, y + 1, toggle.get().get() ? 1.0F : 0.0F, false, 1.0F, accent);
        }
        return pointer;
    }
}
