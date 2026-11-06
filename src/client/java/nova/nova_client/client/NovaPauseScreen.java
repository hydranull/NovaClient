package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

/**
 * Das Pausemenue im Nova-Look. Alle Knoepfe und ihre Aktionen kommen von Minecraft (so bleibt alles wie gewohnt und
 * funktioniert auf jedem Server) - gezeichnet wird aber selbst: die Wortmarke statt "Spielmenue", die Knoepfe als
 * Nova-Knoepfe (NovaControls) und unten zwei Extras: Module und Discord.
 */
public class NovaPauseScreen extends PauseScreen {
    private int markTop;

    public NovaPauseScreen() {
        super(true);
    }

    /** Alle sichtbaren Elemente (Screen.renderables ist privat). */
    private java.util.List<Renderable> widgets() {
        return ((nova.nova_client.mixin.client.ScreenRenderablesAccessor) this).nova_client$renderables();
    }

    @Override
    protected void init() {
        super.init();
        int top = height;
        int bottom = 0;
        for (Renderable renderable : widgets()) {
            if (renderable instanceof AbstractWidget widget && !(widget instanceof StringWidget)) {
                top = Math.min(top, widget.getY());
                bottom = Math.max(bottom, widget.getY() + widget.getHeight());
            }
        }
        if (bottom == 0) {
            return;
        }
        int y = bottom + 4;
        addRenderableWidget(Button.builder(Component.literal("Modules"), b -> minecraft.gui.setScreen(UiSettings.mainMenu()))
                .bounds(width / 2 - 102, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Discord"), b -> NovaLinks.openDiscord())
                .bounds(width / 2 + 2, y, 100, 20).build());
        markTop = Math.max(6, top - 40);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Den Hintergrund (Unschaerfe) zeichnet Minecraft vorher selbst - hier nie nochmal (nur einmal pro Bild erlaubt)
        NovaGui.background(graphics, width, height);
        NovaType.Face display = NovaType.Face.DISPLAY;
        int markW = NovaType.width(font, display, "nova");
        int markX = (width - markW) / 2;
        NovaType.draw(graphics, font, display, "nova", markX, markTop + 22 - NovaType.BASELINE, NovaGui.TEXT);
        for (Renderable renderable : widgets()) {
            if (renderable instanceof StringWidget) {
                continue; // "Spielmenue" - die Wortmarke steht dafuer da
            }
            if (renderable instanceof Button button && !(button instanceof SpriteIconButton)) {
                drawButton(graphics, button);
            } else {
                //? if >=26.1 {
                renderable.extractRenderState(graphics, mouseX, mouseY, partialTick);
                //?} else {
                /*renderable.render(graphics, mouseX, mouseY, partialTick);
                *///?}
            }
        }
    }

    private void drawButton(GuiGraphicsExtractor g, Button button) {
        NovaControls.button(g, font, button.getX(), button.getY(), button.getX() + button.getWidth(),
                button.getY() + button.getHeight(), button.getMessage().getString(), NovaControls.Variant.SECONDARY,
                button.isHoveredOrFocused(), button.active);
    }
}
