package nova.nova_client.client.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.update.UpdateManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Nova Social: Verbindung zum Nova-Server und alles, was dazugehoert - Freunde mit Online-Status und Server,
 * Anfragen, Direktnachrichten, Party mit Pings und Positionen.
 * <p>
 * Anmelden wie beim Betreten eines Minecraft-Servers: Zufalls-ID vom Nova-Server holen, bei Mojang anmelden
 * (joinServer - der Zugangstoken geht nur an Mojang), der Nova-Server prueft das bei Mojang (hasJoined) und gibt eine
 * Sitzung. Damit eine WebSocket-Verbindung; faellt sie weg, verbindet Nova sich mit wachsender Pause neu.
 * <p>
 * Netzwerk laeuft im Hintergrund, eingehende Nachrichten kommen in eine Warteschlange und werden im Client-Tick
 * verarbeitet: alle Daten hier gehoeren dem Render-Thread.
 */
public final class SocialClient {
    public enum State {
        OFF,
        CONNECTING,
        ONLINE,
        /** Verbindung weg oder Anmeldung fehlgeschlagen; versucht es gleich wieder ({@link #error()}). */
        RETRYING
    }

    public record Friend(UUID uuid, String name, boolean online, String server, long lastSeen) {
    }

    public record Player(UUID uuid, String name) {
    }

    public record Member(UUID uuid, String name, boolean online) {
    }

    public record Party(String id, UUID leader, List<Member> members, List<Player> invited) {
    }

    public record Invite(String party, UUID from, String name) {
    }

    public record Message(UUID from, String text, long at, boolean own) {
    }

    /** Ping oder Position eines Party-Mitglieds an einem Ort (nur auf demselben Server und in derselben Dimension). */
    public record Mark(UUID from, String name, Vec3 pos, String dim, String server, long at) {
    }

    /** Testbetrieb gegen "wrangler dev --var SOCIAL_DEV:1": anmelden ohne Mojang. */
    private static final boolean DEV = Boolean.getBoolean("nova.social.dev");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final Queue<JsonObject> INBOX = new ConcurrentLinkedQueue<>();
    private static final int MAX_CHAT = 100;
    private static final long PING_MS = 12_000L;
    private static final long POSITION_MS = 15_000L;
    private static final long POSITION_EVERY = 2_000L;
    private static final double PING_RANGE = 256.0D;

    // Verbindung (Hintergrund)
    private static volatile boolean wanted;
    private static volatile State state = State.OFF;
    private static volatile String error = "";
    private static volatile WebSocket socket;
    /** Sitzung beim Nova-Server (auch fuer Bewertungen im Nova Hub). */
    private static volatile String token;
    private static Thread thread;
    private static CompletableFuture<?> sending = CompletableFuture.completedFuture(null);

    // Zustand (Render-Thread)
    private static UUID me;
    private static String myName = "";
    private static final Map<UUID, Friend> friends = new LinkedHashMap<>();
    private static final List<Player> incoming = new ArrayList<>();
    private static final List<Player> outgoing = new ArrayList<>();
    private static final List<Invite> invites = new ArrayList<>();
    private static Party party;
    private static final Map<UUID, List<Message>> chats = new HashMap<>();
    private static final Map<UUID, Integer> unread = new HashMap<>();
    private static final List<Mark> pings = new ArrayList<>();
    private static final Map<UUID, Mark> positions = new HashMap<>();
    /** Freund, dessen Chat gerade offen ist (neue Nachrichten von ihm sind dann gleich gelesen). */
    private static UUID reading;
    private static String lastError = "";
    private static long lastErrorAt;

    // Was zuletzt als Anwesenheit / Position geschickt wurde
    private static String sentServer = "\u0000";
    private static boolean sentShow;
    private static Vec3 sentPosition;
    private static long sentPositionAt;

    private SocialClient() {
    }

    // ---------------------------------------------------------------- Ein / Aus

    public static synchronized void start() {
        wanted = true;
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(SocialClient::run, "Nova Social");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public static synchronized void stop() {
        wanted = false;
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        if (thread != null) {
            thread.interrupt();
        }
        state = State.OFF;
        reset();
    }

    public static State state() {
        return state;
    }

    public static boolean online() {
        return state == State.ONLINE;
    }

    /** Warum die Verbindung gerade nicht steht (leer, wenn alles gut ist). */
    public static String error() {
        return error;
    }

    /** Sitzung beim Nova-Server, solange man verbunden ist (sonst null). */
    public static String token() {
        return state == State.ONLINE ? token : null;
    }

    // ---------------------------------------------------------------- Hintergrund: anmelden, verbinden, halten

    private static void run() {
        int failures = 0;
        while (wanted) {
            try {
                state = State.CONNECTING;
                token = login();
                CompletableFuture<Void> closed = new CompletableFuture<>();
                WebSocket ws = HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(socketUrl(token)), new Listener(closed)).get(15, TimeUnit.SECONDS);
                socket = ws;
                failures = 0;
                // "ping" alle 30 Sekunden haelt die Verbindung offen (der Server antwortet ohne aufzuwachen)
                while (wanted && !closed.isDone()) {
                    try {
                        closed.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        sendRaw("ping");
                    }
                }
                closed.getNow(null);
                error = "Connection lost";
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                error = describe(e);
            } finally {
                socket = null;
            }
            if (!wanted) {
                break;
            }
            state = State.RETRYING;
            failures++;
            try {
                Thread.sleep(Math.min(60, 5L * failures) * 1000L);
            } catch (InterruptedException e) {
                break;
            }
        }
        state = State.OFF;
    }

    /** Sitzung holen: Zufalls-ID, bei Mojang "betreten", Nova-Server prueft das. */
    private static String login() throws Exception {
        String serverId = post("/social/challenge", new JsonObject()).get("serverId").getAsString();
        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        JsonObject body = new JsonObject();
        body.addProperty("name", user.getName());
        body.addProperty("serverId", serverId);
        if (DEV) {
            body.addProperty("dev", true);
            body.addProperty("uuid", user.getProfileId().toString());
        } else {
            mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
        }
        JsonObject login = post("/social/login", body);
        if (!login.has("ok") || !login.get("ok").getAsBoolean()) {
            throw new IOException(login.has("error") ? login.get("error").getAsString() : "Login failed");
        }
        return login.get("token").getAsString();
    }

    private static JsonObject post(String path, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(UpdateManager.SERVER + path))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String socketUrl(String token) {
        return UpdateManager.SERVER.replaceFirst("^http", "ws") + "/social/ws?token=" + token;
    }

    private static String describe(Exception e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("Authentication") || name.contains("InvalidCredentials")) {
            return "Minecraft login failed - Nova Social needs a Microsoft account";
        }
        if (e instanceof IOException && e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        return "Nova server not reachable";
    }

    private static final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();
        private final CompletableFuture<Void> closed;

        Listener(CompletableFuture<Void> closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                if (!"pong".equals(text)) {
                    try {
                        INBOX.add(JsonParser.parseString(text).getAsJsonObject());
                    } catch (RuntimeException ignored) {
                        // kaputte Nachricht: ueberspringen
                    }
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable failure) {
            closed.completeExceptionally(failure);
        }
    }

    private static synchronized void sendRaw(String text) {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        // immer erst senden, wenn das vorige fertig ist (sonst wirft der WebSocket)
        sending = sending.handle((v, e) -> null).thenCompose(v -> ws.sendText(text, true));
    }

    private static void send(String type, Object... pairs) {
        JsonObject message = new JsonObject();
        message.addProperty("t", type);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof Number number) {
                message.addProperty((String) pairs[i], number);
            } else if (value instanceof Boolean flag) {
                message.addProperty((String) pairs[i], flag);
            } else {
                message.addProperty((String) pairs[i], value == null ? null : value.toString());
            }
        }
        sendRaw(message.toString());
    }

    // ---------------------------------------------------------------- Client-Tick: Nachrichten verarbeiten

    public static void tick(Minecraft mc) {
        JsonObject message;
        while ((message = INBOX.poll()) != null) {
            try {
                handle(message);
            } catch (RuntimeException e) {
                // unerwartetes Format: diese Nachricht ueberspringen
            }
        }
        long now = Util.getMillis();
        pings.removeIf(p -> now - p.at() > PING_MS);
        positions.values().removeIf(p -> now - p.at() > POSITION_MS);
        if (state != State.ONLINE) {
            return;
        }
        presence(mc);
        sharePosition(mc, now);
    }

    private static void handle(JsonObject m) {
        String type = string(m, "t");
        switch (type) {
            case "state" -> {
                reset();
                JsonObject who = m.getAsJsonObject("me");
                me = uuid(who, "uuid");
                myName = string(who, "name");
                for (JsonElement e : m.getAsJsonArray("friends")) {
                    JsonObject f = e.getAsJsonObject();
                    friends.put(uuid(f, "uuid"), new Friend(uuid(f, "uuid"), string(f, "name"), bool(f, "online"),
                            nullable(f, "server"), f.has("lastSeen") ? f.get("lastSeen").getAsLong() : 0L));
                }
                players(m.getAsJsonArray("incoming"), incoming);
                players(m.getAsJsonArray("outgoing"), outgoing);
                for (JsonElement e : m.getAsJsonArray("invites")) {
                    JsonObject i = e.getAsJsonObject();
                    invites.add(new Invite(string(i, "party"), uuid(i, "from"), string(i, "name")));
                }
                party = party(m.get("party"));
                JsonArray missed = m.getAsJsonArray("unread");
                for (JsonElement e : missed) {
                    JsonObject d = e.getAsJsonObject();
                    receive(uuid(d, "from"), string(d, "text"), d.get("at").getAsLong(), false);
                }
                if (!missed.isEmpty()) {
                    SocialHud.toast("unread", missed.size() + (missed.size() == 1 ? " new message" : " new messages"),
                            "DM", "Open Nova Social to read");
                }
                // Anwesenheit neu schicken (neue Verbindung)
                sentServer = "\u0000";
                state = State.ONLINE;
                error = "";
            }
            case "friend.online" -> {
                UUID id = uuid(m, "uuid");
                Friend old = friends.get(id);
                if (old != null) {
                    friends.put(id, new Friend(id, old.name(), true, nullable(m, "server"), old.lastSeen()));
                    if (!old.online() && SocialSettings.notifyOnline) {
                        SocialHud.toast("online:" + id, old.name(), "online", "is now online");
                    }
                }
            }
            case "friend.offline" -> {
                UUID id = uuid(m, "uuid");
                Friend old = friends.get(id);
                if (old != null) {
                    friends.put(id, new Friend(id, old.name(), false, null, System.currentTimeMillis()));
                }
            }
            case "friend.request" -> {
                Player p = new Player(uuid(m, "uuid"), string(m, "name"));
                incoming.removeIf(x -> x.uuid().equals(p.uuid()));
                incoming.add(p);
                SocialHud.toast("request:" + p.uuid(), p.name(), "request", "wants to be friends");
                NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 1.6F);
            }
            case "friend.sent" -> {
                Player p = new Player(uuid(m, "uuid"), string(m, "name"));
                outgoing.removeIf(x -> x.uuid().equals(p.uuid()));
                outgoing.add(p);
            }
            case "friend.added" -> {
                UUID id = uuid(m, "uuid");
                boolean asked = outgoing.removeIf(x -> x.uuid().equals(id));
                incoming.removeIf(x -> x.uuid().equals(id));
                friends.put(id, new Friend(id, string(m, "name"), bool(m, "online"), nullable(m, "server"), 0L));
                if (asked) {
                    SocialHud.toast("added:" + id, string(m, "name"), "friend", "accepted your request");
                }
            }
            case "friend.removed" -> {
                UUID id = uuid(m, "uuid");
                friends.remove(id);
                incoming.removeIf(x -> x.uuid().equals(id));
                outgoing.removeIf(x -> x.uuid().equals(id));
                unread.remove(id);
            }
            case "dm" -> {
                UUID from = uuid(m, "from");
                boolean own = from.equals(me);
                UUID partner = own ? uuid(m, "to") : from;
                receive(partner, string(m, "text"), m.get("at").getAsLong(), own);
                if (!own && !partner.equals(reading)) {
                    SocialHud.toast("dm:" + partner, string(m, "name"), "DM", string(m, "text"));
                    NovaNotify.sound(SoundEvents.NOTE_BLOCK_BELL, 1.4F);
                }
            }
            case "history" -> {
                UUID with = uuid(m, "with");
                List<Message> list = new ArrayList<>();
                for (JsonElement e : m.getAsJsonArray("messages")) {
                    JsonObject d = e.getAsJsonObject();
                    UUID from = uuid(d, "from");
                    list.add(new Message(from, string(d, "text"), d.get("at").getAsLong(), from.equals(me)));
                }
                chats.put(with, list);
            }
            case "party" -> {
                party = party(m.get("party"));
                if (party == null) {
                    positions.clear();
                }
            }
            case "party.invite" -> {
                Invite invite = new Invite(string(m, "party"), uuid(m, "from"), string(m, "name"));
                invites.removeIf(i -> i.party().equals(invite.party()));
                invites.add(invite);
                SocialHud.toast("party:" + invite.party(), invite.name(), "party", "invited you to a party");
                NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 1.2F);
            }
            case "ping" -> {
                Mark ping = mark(m);
                pings.add(ping);
                if (sameWorld(ping)) {
                    NovaNotify.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.8F);
                }
            }
            case "pos" -> {
                Mark position = mark(m);
                positions.put(position.from(), position);
            }
            case "error" -> {
                lastError = string(m, "text");
                lastErrorAt = Util.getMillis();
                SocialHud.toast("error", "Nova Social", "info", lastError);
            }
            default -> {
            }
        }
    }

    private static void receive(UUID partner, String text, long at, boolean own) {
        List<Message> list = chats.computeIfAbsent(partner, k -> new ArrayList<>());
        list.add(new Message(own ? me : partner, text, at, own));
        while (list.size() > MAX_CHAT) {
            list.remove(0);
        }
        if (!own && !partner.equals(reading)) {
            unread.merge(partner, 1, Integer::sum);
        }
    }

    private static void reset() {
        me = null;
        friends.clear();
        incoming.clear();
        outgoing.clear();
        invites.clear();
        party = null;
        chats.clear();
        unread.clear();
        pings.clear();
        positions.clear();
    }

    // ---------------------------------------------------------------- Anwesenheit und Position

    /** Auf welchem Server man gerade spielt (nur echte Server, nicht Einzelspieler) - geschickt, wenn er sich aendert. */
    private static void presence(Minecraft mc) {
        String server = mc.getCurrentServer() != null && mc.level != null ? mc.getCurrentServer().ip.toLowerCase(Locale.ROOT) : "";
        boolean show = SocialSettings.showServer;
        if (server.equals(sentServer) && show == sentShow) {
            return;
        }
        sentServer = server;
        sentShow = show;
        send("presence", "server", server.isEmpty() ? null : server, "show", show);
    }

    /** In einer Party: eigene Position fuer die Marker der anderen (hoechstens alle 2 Sekunden, nur nach Bewegung). */
    private static void sharePosition(Minecraft mc, long now) {
        if (party == null || party.members().size() < 2 || mc.player == null || mc.level == null
                || now - sentPositionAt < POSITION_EVERY) {
            return;
        }
        Vec3 pos = mc.player.position();
        if (sentPosition != null && pos.distanceToSqr(sentPosition) < 2.25D && now - sentPositionAt < 10_000L) {
            return;
        }
        sentPosition = pos;
        sentPositionAt = now;
        send("pos", "x", round(pos.x), "y", round(pos.y), "z", round(pos.z), "dim", dimension(mc), "server", currentServer(mc));
    }

    // ---------------------------------------------------------------- Aktionen (aus Menue und Tasten)

    public static void addFriend(String name) {
        send("friend.add", "name", name.trim());
    }

    public static void accept(UUID uuid) {
        send("friend.accept", "uuid", uuid);
    }

    public static void decline(UUID uuid) {
        send("friend.decline", "uuid", uuid);
    }

    public static void cancel(UUID uuid) {
        send("friend.cancel", "uuid", uuid);
    }

    public static void removeFriend(UUID uuid) {
        send("friend.remove", "uuid", uuid);
    }

    public static void message(UUID to, String text) {
        if (!text.isBlank()) {
            send("dm", "to", to, "text", text.trim());
        }
    }

    /** Chat mit diesem Freund geoeffnet: Verlauf laden, Nachrichten gelten als gelesen. */
    public static void openChat(UUID friend) {
        reading = friend;
        if (friend != null) {
            unread.remove(friend);
            send("history", "with", friend);
        }
    }

    public static void invite(UUID friend) {
        send("party.invite", "uuid", friend);
    }

    public static void joinParty(String id) {
        invites.removeIf(i -> i.party().equals(id));
        send("party.join", "party", id);
    }

    public static void declineParty(String id) {
        invites.removeIf(i -> i.party().equals(id));
        send("party.decline", "party", id);
    }

    public static void leaveParty() {
        send("party.leave");
    }

    public static void kick(UUID member) {
        send("party.kick", "uuid", member);
    }

    /** Ping-Taste: Ort unter dem Fadenkreuz (bis 256 Bloecke) fuer die Party markieren. */
    public static void ping(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 from = camera.position();
        Vec3 look = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        Vec3 to = from.add(look.scale(PING_RANGE));
        Vec3 hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player)).getLocation();
        if (!online() || party == null) {
            SocialHud.toast("ping", "Party ping", "info", online() ? "Invite friends to a party to ping" : "Turn on Nova Social");
            return;
        }
        pings.add(new Mark(me, myName, hit, dimension(mc), currentServer(mc), Util.getMillis()));
        NovaNotify.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.8F);
        send("ping", "x", round(hit.x), "y", round(hit.y), "z", round(hit.z), "dim", dimension(mc), "server", currentServer(mc));
    }

    /** Zum Server eines Freundes wechseln. */
    public static void join(Friend friend) {
        if (friend.server() == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.disconnectWithProgressScreen();
        }
        ServerData data = new ServerData(friend.name() + "'s server", friend.server(), ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, ServerAddress.parseString(friend.server()),
                data, false, null);
    }

    // ---------------------------------------------------------------- Lesen (Menue, HUD)

    public static UUID me() {
        return me;
    }

    public static String myName() {
        return myName;
    }

    public static Collection<Friend> friends() {
        return friends.values();
    }

    public static Friend friend(UUID uuid) {
        return friends.get(uuid);
    }

    public static List<Player> incoming() {
        return incoming;
    }

    public static List<Player> outgoing() {
        return outgoing;
    }

    public static List<Invite> invites() {
        return invites;
    }

    public static Party party() {
        return party;
    }

    public static List<Message> chat(UUID friend) {
        return chats.getOrDefault(friend, List.of());
    }

    public static int unread(UUID friend) {
        return unread.getOrDefault(friend, 0);
    }

    public static int unreadTotal() {
        int total = 0;
        for (int n : unread.values()) {
            total += n;
        }
        return total;
    }

    public static List<Mark> pings() {
        return pings;
    }

    public static Collection<Mark> positions() {
        return positions.values();
    }

    /** Letzte Fehlermeldung des Servers (z.B. "hat Nova Social noch nicht benutzt"), solange sie frisch ist. */
    public static String recentError() {
        return Util.getMillis() - lastErrorAt < 6000L ? lastError : "";
    }

    /** Liegt die Markierung in der Welt, in der man gerade ist (gleicher Server und gleiche Dimension)? */
    public static boolean sameWorld(Mark mark) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mark.server().equals(currentServer(mc)) && mark.dim().equals(dimension(mc));
    }

    // ---------------------------------------------------------------- Helfer

    private static String currentServer(Minecraft mc) {
        if (mc.getCurrentServer() != null) {
            return mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        }
        return mc.getSingleplayerServer() != null ? "sp" : "";
    }

    private static String dimension(Minecraft mc) {
        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    private static double round(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    private static Mark mark(JsonObject m) {
        return new Mark(uuid(m, "from"), string(m, "name"), new Vec3(m.get("x").getAsDouble(), m.get("y").getAsDouble(),
                m.get("z").getAsDouble()), string(m, "dim"), string(m, "server"), Util.getMillis());
    }

    private static Party party(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject p = element.getAsJsonObject();
        List<Member> members = new ArrayList<>();
        for (JsonElement e : p.getAsJsonArray("members")) {
            JsonObject member = e.getAsJsonObject();
            members.add(new Member(uuid(member, "uuid"), string(member, "name"), bool(member, "online")));
        }
        List<Player> invited = new ArrayList<>();
        players(p.getAsJsonArray("invited"), invited);
        return new Party(string(p, "id"), uuid(p, "leader"), members, invited);
    }

    private static void players(JsonArray array, List<Player> into) {
        into.clear();
        if (array == null) {
            return;
        }
        for (JsonElement e : array) {
            JsonObject p = e.getAsJsonObject();
            into.add(new Player(uuid(p, "uuid"), string(p, "name")));
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String nullable(JsonObject o, String key) {
        String value = string(o, key);
        return value.isEmpty() ? null : value;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    private static UUID uuid(JsonObject o, String key) {
        return UUID.fromString(string(o, key));
    }
}
