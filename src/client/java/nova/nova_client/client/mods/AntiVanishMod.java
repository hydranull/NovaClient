package nova.nova_client.client.mods;

import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import nova.nova_client.client.HudCards;
import nova.nova_client.client.VanishAlert;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti Vanish: erkennt Spieler (standardmaessig nur Teammitglieder), die sich unsichtbar gemacht haben.
 * <ul>
 *     <li><b>Aus der Tab-Liste verschwunden</b>, ohne Leave-Nachricht - besonders sicher, wenn ihr Koerper noch da ist.</li>
 *     <li><b>In der Tab-Liste versteckt</b> (Server setzt "nicht gelistet").</li>
 *     <li><b>Zuschauer-Modus</b> (Spielmodus-Wechsel zu Spectator).</li>
 *     <li><b>Noch anschreibbar</b>: alle paar Sekunden fragt der Mod still die Vervollstaendigung von "/minecraft:msg " ab -
 *     Namen, die dort auftauchen, aber nicht in der Tab-Liste stehen, sind versteckt online.</li>
 *     <li><b>Unsichtbarer Spieler</b> in deiner Naehe - auch sofort, sobald der Server das Unsichtbar-Flag schickt.</li>
 *     <li><b>Geister-Geraeusche</b>: Tuer, Truhe, Knopf ... ohne sichtbaren Verursacher (eigene Aktionen, Redstone,
 *     Explosionen und Dorfbewohner werden herausgefiltert).</li>
 *     <li><b>Geister-Partikel</b>: Treffer-, Rauch- und Block-Partikel ohne sichtbaren Verursacher.</li>
 *     <li><b>Spielmodus-Wechsel</b> von Staff (nicht nur Spectator).</li>
 *     <li><b>Shard-Wache (DonutSMP)</b>: der Server legt fuer jeden Spieler in deinem Teil der Welt ein Team
 *     "NN-name-true/false" an (NN = Rang, 00-08 = Staff) und loescht es, wenn er wieder geht - auch fuer versteckte
 *     Spieler. Ein neues Team ohne passende Tab-Aenderung heisst: er ist gerade zu dir gekommen.</li>
 * </ul>
 * <b>Region</b>: ein Teammitglied gilt als "bei dir", solange sein Koerper zu sehen ist oder du in den letzten 10 Minuten
 * ein Spielmodus-Paket von ihm bekommen hast (das schickt der Server nur fuer Spieler auf deinem Server) bzw. sein Team
 * bei dir angelegt wurde.
 * <b>Alarm "watching you"</b>: verschwindet jemand aus deiner Region, oder kommen in 15 Sekunden mindestens zwei
 * verschiedene Hinweise (unsichtbar, Geraeusch, Partikel, stilles Verschwinden) mit genug Gewicht zusammen und einer davon
 * gehoert zu jemandem aus deiner Region, gibt es den grossen Alarm.
 * <b>Anzeige</b>: bei einem Fund die grosse Erscheinung passend zum Design (am Ende "NAME is here") mit klingelndem
 * Hoehlengeraeusch, dazu die kleine Liste aus Nova-HUD-Karten; kommt jemand zurueck in die Tab-Liste, verschwindet er
 * still aus der Liste.
 */
public class AntiVanishMod extends GameTickMod {
    private static final String TAG = "Anti Vanish";
    private static final String PROBE = "minecraft:msg ";
    private static final int PROBE_TICKS = 100;
    private static final int PENDING_TICKS = 20;
    /** Massen-Aenderungen (Serverwechsel, Neustart) ignorieren. */
    private static final int MAX_BATCH = 3;
    private static final long TRUSTED_LISTED_MS = 1500L;
    private static final long LEAVE_MESSAGE_MS = 8000L;
    private static final long INVISIBLE_TTL_MS = 20_000L;
    private static final long HIDDEN_TTL_MS = 5 * 60_000L;
    private static final int SENSOR_RANGE = 64;
    private static final int MAX_ROWS = 5;
    /** Hinweise so lange zusammenzaehlen. */
    private static final long HINT_WINDOW_MS = 15_000L;
    /** Mindestsumme (staerkster Hinweis je Art) fuer den Alarm. */
    private static final int ALARM_SCORE = 35;
    private static final long ALARM_COOLDOWN_MS = 12_000L;
    private static final long ALARM_SHOW_MS = 7_000L;
    private static final long WEAK_TTL_MS = 20_000L;
    /** So lange gilt ein Teammitglied nach dem letzten Beweis als "in deiner Region". */
    private static final long REGION_MS = 10 * 60_000L;
    /** Eigene Klicks/Schlaege: so lange danach zaehlen Geraeusche/Partikel nicht. */
    private static final long OWN_ACTION_MS = 1000L;
    private static final long OWN_BLOCK_MS = 6000L;
    private static final long OWN_CONTAINER_MS = 2500L;
    private static final long BLAST_MS = 3000L;
    private static final long REDSTONE_MS = 5000L;
    /** Verursacher (sichtbarer Spieler, Pfeil, Dorfbewohner) so nah = kein Geist. */
    private static final double CAUSE_RANGE_SQ = 16.0D;
    /** Punkt in der Liste fuer blosse Hinweise (unsichtbar, Geraeusch, Partikel); Versteckte bekommen DANGER. */
    private static final int HINT_DOT = 0xFFE8B04A;

    // Shard-Wache (DonutSMP-Teams)
    /** Teamname "NN-name-true/false": NN = Rang. */
    private static final Pattern RANK_TEAM = Pattern.compile("(\\d{2})-([A-Za-z0-9_]{3,16})-(?:true|false)");
    /** Raenge 00 bis hier sind Staff. */
    private static final int STAFF_RANK_MAX = 8;
    /** Team-Aenderung erst nach so langer Zeit bewerten - bis dahin ist eine passende Tab-Aenderung angekommen. */
    private static final long TEAM_HOLD_MS = 1000L;
    /** So viele Team-Aenderungen innerhalb von TEAM_BURST_MS = Serverwechsel/Neustart, keine einzelne Bewegung. */
    private static final int TEAM_BURST = 5;
    private static final long TEAM_BURST_MS = 50L;
    /** Nach dem Betreten eines Servers oder einer Welt kommen alle Teams auf einmal - so lange nichts melden. */
    private static final long TEAM_QUIET_MS = 4000L;
    /** Sichtbaren Staff hoechstens so oft als "on your shard" melden. */
    private static final long ARRIVAL_REPEAT_MS = 60_000L;

    // Einstellungen
    /** Nur Namen aus der Adminlist (plus eigene Namen) beachten. */
    public static boolean staffOnly = true;
    public static String extraNames = "";
    public static boolean probe = true;
    public static boolean invisible = true;
    /** Grosse Erscheinung (passend zum Design) bei einem Fund. */
    public static boolean alert = true;
    public static boolean sound = true;
    public static boolean hudList = true;
    public static boolean ghostSounds = true;
    public static boolean ghostParticles = true;
    public static boolean watchAlarm = true;
    public static boolean gameModes = true;
    /** Ankunft/Abgang ueber die DonutSMP-Teams melden. */
    public static boolean shardWatch = true;
    /** Raenge 00-08 aus den DonutSMP-Teams zaehlen als Staff (zusaetzlich zur Adminlist). */
    public static boolean rankStaff = true;

    private static volatile boolean enabled;
    private static int ticks;
    private static Level lastLevel;
    /** UUID -> Name, solange bekannt. */
    private static final Map<UUID, String> KNOWN = new HashMap<>();
    /** UUID -> seit wann in der Tab-Liste. */
    private static final Map<UUID, Long> LISTED_SINCE = new HashMap<>();
    private record Pending(UUID uuid, String name, int dueTick) {
    }
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private record Message(String text, long at) {
    }
    private static final ArrayDeque<Message> MESSAGES = new ArrayDeque<>();
    private static boolean serverSendsLeaveMessages;

    /** Aktuelle Funde: kleingeschriebener Name -> Fund. */
    private static final class Detection {
        final String name;
        String tag;
        String detail;
        long expires;
        /** Gilt bis zur Rueckkehr in die Tab-Liste (statt nur kurz). */
        boolean hidden;

        Detection(String name, String tag, String detail, long expires, boolean hidden) {
            this.name = name;
            this.tag = tag;
            this.detail = detail;
            this.expires = expires;
            this.hidden = hidden;
        }
    }
    private static final Map<String, Detection> DETECTIONS = new LinkedHashMap<>();
    private static final Map<String, Long> COOLDOWNS = new HashMap<>();

    // Vervollstaendigungs-Abfrage (Antwort kommt auf dem Netzwerk-Thread)
    private static final Set<Integer> PROBE_IDS = ConcurrentHashMap.newKeySet();
    private static volatile List<String> probeAnswer;
    private static int nextProbeId = 30000;

    // Hinweise fuer den Alarm
    private enum Sense {
        VANISH("silent vanish"), INVIS("invisible"), SOUND("ghost sound"), PARTICLE("ghost particle");

        final String label;

        Sense(String label) {
            this.label = label;
        }
    }
    /** who = Name oder Ort ("12m NE"); region = UUID, wenn der Hinweis zu jemandem aus deiner Region gehoert. */
    private record Hint(Sense sense, int weight, String who, UUID region, long at) {
    }
    private static final ArrayDeque<Hint> HINTS = new ArrayDeque<>();
    /** UUID -> letzter Beweis, dass der Spieler auf deinem Server ist. */
    private static final Map<UUID, Long> REGION = new HashMap<>();
    private static long lastAlarm;
    private static long alarmUntil;
    private static String alarmName = "";

    // Filter gegen Fehlalarme (eigene Aktionen kommen aus dem Sende-Pfad, daher thread-sicher)
    private static volatile long lastOwnAction;
    private static long lastOwnContainer;
    /** Block (asLong) -> bis wann er als "selbst benutzt" gilt. */
    private static final Map<Long, Long> OWN_BLOCKS = new ConcurrentHashMap<>();
    /** Block (asLong) -> bis wann er als Redstone-Mechanik gilt. */
    private static final Map<Long, Long> REDSTONE = new HashMap<>();
    private record Blast(Vec3 center, double radius, long at) {
    }
    private static final ArrayDeque<Blast> BLASTS = new ArrayDeque<>();
    /** Schwache Partikel (Rauch, Block) zaehlen erst ab dem zweiten innerhalb von 2 Sekunden. */
    private static final Map<String, Long> WEAK_PARTICLES = new HashMap<>();

    // Shard-Wache
    /** Angelegtes (arrived) oder geloeschtes Team, wird nach TEAM_HOLD_MS bewertet. */
    private record TeamMove(String name, boolean arrived, long at) {
    }
    private static final ArrayDeque<TeamMove> TEAM_MOVES = new ArrayDeque<>();
    /** Zeitpunkte aller Team-Aenderungen (fuer die Massen-Erkennung). */
    private static final ArrayDeque<Long> TEAM_CHANGES = new ArrayDeque<>();
    /** Kleingeschriebener Name -> letzte Tab-Aenderung (Join/Leave): dann gehoert das Team zu einem normalen Join. */
    private static final Map<String, Long> TAB_MOVES = new HashMap<>();
    /** Kleingeschriebener Name -> Rang aus dem Teamnamen. */
    private static final Map<String, Integer> RANKS = new HashMap<>();
    /** Kleingeschriebener Name -> wann als "on your shard" gemeldet (fuer die Abgangs-Meldung). */
    private static final Map<String, Long> ARRIVALS = new HashMap<>();
    private static long teamsQuietUntil;

    // Vorschau ("so sieht es aus, wenn dir jemand im Spectator zuschaut") - getrennt vom echten Zustand
    private static final long PREVIEW_ALARM_MS = 1800L;
    private static final long PREVIEW_MS = 9000L;
    private static long previewStart;
    private static boolean previewAlarm;
    private static String previewName = "";

    public AntiVanishMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        reset();
        if (on) {
            trackListed(client);
            readRanks(client);
        } else {
            VanishAlert.clear();
        }
    }

    private static void reset() {
        KNOWN.clear();
        LISTED_SINCE.clear();
        PENDING.clear();
        MESSAGES.clear();
        DETECTIONS.clear();
        COOLDOWNS.clear();
        PROBE_IDS.clear();
        probeAnswer = null;
        serverSendsLeaveMessages = false;
        ticks = 0;
        HINTS.clear();
        REGION.clear();
        lastAlarm = 0L;
        alarmUntil = 0L;
        alarmName = "";
        lastOwnAction = 0L;
        lastOwnContainer = 0L;
        OWN_BLOCKS.clear();
        REDSTONE.clear();
        BLASTS.clear();
        WEAK_PARTICLES.clear();
        TEAM_MOVES.clear();
        TEAM_CHANGES.clear();
        TAB_MOVES.clear();
        RANKS.clear();
        ARRIVALS.clear();
        teamsQuietUntil = 0L;
        lastLevel = null;
    }

    /** Neuer Server / neue Welt. */
    public static void onLogin() {
        if (enabled) {
            reset();
            teamsQuietUntil = Util.getMillis() + TEAM_QUIET_MS;
        }
    }

    // ---------------------------------------------------------------- Wer zaehlt

    private static boolean watched(String name) {
        if (name == null || name.length() < 3 || name.length() > 16 || name.indexOf(' ') >= 0) {
            return false;
        }
        if (!staffOnly) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (rankStaff && RANKS.getOrDefault(lower, Integer.MAX_VALUE) <= STAFF_RANK_MAX) {
            return true;
        }
        for (String staff : AdminListMod.STAFF_NAMES) {
            if (staff.equalsIgnoreCase(lower)) {
                return true;
            }
        }
        for (String extra : extraNames.split("[,;\\s]+")) {
            if (!extra.isBlank() && extra.trim().equalsIgnoreCase(lower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean self(UUID uuid) {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.getUUID().equals(uuid);
    }

    private static boolean trusted(UUID uuid) {
        Long since = LISTED_SINCE.get(uuid);
        return since != null && Util.getMillis() - since >= TRUSTED_LISTED_MS;
    }

    // ---------------------------------------------------------------- Pakete (Hauptthread, vor dem Anwenden)

    /** Spieler werden aus der Tab-Liste entfernt (sie stehen noch drin). */
    public static void onTabRemove(List<UUID> ids) {
        if (!enabled) {
            return;
        }
        long now = Util.getMillis();
        for (UUID uuid : ids) {
            String name = KNOWN.get(uuid);
            if (name != null) {
                TAB_MOVES.put(name.toLowerCase(Locale.ROOT), now);
            }
        }
        if (ids.size() > MAX_BATCH) {
            return;
        }
        for (UUID uuid : ids) {
            String name = KNOWN.get(uuid);
            if (!self(uuid) && watched(name) && trusted(uuid)) {
                PENDING.removeIf(p -> p.uuid().equals(uuid));
                PENDING.add(new Pending(uuid, name, ticks + PENDING_TICKS));
            }
        }
    }

    public static void onTabUpdate(ClientboundPlayerInfoUpdatePacket packet) {
        if (!enabled) {
            return;
        }
        boolean adding = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
        if (adding) {
            long now = Util.getMillis();
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                if (entry.profile() != null && entry.profile().name() != null) {
                    TAB_MOVES.put(entry.profile().name().toLowerCase(Locale.ROOT), now);
                }
            }
        }
        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED)) {
            List<ClientboundPlayerInfoUpdatePacket.Entry> hidden = new ArrayList<>();
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                if (!entry.listed()) {
                    hidden.add(entry);
                }
            }
            if (!adding && !hidden.isEmpty() && hidden.size() <= MAX_BATCH) {
                for (ClientboundPlayerInfoUpdatePacket.Entry entry : hidden) {
                    String name = KNOWN.get(entry.profileId());
                    if (!self(entry.profileId()) && watched(name) && trusted(entry.profileId()) && !recentlyLeft(name)) {
                        report(name, "VANISH", "hidden from TAB", true);
                    }
                }
            }
        }
        if (!adding && packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                String name = KNOWN.get(entry.profileId());
                if (self(entry.profileId()) || !watched(name) || entry.gameMode() == null) {
                    continue;
                }
                // Spielmodus-Pakete kommen nur fuer Spieler auf deinem Server: Beweis fuer "in deiner Region"
                REGION.put(entry.profileId(), Util.getMillis());
                // Paket ist noch nicht angewendet: in der Tab-Liste steht noch der alte Modus
                PlayerInfo info = connection == null ? null : connection.getPlayerInfo(entry.profileId());
                GameType before = info == null ? null : info.getGameMode();
                if (entry.gameMode() == before) {
                    continue;
                }
                if (entry.gameMode() == GameType.SPECTATOR) {
                    report(name, "SPECTATOR", "switched to spectator", true);
                    continue;
                }
                Detection d = DETECTIONS.get(name.toLowerCase(Locale.ROOT));
                if (d != null && "SPECTATOR".equals(d.tag)) {
                    visibleAgain(name, "left spectator (" + entry.gameMode().getName() + ")");
                } else if (gameModes) {
                    NovaNotify.chat(TAG, "§f" + name + " §7switched to §e" + entry.gameMode().getName());
                }
            }
        }
    }

    /** Team-Paket (Hauptthread, vor dem Anwenden): Rang merken, Anlegen/Loeschen als Bewegung vormerken. */
    public static void onTeam(ClientboundSetPlayerTeamPacket packet) {
        if (!enabled) {
            return;
        }
        Matcher rank = RANK_TEAM.matcher(packet.getName());
        if (!rank.matches()) {
            return;
        }
        String name = rank.group(2);
        RANKS.put(name.toLowerCase(Locale.ROOT), Integer.parseInt(rank.group(1)));
        ClientboundSetPlayerTeamPacket.Action action = packet.getTeamAction();
        if (action == null) {
            return;
        }
        boolean arrived = action == ClientboundSetPlayerTeamPacket.Action.ADD;
        // ein neues Team, in dem der Spieler selbst gar nicht steht, ist keine Bewegung
        if (arrived && !packet.getPlayers().isEmpty() && packet.getPlayers().stream().noneMatch(name::equalsIgnoreCase)) {
            return;
        }
        long now = Util.getMillis();
        TEAM_CHANGES.add(now);
        Minecraft client = Minecraft.getInstance();
        boolean own = client.player != null && client.player.getGameProfile().name().equalsIgnoreCase(name);
        if (shardWatch && !own && now >= teamsQuietUntil) {
            TEAM_MOVES.add(new TeamMove(name, arrived, now));
        }
    }

    /** Beim Einschalten die schon vorhandenen Teams lesen (die Raenge kommen sonst erst mit der naechsten Aenderung). */
    private static void readRanks(Minecraft client) {
        if (client.level == null) {
            return;
        }
        for (PlayerTeam team : client.level.getScoreboard().getPlayerTeams()) {
            Matcher rank = RANK_TEAM.matcher(team.getName());
            if (rank.matches()) {
                RANKS.put(rank.group(2).toLowerCase(Locale.ROOT), Integer.parseInt(rank.group(1)));
            }
        }
    }

    // ---------------------------------------------------------------- Sensoren (Hauptthread)

    /** Positions-Geraeusch vom Server (Tuer, Truhe, Knopf ...). */
    public static void onSound(ClientboundSoundPacket packet) {
        if (!enabled || !ghostSounds) {
            return;
        }
        String id = packet.getSound().value().location().getPath();
        if (!ghostSound(id)) {
            return;
        }
        Vec3 at = new Vec3(packet.getX(), packet.getY(), packet.getZ());
        long now = Util.getMillis();
        if (!unexplained(at, OWN_ACTION_MS) || ownBlock(at, now) || redstone(at, id, now)
                || isContainer(id) && now - lastOwnContainer < OWN_CONTAINER_MS) {
            return;
        }
        weakHint(Sense.SOUND, 14, at, pretty(id));
    }

    /** Geraeusch, das an einem Wesen haengt: kommt es von einem unsichtbaren Teammitglied, ist es ein klarer Hinweis. */
    public static void onEntitySound(ClientboundSoundEntityPacket packet) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || !ghostSounds || client.level == null || client.player == null) {
            return;
        }
        if (client.level.getEntity(packet.getId()) instanceof Player player && player != client.player
                && player.isInvisible() && player.distanceTo(client.player) <= SENSOR_RANGE) {
            String name = player.getGameProfile().name();
            if (watched(name)) {
                addHint(Sense.SOUND, 16, name, player.getUUID());
            }
        }
    }

    public static void onParticles(ClientboundLevelParticlesPacket packet) {
        if (!enabled || !ghostParticles) {
            return;
        }
        Identifier key = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle().getType());
        String id = key == null ? "" : key.getPath();
        boolean hit = id.equals("crit") || id.equals("enchanted_hit") || id.equals("damage_indicator");
        boolean weak = id.equals("block") || id.contains("smoke");
        if (!hit && !weak) {
            return;
        }
        Vec3 at = new Vec3(packet.x(), packet.y(), packet.z());
        if (!unexplained(at, 900L)) {
            return;
        }
        if (weak) {
            Minecraft client = Minecraft.getInstance();
            if (at.distanceToSqr(client.player.position()) < 6.25D || id.contains("smoke") && smokeSource(at)) {
                return;
            }
            long now = Util.getMillis();
            Long last = WEAK_PARTICLES.put(id, now);
            if (last == null || now - last > 2000L) {
                return;
            }
            WEAK_PARTICLES.remove(id);
        }
        weakHint(Sense.PARTICLE, 16, at, id);
    }

    public static void onExplosion(ClientboundExplodePacket packet) {
        if (!enabled) {
            return;
        }
        BLASTS.add(new Blast(packet.center(), Math.max(2.0D, packet.radius() + 4.0D), Util.getMillis()));
        while (BLASTS.size() > 8) {
            BLASTS.removeFirst();
        }
    }

    /** Wesen-Daten wurden gerade angewendet: wird ein Teammitglied unsichtbar, sofort melden (nicht erst im Takt). */
    public static void onEntityData(int entityId) {
        Minecraft client = Minecraft.getInstance();
        if (enabled && invisible && client.level != null && client.player != null
                && client.level.getEntity(entityId) instanceof Player player) {
            checkInvisible(client, player);
        }
    }

    /** Sende-Pfad (beliebiger Thread): eigene Klicks und benutzte Bloecke merken. */
    public static void onSend(Packet<?> packet) {
        if (!enabled) {
            return;
        }
        if (packet instanceof ServerboundUseItemOnPacket use) {
            long now = Util.getMillis();
            lastOwnAction = now;
            BlockHitResult hit = use.hitResult();
            if (hit != null) {
                // Tueren, Betten & Co. sind zwei Bloecke gross: rund um den Klick und den Nachbarblock alles merken
                markOwn(hit.getBlockPos(), now + OWN_BLOCK_MS);
                markOwn(hit.getBlockPos().relative(hit.getDirection()), now + OWN_BLOCK_MS);
            }
        } else if (packet instanceof ServerboundPlayerActionPacket || packet instanceof ServerboundUseItemPacket
                || packet instanceof ServerboundInteractPacket || isSwingPacket(packet)) {
            lastOwnAction = Util.getMillis();
        }
    }

    private static void markOwn(BlockPos center, long until) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            OWN_BLOCKS.put(pos.asLong(), until);
        }
    }

    private static boolean ghostSound(String path) {
        return path.endsWith(".open") || path.endsWith(".close") || path.endsWith(".locked")
                ? path.contains("chest") || path.contains("barrel") || path.contains("shulker_box") || path.contains("door")
                || path.contains("fence_gate")
                : path.contains("button.click") || path.contains("lever.click");
    }

    private static boolean isContainer(String path) {
        return path.contains("chest") || path.contains("barrel") || path.contains("shulker");
    }

    /** "block.wooden_door.open" -> "wooden door open". */
    private static String pretty(String path) {
        String rest = path.startsWith("block.") ? path.substring(6) : path;
        return rest.replace('_', ' ').replace('.', ' ');
    }

    /** In Reichweite, kein sichtbarer Verursacher, keine Explosion und nicht gerade selbst geklickt. */
    private static boolean unexplained(Vec3 at, long ownActionMs) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null
                || at.distanceToSqr(client.player.position()) > (double) SENSOR_RANGE * SENSOR_RANGE) {
            return false;
        }
        long now = Util.getMillis();
        if (now - lastOwnAction < ownActionMs) {
            return false;
        }
        while (!BLASTS.isEmpty() && now - BLASTS.peekFirst().at() > BLAST_MS) {
            BLASTS.removeFirst();
        }
        for (Blast blast : BLASTS) {
            if (blast.center().distanceToSqr(at) <= blast.radius() * blast.radius()) {
                return false;
            }
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player || entity.position().distanceToSqr(at) > CAUSE_RANGE_SQ) {
                continue;
            }
            if (entity instanceof Player player ? !player.isInvisible()
                    : entity instanceof Projectile || entity instanceof Villager) {
                return false;
            }
        }
        return true;
    }

    private static boolean ownBlock(Vec3 at, long now) {
        Long until = OWN_BLOCKS.get(BlockPos.containing(at).asLong());
        return until != null && until > now;
    }

    /** Tuer/Falltuer mit Strom (oder vor kurzem mit Strom): Redstone-Uhr, kein Spieler. */
    private static boolean redstone(Vec3 at, String path, long now) {
        if (!path.contains("door")) {
            return false;
        }
        Level level = Minecraft.getInstance().level;
        BlockPos center = BlockPos.containing(at);
        for (int dy = -1; dy <= 1; dy++) {
            BlockPos pos = center.above(dy);
            BlockState state = level.getBlockState(pos);
            boolean powered = state.hasProperty(BlockStateProperties.POWERED) && state.getValue(BlockStateProperties.POWERED);
            if (powered || level.hasNeighborSignal(pos)) {
                REDSTONE.put(pos.asLong(), now + REDSTONE_MS);
                return true;
            }
            if (REDSTONE.getOrDefault(pos.asLong(), 0L) > now) {
                return true;
            }
        }
        return false;
    }

    /** Rauch neben Lagerfeuer, Ofen, Fackel, Kerze ... ist normal. */
    private static boolean smokeSource(Vec3 at) {
        Level level = Minecraft.getInstance().level;
        BlockPos center = BlockPos.containing(at);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-2, -2, -2), center.offset(2, 2, 2))) {
            String path = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
            if (path.contains("campfire") || path.contains("furnace") || path.contains("smoker") || path.contains("torch")
                    || path.contains("fire") || path.contains("candle") || path.contains("respawn_anchor")) {
                return true;
            }
        }
        return false;
    }

    private static void checkInvisible(Minecraft client, Player player) {
        if (player == client.player || !player.isInvisible() || player.distanceTo(client.player) > SENSOR_RANGE) {
            return;
        }
        String name = player.getGameProfile().name();
        if (!watched(name)) {
            return;
        }
        if (cooldown("invis:" + name, 5000L)) {
            report(name, "INVIS", "invisible, " + Math.round(player.distanceTo(client.player)) + "m away", false);
            addHint(Sense.INVIS, 35, name, player.getUUID());
        }
    }

    // ---------------------------------------------------------------- Region und Alarm

    private static UUID uuidOf(String name) {
        for (Map.Entry<UUID, String> entry : KNOWN.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Koerper zu sehen oder in den letzten 10 Minuten ein Spielmodus-Paket bekommen. */
    private static boolean inRegion(UUID uuid) {
        Minecraft client = Minecraft.getInstance();
        if (uuid == null) {
            return false;
        }
        if (client.level != null && client.level.getPlayerByUUID(uuid) != null) {
            return true;
        }
        Long proven = REGION.get(uuid);
        return proven != null && Util.getMillis() - proven < REGION_MS;
    }

    /** Geraeusch/Partikel ohne Verursacher: Zeile in der Liste (gedrosselt), Hinweis fuer den Alarm. */
    private static void weakHint(Sense sense, int weight, Vec3 at, String what) {
        Minecraft client = Minecraft.getInstance();
        String where = NovaNotify.where(client.player.position(), at);
        if (!cooldown(sense.name(), 3000L)) {
            return;
        }
        String tag = sense == Sense.SOUND ? "SOUND" : "PARTICLE";
        DETECTIONS.put("#" + tag, new Detection(where, tag, what, Util.getMillis() + WEAK_TTL_MS, false));
        addHint(sense, weight, where, null);
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static void addHint(Sense sense, int weight, String who, UUID uuid) {
        long now = Util.getMillis();
        HINTS.add(new Hint(sense, weight, who, inRegion(uuid) ? uuid : null, now));
        while (!HINTS.isEmpty() && now - HINTS.peekFirst().at() > HINT_WINDOW_MS) {
            HINTS.removeFirst();
        }
        // staerkster Hinweis je Art zaehlt; mindestens zwei Arten und einer davon von jemandem aus deiner Region
        Map<Sense, Integer> strongest = new EnumMap<>(Sense.class);
        Hint located = null;
        for (Hint hint : HINTS) {
            strongest.merge(hint.sense(), hint.weight(), Math::max);
            if (hint.region() != null) {
                located = hint;
            }
        }
        int score = 0;
        for (int value : strongest.values()) {
            score += value;
        }
        if (located != null && strongest.size() >= 2 && score >= ALARM_SCORE) {
            List<String> senses = new ArrayList<>();
            for (Sense s : strongest.keySet()) {
                senses.add(s.label);
            }
            watchingYou(located.who(), String.join(" + ", senses));
        }
    }

    /** Der grosse Alarm. true = ausgeloest (dann keinen zweiten Ton spielen). */
    private static boolean watchingYou(String name, String detail) {
        long now = Util.getMillis();
        if (!watchAlarm || now - lastAlarm < ALARM_COOLDOWN_MS) {
            return false;
        }
        lastAlarm = now;
        alarmUntil = now + ALARM_SHOW_MS;
        alarmName = name;
        announce(name, "watching you");
        return true;
    }

    public static void onSystemChat(Component content) {
        if (!enabled || content == null) {
            return;
        }
        if (content.getContents() instanceof TranslatableContents translated && "multiplayer.player.left".equals(translated.getKey())) {
            serverSendsLeaveMessages = true;
        }
        String text = content.getString();
        if (!text.isBlank()) {
            long now = Util.getMillis();
            MESSAGES.add(new Message(text, now));
            while (!MESSAGES.isEmpty() && (now - MESSAGES.peekFirst().at() > LEAVE_MESSAGE_MS || MESSAGES.size() > 64)) {
                MESSAGES.removeFirst();
            }
            for (String name : KNOWN.values()) {
                if (looksLikeLeave(text, name)) {
                    serverSendsLeaveMessages = true;
                    PENDING.removeIf(p -> p.name().equalsIgnoreCase(name));
                }
            }
        }
    }

    /** Netzwerk-Thread: Antwort auf die eigene Abfrage abfangen (true = nicht an Minecraft weitergeben). */
    public static boolean consumeSuggestions(ClientboundCommandSuggestionsPacket packet) {
        if (!PROBE_IDS.remove(packet.id())) {
            return false;
        }
        List<String> names = new ArrayList<>();
        for (Suggestion suggestion : packet.toSuggestions().getList()) {
            String text = suggestion.getText();
            if (text != null && !text.isBlank()) {
                names.add(text.trim());
            }
        }
        probeAnswer = names;
        return true;
    }

    // ---------------------------------------------------------------- Leave-Nachrichten

    private static String normalize(String text) {
        return (" " + text.replaceAll("§.", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", " ") + " ")
                .replaceAll(" +", " ");
    }

    private static boolean looksLikeLeave(String message, String name) {
        if (name == null) {
            return false;
        }
        String text = normalize(message);
        String player = " " + name.toLowerCase(Locale.ROOT) + " ";
        if (!text.contains(player)) {
            return false;
        }
        String raw = message.replaceAll("§.", "").trim();
        return text.contains(player + "left") || text.contains(player + "has left") || text.contains(player + "quit")
                || text.contains(player + "has quit") || text.contains(player + "disconnected")
                || text.contains(player + "logged out") || text.contains(player + "hat den server verlassen")
                || raw.startsWith("[-]") || raw.startsWith("- ") || raw.startsWith("« ") || raw.startsWith("<- ");
    }

    private static boolean recentlyLeft(String name) {
        long now = Util.getMillis();
        for (Message message : MESSAGES) {
            if (now - message.at() <= LEAVE_MESSAGE_MS && looksLikeLeave(message.text(), name)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Tick

    @Override
    public void execute(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (client.player == null || client.level == null || connection == null) {
            return;
        }
        ticks++;
        long now = Util.getMillis();
        if (client.level != lastLevel) {
            // neue Welt/Dimension: der Server schickt die Teams neu
            lastLevel = client.level;
            TEAM_MOVES.clear();
            teamsQuietUntil = now + TEAM_QUIET_MS;
        }
        if (client.player.containerMenu != client.player.inventoryMenu) {
            lastOwnContainer = now;
        }
        judgeTeamMoves(connection, now);
        if (ticks % 10 == 0) {
            trackListed(client);
            // sichtbare Koerper beweisen "in deiner Region"
            for (Player player : client.level.players()) {
                if (player != client.player && watched(player.getGameProfile().name())) {
                    REGION.put(player.getUUID(), now);
                }
            }
        }
        // Aus der Tab-Liste verschwunden: nach einer Sekunde nachsehen
        while (!PENDING.isEmpty() && PENDING.peekFirst().dueTick() <= ticks) {
            Pending pending = PENDING.removeFirst();
            if (connection.getPlayerInfo(pending.uuid()) != null || recentlyLeft(pending.name())) {
                continue;
            }
            Player body = client.level.getPlayerByUUID(pending.uuid());
            if (body != null && !body.isRemoved()) {
                report(pending.name(), "VANISH", "off TAB, still here (" + Math.round(body.distanceTo(client.player)) + "m)", true);
            } else if (serverSendsLeaveMessages) {
                report(pending.name(), "VANISH", "left TAB without leave message", true);
            } else if (inRegion(pending.uuid())) {
                // ohne Leave-Nachrichten kein Beweis, aber ein Hinweis fuer den Alarm
                addHint(Sense.VANISH, 30, pending.name(), pending.uuid());
            }
        }
        if (probe && ticks % PROBE_TICKS == 0) {
            sendProbe(connection);
        }
        List<String> answer = probeAnswer;
        if (answer != null) {
            probeAnswer = null;
            checkProbe(client, connection, answer);
        }
        if (invisible && ticks % 5 == 0) {
            for (Player player : client.level.players()) {
                checkInvisible(client, player);
            }
        }
        DETECTIONS.values().removeIf(d -> d.expires < now);
        if (ticks % 100 == 0) {
            OWN_BLOCKS.values().removeIf(until -> until <= now);
            REDSTONE.values().removeIf(until -> until <= now);
            WEAK_PARTICLES.values().removeIf(at -> now - at > 2000L);
            REGION.values().removeIf(at -> now - at > REGION_MS);
            COOLDOWNS.values().removeIf(at -> now - at > 60_000L);
            TAB_MOVES.values().removeIf(at -> now - at > 10_000L);
            if (RANKS.size() > 4096) {
                RANKS.clear();
                readRanks(client);
            }
        }
    }

    // ---------------------------------------------------------------- Shard-Wache

    /** Vorgemerkte Team-Aenderungen nach TEAM_HOLD_MS bewerten. */
    private static void judgeTeamMoves(ClientPacketListener connection, long now) {
        while (!TEAM_CHANGES.isEmpty() && now - TEAM_CHANGES.peekFirst() > 5000L) {
            TEAM_CHANGES.removeFirst();
        }
        while (!TEAM_MOVES.isEmpty() && now - TEAM_MOVES.peekFirst().at() >= TEAM_HOLD_MS) {
            TeamMove move = TEAM_MOVES.removeFirst();
            // Massen-Aenderung (Serverwechsel) oder normaler Join/Leave mit Tab-Eintrag: keine Bewegung zu dir
            if (burst(move.at()) || tabMoved(move.name(), move.at()) || !watched(move.name())) {
                continue;
            }
            if (move.arrived()) {
                arrived(connection, move.name(), now);
            } else {
                departed(move.name());
            }
        }
    }

    private static boolean burst(long at) {
        int together = 0;
        for (long change : TEAM_CHANGES) {
            if (Math.abs(change - at) <= TEAM_BURST_MS && ++together >= TEAM_BURST) {
                return true;
            }
        }
        return false;
    }

    private static boolean tabMoved(String name, long at) {
        Long moved = TAB_MOVES.get(name.toLowerCase(Locale.ROOT));
        return moved != null && Math.abs(moved - at) <= TEAM_HOLD_MS;
    }

    /** Team angelegt, ohne dass jemand in die Tab-Liste kam: der Spieler ist gerade in deinen Teil der Welt gekommen. */
    private static void arrived(ClientPacketListener connection, String name, long now) {
        PlayerInfo info = connection.getPlayerInfoIgnoreCase(name);
        UUID uuid = info != null ? info.getProfile().id() : uuidOf(name);
        if (uuid != null) {
            REGION.put(uuid, now);
            AdminListMod.provenHere(uuid, now);
        }
        Long last = ARRIVALS.put(name.toLowerCase(Locale.ROOT), now);
        if (uuid == null || !LISTED_SINCE.containsKey(uuid)) {
            // nicht in der Tab-Liste und trotzdem da: versteckt
            report(name, "SHARD", "on your shard, not in TAB", true);
        } else if (last == null || now - last >= ARRIVAL_REPEAT_MS) {
            NovaNotify.chat(TAG, "§f" + name + " §7is now on your shard");
        }
    }

    /** Team geloescht: der Spieler hat deinen Teil der Welt verlassen. */
    private static void departed(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (ARRIVALS.remove(key) != null) {
            NovaNotify.chat(TAG, "§f" + name + " §7left your shard");
        }
        Detection d = DETECTIONS.get(key);
        if (d != null && "SHARD".equals(d.tag)) {
            DETECTIONS.remove(key);
        }
    }

    /** Tab-Liste mitschreiben; wer zurueck ist, ist wieder sichtbar. */
    private static void trackListed(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return;
        }
        long now = Util.getMillis();
        Set<UUID> listed = new HashSet<>();
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            UUID uuid = info.getProfile().id();
            if (uuid == null || self(uuid)) {
                continue;
            }
            listed.add(uuid);
            LISTED_SINCE.putIfAbsent(uuid, now);
            String name = info.getProfile().name();
            if (name != null) {
                KNOWN.put(uuid, name);
                Detection d = DETECTIONS.get(name.toLowerCase(Locale.ROOT));
                if (d != null && d.hidden && !"SPECTATOR".equals(d.tag)) {
                    visibleAgain(name, "back in TAB");
                }
            }
        }
        LISTED_SINCE.keySet().retainAll(listed);
        if (KNOWN.size() > 2048) {
            Set<UUID> online = new HashSet<>();
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                online.add(info.getProfile().id());
            }
            KNOWN.keySet().retainAll(online);
        }
    }

    private static void sendProbe(ClientPacketListener connection) {
        int id = nextProbeId++;
        if (nextProbeId > 40000) {
            nextProbeId = 30000;
        }
        PROBE_IDS.add(id);
        while (PROBE_IDS.size() > 8) {
            PROBE_IDS.remove(PROBE_IDS.iterator().next());
        }
        connection.send(new ServerboundCommandSuggestionPacket(id, PROBE));
    }

    private static void checkProbe(Minecraft client, ClientPacketListener connection, List<String> names) {
        Set<String> online = new HashSet<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            if (info.getProfile().name() != null) {
                online.add(info.getProfile().name().toLowerCase(Locale.ROOT));
            }
        }
        String own = client.player.getGameProfile().name();
        List<String> hidden = new ArrayList<>();
        for (String name : names) {
            if (watched(name) && !name.equalsIgnoreCase(own) && !online.contains(name.toLowerCase(Locale.ROOT)) && !recentlyLeft(name)) {
                hidden.add(name);
            }
        }
        if (!hidden.isEmpty() && hidden.size() <= MAX_BATCH) {
            for (String name : hidden) {
                report(name, "HIDDEN", "off TAB, still messageable", true);
            }
        }
    }

    private static boolean cooldown(String key, long ms) {
        long now = Util.getMillis();
        Long last = COOLDOWNS.get(key);
        if (last != null && now - last < ms) {
            return false;
        }
        COOLDOWNS.put(key, now);
        return true;
    }

    // ---------------------------------------------------------------- Melden

    private static void report(String name, String tag, String detail, boolean hidden) {
        String key = name.toLowerCase(Locale.ROOT);
        long now = Util.getMillis();
        Detection existing = DETECTIONS.get(key);
        boolean fresh = existing == null || !existing.tag.equals(tag);
        if (existing == null) {
            DETECTIONS.put(key, new Detection(name, tag, detail, now + (hidden ? HIDDEN_TTL_MS : INVISIBLE_TTL_MS), hidden));
        } else {
            existing.tag = tag;
            existing.detail = detail;
            existing.hidden |= hidden;
            existing.expires = Math.max(existing.expires, now + (hidden ? HIDDEN_TTL_MS : INVISIBLE_TTL_MS));
        }
        if (!fresh) {
            return;
        }
        // Versteckt und nachweislich bei dir (Koerper da / Spielmodus-Paket): Alarm "watching you"
        if (hidden && inRegion(uuidOf(name)) && watchingYou(name, label(tag))) {
            return;
        }
        announce(name, seen(tag));
    }

    /** Die Erscheinung "NAME is here" passend zum Design (ist sie aus, nur das Klingeln). */
    private static void announce(String name, String detail) {
        if (alert) {
            VanishAlert.show(name, detail, sound);
        } else if (sound) {
            VanishAlert.ring();
        }
    }

    /** Wieder in der Tab-Liste: still aus der Liste nehmen. */
    private static void visibleAgain(String name, String detail) {
        DETECTIONS.remove(name.toLowerCase(Locale.ROOT));
    }

    /** Kurz fuer die Zeile unter "NAME is here". */
    private static String seen(String tag) {
        return switch (tag) {
            case "SPECTATOR" -> "spectator mode";
            case "INVIS" -> "invisible";
            case "HIDDEN" -> "hidden from tab";
            case "SHARD" -> "on your shard";
            default -> "vanished";
        };
    }

    private static String label(String tag) {
        return switch (tag) {
            case "SPECTATOR" -> "is in spectator";
            case "INVIS" -> "is invisible";
            case "HIDDEN" -> "is hidden";
            case "SHARD" -> "is on your shard";
            default -> "vanished";
        };
    }

    /** Art des Funds rechts in der Liste (klein, grau). */
    private static String kind(String tag) {
        return switch (tag) {
            case "ALERT" -> "watching you";
            case "SPECTATOR" -> "spectator";
            case "INVIS" -> "invisible";
            case "HIDDEN" -> "hidden";
            case "SHARD" -> "on your shard";
            case "SOUND" -> "ghost sound";
            case "PARTICLE" -> "ghost particle";
            default -> "vanished";
        };
    }

    // ---------------------------------------------------------------- Zeichnen

    /**
     * Kleine Liste der aktuellen Funde am linken Rand: je Fund eine Nova-HUD-Karte mit Zustands-Punkt (rot = versteckt
     * oder Alarm, gelb = blosser Hinweis), Name weiss, Art rechts grau.
     */
    public static void renderHud(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        tickPreview();
        boolean preview = previewing();
        boolean alarm = Util.getMillis() < alarmUntil;
        if (!enabled && !preview || !hudList || DETECTIONS.isEmpty() && !alarm && !preview || client.player == null
                || client.getDebugOverlay().showDebugScreen()) {
            return;
        }
        Font font = client.font;
        List<Detection> rows = new ArrayList<>(enabled ? DETECTIONS.values() : List.of());
        rows.sort(Comparator.comparing((Detection d) -> !d.hidden).thenComparing(d -> d.name, String.CASE_INSENSITIVE_ORDER));
        if (preview) {
            rows.add(0, new Detection(previewName, "SPECTATOR", "", 0L, true));
            if (previewAlarm) {
                rows.add(0, new Detection(previewName, "ALERT", "", 0L, true));
            }
        }
        if (alarm) {
            rows.add(0, new Detection(alarmName, "ALERT", "", alarmUntil, true));
        }
        if (rows.size() > MAX_ROWS) {
            rows = rows.subList(0, MAX_ROWS);
        }
        int nameW = 0;
        int kindW = 0;
        for (Detection d : rows) {
            nameW = Math.max(nameW, NovaGui.width(font, d.name));
            kindW = Math.max(kindW, NovaGui.width(font, kind(d.tag)));
        }
        int width = HudCards.DOT_TEXT + nameW + 12 + kindW + HudCards.PAD;
        int x0 = 6;
        int y0 = client.getWindow().getGuiScaledHeight() / 4;
        for (int i = 0; i < rows.size(); i++) {
            Detection d = rows.get(i);
            int y = y0 + HudCards.top(i);
            boolean alarmRow = "ALERT".equals(d.tag);
            String kind = kind(d.tag);
            HudCards.card(g, x0, y, x0 + width, d.hidden ? NovaGui.DANGER : HINT_DOT, 1.0F);
            NovaGui.text(g, font, d.name, x0 + HudCards.DOT_TEXT, y + HudCards.TEXT_Y,
                    alarmRow ? NovaGui.DANGER : NovaGui.TEXT, false);
            NovaGui.text(g, font, kind, x0 + width - HudCards.PAD - NovaGui.width(font, kind), y + HudCards.TEXT_Y,
                    NovaGui.TEXT_DIM, false);
        }
    }

    // ---------------------------------------------------------------- Vorschau

    private static boolean previewing() {
        return previewStart != 0L && Util.getMillis() - previewStart < PREVIEW_MS;
    }

    /** Menue schliessen und die Erscheinung vorspielen (dazu die Zeilen in der Liste: erst Spectator, dann Alarm). */
    private static void startPreview() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        client.gui.setScreen(null);
        previewName = AdminListMod.STAFF_NAMES.isEmpty() ? "Moderator" : AdminListMod.STAFF_NAMES.get(0);
        previewStart = Util.getMillis();
        previewAlarm = false;
        announce(previewName, seen("SPECTATOR"));
    }

    /** Laeuft jedes Bild ueber renderHud, damit die Vorschau auch bei ausgeschaltetem Modul weiterlaeuft. */
    private static void tickPreview() {
        if (!previewing() || previewAlarm || Util.getMillis() - previewStart < PREVIEW_ALARM_MS) {
            return;
        }
        previewAlarm = true;
    }

    // ---------------------------------------------------------------- Einstellungen

    private static String status() {
        if (!enabled) {
            return "Module is off";
        }
        Collection<Detection> all = DETECTIONS.values();
        return all.isEmpty() ? "Nobody hidden right now" : all.size() + " hidden / invisible";
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Toggle("Only staff (Adminlist names)", () -> staffOnly, v -> staffOnly = v),
                new ModOptionsMenu.Text("Extra names (a, b, ...)", () -> extraNames, v -> extraNames = v, 256),
                new ModOptionsMenu.Toggle("Messageable check (/msg probe)", () -> probe, v -> probe = v),
                new ModOptionsMenu.Toggle("Invisible players", () -> invisible, v -> invisible = v),
                new ModOptionsMenu.Toggle("Ghost sounds (doors, chests ...)", () -> ghostSounds, v -> ghostSounds = v),
                new ModOptionsMenu.Toggle("Ghost particles (hits, smoke ...)", () -> ghostParticles, v -> ghostParticles = v),
                new ModOptionsMenu.Toggle("\"Watching you\" alarm", () -> watchAlarm, v -> watchAlarm = v),
                new ModOptionsMenu.Toggle("Announce gamemode switches", () -> gameModes, v -> gameModes = v),
                new ModOptionsMenu.Toggle("Shard watch (DonutSMP teams)", () -> shardWatch, v -> shardWatch = v),
                new ModOptionsMenu.Toggle("Ranks 00-08 count as staff", () -> rankStaff, v -> rankStaff = v),
                new ModOptionsMenu.Action(() -> "Display (alert, sound, list) ...", () -> {
                    Minecraft client = Minecraft.getInstance();
                    client.gui.setScreen(createDisplaySettings(client.gui.screen()));
                }),
                new ModOptionsMenu.Action(() -> Minecraft.getInstance().player == null
                        ? "Preview spectator alert (join a world)" : "Preview spectator alert", AntiVanishMod::startPreview),
                new ModOptionsMenu.Info(AntiVanishMod::status)));
    }

    private Screen createDisplaySettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Display", List.of(
                new ModOptionsMenu.Toggle("Big alert (\"NAME is here\")", () -> alert, v -> alert = v),
                new ModOptionsMenu.Toggle("Sound (cave ringing)", () -> sound, v -> sound = v),
                new ModOptionsMenu.Toggle("List on screen", () -> hudList, v -> hudList = v),
                new ModOptionsMenu.Action(() -> Minecraft.getInstance().player == null
                        ? "Preview spectator alert (join a world)" : "Preview spectator alert", AntiVanishMod::startPreview)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "staffOnly", String.valueOf(staffOnly));
        properties.setProperty(prefix + "extraNames", extraNames);
        properties.setProperty(prefix + "probe", String.valueOf(probe));
        properties.setProperty(prefix + "invisible", String.valueOf(invisible));
        properties.setProperty(prefix + "alert", String.valueOf(alert));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
        properties.setProperty(prefix + "hudList", String.valueOf(hudList));
        properties.setProperty(prefix + "ghostSounds", String.valueOf(ghostSounds));
        properties.setProperty(prefix + "ghostParticles", String.valueOf(ghostParticles));
        properties.setProperty(prefix + "watchAlarm", String.valueOf(watchAlarm));
        properties.setProperty(prefix + "gameModes", String.valueOf(gameModes));
        properties.setProperty(prefix + "shardWatch", String.valueOf(shardWatch));
        properties.setProperty(prefix + "rankStaff", String.valueOf(rankStaff));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        staffOnly = Boolean.parseBoolean(properties.getProperty(prefix + "staffOnly", String.valueOf(staffOnly)));
        extraNames = properties.getProperty(prefix + "extraNames", extraNames);
        probe = Boolean.parseBoolean(properties.getProperty(prefix + "probe", String.valueOf(probe)));
        invisible = Boolean.parseBoolean(properties.getProperty(prefix + "invisible", String.valueOf(invisible)));
        // aeltere Configs kennen nur "toasts"
        alert = Boolean.parseBoolean(properties.getProperty(prefix + "alert",
                properties.getProperty(prefix + "toasts", String.valueOf(alert))));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
        hudList = Boolean.parseBoolean(properties.getProperty(prefix + "hudList", String.valueOf(hudList)));
        ghostSounds = Boolean.parseBoolean(properties.getProperty(prefix + "ghostSounds", String.valueOf(ghostSounds)));
        ghostParticles = Boolean.parseBoolean(properties.getProperty(prefix + "ghostParticles", String.valueOf(ghostParticles)));
        watchAlarm = Boolean.parseBoolean(properties.getProperty(prefix + "watchAlarm", String.valueOf(watchAlarm)));
        gameModes = Boolean.parseBoolean(properties.getProperty(prefix + "gameModes", String.valueOf(gameModes)));
        shardWatch = Boolean.parseBoolean(properties.getProperty(prefix + "shardWatch", String.valueOf(shardWatch)));
        rankStaff = Boolean.parseBoolean(properties.getProperty(prefix + "rankStaff", String.valueOf(rankStaff)));
    }

    /** Eigener Armschwung (1.21.11 schickt ihn als Paket; 26.3 leitet der Server ihn aus Angriff/Benutzen ab). */
    private static boolean isSwingPacket(Packet<?> packet) {
        //? if >=26.1 {
        return false;
        //?} else {
        /*return packet instanceof net.minecraft.network.protocol.game.ServerboundSwingPacket;
        *///?}
    }
}
