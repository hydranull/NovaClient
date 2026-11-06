package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import nova.nova_client.client.mods.BlockEspMod;
import nova.nova_client.client.mods.BlockNotifierMod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer BlockNotifier: Bloecke auswaehlen (Suche, "nur ausgewaehlte", Raster mit Icons),
 * Ton an/aus und wie lange ein Toast sichtbar bleibt.
 */
public class BlockNotifierMenu extends NovaSettingsScreen {
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
    private boolean draggingDuration;

    private int columns;
    private int tileWidth;
    private int gridY0;

    private List<Entry> visibleCache;
    private String visibleQuery;
    private boolean visibleOnlySelected;
    private int visibleSelectedCount = -1;

    public BlockNotifierMenu(Screen parent) {
        super(parent, "BlockNotifier");
    }

    private static List<Entry> entries() {
        if (allEntries == null || !entriesHaveIcons && NovaGui.itemsReady()) {
            entriesHaveIcons = NovaGui.itemsReady();
            List<Entry> list = new ArrayList<>();
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
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        return BlockNotifierMod.selectedCount() + " selected  ·  toast top right when found";
    }

    private List<Entry> visible() {
        String query = search.trim().toLowerCase(Locale.ROOT);
        int selectedCount = BlockNotifierMod.selectedCount();
        if (visibleCache != null && query.equals(visibleQuery) && onlySelected == visibleOnlySelected
                && selectedCount == visibleSelectedCount) {
            return visibleCache;
        }
        visibleQuery = query;
        visibleOnlySelected = onlySelected;
        visibleSelectedCount = selectedCount;
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries()) {
            if (onlySelected && !BlockNotifierMod.isSelected(entry.block())) {
                continue;
            }
            if (query.isEmpty() || entry.search().contains(query)) {
                result.add(entry);
            }
        }
        visibleCache = result;
        return result;
    }

    private int maxScroll(int count) {
        int rows = (count + columns - 1) / columns;
        return Math.max(0, rows * (TILE_HEIGHT + TILE_GAP) - TILE_GAP - (cy1 - gridY0));
    }

    // ---------------------------------------------------------------- Geometrie (Buttons rechts neben der Suche)

    private int clearWidth() {
        return NovaGui.width(font, "Clear") + 14;
    }

    private int clearX0() {
        return cx1 - clearWidth();
    }

    private int filterWidth() {
        return NovaGui.width(font, "Selected only") + 26;
    }

    private int filterX0() {
        return clearX0() - 6 - filterWidth();
    }

    private int soundWidth() {
        return NovaGui.width(font, "Sound") + 26;
    }

    private int soundX0() {
        return filterX0() - 6 - soundWidth();
    }

    // Dauer-Regler (Zeile unter der Suche)
    private int durationY() {
        return cy0 + 24;
    }

    private int durationX0() {
        return cx0 + NovaGui.width(font, "Duration") + 14;
    }

    private int durationX1() {
        return cx1 - NovaGui.width(font, "15 s") - 12;
    }

    private boolean durationHovered(double mouseX, double mouseY) {
        return isHovering((int) mouseX, (int) mouseY, durationX0() - 6, durationY() - 2, durationX1() + 6, durationY() + 12);
    }

    private void setDurationFromMouse(double mouseX) {
        float t = (float) ((mouseX - durationX0()) / (durationX1() - durationX0()));
        BlockNotifierMod.duration = 1 + Math.round(Mth.clamp(t, 0.0F, 1.0F) * 14.0F);
    }

    private int tileX(int index) {
        return cx0 + (index % columns) * (tileWidth + TILE_GAP);
    }

    private int tileY(int index) {
        return gridY0 + (index / columns) * (TILE_HEIGHT + TILE_GAP) - Math.round(scroll);
    }

    // ---------------------------------------------------------------- Eingabe

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
            if (isHovering(mx, my, soundX0(), cy0, soundX0() + soundWidth(), cy0 + 17)) {
                BlockNotifierMod.sound = !BlockNotifierMod.sound;
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (isHovering(mx, my, clearX0(), cy0, cx1, cy0 + 17)) {
                BlockNotifierMod.clearSelection();
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (durationHovered(event.x(), event.y())) {
                draggingDuration = true;
                setDurationFromMouse(event.x());
                return true;
            }
            if (my >= gridY0 && my < cy1) {
                List<Entry> list = visible();
                for (int i = 0; i < list.size(); i++) {
                    int x = tileX(i);
                    int y = tileY(i);
                    if (isHovering(mx, my, x, y, x + tileWidth - 1, y + TILE_HEIGHT - 1)) {
                        Block block = list.get(i).block();
                        BlockNotifierMod.setSelected(block, !BlockNotifierMod.isSelected(block));
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
        if (draggingDuration) {
            setDurationFromMouse(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingDuration) {
            draggingDuration = false;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (durationHovered(mouseX, mouseY)) {
            BlockNotifierMod.duration = Mth.clamp(BlockNotifierMod.duration + (scrollY > 0 ? 1 : -1), 1, 15);
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

    // ---------------------------------------------------------------- Rendering

    private boolean drawToggleButton(GuiGraphicsExtractor graphics, int x0, int width, String label, boolean on,
                                     int mouseX, int mouseY) {
        boolean hover = isHovering(mouseX, mouseY, x0, cy0, x0 + width, cy0 + 17);
        NovaShapes.rect(graphics, x0, cy0, x0 + width, cy0 + 18, 5, on ? NovaGui.alpha(accent, 0.2F) : (hover ? NovaGui.RAISED : NovaGui.CARD));
        NovaShapes.outline(graphics, x0, cy0, x0 + width, cy0 + 18, 5, on ? accent : NovaGui.BORDER);
        drawCheck(graphics, x0 + 6, cy0 + 5, on, accent);
        NovaGui.text(graphics, font, label, x0 + 18, cy0 + 5, on ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        return hover;
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

        drawField(graphics, searchBox, "Search blocks...");
        pointer |= drawToggleButton(graphics, filterX0(), filterWidth(), "Selected only", onlySelected, mouseX, mouseY);
        pointer |= drawToggleButton(graphics, soundX0(), soundWidth(), "Sound", BlockNotifierMod.sound, mouseX, mouseY);

        // Leeren-Button
        int lx0 = clearX0();
        boolean clearHover = isHovering(mouseX, mouseY, lx0, cy0, cx1, cy0 + 17);
        pointer |= clearHover;
        NovaShapes.rect(graphics, lx0, cy0, cx1, cy0 + 18, 5, clearHover ? NovaGui.alpha(NovaGui.DANGER, 0.18F) : NovaGui.CARD);
        NovaShapes.outline(graphics, lx0, cy0, cx1, cy0 + 18, 5, clearHover ? NovaGui.DANGER : NovaGui.BORDER);
        NovaGui.centered(graphics, font, "Clear", (lx0 + cx1) / 2, cy0 + 5, clearHover ? NovaGui.DANGER : NovaGui.TEXT_DIM);

        // Dauer-Regler
        int dy = durationY();
        int dx0 = durationX0();
        int dx1 = durationX1();
        boolean durationHover = durationHovered(mouseX, mouseY) || draggingDuration;
        pointer |= durationHover;
        NovaGui.text(graphics, font, "Duration", cx0 + 2, dy + 1, durationHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaShapes.rect(graphics, dx0, dy + 3, dx1, dy + 7, 2, NovaGui.SWITCH_OFF);
        int kx = dx0 + Math.round((dx1 - dx0) * (BlockNotifierMod.duration - 1) / 14.0F);
        NovaShapes.rect(graphics, dx0, dy + 3, kx, dy + 7, 2, accent);
        NovaControls.knob(graphics, kx, dy + 5, durationHover, accent);
        String seconds = BlockNotifierMod.duration + " s";
        NovaGui.text(graphics, font, seconds, cx1 - NovaGui.width(font, seconds), dy + 1, NovaGui.TEXT);

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
            boolean selected = BlockNotifierMod.isSelected(entry.block());
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
