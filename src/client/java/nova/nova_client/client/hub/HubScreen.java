package nova.nova_client.client.hub;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.NovaControls;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaSettingsScreen;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.NovaType;
import nova.nova_client.client.update.UpdateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nova Hub: Add-ons der Community fuer diese Minecraft-Version - suchen, installieren, aktualisieren, entfernen und
 * bewerten (1-5 Punkte, mit Nova Social). Neue Add-ons laedt Minecraft beim naechsten Start.
 */
public class HubScreen extends NovaSettingsScreen {
    private static final int CARD_H = 54;
    private static final int CARD_GAP = 6;
    private static final int DOT = 3;
    private static final int DOT_STEP = 9;

    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private EditBox searchBox;
    private String search = "";
    private float scroll;
    private float scrollTarget;
    private int maxScroll;

    public HubScreen(Screen parent) {
        super(parent, "Nova Hub");
    }

    @Override
    protected void init() {
        setPanel(560, 300);
        searchBox = new EditBox(font, cx0 + 6, cy0 + 5, Math.min(220, (cx1 - cx0) / 2) - 10, 10, Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(40);
        searchBox.setTextColor(NovaGui.TEXT);
        searchBox.setTextShadow(false);
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            search = value;
            scrollTarget = 0.0F;
        });
        NovaGui.themeFont(searchBox);
        addRenderableWidget(searchBox);
        if (!HubClient.loaded() && !HubClient.loading()) {
            HubClient.refresh();
        }
    }

    @Override
    protected String subtitle() {
        String mc = UpdateManager.minecraftVersion();
        if (HubClient.restartNeeded()) {
            return "Restart Minecraft to finish installing";
        }
        if (HubClient.loading() && HubClient.addons().isEmpty()) {
            return "Loading add-ons...";
        }
        int count = HubClient.addons().size();
        return "Community add-ons for Minecraft " + (mc == null ? "?" : mc) + "  ·  " + count + (count == 1 ? " add-on" : " add-ons");
    }

    @Override
    protected boolean drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt) {
        hits.clear();
        drawField(g, searchBox, "Search add-ons");
        boolean pointer = button(g, cx1 - 70, cy0, cx1, cy0 + 18, HubClient.loading() ? "Loading" : "Refresh",
                NovaControls.Variant.SECONDARY, mouseX, mouseY, HubClient::refresh, Integer.MIN_VALUE);
        int top = cy0 + 26;
        String notice = HubClient.notice();
        if (!notice.isEmpty()) {
            NovaType.draw(g, font, NovaType.Face.CAPTION, NovaType.ellipsize(font, NovaType.Face.CAPTION, notice, cx1 - cx0), cx0,
                    NovaType.centerY(NovaType.Face.CAPTION, top, 10), NovaGui.TEXT_MUTED);
            top += 14;
        }

        List<HubClient.Addon> list = filtered();
        if (list.isEmpty()) {
            int cx = (cx0 + cx1) / 2;
            int cy = (top + cy1) / 2;
            String title = !HubClient.error().isEmpty() ? HubClient.error()
                    : HubClient.loading() ? "Loading add-ons..."
                    : HubClient.addons().isEmpty() ? "No add-ons for this Minecraft version yet" : "Nothing matches your search";
            NovaType.centered(g, font, NovaType.Face.BODY, title, cx, cy - 10, NovaGui.TEXT_DIM);
            NovaType.centered(g, font, NovaType.Face.CAPTION, "Add-ons are Fabric mods with the \"nova\" entry point, checked and signed by Nova",
                    cx, cy + 6, NovaGui.TEXT_MUTED);
            return pointer;
        }

        scroll = NovaGui.approach(scroll, scrollTarget, NovaGui.SPEED, dt);
        g.enableScissor(cx0, top, cx1, cy1);
        int y = top - Math.round(scroll);
        for (HubClient.Addon addon : list) {
            if (y + CARD_H > top && y < cy1) {
                pointer |= card(g, addon, cx0, y, cx1, mouseX, mouseY, top);
            }
            y += CARD_H + CARD_GAP;
        }
        g.disableScissor();
        maxScroll = Math.max(0, list.size() * (CARD_H + CARD_GAP) - CARD_GAP - (cy1 - top));
        scrollTarget = Math.min(scrollTarget, maxScroll);
        return pointer;
    }

    private List<HubClient.Addon> filtered() {
        String query = search.trim().toLowerCase(Locale.ROOT);
        List<HubClient.Addon> result = new ArrayList<>();
        for (HubClient.Addon addon : HubClient.addons()) {
            if (query.isEmpty() || (addon.name() + " " + addon.author() + " " + addon.description()).toLowerCase(Locale.ROOT).contains(query)) {
                result.add(addon);
            }
        }
        return result;
    }

    /** Karte eines Add-ons: Name, Version, Autor, Beschreibung, Bewertung, Downloads und der passende Knopf. */
    private boolean card(GuiGraphicsExtractor g, HubClient.Addon addon, int x0, int y, int x1, int mouseX, int mouseY, int top) {
        boolean pointer = false;
        NovaShapes.rect(g, x0, y, x1, y + CARD_H, NovaGui.RADIUS, NovaGui.CARD);
        NovaShapes.outline(g, x0, y, x1, y + CARD_H, NovaGui.RADIUS, NovaGui.BORDER);
        int tx = x0 + 12;
        NovaType.draw(g, font, NovaType.Face.STRONG, addon.name(), tx, NovaType.centerY(NovaType.Face.STRONG, y + 6, 12), NovaGui.TEXT);
        int nx = tx + NovaType.width(font, NovaType.Face.STRONG, addon.name()) + 6;
        NovaType.draw(g, font, NovaType.Face.MONO, addon.version(), nx, NovaType.centerY(NovaType.Face.MONO, y + 6, 12), NovaGui.TEXT_MUTED);
        nx += NovaType.width(font, NovaType.Face.MONO, addon.version()) + 8;
        NovaType.draw(g, font, NovaType.Face.CAPTION, "by " + addon.author(), nx, NovaType.centerY(NovaType.Face.CAPTION, y + 6, 12),
                NovaGui.TEXT_MUTED);

        int buttonsX = x1 - 12 - 112;
        NovaType.draw(g, font, NovaType.Face.BODY, NovaType.ellipsize(font, NovaType.Face.BODY, addon.description(), buttonsX - tx - 8),
                tx, NovaType.centerY(NovaType.Face.BODY, y + 20, 12), NovaGui.TEXT_DIM);

        // Bewertung: 5 Punkte (Klick = eigene Bewertung), Schnitt, Anzahl, Downloads
        HubClient.Rating rating = HubClient.rating(addon.id());
        int mineStars = HubClient.myRating(addon.id());
        float cy = y + 42.5F;
        int hoverStar = 0;
        for (int i = 1; i <= 5; i++) {
            float cx = tx + DOT + (i - 1) * DOT_STEP;
            if (mouseY >= top && NovaControls.hit(mouseX, mouseY, (int) cx - 4, (int) cy - 5, (int) cx + 5, (int) cy + 5)) {
                hoverStar = i;
            }
        }
        float shown = hoverStar > 0 ? hoverStar : mineStars > 0 ? mineStars : rating == null ? 0.0F : (float) rating.avg();
        for (int i = 1; i <= 5; i++) {
            float cx = tx + DOT + (i - 1) * DOT_STEP;
            float fill = Math.max(0.0F, Math.min(1.0F, shown - (i - 1)));
            NovaShapes.disc(g, cx, cy, DOT, NovaGui.TRACK);
            if (fill > 0.0F) {
                NovaShapes.disc(g, cx, cy, DOT, NovaGui.alpha(hoverStar > 0 || mineStars > 0 ? NovaGui.BRAND : 0xFFF5C451, fill));
            }
            int stars = i;
            hits.add(new Hit((int) cx - 4, Math.max((int) cy - 5, top), (int) cx + 5, (int) cy + 5, () -> HubClient.rate(addon, stars)));
        }
        pointer |= hoverStar > 0;
        String info = (rating == null ? "No ratings" : String.format(Locale.ROOT, "%.1f  (%d)", rating.avg(), rating.count()))
                + "   ·   " + downloads(HubClient.downloads(addon.id()));
        NovaType.draw(g, font, NovaType.Face.CAPTION, info, tx + 5 * DOT_STEP + 4, NovaType.centerY(NovaType.Face.CAPTION, (int) cy - 6, 12),
                NovaGui.TEXT_MUTED);

        // Knopf je nach Zustand
        int bx1 = x1 - 12;
        int bx0 = bx1 - 112;
        int by0 = y + (CARD_H - 20) / 2;
        int by1 = by0 + 20;
        float progress = HubClient.progress(addon.id());
        if (progress >= 0.0F) {
            NovaControls.button(g, font, bx0, by0, bx1, by1, "Downloading " + Math.round(progress * 100) + "%",
                    NovaControls.Variant.GHOST, false, false);
            return pointer;
        }
        switch (HubClient.status(addon)) {
            case NOT_INSTALLED -> pointer |= button(g, bx0, by0, bx1, by1, "Install", NovaControls.Variant.PRIMARY, mouseX, mouseY,
                    () -> HubClient.install(addon), top);
            case UPDATE -> {
                pointer |= button(g, bx0, by0, bx1 - 34, by1, "Update", NovaControls.Variant.PRIMARY, mouseX, mouseY,
                        () -> HubClient.install(addon), top);
                pointer |= button(g, bx1 - 30, by0, bx1, by1, "×", NovaControls.Variant.GHOST, mouseX, mouseY,
                        () -> HubClient.remove(addon), top);
            }
            case INSTALLED -> pointer |= button(g, bx0, by0, bx1, by1, "Remove", NovaControls.Variant.DANGER, mouseX, mouseY,
                    () -> HubClient.remove(addon), top);
            case PENDING_INSTALL, PENDING_UPDATE -> {
                NovaControls.button(g, font, bx0, by0, bx1 - 34, by1, "Restart to use", NovaControls.Variant.GHOST, false, false);
                pointer |= button(g, bx1 - 30, by0, bx1, by1, "×", NovaControls.Variant.GHOST, mouseX, mouseY,
                        () -> HubClient.remove(addon), top);
            }
            case PENDING_REMOVE -> NovaControls.button(g, font, bx0, by0, bx1, by1, "Removed on restart",
                    NovaControls.Variant.GHOST, false, false);
        }
        return pointer;
    }

    private static String downloads(long count) {
        if (count >= 1000) {
            return String.format(Locale.ROOT, "%.1fk downloads", count / 1000.0D);
        }
        return count + (count == 1 ? " download" : " downloads");
    }

    private boolean button(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, String label, NovaControls.Variant variant,
                           int mouseX, int mouseY, Runnable action, int top) {
        boolean hover = mouseY >= top && mouseY < cy1 && NovaControls.hit(mouseX, mouseY, x0, y0, x1, y1);
        NovaControls.button(g, font, x0, y0, x1, y1, label, variant, hover, true);
        if (y1 > top && y0 < cy1) {
            hits.add(new Hit(x0, Math.max(y0, top), x1, Math.min(y1, cy1), action));
        }
        return hover;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (NovaControls.hit(event.x(), event.y(), hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
                    NovaGui.clickSound();
                    hit.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollTarget = Math.max(0.0F, Math.min(maxScroll, scrollTarget - (float) scrollY * 30.0F));
        return true;
    }
}
