package nova.nova_client.client.social;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.ModConfig;
import nova.nova_client.client.NovaControls;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaSettingsScreen;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.NovaType;
import nova.nova_client.client.mods.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Fenster von Nova Social: links Freund hinzufuegen, Anfragen, Party-Einladungen und die Freundesliste (online zuerst,
 * mit Server und ungelesenen Nachrichten), rechts der Chat mit dem gewaehlten Freund (Beitreten, zur Party einladen,
 * entfernen) oder die eigene Party. Ist das Modul aus, gibt es nur den Knopf zum Einschalten.
 */
public class SocialScreen extends NovaSettingsScreen {
    private static final int LEFT_W = 196;
    private static final int GAP = 12;
    private static final int FRIEND_ROW = 28;
    private static final int ROW = 22;
    private static final int BUBBLE_MAX_LINES = 8;

    /** Klickbare Flaeche des aktuellen Bildes (jedes Bild neu). */
    private record Hit(int x0, int y0, int x1, int y1, Runnable action) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private EditBox addBox;
    private EditBox chatBox;
    /** Gewaehlter Freund (Chat) oder null; showParty = rechts die Party. */
    private static UUID selected;
    private static boolean showParty;
    private float listScroll;
    private float listScrollTarget;
    private int listMax;
    private float chatScroll;
    private int chatMax;
    private String status = "";

    public SocialScreen(Screen parent, Mod module) {
        super(parent, "Nova Social");
        setModule(module);
    }

    @Override
    protected void init() {
        setPanel(560, 300);
        int leftX1 = cx0 + LEFT_W;
        addBox = box(cx0 + 6, cy0 + 5, LEFT_W - 70, 16, "Add friend");
        chatBox = box(leftX1 + GAP + 6, cy1 - 13, cx1 - leftX1 - GAP - 76, 200, "Message");
        SocialClient.openChat(selected);
    }

    private EditBox box(int x, int y, int width, int max, String label) {
        EditBox box = new EditBox(font, x, y, width, 10, Component.literal(label));
        box.setBordered(false);
        box.setMaxLength(max);
        box.setTextColor(NovaGui.TEXT);
        box.setTextShadow(false);
        NovaGui.themeFont(box);
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void removed() {
        SocialClient.openChat(null);
        super.removed();
    }

    @Override
    protected String subtitle() {
        if (module != null && !module.active) {
            return "Off  ·  turn it on to see your friends";
        }
        return switch (SocialClient.state()) {
            case ONLINE -> "Online as " + SocialClient.myName() + "  ·  " + onlineFriends() + " of "
                    + SocialClient.friends().size() + " friends online";
            case CONNECTING -> "Connecting...";
            case RETRYING -> SocialClient.error() + "  ·  retrying";
            case OFF -> "Offline";
        };
    }

    private long onlineFriends() {
        return SocialClient.friends().stream().filter(SocialClient.Friend::online).count();
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    protected boolean drawContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt) {
        hits.clear();
        boolean on = module == null || module.active;
        addBox.visible = on && SocialClient.online();
        chatBox.visible = on && SocialClient.online() && selected != null && SocialClient.friend(selected) != null;
        if (!on) {
            return offPanel(g, mouseX, mouseY);
        }
        if (!SocialClient.online()) {
            String text = SocialClient.state() == SocialClient.State.CONNECTING ? "Connecting to Nova..." : SocialClient.error();
            NovaType.centered(g, font, NovaType.Face.BODY, text, (cx0 + cx1) / 2, (cy0 + cy1) / 2 - 4, NovaGui.TEXT_DIM);
            return false;
        }
        String error = SocialClient.recentError();
        status = error.isEmpty() ? status : error;
        boolean pointer = drawLeft(g, mouseX, mouseY);
        int rx0 = cx0 + LEFT_W + GAP;
        g.fill(rx0 - GAP / 2, cy0, rx0 - GAP / 2 + 1, cy1, NovaGui.BORDER);
        SocialClient.Friend friend = selected == null ? null : SocialClient.friend(selected);
        if (friend != null) {
            pointer |= drawChat(g, friend, rx0, mouseX, mouseY);
        } else if (showParty || SocialClient.party() != null) {
            pointer |= drawParty(g, rx0, mouseX, mouseY);
        } else {
            drawWelcome(g, rx0);
        }
        return pointer;
    }

    private boolean offPanel(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int cx = (cx0 + cx1) / 2;
        int y = (cy0 + cy1) / 2 - 40;
        NovaType.centered(g, font, NovaType.Face.HEADING, "Your friends, in every game", cx, y, NovaGui.TEXT);
        NovaType.centered(g, font, NovaType.Face.BODY, "See who is online and where, chat, and play together in a party.",
                cx, y + 20, NovaGui.TEXT_DIM);
        NovaType.centered(g, font, NovaType.Face.CAPTION, "Signs in with your Minecraft account - your password never leaves Minecraft.",
                cx, y + 34, NovaGui.TEXT_MUTED);
        return button(g, cx - 50, y + 54, cx + 50, y + 72, "Turn on", NovaControls.Variant.PRIMARY, mouseX, mouseY, () -> {
            module.toggle(Minecraft.getInstance());
            ModConfig.save();
        });
    }

    // ---------------------------------------------------------------- Links: hinzufuegen, Anfragen, Freunde

    private boolean drawLeft(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x0 = cx0;
        int x1 = cx0 + LEFT_W;
        drawField(g, addBox, "Add friend by name");
        boolean pointer = button(g, x1 - 52, cy0, x1, cy0 + 18, "Add", NovaControls.Variant.PRIMARY, mouseX, mouseY, this::addFriend);
        int top = cy0 + 26;
        if (!status.isEmpty()) {
            NovaType.draw(g, font, NovaType.Face.CAPTION, NovaType.ellipsize(font, NovaType.Face.CAPTION, status, LEFT_W),
                    x0, NovaType.centerY(NovaType.Face.CAPTION, top, 10), NovaGui.TEXT_MUTED);
            top += 14;
        }
        listScroll = NovaGui.approach(listScroll, listScrollTarget, NovaGui.SPEED, 0.016F);
        g.enableScissor(x0, top, x1, cy1);
        int y = top - Math.round(listScroll);
        int start = y;

        List<SocialClient.Player> incoming = SocialClient.incoming();
        List<SocialClient.Player> outgoing = SocialClient.outgoing();
        if (!incoming.isEmpty() || !outgoing.isEmpty()) {
            y = section(g, "Requests", x0, y, x1);
            for (SocialClient.Player p : incoming) {
                pointer |= rowHover(g, x0, y, x1, ROW, mouseX, mouseY, top);
                text(g, NovaType.Face.STRONG, p.name(), x0 + 8, y, ROW, NovaGui.TEXT);
                pointer |= button(g, x1 - 58, y + 3, x1 - 30, y + ROW - 3, "Yes", NovaControls.Variant.PRIMARY, mouseX, mouseY,
                        () -> SocialClient.accept(p.uuid()), top);
                pointer |= button(g, x1 - 27, y + 3, x1 - 2, y + ROW - 3, "×", NovaControls.Variant.GHOST, mouseX, mouseY,
                        () -> SocialClient.decline(p.uuid()), top);
                y += ROW;
            }
            for (SocialClient.Player p : outgoing) {
                text(g, NovaType.Face.BODY, p.name(), x0 + 8, y, ROW, NovaGui.TEXT_DIM);
                text(g, NovaType.Face.CAPTION, "sent", x1 - 58, y, ROW, NovaGui.TEXT_MUTED);
                pointer |= button(g, x1 - 27, y + 3, x1 - 2, y + ROW - 3, "×", NovaControls.Variant.GHOST, mouseX, mouseY,
                        () -> SocialClient.cancel(p.uuid()), top);
                y += ROW;
            }
            y += 6;
        }

        List<SocialClient.Invite> invites = SocialClient.invites();
        if (!invites.isEmpty()) {
            y = section(g, "Party invites", x0, y, x1);
            for (SocialClient.Invite invite : invites) {
                text(g, NovaType.Face.STRONG, invite.name() + "'s party", x0 + 8, y, ROW, NovaGui.TEXT);
                pointer |= button(g, x1 - 64, y + 3, x1 - 30, y + ROW - 3, "Join", NovaControls.Variant.PRIMARY, mouseX, mouseY,
                        () -> {
                            SocialClient.joinParty(invite.party());
                            selected = null;
                            showParty = true;
                        }, top);
                pointer |= button(g, x1 - 27, y + 3, x1 - 2, y + ROW - 3, "×", NovaControls.Variant.GHOST, mouseX, mouseY,
                        () -> SocialClient.declineParty(invite.party()), top);
                y += ROW;
            }
            y += 6;
        }

        SocialClient.Party party = SocialClient.party();
        if (party != null) {
            boolean on = selected == null;
            pointer |= rowSelect(g, x0, y, x1, ROW, on, mouseX, mouseY, top, () -> {
                selected = null;
                showParty = true;
                SocialClient.openChat(null);
            });
            NovaShapes.disc(g, x0 + 11, y + ROW / 2.0F, 3.0F, NovaGui.BRAND);
            text(g, NovaType.Face.STRONG, "Party", x0 + 20, y, ROW, NovaGui.TEXT);
            text(g, NovaType.Face.CAPTION, party.members().size() + " members", x1 - 6 - NovaType.width(font,
                    NovaType.Face.CAPTION, party.members().size() + " members"), y, ROW, NovaGui.TEXT_MUTED);
            y += ROW + 6;
        }

        y = section(g, "Friends", x0, y, x1);
        List<SocialClient.Friend> friends = new ArrayList<>(SocialClient.friends());
        friends.sort(Comparator.comparing((SocialClient.Friend f) -> !f.online()).thenComparing(f -> f.name().toLowerCase(Locale.ROOT)));
        if (friends.isEmpty()) {
            text(g, NovaType.Face.CAPTION, "No friends yet - add someone who uses Nova", x0 + 8, y, ROW, NovaGui.TEXT_MUTED);
            y += ROW;
        }
        for (SocialClient.Friend f : friends) {
            boolean on = f.uuid().equals(selected);
            pointer |= rowSelect(g, x0, y, x1, FRIEND_ROW, on, mouseX, mouseY, top, () -> select(f.uuid()));
            NovaShapes.disc(g, x0 + 11, y + 10.5F, 3.0F, f.online() ? NovaGui.SUCCESS : 0xFF4A4F59);
            NovaType.draw(g, font, NovaType.Face.STRONG, f.name(), x0 + 20, NovaType.centerY(NovaType.Face.STRONG, y + 4, 12),
                    f.online() ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            String where = f.online() ? (f.server() != null ? f.server() : "Online") : "Offline";
            NovaType.draw(g, font, NovaType.Face.CAPTION, NovaType.ellipsize(font, NovaType.Face.CAPTION, where, LEFT_W - 50),
                    x0 + 20, NovaType.centerY(NovaType.Face.CAPTION, y + 15, 10), NovaGui.TEXT_MUTED);
            int count = SocialClient.unread(f.uuid());
            if (count > 0) {
                String n = String.valueOf(count);
                int w = Math.max(14, NovaType.width(font, NovaType.Face.CAPTION, n) + 8);
                NovaShapes.rect(g, x1 - 6 - w, y + 7, x1 - 6, y + 21, 7, NovaGui.BRAND);
                NovaType.centered(g, font, NovaType.Face.CAPTION, n, x1 - 6 - w / 2, NovaType.centerY(NovaType.Face.CAPTION, y + 7, 14),
                        NovaGui.ON_ACCENT);
            }
            y += FRIEND_ROW;
        }
        g.disableScissor();
        listMax = Math.max(0, (y - start) - (cy1 - top));
        listScrollTarget = Math.min(listScrollTarget, listMax);
        return pointer;
    }

    private int section(GuiGraphicsExtractor g, String title, int x0, int y, int x1) {
        NovaControls.section(g, font, x0, y, x1, title);
        return y + NovaControls.SECTION_H;
    }

    private void select(UUID friend) {
        selected = friend;
        showParty = false;
        chatScroll = 0.0F;
        SocialClient.openChat(friend);
    }

    private void addFriend() {
        // Minecraft-Namen: nur Buchstaben, Ziffern und _
        String name = addBox.getValue().replaceAll("[^A-Za-z0-9_]", "");
        if (!name.isEmpty()) {
            SocialClient.addFriend(name);
            status = "Request sent to " + name;
            addBox.setValue("");
        }
    }

    // ---------------------------------------------------------------- Rechts: Chat

    private boolean drawChat(GuiGraphicsExtractor g, SocialClient.Friend friend, int x0, int mouseX, int mouseY) {
        int x1 = cx1;
        boolean pointer = false;
        NovaShapes.disc(g, x0 + 5, cy0 + 8.5F, 3.5F, friend.online() ? NovaGui.SUCCESS : 0xFF4A4F59);
        NovaType.draw(g, font, NovaType.Face.HEADING, friend.name(), x0 + 14, NovaType.centerY(NovaType.Face.HEADING, cy0, 18), NovaGui.TEXT);
        String where = friend.online() ? (friend.server() != null ? "Playing on " + friend.server() : "Online") : "Offline";
        NovaType.draw(g, font, NovaType.Face.CAPTION, where, x0 + 14, NovaType.centerY(NovaType.Face.CAPTION, cy0 + 18, 10), NovaGui.TEXT_MUTED);

        // Knoepfe rechts: Beitreten (wenn er auf einem anderen Server spielt), Party, Entfernen
        int bx = x1;
        pointer |= button(g, bx - 22, cy0 + 2, bx, cy0 + 20, "×", NovaControls.Variant.GHOST, mouseX, mouseY, () -> {
            SocialClient.removeFriend(friend.uuid());
            selected = null;
        });
        bx -= 26;
        if (friend.online()) {
            pointer |= button(g, bx - 54, cy0 + 2, bx, cy0 + 20, "Party", NovaControls.Variant.SECONDARY, mouseX, mouseY,
                    () -> SocialClient.invite(friend.uuid()));
            bx -= 58;
        }
        String current = Minecraft.getInstance().getCurrentServer() == null ? ""
                : Minecraft.getInstance().getCurrentServer().ip.toLowerCase(Locale.ROOT);
        if (friend.server() != null && !friend.server().equals(current)) {
            pointer |= button(g, bx - 48, cy0 + 2, bx, cy0 + 20, "Join", NovaControls.Variant.PRIMARY, mouseX, mouseY,
                    () -> SocialClient.join(friend));
        }

        // Verlauf: unten die neuesten, Mausrad scrollt nach oben
        int top = cy0 + 34;
        int bottom = cy1 - 26;
        g.fill(x0, top - 6, x1, top - 5, NovaGui.BORDER);
        List<SocialClient.Message> messages = SocialClient.chat(friend.uuid());
        int maxWidth = (x1 - x0) * 3 / 4;
        g.enableScissor(x0, top, x1, bottom);
        int y = bottom + Math.round(chatScroll);
        String[] lines = new String[BUBBLE_MAX_LINES];
        for (int i = messages.size() - 1; i >= 0 && y > top - 400; i--) {
            SocialClient.Message message = messages.get(i);
            int count = NovaType.wrap(font, NovaType.Face.BODY, message.text(), maxWidth - 16, lines);
            int width = 0;
            for (int l = 0; l < count; l++) {
                width = Math.max(width, NovaType.width(font, NovaType.Face.BODY, lines[l]));
            }
            int h = count * 11 + 8;
            y -= h;
            int bx0 = message.own() ? x1 - width - 16 : x0;
            int bx1 = bx0 + width + 16;
            NovaShapes.rect(g, bx0, y, bx1, y + h, 8, message.own() ? NovaGui.alpha(NovaGui.BRAND, 0.85F) : NovaGui.CARD);
            for (int l = 0; l < count; l++) {
                NovaType.draw(g, font, NovaType.Face.BODY, lines[l], bx0 + 8, NovaType.centerY(NovaType.Face.BODY, y + 4 + l * 11, 11),
                        message.own() ? NovaGui.ON_ACCENT : NovaGui.TEXT);
            }
            y -= 4;
        }
        g.disableScissor();
        chatMax = Math.max(0, (bottom + Math.round(chatScroll)) - y - (bottom - top));
        if (messages.isEmpty()) {
            NovaType.centered(g, font, NovaType.Face.CAPTION, "Say hi to " + friend.name(), (x0 + x1) / 2, (top + bottom) / 2,
                    NovaGui.TEXT_MUTED);
        }
        drawField(g, chatBox, "Message " + friend.name());
        pointer |= button(g, x1 - 60, cy1 - 18, x1, cy1, "Send", NovaControls.Variant.PRIMARY, mouseX, mouseY, this::sendMessage);
        return pointer;
    }

    private void sendMessage() {
        if (selected != null && !chatBox.getValue().isBlank()) {
            SocialClient.message(selected, chatBox.getValue());
            chatBox.setValue("");
            chatScroll = 0.0F;
        }
    }

    // ---------------------------------------------------------------- Rechts: Party

    private boolean drawParty(GuiGraphicsExtractor g, int x0, int mouseX, int mouseY) {
        int x1 = cx1;
        SocialClient.Party party = SocialClient.party();
        boolean pointer = false;
        NovaType.draw(g, font, NovaType.Face.HEADING, "Party", x0, NovaType.centerY(NovaType.Face.HEADING, cy0, 18), NovaGui.TEXT);
        NovaType.draw(g, font, NovaType.Face.CAPTION, "Press " + SocialKeys.pingKeyName() + " to ping where you look - your party sees it",
                x0, NovaType.centerY(NovaType.Face.CAPTION, cy0 + 18, 10), NovaGui.TEXT_MUTED);
        if (party == null) {
            NovaType.centered(g, font, NovaType.Face.BODY, "Pick a friend and press Party to start one", (x0 + x1) / 2,
                    (cy0 + cy1) / 2, NovaGui.TEXT_DIM);
            return false;
        }
        boolean leader = party.leader().equals(SocialClient.me());
        int y = cy0 + 34;
        g.fill(x0, y - 6, x1, y - 5, NovaGui.BORDER);
        for (SocialClient.Member member : party.members()) {
            NovaShapes.disc(g, x0 + 6, y + ROW / 2.0F, 3.0F, member.online() ? NovaGui.SUCCESS : 0xFF4A4F59);
            text(g, NovaType.Face.STRONG, member.name(), x0 + 16, y, ROW, NovaGui.TEXT);
            int nameEnd = x0 + 16 + NovaType.width(font, NovaType.Face.STRONG, member.name());
            if (member.uuid().equals(party.leader())) {
                text(g, NovaType.Face.CAPTION, "leader", nameEnd + 8, y, ROW, NovaGui.BRAND);
            }
            if (leader && !member.uuid().equals(SocialClient.me())) {
                pointer |= button(g, x1 - 46, y + 2, x1, y + ROW - 2, "Kick", NovaControls.Variant.GHOST, mouseX, mouseY,
                        () -> SocialClient.kick(member.uuid()));
            }
            y += ROW;
        }
        for (SocialClient.Player invited : party.invited()) {
            text(g, NovaType.Face.BODY, invited.name(), x0 + 16, y, ROW, NovaGui.TEXT_MUTED);
            text(g, NovaType.Face.CAPTION, "invited", x0 + 22 + NovaType.width(font, NovaType.Face.BODY, invited.name()), y, ROW,
                    NovaGui.TEXT_MUTED);
            y += ROW;
        }
        pointer |= button(g, x1 - 90, cy1 - 18, x1, cy1, "Leave party", NovaControls.Variant.DANGER, mouseX, mouseY, () -> {
            SocialClient.leaveParty();
            showParty = false;
        });
        return pointer;
    }

    private void drawWelcome(GuiGraphicsExtractor g, int x0) {
        int cx = (x0 + cx1) / 2;
        int y = (cy0 + cy1) / 2 - 30;
        NovaType.centered(g, font, NovaType.Face.HEADING, "Hi " + SocialClient.myName(), cx, y, NovaGui.TEXT);
        NovaType.centered(g, font, NovaType.Face.BODY, "Pick a friend to chat, join their server", cx, y + 20, NovaGui.TEXT_DIM);
        NovaType.centered(g, font, NovaType.Face.BODY, "or start a party.", cx, y + 32, NovaGui.TEXT_DIM);
        NovaType.centered(g, font, NovaType.Face.CAPTION, "Friends need Nova with Nova Social turned on.", cx, y + 52,
                NovaGui.TEXT_MUTED);
    }

    // ---------------------------------------------------------------- Bausteine

    private void text(GuiGraphicsExtractor g, NovaType.Face face, String text, int x, int y, int height, int color) {
        NovaType.draw(g, font, face, text, x, NovaType.centerY(face, y, height), color);
    }

    private boolean rowHover(GuiGraphicsExtractor g, int x0, int y, int x1, int height, int mouseX, int mouseY, int top) {
        boolean hover = mouseY >= top && NovaControls.hit(mouseX, mouseY, x0, y, x1, y + height);
        if (hover) {
            NovaShapes.rect(g, x0, y, x1, y + height, NovaGui.RADIUS_SMALL, NovaGui.HOVER);
        }
        return false;
    }

    private boolean rowSelect(GuiGraphicsExtractor g, int x0, int y, int x1, int height, boolean selectedRow, int mouseX, int mouseY,
                              int top, Runnable action) {
        boolean hover = mouseY >= top && mouseY < cy1 && NovaControls.hit(mouseX, mouseY, x0, y, x1, y + height);
        if (selectedRow || hover) {
            NovaShapes.rect(g, x0, y, x1, y + height, NovaGui.RADIUS_SMALL,
                    selectedRow ? NovaGui.alpha(NovaGui.BRAND, 0.16F) : NovaGui.HOVER);
        }
        // nur der sichtbare Teil der Zeile ist klickbar
        if (y + height > top && y < cy1) {
            hits.add(new Hit(x0, Math.max(y, top), x1, Math.min(y + height, cy1), action));
        }
        return hover;
    }

    private boolean button(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, String label, NovaControls.Variant variant,
                           int mouseX, int mouseY, Runnable action) {
        return button(g, x0, y0, x1, y1, label, variant, mouseX, mouseY, action, Integer.MIN_VALUE);
    }

    /** Knopf; top = Oberkante einer Liste (darueber ist er weggescrollt und nicht klickbar). */
    private boolean button(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, String label, NovaControls.Variant variant,
                           int mouseX, int mouseY, Runnable action, int top) {
        boolean visible = y1 > top && y0 < cy1;
        boolean hover = visible && mouseY >= top && mouseY < cy1 && NovaControls.hit(mouseX, mouseY, x0, y0, x1, y1);
        NovaControls.button(g, font, x0, y0, x1, y1, label, variant, hover, true);
        if (visible) {
            // Knoepfe vor Zeilen: zuletzt eingetragen, zuerst geprueft
            hits.add(new Hit(x0, Math.max(y0, top), x1, Math.min(y1, cy1), action));
        }
        return hover;
    }

    // ---------------------------------------------------------------- Eingabe

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
        if (mouseX < cx0 + LEFT_W) {
            listScrollTarget = Math.max(0.0F, Math.min(listMax, listScrollTarget - (float) scrollY * 24.0F));
        } else {
            chatScroll = Math.max(0.0F, Math.min(chatMax, chatScroll + (float) scrollY * 24.0F));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            if (chatBox.isFocused()) {
                sendMessage();
                return true;
            }
            if (addBox.isFocused()) {
                addFriend();
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
