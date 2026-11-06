package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.mods.CrosshairMod;

import java.util.Locale;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Crosshair-Designer: links Form, Regler, Schalter und Farbe, rechts die Vorschau.
 * Bei "Custom" ist rechts ein Pixel-Raster: Linksklick malt, Rechtsklick radiert (auch mit Ziehen).
 */
public class CrosshairMenu extends NovaSettingsScreen {
    private static final int LABEL_WIDTH = 62;
    private static final int PREVIEW_WIDTH = 160;
    private static final int ROW = 21;
    private static final int STYLES_PER_ROW = 4;
    private static final String[] SLIDERS = {"Length", "Thickness", "Gap", "Opacity"};
    private static final int[][] RANGES = {{1, 30}, {1, 8}, {0, 20}, {10, 100}};
    private static final Identifier VANILLA_CROSSHAIR = Identifier.withDefaultNamespace("hud/crosshair");

    private int dragging = -1;
    /** Malen im Raster: 1 = setzen, 0 = loeschen, -1 = nicht aktiv. */
    private int painting = -1;
    /** Letzte normale Form (fuer "From shape" im Custom-Raster). */
    private static CrosshairMod.Style lastShape = CrosshairMod.Style.CROSS;
    private EditBox hexBox;

    public CrosshairMenu(Screen parent) {
        super(parent, "Crosshair");
    }

    @Override
    protected void init() {
        super.init();
        hexBox = new EditBox(font, cx0 + LABEL_WIDTH + 6, hexY() + 5, 60, 10, Component.literal("Hex"));
        hexBox.setBordered(false);
        hexBox.setMaxLength(7);
        hexBox.setTextColor(NovaGui.TEXT);
        hexBox.setTextShadow(false);
        hexBox.setValue(hex());
        hexBox.setResponder(value -> {
            String clean = value.trim().replace("#", "");
            if (clean.length() == 6) {
                try {
                    CrosshairMod.color = Integer.parseInt(clean, 16);
                } catch (NumberFormatException ignored) {
                    // unvollstaendig/ungueltig -> Farbe behalten
                }
            }
        });
        NovaGui.themeFont(hexBox);
        addRenderableWidget(hexBox);
    }

    private static String hex() {
        return String.format(Locale.ROOT, "#%06X", CrosshairMod.color & 0xFFFFFF);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        return CrosshairMod.style == CrosshairMod.Style.CUSTOM
                ? "Custom: left click paints, right click erases"
                : "Design your own crosshair";
    }

    // ---------------------------------------------------------------- Werte

    private static int value(int slider) {
        return switch (slider) {
            case 0 -> CrosshairMod.length;
            case 1 -> CrosshairMod.thickness;
            case 2 -> CrosshairMod.gap;
            default -> CrosshairMod.opacity;
        };
    }

    private static void setValue(int slider, int value) {
        int v = Math.max(RANGES[slider][0], Math.min(RANGES[slider][1], value));
        switch (slider) {
            case 0 -> CrosshairMod.length = v;
            case 1 -> CrosshairMod.thickness = v;
            case 2 -> CrosshairMod.gap = v;
            default -> CrosshairMod.opacity = v;
        }
    }

    // ---------------------------------------------------------------- Geometrie links

    private int leftX1() {
        return cx1 - PREVIEW_WIDTH - 12;
    }

    private int styleY(int index) {
        return cy0 + (index / STYLES_PER_ROW) * 20;
    }

    private int styleWidth() {
        return (leftX1() - cx0 - LABEL_WIDTH - 4 * (STYLES_PER_ROW - 1)) / STYLES_PER_ROW;
    }

    private int styleX(int index) {
        return cx0 + LABEL_WIDTH + (index % STYLES_PER_ROW) * (styleWidth() + 4);
    }

    private int sliderY(int index) {
        return cy0 + 44 + index * ROW;
    }

    private int togglesY() {
        return sliderY(SLIDERS.length) + 2;
    }

    private int colorsY() {
        return togglesY() + 22;
    }

    private int hexY() {
        return colorsY() + 22;
    }

    private int trackX0() {
        return cx0 + LABEL_WIDTH;
    }

    private int trackX1() {
        return leftX1() - 30;
    }

    private int halfWidth() {
        return (leftX1() - cx0 - 12) / 2;
    }

    private int swatchX(int index) {
        return cx0 + LABEL_WIDTH + index * 18;
    }

    private void setFromMouse(int slider, double mouseX) {
        float t = (float) ((mouseX - trackX0()) / (trackX1() - trackX0()));
        t = Math.max(0.0F, Math.min(1.0F, t));
        setValue(slider, Math.round(RANGES[slider][0] + t * (RANGES[slider][1] - RANGES[slider][0])));
    }

    // ---------------------------------------------------------------- Geometrie rechts (Vorschau / Raster)

    private int previewX0() {
        return cx1 - PREVIEW_WIDTH;
    }

    /** Unterkante des grossen Bereichs (darunter: Originalgroesse hell/dunkel). */
    private int bigBottom() {
        return cy1 - 44;
    }

    private int cell() {
        int byWidth = PREVIEW_WIDTH / CrosshairMod.GRID;
        int byHeight = (bigBottom() - cy0 - 22) / CrosshairMod.GRID;
        return Math.max(3, Math.min(byWidth, byHeight));
    }

    private int gridX0() {
        return previewX0() + (PREVIEW_WIDTH - cell() * CrosshairMod.GRID) / 2;
    }

    private int gridY0() {
        return cy0;
    }

    private int gridButtonsY() {
        return gridY0() + cell() * CrosshairMod.GRID + 4;
    }

    private int gridButtonWidth() {
        return (PREVIEW_WIDTH - 4) / 2;
    }

    /** Raster-Zelle unter der Maus als {x, y}, oder null. */
    private int[] cellAt(double mouseX, double mouseY) {
        int size = cell();
        int x = (int) Math.floor((mouseX - gridX0()) / size);
        int y = (int) Math.floor((mouseY - gridY0()) / size);
        if (x < 0 || y < 0 || x >= CrosshairMod.GRID || y >= CrosshairMod.GRID) {
            return null;
        }
        return new int[]{x, y};
    }

    private boolean custom() {
        return CrosshairMod.style == CrosshairMod.Style.CUSTOM;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x();
        int my = (int) event.y();

        // Raster: Linksklick malt, Rechtsklick radiert
        if (custom() && (event.button() == 0 || event.button() == 1)) {
            int[] c = cellAt(event.x(), event.y());
            if (c != null) {
                painting = event.button() == 0 ? 1 : 0;
                CrosshairMod.setPixel(c[0], c[1], painting == 1);
                return true;
            }
        }

        if (event.button() == 0) {
            CrosshairMod.Style[] styles = CrosshairMod.Style.values();
            for (int i = 0; i < styles.length; i++) {
                if (isHovering(mx, my, styleX(i), styleY(i), styleX(i) + styleWidth(), styleY(i) + 16)) {
                    selectStyle(styles[i]);
                    changed();
                    return true;
                }
            }
            for (int i = 0; i < SLIDERS.length; i++) {
                int y = sliderY(i);
                if (isHovering(mx, my, trackX0() - 4, y, trackX1() + 4, y + 14)) {
                    dragging = i;
                    setFromMouse(i, event.x());
                    return true;
                }
            }
            if (isHovering(mx, my, cx0, togglesY(), cx0 + halfWidth(), togglesY() + 14)) {
                CrosshairMod.dot = !CrosshairMod.dot;
                changed();
                return true;
            }
            if (isHovering(mx, my, cx0 + halfWidth() + 12, togglesY(), leftX1(), togglesY() + 14)) {
                CrosshairMod.outline = !CrosshairMod.outline;
                changed();
                return true;
            }
            for (int i = 0; i < CrosshairMod.COLORS.length; i++) {
                int x = swatchX(i);
                if (isHovering(mx, my, x, colorsY(), x + 14, colorsY() + 14)) {
                    CrosshairMod.color = CrosshairMod.COLORS[i];
                    hexBox.setValue(hex());
                    changed();
                    return true;
                }
            }
            if (isHovering(mx, my, leftX1() - 70, hexY(), leftX1(), hexY() + 16)) {
                CrosshairMod.reset();
                hexBox.setValue(hex());
                changed();
                return true;
            }
            if (custom()) {
                int by = gridButtonsY();
                if (isHovering(mx, my, previewX0(), by, previewX0() + gridButtonWidth(), by + 16)) {
                    CrosshairMod.clearPixels();
                    changed();
                    return true;
                }
                if (isHovering(mx, my, cx1 - gridButtonWidth(), by, cx1, by + 16)) {
                    CrosshairMod.rasterize(lastShape);
                    changed();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static void selectStyle(CrosshairMod.Style style) {
        if (style != CrosshairMod.Style.CUSTOM && style != CrosshairMod.Style.DEFAULT) {
            lastShape = style;
        }
        CrosshairMod.style = style;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (painting >= 0) {
            int[] c = cellAt(event.x(), event.y());
            if (c != null) {
                CrosshairMod.setPixel(c[0], c[1], painting == 1);
            }
            return true;
        }
        if (dragging >= 0) {
            setFromMouse(dragging, event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (painting >= 0 || dragging >= 0) {
            painting = -1;
            dragging = -1;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (int i = 0; i < SLIDERS.length; i++) {
            int y = sliderY(i);
            if (isHovering((int) mouseX, (int) mouseY, cx0, y, leftX1(), y + 14)) {
                setValue(i, value(i) + (scrollY > 0 ? 1 : -1));
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

        // Form (zwei Reihen)
        NovaGui.text(graphics, font, "Style", cx0, cy0 + 4, NovaGui.TEXT_DIM);
        CrosshairMod.Style[] styles = CrosshairMod.Style.values();
        for (int i = 0; i < styles.length; i++) {
            int x = styleX(i);
            int y = styleY(i);
            boolean hover = isHovering(mouseX, mouseY, x, y, x + styleWidth(), y + 16);
            pointer |= hover;
            NovaControls.choice(graphics, font, x, y, x + styleWidth(), y + 16, styles[i].label, hover, CrosshairMod.style == styles[i], accent);
        }

        // Regler (bei Default/Custom ohne Wirkung auf die Form -> gedimmt)
        boolean shapeSliders = CrosshairMod.style != CrosshairMod.Style.DEFAULT && !custom();
        for (int i = 0; i < SLIDERS.length; i++) {
            int y = sliderY(i);
            boolean relevant = shapeSliders || i == 3 && CrosshairMod.style != CrosshairMod.Style.DEFAULT;
            boolean hover = isHovering(mouseX, mouseY, trackX0() - 4, y, trackX1() + 4, y + 14) || dragging == i;
            pointer |= hover;
            NovaGui.text(graphics, font, SLIDERS[i], cx0, y + 3,
                    hover ? NovaGui.TEXT : relevant ? NovaGui.TEXT_DIM : NovaGui.TEXT_MUTED);
            int ty = y + 5;
            float t = (value(i) - RANGES[i][0]) / (float) (RANGES[i][1] - RANGES[i][0]);
            int kx = trackX0() + Math.round((trackX1() - trackX0()) * t);
            NovaShapes.rect(graphics, trackX0(), ty + 1, trackX1(), ty + 3, 1, NovaGui.TRACK);
            NovaShapes.rect(graphics, trackX0(), ty, kx, ty + 4, 2, relevant ? accent : NovaGui.TEXT_MUTED);
            NovaShapes.rect(graphics, kx - 4, ty - 3, kx + 4, ty + 7, 4, relevant ? NovaGui.TEXT : NovaGui.TEXT_MUTED);
            String text = i == 3 ? value(i) + "%" : value(i) + "px";
            NovaGui.text(graphics, font, text, leftX1() - NovaGui.width(font, text), y + 3, relevant ? NovaGui.TEXT : NovaGui.TEXT_MUTED);
        }

        // Schalter
        int y = togglesY();
        boolean dotHover = isHovering(mouseX, mouseY, cx0, y, cx0 + halfWidth(), y + 14);
        boolean outlineHover = isHovering(mouseX, mouseY, cx0 + halfWidth() + 12, y, leftX1(), y + 14);
        pointer |= dotHover || outlineHover;
        NovaGui.text(graphics, font, "Center dot", cx0, y + 3, dotHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(graphics, cx0 + halfWidth() - 22, y + 1, CrosshairMod.dot ? 1.0F : 0.0F, false, 1.0F, accent);
        NovaGui.text(graphics, font, "Outline", cx0 + halfWidth() + 12, y + 3, outlineHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(graphics, leftX1() - 22, y + 1, CrosshairMod.outline ? 1.0F : 0.0F, false, 1.0F, accent);

        // Farben
        NovaGui.text(graphics, font, "Color", cx0, colorsY() + 3, NovaGui.TEXT_DIM);
        for (int i = 0; i < CrosshairMod.COLORS.length; i++) {
            int x = swatchX(i);
            boolean hover = isHovering(mouseX, mouseY, x, colorsY(), x + 14, colorsY() + 14);
            boolean selected = (CrosshairMod.color & 0xFFFFFF) == CrosshairMod.COLORS[i];
            pointer |= hover;
            NovaShapes.rect(graphics, x, colorsY(), x + 14, colorsY() + 14, 3, 0xFF000000 | CrosshairMod.COLORS[i]);
            if (selected || hover) {
                NovaShapes.outline(graphics, x - 2, colorsY() - 2, x + 16, colorsY() + 16, 4, selected ? NovaGui.TEXT : NovaGui.TEXT_MUTED);
            }
        }

        // Hex + Zuruecksetzen (zurueck zum Minecraft-Fadenkreuz)
        NovaGui.text(graphics, font, "Hex", cx0, hexY() + 5, NovaGui.TEXT_DIM);
        drawField(graphics, hexBox, "#RRGGBB");
        boolean resetHover = isHovering(mouseX, mouseY, leftX1() - 70, hexY(), leftX1(), hexY() + 16);
        pointer |= resetHover;
        NovaControls.choice(graphics, font, leftX1() - 70, hexY(), leftX1(), hexY() + 16, "Reset", resetHover, false, accent);

        if (custom()) {
            pointer |= drawGrid(graphics, mouseX, mouseY);
        } else {
            drawZoomPreview(graphics);
        }
        drawActualSize(graphics);
        return pointer;
    }

    /** Pixel-Raster fuer "Custom": Hintergrund, gesetzte Pixel in der Farbe, Mittellinien, Maus-Zelle. */
    private boolean drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int size = cell();
        int grid = CrosshairMod.GRID;
        int x0 = gridX0();
        int y0 = gridY0();
        int x1 = x0 + size * grid;
        int y1 = y0 + size * grid;
        int pixelColor = 0xFF000000 | CrosshairMod.color;

        graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, NovaGui.BORDER);
        for (int gy = 0; gy < grid; gy++) {
            for (int gx = 0; gx < grid; gx++) {
                int cx = x0 + gx * size;
                int cy = y0 + gy * size;
                boolean checker = (gx + gy) % 2 == 0;
                int bg = checker ? 0xFF22222A : 0xFF1B1B22;
                graphics.fill(cx, cy, cx + size, cy + size, CrosshairMod.pixel(gx, gy) ? pixelColor : bg);
            }
        }
        // Mitte markieren
        int mid = grid / 2;
        graphics.fill(x0 + mid * size, y0, x0 + mid * size + size, y1, 0x14FFFFFF);
        graphics.fill(x0, y0 + mid * size, x1, y0 + mid * size + size, 0x14FFFFFF);

        boolean pointer = false;
        int[] hovered = cellAt(mouseX, mouseY);
        if (hovered != null) {
            int hx = x0 + hovered[0] * size;
            int hy = y0 + hovered[1] * size;
            NovaShapes.outline(graphics, hx - 1, hy - 1, hx + size + 1, hy + size + 1, 1, NovaGui.TEXT);
            pointer = true;
        }

        int by = gridButtonsY();
        boolean clearHover = isHovering(mouseX, mouseY, previewX0(), by, previewX0() + gridButtonWidth(), by + 16);
        boolean fromHover = isHovering(mouseX, mouseY, cx1 - gridButtonWidth(), by, cx1, by + 16);
        NovaControls.choice(graphics, font, previewX0(), by, previewX0() + gridButtonWidth(), by + 16, "Clear", clearHover, false, NovaGui.DANGER);
        NovaControls.choice(graphics, font, cx1 - gridButtonWidth(), by, cx1, by + 16, "From " + lastShape.label, fromHover, false, accent);
        return pointer || clearHover || fromHover;
    }

    /** Grosse Vorschau (4 Pixel pro Fadenkreuz-Pixel) vor einem Himmel-/Boden-Verlauf. */
    private void drawZoomPreview(GuiGraphicsExtractor graphics) {
        int x0 = previewX0();
        int y0 = cy0;
        int x1 = cx1;
        int y1 = bigBottom();
        graphics.fillGradient(x0, y0, x1, y1, 0xFF5B8CC9, 0xFF3A5A30);
        NovaShapes.outline(graphics, x0, y0, x1, y1, 4, NovaGui.BORDER);
        float guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        float cx = (x0 + x1) / 2.0F;
        float cy = (y0 + y1) / 2.0F;
        graphics.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        if (CrosshairMod.style == CrosshairMod.Style.DEFAULT) {
            drawVanilla(graphics, cx, cy, 4.0F);
        } else {
            CrosshairMod.draw(graphics, cx, cy, 4.0F / guiScale);
        }
        graphics.disableScissor();
        NovaGui.text(graphics, font, CrosshairMod.style == CrosshairMod.Style.DEFAULT ? "4x  Minecraft default" : "4x",
                x0 + 5, y0 + 4, 0xC0FFFFFF);
    }

    /** Originalgroesse auf hellem und dunklem Grund. */
    private void drawActualSize(GuiGraphicsExtractor graphics) {
        int x0 = previewX0();
        int x1 = cx1;
        int y1 = cy1;
        int half = (x1 - x0 - 6) / 2;
        int by0 = bigBottom() + 6;
        graphics.fill(x0, by0, x0 + half, y1, 0xFFB8C4CC);
        graphics.fill(x0 + half + 6, by0, x1, y1, 0xFF1A1A20);
        float cyA = (by0 + y1) / 2.0F;
        float left = x0 + half / 2.0F;
        float right = x0 + half + 6 + half / 2.0F;
        if (CrosshairMod.style == CrosshairMod.Style.DEFAULT) {
            drawVanilla(graphics, left, cyA, 1.0F);
            drawVanilla(graphics, right, cyA, 1.0F);
        } else {
            float unit = 1.0F / Minecraft.getInstance().getWindow().getGuiScale();
            CrosshairMod.draw(graphics, left, cyA, unit);
            CrosshairMod.draw(graphics, right, cyA, unit);
        }
    }

    /** Das normale Minecraft-Fadenkreuz (15x15 Sprite) um (cx, cy), optional vergroessert. */
    private static void drawVanilla(GuiGraphicsExtractor graphics, float cx, float cy, float zoom) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx, cy);
        graphics.pose().scale(zoom, zoom);
        graphics.blitSprite(RenderPipelines.CROSSHAIR, VANILLA_CROSSHAIR, -8, -8, 15, 15);
        graphics.pose().popMatrix();
    }
}
