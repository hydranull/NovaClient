package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.farmbuilder.BuildPlan;
import nova.nova_client.client.farmbuilder.Estimate;
import nova.nova_client.client.farmbuilder.FarmBot;
import nova.nova_client.client.farmbuilder.Schematic;
import nova.nova_client.client.farmbuilder.Tools;
import nova.nova_client.client.mods.SchematicBuilderMod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Menue fuer den Schematic Builder: Datei waehlen (oder hineinziehen), Platz festlegen, Kisten markieren,
 * Materialliste ansehen, Optionen - und unten immer Status, Fortschritt, Restzeit und Start/Pause.
 */
public class SchematicBuilderMenu extends NovaSettingsScreen {
    private static final String[] TABS = {"Setup", "Materials", "Options"};
    private static final int FILE_ROW = 13;
    private static final int MAT_ROW = 17;
    private static final int OPT_ROW = 20;

    private static int tab;

    private record Button(int x0, int y0, int x1, int y1, Runnable action) {
        boolean hit(double x, double y) {
            return isHovering((int) x, (int) y, x0, y0, x1, y1);
        }
    }

    private final List<Button> buttons = new ArrayList<>();
    private List<String> files = new ArrayList<>();
    private float fileScroll;
    private float matScroll;
    /** Wartet auf einen Tastendruck fuer die Stopp-Taste. */
    private boolean listeningForKey;
    private long filesListedAt;

    public SchematicBuilderMenu(Screen parent) {
        super(parent, "Schematic Builder");
    }

    @Override
    protected void init() {
        super.init();
        setPanel(540, 284);
        refreshFiles();
    }

    private void refreshFiles() {
        files = SchematicBuilderMod.listFiles();
        filesListedAt = Util.getMillis();
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    // ---------------------------------------------------------------- Geometrie

    private int contentTop() {
        return cy0 + 22;
    }

    private int bottomTop() {
        return py1 - 44;
    }

    private int splitX() {
        return cx0 + (cx1 - cx0) * 42 / 100;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (Button b : new ArrayList<>(buttons)) {
                if (b.hit(event.x(), event.y())) {
                    NovaGui.clickSound();
                    b.action().run();
                    ModConfig.save();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listeningForKey) {
            SchematicBuilderMod mod = SchematicBuilderMod.instance();
            if (mod != null && !event.isEscape()) {
                boolean clear = event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE;
                mod.keyCode = clear ? nova.nova_client.client.mods.Mod.NO_KEY : event.key();
                ModConfig.save();
            }
            listeningForKey = false;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab == 0 && mouseX < splitX()) {
            fileScroll = Math.max(0, fileScroll - (float) scrollY * FILE_ROW * 2);
            return true;
        }
        if (tab == 1) {
            matScroll = Math.max(0, matScroll - (float) scrollY * MAT_ROW * 2);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        String first = SchematicBuilderMod.importFiles(paths);
        refreshFiles();
        if (first != null) {
            SchematicBuilderMod.load(first);
            tab = 0;
            ModConfig.save();
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    private boolean button(GuiGraphicsExtractor g, int mouseX, int mouseY, int x0, int y0, int x1, int y1, String label,
                           boolean active, Runnable action) {
        boolean hover = isHovering(mouseX, mouseY, x0, y0, x1, y1);
        NovaControls.choice(g, font, x0, y0, x1, y1, label, hover, active, accent);
        buttons.add(new Button(x0, y0, x1, y1, action));
        return hover;
    }

    @Override
    protected boolean drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt) {
        buttons.clear();
        if (Util.getMillis() - filesListedAt > 3000) {
            refreshFiles();
        }
        boolean pointer = false;
        // Tabs
        int tw = 74;
        for (int i = 0; i < TABS.length; i++) {
            int x0 = cx0 + i * (tw + 6);
            final int index = i;
            pointer |= button(g, mouseX, mouseY, x0, cy0, x0 + tw, cy0 + 16, TABS[i], tab == i, () -> tab = index);
        }
        g.enableScissor(cx0 - 4, contentTop() - 2, cx1 + 4, bottomTop() - 4);
        pointer |= switch (tab) {
            case 1 -> drawMaterials(g, mouseX, mouseY);
            case 2 -> drawOptions(g, mouseX, mouseY);
            default -> drawSetup(g, mouseX, mouseY);
        };
        g.disableScissor();
        pointer |= drawBottom(g, mouseX, mouseY);
        return pointer;
    }

    // ---------------------------------------------------------------- Setup

    private boolean drawSetup(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean pointer = false;
        int top = contentTop();
        int bottom = bottomTop() - 6;
        int split = splitX();

        // Linke Spalte: Dateien
        NovaGui.text(g, font, "Schematics", cx0, top, NovaGui.TEXT);
        int listTop = top + 12;
        int listBottom = bottom - 22;
        NovaShapes.rect(g, cx0 - 2, listTop - 2, split - 8, listBottom + 2, 4, NovaGui.INSET);
        if (files.isEmpty()) {
            NovaGui.text(g, font, NovaGui.ellipsize(font, "No files yet.", split - cx0 - 14), cx0 + 4, listTop + 4, NovaGui.TEXT_MUTED);
            NovaGui.text(g, font, NovaGui.ellipsize(font, "Drop a .litematic here", split - cx0 - 14), cx0 + 4, listTop + 16, NovaGui.TEXT_MUTED);
            NovaGui.text(g, font, NovaGui.ellipsize(font, "or put it in /schematics", split - cx0 - 14), cx0 + 4, listTop + 28, NovaGui.TEXT_MUTED);
        } else {
            int visible = Math.max(1, (listBottom - listTop) / FILE_ROW);
            fileScroll = Mth.clamp(fileScroll, 0, Math.max(0, (files.size() - visible) * FILE_ROW));
            g.enableScissor(cx0 - 2, listTop, split - 8, listBottom);
            int y = listTop - Math.round(fileScroll);
            for (String file : files) {
                if (y + FILE_ROW >= listTop && y <= listBottom) {
                    boolean selected = file.equals(SchematicBuilderMod.fileName) && SchematicBuilderMod.schematic != null;
                    boolean hover = isHovering(mouseX, mouseY, cx0, y, split - 10, y + FILE_ROW - 1)
                            && mouseY >= listTop && mouseY <= listBottom;
                    if (selected || hover) {
                        NovaShapes.rect(g, cx0, y, split - 10, y + FILE_ROW - 1, 3, selected ? NovaGui.alpha(accent, 0.3F) : NovaGui.RAISED);
                    }
                    NovaGui.text(g, font, NovaGui.ellipsize(font, file, split - cx0 - 18), cx0 + 3, y + 2,
                            selected ? NovaGui.TEXT : NovaGui.TEXT_DIM);
                    if (y >= listTop - 2 && y + FILE_ROW <= listBottom + 2) {
                        buttons.add(new Button(cx0, Math.max(y, listTop), split - 10, Math.min(y + FILE_ROW - 1, listBottom),
                                () -> SchematicBuilderMod.load(file)));
                    }
                    pointer |= hover;
                }
                y += FILE_ROW;
            }
            g.disableScissor();
        }
        int by = bottom - 16;
        int mid = (cx0 + split - 8) / 2;
        pointer |= button(g, mouseX, mouseY, cx0 - 2, by, mid - 2, by + 16, "Open folder", false,
                () -> Compat.openPath(SchematicBuilderMod.schematicDir()));
        pointer |= button(g, mouseX, mouseY, mid + 2, by, split - 8, by + 16, "Refresh", false, this::refreshFiles);

        // Rechte Spalte: Infos, Position, Kisten
        int x = split + 4;
        int y = top;
        Schematic s = SchematicBuilderMod.schematic;
        BuildPlan plan = SchematicBuilderMod.plan();
        if (s == null) {
            NovaGui.text(g, font, "No schematic loaded", x, y, NovaGui.TEXT);
            if (!SchematicBuilderMod.loadError.isEmpty()) {
                NovaGui.text(g, font, NovaGui.ellipsize(font, "§c" + SchematicBuilderMod.loadError, cx1 - x), x, y + 12, NovaGui.DANGER);
            } else {
                NovaGui.text(g, font, NovaGui.ellipsize(font, "Pick a file on the left.", cx1 - x), x, y + 12, NovaGui.TEXT_MUTED);
            }
            return pointer;
        }
        NovaGui.text(g, font, NovaGui.ellipsize(font, s.name, cx1 - x), x, y, NovaGui.TEXT);
        y += 11;
        NovaGui.text(g, font, NovaGui.ellipsize(font, "by " + s.author, cx1 - x), x, y, NovaGui.TEXT_MUTED);
        y += 14;
        if (plan != null) {
            line(g, x, y, "Needs", plan.sizeX + " x " + plan.sizeZ + " area, " + plan.sizeY + " high", accent);
            y += 11;
            line(g, x, y, "Blocks", String.format(Locale.ROOT, "%,d  (%d materials)", plan.totalPlacements(), plan.materials.size()), NovaGui.TEXT);
            y += 11;
            line(g, x, y, "Time", Estimate.format(SchematicBuilderMod.estimateSeconds()), NovaGui.TEXT);
            y += 11;
            List<String> notes = new ArrayList<>();
            if (plan.fluidCount > 0) {
                notes.add(plan.fluidCount + " water/lava");
            }
            if (s.entityCount > 0) {
                notes.add(s.entityCount + " entities not built");
            }
            if (plan.skippedCount > 0) {
                notes.add(plan.skippedCount + " skipped blocks");
            }
            if (s.unknownBlocks > 0) {
                notes.add(s.unknownBlocks + " unknown");
            }
            if (!notes.isEmpty()) {
                NovaGui.text(g, font, NovaGui.ellipsize(font, String.join(" · ", notes), cx1 - x), x, y, NovaGui.TEXT_MUTED);
                y += 11;
            }
        }
        y += 5;
        // Position
        NovaGui.text(g, font, "Position", x, y, NovaGui.TEXT);
        String pos = SchematicBuilderMod.origin == null ? "not set" : SchematicBuilderMod.origin.toShortString()
                + "  ·  " + SchematicBuilderMod.rotation * 90 + "°";
        NovaGui.text(g, font, NovaGui.ellipsize(font, pos, cx1 - x - 54), x + 50, y, NovaGui.TEXT_MUTED);
        y += 12;
        int w3 = (cx1 - x - 8) / 3;
        pointer |= button(g, mouseX, mouseY, x, y, x + w3, y + 15, "Corner here", false, SchematicBuilderMod::cornerAtFeet);
        pointer |= button(g, mouseX, mouseY, x + w3 + 4, y, x + 2 * w3 + 4, y + 15, "Center here", false, SchematicBuilderMod::centerOnMe);
        pointer |= button(g, mouseX, mouseY, x + 2 * w3 + 8, y, cx1, y + 15, "At crosshair", false, SchematicBuilderMod::cornerAtCrosshair);
        y += 18;
        String[] labels = {"-X", "+X", "-Y", "+Y", "-Z", "+Z", "Rotate"};
        int[][] moves = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        int small = (cx1 - x - 6 * 3 - 20) / 7;
        int bx = x;
        for (int i = 0; i < labels.length; i++) {
            int bw = i == 6 ? cx1 - bx : small;
            final int index = i;
            pointer |= button(g, mouseX, mouseY, bx, y, bx + bw, y + 15, labels[i], false, () -> {
                if (index == 6) {
                    SchematicBuilderMod.rotate();
                } else {
                    SchematicBuilderMod.nudge(moves[index][0], moves[index][1], moves[index][2]);
                }
            });
            bx += bw + 3;
        }
        y += 22;
        // Kisten
        NovaGui.text(g, font, "Chests", x, y, NovaGui.TEXT);
        int indexedMissing = SchematicBuilderMod.CHESTS.unindexedCount();
        String chestInfo = SchematicBuilderMod.CHESTS.chests().size() + " selected"
                + (indexedMissing > 0 ? " (" + indexedMissing + " not opened yet)" : "");
        NovaGui.text(g, font, NovaGui.ellipsize(font, chestInfo, cx1 - x - 54), x + 50, y, NovaGui.TEXT_MUTED);
        y += 12;
        int w2 = (cx1 - x - 4) * 2 / 3;
        pointer |= button(g, mouseX, mouseY, x, y, x + w2, y + 15,
                SchematicBuilderMod.selectingChests ? "Done selecting" : "Select chests", SchematicBuilderMod.selectingChests, () -> {
                    SchematicBuilderMod.selectingChests = !SchematicBuilderMod.selectingChests;
                    if (SchematicBuilderMod.selectingChests) {
                        NovaNotify.chat("Schematic Builder", "§7Open every chest with materials once. Open this menu again when you are done.");
                        Minecraft.getInstance().gui.setScreen(null);
                    }
                });
        pointer |= button(g, mouseX, mouseY, x + w2 + 4, y, cx1, y + 15, "Clear", false, SchematicBuilderMod.CHESTS::clear);
        y += 19;
        NovaGui.text(g, font, NovaGui.ellipsize(font, "Dig out the area first, then put the materials in the chests.", cx1 - x),
                x, y, NovaGui.TEXT_MUTED);
        return pointer;
    }

    private void line(GuiGraphicsExtractor g, int x, int y, String label, String value, int color) {
        NovaGui.text(g, font, label, x, y, NovaGui.TEXT_MUTED);
        NovaGui.text(g, font, NovaGui.ellipsize(font, value, cx1 - x - 46), x + 44, y, color);
    }

    // ---------------------------------------------------------------- Materialien

    private boolean drawMaterials(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = contentTop();
        int bottom = bottomTop() - 6;
        BuildPlan plan = SchematicBuilderMod.plan();
        if (plan == null) {
            NovaGui.text(g, font, "Load a schematic and set a position first.", cx0, top, NovaGui.TEXT_MUTED);
            return false;
        }
        int missingTypes = 0;
        int total = 0;
        for (Map.Entry<Item, Integer> e : plan.materials.entrySet()) {
            total += e.getValue();
            if (SchematicBuilderMod.available(e.getKey()) < e.getValue()) {
                missingTypes++;
            }
        }
        String head = String.format(Locale.ROOT, "%d types · %,d items", plan.materials.size(), total)
                + (missingTypes > 0 ? "  ·  §c" + missingTypes + " missing" : "  ·  §aall there");
        NovaGui.text(g, font, head, cx0, top, NovaGui.TEXT);
        List<String> extras = new ArrayList<>();
        if (plan.sizeY > 3 && SchematicBuilderMod.useTowers) {
            extras.add("~" + Math.min(192, 32 + plan.sizeY * 4) + " dirt/cobble to climb");
        }
        if (!extras.isEmpty()) {
            String tool = "+ " + String.join(", ", extras);
            NovaGui.text(g, font, NovaGui.ellipsize(font, tool, (cx1 - cx0) / 2), cx1 - Math.min(NovaGui.width(font, tool), (cx1 - cx0) / 2),
                    top, NovaGui.TEXT_MUTED);
        }
        int headerY = top + 13;
        int colNeed = cx1 - 150;
        int colHave = cx1 - 90;
        int colState = cx1 - 34;
        NovaGui.text(g, font, "Item", cx0 + 20, headerY, NovaGui.TEXT_MUTED);
        NovaGui.text(g, font, "Need", colNeed, headerY, NovaGui.TEXT_MUTED);
        NovaGui.text(g, font, "Have", colHave, headerY, NovaGui.TEXT_MUTED);
        g.fill(cx0, headerY + 10, cx1, headerY + 11, NovaGui.BORDER);
        int listTop = headerY + 13;
        List<Tools.Kind> tools = Tools.needed(plan);
        int rows = plan.materials.size() + tools.size() + 3;
        int visible = Math.max(1, (bottom - listTop) / MAT_ROW);
        matScroll = Mth.clamp(matScroll, 0, Math.max(0, (rows - visible) * MAT_ROW));
        g.enableScissor(cx0 - 2, listTop, cx1 + 2, bottom);
        int y = listTop - Math.round(matScroll);
        // Werkzeuge: damit baut der Bot Hilfsbloecke und Bloecke im Weg ab
        for (Tools.Kind kind : tools) {
            if (y + MAT_ROW >= listTop && y <= bottom) {
                Item found = SchematicBuilderMod.findTool(kind);
                NovaGui.item(g, NovaGui.stack(found != null ? found : kind.icon), cx0 + 8, y + 7, 0.75F);
                String label = kind.label + " §8- " + kind.purpose;
                NovaGui.text(g, font, NovaGui.ellipsize(font, label, colHave - cx0 - 26), cx0 + 20, y + 3, NovaGui.TEXT);
                String have = found == null ? "none" : FarmBot.nameOf(found);
                NovaGui.text(g, font, NovaGui.ellipsize(font, have, colState - colHave - 4), colHave, y + 3, NovaGui.TEXT_DIM);
                boolean required = kind == Tools.Kind.HOE;
                String state = found != null ? "§a✔" : required ? "§c✖" : "§e?";
                NovaGui.text(g, font, state, colState, y + 3, found != null ? NovaGui.SUCCESS : NovaGui.DANGER);
            }
            y += MAT_ROW;
        }
        // Essen: immer 1 Stapel (egal welches) - bei grossen Bauten wird der Bot hungrig
        if (y + MAT_ROW >= listTop && y <= bottom) {
            int food = SchematicBuilderMod.availableFood();
            NovaGui.item(g, NovaGui.stack(net.minecraft.world.item.Items.BREAD), cx0 + 8, y + 7, 0.75F);
            NovaGui.text(g, font, NovaGui.ellipsize(font, "Food (any) §8- the bot eats when hungry", colNeed - cx0 - 30),
                    cx0 + 20, y + 3, NovaGui.TEXT);
            NovaGui.text(g, font, String.valueOf(SchematicBuilderMod.FOOD_WANTED), colNeed, y + 3, NovaGui.TEXT_DIM);
            NovaGui.text(g, font, String.valueOf(food), colHave, y + 3, NovaGui.TEXT_DIM);
            boolean enough = food >= SchematicBuilderMod.FOOD_WANTED;
            NovaGui.text(g, font, enough ? "§a✔" : "§e-" + (SchematicBuilderMod.FOOD_WANTED - food), colState, y + 3,
                    enough ? NovaGui.SUCCESS : NovaGui.DANGER);
        }
        y += MAT_ROW;
        // Hilfsbloecke: Stuetzen unter schwebenden Teilen, Tuerme - werden am Ende wieder abgebaut
        if (y + MAT_ROW >= listTop && y <= bottom) {
            int helpers = SchematicBuilderMod.availableHelpers();
            NovaGui.item(g, NovaGui.stack(net.minecraft.world.item.Items.DIRT), cx0 + 8, y + 7, 0.75F);
            NovaGui.text(g, font, NovaGui.ellipsize(font, "Helper blocks (dirt/cobble) §8- supports, removed again",
                    colNeed - cx0 - 30), cx0 + 20, y + 3, NovaGui.TEXT);
            NovaGui.text(g, font, String.valueOf(SchematicBuilderMod.HELPERS_WANTED), colNeed, y + 3, NovaGui.TEXT_DIM);
            NovaGui.text(g, font, String.valueOf(helpers), colHave, y + 3, NovaGui.TEXT_DIM);
            boolean enough = helpers >= SchematicBuilderMod.HELPERS_WANTED;
            NovaGui.text(g, font, enough ? "§a✔" : "§e-" + (SchematicBuilderMod.HELPERS_WANTED - helpers), colState, y + 3,
                    enough ? NovaGui.SUCCESS : NovaGui.DANGER);
        }
        y += MAT_ROW;
        if (y + MAT_ROW >= listTop && y <= bottom) {
            g.fill(cx0, y + MAT_ROW / 2, cx1, y + MAT_ROW / 2 + 1, NovaGui.BORDER);
        }
        y += MAT_ROW;
        for (Map.Entry<Item, Integer> e : plan.materials.entrySet()) {
            if (y + MAT_ROW >= listTop && y <= bottom) {
                Item item = e.getKey();
                int need = e.getValue();
                int have = SchematicBuilderMod.available(item);
                NovaGui.item(g, NovaGui.stack(item), cx0 + 8, y + 7, 0.75F);
                NovaGui.text(g, font, NovaGui.ellipsize(font, FarmBot.nameOf(item), colNeed - cx0 - 30), cx0 + 20, y + 3, NovaGui.TEXT);
                NovaGui.text(g, font, stacks(item, need), colNeed, y + 3, NovaGui.TEXT_DIM);
                NovaGui.text(g, font, String.valueOf(have), colHave, y + 3, NovaGui.TEXT_DIM);
                String state = have >= need ? "§a✔" : "§c-" + (need - have);
                NovaGui.text(g, font, state, colState, y + 3, have >= need ? NovaGui.SUCCESS : NovaGui.DANGER);
            }
            y += MAT_ROW;
        }
        g.disableScissor();
        return false;
    }

    /** "130 (2 st + 2)" */
    private static String stacks(Item item, int count) {
        int max = item.getDefaultMaxStackSize();
        if (max <= 1 || count < max) {
            return String.valueOf(count);
        }
        int full = count / max;
        int rest = count % max;
        return count + " (" + full + "×" + max + (rest > 0 ? "+" + rest : "") + ")";
    }

    // ---------------------------------------------------------------- Optionen

    private boolean drawOptions(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean pointer = false;
        int y = contentTop();
        // Geschwindigkeit
        NovaGui.text(g, font, "Speed", cx0, y + 4, NovaGui.TEXT);
        String speed = "‹ " + SchematicBuilderMod.SPEEDS[SchematicBuilderMod.speedIndex] + " ›";
        pointer |= button(g, mouseX, mouseY, cx1 - 140, y, cx1, y + 16, speed, false,
                () -> SchematicBuilderMod.speedIndex = (SchematicBuilderMod.speedIndex + 1) % SchematicBuilderMod.SPEEDS.length);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Build layer by layer (bottom to top)", SchematicBuilderMod.layerByLayer,
                () -> SchematicBuilderMod.layerByLayer = !SchematicBuilderMod.layerByLayer);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Break blocks that are in the way", SchematicBuilderMod.breakWrong,
                () -> SchematicBuilderMod.breakWrong = !SchematicBuilderMod.breakWrong);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Pause when another player comes close", SchematicBuilderMod.pauseNearPlayers,
                () -> SchematicBuilderMod.pauseNearPlayers = !SchematicBuilderMod.pauseNearPlayers);
        y += OPT_ROW;
        // Stopp-Taste: waehrend des Bauens ignoriert der Bot alle anderen Eingaben
        String key = SchematicBuilderMod.stopKeyName();
        NovaGui.text(g, font, "Stop key (only way to stop while building)", cx0, y + 4, NovaGui.TEXT);
        String keyLabel = listeningForKey ? "Press a key..." : key.isEmpty() ? "§eNot set - click" : key;
        pointer |= button(g, mouseX, mouseY, cx1 - 120, y, cx1, y + 16, keyLabel, listeningForKey,
                () -> listeningForKey = !listeningForKey);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Human breaks and looking around", SchematicBuilderMod.humanPauses,
                () -> SchematicBuilderMod.humanPauses = !SchematicBuilderMod.humanPauses);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Put junk into the chests when inventory is full", SchematicBuilderMod.storeJunk,
                () -> SchematicBuilderMod.storeJunk = !SchematicBuilderMod.storeJunk);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Climb high parts with dirt/cobble (towers + bridges)", SchematicBuilderMod.useTowers,
                () -> SchematicBuilderMod.useTowers = !SchematicBuilderMod.useTowers);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Dig through blocks to reach spots (puts them back)", SchematicBuilderMod.digThrough,
                () -> SchematicBuilderMod.digThrough = !SchematicBuilderMod.digThrough);
        y += OPT_ROW;
        pointer |= toggle(g, mouseX, mouseY, y, "Show preview in the world", SchematicBuilderMod.showPreview,
                () -> SchematicBuilderMod.showPreview = !SchematicBuilderMod.showPreview);
        y += OPT_ROW + 2;
        NovaGui.text(g, font, NovaGui.ellipsize(font, "While building, mouse and keys are ignored - press the stop key to stop.",
                cx1 - cx0), cx0, y, NovaGui.TEXT_MUTED);
        return pointer;
    }

    private boolean toggle(GuiGraphicsExtractor g, int mouseX, int mouseY, int y, String label, boolean on, Runnable flip) {
        boolean hover = isHovering(mouseX, mouseY, cx0 - 4, y - 2, cx1 + 4, y + OPT_ROW - 4);
        if (hover) {
            NovaShapes.rect(g, cx0 - 4, y - 2, cx1 + 4, y + OPT_ROW - 4, 4, NovaGui.RAISED);
        }
        NovaGui.text(g, font, label, cx0, y + 4, on ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(g, cx1 - 22, y + 2, on ? 1.0F : 0.0F, false, 1.0F, accent);
        buttons.add(new Button(cx0 - 4, y - 2, cx1 + 4, y + OPT_ROW - 4, flip));
        return hover;
    }

    // ---------------------------------------------------------------- Unten: Status + Start

    private boolean drawBottom(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean pointer = false;
        int top = bottomTop();
        g.fill(cx0, top - 3, cx1, top - 2, NovaGui.BORDER);
        SchematicBuilderMod mod = SchematicBuilderMod.instance();
        boolean running = mod != null && mod.active;
        FarmBot bot = SchematicBuilderMod.BOT;
        int buttonsX = cx1 - 170;

        String status;
        String second;
        if (running) {
            status = bot.status();
            second = bot.doneCount() + " / " + bot.totalCount() + " blocks  ·  " + Estimate.format(bot.etaSeconds()) + " left";
        } else {
            String problem = SchematicBuilderMod.startProblem();
            status = problem == null ? "Ready to build" : problem;
            BuildPlan plan = SchematicBuilderMod.plan();
            second = plan == null ? "" : SchematicBuilderMod.sizeText() + "  ·  " + Estimate.format(SchematicBuilderMod.estimateSeconds());
        }
        NovaGui.text(g, font, NovaGui.ellipsize(font, status, buttonsX - cx0 - 8), cx0, top + 2,
                running || SchematicBuilderMod.startProblem() == null ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaGui.text(g, font, NovaGui.ellipsize(font, second, buttonsX - cx0 - 8), cx0, top + 14, NovaGui.TEXT_MUTED);
        if (running) {
            float progress = bot.doneCount() / (float) Math.max(1, bot.totalCount());
            int bx1 = buttonsX - 8;
            NovaShapes.rect(g, cx0, top + 27, bx1, top + 31, 2, NovaGui.SWITCH_OFF);
            NovaShapes.rect(g, cx0, top + 27, cx0 + Math.max(4, Math.round((bx1 - cx0) * Mth.clamp(progress, 0.0F, 1.0F))), top + 31, 2, accent);
        }

        if (running) {
            pointer |= button(g, mouseX, mouseY, buttonsX, top + 4, buttonsX + 80, top + 24, bot.paused() ? "Resume" : "Pause",
                    bot.paused(), () -> {
                        SchematicBuilderMod.togglePause();
                        if (!SchematicBuilderMod.BOT.paused()) {
                            Minecraft.getInstance().gui.setScreen(null);
                        }
                    });
            pointer |= button(g, mouseX, mouseY, buttonsX + 86, top + 4, cx1, top + 24, "Stop", false, () -> {
                SchematicBuilderMod m = SchematicBuilderMod.instance();
                if (m != null && m.active) {
                    m.toggle(Minecraft.getInstance());
                }
            });
        } else {
            boolean ready = SchematicBuilderMod.startProblem() == null;
            pointer |= button(g, mouseX, mouseY, buttonsX, top + 4, cx1, top + 24, ready ? "Start building" : "Start", ready, () -> {
                SchematicBuilderMod m = SchematicBuilderMod.instance();
                if (m == null) {
                    return;
                }
                m.toggle(Minecraft.getInstance());
                if (m.active) {
                    ModConfig.save();
                    Minecraft.getInstance().gui.setScreen(null);
                }
            });
        }
        return pointer;
    }
}
