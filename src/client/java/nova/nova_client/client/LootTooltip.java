package nova.nova_client.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Tooltip-Bild der Loot Preview: Inhalt als Raster (9 Spalten) mit Items und Anzahl, getoent in der Item-Farbe.
 */
public final class LootTooltip {
    private static final int COLUMNS = 9;
    private static final int SLOT = 18;

    private LootTooltip() {
    }

    /** Daten fuer den Tooltip (serverseitig unbekannt, nur clientseitig benutzt). */
    public record Preview(List<ItemStack> items, int tint) implements TooltipComponent {
    }

    /** Zeichnet die Vorschau im Tooltip. */
    public record Client(Preview preview) implements ClientTooltipComponent {
        private int rows() {
            return Math.max(1, (preview.items().size() + COLUMNS - 1) / COLUMNS);
        }

        @Override
        public int getHeight(Font font) {
            return rows() * SLOT + 6;
        }

        @Override
        public int getWidth(Font font) {
            return COLUMNS * SLOT + 4;
        }

        @Override
        public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
            int tint = preview.tint();
            int w = COLUMNS * SLOT + 4;
            int h = rows() * SLOT + 4;
            // Hintergrund in gedaempfter Item-Farbe, Slots leicht abgesetzt
            graphics.fill(x, y, x + w, y + h, NovaGui.mix(0xFF101014, tint, 0.35F));
            graphics.fill(x, y, x + w, y + 1, NovaGui.mix(0xFF101014, tint, 0.8F));
            List<ItemStack> items = preview.items();
            for (int i = 0; i < rows() * COLUMNS; i++) {
                int sx = x + 2 + (i % COLUMNS) * SLOT;
                int sy = y + 2 + (i / COLUMNS) * SLOT;
                graphics.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x40000000);
                if (i < items.size() && !items.get(i).isEmpty()) {
                    ItemStack stack = items.get(i);
                    graphics.item(stack, sx + 1, sy + 1);
                    graphics.itemDecorations(font, stack, sx + 1, sy + 1);
                }
            }
        }
    }
}
