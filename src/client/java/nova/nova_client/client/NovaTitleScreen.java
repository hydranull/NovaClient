package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.realmsclient.RealmsMainScreen;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.SafetyScreen;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screens.options.LanguageSelectScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.SkinCustomizationScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerSkin;
import nova.nova_client.client.update.UpdateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Der Titelbildschirm im Nova-Look: ruhiger dunkler Hintergrund mit dem Sternenhimmel des Nova-Menues, links die
 * Wortmarke und darunter die Eintraege als klare Textliste (Maus oder Tasten 1-6, Pfeile/Tab und Enter), darunter
 * "Join" fuer den ersten Server. Oben rechts das eigene Profil, oben in der Mitte (falls vorhanden) das Update, unten
 * Version und Links. Erbt von {@link TitleScreen}, damit alles, was im Spiel "ist das der Titelbildschirm?" fragt,
 * weiter funktioniert.
 */
public class NovaTitleScreen extends TitleScreen {
    private static final int ENTRY_H = 26;

    private record Entry(String label, boolean enabled, Runnable action) {
    }

    /** Klickflaeche, die beim Zeichnen gesammelt wird. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private float[] hover = new float[0];
    private int keyFocus = -1;
    private long openedAt = Util.getMillis();
    private long lastFrame = openedAt;
    private boolean pointer;
    private float joinHover;
    private float updateHover;

    private Supplier<PlayerSkin> skin;
    private ServerData server;
    private boolean serverLoaded;

    public NovaTitleScreen() {
        super(false, null);
    }

    // ================================================================ Hintergrund fuer alle anderen Menues

    /** Soll ein Menue statt des Minecraft-Panoramas den Nova-Hintergrund bekommen? (Nur ausserhalb einer Welt.) */
    public static boolean replacesMenuBackground(Minecraft minecraft) {
        return UiSettings.novaScreens && minecraft.level == null;
    }

    /** Der Hintergrund des Titelbildschirms ohne Text - fuer Optionen, Mehrspieler, Ladebildschirme usw. */
    public static void drawMenuBackground(GuiGraphicsExtractor g, int width, int height) {
        g.fill(0, 0, width, height, NovaGui.BG);
        NovaStars.draw(g, 0, 0, width, height, 1.0F);
    }

    // ================================================================ Aufbau

    @Override
    protected void init() {
        entries.clear();
        boolean multiplayer = minecraft.allowsMultiplayer();
        entries.add(new Entry("Singleplayer", true, () -> minecraft.gui.setScreen(new SelectWorldScreen(this))));
        entries.add(new Entry("Multiplayer", multiplayer, () -> minecraft.gui.setScreen(minecraft.options.skipMultiplayerWarning
                ? new JoinMultiplayerScreen(this) : new SafetyScreen(this))));
        entries.add(new Entry("Realms", multiplayer, () -> minecraft.gui.setScreen(new RealmsMainScreen(this))));
        entries.add(new Entry("Modules", true, () -> minecraft.gui.setScreen(UiSettings.mainMenu())));
        entries.add(new Entry("Options", true, () -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options))));
        entries.add(new Entry("Quit", true, minecraft::stop));
        if (hover.length != entries.size()) {
            hover = new float[entries.size()];
        }
        if (skin == null) {
            skin = minecraft.getSkinManager().createLookup(minecraft.getGameProfile(), false);
        }
        if (!serverLoaded) {
            serverLoaded = true;
            try {
                ServerList list = new ServerList(minecraft);
                list.load();
                server = list.size() > 0 ? list.get(0) : null;
            } catch (RuntimeException e) {
                server = null;
            }
        }
    }

    private boolean hasJoin() {
        return server != null && minecraft.allowsMultiplayer();
    }

    // ================================================================ Zeichnen

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        long now = Util.getMillis();
        float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        if (minecraft.gui.overlay() != null) {
            openedAt = now; // Einblenden erst, wenn der Ladebildschirm weg ist
        }
        float appear = UiSettings.animations ? NovaGui.easeOutCubic((now - openedAt) / 450.0F) : 1.0F;
        hits.clear();
        pointer = false;

        drawMenuBackground(g, width, height);
        int left = Math.max(28, Math.round(width * 0.09F));
        int blockH = 56 + entries.size() * ENTRY_H + (hasJoin() ? 36 : 0);
        int top = Math.max(40, (height - blockH) / 2 - 8) + Math.round((1.0F - appear) * 8.0F);

        // Wortmarke "nova" (Grundlinie bei top + 22) und Zeile darunter
        NovaType.Face display = NovaType.Face.DISPLAY;
        int markY = top + 22 - NovaType.BASELINE;
        NovaType.draw(g, font, display, "nova", left, markY, NovaGui.alpha(NovaGui.TEXT, appear));
        String line = "Minecraft " + SharedConstants.getCurrentVersion().name() + "  ·  " + Mods.mods.size() + " modules";
        NovaType.draw(g, font, NovaType.Face.CAPTION, line, left + 1, top + 32, NovaGui.alpha(NovaGui.TEXT_MUTED, appear));

        int y = top + 56;
        for (int i = 0; i < entries.size(); i++) {
            drawEntry(g, i, left, y, mouseX, mouseY, dt, appear);
            y += ENTRY_H;
        }
        if (hasJoin()) {
            drawJoin(g, left, y + 10, mouseX, mouseY, dt, appear);
        }
        drawProfile(g, mouseX, mouseY, appear);
        drawUpdate(g, mouseX, mouseY, dt, appear);
        drawFooter(g, mouseX, mouseY, appear);

        if (pointer) {
            g.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    /** Eintrag als Text: beim Drueberfahren heller, leicht eingerueckt, mit Akzentstrich links. */
    private void drawEntry(GuiGraphicsExtractor g, int index, int left, int y, int mouseX, int mouseY, float dt, float appear) {
        Entry e = entries.get(index);
        NovaType.Face face = NovaType.Face.TITLE;
        int w = NovaType.width(font, face, e.label()) + 28;
        boolean over = e.enabled() && mouseX >= left - 8 && mouseX < left + w && mouseY >= y && mouseY < y + ENTRY_H;
        if (over) {
            keyFocus = -1;
        }
        boolean lit = over || keyFocus == index;
        hover[index] = NovaGui.approach(hover[index], lit ? 1.0F : 0.0F, 16.0F, dt);
        float h = hover[index];
        int ty = NovaType.centerY(face, y, ENTRY_H);
        if (h > 0.02F) {
            int barH = Math.round(4.0F + 8.0F * h);
            int by = y + (ENTRY_H - barH) / 2;
            g.fill(left - 10, by, left - 8, by + barH, NovaGui.alpha(NovaGui.BRAND, h * appear));
        }
        int color = !e.enabled() ? NovaGui.alpha(NovaGui.TEXT_MUTED, 0.6F) : NovaGui.mix(NovaGui.TEXT_DIM, NovaGui.TEXT, h);
        NovaType.draw(g, font, face, e.label(), left + Math.round(4.0F * h), ty, NovaGui.alpha(color, appear));
        if (e.enabled()) {
            hits.add(new Hit(left - 8, y, left + w, y + ENTRY_H, () -> activate(e)));
        }
        pointer |= over;
    }

    /** Schnellstart: ersten Server betreten. */
    private void drawJoin(GuiGraphicsExtractor g, int left, int y, int mouseX, int mouseY, float dt, float appear) {
        String label = NovaGui.ellipsize(font, "Join " + server.name, Math.max(80, width / 3));
        int x0 = left;
        int x1 = x0 + NovaGui.width(font, label) + 30;
        int y1 = y + 20;
        boolean over = mouseX >= x0 && mouseX < x1 && mouseY >= y && mouseY < y1;
        joinHover = NovaGui.approach(joinHover, over ? 1.0F : 0.0F, 16.0F, dt);
        NovaShapes.rect(g, x0, y, x1, y1, NovaGui.RADIUS, NovaGui.alpha(NovaGui.mix(NovaGui.CARD, NovaGui.RAISED, joinHover), appear));
        NovaShapes.outline(g, x0, y, x1, y1, NovaGui.RADIUS, NovaGui.alpha(NovaGui.mix(NovaGui.BORDER, NovaGui.BORDER_STRONG,
                joinHover), appear));
        NovaShapes.disc(g, x0 + 11.0F, y + 10.0F, 2.5F, NovaGui.alpha(NovaGui.SUCCESS, appear));
        NovaGui.text(g, font, label, x0 + 19, NovaType.centerY(NovaType.Face.BODY, y, 20),
                NovaGui.alpha(NovaGui.mix(NovaGui.TEXT_DIM, NovaGui.TEXT, joinHover), appear));
        ServerData data = server;
        hits.add(new Hit(x0, y, x1, y1, () -> {
            NovaGui.clickSound();
            ConnectScreen.startConnecting(this, minecraft, ServerAddress.parseString(data.ip), data, false, null);
        }));
        pointer |= over;
    }

    /** Oben rechts: Kopf und Name (Klick = Skin-Einstellungen). */
    private void drawProfile(GuiGraphicsExtractor g, int mouseX, int mouseY, float appear) {
        String name = minecraft.getUser().getName();
        int nameW = NovaGui.width(font, name);
        int x1 = width - 18;
        int x0 = x1 - nameW - 26;
        int y0 = 14;
        int y1 = y0 + 22;
        boolean over = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        if (over) {
            NovaShapes.rect(g, x0 - 4, y0, x1 + 4, y1, NovaGui.RADIUS, NovaGui.alpha(NovaGui.HOVER, appear));
        }
        PlayerFaceExtractor.extractRenderState(g, skin.get(), x0, y0 + 3, 16, NovaGui.alpha(0xFFFFFFFF, appear));
        NovaGui.text(g, font, name, x0 + 24, NovaType.centerY(NovaType.Face.BODY, y0, 22),
                NovaGui.alpha(over ? NovaGui.TEXT : NovaGui.TEXT_DIM, appear));
        hits.add(new Hit(x0 - 4, y0, x1 + 4, y1, () -> {
            NovaGui.clickSound();
            minecraft.gui.setScreen(new SkinCustomizationScreen(this, minecraft.options));
        }));
        pointer |= over;
    }

    /** Update: waehrend es laedt der Fortschritt, danach eine Pille "Quit & install". */
    private void drawUpdate(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt, float appear) {
        UpdateManager.State state = UpdateManager.state();
        boolean ready = state == UpdateManager.State.READY;
        if (!ready && state != UpdateManager.State.DOWNLOADING) {
            return;
        }
        float progress = UpdateManager.progress();
        String text = ready ? "Update " + UpdateManager.newVersion() + " is ready  ·  Quit & install"
                : "Downloading update  ·  " + Math.round(progress * 100.0F) + "%";
        int w = NovaGui.width(font, text) + 34;
        int x0 = (width - w) / 2;
        int x1 = x0 + w;
        int y0 = 14;
        int y1 = y0 + 22;
        boolean over = ready && mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        updateHover = NovaGui.approach(updateHover, over ? 1.0F : 0.0F, 16.0F, dt);
        int accent = NovaGui.SUCCESS;
        NovaShapes.rect(g, x0, y0, x1, y1, NovaGui.RADIUS, NovaGui.alpha(NovaGui.mix(NovaGui.CARD, accent,
                ready ? 0.16F + 0.1F * updateHover : 0.0F), appear));
        NovaShapes.outline(g, x0, y0, x1, y1, NovaGui.RADIUS, NovaGui.alpha(ready ? NovaGui.alpha(accent, 0.6F + 0.4F * updateHover)
                : NovaGui.BORDER, appear));
        NovaShapes.disc(g, x0 + 13.0F, y0 + 11.0F, 2.5F, NovaGui.alpha(accent, appear));
        NovaGui.text(g, font, text, x0 + 22, NovaType.centerY(NovaType.Face.BODY, y0, 22), NovaGui.alpha(NovaGui.TEXT, appear));
        if (ready) {
            hits.add(new Hit(x0, y0, x1, y1, () -> {
                NovaGui.clickSound();
                minecraft.stop();
            }));
            pointer |= over;
        } else {
            int fill = x0 + 11 + Math.round((w - 22) * Math.max(0.0F, Math.min(1.0F, progress)));
            g.fill(x0 + 11, y1 - 3, x1 - 11, y1 - 2, NovaGui.alpha(NovaGui.TRACK, appear));
            g.fill(x0 + 11, y1 - 3, fill, y1 - 2, NovaGui.alpha(accent, appear));
        }
    }

    /** Unten: Version links, Links rechts. */
    private void drawFooter(GuiGraphicsExtractor g, int mouseX, int mouseY, float appear) {
        NovaType.Face face = NovaType.Face.CAPTION;
        int y0 = height - 22;
        int textY = NovaType.centerY(face, y0, 22);
        String version = UiSettings.version();
        NovaType.draw(g, font, face, "Nova " + version + "  ·  Minecraft " + SharedConstants.getCurrentVersion().name(), 18,
                textY, NovaGui.alpha(NovaGui.TEXT_MUTED, appear));

        String[] labels = {"Discord", "Language", "Accessibility", "Classic menu", "Credits"};
        Runnable[] actions = {
                NovaLinks::openDiscord,
                () -> minecraft.gui.setScreen(new LanguageSelectScreen(this, minecraft.options, minecraft.getLanguageManager())),
                () -> minecraft.gui.setScreen(new AccessibilityOptionsScreen(this, minecraft.options)),
                () -> {
                    UiSettings.novaScreens = false;
                    ModConfig.save();
                    minecraft.gui.setScreen(new TitleScreen());
                },
                () -> minecraft.gui.setScreen(new CreditsAndAttributionScreen(this))
        };
        int x = width - 18;
        for (int i = labels.length - 1; i >= 0; i--) {
            // auf schmalen Fenstern nur die wichtigsten
            if (width < 520 && (i == 1 || i == 2)) {
                continue;
            }
            int w = NovaType.width(font, face, labels[i]);
            int x0 = x - w;
            boolean over = mouseX >= x0 - 4 && mouseX < x + 4 && mouseY >= y0 && mouseY < height;
            NovaType.draw(g, font, face, labels[i], x0, textY, NovaGui.alpha(over ? NovaGui.TEXT : NovaGui.TEXT_MUTED, appear));
            Runnable action = actions[i];
            hits.add(new Hit(x0 - 4, y0, x + 4, height, () -> {
                NovaGui.clickSound();
                action.run();
            }));
            pointer |= over;
            x = x0 - 16;
        }
    }

    private void activate(Entry e) {
        NovaGui.clickSound();
        e.action().run();
    }

    // ================================================================ Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return false;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(event.x(), event.y())) {
                hit.action().run();
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key >= InputConstants.KEY_1 && key <= InputConstants.KEY_9) {
            int index = key - InputConstants.KEY_1;
            if (index < entries.size() && entries.get(index).enabled()) {
                activate(entries.get(index));
                return true;
            }
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_TAB || key == InputConstants.KEY_UP) {
            int dir = key == InputConstants.KEY_UP ? -1 : 1;
            int next = keyFocus;
            for (int i = 0; i < entries.size(); i++) {
                next = Math.floorMod((next < 0 ? (dir > 0 ? -1 : 0) : next) + dir, entries.size());
                if (entries.get(next).enabled()) {
                    break;
                }
            }
            keyFocus = next;
            return true;
        }
        if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER || key == InputConstants.KEY_SPACE)
                && keyFocus >= 0 && keyFocus < entries.size() && entries.get(keyFocus).enabled()) {
            activate(entries.get(keyFocus));
            return true;
        }
        return super.keyPressed(event);
    }
}
