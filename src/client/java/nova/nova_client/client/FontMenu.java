package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import nova.nova_client.client.mods.CustomFontMod;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Auswahl fuer Custom Font: jede Schrift als Karte, Name und Beispiel in der Schrift selbst. Oben der Schalter an/aus,
 * unten eine grosse Vorschau der gewaehlten Schrift. Klick auf eine Karte waehlt sie (und schaltet Custom Font ein,
 * falls es aus ist). Die Nova-Menues behalten ihre eigene Schrift (NovaType).
 */
public class FontMenu extends NovaSettingsScreen {
    private static final int CARD_H = 20;
    private static final int GAP = 4;
    private static final int PREVIEW_H = 36;
    private static final String SAMPLE = "Aa Bb 123";
    private static final String PREVIEW_1 = "The quick brown fox jumps over the lazy dog";
    private static final String PREVIEW_2 = "0123456789  ÄÖÜ ß !?";

    private final CustomFontMod mod;
    private final Style[] styles = new Style[CustomFonts.ALL.size()];
    private final float[] hoverAnim = new float[CustomFonts.ALL.size()];
    private float enabledAnim;
    private double scroll;

    public FontMenu(Screen parent, CustomFontMod mod) {
        super(parent, mod.name);
        this.mod = mod;
        for (int i = 0; i < styles.length; i++) {
            styles[i] = Style.EMPTY.withFont(new FontDescription.Resource(
                    Identifier.fromNamespaceAndPath("nova_client", CustomFonts.ALL.get(i).spec().name())));
        }
        enabledAnim = mod.active ? 1.0F : 0.0F;
    }

    @Override
    protected String subtitle() {
        return "Changes all text in the game  ·  " + CustomFonts.ALL.size() + " fonts";
    }

    // ---------------------------------------------------------------- Layout

    private int toggleY() {
        return cy0;
    }

    private int gridTop() {
        return cy0 + 24;
    }

    private int gridBottom() {
        return cy1 - PREVIEW_H - 6;
    }

    private int columnWidth() {
        return (cx1 - cx0 - GAP * 2) / 2;
    }

    private int rows() {
        return (CustomFonts.ALL.size() + 1) / 2;
    }

    private double maxScroll() {
        return Math.max(0, rows() * (CARD_H + GAP) - GAP - (gridBottom() - gridTop()));
    }

    private int cardX(int index) {
        return cx0 + (index % 2) * (columnWidth() + GAP * 2);
    }

    private int cardY(int index) {
        return gridTop() + (index / 2) * (CARD_H + GAP) - (int) Math.round(scroll);
    }

    private boolean cardHovered(int index, double mouseX, double mouseY) {
        int x = cardX(index);
        int y = cardY(index);
        return mouseY >= gridTop() && mouseY < gridBottom()
                && isHovering((int) mouseX, (int) mouseY, x, y, x + columnWidth(), y + CARD_H);
    }

    private boolean enabledHovered(double mouseX, double mouseY) {
        return isHovering((int) mouseX, (int) mouseY, cx0 - 4, toggleY() - 2, cx1 + 4, toggleY() + 18);
    }

    private Component inFont(int index, String text) {
        return Component.literal(text).setStyle(styles[index]);
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            double x = event.x();
            double y = event.y();
            if (enabledHovered(x, y)) {
                NovaGui.clickSound();
                mod.toggle(Minecraft.getInstance());
                ModConfig.save();
                return true;
            }
            for (int i = 0; i < CustomFonts.ALL.size(); i++) {
                if (cardHovered(i, x, y)) {
                    NovaGui.clickSound();
                    CustomFontMod.select(i);
                    if (!mod.active) {
                        mod.toggle(Minecraft.getInstance());
                        ModConfig.save();
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0) {
            scroll = Mth.clamp(scroll - scrollY * (CARD_H + GAP), 0.0D, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        scroll = Mth.clamp(scroll, 0.0D, maxScroll());

        // Schalter oben
        int ty = toggleY();
        boolean enabledHover = enabledHovered(mouseX, mouseY);
        enabledAnim = NovaGui.approach(enabledAnim, mod.active ? 1.0F : 0.0F, 14.0F, dt);
        if (enabledHover) {
            NovaShapes.rect(graphics, cx0 - 4, ty - 2, cx1 + 4, ty + 18, 4, NovaGui.HOVER);
        }
        NovaGui.text(graphics, font, "Use custom font", cx0, ty + 4, mod.active ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(graphics, cx1 - 22, ty + 2, enabledAnim, false, 1.0F, accent);
        pointer |= enabledHover;

        // Schrift-Karten
        int selected = CustomFontMod.selected;
        int width = columnWidth();
        graphics.enableScissor(cx0 - 6, gridTop() - 2, cx1 + 6, gridBottom());
        for (int i = 0; i < CustomFonts.ALL.size(); i++) {
            int x = cardX(i);
            int y = cardY(i);
            if (y + CARD_H < gridTop() - 2 || y > gridBottom()) {
                continue;
            }
            CustomFonts.Entry entry = CustomFonts.ALL.get(i);
            boolean hover = cardHovered(i, mouseX, mouseY);
            boolean chosen = i == selected;
            hoverAnim[i] = NovaGui.approach(hoverAnim[i], hover ? 1.0F : 0.0F, 14.0F, dt);
            NovaShapes.rect(graphics, x, y, x + width, y + CARD_H, 5, NovaGui.mix(NovaGui.CARD, NovaGui.RAISED, hoverAnim[i]));
            NovaShapes.outline(graphics, x, y, x + width, y + CARD_H, 5, chosen ? accent : NovaGui.mix(NovaGui.BORDER, accent, hoverAnim[i] * 0.6F));
            // Name in der Schrift selbst, rechts ein kurzes Beispiel (ebenfalls in der Schrift)
            graphics.text(font, inFont(i, entry.label()), x + 7, y + 6, chosen ? 0xFFFFFFFF : NovaGui.TEXT, false);
            Component sample = inFont(i, SAMPLE);
            graphics.text(font, sample, x + width - 7 - font.width(sample), y + 6, NovaGui.TEXT_MUTED, false);
            pointer |= hover;
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            // Bildlaufleiste
            int barTop = gridTop();
            int barHeight = gridBottom() - gridTop();
            int thumb = Math.max(12, (int) (barHeight * barHeight / (double) (barHeight + maxScroll())));
            int thumbY = barTop + (int) Math.round((barHeight - thumb) * (scroll / maxScroll()));
            NovaShapes.rect(graphics, cx1 + 2, thumbY, cx1 + 4, thumbY + thumb, 1, NovaGui.BORDER);
        }

        // Grosse Vorschau der gewaehlten Schrift
        int py = cy1 - PREVIEW_H;
        NovaShapes.rect(graphics, cx0, py, cx1, cy1, 6, NovaGui.CARD);
        NovaShapes.outline(graphics, cx0, py, cx1, cy1, 6, NovaGui.BORDER);
        CustomFonts.Entry current = CustomFontMod.current();
        int index = Mth.clamp(selected, 0, CustomFonts.ALL.size() - 1);
        String tag = current.label() + "  ·  " + current.style();
        graphics.text(font, inFont(index, PREVIEW_1), cx0 + 8, py + 6, NovaGui.TEXT, true);
        graphics.text(font, inFont(index, PREVIEW_2), cx0 + 8, py + 20, NovaGui.TEXT_DIM, true);
        NovaGui.text(graphics, font, tag, cx1 - 8 - NovaGui.width(font, tag), py + 21, NovaGui.TEXT_MUTED);
        return pointer;
    }
}
