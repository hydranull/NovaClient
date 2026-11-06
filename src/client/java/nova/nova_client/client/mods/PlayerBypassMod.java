package nova.nova_client.client.mods;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ChunkOverlayRenderer;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.NovaTracers;
import nova.nova_client.client.activityinspector.SkyLightSections;
import nova.nova_client.client.activityinspector.SkyLightSections.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Player Bypass: wertet wie Activity Inspector das Himmelslicht aus den Licht-Paketen aus - eine Sektion unter Y=62,
 * fuer die der Server ein explizites, komplett dunkles Licht-Array schickt, gibt es nur, wenn dort Licht neu berechnet
 * wurde (gegraben, gebaut). Dazu kommen drei Filter gegen Fehlalarme:
 * <ul>
 *     <li><b>Eigene Chunks</b>: Chunks rund um die Stellen, an denen du selbst warst, werden pro Server/Dimension
 *     gespeichert und melden nie etwas (dein eigenes Graben).</li>
 *     <li><b>Verdaechtig statt sicher</b> (gelb): Sektionen, die vorher schon mal hell geschickt wurden ("wurde dunkel"),
 *     oder die samt Nachbarn komplett ohne Luft sind.</li>
 *     <li><b>Unter Y=0</b>: waehrend du selbst tief unten bist, werden Funde nur vorgemerkt, nicht gemeldet.</li>
 * </ul>
 * Funde werden als Zone (weich auslaufend) auf Y=64 markiert und per Toast gemeldet. Mit Auto RTP ({@link BypassAutoRtp})
 * teleportiert Nova selbst weiter, bis eine Base gefunden ist.
 */
public class PlayerBypassMod extends GameTickMod {
    private static final String TAG = "Player Bypass";
    private static final int BASE_CEILING_Y = 62;
    private static final int RENDER_CHUNKS = 16;
    private static final int KEEP_CHUNKS = 40;
    private static final int ZONE_RADIUS = 5;
    /** Eigene Chunks: so viele Chunks um dich herum (in jede Richtung). */
    private static final int PROTECT_RADIUS = 2;
    private static final double MARKER_Y = 64.0D;
    private static final int BASE_COLOR = 0xD99800;
    private static final int SUSPECT_COLOR = 0xFFE97A;
    private static final int MAX_REMEMBERED = 250_000;
    private static final long SAVE_DELAY_MS = 10_000L;

    // Einstellungen
    public static int opacity = 55;
    public static boolean tracers = false;
    public static boolean toasts = true;
    public static boolean sound = true;
    public static boolean showCoordinates = true;
    public static boolean autoDisconnect = false;

    private static volatile boolean enabled;
    private static ClientLevel lastLevel;
    /** Letzter Stand je Chunk (nur fuer Chunks mit Signal). */
    private static final Map<Long, Status[]> SKY = new HashMap<>();
    private static final Set<Long> ALERTED = new HashSet<>();
    private static final Set<Long> SUSPECT = new HashSet<>();
    /** Vorgemerkt, waehrend du unter Y=0 warst. */
    private static final Set<Long> HELD = new HashSet<>();
    /** Je Chunk: Sektionen (Bit = Index), die schon mal hell/leer geschickt wurden. */
    private static final Map<Long, Integer> SENT_NOT_DARK = new HashMap<>();
    private static final NovaToasts.Feed TOASTS = new NovaToasts.Feed(4);
    private static final ChunkOverlayRenderer SOURCE_OVERLAY = new ChunkOverlayRenderer();

    // Zonen (im Tick gebaut, pro Frame nur gezeichnet)
    private static long[] baseKeys = new long[0];
    private static long[] suspectKeys = new long[0];
    private static Set<Long> sourceSet = Set.of();
    private static long[] sourceKeys = new long[0];
    private static Map<Long, float[]> zone = Map.of();
    /** Zaehlt Aenderungen an SKY/ALERTED/SUSPECT - Zonen nur dann (oder beim Chunk-Wechsel) neu bauen. */
    private static int version;
    private static int builtVersion = -1;
    private static long builtChunk = Long.MIN_VALUE;
    private static int sinceBuild;
    // Zonen-Flaechen fertig zum Zeichnen (aus zone + Deckkraft)
    private static Map<Long, float[]> drawnZone;
    private static int drawnOpacity = -1;
    private static double[] zoneX = new double[0];
    private static double[] zoneZ = new double[0];
    private static GizmoStyle[] zoneStyle = new GizmoStyle[0];

    // Eigene Chunks
    private static final ProtectedChunks PROTECTED = new ProtectedChunks();
    private static long guardChunk = Long.MIN_VALUE;
    private static boolean entryPending;
    private static final Set<Long> WAITING = new HashSet<>();

    public PlayerBypassMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        if (!on) {
            BypassAutoRtp.stop(null);
            PROTECTED.flush();
            TOASTS.clear();
            reset();
            lastLevel = null;
        }
    }

    private static void reset() {
        version++;
        SKY.clear();
        ALERTED.clear();
        SUSPECT.clear();
        HELD.clear();
        WAITING.clear();
        guardChunk = Long.MIN_VALUE;
        entryPending = false;
        baseKeys = new long[0];
        suspectKeys = new long[0];
        sourceKeys = new long[0];
        sourceSet = Set.of();
        zone = Map.of();
    }

    private static void syncLevel(Minecraft client) {
        if (client.level == lastLevel) {
            return;
        }
        PROTECTED.flush();
        reset();
        SENT_NOT_DARK.clear();
        lastLevel = client.level;
        if (client.level != null) {
            PROTECTED.select(NovaNotify.serverId(), client.level.dimension().identifier().toString());
        }
    }

    // ---------------------------------------------------------------- Licht-Pakete (Hauptthread)

    public static void onLightData(int chunkX, int chunkZ, ClientboundLightUpdatePacketData data) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null || client.player == null || data == null) {
            return;
        }
        syncLevel(client);
        int count = client.level.getSectionsCount();
        int bottom = client.level.getMinSectionY();
        Status[] statuses = SkyLightSections.classify(data, count);
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (client.player.getY() < 0.0D) {
            hold(key, statuses, bottom);
        } else {
            ingest(client.level, key, chunkX, chunkZ, statuses, bottom);
        }
        rememberNotDark(key, statuses, count, bottom);
    }

    private static boolean candidate(int index, int bottom) {
        return (bottom + index - 1) * 16 + 15 < BASE_CEILING_Y;
    }

    private static boolean hasSignal(Status[] sky, int bottom) {
        for (int i = 1; i < sky.length - 1; i++) {
            if (sky[i] == Status.ZEROED && candidate(i, bottom)) {
                return true;
            }
        }
        return false;
    }

    private static void merge(Status[] into, Status[] update) {
        for (int i = 0; i < into.length && i < update.length; i++) {
            if (update[i] != null) {
                into[i] = update[i];
            }
        }
    }

    private static void ingest(ClientLevel level, long key, int chunkX, int chunkZ, Status[] statuses, int bottom) {
        version++;
        if (HELD.contains(key)) {
            hold(key, statuses, bottom);
        }
        Status[] sky = SKY.get(key);
        boolean wasFlagged = ALERTED.contains(key) || sky != null && hasSignal(sky, bottom);
        if (sky == null || sky.length != statuses.length) {
            sky = new Status[statuses.length];
            SKY.put(key, sky);
        }
        merge(sky, statuses);
        boolean flagged = hasSignal(sky, bottom);
        if (flagged && !wasFlagged && PROTECTED.contains(key)) {
            SKY.remove(key); // dein eigenes Graben
            return;
        }
        if (flagged) {
            if (suspicious(level, key, chunkX, chunkZ, sky, bottom)) {
                if (SUSPECT.add(key)) {
                    BypassAutoRtp.suspectSpotted();
                }
            } else {
                SUSPECT.remove(key);
                if (ALERTED.add(key)) {
                    spotted(chunkX, chunkZ);
                }
            }
        } else if (!ALERTED.contains(key)) {
            SKY.remove(key);
            SUSPECT.remove(key);
        }
    }

    private static void hold(long key, Status[] statuses, int bottom) {
        Status[] sky = new Status[statuses.length];
        merge(sky, statuses);
        if (hasSignal(sky, bottom) && !PROTECTED.contains(key)) {
            HELD.add(key);
        } else {
            HELD.remove(key);
        }
    }

    private static void rememberNotDark(long key, Status[] statuses, int count, int bottom) {
        int bits = 0;
        for (int i = 1; i <= count && i < 32 && i < statuses.length; i++) {
            Status s = statuses[i];
            if (candidate(i, bottom) && (s == Status.NORMAL || s == Status.FULL || s == Status.EMPTY)) {
                bits |= 1 << i;
            }
        }
        if (bits != 0) {
            if (SENT_NOT_DARK.size() >= MAX_REMEMBERED) {
                SENT_NOT_DARK.clear();
            }
            SENT_NOT_DARK.merge(key, bits, (a, b) -> a | b);
        }
    }

    /**
     * Nur verdaechtig: jede dunkle Sektion war vorher schon mal hell (wurde dunkel) oder hat ringsum keine Luft. Eine
     * einzige dunkle Sektion, die beides nicht erfuellt, macht den Chunk zu einem sicheren Fund.
     */
    private static boolean suspicious(ClientLevel level, long key, int chunkX, int chunkZ, Status[] sky, int bottom) {
        int notDark = SENT_NOT_DARK.getOrDefault(key, 0);
        boolean any = false;
        for (int i = 1; i < sky.length - 1; i++) {
            if (sky[i] != Status.ZEROED || !candidate(i, bottom)) {
                continue;
            }
            if (i < 32 && (notDark >>> i & 1) != 0) {
                any = true;
            } else if (airlessAround(level, chunkX, bottom + i - 1, chunkZ)) {
                any = true;
            } else {
                return false;
            }
        }
        return any;
    }

    private static boolean airlessAround(ClientLevel level, int chunkX, int sectionY, int chunkZ) {
        if (!airless(level, chunkX, sectionY, chunkZ)) {
            return false;
        }
        for (Direction d : Direction.values()) {
            if (!airless(level, chunkX + d.getStepX(), sectionY + d.getStepY(), chunkZ + d.getStepZ())) {
                return false;
            }
        }
        return true;
    }

    private static boolean airless(ClientLevel level, int chunkX, int sectionY, int chunkZ) {
        if (sectionY < level.getMinSectionY() || sectionY > level.getMaxSectionY()) {
            return true;
        }
        if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
            return false;
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
        if (section.hasOnlyAir()) {
            return false;
        }
        if (!section.getStates().maybeHas(BlockState::isAir)) {
            return true;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (section.getBlockState(x, y, z).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void spotted(int chunkX, int chunkZ) {
        Minecraft client = Minecraft.getInstance();
        BypassAutoRtp.baseSpotted();
        int x = (chunkX << 4) + 8;
        int z = (chunkZ << 4) + 8;
        String where = showCoordinates ? x + ", " + z : "chunk hidden";
        NovaNotify.chat(TAG, "§cBase detected" + (showCoordinates ? "§7 at §f" + x + ", " + z : ""));
        if (toasts) {
            TOASTS.push("chunk:" + ChunkPos.pack(chunkX, chunkZ), "Base detected", "Bypass",
                    showCoordinates ? new BlockPos(x, (int) MARKER_Y, z) : null, where,
                    List.of(new ItemStack(Items.PLAYER_HEAD)));
        }
        if (sound) {
            NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 0.8F);
        }
        ClientPacketListener connection = client.getConnection();
        if (autoDisconnect && connection != null) {
            connection.getConnection().disconnect(Component.literal("Player Bypass").withStyle(ChatFormatting.RED)
                    .append(Component.literal("\n\nBase detected" + (showCoordinates ? " at " + x + ", " + z : ""))
                            .withStyle(ChatFormatting.WHITE)));
        }
    }

    // ---------------------------------------------------------------- Tick

    @Override
    public void execute(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        syncLevel(client);
        BypassAutoRtp.tick(client);
        int px = client.player.getBlockX() >> 4;
        int pz = client.player.getBlockZ() >> 4;
        guardOwnChunks(client.level, ChunkPos.pack(px, pz));
        PROTECTED.tick();

        // Zonen nur neu bauen, wenn neue Daten kamen oder du den Chunk gewechselt hast (sonst jede Sekunde)
        long playerChunk = ChunkPos.pack(px, pz);
        if (version == builtVersion && playerChunk == builtChunk && ++sinceBuild < 20) {
            return;
        }
        sinceBuild = 0;
        builtVersion = version;
        builtChunk = playerChunk;

        // Weit Entferntes vergessen
        SKY.keySet().removeIf(key -> far(key, px, pz, KEEP_CHUNKS) && !ALERTED.contains(key));
        HELD.removeIf(key -> far(key, px, pz, KEEP_CHUNKS));

        // Zonen fuer die Anzeige bauen
        List<Long> bases = new ArrayList<>();
        List<Long> suspects = new ArrayList<>();
        for (Map.Entry<Long, Status[]> entry : SKY.entrySet()) {
            long key = entry.getKey();
            if (far(key, px, pz, RENDER_CHUNKS)) {
                continue;
            }
            if (ALERTED.contains(key) || hasSignal(entry.getValue(), client.level.getMinSectionY())) {
                (SUSPECT.contains(key) && !ALERTED.contains(key) ? suspects : bases).add(key);
            }
        }
        baseKeys = bases.stream().mapToLong(Long::longValue).toArray();
        suspectKeys = suspects.stream().mapToLong(Long::longValue).toArray();
        Set<Long> sources = new HashSet<>(bases);
        sources.addAll(suspects);
        sourceSet = sources;
        sourceKeys = sources.stream().mapToLong(Long::longValue).toArray();
        Map<Long, float[]> weights = new HashMap<>();
        spread(baseKeys, weights, 0);
        spread(suspectKeys, weights, 1);
        zone = weights;
    }

    private static boolean far(long key, int px, int pz, int limit) {
        return Math.max(Math.abs(ChunkPos.getX(key) - px), Math.abs(ChunkPos.getZ(key) - pz)) > limit;
    }

    /** Gewicht je Chunk um die Funde: 1 in der Mitte, weich auslaufend bis zum Rand der Zone. */
    private static void spread(long[] sources, Map<Long, float[]> into, int slot) {
        for (long key : sources) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            for (int dx = -ZONE_RADIUS; dx <= ZONE_RADIUS; dx++) {
                for (int dz = -ZONE_RADIUS; dz <= ZONE_RADIUS; dz++) {
                    float t = Math.max(Math.abs(dx), Math.abs(dz)) / (ZONE_RADIUS + 1.0F);
                    float weight = 1.0F - t * t * (3.0F - 2.0F * t);
                    float[] w = into.computeIfAbsent(ChunkPos.pack(cx + dx, cz + dz), k -> new float[2]);
                    w[slot] = Math.max(w[slot], weight);
                }
            }
        }
    }

    /** Eigene Chunks: kommst du in einen Chunk ohne Signal, gehoeren er und seine Nachbarn ab jetzt dir. */
    private static void guardOwnChunks(ClientLevel level, long playerChunk) {
        if (playerChunk != guardChunk) {
            guardChunk = playerChunk;
            entryPending = true;
            WAITING.clear();
        }
        if (entryPending) {
            if (!loaded(level, playerChunk)) {
                return;
            }
            entryPending = false;
            if (flaggedOrHeld(playerChunk)) {
                return;
            }
            int cx = ChunkPos.getX(playerChunk);
            int cz = ChunkPos.getZ(playerChunk);
            for (int dx = -PROTECT_RADIUS; dx <= PROTECT_RADIUS; dx++) {
                for (int dz = -PROTECT_RADIUS; dz <= PROTECT_RADIUS; dz++) {
                    long key = ChunkPos.pack(cx + dx, cz + dz);
                    if (!loaded(level, key)) {
                        WAITING.add(key);
                    } else if (!flaggedOrHeld(key)) {
                        PROTECTED.add(key);
                    }
                }
            }
        } else if (!WAITING.isEmpty()) {
            Iterator<Long> it = WAITING.iterator();
            while (it.hasNext()) {
                long key = it.next();
                if (loaded(level, key)) {
                    it.remove();
                    if (!flaggedOrHeld(key)) {
                        PROTECTED.add(key);
                    }
                }
            }
        }
    }

    private static boolean loaded(ClientLevel level, long key) {
        return level.getChunkSource().hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
    }

    private static boolean flaggedOrHeld(long key) {
        if (HELD.contains(key) || ALERTED.contains(key)) {
            return true;
        }
        Status[] sky = SKY.get(key);
        return sky != null && lastLevel != null && hasSignal(sky, lastLevel.getMinSectionY());
    }

    // ---------------------------------------------------------------- Zeichnen

    public static void renderToasts(GuiGraphicsExtractor g) {
        if (enabled) {
            TOASTS.render(g, 8);
        }
    }

    /** Jeden Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null || client.player == null || zone.isEmpty()) {
            return;
        }
        Map<Long, float[]> cells = zone;
        if (cells != drawnZone || opacity != drawnOpacity) {
            buildZoneCells(cells);
        }
        net.minecraft.client.renderer.culling.Frustum view = nova.nova_client.client.ViewCull.frustum();
        for (int i = 0; i < zoneStyle.length; i++) {
            double x0 = zoneX[i];
            double z0 = zoneZ[i];
            if (nova.nova_client.client.ViewCull.visible(view, x0, MARKER_Y, z0, x0 + 16.0D, MARKER_Y, z0 + 16.0D, 0.0F)) {
                Gizmos.rect(new Vec3(x0, MARKER_Y, z0), new Vec3(x0 + 16.0D, MARKER_Y, z0 + 16.0D), Direction.UP,
                        zoneStyle[i]).setAlwaysOnTop();
            }
        }
        SOURCE_OVERLAY.emit(sourceKeys, sourceSet, MARKER_Y + 0.05D, BASE_COLOR, 0.0F, 1.0F);
        if (tracers) {
            Vec3 start = NovaTracers.start();
            for (long key : baseKeys) {
                NovaTracers.line(start, center(key), BASE_COLOR, 160.0D);
            }
            for (long key : suspectKeys) {
                NovaTracers.line(start, center(key), SUSPECT_COLOR, 160.0D);
            }
        }
    }

    /** Farbe/Deckkraft je Zonen-Chunk einmal ausrechnen (statt jeden Frame neu). */
    private static void buildZoneCells(Map<Long, float[]> cells) {
        float maxAlpha = Math.max(10, Math.min(255, Math.round(opacity * 2.55F)));
        int n = 0;
        double[] xs = new double[cells.size()];
        double[] zs = new double[cells.size()];
        GizmoStyle[] styles = new GizmoStyle[cells.size()];
        for (Map.Entry<Long, float[]> entry : cells.entrySet()) {
            float b = entry.getValue()[0];
            float s = entry.getValue()[1];
            float mix = b * b / Math.max(1.0E-6F, b * b + s * s);
            int color = NovaGui.mix(SUSPECT_COLOR, BASE_COLOR, mix) & 0xFFFFFF;
            float strength = 0.25F + 0.75F * Math.max(b, s);
            int alpha = Math.round(maxAlpha * 0.45F * strength);
            if (alpha <= 0) {
                continue;
            }
            xs[n] = ChunkPos.getX(entry.getKey()) << 4;
            zs[n] = ChunkPos.getZ(entry.getKey()) << 4;
            styles[n] = GizmoStyle.fill((alpha << 24) | color);
            n++;
        }
        zoneX = java.util.Arrays.copyOf(xs, n);
        zoneZ = java.util.Arrays.copyOf(zs, n);
        zoneStyle = java.util.Arrays.copyOf(styles, n);
        drawnZone = cells;
        drawnOpacity = opacity;
    }

    private static Vec3 center(long key) {
        return new Vec3((ChunkPos.getX(key) << 4) + 8.0D, MARKER_Y, (ChunkPos.getZ(key) << 4) + 8.0D);
    }

    // ---------------------------------------------------------------- Einstellungen

    private static String status() {
        if (!enabled) {
            return "Module is off";
        }
        return ALERTED.size() + " bases  ·  " + SUSPECT.size() + " suspects  ·  " + PROTECTED.size() + " own chunks";
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Slider("Opacity", 5.0D, 100.0D, 1.0D, () -> opacity,
                        v -> opacity = (int) Math.round(v), v -> Math.round(v) + "%"),
                new ModOptionsMenu.Toggle("Tracers", () -> tracers, v -> tracers = v),
                new ModOptionsMenu.Toggle("Toasts", () -> toasts, v -> toasts = v),
                new ModOptionsMenu.Toggle("Sound", () -> sound, v -> sound = v),
                new ModOptionsMenu.Toggle("Show coordinates", () -> showCoordinates, v -> showCoordinates = v),
                new ModOptionsMenu.Toggle("Auto disconnect on base", () -> autoDisconnect, v -> autoDisconnect = v),
                new ModOptionsMenu.Action(() -> "Auto RTP ...  (" + BypassAutoRtp.status() + ")",
                        () -> Minecraft.getInstance().gui.setScreen(autoRtpMenu(Minecraft.getInstance().gui.screen()))),
                new ModOptionsMenu.Info(() -> "Orange = base  ·  yellow = suspect (turned dark / no air)"),
                new ModOptionsMenu.Action(() -> "Forget own chunks on this server (" + PROTECTED.size() + ")", PROTECTED::clear),
                new ModOptionsMenu.Info(PlayerBypassMod::status)));
    }

    /** Unter-Menue fuer Auto RTP. */
    private Screen autoRtpMenu(Screen parent) {
        return new ModOptionsMenu(parent, name + " · Auto RTP", "Teleports until a base is found", List.of(
                new ModOptionsMenu.Action(() -> BypassAutoRtp.running() ? "Stop Auto RTP" : "Start Auto RTP", () -> {
                    if (BypassAutoRtp.running()) {
                        BypassAutoRtp.stop("§7Stopped.");
                    } else if (enabled) {
                        BypassAutoRtp.start();
                    } else {
                        NovaNotify.chat("Auto RTP", "§cTurn on Player Bypass first.");
                    }
                }),
                new ModOptionsMenu.Text("RTP command", () -> BypassAutoRtp.command, v -> BypassAutoRtp.command = v.trim(), 64),
                new ModOptionsMenu.Slider("Scan time", 2.0D, 30.0D, 1.0D, () -> BypassAutoRtp.scanSeconds,
                        v -> BypassAutoRtp.scanSeconds = (int) Math.round(v), v -> Math.round(v) + " s"),
                new ModOptionsMenu.Slider("Delay between RTPs", 3.0D, 120.0D, 1.0D, () -> BypassAutoRtp.delaySeconds,
                        v -> BypassAutoRtp.delaySeconds = (int) Math.round(v), v -> Math.round(v) + " s"),
                new ModOptionsMenu.Slider("Teleport timeout", 3.0D, 60.0D, 1.0D, () -> BypassAutoRtp.timeoutSeconds,
                        v -> BypassAutoRtp.timeoutSeconds = (int) Math.round(v), v -> Math.round(v) + " s"),
                new ModOptionsMenu.Slider("Max RTPs", 0.0D, 500.0D, 10.0D, () -> BypassAutoRtp.maxRtps,
                        v -> BypassAutoRtp.maxRtps = (int) Math.round(v), v -> Math.round(v) == 0 ? "Unlimited" : String.valueOf(Math.round(v))),
                new ModOptionsMenu.Toggle("Stop on suspect (yellow)", () -> BypassAutoRtp.stopOnSuspect, v -> BypassAutoRtp.stopOnSuspect = v),
                new ModOptionsMenu.Text("Menu item (if /rtp opens a menu)", () -> BypassAutoRtp.menuItem, v -> BypassAutoRtp.menuItem = v, 48),
                new ModOptionsMenu.Info(BypassAutoRtp::status)));
    }

    /** Server-Nachrichten (AntiVanishPacketMixin): Wartezeiten fuer Auto RTP. */
    public static void onSystemChat(Component message) {
        if (enabled) {
            BypassAutoRtp.onChat(message);
        }
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "rtpCommand", BypassAutoRtp.command);
        properties.setProperty(prefix + "rtpScan", String.valueOf(BypassAutoRtp.scanSeconds));
        properties.setProperty(prefix + "rtpDelay", String.valueOf(BypassAutoRtp.delaySeconds));
        properties.setProperty(prefix + "rtpTimeout", String.valueOf(BypassAutoRtp.timeoutSeconds));
        properties.setProperty(prefix + "rtpMax", String.valueOf(BypassAutoRtp.maxRtps));
        properties.setProperty(prefix + "rtpStopOnSuspect", String.valueOf(BypassAutoRtp.stopOnSuspect));
        properties.setProperty(prefix + "rtpMenuItem", BypassAutoRtp.menuItem);
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "toasts", String.valueOf(toasts));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
        properties.setProperty(prefix + "showCoordinates", String.valueOf(showCoordinates));
        properties.setProperty(prefix + "autoDisconnect", String.valueOf(autoDisconnect));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            opacity = Math.max(5, Math.min(100, Integer.parseInt(properties.getProperty(prefix + "opacity", String.valueOf(opacity)).trim())));
            BypassAutoRtp.scanSeconds = Math.max(2, Math.min(30, Integer.parseInt(properties.getProperty(prefix + "rtpScan", String.valueOf(BypassAutoRtp.scanSeconds)).trim())));
            BypassAutoRtp.delaySeconds = Math.max(3, Math.min(120, Integer.parseInt(properties.getProperty(prefix + "rtpDelay", String.valueOf(BypassAutoRtp.delaySeconds)).trim())));
            BypassAutoRtp.timeoutSeconds = Math.max(3, Math.min(60, Integer.parseInt(properties.getProperty(prefix + "rtpTimeout", String.valueOf(BypassAutoRtp.timeoutSeconds)).trim())));
            BypassAutoRtp.maxRtps = Math.max(0, Math.min(500, Integer.parseInt(properties.getProperty(prefix + "rtpMax", String.valueOf(BypassAutoRtp.maxRtps)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        BypassAutoRtp.command = properties.getProperty(prefix + "rtpCommand", BypassAutoRtp.command);
        BypassAutoRtp.stopOnSuspect = Boolean.parseBoolean(properties.getProperty(prefix + "rtpStopOnSuspect", String.valueOf(BypassAutoRtp.stopOnSuspect)));
        BypassAutoRtp.menuItem = properties.getProperty(prefix + "rtpMenuItem", BypassAutoRtp.menuItem);
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)));
        toasts = Boolean.parseBoolean(properties.getProperty(prefix + "toasts", String.valueOf(toasts)));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
        showCoordinates = Boolean.parseBoolean(properties.getProperty(prefix + "showCoordinates", String.valueOf(showCoordinates)));
        autoDisconnect = Boolean.parseBoolean(properties.getProperty(prefix + "autoDisconnect", String.valueOf(autoDisconnect)));
    }

    // ---------------------------------------------------------------- Eigene Chunks (pro Server + Dimension)

    /** Gespeichert als Textdatei "x,z" je Zeile in config/nova_client_player_bypass/. */
    private static final class ProtectedChunks {
        private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("nova_client_player_bypass");
        private final Set<Long> chunks = new HashSet<>();
        private Path file;
        private boolean dirty;
        private long dirtySince;

        void select(String server, String dimension) {
            chunks.clear();
            dirty = false;
            String name = (server + "_" + dimension).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
            file = DIR.resolve(name + ".txt");
            if (!Files.isRegularFile(file)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] xz = line.split(",");
                    if (xz.length == 2) {
                        try {
                            chunks.add(ChunkPos.pack(Integer.parseInt(xz[0].trim()), Integer.parseInt(xz[1].trim())));
                        } catch (NumberFormatException ignored) {
                            // kaputte Zeile ueberspringen
                        }
                    }
                }
            } catch (IOException ignored) {
                // nicht lesbar: leer anfangen
            }
        }

        boolean contains(long key) {
            return chunks.contains(key);
        }

        int size() {
            return chunks.size();
        }

        void add(long key) {
            if (file != null && chunks.add(key) && !dirty) {
                dirty = true;
                dirtySince = System.currentTimeMillis();
            }
        }

        void clear() {
            chunks.clear();
            if (file != null) {
                dirty = true;
                dirtySince = 0L;
            }
        }

        void tick() {
            if (dirty && System.currentTimeMillis() - dirtySince >= SAVE_DELAY_MS) {
                flush();
            }
        }

        void flush() {
            if (!dirty || file == null) {
                return;
            }
            dirty = false;
            Path target = file;
            List<String> lines = new ArrayList<>(chunks.size());
            for (long key : chunks) {
                lines.add(ChunkPos.getX(key) + "," + ChunkPos.getZ(key));
            }
            CompletableFuture.runAsync(() -> {
                try {
                    Files.createDirectories(target.getParent());
                    Files.write(target, lines, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    // beim naechsten Mal wieder versuchen
                }
            });
        }
    }
}
