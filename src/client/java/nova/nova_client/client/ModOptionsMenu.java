package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.mods.Mod;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Allgemeines Einstellungs-Fenster: eine Zeile je Option (Sektion, Schalter, Regler, Auswahl, Segmente, Knopf, Taste,
 * Info, Text, Farben), gezeichnet mit den gemeinsamen Bedienelementen. Aenderungen werden sofort gespeichert. Regler
 * sind exakt einstellbar: Mausrad auf dem Regler = ein Schritt, Rechtsklick = Zahl eintippen (Enter uebernimmt).
 * Tastatur: Tab/Pfeile waehlen eine Zeile, Enter/Leertaste schaltet, links/rechts aendert Werte. Passen nicht alle
 * Zeilen ins Fenster, scrollt die Liste mit dem Mausrad.
 */
public class ModOptionsMenu extends NovaSettingsScreen {
    private static final int PITCH = 24;
    private static final int SECTION = 26;
    private static final int INFO = 16;

    /** Eine Zeile im Menue. */
    public sealed interface Option permits Section, Toggle, Slider, Choice, Segmented, Action, Actions, Info, KeyBind, Text, Colors {
    }

    /** Ueberschrift einer Gruppe von Zeilen. */
    public record Section(String title) implements Option {
    }

    /** An/Aus-Schalter rechts in der Zeile; swatch != 0 = kleines Farbfeld vor der Beschriftung. */
    public record Toggle(String label, BooleanSupplier get, Consumer<Boolean> set, int swatch) implements Option {
        public Toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
            this(label, get, set, 0);
        }
    }

    /** Regler von min bis max in Schritten von step; format macht aus dem Wert den Text rechts. */
    public record Slider(String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set,
                         DoubleFunction<String> format) implements Option {
    }

    /** Auswahl: Klick = naechster Wert, Rechtsklick = vorheriger. */
    public record Choice(String label, String[] values, IntSupplier get, IntConsumer set) implements Option {
    }

    /** Wenige Werte nebeneinander als Segmente (label leer = ueber die ganze Breite). */
    public record Segmented(String label, String[] values, IntSupplier get, IntConsumer set) implements Option {
    }

    /** Knopf ueber die ganze Breite. */
    public record Action(Supplier<String> label, Runnable run) implements Option {
    }

    /** Mehrere Knoepfe nebeneinander. */
    public record Actions(List<Action> actions) implements Option {
    }

    /** Graue Info-Zeile (z.B. Status oder Zaehler), Text wird jeden Frame neu geholt. */
    public record Info(Supplier<String> text) implements Option {
    }

    /** Textfeld rechts in der Zeile (z.B. ein Befehl), wird bei jeder Aenderung uebernommen. */
    public record Text(String label, Supplier<String> get, Consumer<String> set, int maxLength) implements Option {
    }

    /** Taste festlegen: anklicken, dann Taste druecken (Entf/Backspace = keine, Esc = abbrechen). */
    public record KeyBind(String label, IntSupplier get, IntConsumer set) implements Option {
    }

    /** Farbauswahl: kleine Farbkreise rechts in der Zeile, Klick waehlt (get/set = Index in colors). */
    public record Colors(String label, int[] colors, IntSupplier get, IntConsumer set) implements Option {
    }

    private final String subtitle;
    private final List<Option> options;
    /** Oberkante jeder Zeile relativ zum Anfang der Liste, dazu die Gesamthoehe. */
    private final int[] offsets;
    private final int listHeight;
    private final NovaControls.Springs springs = new NovaControls.Springs();
    private int panelWidth = 400;
    private int dragging = -1;
    /** Index der KeyBind-Zeile, die gerade auf eine Taste wartet (-1 = keine). */
    private int listening = -1;
    /** Textfelder je Zeile (null bei anderen Optionen). */
    private final EditBox[] fields;
    /** Regler, dessen Wert gerade eingetippt wird (-1 = keiner), und der Text dazu. */
    private int editing = -1;
    private String editText = "";
    /** Zeile mit Tastatur-Fokus (-1 = keine) und Knopf darin (bei mehreren Knoepfen). */
    private int focus = -1;
    private int focusPart;
    private float scroll;
    private float scrollTarget;
    private final String[] infoTexts;
    private final long[] infoTimes;

    /** Knopf, der ein anderes Fenster oeffnet (dieses wird dessen Zurueck-Ziel). */
    public static Action open(String label, java.util.function.Function<Screen, Screen> screen) {
        return new Action(() -> label, () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.gui.setScreen(screen.apply(mc.gui.screen()));
        });
    }

    /** Beschriftungen fuer Groessen-Stufen ("75%", "100%", ...). */
    public static String[] percent(float[] scales) {
        String[] labels = new String[scales.length];
        for (int i = 0; i < scales.length; i++) {
            labels[i] = Math.round(scales[i] * 100.0F) + "%";
        }
        return labels;
    }

    /** Namen der Einstellungen in Reihenfolge (fuer die Nova-KI: "wie benutze ich ..."). */
    public List<String> settingNames() {
        List<String> names = new java.util.ArrayList<>();
        for (Option option : options) {
            switch (option) {
                case Toggle o -> names.add(o.label());
                case Slider o -> names.add(o.label());
                case Choice o -> names.add(o.label());
                case Segmented o -> {
                    if (!o.label().isEmpty()) {
                        names.add(o.label());
                    }
                }
                case Text o -> names.add(o.label());
                case KeyBind o -> names.add(o.label());
                case Colors o -> names.add(o.label());
                default -> {
                }
            }
        }
        return names;
    }

    /** Fenster mit der Beschreibung des Moduls als Untertitel. */
    public ModOptionsMenu(Screen parent, String title, List<Option> options) {
        this(parent, title, "", options);
    }

    /** Fenster mit eigenem Untertitel (leer = Beschreibung des Moduls). */
    public ModOptionsMenu(Screen parent, String title, String subtitle, List<Option> options) {
        super(parent, title);
        this.subtitle = subtitle;
        this.options = options;
        this.fields = new EditBox[options.size()];
        this.infoTexts = new String[options.size()];
        this.infoTimes = new long[options.size()];
        this.offsets = new int[options.size()];
        int y = 0;
        for (int i = 0; i < options.size(); i++) {
            offsets[i] = y;
            y += pitch(i);
        }
        this.listHeight = y;
    }

    /** Breitere oder schmalere Fenster (Standard 400). */
    public ModOptionsMenu withWidth(int width) {
        this.panelWidth = width;
        return this;
    }

    private int pitch(int index) {
        return switch (options.get(index)) {
            case Section ignored -> index == 0 ? SECTION - 8 : SECTION;
            case Info ignored -> INFO;
            default -> PITCH;
        };
    }

    @Override
    protected void init() {
        setPanel(panelWidth, listHeight);
        scroll = scrollTarget = Math.min(scrollTarget, maxScroll());
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i) instanceof Text text) {
                EditBox box = new EditBox(font, choiceX0() + 6, controlY(i) + 5, cx1 - choiceX0() - 10, 10,
                        Component.literal(text.label()));
                box.setBordered(false);
                box.setMaxLength(text.maxLength());
                box.setTextColor(NovaGui.TEXT);
                box.setTextShadow(false);
                box.setValue(text.get().get());
                box.setResponder(value -> text.set().accept(value));
                NovaGui.themeFont(box);
                fields[i] = box;
                addRenderableWidget(box);
            }
        }
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        return subtitle.isEmpty() ? super.subtitle() : subtitle;
    }

    /** Speichern nach jeder Aenderung (Schalter, Regler losgelassen, Auswahl, ...). */
    private void saved() {
        ModConfig.save();
        changed();
    }

    /** Nach jeder abgeschlossenen Aenderung (z.B. um sie anzuwenden). */
    protected void changed() {
    }

    /** Info-Text, hoechstens alle 200 ms neu geholt (statt jedes Bild einen neuen Text zu bauen). */
    private String info(int index, Info info) {
        long now = net.minecraft.util.Util.getMillis();
        if (infoTexts[index] == null || now - infoTimes[index] > 200L) {
            infoTexts[index] = info.text().get();
            infoTimes[index] = now;
        }
        return infoTexts[index];
    }

    // ---------------------------------------------------------------- Geometrie

    private int rowY(int index) {
        return cy0 + offsets[index] - Math.round(scroll);
    }

    /** Oberkante eines Bedienelements (CONTROL_H hoch) mittig in der Zeile. */
    private int controlY(int index) {
        return rowY(index) + (pitch(index) - NovaGui.CONTROL_H) / 2;
    }

    /** Oberkante der Hover-Flaeche (ROW_H hoch) mittig in der Zeile. */
    private int boxY(int index) {
        return rowY(index) + (pitch(index) - NovaGui.ROW_H) / 2;
    }

    private int maxScroll() {
        return Math.max(0, listHeight - (cy1 - cy0));
    }

    private int sliderX0() {
        return cx0 + (cx1 - cx0) * 2 / 5;
    }

    private int sliderX1() {
        return cx1 - NovaControls.VALUE_W - 8;
    }

    private int choiceX0() {
        return cx1 - Math.min(160, (cx1 - cx0) / 2);
    }

    private int segmentsX0(Segmented segmented) {
        if (segmented.label().isEmpty()) {
            return cx0;
        }
        // So breit, dass die laengste Beschriftung passt - hoechstens bis kurz hinter die Zeilen-Beschriftung
        int widest = 0;
        for (String value : segmented.values()) {
            widest = Math.max(widest, NovaType.width(font, NovaType.Face.BODY, value));
        }
        int n = segmented.values().length;
        int room = cx1 - cx0 - NovaType.width(font, NovaType.Face.BODY, segmented.label()) - 16;
        return cx1 - Math.min(room, Math.max(Math.min((cx1 - cx0) * 3 / 5, n * 72), n * (widest + 16)));
    }

    private boolean inView(double mouseY) {
        return mouseY >= cy0 && mouseY < cy1;
    }

    private boolean rowHovered(int index, double mouseX, double mouseY) {
        return inView(mouseY) && NovaControls.rowHit(mouseX, mouseY, cx0, boxY(index), cx1, NovaGui.ROW_H);
    }

    private boolean sliderHovered(int index, double mouseX, double mouseY) {
        int y = rowY(index) + pitch(index) / 2;
        return inView(mouseY) && NovaControls.hit(mouseX, mouseY, sliderX0() - 6, y - 7, sliderX1() + 6, y + 7);
    }

    /** Knopf (bei mehreren nebeneinander) unter der Maus, -1 = keiner. */
    private int actionAt(int index, Actions actions, double mouseX, double mouseY) {
        int n = actions.actions().size();
        for (int b = 0; b < n; b++) {
            if (inView(mouseY) && NovaControls.hit(mouseX, mouseY, actionX0(n, b), boxY(index), actionX1(n, b), boxY(index) + NovaGui.ROW_H)) {
                return b;
            }
        }
        return -1;
    }

    private int actionX0(int n, int b) {
        return cx0 + (cx1 - cx0 + 8) * b / n;
    }

    private int actionX1(int n, int b) {
        return cx0 + (cx1 - cx0 + 8) * (b + 1) / n - 8;
    }

    private void setSlider(Slider slider, double mouseX) {
        setValue(slider, slider.min() + (slider.max() - slider.min()) * NovaControls.sliderValue(mouseX, sliderX0(), sliderX1()));
    }

    /** Wert auf den naechsten Schritt runden, begrenzen und uebernehmen. */
    private static void setValue(Slider slider, double value) {
        if (slider.step() > 0.0D) {
            value = slider.min() + Math.round((value - slider.min()) / slider.step()) * slider.step();
        }
        slider.set().accept(Math.max(slider.min(), Math.min(slider.max(), value)));
    }

    /** Ein Schritt des Reglers (bei stufenlosen Reglern 1 %). */
    private static double stepOf(Slider slider) {
        return slider.step() > 0.0D ? slider.step() : (slider.max() - slider.min()) / 100.0D;
    }

    /** Eingetippten Wert uebernehmen (ungueltige Eingabe = nichts aendern). */
    private void commitEdit() {
        if (editing >= 0 && options.get(editing) instanceof Slider slider) {
            try {
                setValue(slider, Double.parseDouble(editText.trim().replace(',', '.')));
                saved();
            } catch (NumberFormatException ignored) {
                // ungueltig: alten Wert behalten
            }
        }
        editing = -1;
    }

    /** Wert als Zahl zum Bearbeiten (ohne Einheit). */
    private static String plain(Slider slider) {
        double v = slider.get().getAsDouble();
        return stepOf(slider) >= 1.0D && Math.abs(v - Math.rint(v)) < 1.0E-9D ? String.valueOf(Math.round(v))
                : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static boolean focusable(Option option) {
        return !(option instanceof Section) && !(option instanceof Info);
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        focus = -1;
        if (editing >= 0) {
            commitEdit(); // Klick daneben uebernimmt die Eingabe
        }
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            if (option instanceof Slider slider && button == 1 && rowHovered(i, mx, my)) {
                editing = i; // Rechtsklick: Zahl eintippen
                editText = plain(slider);
                NovaGui.clickSound();
                return true;
            }
            switch (option) {
                case Toggle toggle -> {
                    if (button == 0 && rowHovered(i, mx, my)) {
                        activate(i);
                        return true;
                    }
                }
                case Slider slider -> {
                    if (button == 0 && sliderHovered(i, mx, my)) {
                        dragging = i;
                        setSlider(slider, mx);
                        return true;
                    }
                }
                case Choice choice -> {
                    if ((button == 0 || button == 1) && rowHovered(i, mx, my)) {
                        step(i, button == 0 ? 1 : -1);
                        return true;
                    }
                }
                case Segmented segmented -> {
                    int s = button == 0 && inView(my) ? NovaControls.segmentAt(mx, my, segmentsX0(segmented), controlY(i), cx1,
                            controlY(i) + NovaGui.CONTROL_H, segmented.values().length) : -1;
                    if (s >= 0) {
                        segmented.set().accept(s);
                        NovaGui.clickSound();
                        saved();
                        return true;
                    }
                }
                case Action ignored -> {
                    if (button == 0 && rowHovered(i, mx, my)) {
                        activate(i);
                        return true;
                    }
                }
                case Actions actions -> {
                    int b = button == 0 ? actionAt(i, actions, mx, my) : -1;
                    if (b >= 0) {
                        focusPart = b;
                        activate(i);
                        return true;
                    }
                }
                case KeyBind ignored -> {
                    if (button == 0 && rowHovered(i, mx, my)) {
                        activate(i);
                        return true;
                    }
                }
                case Colors colors -> {
                    int c = button == 0 && inView(my) ? NovaControls.swatchAt(mx, my, colors.colors().length, cx1,
                            rowY(i) + pitch(i) / 2.0F) : -1;
                    if (c >= 0) {
                        colors.set().accept(c);
                        NovaGui.clickSound();
                        saved();
                        return true;
                    }
                }
                case Section ignored -> {
                }
                case Info ignored -> {
                }
                case Text ignored -> {
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Zeile ausloesen (Klick, Enter oder Leertaste). */
    private void activate(int index) {
        switch (options.get(index)) {
            case Toggle toggle -> {
                toggle.set().accept(!toggle.get().getAsBoolean());
                NovaGui.clickSound();
                saved();
            }
            case Choice ignored -> step(index, 1);
            case Segmented segmented -> {
                segmented.set().accept(Math.floorMod(segmented.get().getAsInt() + 1, segmented.values().length));
                NovaGui.clickSound();
                saved();
            }
            case Action action -> {
                NovaGui.clickSound();
                action.run().run();
                saved();
            }
            case Actions actions -> {
                NovaGui.clickSound();
                actions.actions().get(Math.max(0, Math.min(actions.actions().size() - 1, focusPart))).run().run();
                saved();
            }
            case KeyBind ignored -> {
                listening = listening == index ? -1 : index;
                NovaGui.clickSound();
            }
            case Text ignored -> {
                if (fields[index] != null) {
                    setFocused(fields[index]);
                }
            }
            default -> {
            }
        }
    }

    /** Wert eine Stufe weiter (Auswahl, Segmente, Farben, Regler). */
    private void step(int index, int direction) {
        switch (options.get(index)) {
            case Choice choice -> choice.set().accept(Math.floorMod(choice.get().getAsInt() + direction, choice.values().length));
            case Segmented segmented -> segmented.set().accept(Math.max(0, Math.min(segmented.values().length - 1,
                    segmented.get().getAsInt() + direction)));
            case Colors colors -> colors.set().accept(Math.max(0, Math.min(colors.colors().length - 1, colors.get().getAsInt() + direction)));
            case Slider slider -> setValue(slider, slider.get().getAsDouble() + direction * stepOf(slider));
            case Actions actions -> {
                focusPart = Math.max(0, Math.min(actions.actions().size() - 1, focusPart + direction));
                return;
            }
            default -> {
                return;
            }
        }
        NovaGui.clickSound();
        saved();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editing >= 0) {
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                commitEdit();
            } else if (event.isEscape()) {
                editing = -1;
            } else if (event.key() == InputConstants.KEY_BACKSPACE && !editText.isEmpty()) {
                editText = editText.substring(0, editText.length() - 1);
            }
            return true;
        }
        if (listening >= 0 && options.get(listening) instanceof KeyBind bind) {
            if (!event.isEscape()) {
                boolean clear = event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE;
                bind.set().accept(clear ? Mod.NO_KEY : event.key());
                saved();
                NovaGui.clickSound();
            }
            listening = -1;
            return true;
        }
        boolean typing = getFocused() instanceof EditBox box && box.isFocused();
        if (!typing) {
            int key = event.key();
            if (key == InputConstants.KEY_TAB || key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
                moveFocus(key == InputConstants.KEY_UP || key == InputConstants.KEY_TAB && event.hasShiftDown() ? -1 : 1);
                return true;
            }
            if (focus >= 0 && (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER || key == InputConstants.KEY_SPACE)) {
                activate(focus);
                return true;
            }
            if (focus >= 0 && (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT)) {
                step(focus, key == InputConstants.KEY_LEFT ? -1 : 1);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /** Tastatur-Fokus zur naechsten bedienbaren Zeile und sie ins Bild holen. */
    private void moveFocus(int direction) {
        int n = options.size();
        int next = focus;
        for (int k = 0; k < n; k++) {
            next = next < 0 ? (direction > 0 ? 0 : n - 1) : Math.floorMod(next + direction, n);
            if (focusable(options.get(next))) {
                break;
            }
        }
        if (next < 0 || !focusable(options.get(next))) {
            return;
        }
        focus = next;
        focusPart = 0;
        int top = offsets[next];
        int bottom = top + pitch(next);
        int view = cy1 - cy0;
        if (top < scrollTarget) {
            scrollTarget = top;
        } else if (bottom > scrollTarget + view) {
            scrollTarget = Math.min(maxScroll(), bottom - view);
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editing >= 0) {
            String c = event.codepointAsString();
            if (editText.length() < 10 && (c.matches("[0-9.,]") || c.equals("-") && editText.isEmpty())) {
                editText += c;
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i) instanceof Slider slider && sliderHovered(i, mouseX, mouseY) && scrollY != 0.0D) {
                // Mausrad auf dem Regler: genau einen Schritt (z.B. Y -57 -> -56)
                setValue(slider, slider.get().getAsDouble() + (scrollY > 0.0D ? stepOf(slider) : -stepOf(slider)));
                saved();
                return true;
            }
        }
        if (maxScroll() > 0 && scrollY != 0.0D) {
            scrollTarget = Math.max(0.0F, Math.min(maxScroll(), scrollTarget - (float) scrollY * PITCH * 1.5F));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging >= 0 && options.get(dragging) instanceof Slider slider) {
            setSlider(slider, event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging >= 0) {
            dragging = -1;
            saved();
            return true;
        }
        return super.mouseReleased(event);
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        scroll = NovaGui.approach(scroll, scrollTarget, NovaGui.SPEED, dt);
        boolean pointer = false;
        graphics.enableScissor(px0 + 1, cy0, px1 - 1, cy1);
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            int y = rowY(i);
            int h = pitch(i);
            boolean visible = y + h > cy0 && y < cy1;
            if (fields[i] != null) {
                // Textfelder scrollen mit (ausserhalb der Liste unsichtbar und nicht klickbar)
                fields[i].setY(controlY(i) + 5);
                fields[i].visible = y >= cy0 && y + h <= cy1;
                // Wert von aussen geaendert (z.B. per Knopf)? Dann das Feld nachziehen
                if (!fields[i].isFocused() && option instanceof Text text && !fields[i].getValue().equals(text.get().get())) {
                    fields[i].setValue(text.get().get());
                    fields[i].moveCursorToStart(false);
                }
            }
            if (!visible) {
                continue;
            }
            boolean hover = rowHovered(i, mouseX, mouseY);
            boolean focused = i == focus;
            float lit = springs.get(option, hover || focused ? 1.0F : 0.0F, NovaGui.FAST, dt);
            int labelY = NovaType.centerY(NovaType.Face.BODY, y, h);
            switch (option) {
                case Section section -> NovaControls.section(graphics, font, cx0, y + h - 18, cx1, section.title());
                case Toggle toggle -> {
                    float on = springs.get(toggle.get(), toggle.get().getAsBoolean(), dt);
                    NovaControls.toggleRow(graphics, font, cx0, boxY(i), cx1, toggle.label(), toggle.swatch(), on, lit, true, accent);
                    pointer |= hover;
                }
                case Slider slider -> pointer |= drawSlider(graphics, i, slider, y, h, mouseX, mouseY);
                case Choice choice -> {
                    NovaControls.rowHover(graphics, cx0, boxY(i), cx1, NovaGui.ROW_H, lit);
                    NovaGui.text(graphics, font, NovaGui.ellipsize(font, choice.label(), choiceX0() - cx0 - 8), cx0, labelY,
                            hover || focused ? NovaGui.TEXT : NovaGui.TEXT_DIM);
                    int index = Math.floorMod(choice.get().getAsInt(), choice.values().length);
                    NovaControls.select(graphics, font, choiceX0(), controlY(i), cx1, controlY(i) + NovaGui.CONTROL_H,
                            choice.values()[index], hover);
                    pointer |= hover;
                }
                case Segmented segmented -> {
                    int x0 = segmentsX0(segmented);
                    if (!segmented.label().isEmpty()) {
                        NovaGui.text(graphics, font, NovaGui.ellipsize(font, segmented.label(), x0 - cx0 - 8), cx0, labelY,
                                NovaGui.TEXT_DIM);
                    }
                    int over = inView(mouseY) ? NovaControls.segmentAt(mouseX, mouseY, x0, controlY(i), cx1,
                            controlY(i) + NovaGui.CONTROL_H, segmented.values().length) : -1;
                    NovaControls.segmented(graphics, font, x0, controlY(i), cx1, controlY(i) + NovaGui.CONTROL_H, segmented.values(),
                            segmented.get().getAsInt(), over, accent);
                    pointer |= over >= 0;
                }
                case Action action -> {
                    NovaControls.button(graphics, font, cx0, boxY(i), cx1, boxY(i) + NovaGui.ROW_H, action.label().get(),
                            NovaControls.Variant.SECONDARY, hover || focused, true);
                    pointer |= hover;
                }
                case Actions actions -> {
                    int n = actions.actions().size();
                    int over = actionAt(i, actions, mouseX, mouseY);
                    for (int b = 0; b < n; b++) {
                        boolean lit2 = over == b || focused && focusPart == b;
                        NovaControls.button(graphics, font, actionX0(n, b), boxY(i), actionX1(n, b), boxY(i) + NovaGui.ROW_H,
                                actions.actions().get(b).label().get(), NovaControls.Variant.SECONDARY, lit2, true);
                    }
                    pointer |= over >= 0;
                }
                case KeyBind bind -> {
                    NovaControls.rowHover(graphics, cx0, boxY(i), cx1, NovaGui.ROW_H, lit);
                    NovaGui.text(graphics, font, NovaGui.ellipsize(font, bind.label(), choiceX0() - cx0 - 8), cx0, labelY,
                            hover || focused ? NovaGui.TEXT : NovaGui.TEXT_DIM);
                    int key = bind.get().getAsInt();
                    NovaControls.keybind(graphics, font, cx1, controlY(i), NovaControls.keyName(key), listening == i,
                            key == Mod.NO_KEY, hover, accent);
                    pointer |= hover;
                }
                case Info info -> NovaType.draw(graphics, font, NovaType.Face.CAPTION,
                        NovaType.ellipsize(font, NovaType.Face.CAPTION, info(i, info), cx1 - cx0), cx0,
                        NovaType.centerY(NovaType.Face.CAPTION, y, h), NovaGui.TEXT_MUTED);
                case Text text -> {
                    NovaGui.text(graphics, font, NovaGui.ellipsize(font, text.label(), choiceX0() - cx0 - 8), cx0, labelY,
                            NovaGui.TEXT_DIM);
                    if (fields[i] != null && fields[i].visible) {
                        drawField(graphics, fields[i], "");
                    }
                }
                case Colors colors -> {
                    NovaGui.text(graphics, font, NovaGui.ellipsize(font, colors.label(),
                            NovaControls.swatchX(colors.colors().length, 0, cx1) - NovaControls.SWATCH - cx0), cx0, labelY,
                            NovaGui.TEXT_DIM);
                    int over = inView(mouseY) ? NovaControls.swatchAt(mouseX, mouseY, colors.colors().length, cx1, y + h / 2.0F) : -1;
                    NovaControls.swatches(graphics, colors.colors(), cx1, y + h / 2.0F, colors.get().getAsInt(), over);
                    pointer |= over >= 0;
                }
            }
            if (focused && !(option instanceof Actions)) {
                NovaShapes.outline(graphics, cx0 - NovaControls.ROW_PAD, boxY(i), cx1 + NovaControls.ROW_PAD, boxY(i) + NovaGui.ROW_H,
                        NovaGui.RADIUS, NovaGui.alpha(accent, 0.55F));
            }
        }
        graphics.disableScissor();
        NovaControls.scrollbar(graphics, px1 - 4, cy0, cy1, scroll, maxScroll(), false);
        return pointer;
    }

    private boolean drawSlider(GuiGraphicsExtractor graphics, int i, Slider slider, int y, int h, int mouseX, int mouseY) {
        boolean active = sliderHovered(i, mouseX, mouseY) || dragging == i || focus == i;
        double t = (slider.get().getAsDouble() - slider.min()) / Math.max(1.0E-9D, slider.max() - slider.min());
        if (editing == i) {
            // Eingabefeld statt Wert (blinkender Strich)
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, slider.label(), sliderX0() - cx0 - 10), cx0,
                    NovaType.centerY(NovaType.Face.BODY, y, h), NovaGui.TEXT);
            NovaControls.sliderTrack(graphics, sliderX0(), sliderX1(), y + h / 2, (float) t, true, accent);
            NovaControls.valueEditor(graphics, font, editText, cx1, y, h, accent);
        } else {
            NovaControls.sliderRow(graphics, font, cx0, y + (h - NovaGui.ROW_H) / 2, cx1, sliderX0(), slider.label(), (float) t,
                    slider.format().apply(slider.get().getAsDouble()), active, accent);
        }
        return sliderHovered(i, mouseX, mouseY) || dragging == i;
    }
}
