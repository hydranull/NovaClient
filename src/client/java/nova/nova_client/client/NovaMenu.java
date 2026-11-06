package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import nova.nova_client.client.mods.Mod;

import java.util.List;
import java.util.Locale;

/**
 * Das Nova-Menue: ueber der leicht abgedunkelten Spielwelt schweben eine runde Leiste (Wortmarke, Suche, Zaehler,
 * Profile / HUD / Einstellungen) und alle Kategorien als Glas-Karten nebeneinander - mit einem kleinen, bewegten
 * Sternenhimmel darin (treibende, funkelnde Sterne, ab und zu eine Sternschnuppe). Jedes Modul ist eine Zeile; an ist
 * sie als runde Flaeche in der Akzentfarbe hinterlegt. Unten rechts zeigt eine Vorschau, was das Modul unter der Maus
 * macht (am eigenen Charakter oder als HUD-Element) - nur bei Modulen, bei denen man wirklich etwas sehen kann.
 * <p>
 * Klick schaltet, Rechtsklick oder "›" oeffnet die Einstellungen, Mausrad-Klick legt eine Taste fest, Mausrad scrollt
 * eine zu lange Karte. Lostippen sucht (Name und Beschreibung), Pfeile waehlen, Enter schaltet, Pfeil rechts oeffnet
 * die Einstellungen. Ein Klick auf den Zaehler zeigt nur die aktiven Module. Sind die Karten fuer das Fenster zu
 * schmal, stehen sie in mehreren Reihen und das Brett scrollt.
 */
public class NovaMenu extends Screen {
    private static final int MARGIN = 16;
    private static final int BAR_Y = 12;
    private static final int BAR_H = 30;
    private static final int CONTROL = 20;
    private static final int GAP = 10;
    /** Abstand zwischen schmalen Karten. */
    private static final int GAP_NARROW = 8;
    /** Kopf einer Karte, Zeilenhoehe und Rand unter der letzten Zeile. */
    private static final int HEAD = 28;
    private static final int ROW = 16;
    private static final int PAD_B = 6;
    /** Abstand des Sternenhimmels zum Kartenrand (so bleibt er sicher innerhalb der runden Ecken). */
    private static final int STAR_INSET = 5;
    /** Breite des Pfeils zu den Einstellungen. */
    private static final int SETTINGS_W = 14;
    private static final int MIN_COLUMN = 92;
    private static final int MAX_COLUMN = 168;
    private static final int MAX_QUERY = 32;
    /** Vorschau: Textbereich unter der Buehne, Innenabstand, Breite (die Buehne selbst bemisst ModPreview). */
    private static final int PREVIEW_TEXT_H = 87;
    private static final int PREVIEW_PAD = 11;
    private static final int PREVIEW_MIN_W = 124;
    private static final int PREVIEW_MAX_W = 168;
    /** So lange bleibt die Vorschau, wenn die Maus eine Zeile verlaesst (kein Flackern zwischen zwei Karten). */
    private static final long PREVIEW_HOLD = 160L;
    private static final String[] TOOLS = {"Profiles", "HUD", "Social", "Hub", "Settings"};

    private final List<Mod> mods = Mods.mods;
    private final ModCategory[] categories = ModCategory.values();
    /** Sichtbare Module je Kategorie (Index in Mods.mods) nach Suche und Filter. */
    private final int[][] visible = new int[categories.length][];
    /** Lage jeder Karte (relativ zum Brett, ohne Scrollen); Breite 0 = Kategorie hat keine Treffer. */
    private final int[] cardX = new int[categories.length];
    private final int[] cardY = new int[categories.length];
    private final int[] cardW = new int[categories.length];
    private final int[] cardH = new int[categories.length];
    /** Reihenfolge der Karten (fuer das Einblenden nacheinander). */
    private final int[] cardOrder = new int[categories.length];
    /** Scrollen einzelner Karten (wenn sie in einer Reihe stehen und laenger als das Fenster sind). */
    private final float[] cardScroll = new float[categories.length];
    private final float[] cardScrollTarget = new float[categories.length];
    /** Mehrere Reihen: das ganze Brett scrollt. */
    private boolean wrapped;
    /** Abstand zwischen den Karten und Innenabstand des Textes (bei schmalen Karten enger). */
    private int gap = GAP;
    private int inset = 12;
    private int contentHeight;
    private float scroll;
    private float scrollTarget;
    /** Sichtbare Module in Lese-Reihenfolge (fuer Pfeiltasten). */
    private int[] order = new int[0];

    // Pro Modul: kleingeschriebener Name und Beschreibung (Suche), Animationen, Tastenname
    private final String[] names;
    private final String[] descriptions;
    private final String[] searchText;
    private final float[] hoverAnim;
    private final float[] onAnim;
    private final int[] keyCodes;
    private final String[] keyNames;
    // Zaehler je Kategorie ("3/15") und oben ("12 active"), nur bei Aenderung neu
    private final int[] lastOn = new int[categories.length];
    private final String[] countTexts = new String[categories.length];
    private int lastActive = -1;
    private String activeText = "";
    private String emptyText = "";

    private final long openedAt = Util.getMillis();
    private long lastFrame = openedAt;

    // Leiste und Brett
    private int barX0, barX1;
    private int sx0, sx1, cx0, cx1;
    private final int[] toolX0 = new int[TOOLS.length];
    private final int[] toolX1 = new int[TOOLS.length];
    private int boardTop, boardBottom;

    private String query = "";
    private boolean onlyActive;
    private int listening = -1;
    private boolean swallowChar;
    /** Modul mit Tastatur-Fokus (Index in Mods.mods, -1 = keins). */
    private int focus = -1;
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    /** Modul unter der Maus (aus dem letzten Bild), seine Karte und Zeile. */
    private int hovered = -1;
    private int hoveredCard = -1;
    private int hoveredRowY;
    private boolean hoveredSettings;
    // Vorschau: gezeigtes Modul, Einblenden, seit wann und wie lange noch, umgebrochene Beschreibung, Lage
    private final ModPreview preview = new ModPreview();
    private int previewIndex = -1;
    private float previewAnim;
    private long previewSince;
    private long previewHold;
    private final String[] previewLines = new String[3];
    private int previewLineCount;
    private int previewW = PREVIEW_MIN_W;
    private int boardLeft, boardRight;
    // Fusszeile zur Taste (nur bei Aenderung neu)
    private int footerIndex = -1;
    private int footerKey;
    private String footerText = "";

    public NovaMenu() {
        super(Component.literal("Nova"));
        int n = mods.size();
        names = new String[n];
        descriptions = new String[n];
        searchText = new String[n];
        hoverAnim = new float[n];
        onAnim = new float[n];
        keyCodes = new int[n];
        keyNames = new String[n];
        for (int i = 0; i < n; i++) {
            Mod mod = mods.get(i);
            names[i] = mod.name.toLowerCase(Locale.ROOT);
            descriptions[i] = ModInfo.description(mod);
            searchText[i] = descriptions[i].toLowerCase(Locale.ROOT);
            onAnim[i] = mod.active ? 1.0F : 0.0F;
            keyCodes[i] = Integer.MIN_VALUE;
        }
        java.util.Arrays.fill(lastOn, -1);
    }

    @Override
    protected void init() {
        layout();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    // ---------------------------------------------------------------- Aufbau

    /** Passt das Modul zur Suche? 0 = Name, 1 = nur Beschreibung, -1 = nein. */
    private int match(int index, String q) {
        if (onlyActive && !mods.get(index).active) {
            return -1;
        }
        if (q.isEmpty()) {
            return 0;
        }
        return names[index].contains(q) ? 0 : searchText[index].contains(q) ? 1 : -1;
    }

    /** Leiste und Karten neu aufbauen (bei Groessenaenderung, Suche oder Filter). */
    private void layout() {
        int available = width - MARGIN * 2;
        boardTop = BAR_Y + BAR_H + 12;
        boardBottom = height - MARGIN;

        // Leiste so breit wie alle sieben Karten nebeneinander (bleibt beim Suchen gleich)
        int all = categories.length;
        int fullColumn = Math.min(MAX_COLUMN, (available - (all - 1) * GAP) / all);
        int barW = Math.min(available, Math.max(all * fullColumn + (all - 1) * GAP, 560));
        barX0 = (width - barW) / 2;
        barX1 = barX0 + barW;
        layoutBar();

        // Treffer je Kategorie: Namens-Treffer vor Beschreibungs-Treffern
        String q = query.toLowerCase(Locale.ROOT);
        int total = 0;
        int shown = 0;
        int[] tmp = new int[mods.size()];
        for (int c = 0; c < categories.length; c++) {
            int count = 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < mods.size(); i++) {
                    if (mods.get(i).category == categories[c] && match(i, q) == pass) {
                        tmp[count++] = i;
                    }
                }
            }
            visible[c] = java.util.Arrays.copyOf(tmp, count);
            total += count;
            cardW[c] = 0;
            if (count > 0) {
                cardOrder[shown++] = c;
            }
        }

        // Spalten: alle nebeneinander (bei schmalen Fenstern mit weniger Abstand) - erst wenn sie dann noch zu schmal
        // sind, in Reihen umbrechen
        contentHeight = 0;
        if (shown > 0) {
            int perRow = shown;
            gap = GAP;
            int column = (available - (shown - 1) * gap) / shown;
            if (column < 120) {
                gap = GAP_NARROW;
                column = (available - (shown - 1) * gap) / shown;
            }
            if (column < MIN_COLUMN) {
                perRow = Math.max(1, (available + gap) / (MIN_COLUMN + gap));
                column = (available - (perRow - 1) * gap) / perRow;
            }
            column = Math.min(MAX_COLUMN, column);
            inset = column < 112 ? 9 : 12;
            wrapped = perRow < shown;
            int view = boardBottom - boardTop;
            int y = 0;
            for (int start = 0; start < shown; start += perRow) {
                int inRow = Math.min(perRow, shown - start);
                int left = (width - (inRow * column + (inRow - 1) * gap)) / 2;
                int rowHeight = 0;
                for (int k = 0; k < inRow; k++) {
                    int c = cardOrder[start + k];
                    int full = HEAD + visible[c].length * ROW + PAD_B;
                    cardX[c] = left + k * (column + gap);
                    cardY[c] = y;
                    cardW[c] = column;
                    cardH[c] = wrapped ? full : Math.min(full, view);
                    rowHeight = Math.max(rowHeight, cardH[c]);
                    cardScrollTarget[c] = Math.min(cardScrollTarget[c], maxCardScroll(c));
                    cardScroll[c] = Math.min(cardScroll[c], maxCardScroll(c));
                }
                y += rowHeight + gap;
            }
            contentHeight = y - gap;
        }

        // Vorschau: buendig mit der rechten Karte und etwa so breit (in Grenzen)
        boardLeft = MARGIN;
        boardRight = width - MARGIN;
        int rightW = PREVIEW_MIN_W;
        if (shown > 0) {
            boardLeft = width;
            boardRight = 0;
            for (int c = 0; c < categories.length; c++) {
                if (cardW[c] > 0) {
                    boardLeft = Math.min(boardLeft, cardX[c]);
                    if (cardX[c] + cardW[c] >= boardRight) {
                        boardRight = cardX[c] + cardW[c];
                        rightW = cardW[c];
                    }
                }
            }
        }
        int previewWidth = Math.max(PREVIEW_MIN_W, Math.min(PREVIEW_MAX_W, rightW));
        if (previewWidth != previewW) {
            previewW = previewWidth;
            previewIndex = -1; // Beschreibung fuer die neue Breite neu umbrechen
        }

        order = new int[total];
        int k = 0;
        for (int i = 0; i < shown; i++) {
            for (int index : visible[cardOrder[i]]) {
                order[k++] = index;
            }
        }
        if (focus >= 0 && indexIn(order, focus) < 0) {
            focus = -1;
        }
        emptyText = onlyActive && query.isEmpty() ? "No modules are active" : "No modules match \"" + query + "\"";
        scrollTarget = Math.min(scrollTarget, maxScroll());
        scroll = Math.min(scroll, maxScroll());
    }

    /** Leiste: Wortmarke links, Werkzeuge rechts, dazwischen Zaehler und Suche. */
    private void layoutBar() {
        int x = barX1 - 6;
        for (int i = TOOLS.length - 1; i >= 0; i--) {
            int w = NovaType.width(font, NovaType.Face.BODY, TOOLS[i]) + 20;
            toolX1[i] = x;
            toolX0[i] = x - w;
            x -= w + 2;
        }
        cx1 = x - 6;
        cx0 = cx1 - 64;
        int left = barX0 + 16 + NovaType.width(font, NovaType.Face.HEADING, "nova") + 20;
        int right = cx0 - 12;
        int w = Math.max(90, Math.min(320, right - left));
        sx0 = Math.max(left, Math.min(right - w, (barX0 + barX1 - w) / 2));
        sx1 = sx0 + w;
    }

    /** Wie weit eine Karte in sich scrollen kann (nur wenn alle in einer Reihe stehen). */
    private int maxCardScroll(int c) {
        if (wrapped || cardW[c] == 0) {
            return 0;
        }
        return Math.max(0, visible[c].length * ROW - (cardH[c] - HEAD - PAD_B));
    }

    private int maxScroll() {
        return wrapped ? Math.max(0, contentHeight - (boardBottom - boardTop)) : 0;
    }

    private void setQuery(String value) {
        if (!value.equals(query)) {
            query = value;
            scroll = scrollTarget = 0.0F;
            java.util.Arrays.fill(cardScroll, 0.0F);
            java.util.Arrays.fill(cardScrollTarget, 0.0F);
            layout();
            focus = query.isEmpty() || order.length == 0 ? -1 : order[0];
        }
    }

    private static int indexIn(int[] array, int value) {
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Treffer

    /** Oberkante einer Karte auf dem Bildschirm. */
    private int cardTop(int c) {
        return boardTop + cardY[c] - Math.round(scroll);
    }

    /** Karte unter der Maus (-1 = keine). */
    private int cardAt(double mx, double my) {
        if (my < boardTop || my >= boardBottom) {
            return -1;
        }
        for (int c = 0; c < categories.length; c++) {
            int y = cardTop(c);
            if (cardW[c] > 0 && mx >= cardX[c] && mx < cardX[c] + cardW[c] && my >= y && my < y + cardH[c]) {
                return c;
            }
        }
        return -1;
    }

    /** Modul unter der Maus (Index in Mods.mods, -1 = keins); setzt Karte, Zeile und hoveredSettings. */
    private int modAt(double mx, double my) {
        hoveredSettings = false;
        hoveredCard = cardAt(mx, my);
        if (hoveredCard < 0) {
            return -1;
        }
        int c = hoveredCard;
        int bodyTop = cardTop(c) + HEAD;
        int bodyBottom = cardTop(c) + cardH[c] - PAD_B;
        if (my < bodyTop || my >= bodyBottom) {
            return -1;
        }
        int rowsTop = bodyTop - Math.round(cardScroll[c]);
        int row = (int) Math.floor((my - rowsTop) / ROW);
        if (row < 0 || row >= visible[c].length) {
            return -1;
        }
        int index = visible[c][row];
        hoveredRowY = rowsTop + row * ROW;
        hoveredSettings = mods.get(index).hasSettings() && mx >= cardX[c] + cardW[c] - inset - SETTINGS_W + 4;
        return index;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int button = event.button();
        double mx = event.x();
        double my = event.y();
        if (listening >= 0 && button != 2) {
            listening = -1;
            return true;
        }
        if (button == 0) {
            for (int i = 0; i < TOOLS.length; i++) {
                if (NovaControls.hit(mx, my, toolX0[i], controlY(), toolX1[i], controlY() + CONTROL)) {
                    NovaGui.clickSound();
                    openTool(i);
                    return true;
                }
            }
            if (NovaControls.hit(mx, my, cx0, controlY(), cx1, controlY() + CONTROL)) {
                NovaGui.clickSound();
                onlyActive = !onlyActive;
                scroll = scrollTarget = 0.0F;
                layout();
                return true;
            }
        }
        int index = modAt(mx, my);
        if (index < 0) {
            return super.mouseClicked(event, doubleClick);
        }
        Mod mod = mods.get(index);
        if (button == 0 && !(hoveredSettings && mod.hasSettings())) {
            mod.toggle(Minecraft.getInstance());
            NovaGui.clickSound();
        } else if ((button == 0 || button == 1) && mod.hasSettings()) {
            openSettings(mod);
        } else if (button == 2) {
            listening = listening == index ? -1 : index;
            NovaGui.clickSound();
        }
        return true;
    }

    private void openTool(int tool) {
        Minecraft mc = Minecraft.getInstance();
        switch (tool) {
            case 0 -> mc.gui.setScreen(new ProfileMenu(this));
            case 1 -> mc.gui.setScreen(new HudEditorScreen(this));
            case 2 -> {
                for (Mod mod : mods) {
                    if (mod instanceof nova.nova_client.client.mods.NovaSocialMod) {
                        mc.gui.setScreen(new nova.nova_client.client.social.SocialScreen(this, mod));
                    }
                }
            }
            case 3 -> mc.gui.setScreen(new nova.nova_client.client.hub.HubScreen(this));
            default -> mc.gui.setScreen(UiSettings.screen(this));
        }
    }

    private void openSettings(Mod mod) {
        NovaGui.clickSound();
        Screen screen = mod.createSettings(this);
        if (screen instanceof NovaSettingsScreen settings) {
            settings.setModule(mod);
        }
        Minecraft.getInstance().gui.setScreen(screen);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0D) {
            int c = cardAt(mouseX, mouseY);
            if (c >= 0 && maxCardScroll(c) > 0) {
                cardScrollTarget[c] = Math.max(0.0F, Math.min(maxCardScroll(c), cardScrollTarget[c] - (float) scrollY * ROW * 3.0F));
                return true;
            }
            if (maxScroll() > 0) {
                scrollTarget = Math.max(0.0F, Math.min(maxScroll(), scrollTarget - (float) scrollY * ROW * 3.0F));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (listening >= 0) {
            if (!event.isEscape()) {
                boolean clear = event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE;
                mods.get(listening).keyCode = clear ? Mod.NO_KEY : event.key();
                ModConfig.save();
                NovaGui.clickSound();
            }
            listening = -1;
            swallowChar = true;
            return true;
        }
        int key = event.key();
        if (event.isEscape() && !query.isEmpty()) {
            setQuery("");
            return true;
        }
        if (event.isEscape() && onlyActive) {
            onlyActive = false;
            layout();
            return true;
        }
        if (key == InputConstants.KEY_BACKSPACE && !query.isEmpty()) {
            setQuery(event.hasControlDown() ? "" : query.substring(0, query.length() - 1));
            return true;
        }
        if (event.isPaste()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\p{Cntrl}]", "");
            setQuery((query + clip).substring(0, Math.min(MAX_QUERY, query.length() + clip.length())));
            return true;
        }
        if ((key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) && order.length > 0) {
            int at = indexIn(order, focus);
            int next = at < 0 ? (key == InputConstants.KEY_DOWN ? 0 : order.length - 1)
                    : Math.floorMod(at + (key == InputConstants.KEY_DOWN ? 1 : -1), order.length);
            focus = order[next];
            revealFocus();
            return true;
        }
        if ((key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) && focus >= 0) {
            mods.get(focus).toggle(Minecraft.getInstance());
            NovaGui.clickSound();
            return true;
        }
        if (key == InputConstants.KEY_RIGHT && focus >= 0 && mods.get(focus).hasSettings()) {
            openSettings(mods.get(focus));
            return true;
        }
        return super.keyPressed(event);
    }

    /** Fokus-Zeile ins Bild scrollen (in ihrer Karte bzw. auf dem Brett). */
    private void revealFocus() {
        for (int c = 0; c < categories.length; c++) {
            int row = indexIn(visible[c], focus);
            if (row < 0 || cardW[c] == 0) {
                continue;
            }
            if (wrapped) {
                int top = cardY[c] + HEAD + row * ROW;
                int view = boardBottom - boardTop;
                if (top < scrollTarget) {
                    scrollTarget = Math.max(0, top - HEAD);
                } else if (top + ROW > scrollTarget + view) {
                    scrollTarget = Math.min(maxScroll(), top + 2 * ROW - view);
                }
            } else {
                int top = row * ROW;
                int view = cardH[c] - HEAD - PAD_B;
                if (top < cardScrollTarget[c]) {
                    cardScrollTarget[c] = top;
                } else if (top + ROW > cardScrollTarget[c] + view) {
                    cardScrollTarget[c] = Math.min(maxCardScroll(c), top + ROW - view);
                }
            }
            return;
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (listening >= 0 || swallowChar) {
            swallowChar = false;
            return true;
        }
        String c = event.codepointAsString();
        if (!c.isEmpty() && c.codePointAt(0) >= 32 && query.length() < MAX_QUERY && (!query.isEmpty() || !c.isBlank())) {
            setQuery(query + c);
            return true;
        }
        return super.charTyped(event);
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        NovaGui.background(graphics, width, height);
    }

    private int controlY() {
        return BAR_Y + (BAR_H - CONTROL) / 2;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        swallowChar = false;
        long now = Util.getMillis();
        float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float elapsed = UiSettings.animations ? (now - openedAt) / 1000.0F : 100.0F;
        scroll = NovaGui.approach(scroll, scrollTarget, NovaGui.SPEED, dt);
        for (int c = 0; c < categories.length; c++) {
            cardScroll[c] = NovaGui.approach(cardScroll[c], cardScrollTarget[c], NovaGui.SPEED, dt);
        }
        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            // Maus bewegt: Tastatur-Fokus weg (beim Suchen bleibt er auf dem Treffer, den Enter schaltet)
            if (lastMouseX != Integer.MIN_VALUE && query.isEmpty()) {
                focus = -1;
            }
            lastMouseX = mouseX;
            lastMouseY = mouseY;
        }
        hovered = modAt(mouseX, mouseY);
        // Vorschau: Modul unter der Maus, sonst das mit Tastatur-Fokus - nur wenn es etwas zu zeigen gibt
        int target = hovered >= 0 ? hovered : focus;
        if (target >= 0 && ModPreview.has(mods.get(target))) {
            previewHold = now + PREVIEW_HOLD;
            if (target != previewIndex) {
                previewIndex = target;
                previewSince = now;
                previewLineCount = NovaType.wrap(font, NovaType.Face.BODY, descriptions[target], previewW - 2 * PREVIEW_PAD,
                        previewLines);
            }
        }
        previewAnim = NovaGui.approach(previewAnim, now < previewHold ? 1.0F : 0.0F, NovaGui.FAST, dt);

        boolean pointer = drawBar(graphics, mouseX, mouseY, NovaGui.introAlpha(elapsed));
        drawBoard(graphics, dt, elapsed);
        if (previewIndex >= 0 && previewAnim > 0.01F) {
            drawPreview(graphics, now);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (pointer || hovered >= 0) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    /** Runde Leiste oben: Wortmarke, Suche, Zaehler, Werkzeuge. */
    private boolean drawBar(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int y0 = BAR_Y;
        int y1 = BAR_Y + BAR_H;
        int c0 = controlY();
        int c1 = c0 + CONTROL;
        int round = BAR_H / 2;
        boolean pointer = false;
        g.pose().pushMatrix();
        g.pose().translate(0.0F, Math.round((1.0F - a) * -6.0F));

        NovaShapes.rect(g, barX0, y0, barX1, y1, round, NovaGui.alpha(NovaGui.GLASS, a));
        NovaShapes.outline(g, barX0, y0, barX1, y1, round, NovaGui.alpha(NovaGui.BORDER_STRONG, a));

        // Wortmarke
        NovaType.Face mark = NovaType.Face.HEADING;
        NovaType.draw(g, font, mark, "nova", barX0 + 16, NovaType.centerY(mark, y0, BAR_H), NovaGui.alpha(NovaGui.TEXT, a));

        // Suche (Eingabe kommt direkt ueber die Tastatur)
        boolean typing = !query.isEmpty();
        NovaShapes.rect(g, sx0, c0, sx1, c1, CONTROL / 2, NovaGui.alpha(NovaGui.INSET, 0.85F * a));
        NovaShapes.outline(g, sx0, c0, sx1, c1, CONTROL / 2, NovaGui.alpha(typing ? NovaGui.alpha(NovaGui.BRAND, 0.55F)
                : NovaGui.BORDER, a));
        float iconX = sx0 + 12.0F;
        float iconY = c0 + CONTROL / 2.0F - 0.5F;
        int iconColor = NovaGui.alpha(typing ? NovaGui.TEXT_DIM : NovaGui.TEXT_MUTED, a);
        NovaShapes.ring(g, iconX, iconY, 3.4F, 1.15F, iconColor);
        NovaShapes.disc(g, iconX + 3.7F, iconY + 3.7F, 1.05F, iconColor);
        int textX = sx0 + 22;
        int textY = NovaType.centerY(NovaType.Face.BODY, c0, CONTROL);
        if (typing) {
            String count = String.valueOf(order.length);
            int countW = NovaType.width(font, NovaType.Face.MONO, count);
            String shown = query;
            int room = sx1 - textX - countW - 16;
            while (shown.length() > 1 && NovaGui.width(font, shown) > room) {
                shown = shown.substring(1);
            }
            NovaGui.text(g, font, shown, textX, textY, NovaGui.alpha(NovaGui.TEXT, a));
            if (NovaGui.blink()) {
                int caret = textX + NovaGui.width(font, shown) + 1;
                g.fill(caret, c0 + 5, caret + 1, c1 - 5, NovaGui.alpha(NovaGui.BRAND, a));
            }
            NovaType.right(g, font, NovaType.Face.MONO, count, sx1 - 10, NovaType.centerY(NovaType.Face.MONO, c0, CONTROL),
                    NovaGui.alpha(NovaGui.TEXT_MUTED, a));
        } else {
            NovaType.draw(g, font, NovaType.Face.BODY, NovaType.ellipsize(font, NovaType.Face.BODY, "Search modules", sx1 - textX - 10),
                    textX, textY, NovaGui.alpha(NovaGui.TEXT_MUTED, a));
        }

        // Zaehler: "12 active", Klick = nur aktive zeigen
        int active = NovaGui.countActive();
        if (active != lastActive) {
            lastActive = active;
            activeText = String.valueOf(active);
        }
        boolean overCount = NovaControls.hit(mouseX, mouseY, cx0, c0, cx1, c1);
        if (onlyActive) {
            NovaShapes.rect(g, cx0, c0, cx1, c1, CONTROL / 2, NovaGui.alpha(NovaGui.alpha(NovaGui.BRAND, 0.22F), a));
        } else if (overCount) {
            NovaShapes.rect(g, cx0, c0, cx1, c1, CONTROL / 2, NovaGui.alpha(NovaGui.HOVER, a));
        }
        int countW = NovaType.width(font, NovaType.Face.MONO, activeText);
        int labelW = NovaType.width(font, NovaType.Face.BODY, "active");
        int cx = (cx0 + cx1 - (countW + 4 + labelW)) / 2;
        NovaType.draw(g, font, NovaType.Face.MONO, activeText, cx, NovaType.centerY(NovaType.Face.MONO, c0, CONTROL),
                NovaGui.alpha(active > 0 ? NovaGui.TEXT : NovaGui.TEXT_DIM, a));
        NovaType.draw(g, font, NovaType.Face.BODY, "active", cx + countW + 4, NovaType.centerY(NovaType.Face.BODY, c0, CONTROL),
                NovaGui.alpha(overCount || onlyActive ? NovaGui.TEXT : NovaGui.TEXT_DIM, a));
        pointer |= overCount;

        // Werkzeuge
        for (int i = 0; i < TOOLS.length; i++) {
            boolean over = NovaControls.hit(mouseX, mouseY, toolX0[i], c0, toolX1[i], c1);
            if (over) {
                NovaShapes.rect(g, toolX0[i], c0, toolX1[i], c1, CONTROL / 2, NovaGui.alpha(NovaGui.HOVER, a));
            }
            NovaType.centered(g, font, NovaType.Face.BODY, TOOLS[i], (toolX0[i] + toolX1[i]) / 2,
                    NovaType.centerY(NovaType.Face.BODY, c0, CONTROL), NovaGui.alpha(over ? NovaGui.TEXT : NovaGui.TEXT_DIM, a));
            pointer |= over;
        }
        g.pose().popMatrix();
        return pointer;
    }

    /** Alle Kategorie-Karten (nacheinander einblendend). */
    private void drawBoard(GuiGraphicsExtractor g, float dt, float elapsed) {
        int shown = 0;
        for (int c = 0; c < categories.length; c++) {
            if (cardW[c] > 0) {
                shown++;
            }
        }
        if (shown == 0) {
            int cy = (boardTop + boardBottom) / 2;
            float a = NovaGui.introAlpha(elapsed);
            NovaType.centered(g, font, NovaType.Face.BODY, NovaGui.ellipsize(font, emptyText, width - 40), width / 2, cy - 10,
                    NovaGui.alpha(NovaGui.TEXT_DIM, a));
            NovaType.centered(g, font, NovaType.Face.CAPTION, "Esc to clear", width / 2, cy + 4, NovaGui.alpha(NovaGui.TEXT_MUTED, a));
            return;
        }
        if (wrapped) {
            g.enableScissor(0, boardTop - 2, width, boardBottom);
        }
        // Erst alle Karten-Flaechen mit ihren Sternen, dann (eigene Ebene) Text und Zeilen darueber
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shown; i++) {
                int c = cardOrder[i];
                float a = NovaGui.easeOutCubic((elapsed - 0.03F * i) / NovaGui.OPEN_TIME);
                int y = cardTop(c);
                if (a <= 0.01F || y > boardBottom || y + cardH[c] < boardTop) {
                    continue;
                }
                g.pose().pushMatrix();
                g.pose().translate(0.0F, Math.round((1.0F - a) * 8.0F));
                if (pass == 0) {
                    drawCardBack(g, c, y, a);
                } else {
                    drawCard(g, c, y, dt, a);
                }
                g.pose().popMatrix();
            }
            if (pass == 0) {
                g.nextStratum();
            }
        }
        if (wrapped) {
            g.disableScissor();
            NovaControls.scrollbar(g, width - 6, boardTop, boardBottom, scroll, maxScroll(), false);
        }
    }

    /**
     * Flaeche einer Karte mit bewegtem Hintergrund - ein kleiner Sternenhimmel: feine Sterne treiben langsam nach oben und
     * funkeln, ab und zu fliegt eine Sternschnuppe durch die Karte, und Sterne nahe der Maus leuchten heller.
     */
    private void drawCardBack(GuiGraphicsExtractor g, int c, int y, float a) {
        int x = cardX[c];
        int right = x + cardW[c];
        int bottom = y + cardH[c];
        NovaShapes.rect(g, x, y, right, bottom, NovaGui.RADIUS_LARGE, NovaGui.alpha(NovaGui.GLASS, a));
        // Innerhalb dieses Rahmens liegt alles sicher in der runden Karte (auch an den Ecken)
        int x0 = x + STAR_INSET;
        int y0 = y + STAR_INSET;
        int x1 = right - STAR_INSET;
        int y1 = bottom - STAR_INSET;
        g.enableScissor(x0, y0, x1, y1);
        drawStars(g, c, x0, y0, x1, y1, a);
        if (UiSettings.animations) {
            drawShootingStar(g, c, x0, y0, x1, y1, a);
        }
        g.disableScissor();
        NovaShapes.outline(g, x, y, right, bottom, NovaGui.RADIUS_LARGE, NovaGui.alpha(NovaGui.BORDER_STRONG, a));
    }

    /** Sterne einer Karte: feste Zufallslage je Karte, langsam nach oben treibend (oben raus = unten wieder rein). */
    private void drawStars(GuiGraphicsExtractor g, int c, int x0, int y0, int x1, int y1, float a) {
        float t = NovaGui.seconds();
        float w = x1 - x0;
        float h = y1 - y0;
        boolean mouseHere = hoveredCard == c;
        int count = Math.max(4, Math.round(h / 48.0F));
        for (int i = 0; i < count; i++) {
            float speed = 2.0F + 4.0F * hash(c, i * 7 + 3);
            float sx = x0 + w * hash(c, i * 7);
            float sy = y0 + ((h * hash(c, i * 7 + 1) - t * speed) % h + h) % h;
            float size = 0.4F + 0.35F * hash(c, i * 7 + 2);
            float twinkle = 0.5F + 0.5F * (float) Math.sin(t * (0.8F + 1.6F * hash(c, i * 7 + 4)) + 6.28F * hash(c, i * 7 + 5));
            float alpha = 0.05F + 0.17F * twinkle;
            if (mouseHere) {
                float dx = sx - lastMouseX;
                float dy = sy - lastMouseY;
                float near = 1.0F - (float) Math.sqrt(dx * dx + dy * dy) / 48.0F;
                if (near > 0.0F) {
                    alpha += 0.35F * near;
                    size += 0.25F * near;
                }
            }
            NovaShapes.disc(g, sx, sy, size, NovaGui.alpha(0xFFFFFFFF, Math.min(1.0F, alpha) * a));
        }
    }

    /**
     * Ab und zu eine Sternschnuppe: jede Karte in eigenem Takt (alle 4-7 s), schraeg von oben nach unten, mit
     * ausblendendem Schweif.
     */
    private static void drawShootingStar(GuiGraphicsExtractor g, int c, int x0, int y0, int x1, int y1, float a) {
        float period = 4.0F + 3.0F * hash(c, 101);
        float clock = NovaGui.seconds() + period * hash(c, 102);
        int cycle = (int) Math.floor(clock / period);
        float local = clock - cycle * period;
        float duration = 0.9F;
        if (local >= duration) {
            return;
        }
        float p = local / duration;
        float w = x1 - x0;
        float h = y1 - y0;
        boolean leftward = hash(c, cycle * 3 + 7) < 0.5F;
        float startX = x0 + w * (leftward ? 0.55F + 0.4F * hash(c, cycle * 3 + 8) : 0.05F + 0.4F * hash(c, cycle * 3 + 8));
        float startY = y0 + h * 0.6F * hash(c, cycle * 3 + 9);
        float dirX = leftward ? -0.8F : 0.8F;
        float dirY = 0.6F;
        float travel = Math.min(w * 0.9F, 110.0F);
        float headX = startX + dirX * travel * p;
        float headY = startY + dirY * travel * p;
        float tail = 34.0F;
        float fade = (float) Math.sin(Math.PI * p);
        // Schweif aus drei Stuecken, zum Kopf hin heller
        for (int k = 0; k < 3; k++) {
            float from = tail * (1.0F - k / 3.0F);
            float to = tail * (1.0F - (k + 1) / 3.0F);
            NovaShapes.beam(g, headX - dirX * from, headY - dirY * from, headX - dirX * to, headY - dirY * to, 1.6F,
                    NovaGui.alpha(0xFFFFFFFF, (0.1F + 0.25F * k) * fade * a));
        }
        NovaShapes.disc(g, headX, headY, 1.1F, NovaGui.alpha(0xFFFFFFFF, 0.9F * fade * a));
    }

    /** Fester Zufallswert 0..1 je (a, b) - ohne Objekte, jedes Bild gleich. */
    private static float hash(int a, int b) {
        int h = a * 374761393 + b * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Inhalt einer Kategorie: Kopf mit Name und Zaehler, darunter eine runde Zeile je Modul. */
    private void drawCard(GuiGraphicsExtractor g, int c, int y, float dt, float a) {
        int x = cardX[c];
        int right = x + cardW[c];
        int bottom = y + cardH[c];
        int[] ids = visible[c];

        // Kopf: Name in der normalen Schriftfarbe, rechts der Zaehler
        NovaType.draw(g, font, NovaType.Face.STRONG, NovaType.ellipsize(font, NovaType.Face.STRONG, NovaGui.label(categories[c]),
                cardW[c] - 2 * inset - 28), x + inset, NovaType.centerY(NovaType.Face.STRONG, y, HEAD), NovaGui.alpha(NovaGui.TEXT, a));
        int on = 0;
        for (int id : ids) {
            if (mods.get(id).active) {
                on++;
            }
        }
        if (on != lastOn[c] || countTexts[c] == null) {
            lastOn[c] = on;
            countTexts[c] = on + "/" + ids.length;
        }
        NovaType.right(g, font, NovaType.Face.MONO, countTexts[c], right - inset, NovaType.centerY(NovaType.Face.MONO, y, HEAD),
                NovaGui.alpha(on > 0 ? NovaGui.TEXT_DIM : NovaGui.TEXT_MUTED, a));

        // Zeilen (bei langen Karten mit eigenem Scrollen)
        int bodyTop = y + HEAD;
        int bodyBottom = bottom - PAD_B;
        int rowsTop = bodyTop - Math.round(cardScroll[c]);
        boolean clip = maxCardScroll(c) > 0;
        if (clip) {
            g.enableScissor(x, bodyTop, right, bodyBottom);
        }
        int first = Math.max(0, (bodyTop - rowsTop) / ROW);
        int last = Math.min(ids.length, (bodyBottom - rowsTop + ROW - 1) / ROW);
        for (int i = first; i < last; i++) {
            drawRow(g, c, ids[i], x, right, rowsTop + i * ROW, dt, a);
        }
        if (clip) {
            g.disableScissor();
            NovaControls.scrollbar(g, right - 3, bodyTop, bodyBottom, cardScroll[c], maxCardScroll(c), false);
        }
    }

    private void drawRow(GuiGraphicsExtractor g, int c, int index, int x, int right, int ry, float dt, float a) {
        Mod mod = mods.get(index);
        boolean over = index == hovered;
        boolean focused = index == focus;
        float h = NovaGui.approach(hoverAnim[index], over || focused ? 1.0F : 0.0F, NovaGui.FAST, dt);
        hoverAnim[index] = h;
        float p = NovaGui.approach(onAnim[index], mod.active ? 1.0F : 0.0F, NovaGui.SPEED, dt);
        onAnim[index] = p;

        // Runde Flaeche: an = Akzentfarbe, beim Drueberfahren etwas heller
        int x0 = x + 5;
        int x1 = right - 5;
        int y0 = ry + 1;
        int y1 = ry + ROW - 1;
        int round = (y1 - y0) / 2;
        if (p > 0.01F) {
            NovaShapes.rect(g, x0, y0, x1, y1, round, NovaGui.alpha(NovaGui.alpha(NovaGui.BRAND, 0.24F + 0.1F * h), p * a));
        }
        if (h > 0.01F) {
            NovaShapes.rect(g, x0, y0, x1, y1, round, NovaGui.alpha(NovaGui.HOVER, h * a));
        }
        if (focused || index == listening) {
            NovaShapes.outline(g, x0, y0, x1, y1, round, NovaGui.alpha(NovaGui.alpha(NovaGui.BRAND, NovaGui.blink() || focused ? 0.7F : 0.4F), a));
        }

        // Rechts: beim Drueberfahren der Pfeil zu den Einstellungen, davor die Taste (Mono)
        int textRight = right - inset;
        if (mod.hasSettings() && h > 0.01F) {
            boolean onArrow = over && hoveredSettings;
            NovaType.right(g, font, NovaType.Face.BODY, "›", textRight, NovaType.centerY(NovaType.Face.BODY, ry, ROW),
                    NovaGui.alpha(onArrow ? NovaGui.TEXT : NovaGui.TEXT_DIM, h * a));
            textRight -= SETTINGS_W - 4;
        }
        if (index == listening) {
            String text = "press a key";
            NovaType.right(g, font, NovaType.Face.MONO, text, textRight, NovaType.centerY(NovaType.Face.MONO, ry, ROW),
                    NovaGui.alpha(NovaGui.TEXT, a));
            textRight -= NovaType.width(font, NovaType.Face.MONO, text) + 6;
        } else if (mod.hasKey()) {
            String key = keyName(index, mod);
            NovaType.right(g, font, NovaType.Face.MONO, key, textRight, NovaType.centerY(NovaType.Face.MONO, ry, ROW),
                    NovaGui.alpha(NovaGui.mix(NovaGui.TEXT_MUTED, NovaGui.TEXT_DIM, Math.max(h, p)), a));
            textRight -= NovaType.width(font, NovaType.Face.MONO, key) + 6;
        }

        int nameColor = NovaGui.mix(NovaGui.mix(NovaGui.TEXT_DIM, NovaGui.TEXT, p), NovaGui.TEXT, h);
        String name = NovaGui.ellipsize(font, mod.name, textRight - (x + inset));
        NovaGui.text(g, font, name, x + inset, NovaType.centerY(NovaType.Face.BODY, ry, ROW), NovaGui.alpha(nameColor, a));
    }

    /**
     * Vorschau unten rechts (buendig mit der rechten Karte): oben die Buehne mit dem, was das Modul zeigt (dein
     * Charakter bzw. das HUD-Element), darunter Name, Zustand, was das Modul macht und wie man es bedient. Laege sie auf
     * der Zeile unter der Maus, rueckt sie direkt links neben deren Karte.
     */
    private void drawPreview(GuiGraphicsExtractor g, long now) {
        float a = previewAnim;
        Mod mod = mods.get(previewIndex);
        if (!ModPreview.has(mod)) {
            return;
        }
        int stage = preview.stageHeight(mod, height);
        int w = Math.min(width - 2 * MARGIN, preview.stageWidth(mod, previewW - 10) + 10);
        int y1 = height - MARGIN;
        int y0 = y1 - 5 - stage - PREVIEW_TEXT_H;
        int x1 = boardRight;
        int x0 = x1 - w;
        if (hovered >= 0 && x0 < cardX[hoveredCard] + cardW[hoveredCard] && x1 > cardX[hoveredCard]
                && y0 < hoveredRowY + ROW && y1 > hoveredRowY) {
            x1 = cardX[hoveredCard] - gap;
            x0 = x1 - w;
            if (x0 < MARGIN) {
                x0 = boardLeft;
                x1 = x0 + w;
            }
        }
        NovaGui.shadow(g, x0, y0, x1, y1, NovaGui.RADIUS_LARGE, a);
        NovaShapes.rect(g, x0, y0, x1, y1, NovaGui.RADIUS_LARGE, NovaGui.alpha(NovaGui.PANEL, a));
        NovaShapes.outline(g, x0, y0, x1, y1, NovaGui.RADIUS_LARGE, NovaGui.alpha(NovaGui.BORDER_STRONG, a));

        int sy1 = y0 + 5 + stage;
        preview.draw(g, font, mod, x0 + 5, y0 + 5, x1 - 5, sy1, UiSettings.animations ? (now - previewSince) / 1000.0F : 0.0F,
                a, onAnim[previewIndex]);

        // Name, rechts der Zustand
        int tx = x0 + PREVIEW_PAD;
        int tr = x1 - PREVIEW_PAD;
        int ty = sy1 + 6;
        String state = mod.active ? "On" : "Off";
        int stateW = NovaType.width(font, NovaType.Face.CAPTION, state) + 10;
        NovaShapes.rect(g, tr - stateW, ty + 1, tr, ty + 11, 5,
                NovaGui.alpha(mod.active ? NovaGui.alpha(NovaGui.BRAND, 0.32F) : NovaGui.HOVER, a));
        NovaType.centered(g, font, NovaType.Face.CAPTION, state, tr - stateW / 2, NovaType.centerY(NovaType.Face.CAPTION, ty + 1, 10),
                NovaGui.alpha(mod.active ? NovaGui.TEXT : NovaGui.TEXT_MUTED, a));
        NovaType.draw(g, font, NovaType.Face.STRONG, NovaType.ellipsize(font, NovaType.Face.STRONG, mod.name, tr - stateW - 6 - tx),
                tx, NovaType.centerY(NovaType.Face.STRONG, ty, 12), NovaGui.alpha(NovaGui.TEXT, a));

        // Beschreibung (beim Wechsel umgebrochen), unten Einstellungen und Taste
        for (int i = 0; i < previewLineCount; i++) {
            NovaType.draw(g, font, NovaType.Face.BODY, previewLines[i], tx, NovaType.centerY(NovaType.Face.BODY, ty + 15 + i * 11, 11),
                    NovaGui.alpha(NovaGui.TEXT_DIM, a));
        }
        int fy = y1 - 28;
        NovaType.draw(g, font, NovaType.Face.CAPTION, mod.hasSettings() ? "Right-click for settings" : "No settings", tx,
                NovaType.centerY(NovaType.Face.CAPTION, fy, 10), NovaGui.alpha(NovaGui.TEXT_MUTED, a));
        NovaType.draw(g, font, NovaType.Face.CAPTION, footer(previewIndex, mod), tx, NovaType.centerY(NovaType.Face.CAPTION, fy + 10, 10),
                NovaGui.alpha(NovaGui.TEXT_MUTED, a));
    }

    /** Fusszeile der Vorschau zur Taste (nur bei Aenderung neu gebaut). */
    private String footer(int index, Mod mod) {
        if (index != footerIndex || mod.keyCode != footerKey) {
            footerIndex = index;
            footerKey = mod.keyCode;
            footerText = mod.hasKey() ? "Key: " + keyName(index, mod) : "Middle-click to bind a key";
        }
        return footerText;
    }

    /** Tastenname eines Moduls, nur bei Aenderung neu geholt. */
    private String keyName(int index, Mod mod) {
        if (keyCodes[index] != mod.keyCode || keyNames[index] == null) {
            keyCodes[index] = mod.keyCode;
            keyNames[index] = mod.keyName();
        }
        return keyNames[index];
    }
}
