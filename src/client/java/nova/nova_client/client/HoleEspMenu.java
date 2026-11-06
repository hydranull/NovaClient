package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;
import nova.nova_client.client.mods.HoleEspMod;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer HoleESP: 1x1 und 1x3 einzeln an/aus mit eigener Farbe (Vorlagen oder frei per
 * Farbton/Saettigung/Helligkeit) und Deckkraft der Fuellung.
 */
public class HoleEspMenu extends NovaSettingsScreen {
    private static final int[] PRESETS = {
            0xFF4D6A, 0xFF7A1A, 0xFFB000, 0xFFE44D, 0x5DFF6A, 0x2EE6C8, 0x3DB8FF, 0x5B6BFF, 0xB45CF6, 0xFF5FD2,
            0xFFFFFF, 0x808080,
    };
    private static final String[] SLIDERS = {"Hue", "Saturation", "Brightness", "Opacity"};
    private static final int HUE = 0;
    private static final int SATURATION = 1;
    private static final int BRIGHTNESS = 2;
    private static final int OPACITY = 3;

    /** Welche Farbe gerade bearbeitet wird: 0 = 1x1, 1 = 1x3. */
    private int editing;
    /** HSV je Typ (als eigener Zustand, damit der Farbton bei Grau/Schwarz nicht verloren geht). */
    private final float[][] hsv = new float[2][];
    private int dragging = -1;

    public HoleEspMenu(Screen parent) {
        super(parent, "HoleESP");
        hsv[0] = toHsv(HoleEspMod.color1x1);
        hsv[1] = toHsv(HoleEspMod.color1x3);
    }

    @Override
    protected String subtitle() {
        return "1x1 / 1x3 shafts, 4+ blocks, up to 100 below surface";
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

    private static int fromHsv(float[] value) {
        return Mth.hsvToRgb(value[0] % 1.0F, value[1], value[2]) & 0xFFFFFF;
    }

    private int color(int type) {
        return type == 0 ? HoleEspMod.color1x1 : HoleEspMod.color1x3;
    }

    private void applyColor() {
        int rgb = fromHsv(hsv[editing]);
        if (editing == 0) {
            HoleEspMod.color1x1 = rgb;
        } else {
            HoleEspMod.color1x3 = rgb;
        }
    }

    private void setColor(int rgb) {
        hsv[editing] = toHsv(rgb);
        if (editing == 0) {
            HoleEspMod.color1x1 = rgb;
        } else {
            HoleEspMod.color1x3 = rgb;
        }
    }

    private float sliderValue(int slider) {
        return slider == OPACITY ? HoleEspMod.opacity / 100.0F : hsv[editing][slider];
    }

    private void setSliderValue(int slider, float value) {
        float v = Math.max(0.0F, Math.min(1.0F, value));
        if (slider == OPACITY) {
            HoleEspMod.setOpacity(Math.round(v * 100.0F));
        } else {
            hsv[editing][slider] = slider == HUE ? Math.min(v, 0.999F) : v;
            applyColor();
        }
    }

    private String sliderText(int slider) {
        return switch (slider) {
            case HUE -> Math.round(hsv[editing][HUE] * 360.0F) + "°";
            case OPACITY -> HoleEspMod.opacity + "%";
            default -> Math.round(hsv[editing][slider] * 100.0F) + "%";
        };
    }

    /** Farbe der Spur an Stelle t (0..1). */
    private int trackColor(int slider, float t) {
        float[] value = hsv[editing];
        return 0xFF000000 | switch (slider) {
            case HUE -> Mth.hsvToRgb(Math.min(t, 0.999F), 1.0F, 1.0F);
            case SATURATION -> Mth.hsvToRgb(value[HUE], t, Math.max(0.35F, value[BRIGHTNESS]));
            case BRIGHTNESS -> Mth.hsvToRgb(value[HUE], value[SATURATION], t);
            default -> NovaGui.mix(NovaGui.SWITCH_OFF, 0xFF000000 | color(editing), t) & 0xFFFFFF;
        };
    }

    // ---------------------------------------------------------------- Geometrie

    private int cardWidth() {
        return (cx1 - cx0 - 6) / 2;
    }

    private int cardX(int type) {
        return cx0 + type * (cardWidth() + 6);
    }

    private int cardToggleX(int type) {
        return cardX(type) + cardWidth() - 30;
    }

    private int presetY() {
        return cy0 + 44;
    }

    private int presetSize() {
        return Math.min(18, (cx1 - cx0 - 70 - 4 * (PRESETS.length - 1)) / PRESETS.length);
    }

    private int presetX(int index) {
        return cx0 + 70 + index * (presetSize() + 4);
    }

    private int sliderY(int slider) {
        return cy0 + 74 + slider * 22 + (slider == OPACITY ? 10 : 0);
    }

    private int trackX0() {
        return cx0 + 76;
    }

    private int trackX1() {
        return cx1 - 44;
    }

    private boolean sliderHovered(int slider, double mouseX, double mouseY) {
        int y = sliderY(slider);
        return isHovering((int) mouseX, (int) mouseY, trackX0() - 6, y - 3, trackX1() + 6, y + 11);
    }

    private void setFromMouse(int slider, double mouseX) {
        setSliderValue(slider, (float) ((mouseX - trackX0()) / (trackX1() - trackX0())));
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            for (int type = 0; type < 2; type++) {
                int x = cardX(type);
                if (isHovering(mx, my, cardToggleX(type) - 4, cy0, x + cardWidth(), cy0 + 32)) {
                    if (type == 0) {
                        HoleEspMod.show1x1 = !HoleEspMod.show1x1;
                    } else {
                        HoleEspMod.show1x3 = !HoleEspMod.show1x3;
                    }
                    ModConfig.save();
                    NovaGui.clickSound();
                    return true;
                }
                if (isHovering(mx, my, x, cy0, x + cardWidth(), cy0 + 32)) {
                    editing = type;
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
                float step = slider == HUE ? 1.0F / 72.0F : 0.05F;
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

        // Karten: 1x1 / 1x3 (Klick = Farbe bearbeiten, Schalter = anzeigen)
        String[] names = {"1x1 holes", "1x3 holes"};
        boolean[] shown = {HoleEspMod.show1x1, HoleEspMod.show1x3};
        for (int type = 0; type < 2; type++) {
            int x0 = cardX(type);
            int x1 = x0 + cardWidth();
            boolean hover = isHovering(mouseX, mouseY, x0, cy0, x1, cy0 + 32);
            boolean selected = editing == type;
            pointer |= hover;
            NovaShapes.rect(graphics, x0, cy0, x1, cy0 + 32, 5, hover ? NovaGui.RAISED : NovaGui.CARD);
            NovaShapes.outline(graphics, x0, cy0, x1, cy0 + 32, 5, selected ? accent : NovaGui.BORDER);
            int swatch = 0xFF000000 | color(type);
            NovaShapes.rect(graphics, x0 + 8, cy0 + 8, x0 + 24, cy0 + 24, 4, swatch);
            NovaGui.text(graphics, font, names[type], x0 + 32, cy0 + 8, selected ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            NovaGui.text(graphics, font, selected ? "editing color" : "click to edit", x0 + 32, cy0 + 19,
                    NovaGui.TEXT_MUTED);
            NovaControls.toggle(graphics, cardToggleX(type), cy0 + 10, shown[type] ? 1.0F : 0.0F, false, 1.0F, accent);
        }

        // Farb-Vorlagen
        NovaGui.text(graphics, font, "Presets", cx0 + 2, presetY() + presetSize() / 2 - 4, NovaGui.TEXT_DIM);
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
            int x0 = trackX0();
            int x1 = trackX1();
            boolean hover = sliderHovered(slider, mouseX, mouseY) || dragging == slider;
            pointer |= hover;
            if (slider == OPACITY) {
                graphics.fill(cx0, y - 9, cx1, y - 8, NovaGui.BORDER);
            }
            NovaGui.text(graphics, font, SLIDERS[slider], cx0 + 2, y + 1, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            for (int i = 0; i < NovaControls.TRACK_PIECES; i++) {
                NovaControls.trackPiece(graphics, x0, y + 2, x1, y + 8, i, trackColor(slider, (float) i / NovaControls.TRACK_PIECES),
                        trackColor(slider, (float) (i + 1) / NovaControls.TRACK_PIECES));
            }
            NovaShapes.outline(graphics, x0 - 1, y + 1, x1 + 1, y + 9, 2, NovaGui.BORDER);
            int kx = x0 + Math.round((x1 - x0) * sliderValue(slider));
            int knob = slider == OPACITY ? NovaGui.TEXT : trackColor(slider, sliderValue(slider));
            NovaControls.knob(graphics, kx, y + 5, hover, accent);
            String value = sliderText(slider);
            NovaGui.text(graphics, font, value, cx1 - NovaGui.width(font, value), y + 1, NovaGui.TEXT);
        }

        return pointer;
    }
}
