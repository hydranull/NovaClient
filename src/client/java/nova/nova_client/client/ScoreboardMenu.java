package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import nova.nova_client.client.mods.ScoreboardMod;

import java.util.List;
import java.util.function.Consumer;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Scoreboard-Designer: links Titel/Zeilen, Ersetzen-Regeln und Stil bearbeiten, rechts Live-Vorschau.
 */
public class ScoreboardMenu extends NovaSettingsScreen {
    private static final String[] TABS = {"Lines", "Replace", "Style"};
    private static final int ROW = 20;
    private static final int PREVIEW_WIDTH = 170;
    private static final int COPY_WIDTH = 16;
    private static final String[] OPACITY_LABELS = {"Vanilla", "0%", "25%", "50%", "75%"};

    private static int tab;

    /** Zeile 0 = Titel, 1..15 = Scoreboard-Zeilen. */
    private final EditBox[] lineBoxes = new EditBox[ScoreboardMod.MAX_LINES + 1];
    private final EditBox[] findBoxes = new EditBox[ScoreboardMod.MAX_RULES];
    private final EditBox[] replaceBoxes = new EditBox[ScoreboardMod.MAX_RULES];
    private int scroll;

    // Aktuelles Original vom Server (jedes Frame neu gelesen)
    private Objective objective;
    private List<ScoreboardMod.Line> original = List.of();

    public ScoreboardMenu(Screen parent) {
        super(parent, "Fake Stats");
    }

    @Override
    protected void init() {
        super.init();
        setPanel(600, 274);

        for (int i = 0; i < lineBoxes.length; i++) {
            final int index = i;
            lineBoxes[i] = field(i == 0 ? ScoreboardMod.title : ScoreboardMod.lines[i - 1], value -> {
                if (index == 0) {
                    ScoreboardMod.title = value;
                } else {
                    ScoreboardMod.lines[index - 1] = value;
                }
            });
        }
        for (int i = 0; i < ScoreboardMod.MAX_RULES; i++) {
            final int index = i;
            findBoxes[i] = field(ScoreboardMod.find[i], value -> ScoreboardMod.find[index] = value);
            replaceBoxes[i] = field(ScoreboardMod.replace[i], value -> ScoreboardMod.replace[index] = value);
        }
        layout();
    }

    private EditBox field(String value, Consumer<String> responder) {
        EditBox box = new EditBox(font, 0, 0, 50, 10, Component.empty());
        box.setBordered(false);
        box.setMaxLength(160);
        box.setTextColor(NovaGui.TEXT);
        box.setTextShadow(false);
        box.setValue(value);
        box.moveCursorToStart(false);
        box.setResponder(responder);
        NovaGui.themeFont(box);
        return addRenderableWidget(box);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        return "Change the sidebar visually (only you see it). & colors, &#RRGGBB, {hide}";
    }

    // ---------------------------------------------------------------- Geometrie

    private int leftX1() {
        return cx1 - PREVIEW_WIDTH - 10;
    }

    private int tabWidth() {
        return (leftX1() - cx0 - 4 * (TABS.length - 1)) / TABS.length;
    }

    private int tabX(int index) {
        return cx0 + index * (tabWidth() + 4);
    }

    private int listY() {
        return cy0 + 24;
    }

    private int visibleRows() {
        return Math.max(1, (cy1 - listY()) / ROW);
    }

    private int maxScroll() {
        return Math.max(0, lineBoxes.length - visibleRows());
    }

    private int labelWidth() {
        return (leftX1() - cx0) * 2 / 5;
    }

    /** Linke Kante des Eingabefelds in der Zeilen-Ansicht (nach Label + Kopier-Knopf). */
    private int lineFieldX() {
        return cx0 + labelWidth() + COPY_WIDTH + 8;
    }

    private int ruleHalf() {
        return (leftX1() - cx0 - 18) / 2;
    }

    private int styleY(int row) {
        return listY() + row * 24;
    }

    private int opacityWidth() {
        return (leftX1() - cx0 - 4 * (OPACITY_LABELS.length - 1)) / OPACITY_LABELS.length;
    }

    private int opacityX(int index) {
        return cx0 + index * (opacityWidth() + 4);
    }

    /** Positioniert alle Felder passend zu Tab und Scrollposition. */
    private void layout() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (int i = 0; i < lineBoxes.length; i++) {
            int row = i - scroll;
            EditBox box = lineBoxes[i];
            box.visible = tab == 0 && row >= 0 && row < visibleRows();
            box.setX(lineFieldX() + 6);
            box.setY(listY() + row * ROW + 5);
            box.setWidth(leftX1() - lineFieldX() - 10);
        }
        for (int i = 0; i < ScoreboardMod.MAX_RULES; i++) {
            int y = listY() + 12 + i * ROW + 5;
            findBoxes[i].visible = tab == 1;
            findBoxes[i].setX(cx0 + 6);
            findBoxes[i].setY(y);
            findBoxes[i].setWidth(ruleHalf() - 10);
            replaceBoxes[i].visible = tab == 1;
            replaceBoxes[i].setX(leftX1() - ruleHalf() + 6);
            replaceBoxes[i].setY(y);
            replaceBoxes[i].setWidth(ruleHalf() - 10);
        }
        // Unsichtbare Felder duerfen keine Tasten mehr bekommen
        if (getFocused() instanceof EditBox box && !box.visible) {
            clearFocus();
        }
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            for (int i = 0; i < TABS.length; i++) {
                if (isHovering(mx, my, tabX(i), cy0, tabX(i) + tabWidth(), cy0 + 16)) {
                    tab = i;
                    layout();
                    NovaGui.clickSound();
                    return true;
                }
            }
            if (tab == 0) {
                // Kopier-Knopf: Originaltext (mit Farben) ins Feld uebernehmen und dann nur das Noetige aendern
                for (int i = 0; i < lineBoxes.length; i++) {
                    int row = i - scroll;
                    if (row < 0 || row >= visibleRows()) {
                        continue;
                    }
                    int x = cx0 + labelWidth() + 4;
                    int y = listY() + row * ROW;
                    if (isHovering(mx, my, x, y + 1, x + COPY_WIDTH, y + 17)) {
                        Component source = originalAt(i);
                        if (source != null) {
                            lineBoxes[i].setValue(ScoreboardMod.toCodes(source));
                            lineBoxes[i].moveCursorToStart(false);
                            NovaGui.clickSound();
                        }
                        return true;
                    }
                }
            } else if (tab == 2) {
                if (isHovering(mx, my, cx0, styleY(0), leftX1(), styleY(0) + 14)) {
                    ScoreboardMod.hideNumbers = !ScoreboardMod.hideNumbers;
                    ModConfig.save();
                    NovaGui.clickSound();
                    return true;
                }
                for (int i = 0; i < OPACITY_LABELS.length; i++) {
                    if (isHovering(mx, my, opacityX(i), styleY(1) + 12, opacityX(i) + opacityWidth(), styleY(1) + 28)) {
                        ScoreboardMod.opacity = ScoreboardMod.OPACITY_PRESETS[i];
                        ModConfig.save();
                        NovaGui.clickSound();
                        return true;
                    }
                }
                if (isHovering(mx, my, cx0, resetY(), cx0 + 110, resetY() + 16)) {
                    ScoreboardMod.reset();
                    ModConfig.save();
                    NovaGui.clickSound();
                    rebuildWidgets();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private int resetY() {
        return styleY(3) + 8;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == 0) {
            scroll += scrollY > 0 ? -1 : 1;
            layout();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Originaltext einer Designer-Zeile (0 = Titel), oder null wenn der Server sie nicht hat. */
    private Component originalAt(int index) {
        if (index == 0) {
            return objective != null ? objective.getDisplayName() : null;
        }
        return index - 1 < original.size() ? original.get(index - 1).name() : null;
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        objective = ScoreboardMod.sidebarObjective(Minecraft.getInstance());
        original = objective != null ? ScoreboardMod.originalLines(objective) : List.of();
        boolean pointer = false;

        // Tabs
        for (int i = 0; i < TABS.length; i++) {
            boolean hover = isHovering(mouseX, mouseY, tabX(i), cy0, tabX(i) + tabWidth(), cy0 + 16);
            pointer |= hover;
            NovaControls.choice(graphics, font, tabX(i), cy0, tabX(i) + tabWidth(), cy0 + 16, TABS[i], hover, tab == i, accent);
        }

        if (tab == 0) {
            pointer |= drawLines(graphics, mouseX, mouseY);
        } else if (tab == 1) {
            drawRules(graphics);
        } else {
            pointer |= drawStyle(graphics, mouseX, mouseY);
        }

        drawPreview(graphics);
        return pointer;
    }

    private boolean drawLines(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        boolean pointer = false;
        for (int i = 0; i < lineBoxes.length; i++) {
            int row = i - scroll;
            if (row < 0 || row >= visibleRows()) {
                continue;
            }
            int y = listY() + row * ROW;
            Component source = originalAt(i);
            String label = (i == 0 ? "Title: " : "#" + i + " ") + (source != null ? source.getString() : "-");
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, label, labelWidth()), cx0, y + 5,
                    source != null ? NovaGui.TEXT_DIM : NovaGui.TEXT_MUTED);

            int bx = cx0 + labelWidth() + 4;
            boolean hover = source != null && isHovering(mouseX, mouseY, bx, y + 1, bx + COPY_WIDTH, y + 17);
            pointer |= hover;
            NovaControls.choice(graphics, font, bx, y + 1, bx + COPY_WIDTH, y + 17, ">", hover, false, accent);

            drawField(graphics, lineBoxes[i], i == 0 ? "Own title (empty = original)" : "Own text (empty = original)");
        }
        if (maxScroll() > 0) {
            // Scrollbalken
            int trackY0 = listY();
            int trackY1 = listY() + visibleRows() * ROW - 2;
            int thumb = Math.max(12, (trackY1 - trackY0) * visibleRows() / lineBoxes.length);
            int ty = trackY0 + (trackY1 - trackY0 - thumb) * scroll / maxScroll();
            graphics.fill(leftX1() + 3, trackY0, leftX1() + 5, trackY1, NovaGui.TRACK);
            graphics.fill(leftX1() + 3, ty, leftX1() + 5, ty + thumb, accent);
        }
        return pointer;
    }

    private void drawRules(GuiGraphicsExtractor graphics) {
        NovaGui.text(graphics, font, "Find", cx0, listY(), NovaGui.TEXT_MUTED);
        NovaGui.text(graphics, font, "Replace with", leftX1() - ruleHalf(), listY(), NovaGui.TEXT_MUTED);
        for (int i = 0; i < ScoreboardMod.MAX_RULES; i++) {
            drawField(graphics, findBoxes[i], i == 0 ? "e.g. $12.3K" : "");
            drawField(graphics, replaceBoxes[i], i == 0 ? "e.g. &a$999B" : "");
            NovaGui.centered(graphics, font, ">", (cx0 + ruleHalf() + leftX1() - ruleHalf()) / 2, findBoxes[i].getY(),
                    NovaGui.TEXT_MUTED);
        }
    }

    private boolean drawStyle(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        boolean pointer = false;

        int y = styleY(0);
        boolean numbersHover = isHovering(mouseX, mouseY, cx0, y, leftX1(), y + 14);
        pointer |= numbersHover;
        NovaGui.text(graphics, font, "Hide red numbers", cx0, y + 3, numbersHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(graphics, leftX1() - 22, y + 1, ScoreboardMod.hideNumbers ? 1.0F : 0.0F, false, 1.0F, accent);

        y = styleY(1);
        NovaGui.text(graphics, font, "Background", cx0, y, NovaGui.TEXT_DIM);
        for (int i = 0; i < OPACITY_LABELS.length; i++) {
            int x = opacityX(i);
            boolean hover = isHovering(mouseX, mouseY, x, y + 12, x + opacityWidth(), y + 28);
            pointer |= hover;
            NovaControls.choice(graphics, font, x, y + 12, x + opacityWidth(), y + 28, OPACITY_LABELS[i], hover, ScoreboardMod.opacity == ScoreboardMod.OPACITY_PRESETS[i], accent);
        }

        boolean resetHover = isHovering(mouseX, mouseY, cx0, resetY(), cx0 + 110, resetY() + 16);
        pointer |= resetHover;
        NovaControls.choice(graphics, font, cx0, resetY(), cx0 + 110, resetY() + 16, "Reset everything", resetHover, false, NovaGui.DANGER);

        int hy = resetY() + 28;
        String[] help = {
                "&a-&f green  &c&l-&f bold red  &#FF55FF-&f hex color",
                "{hide} as line text hides that line.",
                "\">\" copies the original line incl. colors.",
                "Only visual: the server still has your real values."
        };
        for (int i = 0; i < help.length; i++) {
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, help[i], leftX1() - cx0), cx0, hy + i * 11, NovaGui.TEXT_MUTED);
        }
        return pointer;
    }

    private void drawPreview(GuiGraphicsExtractor graphics) {
        int x0 = cx1 - PREVIEW_WIDTH;
        int y0 = cy0;
        NovaShapes.rect(graphics, x0, y0, cx1, cy1, 6, NovaGui.INSET);
        NovaShapes.outline(graphics, x0, y0, cx1, cy1, 6, NovaGui.BORDER);
        NovaGui.text(graphics, font, "Preview", x0 + 8, y0 + 6, NovaGui.TEXT_MUTED);

        if (objective == null) {
            NovaGui.centered(graphics, font, "No scoreboard", (x0 + cx1) / 2, (y0 + cy1) / 2 - 8, NovaGui.TEXT_DIM);
            NovaGui.centered(graphics, font, "on this server", (x0 + cx1) / 2, (y0 + cy1) / 2 + 3, NovaGui.TEXT_MUTED);
            return;
        }
        graphics.enableScissor(x0 + 2, y0 + 18, cx1 - 2, cy1 - 2);
        ScoreboardMod.draw(graphics, font, objective, cx1 - 6, (y0 + 18 + cy1) / 2);
        graphics.disableScissor();
    }
}
