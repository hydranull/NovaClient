package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import nova.nova_client.client.AdminListMenu;
import nova.nova_client.client.HudStyle;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.Rect;
import nova.nova_client.client.VanishAlert;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Adminlist: zeigt in einem verschiebbaren Kasten, welche Teammitglieder gerade online sind - und wie. Die
 * Teammitglieder stehen fest in {@link #STAFF_NAMES}, es muessen keine Raenge eingetragen oder erkannt werden.
 * <ul>
 *     <li><b>Zustand</b>: online, im Zuschauer-Modus oder versteckt (in der Spielerliste des Servers, aber nicht in der
 *     Tab-Liste - oder aus dem Tab verschwunden, waehrend sein Koerper noch zu sehen ist).</li>
 *     <li><b>Chat</b>: Join, Leave und Zustands-Wechsel als kurze Zeile, mehr als drei auf einmal in einer Zeile. Nach dem
 *     Betreten eines Servers drei Sekunden Ruhe, bis die Tab-Liste vollstaendig ist.</li>
 *     <li><b>Region</b>: ein Teammitglied ist "in deiner Region", solange sein Koerper zu sehen ist, der Server einen
 *     Spielmodus-Wechsel von ihm schickt (das tut er nur fuer Spieler bei dir) oder Anti Vanish sein Team bei dir
 *     ankommen sieht - danach noch 10 Minuten. Es rutscht nach oben, und es gibt eine Meldung mit Klingeln (hoechstens
 *     alle 2 Minuten je Person).</li>
 * </ul>
 * Reihenfolge: in der Naehe, in deiner Region, versteckt/Zuschauer, online - innerhalb wie in {@link #STAFF_NAMES}.
 */
public class AdminListMod extends RenderMod {
    public static final float[] SCALES = {0.75F, 1.0F, 1.25F, 1.5F};
    private static final String TAG = "Adminlist";

    /** Teammitglieder, die angezeigt werden, wenn sie online sind (in dieser Reihenfolge). */
    public static final List<String> STAFF_NAMES = List.of(
            "0Gsummer", "archivePedro", "Pastagamer08", "Fluffymaster07", "Frenk_btw",
            "Napooo_", "DrDonutt", "u_vv", "captainmoose35", "w1zox_",
            "LzouZMp5", "BobIsFound", "cryptodaveyt", "splaterd", "Auzzitech",
            "bloodspulse", "Dough4", "0G9", "Munkerlich", "ZEEF69",
            "showered", "NoahvdAa", "Owen1212055", "Fallerfly", "_chaon",
            "evify");

    private static final int SCAN_TICKS = 10;
    /** Nach dem Betreten eines Servers so lange nichts melden - die Tab-Liste fuellt sich erst. */
    private static final int SETTLE_TICKS = 60;
    /** Aus dem Tab verschwunden, Koerper aber noch da: erst nach so vielen Scans als versteckt melden. */
    private static final int OFF_TAB_SCANS = 2;
    /** Mehr Aenderungen einer Art auf einmal werden in einer Zeile zusammengefasst. */
    private static final int GROUP_LINE = 3;
    private static final long REGION_MS = 10 * 60_000L;
    private static final long ALERT_COOLDOWN_MS = 2 * 60_000L;
    private static final long HIGHLIGHT_MS = 4000L;
    /** So lange muss ein Koerper weg sein, damit sein Wiederauftauchen neu gemeldet wird. */
    private static final long BODY_GONE_MS = 10_000L;
    /** Mehr Zeilen zeigt die Liste nicht (die letzte wird dann zu "+N more"). */
    private static final int MAX_ROWS = 8;
    /** Wertfarbe fuer "in deiner Region" / Abstand; versteckt und Alarm sind rot. */
    private static final int WARN = 0xE8B04A;

    // Einstellungen
    /** Aussehen (Farben, Hintergrund, Schrift, Abstaende), einstellbar im HudStyleMenu. */
    public static final HudStyle style = new HudStyle();
    /** Join/Leave/Zustands-Wechsel im Chat. */
    public static boolean chat = true;
    /** Meldung, wenn ein Teammitglied in deine Region kommt. */
    public static boolean regionAlert = true;
    public static boolean sound = true;

    static {
        cleanBackground();
    }

    /** Sauberer Hintergrund: dunkles, abgerundetes Feld hinter der Liste. */
    private static void cleanBackground() {
        style.background = true;
        style.backgroundColor = 0x101014;
        style.backgroundOpacity = 80;
        style.radius = 4;
    }
    public static int scaleIndex = 1;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = Standard. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    /** Zuletzt gezeichneter Bereich (fuer den Verschiebe-Modus). */
    public static Rect bounds;

    private static AdminListMod instance;
    private long enabledAt = Util.getMillis();

    public enum Presence {
        ONLINE, SPECTATOR, HIDDEN
    }

    /** Was die Liste von einem Teammitglied zeigt. */
    public record Staff(String name, PlayerSkin skin, Presence presence, boolean nearby, float distance, boolean inRegion,
                        boolean highlight) {
    }

    public AdminListMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        enabledAt = Util.getMillis();
        forget();
        if (!enabled) {
            bounds = null;
        }
    }

    private static boolean isActive() {
        return instance != null && instance.active;
    }

    // ---------------------------------------------------------------- Erkennen

    /** Kleingeschriebener Name -> Platz in {@link #STAFF_NAMES} (fuer die Sortierung). */
    private static final Map<String, Integer> STAFF_ORDER = new HashMap<>();

    static {
        for (int i = 0; i < STAFF_NAMES.size(); i++) {
            STAFF_ORDER.put(STAFF_NAMES.get(i).toLowerCase(Locale.ROOT), i);
        }
    }

    /** Ein Teammitglied, solange es auf dem Server ist. */
    private static final class Tracked {
        final UUID id;
        String name;
        PlayerSkin skin;
        Presence presence;
        int offTab;
        /** Region: letzter Beweis, Koerper zu sehen (mit Abstand), seit wann weg, Markierung, letzte Meldung. */
        long provenAt;
        boolean nearby;
        int bodyTick;
        float distance;
        long bodyGoneAt;
        long highlightUntil;
        long alertedAt;

        Tracked(UUID id, String name, PlayerSkin skin, Presence presence) {
            this.id = id;
            this.name = name;
            this.skin = skin;
            this.presence = presence;
        }

        boolean inRegion(long now) {
            return nearby || provenAt != 0L && now - provenAt < REGION_MS;
        }

        void leaveRegion() {
            provenAt = 0L;
            nearby = false;
            bodyGoneAt = 0L;
        }
    }

    private static final Map<UUID, Tracked> TRACKED = new LinkedHashMap<>();
    private static List<Staff> view = List.of();
    private static ClientPacketListener connection;
    private static Level level;
    private static int ticks;
    private static int settle;
    private static boolean primed;

    private static void forget() {
        TRACKED.clear();
        view = List.of();
        connection = null;
        level = null;
        primed = false;
    }

    private static boolean isStaff(String name) {
        return name != null && STAFF_ORDER.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** Jeder Client-Tick (Nova_clientClient). */
    public static void tick(Minecraft client) {
        ClientPacketListener current = client.getConnection();
        if (!isActive() || current == null || client.player == null || client.level == null) {
            if (connection != null || !TRACKED.isEmpty()) {
                forget();
            }
            return;
        }
        long now = Util.getMillis();
        if (current != connection) {
            forget();
            connection = current;
            settle = SETTLE_TICKS;
        }
        if (client.level != level) {
            // neue Welt/Dimension: alte Region-Beweise gelten nicht mehr
            level = client.level;
            for (Tracked t : TRACKED.values()) {
                t.leaveRegion();
            }
        }
        if (settle > 0) {
            settle--;
        }
        ticks++;
        if (regionAlert) {
            bodies(client, now);
        }
        if (ticks % SCAN_TICKS == 0) {
            Changes changes = scan(client, current);
            if (primed) {
                announce(changes);
            } else if (settle == 0) {
                primed = true;
            }
        }
        view = build(now);
    }

    private static final class Changes {
        final List<Tracked> joined = new ArrayList<>();
        final List<Tracked> changed = new ArrayList<>();
        final List<Tracked> left = new ArrayList<>();
    }

    /** Tab-Liste mit dem letzten Stand vergleichen. */
    private static Changes scan(Minecraft client, ClientPacketListener connection) {
        UUID self = client.player.getUUID();
        Set<UUID> listed = new HashSet<>();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            listed.add(info.getProfile().id());
        }
        Changes changes = new Changes();
        Set<UUID> present = new HashSet<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            UUID id = info.getProfile().id();
            String name = info.getProfile().name();
            // Version 2 = Platzhalter-Eintraege von Tab-Plugins, keine echten Spieler
            if (id == null || id.equals(self) || id.version() == 2 || !isStaff(name)) {
                continue;
            }
            present.add(id);
            Presence presence = info.getGameMode() == GameType.SPECTATOR ? Presence.SPECTATOR
                    : listed.contains(id) ? Presence.ONLINE : Presence.HIDDEN;
            Tracked t = TRACKED.get(id);
            if (t == null) {
                t = new Tracked(id, name, info.getSkin(), presence);
                TRACKED.put(id, t);
                changes.joined.add(t);
            } else {
                t.name = name;
                t.skin = info.getSkin();
                t.offTab = 0;
                if (t.presence != presence) {
                    t.presence = presence;
                    changes.changed.add(t);
                }
            }
        }
        Iterator<Tracked> it = TRACKED.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            if (present.contains(t.id)) {
                continue;
            }
            if (client.level.getPlayerByUUID(t.id) != null) {
                // aus dem Tab verschwunden, aber der Koerper ist noch da: versteckt
                if (++t.offTab >= OFF_TAB_SCANS && t.presence != Presence.HIDDEN) {
                    t.presence = Presence.HIDDEN;
                    changes.changed.add(t);
                }
            } else {
                it.remove();
                changes.left.add(t);
            }
        }
        return changes;
    }

    /** Koerper in Sichtweite = in deiner Region; taucht einer nach mindestens 10 Sekunden Pause auf, gibt es eine Meldung. */
    private static void bodies(Minecraft client, long now) {
        for (Player body : client.level.players()) {
            Tracked t = body == client.player ? null : TRACKED.get(body.getUUID());
            if (t == null) {
                continue;
            }
            t.distance = client.player.distanceTo(body);
            t.provenAt = now;
            t.bodyTick = ticks;
            if (!t.nearby) {
                t.nearby = true;
                if (t.bodyGoneAt == 0L || now - t.bodyGoneAt > BODY_GONE_MS) {
                    alert(t, now, "nearby, " + Math.round(t.distance) + "m");
                }
            }
        }
        for (Tracked t : TRACKED.values()) {
            if (t.nearby && t.bodyTick != ticks) {
                t.nearby = false;
                t.bodyGoneAt = now;
            }
        }
    }

    /** Spielmodus-Wechsel (Paket noch nicht angewendet): der Server schickt ihn nur fuer Spieler in deiner Region. */
    public static void onTabUpdate(ClientboundPlayerInfoUpdatePacket packet) {
        if (!isActive() || !regionAlert || connection == null
                || !packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)
                || packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            return;
        }
        long now = Util.getMillis();
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            Tracked t = TRACKED.get(entry.profileId());
            if (t != null && entry.gameMode() != null) {
                t.provenAt = now;
                alert(t, now, "switched to " + entry.gameMode().getName());
            }
        }
    }

    /** Anti Vanish hat das Team des Spielers bei dir ankommen sehen - Anti Vanish meldet selbst, hier nur markieren. */
    public static void provenHere(UUID id, long now) {
        Tracked t = isActive() ? TRACKED.get(id) : null;
        if (t != null) {
            t.provenAt = now;
            t.highlightUntil = now + HIGHLIGHT_MS;
        }
    }

    /** Zeile kurz rot markieren; Chat und Klingeln hoechstens alle ALERT_COOLDOWN_MS je Person. */
    private static void alert(Tracked t, long now, String how) {
        t.highlightUntil = now + HIGHLIGHT_MS;
        if (t.alertedAt != 0L && now - t.alertedAt < ALERT_COOLDOWN_MS) {
            return;
        }
        t.alertedAt = now;
        if (chat) {
            NovaNotify.chat(TAG, "§f" + t.name + " §cis in your region §7(" + how + ")");
        }
        if (sound) {
            VanishAlert.ring();
        }
    }

    // ---------------------------------------------------------------- Melden

    private static void announce(Changes changes) {
        if (!chat) {
            return;
        }
        lines(changes.joined, "joined", t -> switch (t.presence) {
            case ONLINE -> "§7joined";
            case SPECTATOR -> "§7joined §c(spectating)";
            case HIDDEN -> "§7joined §c(vanished)";
        });
        lines(changes.changed, "changed visibility", t -> switch (t.presence) {
            case ONLINE -> "§7is visible again";
            case SPECTATOR -> "§7switched to §cspectator";
            case HIDDEN -> "§cvanished §7" + (t.offTab > 0 ? "(off TAB, still nearby)" : "(hidden from TAB)");
        });
        lines(changes.left, "left", t -> t.presence == Presence.HIDDEN ? "§7is out of sight" : "§7left");
        boolean urgent = !changes.joined.isEmpty() || changes.changed.stream().anyMatch(t -> t.presence != Presence.ONLINE);
        if (urgent && sound) {
            NovaNotify.sound(SoundEvents.NOTE_BLOCK_CHIME, 1.25F);
        }
    }

    /** Eine Zeile je Teammitglied, bei mehr als GROUP_LINE eine Sammelzeile. */
    private static void lines(List<Tracked> group, String verb, Function<Tracked, String> what) {
        if (group.size() > GROUP_LINE) {
            List<String> names = new ArrayList<>();
            for (Tracked t : group) {
                names.add(t.name);
            }
            NovaNotify.chat(TAG, "§f" + group.size() + " staff §7" + verb + ": §f" + String.join("§7, §f", names));
            return;
        }
        for (Tracked t : group) {
            NovaNotify.chat(TAG, "§f" + t.name + " " + what.apply(t));
        }
    }

    // ---------------------------------------------------------------- Liste

    private static List<Staff> build(long now) {
        List<Tracked> all = new ArrayList<>(TRACKED.values());
        all.sort(Comparator.comparingInt((Tracked t) -> group(t, now))
                .thenComparingInt(t -> STAFF_ORDER.getOrDefault(t.name.toLowerCase(Locale.ROOT), Integer.MAX_VALUE)));
        List<Staff> staff = new ArrayList<>(all.size());
        for (Tracked t : all) {
            staff.add(new Staff(t.name, t.skin, t.presence, t.nearby, t.distance, t.inRegion(now), now < t.highlightUntil));
        }
        return List.copyOf(staff);
    }

    /** 0 = in der Naehe, 1 = in deiner Region, 2 = versteckt/Zuschauer, 3 = einfach online. */
    private static int group(Tracked t, long now) {
        if (t.nearby) {
            return 0;
        }
        if (t.inRegion(now)) {
            return 1;
        }
        return t.presence == Presence.ONLINE ? 3 : 2;
    }

    /** So lange (ms) gilt das letzte Ergebnis, wenn das Modul aus ist - die Tab-Liste wird nicht jedes Bild durchsucht. */
    private static final long CACHE_MS = 500L;
    private static List<Staff> cachedStaff = List.of();
    private static ClientPacketListener cachedConnection;
    private static long cachedAt;

    /**
     * Die Teammitglieder fuer die Liste (sortiert). Laeuft das Modul, kommt der laufend gepflegte Stand; sonst (z.B. im
     * Einstellungs-Menue) wird die Tab-Liste einmal durchlaufen und kurz zwischengespeichert.
     */
    public static List<Staff> findStaff(Minecraft client) {
        if (isActive() && connection != null) {
            return view;
        }
        ClientPacketListener current = client.getConnection();
        if (current == null) {
            cachedConnection = null;
            return List.of();
        }
        long now = Util.getMillis();
        if (current == cachedConnection && now - cachedAt < CACHE_MS) {
            return cachedStaff;
        }
        Set<UUID> listed = new HashSet<>();
        for (PlayerInfo info : current.getListedOnlinePlayers()) {
            listed.add(info.getProfile().id());
        }
        List<Staff> staff = new ArrayList<>();
        for (PlayerInfo info : current.getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (isStaff(name)) {
                Presence presence = info.getGameMode() == GameType.SPECTATOR ? Presence.SPECTATOR
                        : listed.contains(info.getProfile().id()) ? Presence.ONLINE : Presence.HIDDEN;
                staff.add(new Staff(name, info.getSkin(), presence, false, 0.0F, false, false));
            }
        }
        staff.sort(Comparator.comparingInt((Staff s) -> s.presence() == Presence.ONLINE ? 1 : 0)
                .thenComparingInt(s -> STAFF_ORDER.get(s.name().toLowerCase(Locale.ROOT))));
        cachedStaff = List.copyOf(staff);
        cachedConnection = current;
        cachedAt = now;
        return cachedStaff;
    }

    /** Kurz rechts neben dem Namen: Abstand, Zustand oder Region; leer = einfach online. */
    public static String status(Staff s) {
        if (s.nearby()) {
            return Math.round(s.distance()) + "m";
        }
        return switch (s.presence()) {
            case SPECTATOR -> "spectator";
            case HIDDEN -> "vanished";
            case ONLINE -> s.inRegion() ? "your region" : "";
        };
    }

    /** Rot = versteckt, Zuschauer oder gerade gemeldet; gelb = in der Naehe/Region; 0 = Stilfarbe. */
    private static int statusColor(Staff s) {
        if (s.highlight() || s.presence() != Presence.ONLINE) {
            return NovaGui.DANGER;
        }
        return s.nearby() || s.inRegion() ? WARN : 0;
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Schild-Bild (64x64) fuer die Kopfzeile. */
    private static final Identifier SHIELD = Identifier.fromNamespaceAndPath("nova_client",
            "textures/gui/adminlist_shield.png");
    private static final int SHIELD_SIZE = 64;

    /** Schild als 8x8-Symbol, blendet mit der Liste ein. */
    private static final HudStyle.Icon SHIELD_ICON = (g, x, y, intro) -> g.blit(RenderPipelines.GUI_TEXTURED, SHIELD,
            x, y, 0, 0, 8, 8, SHIELD_SIZE, SHIELD_SIZE, SHIELD_SIZE, SHIELD_SIZE, NovaGui.alpha(0xFFFFFFFF, intro));

    /** Spielerkopf (Gesicht + Hut-Ebene des Skins) als 8x8-Symbol. */
    private static HudStyle.Icon head(PlayerSkin skin) {
        return (g, x, y, intro) -> PlayerFaceExtractor.extractRenderState(g, skin, x, y, 8,
                NovaGui.alpha(0xFFFFFFFF, intro));
    }

    /** Kopfzeile (Schild + "Admins") + eine Zeile je Teammitglied (Kopf, Name, rechts der Zustand). */
    public static List<HudStyle.Row> rows(List<Staff> staff) {
        List<HudStyle.Row> rows = new ArrayList<>();
        rows.add(new HudStyle.Row("Admins", "", 0, 0, SHIELD_ICON));
        if (staff.isEmpty()) {
            rows.add(new HudStyle.Row("None online", ""));
        }
        int shown = staff.size() <= MAX_ROWS ? staff.size() : MAX_ROWS - 1;
        for (int i = 0; i < shown; i++) {
            Staff s = staff.get(i);
            rows.add(new HudStyle.Row(s.name(), status(s), 0, statusColor(s), head(s.skin())));
        }
        if (shown < staff.size()) {
            rows.add(new HudStyle.Row("+" + (staff.size() - shown) + " more", ""));
        }
        return rows;
    }

    /** Echte Teammitglieder, falls welche online sind, sonst Beispielzeilen fuer die Vorschau im Stil-Menue. */
    public static List<HudStyle.Row> previewRows() {
        List<Staff> staff = findStaff(Minecraft.getInstance());
        if (staff.isEmpty()) {
            List<String> names = STAFF_NAMES.subList(0, 3);
            staff = List.of(
                    new Staff(names.get(0), previewSkin(names.get(0)), Presence.ONLINE, true, 12.0F, true, false),
                    new Staff(names.get(1), previewSkin(names.get(1)), Presence.HIDDEN, false, 0.0F, false, false),
                    new Staff(names.get(2), previewSkin(names.get(2)), Presence.ONLINE, false, 0.0F, false, false));
        }
        return rows(staff);
    }

    private static PlayerSkin previewSkin(String name) {
        return DefaultPlayerSkin.get(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getDebugOverlay().showDebugScreen()) {
            bounds = null;
            return;
        }
        Font font = client.font;
        List<HudStyle.Row> rows = rows(findStaff(client));

        float scale = SCALES[Math.max(0, Math.min(SCALES.length - 1, scaleIndex))];
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        float w = style.width(font, rows) * scale;
        float h = style.height(rows, 1) * scale;
        float left;
        float top;
        if (posX >= 0.0F) {
            left = Math.max(0.0F, Math.min(screenW - w, posX * screenW));
            top = Math.max(0.0F, Math.min(screenH - h, posY * screenH));
        } else {
            // Standard: linke Spalte (unter HudInfo und Region Map)
            left = 6;
            top = nova.nova_client.client.HudLayout.top(nova.nova_client.client.HudLayout.Slot.ADMIN_LIST, Math.round(h));
        }
        bounds = new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h));

        float intro = NovaGui.easeOutCubic((Util.getMillis() - enabledAt) / 350.0F);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        style.draw(g, font, rows, 1, intro);
        g.pose().popMatrix();
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new AdminListMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "scale", String.valueOf(scaleIndex));
        properties.setProperty(prefix + "x", String.valueOf(posX));
        properties.setProperty(prefix + "y", String.valueOf(posY));
        properties.setProperty(prefix + "chat", String.valueOf(chat));
        properties.setProperty(prefix + "regionAlert", String.valueOf(regionAlert));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
        style.save(properties, prefix);
        properties.setProperty(prefix + "style.clean", "true");
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            scaleIndex = Math.max(0, Math.min(SCALES.length - 1,
                    Integer.parseInt(properties.getProperty(prefix + "scale", String.valueOf(scaleIndex)))));
            posX = Float.parseFloat(properties.getProperty(prefix + "x", String.valueOf(posX)));
            posY = Float.parseFloat(properties.getProperty(prefix + "y", String.valueOf(posY)));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        chat = Boolean.parseBoolean(properties.getProperty(prefix + "chat", String.valueOf(chat)));
        regionAlert = Boolean.parseBoolean(properties.getProperty(prefix + "regionAlert", String.valueOf(regionAlert)));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
        style.load(properties, prefix);
        // Aeltere Configs ohne Hintergrund einmalig auf den sauberen Hintergrund umstellen
        if (properties.getProperty(prefix + "style.clean") == null) {
            cleanBackground();
        }
    }
}
