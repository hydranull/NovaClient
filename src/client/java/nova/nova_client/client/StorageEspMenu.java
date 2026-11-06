package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import nova.nova_client.client.mods.StorageEspMod;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer Storage ESP: die Lager-Bloecke einzeln an/aus (Karten mit Icon), Alle/Keine, Tracer, Deckkraft
 * der Fuellung und (nur Storage ESP) die Reichweite.
 */
public class StorageEspMenu extends NovaSettingsScreen {
    private static final int COLUMNS = 4;
    private static final int CARD_H = 24;
    private static final int GAP = 4;
    private static final StorageEspMod.Type[] TYPES = StorageEspMod.Type.values();

    /** Wessen Einstellungen das Menue bearbeitet (Storage ESP oder Advanced Stash Finder). */
    public interface Target {
        java.util.Set<StorageEspMod.Type> selected();

        void toggle(StorageEspMod.Type type);

        void selectAll(boolean all);

        boolean tracers();

        void setTracers(boolean on);

        int opacity();

        void setOpacity(int percent);

        /** Reichweite in Bloecken, -1 = kein Regler. */
        default int range() {
            return -1;
        }

        default void setRange(int blocks) {
        }
    }

    /** Storage ESP selbst. */
    private static final Target STORAGE_ESP = new Target() {
        public java.util.Set<StorageEspMod.Type> selected() {
            return StorageEspMod.SELECTED;
        }

        public void toggle(StorageEspMod.Type type) {
            StorageEspMod.toggle(type);
        }

        public void selectAll(boolean all) {
            StorageEspMod.selectAll(all);
        }

        public boolean tracers() {
            return StorageEspMod.tracers;
        }

        public void setTracers(boolean on) {
            StorageEspMod.tracers = on;
        }

        public int opacity() {
            return StorageEspMod.opacity;
        }

        public void setOpacity(int percent) {
            StorageEspMod.setOpacity(percent);
        }

        @Override
        public int range() {
            return StorageEspMod.range;
        }

        @Override
        public void setRange(int blocks) {
            StorageEspMod.setRange(blocks);
        }
    };
    private static final int RANGE_MIN = 32;
    private static final int RANGE_MAX = 1024;

    private final Target target;
    private boolean draggingOpacity;
    private boolean draggingRange;

    public StorageEspMenu(Screen parent) {
        this(parent, "Storage ESP", STORAGE_ESP);
    }

    public StorageEspMenu(Screen parent, String title, Target target) {
        super(parent, title);
        this.target = target;
    }

    @Override
    protected void init() {
        super.init();
        int rows = (TYPES.length + COLUMNS - 1) / COLUMNS;
        setPanel(520, rows * (CARD_H + GAP) + (target.range() >= 0 ? 68 : 46));
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        return target.selected().size() + " / " + TYPES.length + " selected";
    }

    // ---------------------------------------------------------------- Geometrie

    private int cardWidth() {
        return (cx1 - cx0 - GAP * (COLUMNS - 1)) / COLUMNS;
    }

    private int cardX(int index) {
        return cx0 + (index % COLUMNS) * (cardWidth() + GAP);
    }

    private int cardY(int index) {
        return cy0 + (index / COLUMNS) * (CARD_H + GAP);
    }

    private int bottomY() {
        int rows = (TYPES.length + COLUMNS - 1) / COLUMNS;
        return cy0 + rows * (CARD_H + GAP) + 6;
    }

    private int buttonWidth() {
        return 54;
    }

    private int allX() {
        return cx0;
    }

    private int noneX() {
        return cx0 + buttonWidth() + 4;
    }

    private int tracerX0() {
        return noneX() + buttonWidth() + 16;
    }

    private int tracerX1() {
        return tracerX0() + NovaGui.width(font, "Tracers") + 32;
    }

    private int sliderY() {
        return bottomY() + 26;
    }

    private int trackX0() {
        return cx0 + 62;
    }

    private int trackX1() {
        return cx1 - 40;
    }

    private void setOpacityFromMouse(double mouseX) {
        float t = (float) ((mouseX - trackX0()) / (trackX1() - trackX0()));
        target.setOpacity(Math.round(Math.max(0.0F, Math.min(1.0F, t)) * 100.0F));
    }

    private boolean overSlider(double mouseX, double mouseY) {
        int y = sliderY();
        return isHovering((int) mouseX, (int) mouseY, trackX0() - 6, y - 3, trackX1() + 6, y + 11);
    }

    private int rangeY() {
        return sliderY() + 22;
    }

    private void setRangeFromMouse(double mouseX) {
        float t = (float) ((mouseX - trackX0()) / (trackX1() - trackX0()));
        int blocks = RANGE_MIN + Math.round(Math.max(0.0F, Math.min(1.0F, t)) * (RANGE_MAX - RANGE_MIN) / 32.0F) * 32;
        target.setRange(blocks);
    }

    private boolean overRange(double mouseX, double mouseY) {
        int y = rangeY();
        return target.range() >= 0
                && isHovering((int) mouseX, (int) mouseY, trackX0() - 6, y - 3, trackX1() + 6, y + 11);
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            for (int i = 0; i < TYPES.length; i++) {
                if (isHovering(mx, my, cardX(i), cardY(i), cardX(i) + cardWidth(), cardY(i) + CARD_H)) {
                    target.toggle(TYPES[i]);
                    ModConfig.save();
                    NovaGui.clickSound();
                    return true;
                }
            }
            int by = bottomY();
            if (isHovering(mx, my, allX(), by, allX() + buttonWidth(), by + 16)) {
                target.selectAll(true);
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (isHovering(mx, my, noneX(), by, noneX() + buttonWidth(), by + 16)) {
                target.selectAll(false);
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (isHovering(mx, my, tracerX0(), by, tracerX1(), by + 16)) {
                target.setTracers(!target.tracers());
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (overSlider(event.x(), event.y())) {
                draggingOpacity = true;
                setOpacityFromMouse(event.x());
                return true;
            }
            if (overRange(event.x(), event.y())) {
                draggingRange = true;
                setRangeFromMouse(event.x());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingOpacity) {
            setOpacityFromMouse(event.x());
            return true;
        }
        if (draggingRange) {
            setRangeFromMouse(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingOpacity || draggingRange) {
            draggingOpacity = false;
            draggingRange = false;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (overSlider(mouseX, mouseY)) {
            target.setOpacity(target.opacity() + (scrollY > 0 ? 5 : -5));
            ModConfig.save();
            return true;
        }
        if (overRange(mouseX, mouseY)) {
            target.setRange(target.range() + (scrollY > 0 ? 32 : -32));
            ModConfig.save();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;

        // Karten
        for (int i = 0; i < TYPES.length; i++) {
            StorageEspMod.Type type = TYPES[i];
            int x0 = cardX(i);
            int y0 = cardY(i);
            int x1 = x0 + cardWidth();
            int y1 = y0 + CARD_H;
            boolean hover = isHovering(mouseX, mouseY, x0, y0, x1, y1);
            boolean on = target.selected().contains(type);
            int color = 0xFF000000 | type.color;
            pointer |= hover;
            NovaShapes.rect(graphics, x0, y0, x1, y1, 5, hover ? NovaGui.RAISED : NovaGui.CARD);
            NovaShapes.outline(graphics, x0, y0, x1, y1, 5, on ? color : NovaGui.BORDER);
            graphics.item(type.icon(), x0 + 4, y0 + 4);
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, type.label, x1 - x0 - 30), x0 + 24, y0 + 8,
                    on ? NovaGui.TEXT : NovaGui.TEXT_MUTED);
        }

        // Alle / Keine / Tracer
        int by = bottomY();
        boolean allHover = isHovering(mouseX, mouseY, allX(), by, allX() + buttonWidth(), by + 16);
        boolean noneHover = isHovering(mouseX, mouseY, noneX(), by, noneX() + buttonWidth(), by + 16);
        boolean tracerHover = isHovering(mouseX, mouseY, tracerX0(), by, tracerX1(), by + 16);
        pointer |= allHover || noneHover || tracerHover;
        NovaControls.choice(graphics, font, allX(), by, allX() + buttonWidth(), by + 16, "All", allHover, false, accent);
        NovaControls.choice(graphics, font, noneX(), by, noneX() + buttonWidth(), by + 16, "None", noneHover, false, accent);
        NovaGui.text(graphics, font, "Tracers", tracerX0(), by + 4, tracerHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(graphics, tracerX1() - 22, by + 2, target.tracers() ? 1.0F : 0.0F, false, 1.0F, accent);

        // Deckkraft
        int y = sliderY();
        int x0 = trackX0();
        int x1 = trackX1();
        boolean hover = overSlider(mouseX, mouseY) || draggingOpacity;
        pointer |= hover;
        NovaGui.text(graphics, font, "Fill", cx0, y + 1, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        int kx = x0 + Math.round((x1 - x0) * target.opacity() / 100.0F);
        NovaShapes.rect(graphics, x0, y + 3, x1, y + 7, 2, NovaGui.SWITCH_OFF);
        NovaShapes.rect(graphics, x0, y + 3, kx, y + 7, 2, accent);
        NovaControls.knob(graphics, kx, y + 5, hover, accent);
        String value = target.opacity() == 0 ? "Off" : target.opacity() + "%";
        NovaGui.text(graphics, font, value, cx1 - NovaGui.width(font, value), y + 1, NovaGui.TEXT);

        // Reichweite (nur Storage ESP)
        if (target.range() >= 0) {
            int ry = rangeY();
            boolean rangeHover = overRange(mouseX, mouseY) || draggingRange;
            pointer |= rangeHover;
            NovaGui.text(graphics, font, "Range", cx0, ry + 1, rangeHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            int rx = x0 + Math.round((x1 - x0) * (target.range() - RANGE_MIN) / (float) (RANGE_MAX - RANGE_MIN));
            NovaShapes.rect(graphics, x0, ry + 3, x1, ry + 7, 2, NovaGui.SWITCH_OFF);
            NovaShapes.rect(graphics, x0, ry + 3, rx, ry + 7, 2, accent);
            NovaControls.knob(graphics, rx, ry + 5, rangeHover, accent);
            String blocks = target.range() + " m";
            NovaGui.text(graphics, font, blocks, cx1 - NovaGui.width(font, blocks), ry + 1, NovaGui.TEXT);
        }
        return pointer;
    }
}
