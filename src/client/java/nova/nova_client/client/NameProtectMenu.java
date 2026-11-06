package nova.nova_client.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.mods.NameProtectMod;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer NameProtect: angezeigter Name (frei eintippen), Name schuetzen an/aus und Skin Protect - entweder
 * der Standard-Steve-Skin oder eine eigene Datei ueber den Ordner-Button (Datei-Dialog), jeweils mit Vorschau.
 */
public class NameProtectMenu extends NovaSettingsScreen {
    private static final int LABEL_WIDTH = 48;
    private static final int FOLDER_W = 26;
    private static final int FOLDER_H = 20;

    private EditBox nameBox;

    public NameProtectMenu(Screen parent) {
        super(parent, "NameProtect");
    }

    @Override
    protected void init() {
        super.init();
        setPanel(320, 162);

        nameBox = new EditBox(font, cx0 + LABEL_WIDTH + 6, nameY() + 5, cx1 - cx0 - LABEL_WIDTH - 16, 10,
                Component.literal("Name"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(32);
        nameBox.setTextColor(NovaGui.TEXT);
        nameBox.setTextShadow(false);
        nameBox.setValue(NameProtectMod.fakeName);
        nameBox.setResponder(value -> NameProtectMod.fakeName = value);
        NovaGui.themeFont(nameBox);
        addRenderableWidget(nameBox);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    // ---------------------------------------------------------------- Geometrie

    private int nameY() {
        return cy0;
    }

    private int protectNameY() {
        return cy0 + 24;
    }

    private int protectSkinY() {
        return cy0 + 50;
    }

    private int steveY() {
        return cy0 + 70;
    }

    private int folderY() {
        return cy0 + 92;
    }

    private int removeY() {
        return cy0 + 120;
    }

    private int folderX() {
        return cx0;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mx = (int) event.x();
            int my = (int) event.y();
            if (isHovering(mx, my, cx0, protectNameY(), cx1, protectNameY() + 14)) {
                NameProtectMod.protectName = !NameProtectMod.protectName;
                changed();
                return true;
            }
            if (isHovering(mx, my, cx0, protectSkinY(), cx1, protectSkinY() + 14)) {
                NameProtectMod.protectSkin = !NameProtectMod.protectSkin;
                changed();
                return true;
            }
            if (NameProtectMod.protectSkin) {
                if (isHovering(mx, my, cx0, steveY(), cx1, steveY() + 14)) {
                    NameProtectMod.steveSkin = !NameProtectMod.steveSkin;
                    changed();
                    return true;
                }
                if (!NameProtectMod.steveSkin
                        && isHovering(mx, my, folderX(), folderY(), folderX() + FOLDER_W, folderY() + FOLDER_H)) {
                    NovaGui.clickSound();
                    NameProtectMod.pickSkinFile();
                    return true;
                }
                if (!NameProtectMod.steveSkin && NameProtectMod.hasSkin()
                        && isHovering(mx, my, cx0, removeY(), cx0 + 110, removeY() + 16)) {
                    NameProtectMod.removeSkin();
                    changed();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void changed() {
        ModConfig.save();
        NovaGui.clickSound();
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;

        NovaGui.text(graphics, font, "Name", cx0, nameY() + 5, NovaGui.TEXT_DIM);
        drawField(graphics, nameBox, "Your fake name");

        pointer |= toggleRow(graphics, "Protect name", NameProtectMod.protectName, protectNameY(), mouseX, mouseY);
        graphics.fill(cx0, protectSkinY() - 6, cx1, protectSkinY() - 5, NovaGui.BORDER);
        pointer |= toggleRow(graphics, "Protect skin", NameProtectMod.protectSkin, protectSkinY(), mouseX, mouseY);

        if (NameProtectMod.protectSkin) {
            pointer |= toggleRow(graphics, "Steve skin", NameProtectMod.steveSkin, steveY(), mouseX, mouseY);
            int fy = folderY();
            if (NameProtectMod.steveSkin) {
                // Standard-Steve: keine Datei noetig
                NovaGui.text(graphics, font, "You look like Steve (no file needed)", cx0, fy + 6, NovaGui.TEXT);
                drawFace(graphics, NameProtectMod.STEVE_TEXTURE, fy);
            } else {
                // Ordner-Button: oeffnet den Datei-Dialog
                int fx = folderX();
                boolean folderHover = isHovering(mouseX, mouseY, fx, fy, fx + FOLDER_W, fy + FOLDER_H);
                pointer |= folderHover;
                NovaControls.choice(graphics, font, fx, fy, fx + FOLDER_W, fy + FOLDER_H, "", folderHover, NameProtectMod.isPicking(), accent);
                drawFolderIcon(graphics, fx + FOLDER_W / 2, fy + FOLDER_H / 2, folderHover ? NovaGui.TEXT : accent);

                String status = NameProtectMod.isPicking() ? "Choose a file..." : NameProtectMod.skinStatus();
                int statusColor = NameProtectMod.hasSkin() ? NovaGui.TEXT : NovaGui.TEXT_MUTED;
                NovaGui.text(graphics, font, NovaGui.ellipsize(font, status, cx1 - cx0 - FOLDER_W - 44), fx + FOLDER_W + 8,
                        fy + 6, statusColor);
                if (NameProtectMod.hasSkin()) {
                    drawFace(graphics, NameProtectMod.SKIN_ID, fy);
                    boolean removeHover = isHovering(mouseX, mouseY, cx0, removeY(), cx0 + 110, removeY() + 16);
                    pointer |= removeHover;
                    NovaControls.choice(graphics, font, cx0, removeY(), cx0 + 110, removeY() + 16, "Remove skin", removeHover, false, accent);
                }
            }
        }

        NovaGui.text(graphics, font, NovaGui.ellipsize(font, "Only you see this. Own skin: 64x64 PNG.", cx1 - cx0),
                cx0, cy1 - 10, NovaGui.TEXT_MUTED);
        return pointer;
    }

    /** Vorschau: Gesicht (+ Hut-Ebene) einer 64x64-Skin-Textur, rechts in der Zeile y. */
    private void drawFace(GuiGraphicsExtractor graphics, Identifier texture, int y) {
        int size = 20;
        int px = cx1 - size;
        int py = y + (FOLDER_H - size) / 2;
        NovaShapes.rect(graphics, px - 2, py - 2, cx1 + 2, py + size + 2, 3, NovaGui.CARD);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, px, py, 8, 8, size, size, 8, 8, 64, 64, 0xFFFFFFFF);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, px, py, 40, 8, size, size, 8, 8, 64, 64, 0xFFFFFFFF);
    }

    private boolean toggleRow(GuiGraphicsExtractor graphics, String label, boolean on, int y, int mouseX, int mouseY) {
        boolean hover = isHovering(mouseX, mouseY, cx0, y, cx1, y + 14);
        NovaGui.text(graphics, font, label, cx0, y + 3, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaControls.toggle(graphics, cx1 - 22, y + 1, on ? 1.0F : 0.0F, false, 1.0F, accent);
        return hover;
    }

    /** Kleines Ordner-Symbol (14x10), mittig um (cx, cy). */
    private static void drawFolderIcon(GuiGraphicsExtractor graphics, int cx, int cy, int color) {
        int x0 = cx - 7;
        int y0 = cy - 5;
        graphics.fill(x0, y0, x0 + 6, y0 + 2, color);            // Reiter
        graphics.fill(x0, y0 + 2, x0 + 14, y0 + 10, color);      // Koerper
        graphics.fill(x0 + 1, y0 + 4, x0 + 13, y0 + 5, NovaGui.alpha(0xFF000000, 0.35F)); // Kante
    }
}
