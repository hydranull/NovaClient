package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.mods.MacroMod;
import nova.nova_client.client.mods.Mod;

import java.util.Collections;
import java.util.List;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Macro-Editor: links die Liste der Macros, rechts Name, Ausloeser-Taste und die Schritte des Macros.
 * Schritte: aufgenommene Tastendruecke (Tastatur + Maus), Text/Befehle und Wartezeiten.
 */
public class MacroMenu extends NovaSettingsScreen {
    private static final int LIST_WIDTH = 132;
    private static final int ROW_HEIGHT = 26;
    private static final int ROW_GAP = 4;
    private static final int STEP_HEIGHT = 18;
    private static final int STEP_GAP = 2;
    private static final int BUTTON = 12;
    // Farben der Schritt-Arten (in init() gesetzt)
    private int DANGER;
    private int KEY_COLOR;
    private int TEXT_COLOR;
    private int WAIT_COLOR;
    private int RECORD_COLOR;
    private static final int DEFAULT_WAIT = 10;
    private static final int MAX_TICKS = 20 * 60;

    private enum Capture {
        NONE, TRIGGER, RECORD
    }

    private int selected = MacroMod.MACROS.isEmpty() ? -1 : 0;
    private Capture capture = Capture.NONE;
    private boolean swallowChar;
    private int editingText = -1;
    private float listScroll;
    private float stepScroll;

    private EditBox nameBox;
    private EditBox textBox;

    // Layout
    private int listX1;
    private int editX0;

    public MacroMenu(Screen parent) {
        super(parent, "Macros");
    }

    private Macro current() {
        return selected >= 0 && selected < MacroMod.MACROS.size() ? MacroMod.MACROS.get(selected) : null;
    }

    private static MacroMod macroMod() {
        for (Mod mod : Mods.mods) {
            if (mod instanceof MacroMod macroMod) {
                return macroMod;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Layout

    private int deleteX0() {
        return cx1 - NovaGui.width(font, "Delete") - 14;
    }

    private int triggerX1() {
        return deleteX0() - 6;
    }

    private int triggerX0() {
        return triggerX1() - 96;
    }

    private int fieldY() {
        return cy0 + 12;
    }

    private int stepsY0() {
        return cy0 + 50;
    }

    private int stepsY1() {
        return cy1 - 72;
    }

    private int stepsX1() {
        return cx1 - 6;
    }

    private int textRowY() {
        return cy1 - 66;
    }

    private int textButtonX0() {
        return cx1 - NovaGui.width(font, "Save") - 14;
    }

    private int buttonRowY() {
        return cy1 - 44;
    }

    private int recordX1() {
        return (editX0 + cx1) / 2 - 3;
    }

    private int waitX0() {
        return (editX0 + cx1) / 2 + 3;
    }

    private int statusY0() {
        return cy1 - 20;
    }

    private int listBottom() {
        return cy1 - 26;
    }

    private int addY0() {
        return cy1 - 20;
    }

    private int rowY(int index) {
        return cy0 + index * (ROW_HEIGHT + ROW_GAP) - Math.round(listScroll);
    }

    private int stepY(int index) {
        return stepsY0() + index * (STEP_HEIGHT + STEP_GAP) - Math.round(stepScroll);
    }

    // Kleine Knoepfe in einer Schritt-Zeile (von rechts)
    private int deleteButtonX(int x1) {
        return x1 - 14;
    }

    private int downButtonX(int x1) {
        return x1 - 28;
    }

    private int upButtonX(int x1) {
        return x1 - 42;
    }

    private int plusButtonX(int x1) {
        return x1 - 58;
    }

    private int valueX0(int x1) {
        return x1 - 94;
    }

    private int minusButtonX(int x1) {
        return x1 - 108;
    }

    // ---------------------------------------------------------------- Init

    @Override
    protected void init() {
        super.init();
        DANGER = NovaGui.DANGER;
        KEY_COLOR = 0xFF5E9EEE;
        TEXT_COLOR = 0xFFE6A94C;
        WAIT_COLOR = NovaGui.TEXT_DIM;
        RECORD_COLOR = NovaGui.DANGER;
        listX1 = cx0 + LIST_WIDTH;
        editX0 = listX1 + 14;

        nameBox = new EditBox(font, editX0 + 6, fieldY() + 5, triggerX0() - 6 - editX0 - 10, 10, Component.literal("Name"));
        styleField(nameBox, 32);
        nameBox.setResponder(value -> {
            Macro macro = current();
            if (macro != null) {
                macro.name = value;
            }
        });

        textBox = new EditBox(font, editX0 + 6, textRowY() + 5, textButtonX0() - 6 - editX0 - 10, 10,
                Component.literal("Text"));
        styleField(textBox, 256);

        NovaGui.themeFont(nameBox);
        addRenderableWidget(nameBox);
        NovaGui.themeFont(textBox);
        addRenderableWidget(textBox);
        loadFields();
    }

    private void styleField(EditBox box, int maxLength) {
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(NovaGui.TEXT);
        box.setTextShadow(false);
    }

    private void loadFields() {
        Macro macro = current();
        boolean visible = macro != null;
        nameBox.visible = visible;
        textBox.visible = visible;
        nameBox.setValue(visible ? macro.name : "");
        textBox.setValue("");
        editingText = -1;
        stepScroll = 0.0F;
        if (!visible) {
            setFocused(null);
        }
    }

    private void select(int index) {
        ModConfig.save();
        selected = index;
        capture = Capture.NONE;
        loadFields();
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        Macro macro = current();
        if (capture == Capture.TRIGGER && macro != null) {
            return "Press a trigger key for \"" + macro.name + "\"";
        }
        if (capture == Capture.RECORD) {
            return "Recording...";
        }
        return MacroMod.MACROS.size() + " macros";
    }

    // ---------------------------------------------------------------- Aktionen

    private void addStep(Macro macro, Macro.Step step) {
        macro.steps.add(step);
        // ans Ende scrollen
        int max = maxStepScroll(macro);
        stepScroll = max;
    }

    private int maxStepScroll(Macro macro) {
        int content = macro.steps.size() * (STEP_HEIGHT + STEP_GAP) - STEP_GAP;
        return Math.max(0, content - (stepsY1() - stepsY0()));
    }

    private void commitText(Macro macro) {
        String text = textBox.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        if (editingText >= 0 && editingText < macro.steps.size()
                && macro.steps.get(editingText).type == Macro.StepType.TEXT) {
            macro.steps.get(editingText).text = text;
        } else {
            addStep(macro, Macro.Step.text(text));
        }
        editingText = -1;
        textBox.setValue("");
        ModConfig.save();
        NovaGui.clickSound();
    }

    private static int totalTicks(Macro macro) {
        return MacroMod.estimateTicks(macro);
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x();
        int my = (int) event.y();
        Macro macro = current();

        // Aufnahme: Klick auf Stopp beendet, jeder andere Mausklick wird als Schritt aufgenommen
        if (capture == Capture.RECORD && macro != null) {
            if (event.button() == 0 && isHovering(mx, my, editX0, buttonRowY(), recordX1(), buttonRowY() + 18)) {
                capture = Capture.NONE;
                ModConfig.save();
                NovaGui.clickSound();
            } else {
                addStep(macro, Macro.Step.key(InputConstants.Type.MOUSE.getOrCreate(event.button())));
            }
            return true;
        }

        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        if (capture == Capture.TRIGGER) {
            capture = Capture.NONE;
            return true;
        }

        // Liste der Macros
        if (mx >= cx0 && mx < listX1 && my >= cy0 && my < listBottom()) {
            for (int i = 0; i < MacroMod.MACROS.size(); i++) {
                int y = rowY(i);
                if (my >= y && my < y + ROW_HEIGHT) {
                    NovaGui.clickSound();
                    select(i);
                    return true;
                }
            }
        }

        // Neues Macro
        if (isHovering(mx, my, cx0, addY0(), listX1, addY0() + 20)) {
            MacroMod.MACROS.add(new Macro("Macro " + (MacroMod.MACROS.size() + 1), Mod.NO_KEY));
            NovaGui.clickSound();
            select(MacroMod.MACROS.size() - 1);
            listScroll = Math.max(0, MacroMod.MACROS.size() * (ROW_HEIGHT + ROW_GAP) - (listBottom() - cy0));
            setFocused(nameBox);
            return true;
        }

        if (macro != null) {
            // Ausloeser-Taste
            if (isHovering(mx, my, triggerX0(), fieldY(), triggerX1(), fieldY() + 18)) {
                capture = Capture.TRIGGER;
                setFocused(null);
                NovaGui.clickSound();
                return true;
            }
            // Macro loeschen
            if (isHovering(mx, my, deleteX0(), fieldY(), cx1, fieldY() + 18)) {
                MacroMod.MACROS.remove(selected);
                NovaGui.clickSound();
                select(Math.min(selected, MacroMod.MACROS.size() - 1));
                return true;
            }
            // Schritte
            if (my >= stepsY0() && my < stepsY1() && mx >= editX0 && mx < stepsX1()) {
                if (clickStep(macro, mx, my, event.hasShiftDown())) {
                    return true;
                }
            }
            // Text hinzufuegen / speichern
            if (isHovering(mx, my, textButtonX0(), textRowY(), cx1, textRowY() + 18)) {
                commitText(macro);
                return true;
            }
            // Aufnahme starten
            if (isHovering(mx, my, editX0, buttonRowY(), recordX1(), buttonRowY() + 18)) {
                capture = Capture.RECORD;
                setFocused(null);
                NovaGui.clickSound();
                return true;
            }
            // Warten hinzufuegen
            if (isHovering(mx, my, waitX0(), buttonRowY(), cx1, buttonRowY() + 18)) {
                addStep(macro, Macro.Step.waitTicks(DEFAULT_WAIT));
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
        }

        // Macro-Mod an/aus
        MacroMod mod = macroMod();
        if (mod != null && isHovering(mx, my, editX0, statusY0(), cx1, statusY0() + 20)) {
            mod.toggle(Minecraft.getInstance());
            NovaGui.clickSound();
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickStep(Macro macro, int mx, int my, boolean shift) {
        List<Macro.Step> steps = macro.steps;
        int x1 = stepsX1();
        for (int i = 0; i < steps.size(); i++) {
            int y0 = stepY(i);
            if (my < y0 || my >= y0 + STEP_HEIGHT) {
                continue;
            }
            Macro.Step step = steps.get(i);
            int by = y0 + 3;
            if (isHovering(mx, my, deleteButtonX(x1), by, deleteButtonX(x1) + BUTTON, by + BUTTON)) {
                steps.remove(i);
                if (editingText == i) {
                    editingText = -1;
                    textBox.setValue("");
                } else if (editingText > i) {
                    editingText--;
                }
            } else if (isHovering(mx, my, upButtonX(x1), by, upButtonX(x1) + BUTTON, by + BUTTON)) {
                if (i > 0) {
                    Collections.swap(steps, i, i - 1);
                    editingText = -1;
                }
            } else if (isHovering(mx, my, downButtonX(x1), by, downButtonX(x1) + BUTTON, by + BUTTON)) {
                if (i < steps.size() - 1) {
                    Collections.swap(steps, i, i + 1);
                    editingText = -1;
                }
            } else if (step.type != Macro.StepType.TEXT
                    && isHovering(mx, my, plusButtonX(x1), by, plusButtonX(x1) + BUTTON, by + BUTTON)) {
                step.ticks = Math.min(MAX_TICKS, step.ticks + (shift ? 10 : 1));
            } else if (step.type != Macro.StepType.TEXT
                    && isHovering(mx, my, minusButtonX(x1), by, minusButtonX(x1) + BUTTON, by + BUTTON)) {
                int min = step.type == Macro.StepType.KEY ? 1 : 0;
                step.ticks = Math.max(min, step.ticks - (shift ? 10 : 1));
            } else if (step.type == Macro.StepType.TEXT) {
                // Text-Schritt zum Bearbeiten ins Textfeld laden
                editingText = i;
                textBox.setValue(step.text);
                setFocused(textBox);
            } else {
                return false;
            }
            ModConfig.save();
            NovaGui.clickSound();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < listX1) {
            int max = Math.max(0, MacroMod.MACROS.size() * (ROW_HEIGHT + ROW_GAP) - ROW_GAP - (listBottom() - cy0));
            listScroll = Math.max(0.0F, Math.min(max, listScroll - (float) scrollY * 20.0F));
            return true;
        }
        Macro macro = current();
        if (macro != null && mouseY >= stepsY0() && mouseY < stepsY1()) {
            stepScroll = Math.max(0.0F, Math.min(maxStepScroll(macro), stepScroll - (float) scrollY * 20.0F));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        Macro macro = current();
        if (macro != null && capture == Capture.TRIGGER) {
            if (!event.isEscape()) {
                boolean clear = event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE;
                macro.keyCode = clear ? Mod.NO_KEY : event.key();
                ModConfig.save();
                NovaGui.clickSound();
            }
            capture = Capture.NONE;
            swallowChar = true;
            return true;
        }
        if (macro != null && capture == Capture.RECORD) {
            if (event.isEscape()) {
                capture = Capture.NONE;
                ModConfig.save();
                NovaGui.clickSound();
            } else {
                addStep(macro, Macro.Step.key(InputConstants.getKey(event)));
            }
            swallowChar = true;
            return true;
        }
        if (macro != null && getFocused() == textBox && (event.key() == InputConstants.KEY_RETURN
                || event.key() == InputConstants.KEY_NUMPADENTER)) {
            commitText(macro);
            return true;
        }
        if (event.isEscape() && editingText >= 0) {
            editingText = -1;
            textBox.setValue("");
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (capture != Capture.NONE || swallowChar) {
            swallowChar = false;
            return true;
        }
        return super.charTyped(event);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        swallowChar = false;
        boolean pointer = drawMacroList(graphics, mouseX, mouseY);
        graphics.fill(listX1 + 6, cy0, listX1 + 7, cy1, NovaGui.BORDER);

        Macro macro = current();
        if (macro == null) {
            int midX = (editX0 + cx1) / 2;
            int midY = (cy0 + statusY0()) / 2 - 10;
            NovaGui.centered(graphics, font, "No macro selected", midX, midY, NovaGui.TEXT_DIM);
            NovaGui.centered(graphics, font, "", midX, midY + 12, NovaGui.TEXT_MUTED);
        } else {
            pointer |= drawHeaderRow(graphics, macro, mouseX, mouseY);
            pointer |= drawSteps(graphics, macro, mouseX, mouseY);
            pointer |= drawAddControls(graphics, macro, mouseX, mouseY);
        }

        pointer |= drawStatus(graphics, mouseX, mouseY);
        return pointer;
    }

    private boolean drawMacroList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        boolean pointer = false;
        List<Macro> macros = MacroMod.MACROS;
        graphics.enableScissor(cx0 - 2, cy0, listX1 + 2, listBottom());
        for (int i = 0; i < macros.size(); i++) {
            Macro macro = macros.get(i);
            int y0 = rowY(i);
            int y1 = y0 + ROW_HEIGHT;
            if (y1 < cy0 || y0 > listBottom()) {
                continue;
            }
            boolean isSelected = i == selected;
            boolean hovered = isHovering(mouseX, mouseY, cx0, y0, listX1 - 1, y1 - 1) && mouseY < listBottom();
            pointer |= hovered && !isSelected;
            NovaShapes.rect(graphics, cx0, y0, listX1, y1, 5, isSelected ? NovaGui.alpha(accent, 0.16F) : (hovered ? NovaGui.RAISED : NovaGui.CARD));
            NovaShapes.outline(graphics, cx0, y0, listX1, y1, 5, isSelected ? accent : NovaGui.BORDER);
            String name = macro.name.isBlank() ? "(unnamed)" : macro.name;
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, name, LIST_WIDTH - 14), cx0 + 7, y0 + 4,
                    isSelected ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            String info = macro.steps.size() + " steps  ·  " + (macro.hasKey() ? macro.keyName() : "no key");
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, info, LIST_WIDTH - 14), cx0 + 7, y0 + 14,
                    macro.hasKey() ? NovaGui.mix(NovaGui.TEXT_MUTED, accent, 0.6F) : NovaGui.TEXT_MUTED);
        }
        graphics.disableScissor();

        boolean addHover = isHovering(mouseX, mouseY, cx0, addY0(), listX1, addY0() + 20);
        pointer |= addHover;
        NovaShapes.rect(graphics, cx0, addY0(), listX1, addY0() + 20, 5, NovaGui.alpha(accent, addHover ? 0.25F : 0.12F));
        NovaShapes.outline(graphics, cx0, addY0(), listX1, addY0() + 20, 5, accent);
        NovaGui.centered(graphics, font, "+ New macro", (cx0 + listX1) / 2, addY0() + 6, NovaGui.TEXT);
        return pointer;
    }

    private boolean drawHeaderRow(GuiGraphicsExtractor graphics, Macro macro, int mouseX, int mouseY) {
        boolean pointer = false;
        int fy = fieldY();
        NovaGui.text(graphics, font, "Name", editX0, cy0 + 1, NovaGui.TEXT_DIM);
        NovaGui.text(graphics, font, "Trigger", triggerX0(), cy0 + 1, NovaGui.TEXT_DIM);
        drawField(graphics, nameBox, "Macro name");

        // Ausloeser-Taste
        int tx0 = triggerX0();
        int tx1 = triggerX1();
        boolean capturing = capture == Capture.TRIGGER;
        boolean triggerHover = isHovering(mouseX, mouseY, tx0, fy, tx1, fy + 18);
        pointer |= triggerHover;
        if (capturing) {
            float pulse = 0.5F + 0.5F * (float) Math.sin(NovaGui.seconds() * 6.0F);
            NovaShapes.rect(graphics, tx0, fy, tx1, fy + 18, 5, NovaGui.alpha(accent, 0.25F));
            NovaShapes.outline(graphics, tx0, fy, tx1, fy + 18, 5, accent);
        } else {
            NovaShapes.rect(graphics, tx0, fy, tx1, fy + 18, 5, triggerHover ? NovaGui.RAISED : NovaGui.CARD);
            NovaShapes.outline(graphics, tx0, fy, tx1, fy + 18, 5, macro.hasKey() ? NovaGui.alpha(accent, 0.8F) : NovaGui.BORDER);
        }
        String label = capturing ? "Press key..." : macro.hasKey() ? macro.keyName() : "None";
        NovaGui.centered(graphics, font, NovaGui.ellipsize(font, label, tx1 - tx0 - 8), (tx0 + tx1) / 2, fy + 5,
                capturing || macro.hasKey() ? NovaGui.TEXT : NovaGui.TEXT_MUTED);

        // Loeschen
        int dx0 = deleteX0();
        boolean deleteHover = isHovering(mouseX, mouseY, dx0, fy, cx1, fy + 18);
        pointer |= deleteHover;
        NovaShapes.rect(graphics, dx0, fy, cx1, fy + 18, 5, deleteHover ? NovaGui.alpha(NovaGui.DANGER, 0.18F) : NovaGui.CARD);
        NovaShapes.outline(graphics, dx0, fy, cx1, fy + 18, 5, deleteHover ? DANGER : NovaGui.alpha(DANGER, 0.45F));
        NovaGui.centered(graphics, font, "Delete", (dx0 + cx1) / 2, fy + 5,
                deleteHover ? NovaGui.DANGER : NovaGui.alpha(DANGER, 0.9F));

        // Kopf der Schrittliste
        NovaGui.text(graphics, font, "Steps (" + macro.steps.size() + ")", editX0, cy0 + 38, NovaGui.TEXT_DIM);
        String duration = "~ " + Macro.formatTicks(totalTicks(macro));
        NovaGui.text(graphics, font, duration, cx1 - NovaGui.width(font, duration), cy0 + 38, NovaGui.TEXT_MUTED);
        return pointer;
    }

    private boolean drawSteps(GuiGraphicsExtractor graphics, Macro macro, int mouseX, int mouseY) {
        boolean pointer = false;
        List<Macro.Step> steps = macro.steps;
        int y0Area = stepsY0();
        int y1Area = stepsY1();
        int x1 = stepsX1();
        int max = maxStepScroll(macro);
        stepScroll = Math.max(0.0F, Math.min(max, stepScroll));

        if (steps.isEmpty()) {
            int midX = (editX0 + cx1) / 2;
            int midY = (y0Area + y1Area) / 2 - 8;
            NovaGui.centered(graphics, font, "No steps yet", midX, midY, NovaGui.TEXT_DIM);
            NovaGui.centered(graphics, font, NovaGui.ellipsize(font,
                    "", cx1 - editX0), midX, midY + 11, NovaGui.TEXT_MUTED);
            return false;
        }

        boolean mouseInArea = mouseY >= y0Area && mouseY < y1Area;
        graphics.enableScissor(editX0 - 2, y0Area, cx1 + 2, y1Area);
        for (int i = 0; i < steps.size(); i++) {
            int y0 = stepY(i);
            int y1 = y0 + STEP_HEIGHT;
            if (y1 < y0Area || y0 > y1Area) {
                continue;
            }
            Macro.Step step = steps.get(i);
            boolean rowHover = mouseInArea && isHovering(mouseX, mouseY, editX0, y0, x1 - 1, y1 - 1);
            boolean editing = i == editingText;
            int typeColor = switch (step.type) {
                case KEY -> KEY_COLOR;
                case TEXT -> TEXT_COLOR;
                case WAIT -> WAIT_COLOR;
            };

            NovaShapes.rect(graphics, editX0, y0, x1, y1, 4, rowHover || editing ? NovaGui.RAISED : NovaGui.CARD);
            NovaShapes.outline(graphics, editX0, y0, x1, y1, 4, editing ? TEXT_COLOR : NovaGui.mix(NovaGui.BORDER, typeColor, rowHover ? 0.4F : 0.12F));

            // Nummer + Typ-Badge
            String number = String.valueOf(i + 1);
            NovaGui.text(graphics, font, number, editX0 + 5, y0 + 5, NovaGui.TEXT_MUTED);
            String badge = switch (step.type) {
                case KEY -> "Key";
                case TEXT -> step.text.trim().startsWith("/") ? "Command" : "Chat";
                case WAIT -> "Wait";
            };
            int bx0 = editX0 + 8 + Math.max(NovaGui.width(font, "99"), NovaGui.width(font, number));
            int bx1 = bx0 + NovaGui.width(font, badge) + 8;
            NovaShapes.rect(graphics, bx0, y0 + 3, bx1, y1 - 3, 3, NovaGui.alpha(typeColor, 0.2F));
            NovaGui.text(graphics, font, badge, bx0 + 4, y0 + 5, typeColor, false);

            // Beschreibung
            boolean hasValue = step.type != Macro.StepType.TEXT;
            int descX = bx1 + 6;
            int descMax = (hasValue ? minusButtonX(x1) : upButtonX(x1)) - descX - 4;
            String description = switch (step.type) {
                case KEY -> step.keyDisplayName();
                case TEXT -> step.text;
                case WAIT -> "Pause";
            };
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, description, descMax), descX, y0 + 5,
                    step.type == Macro.StepType.WAIT ? NovaGui.TEXT_MUTED : NovaGui.TEXT);

            // Wert mit - / +
            int by = y0 + 3;
            if (hasValue) {
                pointer |= iconButton(graphics, minusButtonX(x1), by, mouseX, mouseY, mouseInArea, Icon.MINUS, NovaGui.TEXT_DIM);
                String value = Macro.formatTicks(step.ticks);
                NovaGui.centered(graphics, font, value, valueX0(x1) + 17, y0 + 5, typeColor);
                pointer |= iconButton(graphics, plusButtonX(x1), by, mouseX, mouseY, mouseInArea, Icon.PLUS, NovaGui.TEXT_DIM);
            }
            pointer |= iconButton(graphics, upButtonX(x1), by, mouseX, mouseY, mouseInArea, Icon.UP, NovaGui.TEXT_DIM);
            pointer |= iconButton(graphics, downButtonX(x1), by, mouseX, mouseY, mouseInArea, Icon.DOWN, NovaGui.TEXT_DIM);
            pointer |= iconButton(graphics, deleteButtonX(x1), by, mouseX, mouseY, mouseInArea, Icon.CROSS, DANGER);
            pointer |= rowHover && step.type == Macro.StepType.TEXT;
        }
        graphics.disableScissor();

        if (max > 0) {
            int viewHeight = y1Area - y0Area;
            int thumbHeight = Math.max(12, viewHeight * viewHeight / (viewHeight + max));
            int thumbY = y0Area + Math.round((viewHeight - thumbHeight) * (stepScroll / max));
            NovaShapes.rect(graphics, cx1 - 3, y0Area, cx1, y1Area, 1, NovaGui.TRACK);
            NovaShapes.rect(graphics, cx1 - 3, thumbY, cx1, thumbY + thumbHeight, 1, NovaGui.alpha(accent, 0.9F));
        }
        return pointer;
    }

    private boolean drawAddControls(GuiGraphicsExtractor graphics, Macro macro, int mouseX, int mouseY) {
        boolean pointer = false;

        // Textzeile
        drawField(graphics, textBox, editingText >= 0 ? "Edit text..." : "Message or /command...");
        int tbx0 = textButtonX0();
        int ty = textRowY();
        boolean textHover = isHovering(mouseX, mouseY, tbx0, ty, cx1, ty + 18);
        pointer |= textHover;
        NovaShapes.rect(graphics, tbx0, ty, cx1, ty + 18, 5, NovaGui.alpha(TEXT_COLOR, textHover ? 0.28F : 0.14F));
        NovaShapes.outline(graphics, tbx0, ty, cx1, ty + 18, 5, NovaGui.alpha(TEXT_COLOR, 0.8F));
        NovaGui.centered(graphics, font, editingText >= 0 ? "Save" : "+ Text", (tbx0 + cx1) / 2, ty + 5, NovaGui.TEXT);

        // Aufnahme
        int by = buttonRowY();
        boolean recording = capture == Capture.RECORD;
        int rx1 = recordX1();
        boolean recordHover = isHovering(mouseX, mouseY, editX0, by, rx1, by + 18);
        pointer |= recordHover;
        if (recording) {
            float pulse = 0.5F + 0.5F * (float) Math.sin(NovaGui.seconds() * 6.0F);
        }
        NovaShapes.rect(graphics, editX0, by, rx1, by + 18, 5, NovaGui.alpha(RECORD_COLOR, recording ? 0.3F : (recordHover ? 0.22F : 0.12F)));
        NovaShapes.outline(graphics, editX0, by, rx1, by + 18, 5, NovaGui.alpha(RECORD_COLOR, 0.85F));
        String recordLabel = recording ? "Stop" : "Record keys";
        int labelWidth = NovaGui.width(font, recordLabel) + 10;
        int lx = (editX0 + rx1) / 2 - labelWidth / 2;
        if (recording) {
            NovaShapes.rect(graphics, lx, by + 5, lx + 7, by + 12, 1, RECORD_COLOR);
        } else {
            NovaShapes.rect(graphics, lx, by + 5, lx + 7, by + 12, 3, RECORD_COLOR);
        }
        NovaGui.text(graphics, font, recordLabel, lx + 10, by + 5, NovaGui.TEXT);

        // Warten
        int wx0 = waitX0();
        boolean waitHover = isHovering(mouseX, mouseY, wx0, by, cx1, by + 18);
        pointer |= waitHover;
        NovaShapes.rect(graphics, wx0, by, cx1, by + 18, 5, waitHover ? NovaGui.RAISED : NovaGui.CARD);
        NovaShapes.outline(graphics, wx0, by, cx1, by + 18, 5, NovaGui.mix(NovaGui.BORDER, WAIT_COLOR, 0.5F));
        NovaGui.centered(graphics, font, "+ Wait " + Macro.formatTicks(DEFAULT_WAIT), (wx0 + cx1) / 2, by + 5,
                NovaGui.TEXT_DIM);
        return pointer;
    }

    private boolean drawStatus(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        MacroMod mod = macroMod();
        if (mod == null) {
            return false;
        }
        int sy0 = statusY0();
        boolean hover = isHovering(mouseX, mouseY, editX0, sy0, cx1, sy0 + 20);
        int color = mod.active ? NovaGui.SUCCESS : DANGER;
        NovaShapes.rect(graphics, editX0, sy0, cx1, sy0 + 20, 5, NovaGui.alpha(color, hover ? 0.22F : 0.12F));
        NovaShapes.outline(graphics, editX0, sy0, cx1, sy0 + 20, 5, NovaGui.alpha(color, 0.7F));
        String status = mod.active ? "Macros ON"
                : "Macros OFF";
        NovaGui.centered(graphics, font, NovaGui.ellipsize(font, status, cx1 - editX0 - 8), (editX0 + cx1) / 2, sy0 + 6, color);
        return hover;
    }

    // ---------------------------------------------------------------- Mini-Icons

    private enum Icon {
        MINUS, PLUS, UP, DOWN, CROSS
    }

    private boolean iconButton(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY, boolean mouseInArea,
                               Icon icon, int color) {
        boolean hover = mouseInArea && isHovering(mouseX, mouseY, x, y, x + BUTTON, y + BUTTON);
        NovaShapes.rect(graphics, x, y, x + BUTTON, y + BUTTON, 3, hover ? NovaGui.alpha(color, 0.25F) : NovaGui.INSET);
        int c = hover ? NovaGui.TEXT : color;
        int cx = x + BUTTON / 2;
        int cy = y + BUTTON / 2;
        switch (icon) {
            case MINUS -> graphics.fill(cx - 3, cy, cx + 3, cy + 1, c);
            case PLUS -> {
                graphics.fill(cx - 3, cy, cx + 3, cy + 1, c);
                graphics.fill(cx, cy - 3, cx + 1, cy + 4, c);
            }
            case UP -> {
                for (int row = 0; row < 3; row++) {
                    graphics.fill(cx - row, cy - 2 + row, cx + row + 1, cy - 1 + row, c);
                }
            }
            case DOWN -> {
                for (int row = 0; row < 3; row++) {
                    graphics.fill(cx - row, cy + 2 - row, cx + row + 1, cy + 3 - row, c);
                }
            }
            case CROSS -> {
                for (int d = -2; d <= 2; d++) {
                    graphics.fill(cx + d, cy + d, cx + d + 1, cy + d + 1, c);
                    graphics.fill(cx + d, cy - d, cx + d + 1, cy - d + 1, c);
                }
            }
        }
        return hover;
    }
}
