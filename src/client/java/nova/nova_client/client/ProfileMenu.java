package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Profile verwalten: aktuellen Stand als Profil speichern, Profile laden, ueberschreiben, umbenennen, loeschen.
 */
public class ProfileMenu extends NovaSettingsScreen {
    private static final int LIST_WIDTH = 170;
    private static final int ROW_HEIGHT = 30;
    private static final int ROW_GAP = 4;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("MMM d, yyyy HH:mm", java.util.Locale.ENGLISH);

    private List<Profiles.Info> profiles;
    private String selected;
    private boolean confirmDelete;
    private float listScroll;

    private String status = "";
    private int statusColor;
    private long statusTime;

    private EditBox newNameBox;
    private EditBox renameBox;

    // Layout
    private int listX1;
    private int rx0;

    public ProfileMenu(Screen parent) {
        super(parent, "Profiles");
    }

    @Override
    protected void init() {
        super.init();
        accent = NovaGui.BRAND;
        listX1 = cx0 + LIST_WIDTH;
        rx0 = listX1 + 14;
        refresh();
        if (selected == null) {
            selected = !Profiles.current.isEmpty() && Profiles.exists(Profiles.current) ? Profiles.current
                    : profiles.isEmpty() ? null : profiles.get(0).name();
        }

        newNameBox = new EditBox(font, cx0 + 6, cy0 + 5, LIST_WIDTH - 22 - 10, 10, Component.literal("New profile"));
        styleField(newNameBox);
        NovaGui.themeFont(newNameBox);
        addRenderableWidget(newNameBox);

        renameBox = new EditBox(font, rx0 + 6, cy0 + 37, renameX0() - 6 - rx0 - 10, 10, Component.literal("Name"));
        styleField(renameBox);
        NovaGui.themeFont(renameBox);
        addRenderableWidget(renameBox);
        loadSelection();
        setInitialFocus(newNameBox);
    }

    private void styleField(EditBox box) {
        box.setBordered(false);
        box.setMaxLength(Profiles.MAX_NAME_LENGTH);
        box.setTextColor(NovaGui.TEXT);
        box.setTextShadow(false);
    }

    private void refresh() {
        profiles = Profiles.list();
        if (selected != null && profiles.stream().noneMatch(p -> p.name().equals(selected))) {
            selected = profiles.isEmpty() ? null : profiles.get(0).name();
        }
    }

    private Profiles.Info selectedInfo() {
        for (Profiles.Info info : profiles) {
            if (info.name().equals(selected)) {
                return info;
            }
        }
        return null;
    }

    private void loadSelection() {
        confirmDelete = false;
        renameBox.visible = selected != null;
        renameBox.setValue(selected == null ? "" : selected);
    }

    private void status(String text, int color) {
        status = text;
        statusColor = color;
        statusTime = Util.getMillis();
    }

    @Override
    protected String subtitle() {
        String currentText = Profiles.current.isEmpty() ? "none loaded" : "current: " + Profiles.current;
        return profiles.size() + " profiles  ·  " + currentText;
    }

    // ---------------------------------------------------------------- Geometrie

    private int createX0() {
        return listX1 - 18;
    }

    private int rowY(int index) {
        return cy0 + 26 + index * (ROW_HEIGHT + ROW_GAP) - Math.round(listScroll);
    }

    private int renameX0() {
        return cx1 - NovaGui.width(font, "Rename") - 14;
    }

    private int buttonsY() {
        return cy1 - 20;
    }

    private int buttonWidth() {
        return (cx1 - rx0 - 12) / 3;
    }

    private int buttonX(int index) {
        return rx0 + index * (buttonWidth() + 6);
    }

    // ---------------------------------------------------------------- Aktionen

    private void create() {
        String name = Profiles.sanitize(newNameBox.getValue());
        if (name.isEmpty()) {
            status("Enter a name", NovaGui.DANGER);
            return;
        }
        boolean existed = Profiles.exists(name);
        if (Profiles.save(name)) {
            newNameBox.setValue("");
            selected = name;
            refresh();
            loadSelection();
            status(existed ? "Saved \"" + name + "\"" : "Created \"" + name + "\"", NovaGui.SUCCESS);
            NovaGui.clickSound();
        } else {
            status("Save failed", NovaGui.DANGER);
        }
    }

    private void rename() {
        if (selected == null) {
            return;
        }
        String name = Profiles.sanitize(renameBox.getValue());
        if (name.equals(selected)) {
            return;
        }
        if (Profiles.exists(name)) {
            status("\"" + name + "\" already exists", NovaGui.DANGER);
            return;
        }
        if (Profiles.rename(selected, name)) {
            selected = name;
            refresh();
            loadSelection();
            status("Renamed to \"" + name + "\"", NovaGui.SUCCESS);
            NovaGui.clickSound();
        } else {
            status("Rename failed", NovaGui.DANGER);
        }
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        int mx = (int) event.x();
        int my = (int) event.y();

        if (isHovering(mx, my, createX0(), cy0, listX1, cy0 + 18)) {
            create();
            return true;
        }

        if (mx >= cx0 && mx < listX1 && my >= cy0 + 26 && my < cy1) {
            for (int i = 0; i < profiles.size(); i++) {
                int y = rowY(i);
                if (my >= y && my < y + ROW_HEIGHT) {
                    if (!profiles.get(i).name().equals(selected)) {
                        selected = profiles.get(i).name();
                        loadSelection();
                        NovaGui.clickSound();
                    } else if (doubleClick) {
                        loadSelected();
                    }
                    return true;
                }
            }
        }

        if (selected != null) {
            if (isHovering(mx, my, renameX0(), cy0 + 32, cx1, cy0 + 50)) {
                rename();
                return true;
            }
            int by = buttonsY();
            if (isHovering(mx, my, buttonX(0), by, buttonX(0) + buttonWidth(), by + 18)) {
                loadSelected();
                return true;
            }
            if (isHovering(mx, my, buttonX(1), by, buttonX(1) + buttonWidth(), by + 18)) {
                if (Profiles.save(selected)) {
                    refresh();
                    status("Saved \"" + selected + "\"", NovaGui.SUCCESS);
                    NovaGui.clickSound();
                }
                return true;
            }
            if (isHovering(mx, my, buttonX(2), by, cx1, by + 18)) {
                if (!confirmDelete) {
                    confirmDelete = true;
                    status("Click again to delete", NovaGui.DANGER);
                } else {
                    String name = selected;
                    Profiles.delete(name);
                    selected = null;
                    refresh();
                    selected = profiles.isEmpty() ? null : profiles.get(0).name();
                    loadSelection();
                    status("Deleted \"" + name + "\"", NovaGui.TEXT_DIM);
                }
                NovaGui.clickSound();
                return true;
            }
        }
        confirmDelete = false;
        return super.mouseClicked(event, doubleClick);
    }

    private void loadSelected() {
        if (selected != null && Profiles.load(selected)) {
            refresh();
            status("Loaded \"" + selected + "\"", NovaGui.SUCCESS);
            NovaGui.clickSound();
        } else {
            status("Load failed", NovaGui.DANGER);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < listX1) {
            int max = Math.max(0, profiles.size() * (ROW_HEIGHT + ROW_GAP) - ROW_GAP - (cy1 - cy0 - 26));
            listScroll = Math.max(0.0F, Math.min(max, listScroll - (float) scrollY * 20.0F));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean enter = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER;
        if (enter && getFocused() == newNameBox) {
            create();
            return true;
        }
        if (enter && getFocused() == renameBox) {
            rename();
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        graphics.fill(listX1 + 6, cy0, listX1 + 7, cy1, NovaGui.BORDER);

        // Neues Profil
        drawField(graphics, newNameBox, "New profile...");
        boolean createHover = isHovering(mouseX, mouseY, createX0(), cy0, listX1, cy0 + 18);
        pointer |= createHover;
        NovaShapes.rect(graphics, createX0(), cy0, listX1, cy0 + 18, 5, NovaGui.alpha(accent, createHover ? 0.35F : 0.18F));
        NovaShapes.outline(graphics, createX0(), cy0, listX1, cy0 + 18, 5, accent);
        NovaGui.centered(graphics, font, "+", (createX0() + listX1) / 2 + 1, cy0 + 5, NovaGui.TEXT);

        // Liste
        if (profiles.isEmpty()) {
            NovaGui.centered(graphics, font, "No profiles yet", (cx0 + listX1) / 2, cy0 + 40, NovaGui.TEXT_DIM);
        }
        graphics.enableScissor(cx0 - 2, cy0 + 24, listX1 + 2, cy1);
        for (int i = 0; i < profiles.size(); i++) {
            Profiles.Info info = profiles.get(i);
            int y0 = rowY(i);
            int y1 = y0 + ROW_HEIGHT;
            if (y1 < cy0 + 24 || y0 > cy1) {
                continue;
            }
            boolean isSelected = info.name().equals(selected);
            boolean isCurrent = info.name().equals(Profiles.current);
            boolean hovered = isHovering(mouseX, mouseY, cx0, y0, listX1 - 1, y1 - 1) && mouseY >= cy0 + 24;
            pointer |= hovered && !isSelected;
            NovaShapes.rect(graphics, cx0, y0, listX1, y1, 5, isSelected ? NovaGui.alpha(accent, 0.16F) : hovered ? NovaGui.RAISED : NovaGui.CARD);
            NovaShapes.outline(graphics, cx0, y0, listX1, y1, 5, isSelected ? accent : NovaGui.BORDER);

            int nameMax = LIST_WIDTH - 14 - (isCurrent ? NovaGui.width(font, "active") + 10 : 0);
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, info.name(), nameMax), cx0 + 7, y0 + 5,
                    isSelected ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            NovaGui.text(graphics, font, info.activeMods().size() + " on  ·  " + DATE.format(new Date(info.modified())),
                    cx0 + 7, y0 + 17, NovaGui.TEXT_MUTED);
            if (isCurrent) {
                int bw = NovaGui.width(font, "active") + 6;
                NovaShapes.rect(graphics, listX1 - 5 - bw, y0 + 4, listX1 - 5, y0 + 14, 3, NovaGui.SUCCESS);
                NovaGui.text(graphics, font, "active", listX1 - 2 - bw, y0 + 5, NovaGui.ON_ACCENT, false);
            }
        }
        graphics.disableScissor();

        // Details
        Profiles.Info info = selectedInfo();
        if (info == null) {
            int midX = (rx0 + cx1) / 2;
            int midY = (cy0 + cy1) / 2 - 16;
            NovaGui.centered(graphics, font, "No profile selected", midX, midY, NovaGui.TEXT_DIM);
            NovaGui.centered(graphics, font, NovaGui.ellipsize(font, "", cx1 - rx0),
                    midX, midY + 14, NovaGui.TEXT_MUTED);
            NovaGui.centered(graphics, font, NovaGui.ellipsize(font, "",
                    cx1 - rx0), midX, midY + 26, NovaGui.TEXT_MUTED);
        } else {
            pointer |= drawDetails(graphics, info, mouseX, mouseY);
        }

        // Statusmeldung (blendet nach 3 s aus)
        float age = (Util.getMillis() - statusTime) / 1000.0F;
        if (!status.isEmpty() && age < 3.5F) {
            float fade = age < 3.0F ? 1.0F : 1.0F - (age - 3.0F) * 2.0F;
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, status, cx1 - rx0), rx0, buttonsY() - 14,
                    NovaGui.alpha(statusColor, fade));
        }
        return pointer;
    }

    private boolean drawDetails(GuiGraphicsExtractor graphics, Profiles.Info info, int mouseX, int mouseY) {
        boolean pointer = false;

        NovaGui.text(graphics, font, "Profile", rx0, cy0 + 1, NovaGui.TEXT_DIM);
        String saved = "Saved " + DATE.format(new Date(info.modified()));
        NovaGui.text(graphics, font, saved, cx1 - NovaGui.width(font, saved), cy0 + 1, NovaGui.TEXT_MUTED);

        // Umbenennen
        drawField(graphics, renameBox, "Name");
        boolean renameHover = isHovering(mouseX, mouseY, renameX0(), cy0 + 32, cx1, cy0 + 50);
        pointer |= renameHover;
        NovaControls.choice(graphics, font, renameX0(), cy0 + 32, cx1, cy0 + 50, "Rename", renameHover, false, accent);

        // Inhalt
        int y = cy0 + 60;
        NovaGui.text(graphics, font, "Enabled modules (" + info.activeMods().size() + ")", rx0, y, NovaGui.TEXT_DIM);
        y += 12;
        if (info.activeMods().isEmpty()) {
            NovaGui.text(graphics, font, "none", rx0, y, NovaGui.TEXT_MUTED);
            y += 14;
        } else {
            int x = rx0;
            int maxY = buttonsY() - 44;
            for (String name : info.activeMods()) {
                int w = NovaGui.width(font, name) + 10;
                if (x + w > cx1) {
                    x = rx0;
                    y += 15;
                }
                if (y > maxY) {
                    NovaGui.text(graphics, font, "...", x, y + 2, NovaGui.TEXT_MUTED);
                    break;
                }
                int color = NovaGui.accent(categoryOf(name));
                NovaShapes.rect(graphics, x, y, x + w, y + 12, 4, NovaGui.alpha(color, 0.2F));
                NovaShapes.outline(graphics, x, y, x + w, y + 12, 4, NovaGui.alpha(color, 0.7F));
                NovaGui.text(graphics, font, name, x + 5, y + 2, NovaGui.TEXT);
                x += w + 4;
            }
            y += 20;
        }
        NovaGui.text(graphics, font, "Keybinds: " + info.keybinds() + "  ·  BlockESP blocks: " + info.espBlocks(),
                rx0, y, NovaGui.TEXT_MUTED);

        // Buttons
        int by = buttonsY();
        String[] labels = {"Load", "Overwrite", confirmDelete ? "Confirm?" : "Delete"};
        int[] colors = {NovaGui.SUCCESS, accent, NovaGui.DANGER};
        for (int i = 0; i < 3; i++) {
            int x0 = buttonX(i);
            int x1 = i == 2 ? cx1 : x0 + buttonWidth();
            boolean hover = isHovering(mouseX, mouseY, x0, by, x1, by + 18);
            pointer |= hover;
            NovaControls.choice(graphics, font, x0, by, x1, by + 18, labels[i], hover, i == 0 || (i == 2 && confirmDelete), colors[i]);
        }
        return pointer;
    }

    private static ModCategory categoryOf(String modName) {
        for (nova.nova_client.client.mods.Mod mod : Mods.mods) {
            if (mod.name.equals(modName)) {
                return mod.category;
            }
        }
        return ModCategory.MISC;
    }
}
