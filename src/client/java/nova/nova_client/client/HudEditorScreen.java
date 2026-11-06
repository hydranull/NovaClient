package nova.nova_client.client;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.mods.AdminListMod;
import nova.nova_client.client.mods.HotkeyListMod;
import nova.nova_client.client.mods.HudInfoMod;
import nova.nova_client.client.mods.PotionHudMod;
import nova.nova_client.client.mods.RadarMod;
import nova.nova_client.client.mods.RegionMapMod;
import nova.nova_client.client.mods.SpotifyMod;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Verschiebe-Modus: HudInfo, Spotify-Karte, Adminlist, Hotkey-Liste, Radar, PotionHUD, Region Map und Target HUD mit der
 * Maus an die gewuenschte Stelle ziehen.
 * Rechtsklick setzt ein Element auf seine Standard-Position zurueck, ESC speichert.
 */
public class HudEditorScreen extends Screen {
    private static final int HUD = 0;
    private static final int SPOTIFY = 1;
    private static final int ADMIN = 2;
    private static final int HOTKEYS = 3;
    private static final int RADAR = 4;
    private static final int POTIONS = 5;
    private static final int REGION = 6;
    private static final int TARGET = 7;
    private static final int LAST = TARGET;

    private final Screen parent;
    private int dragging = -1;
    private int offsetX;
    private int offsetY;

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Move HUD"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ModConfig.save();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private static Rect bounds(int element) {
        return switch (element) {
            case HUD -> HudInfoMod.bounds;
            case SPOTIFY -> SpotifyMod.bounds;
            case ADMIN -> AdminListMod.bounds;
            case RADAR -> RadarMod.bounds;
            case POTIONS -> PotionHudMod.bounds;
            case REGION -> RegionMapMod.bounds;
            case TARGET -> nova.nova_client.client.mods.TargetHudMod.bounds;
            default -> HotkeyListMod.bounds;
        };
    }

    private static String name(int element) {
        return switch (element) {
            case HUD -> "HUD Info";
            case SPOTIFY -> "Spotify";
            case ADMIN -> "Adminlist";
            case RADAR -> "Radar";
            case POTIONS -> "PotionHUD";
            case REGION -> "Region Map";
            case TARGET -> "Target HUD";
            default -> "Hotkeys";
        };
    }

    private int hovered(double mx, double my) {
        // Zuletzt gezeichnete zuerst (liegen oben drauf)
        for (int element = LAST; element >= HUD; element--) {
            Rect r = bounds(element);
            if (r != null && isHovering((int) mx, (int) my, r.x0 - 2, r.y0 - 2, r.x1 + 2, r.y1 + 2)) {
                return element;
            }
        }
        return -1;
    }

    private void setPosition(int element, float x, float y) {
        switch (element) {
            case HUD -> {
                HudInfoMod.posX = x;
                HudInfoMod.posY = y;
            }
            case SPOTIFY -> {
                SpotifyMod.posX = x;
                SpotifyMod.posY = y;
            }
            case ADMIN -> {
                AdminListMod.posX = x;
                AdminListMod.posY = y;
            }
            case RADAR -> {
                RadarMod.posX = x;
                RadarMod.posY = y;
            }
            case POTIONS -> {
                PotionHudMod.posX = x;
                PotionHudMod.posY = y;
            }
            case REGION -> {
                RegionMapMod.posX = x;
                RegionMapMod.posY = y;
            }
            case TARGET -> {
                nova.nova_client.client.mods.TargetHudMod.posX = x;
                nova.nova_client.client.mods.TargetHudMod.posY = y;
            }
            default -> {
                HotkeyListMod.posX = x;
                HotkeyListMod.posY = y;
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int element = hovered(event.x(), event.y());
        if (element >= 0) {
            if (event.button() == 1) {
                setPosition(element, -1.0F, -1.0F);
                ModConfig.save();
                NovaGui.clickSound();
            } else if (event.button() == 0) {
                Rect r = bounds(element);
                dragging = element;
                offsetX = (int) event.x() - r.x0;
                offsetY = (int) event.y() - r.y0;
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging >= 0) {
            Rect r = bounds(dragging);
            int w = r == null ? 0 : r.x1 - r.x0;
            int h = r == null ? 0 : r.y1 - r.y0;
            int x = Math.max(0, Math.min(width - w, (int) event.x() - offsetX));
            int y = Math.max(0, Math.min(height - h, (int) event.y() - offsetY));
            setPosition(dragging, x / (float) width, y / (float) height);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging >= 0) {
            dragging = -1;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // kein Unschaerfe-Hintergrund: das HUD soll klar sichtbar bleiben, nur ein feines Punkt-Raster und die Mitte
        graphics.fill(0, 0, width, height, 0x30000000);
        NovaShapes.dots(graphics, width, height, 20, 0x30FFFFFF);
        graphics.fill(width / 2, 0, width / 2 + 1, height, NovaGui.BORDER_STRONG);
        graphics.fill(0, height / 2, width, height / 2 + 1, NovaGui.BORDER_STRONG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int hover = dragging >= 0 ? dragging : hovered(mouseX, mouseY);
        boolean any = false;
        for (int element = HUD; element <= LAST; element++) {
            Rect r = bounds(element);
            if (r == null) {
                continue;
            }
            any = true;
            boolean active = element == hover;
            int color = active ? NovaGui.BRAND : NovaGui.alpha(NovaGui.BRAND, 0.55F);
            NovaShapes.outline(graphics, r.x0 - 3, r.y0 - 3, r.x1 + 3, r.y1 + 3, NovaGui.RADIUS, color);
            // Namensschild ueber (oder unter) dem Element
            String label = name(element);
            int lw = NovaType.width(font, NovaType.Face.CAPTION, label) + 10;
            int ly = r.y0 - 16 >= 0 ? r.y0 - 16 : r.y1 + 5;
            NovaShapes.rect(graphics, r.x0 - 3, ly, r.x0 - 3 + lw, ly + 11, NovaGui.RADIUS_SMALL, color);
            NovaType.draw(graphics, font, NovaType.Face.CAPTION, label, r.x0 + 2, NovaType.centerY(NovaType.Face.CAPTION, ly, 11),
                    NovaGui.ON_ACCENT);
        }

        String hint = any ? "Drag to move  ·  Right click to reset  ·  ESC to save"
                : "Turn on HUD Info, Spotify, Adminlist, Hotkeys, Radar, PotionHUD or Region Map to move them";
        int hw = NovaType.width(font, NovaType.Face.CAPTION, hint) + 20;
        NovaShapes.rect(graphics, width / 2 - hw / 2, height - 32, width / 2 + hw / 2, height - 14, NovaGui.RADIUS, NovaGui.PANEL);
        NovaShapes.outline(graphics, width / 2 - hw / 2, height - 32, width / 2 + hw / 2, height - 14, NovaGui.RADIUS, NovaGui.BORDER);
        NovaType.centered(graphics, font, NovaType.Face.CAPTION, hint, width / 2, NovaType.centerY(NovaType.Face.CAPTION, height - 32, 18),
                NovaGui.TEXT_DIM);

        if (hover >= 0) {
            graphics.requestCursor(dragging >= 0 ? CursorTypes.RESIZE_ALL : CursorTypes.POINTING_HAND);
        }
    }
}
