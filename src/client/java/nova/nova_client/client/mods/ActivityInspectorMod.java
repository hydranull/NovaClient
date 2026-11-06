package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ChunkOverlayRenderer;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.activityinspector.DiscordWebhook;
import nova.nova_client.client.activityinspector.SkyLightSections;
import nova.nova_client.client.activityinspector.SkyLightSections.Status;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Activity Inspector: liest die Himmelslicht-Daten aus den Chunk- und Licht-Paketen und meldet Chunks, in denen
 * unter Y=62 eine Sektion mit explizit mitgeschicktem, komplett dunklem Licht-Array liegt ({@link SkyLightSections}).
 * So eine Sektion entsteht nur, wenn der Server dort das Licht neu berechnet hat - typisch fuer Spawner-Raeume und
 * versteckte Bases. Funde werden als rote Platte auf Y=64 markiert.
 * <p>
 * Dazu ein RTP-Loop (/rtp mit den gewaehlten Regionen im Wechsel). Bei einem Fund stoppt er und meldet sich per Toast - oder schickt im Crown-Modus /tpahere an einen zweiten Account und macht nach einer
 * Wartezeit weiter.
 * Steuerung auch per Chat: /artp start|stop|status.
 */
public class ActivityInspectorMod extends GameTickMod {
    private static final String TAG = "Activity Inspector";
    public static final String[] BOX_STYLES = {"Box", "Plate"};
    /** Rot der Markierung (255, 40, 40). */
    private static final int COLOR = 0xFF2828;
    private static final int MARKER_Y = 64;
    private static final int RENDER_CHUNKS = 16;
    /** Chunks weiter weg werden vergessen. */
    private static final int KEEP_CHUNKS = 32;
    /** Nur Sektionen, deren Oberkante unter dieser Hoehe liegt, zaehlen als Base-Signal. */
    private static final int BASE_CEILING_Y = 62;
    private static final int ALERT_MIN_Y = -64;
    private static final int ALERT_MAX_Y = 184;
    private static final int MAX_PENDING = 2048;
    private static final int MAX_DRAIN_PER_TICK = 4096;
    /** Gemerkte Funde: so viele Welten (Server + Dimension), pro Welt so viele Chunks. */
    private static final int MAX_WORLDS = 8;
    private static final int MAX_MARKERS_PER_WORLD = 1024;
    private static final String[][] REGIONS = {
            {"East", "rtp east"}, {"West", "rtp west"}, {"EU West", "rtp eu west"},
            {"EU Central", "rtp eu central"}, {"Asia", "rtp asia"}, {"Oceania", "rtp oceania"}};

    // Einstellungen
    public static int boxStyle = 0;
    public static int opacity = 140;
    public static boolean toastOnSpotted = true;
    public static boolean chatOnSpotted = true;
    public static boolean showCoordinates = true;
    public static final boolean[] rtpRegions = new boolean[REGIONS.length];
    /** Ticks zwischen zwei /rtp. */
    public static int rtpDelay = 40;
    public static boolean crownEnabled = false;
    public static String crownUsername = "";
    public static boolean crownFeedback = true;
    /** Sekunden nach /tpahere, bis der RTP-Loop weiterlaeuft (0 = an der Base bleiben). */
    public static int crownTimeout = 20;
    /** Discord-Webhook fuer Funde ("" = aus). */
    public static String webhookUrl = "";

    private enum CrownState {IDLE, WAITING_CONFIRMATION, COUNTDOWN}

    private record Pending(int chunkX, int chunkZ, Status[] sky) {
    }

    /** Zusammengefuehrter Lichtstand eines Chunks (Licht-Updates enthalten oft nur einige Sektionen). */
    private static final class Snapshot {
        final int sectionCount;
        final int minSectionY;
        final Status[] sky;

        Snapshot(int sectionCount, int minSectionY) {
            this.sectionCount = sectionCount;
            this.minSectionY = minSectionY;
            this.sky = new Status[sectionCount + 2];
        }
    }

    private static final Map<Long, Snapshot> SNAPSHOTS = new HashMap<>();
    private static final Set<Long> ALERTED = new HashSet<>();
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    /** Funde pro Welt, bleiben ueber Entladen und Aus-/Einschalten hinweg (aelteste fliegen zuerst raus). */
    private static final LinkedHashMap<String, LinkedHashSet<Long>> MARKERS = new LinkedHashMap<>(16, 0.75F, true);
    private static final ChunkOverlayRenderer OVERLAY = new ChunkOverlayRenderer();

    private static ActivityInspectorMod instance;
    private static LinkedHashSet<Long> currentMarkers;
    private static ClientLevel lastLevel;
    private static long dropped;

    private static boolean rtpRunning;
    private static int rtpTimer;
    private static int rtpIndex;
    private static CrownState crownState = CrownState.IDLE;
    private static int crownWaitTicks;
    private static int crownCountdownTicks;
    /** Funde fuer den Webhook sammeln (kommen oft mehrere Chunks gleichzeitig) und gebuendelt schicken. */
    private static final List<DiscordWebhook.Detection> WEBHOOK_PENDING = new ArrayList<>();
    private static boolean webhookRtpStopped;
    private static int webhookWaitTicks;
    private static final int WEBHOOK_BATCH_TICKS = 40;
    private static final int WEBHOOK_MAX_COORDS = 15;

    public ActivityInspectorMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    public static boolean isEnabled() {
        return instance != null && instance.active;
    }

    // ---------------------------------------------------------------- An/Aus

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (!WEBHOOK_PENDING.isEmpty()) {
            // beim Ausschalten noch nicht gesendete Funde sofort schicken
            webhookWaitTicks = 0;
            flushWebhook();
        }
        clearCache();
        dropped = 0;
        rtpRunning = false;
        rtpTimer = 0;
        rtpIndex = 0;
        resetCrown();
    }

    private static void clearCache() {
        SNAPSHOTS.clear();
        ALERTED.clear();
        PENDING.clear();
    }

    private static void resetCrown() {
        crownState = CrownState.IDLE;
        crownWaitTicks = 0;
        crownCountdownTicks = 0;
    }

    // ---------------------------------------------------------------- Pakete (Haupt-Thread)

    /** Chunk- oder Licht-Paket vom Server, bevor es angewendet wird. */
    public static void onLightData(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data) {
        if (!isEnabled() || data == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        syncWorld(client.level);
        if (client.level == null || client.player == null || client.player.getY() < 0.0D) {
            return;
        }
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.pollFirst();
            dropped++;
        }
        // Erst im Tick auswerten: beim Chunk-Paket ist der Chunk hier noch nicht geladen
        PENDING.addLast(new Pending(chunkX, chunkZ, SkyLightSections.classify(data, client.level.getSectionsCount())));
    }

    public static void onChunkUnloaded(ChunkPos pos) {
        if (!isEnabled()) {
            return;
        }
        long key = ChunkPos.pack(Compat.chunkX(pos), Compat.chunkZ(pos));
        SNAPSHOTS.remove(key);
        ALERTED.remove(key);
        PENDING.removeIf(p -> p.chunkX() == Compat.chunkX(pos) && p.chunkZ() == Compat.chunkZ(pos));
    }

    /** Neue Welt: Cache leeren und die Funde dieser Welt (Server + Dimension) waehlen. */
    private static void syncWorld(ClientLevel level) {
        if (level == lastLevel) {
            return;
        }
        clearCache();
        lastLevel = level;
        if (level == null) {
            currentMarkers = null;
            return;
        }
        String world = NovaNotify.serverId() + "|" + level.dimension().identifier();
        currentMarkers = MARKERS.computeIfAbsent(world, ignored -> new LinkedHashSet<>());
        if (MARKERS.size() > MAX_WORLDS) {
            Iterator<String> eldest = MARKERS.keySet().iterator();
            eldest.next();
            eldest.remove();
        }
    }

    private static void remember(long key) {
        if (currentMarkers == null) {
            return;
        }
        // nach hinten schieben = zuletzt gesehen
        if (!currentMarkers.remove(key) && currentMarkers.size() >= MAX_MARKERS_PER_WORLD) {
            Iterator<Long> eldest = currentMarkers.iterator();
            eldest.next();
            eldest.remove();
        }
        currentMarkers.add(key);
    }

    private static boolean isMarked(long key) {
        return currentMarkers != null && currentMarkers.contains(key);
    }

    // ---------------------------------------------------------------- Ablauf

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        syncWorld(level);
        if (level == null || player == null) {
            return;
        }
        scanLight(level, player);
        flushWebhook();
        tickCrown(client);
        tickRtp(client);
    }

    private void scanLight(ClientLevel level, LocalPlayer player) {
        if (player.getY() < 0.0D) {
            if (!SNAPSHOTS.isEmpty() || !PENDING.isEmpty()) {
                clearCache();
            }
            return;
        }
        int playerCx = Compat.chunkX(player.chunkPosition());
        int playerCz = Compat.chunkZ(player.chunkPosition());
        Iterator<Map.Entry<Long, Snapshot>> it = SNAPSHOTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Snapshot> entry = it.next();
            ChunkPos pos = Compat.chunkPos(entry.getKey());
            if (!withinKeep(Compat.chunkX(pos), Compat.chunkZ(pos), playerCx, playerCz) || !level.hasChunk(Compat.chunkX(pos), Compat.chunkZ(pos))) {
                ALERTED.remove(entry.getKey());
                it.remove();
            }
        }
        int sectionCount = level.getSectionsCount();
        int minSectionY = level.getMinSectionY();
        for (int i = 0; i < MAX_DRAIN_PER_TICK; i++) {
            Pending pending = PENDING.pollFirst();
            if (pending == null) {
                break;
            }
            if (withinKeep(pending.chunkX(), pending.chunkZ(), playerCx, playerCz)
                    && level.hasChunk(pending.chunkX(), pending.chunkZ())) {
                ingest(pending, sectionCount, minSectionY);
            }
        }
    }

    private static boolean withinKeep(int cx, int cz, int playerCx, int playerCz) {
        return Math.abs(cx - playerCx) <= KEEP_CHUNKS && Math.abs(cz - playerCz) <= KEEP_CHUNKS;
    }

    private void ingest(Pending pending, int sectionCount, int minSectionY) {
        long key = ChunkPos.pack(pending.chunkX(), pending.chunkZ());
        Snapshot snap = SNAPSHOTS.get(key);
        if (snap == null || snap.sectionCount != sectionCount || snap.minSectionY != minSectionY) {
            snap = new Snapshot(sectionCount, minSectionY);
            SNAPSHOTS.put(key, snap);
        }
        Status[] update = pending.sky();
        for (int i = 0; i < Math.min(update.length, snap.sky.length); i++) {
            if (update[i] != null) {
                snap.sky[i] = update[i];
            }
        }
        if (!ALERTED.contains(key) && !isMarked(key) && hasBaseSignal(snap)) {
            ALERTED.add(key);
            remember(key);
            onChunkSpotted(pending.chunkX(), pending.chunkZ(), snap);
        }
    }

    /** Eine Sektion unter Y=62 (im Bereich -64..184), fuer die ein komplett dunkles Licht-Array kam. */
    private static boolean hasBaseSignal(Snapshot snap) {
        // Index 0 ist die Sektion unter der Welt, Index i gehoert zu Sektion minSectionY + i - 1
        for (int i = 1; i <= snap.sectionCount; i++) {
            int bottomY = (snap.minSectionY + i - 1) * 16;
            int topY = bottomY + 15;
            if (topY >= ALERT_MIN_Y && bottomY <= ALERT_MAX_Y && topY < BASE_CEILING_Y && snap.sky[i] == Status.ZEROED) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Toasts (gleicher Stil wie Advanced Stash Finder)

    private static final int MAX_TOASTS = 4;
    private static final long TOAST_LIFE_MS = 6000L;
    private static final java.util.List<Toast> TOASTS = new java.util.ArrayList<>();

    private record Toast(net.minecraft.core.BlockPos center, boolean rtpStopped, long created) {
    }

    /** Toasts rechts oben (jeden Frame vom HUD). */
    public static void renderToasts(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (TOASTS.isEmpty() || client.player == null) {
            return;
        }
        net.minecraft.client.gui.Font font = client.font;
        long now = net.minecraft.util.Util.getMillis();
        java.util.List<Toast> toasts;
        synchronized (TOASTS) {
            TOASTS.removeIf(t -> now - t.created() > TOAST_LIFE_MS);
            toasts = java.util.List.copyOf(TOASTS);
        }
        int y = nova.nova_client.client.NovaToasts.top();
        for (Toast toast : toasts) {
            float in = NovaGui.easeOutCubic((now - toast.created()) / 280.0F);
            float out = net.minecraft.util.Mth.clamp((TOAST_LIFE_MS - (now - toast.created())) / 280.0F, 0.0F, 1.0F);
            float show = in * out;
            // Wo: Entfernung + Koordinaten
            net.minecraft.core.BlockPos player = client.player.blockPosition();
            double dx = toast.center().getX() - player.getX();
            double dz = toast.center().getZ() - player.getZ();
            int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            String where = distance + "m  ·  " + toast.center().getX() + " " + toast.center().getZ();
            nova.nova_client.client.NovaToasts.card(g, font,
                    nova.nova_client.client.NovaToasts.slideX(nova.nova_client.client.NovaToasts.CARD_W, show), y, show,
                    "Base detected", toast.rtpStopped() ? "RTP stopped" : "activity", where, java.util.List.of());
            y += nova.nova_client.client.NovaToasts.CARD_H + nova.nova_client.client.NovaToasts.GAP;
        }
        nova.nova_client.client.NovaToasts.setTop(y);
    }

    private void onChunkSpotted(int chunkX, int chunkZ, Snapshot snap) {
        Minecraft client = Minecraft.getInstance();
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;
        if (chatOnSpotted) {
            NovaNotify.chat(TAG, showCoordinates ? "§cBase detected: §f" + centerX + ", " + centerZ : "§cBase detected");
        }
        boolean wasRtp = rtpRunning;
        rtpRunning = false;
        rtpTimer = 0;
        // Beim RTP ohne Crown-Account immer ein Toast (statt Disconnect), sonst nur wenn eingeschaltet
        boolean rtpStop = wasRtp && !crownEnabled;
        if (toastOnSpotted || rtpStop) {
            synchronized (TOASTS) {
                TOASTS.add(0, new Toast(new net.minecraft.core.BlockPos(centerX, 64, centerZ), rtpStop,
                        net.minecraft.util.Util.getMillis()));
                while (TOASTS.size() > MAX_TOASTS) {
                    TOASTS.remove(TOASTS.size() - 1);
                }
            }
        }
        if (crownEnabled) {
            if (crownState != CrownState.IDLE) {
                return;
            }
            if (crownUsername.isBlank()) {
                crownInfo("§eBase detected, RTP stopped. Crown username is empty!");
                return;
            }
            if (wasRtp) {
                crownInfo("§eBase detected! Stopped RTP immediately.");
            }
            crownInfo("§7Waiting 1.0s to confirm chunk and settle position before /tpahere...");
            crownState = CrownState.WAITING_CONFIRMATION;
            crownWaitTicks = 20;
        } else if (wasRtp) {
            NovaNotify.chat(TAG, "§cBase detected! RTP stopped.");
        }
        if (!webhookUrl.isBlank()) {
            queueWebhook(describe(client, chunkX, chunkZ, snap), wasRtp);
        }
    }

    // ---------------------------------------------------------------- Discord-Webhook

    private static void queueWebhook(DiscordWebhook.Detection detection, boolean rtpStopped) {
        if (WEBHOOK_PENDING.isEmpty()) {
            webhookWaitTicks = WEBHOOK_BATCH_TICKS;
            webhookRtpStopped = false;
        }
        if (WEBHOOK_PENDING.size() < WEBHOOK_MAX_COORDS) {
            WEBHOOK_PENDING.add(detection);
        }
        webhookRtpStopped |= rtpStopped;
    }

    /** Was genau angeschlagen hat: dunkle Sektionen, sichtbare Block-Entities im Chunk, Entfernung. */
    private static DiscordWebhook.Detection describe(Minecraft client, int chunkX, int chunkZ, Snapshot snap) {
        int centerX = (chunkX << 4) + 8;
        int centerZ = (chunkZ << 4) + 8;

        // Signal: zusammenhaengende dunkle Sektionen als Hoehenbereiche
        List<String> ranges = new ArrayList<>();
        int sections = 0;
        int rangeStart = Integer.MIN_VALUE;
        int rangeEnd = 0;
        for (int i = 1; i <= snap.sectionCount; i++) {
            int bottomY = (snap.minSectionY + i - 1) * 16;
            int topY = bottomY + 15;
            boolean dark = topY >= ALERT_MIN_Y && bottomY <= ALERT_MAX_Y && topY < BASE_CEILING_Y
                    && snap.sky[i] == Status.ZEROED;
            if (dark) {
                sections++;
                if (rangeStart == Integer.MIN_VALUE) {
                    rangeStart = bottomY;
                }
                rangeEnd = topY;
            } else if (rangeStart != Integer.MIN_VALUE) {
                ranges.add("Y " + rangeStart + " to " + rangeEnd);
                rangeStart = Integer.MIN_VALUE;
            }
        }
        if (rangeStart != Integer.MIN_VALUE) {
            ranges.add("Y " + rangeStart + " to " + rangeEnd);
        }
        String signal = "Built-up area (sky light 0) at " + String.join(", ", ranges) + " · " + sections
                + (sections == 1 ? " section" : " sections");

        // Was der Client in dem Chunk unter Y=62 sehen kann (Spawner, Kisten, ...)
        Map<String, Integer> counts = new HashMap<>();
        if (client.level != null) {
            LevelChunk chunk = client.level.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (chunk != null) {
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity.getBlockPos().getY() < BASE_CEILING_Y) {
                        counts.merge(blockEntityName(client, blockEntity), 1, Integer::sum);
                    }
                }
            }
        }
        String blocks;
        if (counts.isEmpty()) {
            blocks = "Nothing visible (hidden by the server)";
        } else {
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : sorted) {
                parts.add(entry.getKey() + " ×" + entry.getValue());
            }
            blocks = String.join(", ", parts);
        }

        String where = client.player == null ? "?"
                : NovaNotify.where(client.player.position(), new Vec3(centerX, client.player.getY(), centerZ)) + " from you";
        return new DiscordWebhook.Detection(centerX, centerZ, chunkX, chunkZ, signal, blocks, where);
    }

    private static String blockEntityName(Minecraft client, BlockEntity blockEntity) {
        if (blockEntity instanceof SpawnerBlockEntity spawner && client.level != null) {
            try {
                Entity mob = spawner.getSpawner().getOrCreateDisplayEntity(client.level, blockEntity.getBlockPos());
                if (mob != null) {
                    return "Spawner (" + mob.getType().getDescription().getString() + ")";
                }
            } catch (RuntimeException ignored) {
                // Spawner ohne Daten
            }
            return "Spawner";
        }
        return blockEntity.getBlockState().getBlock().getName().getString();
    }

    /** 2 Sekunden nach dem ersten Fund alle gesammelten Funde in einer Nachricht schicken. */
    private static void flushWebhook() {
        if (WEBHOOK_PENDING.isEmpty() || --webhookWaitTicks > 0) {
            return;
        }
        DiscordWebhook.sendBases(webhookUrl, List.copyOf(WEBHOOK_PENDING), webhookRtpStopped);
        WEBHOOK_PENDING.clear();
    }

    // ---------------------------------------------------------------- Crown-Account

    private static void crownInfo(String text) {
        if (crownFeedback) {
            NovaNotify.chat(TAG + "§d] [§6Crown", text);
        }
    }

    private void tickCrown(Minecraft client) {
        if (!crownEnabled) {
            resetCrown();
            return;
        }
        switch (crownState) {
            case IDLE -> {
            }
            case WAITING_CONFIRMATION -> {
                if (crownWaitTicks > 0) {
                    crownWaitTicks--;
                }
                if (crownWaitTicks > 0) {
                    return;
                }
                String user = crownUsername.trim();
                if (user.isEmpty()) {
                    crownInfo("§eCrown username is empty. Aborting /tpahere.");
                    crownState = CrownState.IDLE;
                    return;
                }
                crownInfo("§71s wait complete. Dispatching §f/tpahere " + user + "§7...");
                if (client.getConnection() != null) {
                    client.getConnection().sendCommand("tpahere " + user);
                }
                startCountdown();
            }
            case COUNTDOWN -> {
                if (crownCountdownTicks <= 0 || --crownCountdownTicks > 0) {
                    return;
                }
                crownState = CrownState.IDLE;
                if (crownTimeout > 0) {
                    crownInfo("§7Countdown finished! Repeating RTP cycle...");
                    rtpRunning = true;
                    rtpTimer = 0;
                    rtpIndex = 0;
                } else {
                    crownInfo("§7Holding position at base indefinitely.");
                }
            }
        }
    }

    private static void startCountdown() {
        crownInfo(crownTimeout > 0
                ? "§a/tpahere sent! §7Repeating RTP in §f" + crownTimeout + "s§7..."
                : "§a/tpahere sent! §7Holding position indefinitely (timeout is 0).");
        crownState = CrownState.COUNTDOWN;
        crownCountdownTicks = crownTimeout * 20;
    }

    // ---------------------------------------------------------------- RTP-Loop

    private static List<String> activeRtpCommands() {
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < REGIONS.length; i++) {
            if (rtpRegions[i]) {
                commands.add(REGIONS[i][1]);
            }
        }
        return commands;
    }

    private void tickRtp(Minecraft client) {
        if (!rtpRunning) {
            return;
        }
        List<String> commands = activeRtpCommands();
        if (commands.isEmpty()) {
            return;
        }
        if (rtpTimer > 0) {
            rtpTimer--;
            return;
        }
        if (rtpIndex >= commands.size()) {
            rtpIndex = 0;
        }
        if (client.getConnection() != null) {
            client.getConnection().sendCommand(commands.get(rtpIndex));
        }
        rtpIndex = (rtpIndex + 1) % commands.size();
        rtpTimer = rtpDelay;
    }

    public static void startRtp() {
        Minecraft client = Minecraft.getInstance();
        if (instance == null) {
            return;
        }
        if (!instance.active) {
            instance.toggle(client);
            if (!instance.active) {
                return; // z.B. kein gueltiger Key
            }
        }
        rtpRunning = true;
        rtpTimer = 0;
        rtpIndex = 0;
        resetCrown();
        NovaNotify.chat(TAG, "§7Starting RTP...");
        if (activeRtpCommands().isEmpty()) {
            NovaNotify.chat(TAG, "§eWarning: No RTP regions are enabled in Activity Inspector settings!");
        }
    }

    public static void stopRtp() {
        rtpRunning = false;
        rtpTimer = 0;
        rtpIndex = 0;
        resetCrown();
        NovaNotify.chat(TAG, "§7Stopped RTP.");
    }

    private static void printStatus() {
        List<String> commands = activeRtpCommands();
        NovaNotify.chat(TAG, "§7Active: " + (isEnabled() ? "§aYES" : "§cNO") + "§7, RTP Running: "
                + (rtpRunning ? "§aYES" : "§cNO") + "§7, Delay: §f" + rtpDelay + " ticks§7, Regions: "
                + (commands.isEmpty() ? "§cNone" : "§f/" + String.join(", /", commands)));
        NovaNotify.chat(TAG, "§7Cached chunks: §f" + SNAPSHOTS.size() + "§7, queued light packets: §f" + PENDING.size()
                + "§7, dropped packets: §f" + dropped);
    }

    /** "/artp [start|stop|status]" abfangen. command = ohne "/". true = abgefangen, nicht an den Server schicken. */
    public static boolean handleCommand(String command) {
        String text = command.trim();
        if (!text.equalsIgnoreCase("artp") && !text.toLowerCase(java.util.Locale.ROOT).startsWith("artp ")) {
            return false;
        }
        String args = text.substring(4).trim();
        if (args.isEmpty() || args.equalsIgnoreCase("start")) {
            startRtp();
        } else if (args.equalsIgnoreCase("stop")) {
            stopRtp();
        } else if (args.equalsIgnoreCase("status")) {
            printStatus();
        } else {
            NovaNotify.chat(TAG, "§7Usage: §f/artp [start|stop|status]");
        }
        return true;
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        LinkedHashSet<Long> marked = currentMarkers;
        if (!isEnabled() || client.level == null || client.player == null || client.level != lastLevel
                || marked == null || marked.isEmpty()) {
            return;
        }
        int playerCx = Compat.chunkX(client.player.chunkPosition());
        int playerCz = Compat.chunkZ(client.player.chunkPosition());
        List<Long> visible = new ArrayList<>();
        for (long key : marked) {
            ChunkPos pos = Compat.chunkPos(key);
            if (Math.abs(Compat.chunkX(pos) - playerCx) <= RENDER_CHUNKS && Math.abs(Compat.chunkZ(pos) - playerCz) <= RENDER_CHUNKS) {
                visible.add(key);
            }
        }
        if (visible.isEmpty()) {
            return;
        }
        int lineAlpha = Mth.clamp(opacity, 20, 255);
        int fillAlpha = Mth.clamp(opacity / 3, 8, 255);
        if (boxStyle == 1) {
            long[] keys = visible.stream().mapToLong(Long::longValue).toArray();
            OVERLAY.emit(keys, marked, MARKER_Y + 0.5D, COLOR, fillAlpha / 255.0F, lineAlpha / 255.0F);
            return;
        }
        GizmoStyle style = GizmoStyle.strokeAndFill((lineAlpha << 24) | COLOR, 2.0F, (fillAlpha << 24) | COLOR);
        net.minecraft.client.renderer.culling.Frustum view = nova.nova_client.client.ViewCull.frustum();
        for (long key : visible) {
            ChunkPos pos = Compat.chunkPos(key);
            double x = Compat.chunkX(pos) << 4;
            double z = Compat.chunkZ(pos) << 4;
            if (!nova.nova_client.client.ViewCull.visible(view, x, MARKER_Y, z, x + 16.0D, MARKER_Y + 1.0D, z + 16.0D, 2.0F)) {
                continue;
            }
            Gizmos.cuboid(new AABB(x, MARKER_Y, z, x + 16.0D, MARKER_Y + 1.0D, z + 16.0D), style).setAlwaysOnTop();
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        List<ModOptionsMenu.Option> options = new ArrayList<>(List.of(
                new ModOptionsMenu.Choice("Box style", BOX_STYLES, () -> boxStyle, v -> boxStyle = v),
                new ModOptionsMenu.Slider("Opacity", 10.0D, 255.0D, 1.0D, () -> opacity,
                        v -> opacity = (int) Math.round(v), v -> String.valueOf(Math.round(v))),
                new ModOptionsMenu.Toggle("Toast on spotted", () -> toastOnSpotted, v -> toastOnSpotted = v),
                new ModOptionsMenu.Toggle("Chat on spotted", () -> chatOnSpotted, v -> chatOnSpotted = v),
                new ModOptionsMenu.Toggle("Show coordinates in chat", () -> showCoordinates, v -> showCoordinates = v)));
        for (int i = 0; i < REGIONS.length; i++) {
            int index = i;
            options.add(new ModOptionsMenu.Toggle("RTP: /" + REGIONS[i][1], () -> rtpRegions[index],
                    v -> rtpRegions[index] = v));
        }
        options.addAll(List.of(
                new ModOptionsMenu.Slider("RTP delay", 0.0D, 200.0D, 1.0D, () -> rtpDelay,
                        v -> rtpDelay = (int) Math.round(v), v -> Math.round(v) + " ticks"),
                new ModOptionsMenu.Action(() -> rtpRunning ? "Stop RTP" : "Start RTP",
                        () -> {
                            if (rtpRunning) {
                                stopRtp();
                            } else {
                                startRtp();
                            }
                        }),
                new ModOptionsMenu.Toggle("Crown account (/tpahere on base found)", () -> crownEnabled,
                        v -> crownEnabled = v),
                new ModOptionsMenu.Text("Crown username", () -> crownUsername, v -> crownUsername = v.trim(), 16),
                new ModOptionsMenu.Toggle("Crown chat feedback", () -> crownFeedback, v -> crownFeedback = v),
                new ModOptionsMenu.Slider("Crown timeout", 0.0D, 120.0D, 1.0D, () -> crownTimeout,
                        v -> crownTimeout = (int) Math.round(v), v -> Math.round(v) == 0 ? "Stay" : Math.round(v) + " s"),
                new ModOptionsMenu.Text("Discord webhook URL", () -> webhookUrl, v -> webhookUrl = v.trim(), 256),
                new ModOptionsMenu.Action(() -> webhookUrl.isBlank() ? "Test webhook (no URL set)" : "Test webhook",
                        () -> {
                            if (!webhookUrl.isBlank()) {
                                DiscordWebhook.sendTest(webhookUrl);
                            }
                        }),
                new ModOptionsMenu.Action(() -> "Forget marked chunks ("
                        + (currentMarkers == null ? 0 : currentMarkers.size()) + ")", () -> {
                    if (currentMarkers != null) {
                        currentMarkers.clear();
                    }
                    ALERTED.clear();
                }),
                new ModOptionsMenu.Info(() -> isEnabled()
                        ? "Cached chunks: " + SNAPSHOTS.size() + ", RTP " + (rtpRunning ? "running" : "stopped")
                        : "Chat: /artp start | stop | status")));
        return new ModOptionsMenu(parent, TAG, "", options);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "boxStyle", String.valueOf(boxStyle));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
        properties.setProperty(prefix + "toast", String.valueOf(toastOnSpotted));
        properties.setProperty(prefix + "chat", String.valueOf(chatOnSpotted));
        properties.setProperty(prefix + "coords", String.valueOf(showCoordinates));
        for (int i = 0; i < REGIONS.length; i++) {
            properties.setProperty(prefix + "rtp" + i, String.valueOf(rtpRegions[i]));
        }
        properties.setProperty(prefix + "rtpDelay", String.valueOf(rtpDelay));
        properties.setProperty(prefix + "crown", String.valueOf(crownEnabled));
        properties.setProperty(prefix + "crownUsername", crownUsername);
        properties.setProperty(prefix + "crownFeedback", String.valueOf(crownFeedback));
        properties.setProperty(prefix + "crownTimeout", String.valueOf(crownTimeout));
        properties.setProperty(prefix + "webhookUrl", webhookUrl);
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        boxStyle = readInt(properties, prefix + "boxStyle", boxStyle, 0, BOX_STYLES.length - 1);
        opacity = readInt(properties, prefix + "opacity", opacity, 10, 255);
        toastOnSpotted = readBool(properties, prefix + "toast", toastOnSpotted);
        chatOnSpotted = readBool(properties, prefix + "chat", chatOnSpotted);
        showCoordinates = readBool(properties, prefix + "coords", showCoordinates);
        for (int i = 0; i < REGIONS.length; i++) {
            rtpRegions[i] = readBool(properties, prefix + "rtp" + i, rtpRegions[i]);
        }
        rtpDelay = readInt(properties, prefix + "rtpDelay", rtpDelay, 0, 200);
        crownEnabled = readBool(properties, prefix + "crown", crownEnabled);
        crownUsername = properties.getProperty(prefix + "crownUsername", crownUsername);
        crownFeedback = readBool(properties, prefix + "crownFeedback", crownFeedback);
        crownTimeout = readInt(properties, prefix + "crownTimeout", crownTimeout, 0, 120);
        webhookUrl = properties.getProperty(prefix + "webhookUrl", webhookUrl);
    }

    private static boolean readBool(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)));
    }

    private static int readInt(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Mth.clamp(Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
