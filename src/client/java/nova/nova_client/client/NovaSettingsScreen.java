package nova.nova_client.client;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import nova.nova_client.client.mods.Mod;

/**
 * Basis aller Einstellungs-Fenster: ruhiges Fenster in der Mitte, Kopf mit Zurueck, Titel, Untertitel und (wenn aus
 * dem Nova-Menue geoeffnet) dem An/Aus-Schalter des Moduls, darunter eine Haarlinie. ESC fuehrt zurueck.
 * Unterklassen legen die Groesse mit {@link #setPanel} fest und zeichnen ihren Inhalt in cx0..cx1 / cy0..cy1 - mit den
 * gemeinsamen Bedienelementen aus {@link NovaControls}.
 */
public abstract class NovaSettingsScreen extends Screen {
    /** Hoehe des Kopfes (bis zur Haarlinie). */
    public static final int HEADER_H = 48;
    /** Innenabstand links/rechts. */
    public static final int PAD = 16;
    private static final int GAP_TOP = 12;
    private static final int GAP_BOTTOM = 14;

    protected final Screen parent;
    protected int accent;
    /** Modul, dessen Einstellungen das sind (null = kein Modul-Schalter im Kopf). */
    protected Mod module;

    protected final long openedAt = Util.getMillis();
    private long lastFrame = Util.getMillis();
    private float backHover;
    private float switchAnim = -1.0F;

    // Layout
    protected int px0, py0, px1, py1;
    protected int cx0, cx1, cy0, cy1;

    protected NovaSettingsScreen(Screen parent, String title) {
        super(Component.literal(title));
        this.parent = parent;
        this.accent = NovaGui.BRAND;
    }

    /** Frueher mit eigener Fensterfarbe - heute gibt es nur einen Akzent (UiSettings). */
    protected NovaSettingsScreen(Screen parent, String title, int accent) {
        this(parent, title);
    }

    /** Vom Nova-Menue gesetzt: das Modul bekommt seinen Schalter im Kopf. */
    public void setModule(Mod module) {
        this.module = module;
    }

    @Override
    protected void init() {
        setPanel(480, 250);
    }

    /**
     * Fenster mittig mit dieser Breite und Platz fuer contentHeight Pixel Inhalt (beides wird an kleine Fenster
     * angepasst); setzt px*, py* und den Inhaltsbereich cx*, cy*.
     */
    protected void setPanel(int panelWidth, int contentHeight) {
        int w = Math.min(panelWidth, width - 24);
        int h = Math.min(panelHeight(contentHeight), height - 24);
        px0 = (width - w) / 2;
        py0 = (height - h) / 2;
        px1 = px0 + w;
        py1 = py0 + h;
        cx0 = px0 + PAD;
        cx1 = px1 - PAD;
        cy0 = py0 + HEADER_H + GAP_TOP;
        cy1 = py1 - GAP_BOTTOM;
    }

    /** Hoehe, die ein Fenster mit contentHeight Pixel Inhalt hat. */
    protected static int panelHeight(int contentHeight) {
        return HEADER_H + GAP_TOP + contentHeight + GAP_BOTTOM;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    /** Untertitel unter dem Titel (z.B. Zaehler oder Status); Standard: Kategorie und Beschreibung des Moduls. */
    protected String subtitle() {
        if (module == null) {
            return "";
        }
        if (moduleSubtitle == null) {
            String description = ModInfo.description(module);
            moduleSubtitle = description.isEmpty() ? NovaGui.label(module.category)
                    : NovaGui.label(module.category) + "  ·  " + description;
        }
        return moduleSubtitle;
    }

    private String moduleSubtitle;

    /** Inhalt zeichnen; gibt true zurueck, wenn der Hand-Cursor angezeigt werden soll. */
    protected abstract boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt);

    /** Rahmen fuer ein randloses EditBox-Feld (Feld sitzt bei x+6, y+5) und Platzhalter, solange es leer ist. */
    protected void drawField(GuiGraphicsExtractor graphics, EditBox box, String placeholder) {
        int x0 = box.getX() - 6;
        int y0 = box.getY() - 5;
        int x1 = box.getX() + box.getWidth() + 4;
        int y1 = y0 + 18;
        boolean focused = box.isFocused();
        NovaControls.field(graphics, x0, y0, x1, y1, focused, box.isHovered(), accent);
        if (box.getValue().isEmpty() && !focused) {
            NovaControls.placeholder(graphics, font, placeholder, box.getX(), box.getY(), box.getWidth());
        }
    }

    private int backX0() {
        return px0 + 10;
    }

    private int backY0() {
        return py0 + (HEADER_H - 22) / 2;
    }

    protected boolean backHovered(double mouseX, double mouseY) {
        return NovaControls.hit(mouseX, mouseY, backX0(), backY0(), backX0() + 22, backY0() + 22);
    }

    private int switchX() {
        return px1 - PAD - NovaShapes.SWITCH_W;
    }

    private int switchY() {
        return py0 + (HEADER_H - NovaShapes.SWITCH_H) / 2;
    }

    private boolean switchHovered(double mouseX, double mouseY) {
        return module != null && NovaControls.hit(mouseX, mouseY, switchX() - 4, switchY() - 4,
                switchX() + NovaShapes.SWITCH_W + 4, switchY() + NovaShapes.SWITCH_H + 4);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && backHovered(event.x(), event.y())) {
            NovaGui.clickSound();
            onClose();
            return true;
        }
        if (event.button() == 0 && switchHovered(event.x(), event.y())) {
            NovaGui.clickSound();
            module.toggle(Minecraft.getInstance());
            ModConfig.save();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        NovaGui.background(graphics, width, height);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        long now = Util.getMillis();
        float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float elapsed = UiSettings.animations ? (now - openedAt) / 1000.0F : 100.0F;
        accent = NovaGui.BRAND;
        NovaGui.pushIntro(graphics, elapsed);

        NovaGui.panel(graphics, px0, py0, px1, py1);

        // Zurueck
        boolean back = backHovered(mouseX, mouseY);
        backHover = NovaGui.approach(backHover, back ? 1.0F : 0.0F, NovaGui.FAST, dt);
        int bx0 = backX0();
        int by0 = backY0();
        if (backHover > 0.01F) {
            NovaShapes.rect(graphics, bx0, by0, bx0 + 22, by0 + 22, NovaGui.RADIUS, NovaGui.alpha(NovaGui.HOVER, backHover));
        }
        NovaType.centered(graphics, font, NovaType.Face.HEADING, "‹", bx0 + 11 - Math.round(backHover),
                NovaType.centerY(NovaType.Face.HEADING, by0, 22) - 1, NovaGui.mix(NovaGui.TEXT_DIM, NovaGui.TEXT, backHover));

        // Modul-Schalter rechts
        int titleRight = px1 - PAD;
        boolean overSwitch = switchHovered(mouseX, mouseY);
        if (module != null) {
            float target = module.active ? 1.0F : 0.0F;
            switchAnim = switchAnim < 0.0F ? target : NovaGui.approach(switchAnim, target, NovaGui.SPEED, dt);
            NovaControls.toggle(graphics, switchX(), switchY(), switchAnim, overSwitch, 1.0F, accent);
            titleRight = switchX() - 12;
        }

        // Titel und Untertitel (bei Modulen mit Punkt in der Kategoriefarbe)
        String subtitle = subtitle();
        int titleX = px0 + 40;
        int room = titleRight - titleX;
        String title = NovaType.ellipsize(font, NovaType.Face.HEADING, this.title.getString(), room);
        if (subtitle.isEmpty()) {
            NovaType.draw(graphics, font, NovaType.Face.HEADING, title, titleX,
                    NovaType.centerY(NovaType.Face.HEADING, py0, HEADER_H), NovaGui.TEXT);
        } else {
            NovaType.draw(graphics, font, NovaType.Face.HEADING, title, titleX, NovaType.centerY(NovaType.Face.HEADING, py0 + 9, 14),
                    NovaGui.TEXT);
            int subX = titleX;
            if (module != null) {
                NovaShapes.disc(graphics, titleX + 2.5F, py0 + 31.0F, 2.5F, 0xFF000000 | NovaGui.accent(module.category));
                subX += 9;
            }
            NovaType.draw(graphics, font, NovaType.Face.CAPTION, NovaType.ellipsize(font, NovaType.Face.CAPTION, subtitle,
                    titleRight - subX), subX, NovaType.centerY(NovaType.Face.CAPTION, py0 + 25, 12), NovaGui.TEXT_MUTED);
        }
        graphics.fill(px0 + 1, py0 + HEADER_H, px1 - 1, py0 + HEADER_H + 1, NovaGui.BORDER);

        boolean pointer = drawContent(graphics, mouseX, mouseY, dt) || back || overSwitch;

        // Widgets (Textfelder) zuletzt, damit sie ueber dem Fenster liegen
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        NovaGui.popIntro(graphics);

        if (pointer) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }
}
