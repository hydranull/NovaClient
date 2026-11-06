package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import nova.nova_client.client.mods.TimeChangerMod;

import java.util.Locale;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer TimeChanger: Tageszeit per Regler (0 - 24000 Ticks) und Voreinstellungen.
 * Der Regler zeigt die Himmelsfarbe der jeweiligen Zeit, der Knopf ist Sonne oder Mond.
 */
public class TimeChangerMenu extends NovaSettingsScreen {
    private static final int[] PRESETS = {0, 6000, 12000, 13000, 18000};
    private static final String[] PRESET_NAMES = {"Sunrise", "Noon", "Sunset", "Night", "Midnight"};

    // Himmelsfarbe ueber den Tag: {Tick, Farbe}
    private static final int[][] SKY = {
            {0, 0xFFE8A060},
            {1500, 0xFF78A7FF},
            {10500, 0xFF78A7FF},
            {12000, 0xFFE07040},
            {13500, 0xFF1C2450},
            {18000, 0xFF080C1E},
            {22500, 0xFF1C2450},
            {24000, 0xFFE8A060},
    };
    private static final int SUN = 0xFFFFD84A;
    private static final int MOON = 0xFFE6E9F0;

    private static final int STEP = 250;

    private boolean dragging;
    private float shown = TimeChangerMod.time;

    public TimeChangerMenu(Screen parent) {
        super(parent, "TimeChanger");
    }

    @Override
    protected void init() {
        super.init();
        // Kompaktes Panel
        setPanel(340, 116);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    // ---------------------------------------------------------------- Zeit

    /** Minecraft-Ticks als Uhrzeit (0 Ticks = 06:00). */
    private static String clock(int ticks) {
        int minutes = (ticks * 60 / 1000 + 6 * 60) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String phase(int ticks) {
        if (ticks < 1000 || ticks >= 23000) {
            return "Sunrise";
        }
        if (ticks < 11000) {
            return "Day";
        }
        if (ticks < 13000) {
            return "Sunset";
        }
        return "Night";
    }

    private static boolean isDay(float ticks) {
        return ticks < 12500 || ticks >= 23500;
    }

    private static int skyColor(float ticks) {
        for (int i = 0; i < SKY.length - 1; i++) {
            if (ticks <= SKY[i + 1][0]) {
                float t = (ticks - SKY[i][0]) / (float) (SKY[i + 1][0] - SKY[i][0]);
                return NovaGui.mix(SKY[i][1], SKY[i + 1][1], Math.max(0.0F, Math.min(1.0F, t)));
            }
        }
        return SKY[SKY.length - 1][1];
    }

    // ---------------------------------------------------------------- Geometrie

    private int sliderX0() {
        return cx0 + 6;
    }

    private int sliderX1() {
        return cx1 - 6;
    }

    private int sliderY() {
        return cy0 + 40;
    }

    private int xFor(float ticks) {
        return sliderX0() + Math.round((sliderX1() - sliderX0()) * ticks / TimeChangerMod.DAY_LENGTH);
    }

    private int presetWidth() {
        return (cx1 - cx0 - 4 * (PRESETS.length - 1)) / PRESETS.length;
    }

    private int presetX(int index) {
        return cx0 + index * (presetWidth() + 4);
    }

    private int presetY() {
        return cy0 + 76;
    }

    private void setFromMouse(double mouseX) {
        float t = (float) ((mouseX - sliderX0()) / (sliderX1() - sliderX0()));
        int ticks = Math.round(TimeChangerMod.DAY_LENGTH * Math.max(0.0F, Math.min(1.0F, t)) / 10.0F) * 10;
        TimeChangerMod.setTime(Math.min(ticks, TimeChangerMod.DAY_LENGTH - 1));
        shown = TimeChangerMod.time;
    }

    private void step(int delta) {
        TimeChangerMod.setTime(TimeChangerMod.time + delta);
        ModConfig.save();
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            if (isHovering(mx, my, sliderX0() - 6, sliderY() - 6, sliderX1() + 6, sliderY() + 14)) {
                dragging = true;
                setFromMouse(event.x());
                return true;
            }
            for (int i = 0; i < PRESETS.length; i++) {
                if (isHovering(mx, my, presetX(i), presetY(), presetX(i) + presetWidth(), presetY() + 16)) {
                    TimeChangerMod.setTime(PRESETS[i]);
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
        if (dragging) {
            setFromMouse(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        step(scrollY > 0 ? STEP : -STEP);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isLeft() || event.isRight()) {
            step(event.isRight() ? STEP : -STEP);
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        int time = TimeChangerMod.time;
        // Ueber den Tageswechsel (23999 -> 0) springen statt einmal rueckwaerts durchlaufen
        if (Math.abs(time - shown) > TimeChangerMod.DAY_LENGTH / 2.0F) {
            shown = time;
        }
        shown = NovaGui.approach(shown, time, 18.0F, dt);

        // Sonne/Mond-Symbol und grosse Anzeige
        int iconColor = isDay(time) ? SUN : MOON;
        NovaShapes.rect(graphics, cx0, cy0 + 2, cx0 + 14, cy0 + 16, 7, iconColor);
        if (!isDay(time)) {
            // Mondsichel: Teil des Kreises mit Panelfarbe abdecken
            NovaShapes.rect(graphics, cx0 + 5, cy0, cx0 + 17, cy0 + 12, 6, NovaGui.PANEL);
        }
        // Grosse Uhrzeit als eigener Schnitt (nie skaliert)
        NovaType.draw(graphics, font, NovaType.Face.TITLE, clock(time), cx0 + 22, NovaType.centerY(NovaType.Face.TITLE, cy0 + 2, 14),
                NovaGui.TEXT);
        String info = phase(time) + "  ·  " + time + " ticks";
        NovaGui.text(graphics, font, info, cx1 - NovaGui.width(font, info), cy0 + 6, NovaGui.TEXT_MUTED);

        // Regler mit Himmelsfarben
        int y = sliderY();
        int x0 = sliderX0();
        int x1 = sliderX1();
        boolean sliderHover = isHovering(mouseX, mouseY, x0 - 6, y - 6, x1 + 6, y + 14) || dragging;
        pointer |= sliderHover;
        // Himmelsfarben als weiche Stuecke (je Stueck Grundfarbe + Verlauf zur naechsten) statt einer Linie je Pixel
        int pieces = 24;
        for (int i = 0; i < pieces; i++) {
            int a = x0 + (x1 - x0) * i / pieces;
            int b = x0 + (x1 - x0) * (i + 1) / pieces;
            int from = skyColor((float) i / pieces * TimeChangerMod.DAY_LENGTH);
            int to = skyColor((float) (i + 1) / pieces * TimeChangerMod.DAY_LENGTH);
            graphics.fill(a, y, b, y + 8, from);
            NovaShapes.fade(graphics, a, y, b, y + 8, NovaShapes.FADE_RIGHT, to);
        }
        NovaShapes.outline(graphics, x0 - 1, y - 1, x1 + 1, y + 9, 2, NovaGui.BORDER);
        // Stundenmarken alle 6 Stunden (06:00, 12:00, 18:00, 00:00, 06:00)
        for (int mark = 0; mark <= TimeChangerMod.DAY_LENGTH; mark += 6000) {
            int mx = xFor(mark);
            graphics.fill(mx, y + 10, mx + 1, y + 14, NovaGui.TEXT_MUTED);
            String label = clock(mark % TimeChangerMod.DAY_LENGTH);
            int lx = Math.max(cx0, Math.min(cx1 - NovaGui.width(font, label), mx - NovaGui.width(font, label) / 2 + 1));
            NovaGui.text(graphics, font, label, lx, y + 16, NovaGui.TEXT_MUTED);
        }

        // Knopf: Sonne am Tag, Mond in der Nacht
        int kx = xFor(shown);
        int knob = isDay(shown) ? SUN : MOON;
        NovaControls.knob(graphics, kx, y + 4, sliderHover, accent);

        // Voreinstellungen
        for (int i = 0; i < PRESETS.length; i++) {
            int px = presetX(i);
            boolean hover = isHovering(mouseX, mouseY, px, presetY(), px + presetWidth(), presetY() + 16);
            boolean active = time == PRESETS[i];
            pointer |= hover;
            NovaControls.choice(graphics, font, px, presetY(), px + presetWidth(), presetY() + 16, PRESET_NAMES[i], hover, active, accent);
        }

        if (!TimeChangerMod.isEnabled()) {
            NovaGui.centered(graphics, font, "Module is off - enable it to change the sky", (cx0 + cx1) / 2,
                    presetY() + 24, NovaGui.TEXT_MUTED);
        }
        return pointer;
    }
}
