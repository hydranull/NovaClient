package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import nova.nova_client.client.mods.WaypointsMod;
import nova.nova_client.client.waypoints.Waypoint;
import nova.nova_client.client.waypoints.WaypointStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Waypoints: links die Wegpunkte dieser Welt (alle Dimensionen, neueste oben), rechts der ausgewaehlte - Name,
 * Koordinaten, Farbe, anzeigen, dauerhaft/temporaer, loeschen. Neue Wegpunkte entstehen an deiner Position.
 * Ablauf der temporaeren Wegpunkte, Tasten und Anzeige stehen unter "Settings" oben rechts.
 */
public class WaypointsMenu extends NovaSettingsScreen {
    private static final int ROW_HEIGHT = 28;
    private static final int ROW_GAP = 3;
    private static final int BUTTON_H = 18;
    private static final String[] AXES = {"X", "Y", "Z"};
    private static final SimpleDateFormat DATE = new SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH);

    private List<Waypoint> list = List.of();
    private Waypoint selected;
    private float listScroll;
    private boolean confirmDelete;
    /** Felder werden gerade aus dem Wegpunkt befuellt - dann nichts zurueckschreiben. */
    private boolean syncing;
    private final float[] toggleAnim = new float[2];

    private EditBox nameBox;
    private final EditBox[] coordBoxes = new EditBox[3];

    private String status = "";
    private int statusColor;
    private long statusTime;

    // Layout
    private int listX1;
    private int rx0;

    public WaypointsMenu(Screen parent) {
        super(parent, "Waypoints");
    }

    @Override
    protected void init() {
        super.init();
        accent = NovaGui.accent(ModCategory.RENDER);
        listX1 = cx0 + Math.min(196, (cx1 - cx0) * 2 / 5);
        rx0 = listX1 + 14;
        list = WaypointStore.inWorld(WaypointStore.currentWorld());

        nameBox = new EditBox(font, rx0 + 6, cy0 + 5, cx1 - rx0 - 10, 10, Component.literal("Name"));
        styleField(nameBox, Waypoint.MAX_NAME_LENGTH);
        nameBox.setResponder(value -> {
            if (!syncing && selected != null) {
                selected.name = value;
                WaypointStore.changed();
            }
        });
        addRenderableWidget(nameBox);
        for (int i = 0; i < 3; i++) {
            int axis = i;
            EditBox box = new EditBox(font, coordX(i) + 16, coordsY() + 5, coordWidth() - 20, 10, Component.literal(AXES[i]));
            styleField(box, 9);
            box.setResponder(value -> coordChanged(axis, value));
            coordBoxes[i] = box;
            addRenderableWidget(box);
        }
        // Auswahl bleibt auch nach einer Fenster-Groessenaenderung / Rueckkehr aus den Settings
        select(selected != null && list.contains(selected) ? selected : null);
    }

    private void styleField(EditBox box, int maxLength) {
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(NovaGui.TEXT);
        box.setTextShadow(false);
        NovaGui.themeFont(box);
    }

    @Override
    public void removed() {
        fixBlankName();
        WaypointStore.saveIfDirty();
        super.removed();
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    protected String subtitle() {
        String world = WaypointStore.currentWorld();
        if (world == null) {
            return "Not in a world";
        }
        long temporary = list.stream().filter(w -> w.temporary).count();
        String text = (world.startsWith("sp:") ? world.substring(3) : world) + "  ·  " + list.size()
                + (list.size() == 1 ? " waypoint" : " waypoints") + (temporary > 0 ? ", " + temporary + " temporary" : "");
        // nicht unter den Settings-Knopf laufen
        return NovaGui.ellipsize(font, text, settingsX0() - px0 - 60);
    }

    private void status(String text, int color) {
        status = text;
        statusColor = color;
        statusTime = Util.getMillis();
    }

    // ---------------------------------------------------------------- Auswahl

    private void select(Waypoint waypoint) {
        if (selected != waypoint) {
            fixBlankName();
        }
        selected = waypoint;
        confirmDelete = false;
        boolean show = waypoint != null;
        nameBox.visible = show;
        for (EditBox box : coordBoxes) {
            box.visible = show;
        }
        if (show) {
            toggleAnim[0] = waypoint.visible ? 1.0F : 0.0F;
            toggleAnim[1] = waypoint.temporary ? 1.0F : 0.0F;
        } else {
            setFocused(null);
        }
        syncFields(true);
    }

    /**
     * Felder aus dem Wegpunkt befuellen; force = auch das Feld, in dem gerade getippt wird. Ohne force bleiben
     * Felder mit Fokus unberuehrt (halbe Eingaben wie "-" sollen stehen bleiben, bis man fertig ist).
     */
    private void syncFields(boolean force) {
        syncing = true;
        String name = selected == null ? "" : selected.name;
        if ((force || !nameBox.isFocused()) && !nameBox.getValue().equals(name)) {
            nameBox.setValue(name);
            nameBox.moveCursorToStart(false);
        }
        for (int i = 0; i < 3; i++) {
            EditBox box = coordBoxes[i];
            String value = selected == null ? "" : String.valueOf(i == 0 ? selected.x : i == 1 ? selected.y : selected.z);
            if ((force || !box.isFocused()) && !box.getValue().equals(value)) {
                box.setValue(value);
                box.moveCursorToStart(false);
            }
        }
        syncing = false;
    }

    /** Leeres Namensfeld verlassen: Standardnamen statt gar keinem. */
    private void fixBlankName() {
        if (selected != null && selected.name.isBlank()) {
            selected.name = "Waypoint";
            WaypointStore.changed();
        }
    }

    private void coordChanged(int axis, String value) {
        if (syncing || selected == null) {
            return;
        }
        try {
            int limit = axis == 1 ? 4096 : 30_000_000;
            int v = Math.max(-limit, Math.min(limit, Integer.parseInt(value.trim())));
            switch (axis) {
                case 0 -> selected.x = v;
                case 1 -> selected.y = v;
                default -> selected.z = v;
            }
            WaypointStore.moved(selected);
        } catch (NumberFormatException ignored) {
            // halbe Eingabe (z.B. nur "-"): Position behalten
        }
    }

    private static int colorIndex(int color) {
        for (int i = 0; i < WaypointsMod.COLORS.length; i++) {
            if (WaypointsMod.COLORS[i] == (color & 0xFFFFFF)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Geometrie

    /** Settings-Knopf im Kopf, links vom Modul-Schalter. */
    private int settingsX1() {
        return module != null ? px1 - PAD - NovaShapes.SWITCH_W - 12 : px1 - PAD;
    }

    private int settingsX0() {
        return settingsX1() - NovaGui.width(font, "Settings") - 16;
    }

    private int settingsY0() {
        return py0 + (HEADER_H - NovaGui.ROW_H) / 2;
    }

    private boolean settingsHovered(double mx, double my) {
        return isHovering((int) mx, (int) my, settingsX0(), settingsY0(), settingsX1(), settingsY0() + NovaGui.ROW_H);
    }

    private int addMid() {
        return (cx0 + listX1) / 2;
    }

    private int listTop() {
        return cy0 + 24;
    }

    private int rowY(int index) {
        return listTop() + index * (ROW_HEIGHT + ROW_GAP) - Math.round(listScroll);
    }

    private int maxScroll() {
        return Math.max(0, list.size() * (ROW_HEIGHT + ROW_GAP) - ROW_GAP - (cy1 - listTop()));
    }

    private int coordsY() {
        return cy0 + 24;
    }

    private int coordWidth() {
        return (cx1 - rx0 - 12) / 3;
    }

    private int coordX(int index) {
        return rx0 + index * (coordWidth() + 6);
    }

    private int actionsY() {
        return cy0 + 48;
    }

    private int actionX0(int index) {
        return rx0 + index * ((cx1 - rx0 - 12) / 3 + 6);
    }

    private int actionX1(int index) {
        return index == 2 ? cx1 : actionX0(index) + (cx1 - rx0 - 12) / 3;
    }

    private int infoY() {
        return cy0 + 74;
    }

    private int restartX0() {
        return cx1 - NovaGui.width(font, "Restart") - 12;
    }

    private boolean restartShown(Waypoint waypoint) {
        return waypoint.temporary && WaypointsMod.removal.byTime();
    }

    private int togglesY() {
        return cy0 + 102;
    }

    private int toggleX0(int index) {
        return index == 0 ? rx0 : rx0 + (cx1 - rx0 - 8) / 2 + 8;
    }

    private int toggleX1(int index) {
        return index == 0 ? rx0 + (cx1 - rx0 - 8) / 2 : cx1;
    }

    private boolean toggleHovered(int index, double mx, double my) {
        int y = togglesY();
        return isHovering((int) mx, (int) my, toggleX0(index) - 4, y - 2, toggleX1(index) + 4, y + 16);
    }

    private int deleteY() {
        return cy1 - BUTTON_H;
    }

    // ---------------------------------------------------------------- Aktionen

    private void add(boolean temporary) {
        Waypoint waypoint = WaypointsMod.addHere(Minecraft.getInstance(), temporary);
        if (waypoint == null) {
            status("Join a world first", NovaGui.DANGER);
            return;
        }
        list = WaypointStore.inWorld(WaypointStore.currentWorld());
        listScroll = 0.0F;
        select(waypoint);
        // Gleich einen Namen tippen koennen: Feld mit markiertem Standardnamen
        setFocused(nameBox);
        nameBox.moveCursorToEnd(false);
        nameBox.setHighlightPos(0);
        status((temporary ? "Temporary waypoint" : "Waypoint") + " added at your position - type a name", NovaGui.SUCCESS);
        NovaGui.clickSound();
    }

    /** Klick auf einen Knopf rechts; deleteArmed = der Loeschen-Knopf war schon einmal gedrueckt. */
    private boolean clickDetails(int mx, int my, int button, boolean deleteArmed) {
        Waypoint waypoint = selected;
        Minecraft client = Minecraft.getInstance();
        int ay = actionsY();
        if (button == 0 && isHovering(mx, my, actionX0(0), ay, actionX1(0), ay + BUTTON_H)) {
            if (client.player == null || WaypointStore.currentWorld() == null) {
                status("Join a world first", NovaGui.DANGER);
            } else {
                BlockPos pos = client.player.blockPosition();
                waypoint.x = pos.getX();
                waypoint.y = pos.getY();
                waypoint.z = pos.getZ();
                waypoint.dimension = WaypointStore.currentDimension();
                WaypointStore.moved(waypoint);
                syncFields(true);
                status("Moved to your position", NovaGui.SUCCESS);
            }
            NovaGui.clickSound();
            return true;
        }
        if (button == 0 && isHovering(mx, my, actionX0(1), ay, actionX1(1), ay + BUTTON_H)) {
            client.keyboardHandler.setClipboard(waypoint.x + " " + waypoint.y + " " + waypoint.z);
            status("Coordinates copied", NovaGui.SUCCESS);
            NovaGui.clickSound();
            return true;
        }
        if ((button == 0 || button == 1) && isHovering(mx, my, actionX0(2), ay, actionX1(2), ay + BUTTON_H)) {
            int[] colors = WaypointsMod.COLORS;
            waypoint.color = colors[Math.floorMod(colorIndex(waypoint.color) + (button == 0 ? 1 : -1), colors.length)];
            WaypointStore.changed();
            NovaGui.clickSound();
            return true;
        }
        if (button == 0 && restartShown(waypoint)
                && isHovering(mx, my, restartX0(), infoY() + 9, cx1, infoY() + 23)) {
            WaypointsMod.restartTimer(waypoint);
            status("Timer restarted", NovaGui.SUCCESS);
            NovaGui.clickSound();
            return true;
        }
        if (button == 0 && toggleHovered(0, mx, my)) {
            waypoint.visible = !waypoint.visible;
            WaypointStore.changed();
            NovaGui.clickSound();
            return true;
        }
        if (button == 0 && toggleHovered(1, mx, my)) {
            if (waypoint.temporary) {
                waypoint.temporary = false;
                WaypointStore.changed();
                status("Now permanent", NovaGui.SUCCESS);
            } else {
                WaypointsMod.makeTemporary(waypoint);
                status("Now temporary", NovaGui.SUCCESS);
            }
            NovaGui.clickSound();
            return true;
        }
        if (button == 0 && isHovering(mx, my, rx0, deleteY(), cx1, deleteY() + BUTTON_H)) {
            if (!deleteArmed) {
                confirmDelete = true;
            } else {
                String name = waypoint.displayName();
                WaypointStore.remove(waypoint);
                list = WaypointStore.inWorld(WaypointStore.currentWorld());
                select(null);
                status("Deleted \"" + name + "\"", NovaGui.TEXT_DIM);
            }
            NovaGui.clickSound();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x();
        int my = (int) event.y();
        int button = event.button();
        boolean deleteArmed = confirmDelete;
        confirmDelete = false;

        if (button == 0 && settingsHovered(mx, my)) {
            NovaGui.clickSound();
            Minecraft.getInstance().gui.setScreen(WaypointsMod.settingsScreen(this));
            return true;
        }
        if (button == 0 && isHovering(mx, my, cx0, cy0, addMid() - 3, cy0 + BUTTON_H)) {
            add(false);
            return true;
        }
        if (button == 0 && isHovering(mx, my, addMid() + 3, cy0, listX1, cy0 + BUTTON_H)) {
            add(true);
            return true;
        }
        if (mx >= cx0 && mx <= listX1 && my >= listTop() && my <= cy1) {
            for (int i = 0; i < list.size(); i++) {
                int y = rowY(i);
                if (my >= y && my < y + ROW_HEIGHT) {
                    if (button == 0 && list.get(i) != selected) {
                        select(list.get(i));
                        NovaGui.clickSound();
                    }
                    setFocused(null);
                    return true;
                }
            }
        }
        if (selected != null && clickDetails(mx, my, button, deleteArmed)) {
            return true;
        }
        // Klick daneben laesst das Textfeld los (ein Klick in ein Feld setzt den Fokus gleich wieder)
        setFocused(null);
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX <= listX1 + 6) {
            listScroll = Math.max(0.0F, Math.min(maxScroll(), listScroll - (float) scrollY * 20.0F));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER;
        if (enter && getFocused() instanceof EditBox) {
            setFocused(null);
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        String world = WaypointStore.currentWorld();
        list = WaypointStore.inWorld(world);
        if (selected != null && !list.contains(selected)) {
            select(null); // abgelaufen oder geloescht
        }
        if (selected != null) {
            if (!nameBox.isFocused()) {
                fixBlankName();
            }
            syncFields(false);
        }
        listScroll = Math.max(0.0F, Math.min(maxScroll(), listScroll));
        long now = System.currentTimeMillis();

        // Kopf: Einstellungen
        boolean settingsHover = settingsHovered(mouseX, mouseY);
        pointer |= settingsHover;
        NovaControls.button(graphics, font, settingsX0(), settingsY0(), settingsX1(), settingsY0() + NovaGui.ROW_H, "Settings",
                NovaControls.Variant.SECONDARY, settingsHover, true);

        graphics.fill(listX1 + 6, cy0, listX1 + 7, cy1, NovaGui.BORDER);

        // Neue Wegpunkte an deiner Position
        boolean canAdd = world != null && Minecraft.getInstance().player != null;
        boolean addHover = canAdd && isHovering(mouseX, mouseY, cx0, cy0, addMid() - 3, cy0 + BUTTON_H);
        boolean tempHover = canAdd && isHovering(mouseX, mouseY, addMid() + 3, cy0, listX1, cy0 + BUTTON_H);
        pointer |= addHover || tempHover;
        NovaControls.choice(graphics, font, cx0, cy0, addMid() - 3, cy0 + BUTTON_H, "+ Waypoint", addHover, false, accent);
        NovaControls.choice(graphics, font, addMid() + 3, cy0, listX1, cy0 + BUTTON_H, "+ Temporary", tempHover, false, accent);

        pointer |= drawList(graphics, world, mouseX, mouseY, now);

        if (selected != null) {
            pointer |= drawDetails(graphics, selected, mouseX, mouseY, dt, now);
        } else {
            int midX = (rx0 + cx1) / 2;
            int midY = (cy0 + cy1) / 2 - 10;
            NovaGui.centered(graphics, font, "No waypoint selected", midX, midY, NovaGui.TEXT_DIM);
            String hint = canAdd ? "Add one at your position with + Waypoint" : "Join a world to add waypoints";
            NovaGui.centered(graphics, font, NovaGui.ellipsize(font, hint, cx1 - rx0), midX, midY + 14, NovaGui.TEXT_MUTED);
        }

        // Statusmeldung (blendet nach 3 s aus)
        float age = (Util.getMillis() - statusTime) / 1000.0F;
        if (!status.isEmpty() && age < 3.5F) {
            float fade = age < 3.0F ? 1.0F : 1.0F - (age - 3.0F) * 2.0F;
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, status, cx1 - rx0), rx0, deleteY() - 13,
                    NovaGui.alpha(statusColor, fade));
        }
        return pointer;
    }

    private boolean drawList(GuiGraphicsExtractor graphics, String world, int mouseX, int mouseY, long now) {
        boolean pointer = false;
        int top = listTop();
        if (list.isEmpty()) {
            String text = world == null ? "Not in a world" : "No waypoints in this world yet";
            NovaGui.centered(graphics, font, NovaGui.ellipsize(font, text, listX1 - cx0), (cx0 + listX1) / 2, top + 16,
                    NovaGui.TEXT_DIM);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        graphics.enableScissor(cx0 - 2, top - 2, listX1 + 2, cy1);
        for (int i = 0; i < list.size(); i++) {
            Waypoint waypoint = list.get(i);
            int y0 = rowY(i);
            int y1 = y0 + ROW_HEIGHT;
            if (y1 < top || y0 > cy1) {
                continue;
            }
            boolean isSelected = waypoint == selected;
            boolean hovered = isHovering(mouseX, mouseY, cx0, y0, listX1, y1 - 1) && mouseY >= top && mouseY <= cy1;
            pointer |= hovered && !isSelected;
            NovaShapes.rect(graphics, cx0, y0, listX1, y1, 5, isSelected ? NovaGui.alpha(accent, 0.16F) : hovered ? NovaGui.RAISED : NovaGui.CARD);
            NovaShapes.outline(graphics, cx0, y0, listX1, y1, 5, isSelected ? accent : NovaGui.BORDER);
            int color = 0xFF000000 | waypoint.color;
            NovaShapes.rect(graphics, cx0 + 7, y0 + 6, cx0 + 13, y0 + 12, 3, waypoint.visible ? color : NovaGui.alpha(color, 0.35F));

            // Temporaer: Plakette mit Restzeit (bzw. "TEMP", wenn nur das Entladen zaehlt)
            int nameRight = listX1 - 6;
            if (waypoint.temporary) {
                String badge = WaypointsMod.removal.byTime() ? WaypointsMod.formatRemaining(waypoint.expiresAt - now) : "TEMP";
                int bw = NovaGui.width(font, badge) + 6;
                NovaShapes.rect(graphics, listX1 - 5 - bw, y0 + 4, listX1 - 5, y0 + 14, 3, accent);
                NovaGui.text(graphics, font, badge, listX1 - 2 - bw, y0 + 5, NovaGui.ON_ACCENT, false);
                nameRight = listX1 - 9 - bw;
            }
            String name = waypoint.displayName() + (waypoint.visible ? "" : " (hidden)");
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, name, nameRight - cx0 - 18), cx0 + 18, y0 + 5,
                    isSelected ? NovaGui.TEXT : waypoint.visible ? NovaGui.TEXT_DIM : NovaGui.TEXT_MUTED);

            // Zweite Zeile: Koordinaten, rechts Entfernung (gleiche Dimension) bzw. die Dimension
            String right = player != null && WaypointStore.isHere(waypoint)
                    ? WaypointsMod.formatDistance(player.position().distanceTo(waypoint.center()))
                    : WaypointStore.dimensionName(waypoint.dimension);
            int rightWidth = NovaGui.width(font, right);
            NovaGui.text(graphics, font, right, listX1 - 7 - rightWidth, y0 + 16, NovaGui.TEXT_MUTED);
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, waypoint.coords(), listX1 - 13 - rightWidth - cx0 - 18),
                    cx0 + 18, y0 + 16, NovaGui.TEXT_MUTED);
        }
        graphics.disableScissor();
        return pointer;
    }

    private boolean drawDetails(GuiGraphicsExtractor graphics, Waypoint waypoint, int mouseX, int mouseY, float dt, long now) {
        boolean pointer = false;

        // Name und Koordinaten
        drawField(graphics, nameBox, "Name");
        for (int i = 0; i < 3; i++) {
            int x0 = coordX(i);
            int y0 = coordsY();
            NovaShapes.rect(graphics, x0, y0, x0 + coordWidth(), y0 + 18, 5, NovaGui.CARD);
            NovaShapes.outline(graphics, x0, y0, x0 + coordWidth(), y0 + 18, 5, coordBoxes[i].isFocused() ? accent : NovaGui.BORDER);
            NovaGui.text(graphics, font, AXES[i], x0 + 6, y0 + 5, NovaGui.TEXT_MUTED);
        }

        // Hierher / Kopieren / Farbe (Links- und Rechtsklick)
        int ay = actionsY();
        int colorIndex = colorIndex(waypoint.color);
        String[] labels = {"Move here", "Copy", colorIndex >= 0 ? WaypointsMod.COLOR_NAMES[colorIndex] : "Custom"};
        for (int i = 0; i < 3; i++) {
            boolean hover = isHovering(mouseX, mouseY, actionX0(i), ay, actionX1(i), ay + BUTTON_H);
            pointer |= hover;
            NovaControls.choice(graphics, font, actionX0(i), ay, actionX1(i), ay + BUTTON_H, labels[i], hover, false, i == 2 ? 0xFF000000 | waypoint.color : accent);
        }
        NovaShapes.rect(graphics, actionX0(2) + 6, ay + 6, actionX0(2) + 12, ay + 12, 3, 0xFF000000 | waypoint.color);

        // Wo / wie lange noch
        LocalPlayer player = Minecraft.getInstance().player;
        String where = WaypointStore.dimensionName(waypoint.dimension) + "  ·  ";
        if (player != null && WaypointStore.isHere(waypoint)) {
            where += WaypointsMod.formatDistance(player.position().distanceTo(waypoint.center())) + " "
                    + NovaNotify.direction(player.position(), waypoint.center());
        } else {
            where += "other dimension";
        }
        int iy = infoY();
        NovaGui.text(graphics, font, NovaGui.ellipsize(font, where, cx1 - rx0), rx0, iy, NovaGui.TEXT_DIM);
        String life;
        if (!waypoint.temporary) {
            life = "Permanent  ·  created " + DATE.format(new Date(waypoint.created));
        } else {
            String remaining = WaypointsMod.formatRemaining(waypoint.expiresAt - now);
            life = switch (WaypointsMod.removal) {
                case TIME -> "Temporary  ·  removed in " + remaining;
                case CHUNK -> waypoint.chunkLoaded ? "Temporary  ·  removed when its chunk unloads"
                        : "Temporary  ·  removed after its chunk loads and unloads";
                case BOTH -> "Temporary  ·  " + remaining + " or chunk unload";
            };
        }
        int lifeMax = (restartShown(waypoint) ? restartX0() - 6 : cx1) - rx0;
        NovaGui.text(graphics, font, NovaGui.ellipsize(font, life, lifeMax), rx0, iy + 12, NovaGui.TEXT_MUTED);
        if (restartShown(waypoint)) {
            boolean hover = isHovering(mouseX, mouseY, restartX0(), iy + 9, cx1, iy + 23);
            pointer |= hover;
            NovaControls.choice(graphics, font, restartX0(), iy + 9, cx1, iy + 23, "Restart", hover, false, accent);
        }

        // Schalter: in der Welt zeigen, temporaer
        String[] toggleLabels = {"Show in world", "Temporary"};
        boolean[] values = {waypoint.visible, waypoint.temporary};
        int ty = togglesY();
        for (int i = 0; i < 2; i++) {
            int x0 = toggleX0(i);
            int x1 = toggleX1(i);
            boolean hover = toggleHovered(i, mouseX, mouseY);
            pointer |= hover;
            toggleAnim[i] = NovaGui.approach(toggleAnim[i], values[i] ? 1.0F : 0.0F, 14.0F, dt);
            if (hover) {
                NovaShapes.rect(graphics, x0 - 4, ty - 2, x1 + 4, ty + 16, 4, NovaGui.RAISED);
            }
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, toggleLabels[i], x1 - x0 - 28), x0, ty + 3,
                    values[i] ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            NovaControls.toggle(graphics, x1 - 22, ty + 1, toggleAnim[i], false, 1.0F, accent);
        }

        // Loeschen (zweimal klicken)
        boolean deleteHover = isHovering(mouseX, mouseY, rx0, deleteY(), cx1, deleteY() + BUTTON_H);
        pointer |= deleteHover;
        NovaControls.choice(graphics, font, rx0, deleteY(), cx1, deleteY() + BUTTON_H, confirmDelete ? "Click again to delete" : "Delete waypoint", deleteHover, confirmDelete, NovaGui.DANGER);
        return pointer;
    }
}
