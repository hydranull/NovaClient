package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import nova.nova_client.client.mods.BlockEspMod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Auswahl der Bloecke, die BlockESP umrandet: Suche, Filter "nur ausgewaehlte", Raster mit Icons.
 */
public class BlockEspMenu extends NovaSettingsScreen {
    private static final int TILE_HEIGHT = 22;
    private static final int TILE_GAP = 4;
    private static final int MIN_TILE_WIDTH = 140;

    private record Entry(Block block, String name, String search, ItemStack icon) {
    }

    private static List<Entry> allEntries;
    /** Liste mit Icons gebaut? Im Hauptmenue gibt es noch keine Item-Daten - dann spaeter neu bauen. */
    private static boolean entriesHaveIcons;

    private final Map<Block, Float> hoverAnim = new IdentityHashMap<>();
    private EditBox searchBox;
    private String search = "";
    private boolean onlySelected;
    private float scroll;
    private float scrollTarget;
    private boolean draggingOpacity;

    private int columns;
    private int tileWidth;
    private int gridY0;

    public BlockEspMenu(Screen parent) {
        super(parent, "BlockESP");
    }

    private static List<Entry> entries() {
        if (allEntries == null || !entriesHaveIcons && NovaGui.itemsReady()) {
            entriesHaveIcons = NovaGui.itemsReady();
            List<Entry> list =  new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
                    continue;
                }
                String name = block.getName().getString();
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                ItemStack icon = NovaGui.stack(block.asItem());
                list.add(new Entry(block, name, (name + " " + id).toLowerCase(Locale.ROOT), icon));
            }
            list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            allEntries = list;
        }
        return allEntries;
    }

    @Override
    protected void init() {
        super.init();
        gridY0 = cy0 + 44;
        int gridWidth = cx1 - cx0;
        columns = Math.max(1, (gridWidth + TILE_GAP) / (MIN_TILE_WIDTH + TILE_GAP));
        tileWidth = (gridWidth - TILE_GAP * (columns - 1)) / columns;

        searchBox = new EditBox(font, cx0 + 6, cy0 + 5, Math.min(170, (cx1 - cx0) / 2) - 10, 10, Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(40);
        searchBox.setTextColor(NovaGui.TEXT);
        searchBox.setTextShadow(false);
        searchBox.setCanLoseFocus(false);
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            search = value;
            scrollTarget = 0.0F;
        });
        NovaGui.themeFont(searchBox);
        addRenderableWidget(searchBox);
        setInitialFocus(searchBox);
    }

    @Override
    protected String subtitle() {
        return BlockEspMod.selectedCount() + " selected";
    }

    // Gefilterte Liste nur neu bauen, wenn sich Suche, Filter oder Auswahl aendern (sonst jeden Frame ~1100 Bloecke)
    private List<Entry> visibleCache;
    private String visibleQuery;
    private boolean visibleOnlySelected;
    private int visibleSelectedCount = -1;

    private List<Entry> visible() {
        String query = search.trim().toLowerCase(Locale.ROOT);
        int selectedCount = BlockEspMod.selectedCount();
        if (visibleCache != null && query.equals(visibleQuery) && onlySelected == visibleOnlySelected
                && selectedCount == visibleSelectedCount) {
            return visibleCache;
        }
        visibleQuery = query;
        visibleOnlySelected = onlySelected;
        visibleSelectedCount = selectedCount;
        visibleCache = filter(query);
        return visibleCache;
    }

    private List<Entry> filter(String query) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries()) {
            if (onlySelected && !BlockEspMod.isSelected(entry.block())) {
                continue;
            }
            if (query.isEmpty() || entry.search().contains(query)) {
                result.add(entry);
            }
        }
        return result;
    }

    private int maxScroll(int count) {
        int rows = (count + columns - 1) / columns;
        return Math.max(0, rows * (TILE_HEIGHT + TILE_GAP) - TILE_GAP - (cy1 - gridY0));
    }

    // Buttons rechts neben der Suche
    private int filterX0() {
        return clearX0() - 6 - filterWidth();
    }

    private int filterWidth() {
        return NovaGui.width(font, "Selected only") + 26;
    }

    private int clearX0() {
        return cx1 - clearWidth();
    }

    private int tracerWidth() {
        return NovaGui.width(font, "Tracers") + 26;
    }

    private int tracerX0() {
        return filterX0() - 6 - tracerWidth();
    }

    private int clearWidth() {
        return NovaGui.width(font, "Clear") + 14;
    }

    // Deckkraft-Regler (Zeile unter der Suche)
    private int opacityY() {
        return cy0 + 24;
    }

    private int opacitySliderX0() {
        return cx0 + NovaGui.width(font, "Opacity") + 14;
    }

    private int opacitySliderX1() {
        return cx1 - NovaGui.width(font, "100%") - 12;
    }

    private boolean opacityHovered(double mouseX, double mouseY) {
        return isHovering((int) mouseX, (int) mouseY, opacitySliderX0() - 6, opacityY() - 2,
                opacitySliderX1() + 6, opacityY() + 12);
    }

    private void setOpacityFromMouse(double mouseX) {
        float t = (float) ((mouseX - opacitySliderX0()) / (opacitySliderX1() - opacitySliderX0()));
        BlockEspMod.setOpacity(Math.round(Math.max(0.0F, Math.min(1.0F, t)) * 100.0F));
    }

    private int tileX(int index) {
        return cx0 + (index % columns) * (tileWidth + TILE_GAP);
    }

    private int tileY(int index) {
        return gridY0 + (index / columns) * (TILE_HEIGHT + TILE_GAP) - Math.round(scroll);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x();
        int my = (int) event.y();
        if (event.button() == 0) {
            if (isHovering(mx, my, filterX0(), cy0, filterX0() + filterWidth(), cy0 + 17)) {
                onlySelected = !onlySelected;
                scrollTarget = 0.0F;
                NovaGui.clickSound();
                return true;
            }
            if (isHovering(mx, my, tracerX0(), cy0, tracerX0() + tracerWidth(), cy0 + 17)) {
                BlockEspMod.tracers = !BlockEspMod.tracers;
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (isHovering(mx, my, clearX0(), cy0, cx1, cy0 + 17)) {
                BlockEspMod.clearSelection();
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (opacityHovered(event.x(), event.y())) {
                draggingOpacity = true;
                setOpacityFromMouse(event.x());
                return true;
            }
            if (my >= gridY0 && my < cy1) {
                List<Entry> list = visible();
                for (int i = 0; i < list.size(); i++) {
                    int x = tileX(i);
                    int y = tileY(i);
                    if (isHovering(mx, my, x, y, x + tileWidth - 1, y + TILE_HEIGHT - 1)) {
                        Block block = list.get(i).block();
                        BlockEspMod.setSelected(block, !BlockEspMod.isSelected(block));
                        ModConfig.save();
                        NovaGui.clickSound();
                        return true;
                    }
                }
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
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingOpacity) {
            draggingOpacity = false;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (opacityHovered(mouseX, mouseY)) {
            BlockEspMod.setOpacity(BlockEspMod.opacity + (scrollY > 0 ? 5 : -5));
            ModConfig.save();
            return true;
        }
        int max = maxScroll(visible().size());
        scrollTarget = Math.max(0.0F, Math.min(max, scrollTarget - (float) scrollY * 30.0F));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && !search.isEmpty()) {
            searchBox.setValue("");
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        List<Entry> list = visible();
        int max = maxScroll(list.size());
        scrollTarget = Math.max(0.0F, Math.min(max, scrollTarget));
        scroll = NovaGui.approach(scroll, scrollTarget, 16.0F, dt);
        if (Math.abs(scroll - scrollTarget) < 0.05F) {
            scroll = scrollTarget;
        }

        // Suche
        drawField(graphics, searchBox, "Search blocks...");

        // Filter-Button
        int fx0 = filterX0();
        boolean filterHover = isHovering(mouseX, mouseY, fx0, cy0, fx0 + filterWidth(), cy0 + 17);
        pointer |= filterHover;
        NovaShapes.rect(graphics, fx0, cy0, fx0 + filterWidth(), cy0 + 18, 5, onlySelected ? NovaGui.alpha(accent, 0.2F) : (filterHover ? NovaGui.RAISED : NovaGui.CARD));
        NovaShapes.outline(graphics, fx0, cy0, fx0 + filterWidth(), cy0 + 18, 5, onlySelected ? accent : NovaGui.BORDER);
        drawCheck(graphics, fx0 + 6, cy0 + 5, onlySelected, accent);
        NovaGui.text(graphics, font, "Selected only", fx0 + 18, cy0 + 5, onlySelected ? NovaGui.TEXT : NovaGui.TEXT_DIM);

        // Tracer-Button
        int tx0 = tracerX0();
        boolean tracerOn = BlockEspMod.tracers;
        boolean tracerHover = isHovering(mouseX, mouseY, tx0, cy0, tx0 + tracerWidth(), cy0 + 17);
        pointer |= tracerHover;
        NovaShapes.rect(graphics, tx0, cy0, tx0 + tracerWidth(), cy0 + 18, 5, tracerOn ? NovaGui.alpha(accent, 0.2F) : (tracerHover ? NovaGui.RAISED : NovaGui.CARD));
        NovaShapes.outline(graphics, tx0, cy0, tx0 + tracerWidth(), cy0 + 18, 5, tracerOn ? accent : NovaGui.BORDER);
        drawCheck(graphics, tx0 + 6, cy0 + 5, tracerOn, accent);
        NovaGui.text(graphics, font, "Tracers", tx0 + 18, cy0 + 5, tracerOn ? NovaGui.TEXT : NovaGui.TEXT_DIM);

        // Leeren-Button
        int lx0 = clearX0();
        boolean clearHover = isHovering(mouseX, mouseY, lx0, cy0, cx1, cy0 + 17);
        pointer |= clearHover;
        NovaShapes.rect(graphics, lx0, cy0, cx1, cy0 + 18, 5, clearHover ? NovaGui.alpha(NovaGui.DANGER, 0.18F) : NovaGui.CARD);
        NovaShapes.outline(graphics, lx0, cy0, cx1, cy0 + 18, 5, clearHover ? NovaGui.DANGER : NovaGui.BORDER);
        NovaGui.centered(graphics, font, "Clear", (lx0 + cx1) / 2, cy0 + 5, clearHover ? NovaGui.DANGER : NovaGui.TEXT_DIM);

        // Deckkraft-Regler
        int oy = opacityY();
        int ox0 = opacitySliderX0();
        int ox1 = opacitySliderX1();
        boolean opacityHover = opacityHovered(mouseX, mouseY) || draggingOpacity;
        pointer |= opacityHover;
        NovaGui.text(graphics, font, "Opacity", cx0 + 2, oy + 1, opacityHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaShapes.rect(graphics, ox0, oy + 3, ox1, oy + 7, 2, NovaGui.SWITCH_OFF);
        int okx = ox0 + Math.round((ox1 - ox0) * BlockEspMod.opacity / 100.0F);
        NovaShapes.rect(graphics, ox0, oy + 3, okx, oy + 7, 2, accent);
        NovaControls.knob(graphics, okx, oy + 5, opacityHover, accent);
        String percent = BlockEspMod.opacity + "%";
        NovaGui.text(graphics, font, percent, cx1 - NovaGui.width(font, percent), oy + 1, NovaGui.TEXT);

        if (list.isEmpty()) {
            String text = onlySelected ? "No blocks selected" : "No results";
            NovaGui.centered(graphics, font, text, (cx0 + cx1) / 2, (gridY0 + cy1) / 2 - 4, NovaGui.TEXT_DIM);
            return pointer;
        }

        boolean mouseInGrid = mouseY >= gridY0 && mouseY < cy1;
        graphics.enableScissor(cx0 - 3, gridY0, cx1 + 3, cy1);
        for (int i = 0; i < list.size(); i++) {
            int x0 = tileX(i);
            int y0 = tileY(i);
            if (y0 + TILE_HEIGHT < gridY0 || y0 > cy1) {
                continue;
            }
            Entry entry = list.get(i);
            int x1 = x0 + tileWidth;
            int y1 = y0 + TILE_HEIGHT;
            boolean selected = BlockEspMod.isSelected(entry.block());
            int color = BlockEspMod.colorOf(entry.block());

            boolean hovered = mouseInGrid && isHovering(mouseX, mouseY, x0, y0, x1 - 1, y1 - 1);
            pointer |= hovered;
            float h = NovaGui.approach(hoverAnim.getOrDefault(entry.block(), 0.0F), hovered ? 1.0F : 0.0F, 14.0F, dt);
            hoverAnim.put(entry.block(), h);

            NovaShapes.rect(graphics, x0, y0, x1, y1, 4, NovaGui.mix(NovaGui.CARD, NovaGui.RAISED, h));
            if (selected) {
                NovaShapes.rect(graphics, x0, y0, x1, y1, 4, NovaGui.alpha(color, 0.14F));
            }
            NovaShapes.outline(graphics, x0, y0, x1, y1, 4, selected ? NovaGui.alpha(color, 0.9F) : NovaGui.mix(NovaGui.BORDER, NovaGui.TEXT_DIM, h * 0.4F));

            if (entry.icon().isEmpty()) {
                NovaShapes.rect(graphics, x0 + 6, y0 + 6, x0 + 16, y0 + 16, 2, color);
            } else {
                graphics.item(entry.icon(), x0 + 3, y0 + 3);
            }

            int textMax = tileWidth - 24 - 16;
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, entry.name(), textMax), x0 + 23, y0 + 7,
                    selected ? NovaGui.TEXT : NovaGui.mix(NovaGui.TEXT_DIM, NovaGui.TEXT, h));
            drawCheck(graphics, x1 - 14, y0 + 7, selected, color);
        }
        graphics.disableScissor();

        if (max > 0) {
            int viewHeight = cy1 - gridY0;
            int thumbHeight = Math.max(16, viewHeight * viewHeight / (viewHeight + max));
            int thumbY = gridY0 + Math.round((viewHeight - thumbHeight) * (scroll / max));
            int sx = px1 - 7;
            NovaShapes.rect(graphics, sx, gridY0, sx + 3, cy1, 1, NovaGui.TRACK);
            NovaShapes.rect(graphics, sx, thumbY, sx + 3, thumbY + thumbHeight, 1, NovaGui.alpha(accent, 0.9F));
        }
        return pointer;
    }

    /** Kleines Haekchen-Kaestchen (8x8). */
    private static void drawCheck(GuiGraphicsExtractor graphics, int x, int y, boolean checked, int color) {
        if (checked) {
            NovaShapes.rect(graphics, x, y, x + 8, y + 8, 2, color);
            graphics.fill(x + 2, y + 4, x + 3, y + 6, NovaGui.ON_ACCENT);
            graphics.fill(x + 3, y + 5, x + 4, y + 7, NovaGui.ON_ACCENT);
            graphics.fill(x + 4, y + 4, x + 5, y + 6, NovaGui.ON_ACCENT);
            graphics.fill(x + 5, y + 2, x + 6, y + 5, NovaGui.ON_ACCENT);
        } else {
            NovaShapes.outline(graphics, x, y, x + 8, y + 8, 2, NovaGui.TEXT_MUTED);
        }
    }
}
