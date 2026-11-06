package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ChunkOverlayRenderer;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.StorageEspMenu;
import nova.nova_client.client.chunkfinder.ChunkFinderConfig;
import nova.nova_client.client.mods.StorageEspMod.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Advanced Stash Finder: funktioniert wie Storage ESP (Lager-Bloecke mit Umriss durch Waende, gleiche Typen-Auswahl,
 * Tracer und Fuellung), zaehlt aber zusaetzlich die Lager-Bloecke je Chunk. Hat ein Chunk mindestens
 * "Minimum storage" davon, poppt rechts oben ein Toast auf und der Chunk wird umrahmt. Jeder Chunk meldet sich nur
 * einmal (bis man die Welt wechselt oder die Einstellungen aendert).
 */
public class AdvancedStashFinderMod extends GameTickMod {
    private static final int MAX_CHUNK_RADIUS = 16;
    private static final int CHUNKS_PER_TICK = 16;
    private static final int MAX_OUTLINES = 2000;
    private static final int MAX_TRACERS = 64;
    private static final int REFRESH_TICKS = 5;
    private static final int MAX_TOASTS = 4;

    // Einstellungen
    public static final Set<Type> SELECTED = EnumSet.allOf(Type.class);
    public static boolean tracers = false;
    public static int opacity = 20;
    /** Ab so vielen Lager-Bloecken in einem Chunk gilt er als Stash. */
    public static int minStorage = 16;
    /** Nur Bloecke in Stash-Chunks umranden (statt alle wie Storage ESP). */
    public static boolean onlyStashes = false;
    public static boolean sound = true;
    /** Wie lange ein Toast sichtbar bleibt (Sekunden). */
    public static int duration = 6;

    private static volatile boolean enabled;
    private static final Map<Long, List<BlockPos>> FOUND = new HashMap<>();
    private static final ArrayDeque<long[]> QUEUE = new ArrayDeque<>();
    private static final Set<Long> NOTIFIED = new HashSet<>();
    private static boolean dirty = true;
    private static int refreshTicks;
    private static ClientLevel lastLevel;

    // Fertige Anzeige (im Tick gebaut, pro Frame nur gezeichnet)
    private static BlockPos[] shown = new BlockPos[0];
    private static int[] shownColors = new int[0];
    private static GizmoStyle[] shownStyles = new GizmoStyle[0];
    /** Stash-Chunks, gezeichnet wie beim Chunk Finder (leuchtende Flaeche, Rand nur aussen). */
    private static long[] stashKeys = new long[0];
    private static Set<Long> stashSet = Set.of();
    private static final ChunkOverlayRenderer OVERLAY = new ChunkOverlayRenderer();

    private static final List<Toast> TOASTS = new ArrayList<>();

    private static final class Toast {
        final int chunkX;
        final int chunkZ;
        final int count;
        final BlockPos center;
        final List<ItemStack> icons;
        final long created;

        Toast(int chunkX, int chunkZ, int count, BlockPos center, List<ItemStack> icons, long created) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.count = count;
            this.center = center;
            this.icons = icons;
            this.created = created;
        }
    }

    /** Fuer das (geteilte) Storage-ESP-Menue: Typen, Tracer, Fuellung dieses Moduls. */
    private static final StorageEspMenu.Target TARGET = new StorageEspMenu.Target() {
        public Set<Type> selected() {
            return SELECTED;
        }

        public void toggle(Type type) {
            if (!SELECTED.remove(type)) {
                SELECTED.add(type);
            }
            resetScan();
        }

        public void selectAll(boolean all) {
            SELECTED.clear();
            if (all) {
                SELECTED.addAll(EnumSet.allOf(Type.class));
            }
            resetScan();
        }

        public boolean tracers() {
            return tracers;
        }

        public void setTracers(boolean on) {
            tracers = on;
        }

        public int opacity() {
            return opacity;
        }

        public void setOpacity(int percent) {
            opacity = Math.max(0, Math.min(100, percent));
            refreshTicks = REFRESH_TICKS;
        }
    };

    public AdvancedStashFinderMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Neu suchen und Stashes erneut melden (nach Einstellungs-Aenderungen). */
    private static void resetScan() {
        dirty = true;
        NOTIFIED.clear();
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        AdvancedStashFinderMod.enabled = enabled;
        resetScan();
        shown = new BlockPos[0];
        stashKeys = new long[0];
        stashSet = Set.of();
        if (!enabled) {
            TOASTS.clear();
        }
    }

    // ---------------------------------------------------------------- Suchen (im Tick)

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        if (level != lastLevel) {
            lastLevel = level;
            resetScan();
        }
        int radius = Math.min(MAX_CHUNK_RADIUS, client.options.getEffectiveRenderDistance());
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;

        if (dirty) {
            FOUND.clear();
            QUEUE.clear();
            dirty = false;
            refreshTicks = REFRESH_TICKS;
        }
        FOUND.keySet().removeIf(key -> {
            ChunkPos pos = Compat.chunkPos(key);
            return Math.abs(Compat.chunkX(pos) - centerX) > radius + 1 || Math.abs(Compat.chunkZ(pos) - centerZ) > radius + 1;
        });

        // Neue Runde: alle Chunks im Radius, nahe zuerst
        if (QUEUE.isEmpty()) {
            List<long[]> chunks = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new long[]{centerX + dx, centerZ + dz, (long) dx * dx + (long) dz * dz});
                }
            }
            chunks.sort(Comparator.comparingLong(a -> a[2]));
            QUEUE.addAll(chunks);
        }
        for (int i = 0; i < CHUNKS_PER_TICK && !QUEUE.isEmpty(); i++) {
            long[] next = QUEUE.poll();
            scanChunk(level, (int) next[0], (int) next[1]);
        }

        if (++refreshTicks >= REFRESH_TICKS) {
            refreshTicks = 0;
            rebuildShown(level, client.player.getEyePosition());
        }
    }

    private static void scanChunk(ClientLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (!level.hasChunk(chunkX, chunkZ) || SELECTED.isEmpty()) {
            FOUND.remove(key);
            return;
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            Type type = Type.of(chunk.getBlockState(pos).getBlock());
            if (type != null && SELECTED.contains(type)) {
                result.add(pos.immutable());
            }
        }
        if (result.isEmpty()) {
            FOUND.remove(key);
            return;
        }
        FOUND.put(key, result);
        if (result.size() >= minStorage && NOTIFIED.add(key)) {
            announce(level, chunkX, chunkZ, result);
        }
    }

    private record Hit(BlockPos pos, Type type, double distance) {
    }

    /** Sortiert nach Entfernung und prueft, ob die Bloecke noch da sind. */
    private static void rebuildShown(ClientLevel level, Vec3 eye) {
        List<Hit> hits = new ArrayList<>();
        List<Long> stashChunks = new ArrayList<>();
        for (Map.Entry<Long, List<BlockPos>> entry : FOUND.entrySet()) {
            boolean stash = entry.getValue().size() >= minStorage;
            if (onlyStashes && !stash) {
                continue;
            }
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (BlockPos pos : entry.getValue()) {
                Type type = Type.of(level.getBlockState(pos).getBlock());
                if (type != null && SELECTED.contains(type)) {
                    hits.add(new Hit(pos, type, pos.distToCenterSqr(eye)));
                    minY = Math.min(minY, pos.getY());
                    maxY = Math.max(maxY, pos.getY());
                }
            }
            if (stash && minY <= maxY) {
                stashChunks.add(entry.getKey());
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        int n = Math.min(MAX_OUTLINES, hits.size());
        BlockPos[] positions = new BlockPos[n];
        int[] colors = new int[n];
        GizmoStyle[] styles = new GizmoStyle[n];
        int fillAlpha = Math.round(opacity * 2.55F) << 24;
        Map<Type, GizmoStyle> cache = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Hit hit = hits.get(i);
            positions[i] = hit.pos();
            colors[i] = 0xFF000000 | hit.type().color;
            styles[i] = cache.computeIfAbsent(hit.type(),
                    t -> GizmoStyle.strokeAndFill(0xFF000000 | t.color, 2.0F, t.color | fillAlpha));
        }
        shown = positions;
        shownColors = colors;
        shownStyles = styles;
        stashKeys = stashChunks.stream().mapToLong(Long::longValue).toArray();
        stashSet = new HashSet<>(stashChunks);
    }

    // ---------------------------------------------------------------- Melden

    private static void announce(ClientLevel level, int chunkX, int chunkZ, List<BlockPos> blocks) {
        // Haeufigste Arten als Icons
        Map<Type, Integer> byType = new EnumMap<>(Type.class);
        long sx = 0;
        long sy = 0;
        long sz = 0;
        for (BlockPos pos : blocks) {
            Type type = Type.of(level.getBlockState(pos).getBlock());
            if (type != null) {
                byType.merge(type, 1, Integer::sum);
            }
            sx += pos.getX();
            sy += pos.getY();
            sz += pos.getZ();
        }
        List<Map.Entry<Type, Integer>> sorted = new ArrayList<>(byType.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<ItemStack> icons = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            icons.add(sorted.get(i).getKey().icon());
        }
        int n = blocks.size();
        BlockPos center = new BlockPos((int) (sx / n), (int) (sy / n), (int) (sz / n));
        TOASTS.add(0, new Toast(chunkX, chunkZ, n, center, icons, Util.getMillis()));
        while (TOASTS.size() > MAX_TOASTS) {
            TOASTS.remove(TOASTS.size() - 1);
        }
        if (sound) {
            NovaNotify.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8F);
        }
    }

    /** Toasts rechts oben (jeden Frame vom HUD). */
    public static void renderToasts(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || TOASTS.isEmpty() || client.player == null) {
            return;
        }
        Font font = client.font;
        long now = Util.getMillis();
        long life = duration * 1000L;
        TOASTS.removeIf(t -> now - t.created > life);

        int y = NovaToasts.top();
        for (Toast toast : TOASTS) {
            float in = NovaGui.easeOutCubic((now - toast.created) / 280.0F);
            float out = Mth.clamp((life - (now - toast.created)) / 280.0F, 0.0F, 1.0F);
            float show = in * out;
            // Wo: Entfernung + Koordinaten, rechts die haeufigsten Arten
            int distance = (int) Math.round(Math.sqrt(toast.center.distSqr(client.player.blockPosition())));
            String where = distance + "m  ·  " + toast.center.getX() + " " + toast.center.getY() + " " + toast.center.getZ();
            NovaToasts.card(g, font, NovaToasts.slideX(NovaToasts.CARD_W, show), y, show, "Stash found",
                    toast.count + " storage", where, toast.icons);
            y += NovaToasts.CARD_H + NovaToasts.GAP;
        }
        NovaToasts.setTop(y);
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null || client.player == null) {
            return;
        }
        BlockPos[] positions = shown;
        int[] colors = shownColors;
        GizmoStyle[] styles = shownStyles;
        net.minecraft.client.renderer.culling.Frustum view = nova.nova_client.client.ViewCull.frustum();
        for (int i = 0; i < positions.length; i++) {
            if (nova.nova_client.client.ViewCull.visible(view, positions[i])) {
                Gizmos.cuboid(positions[i], styles[i]).setAlwaysOnTop();
            }
        }
        // Stash-Chunks wie beim Chunk Finder: leuchtende Flaeche auf derselben Hoehe, Rand nur an den Aussenkanten
        long[] keys = stashKeys;
        if (keys.length > 0) {
            OVERLAY.emit(keys, stashSet, ChunkFinderConfig.overlayY, NovaGui.BRAND, ChunkFinderConfig.opacity,
                    ChunkFinderConfig.GLOW_STRENGTH);
        }
        if (tracers && positions.length > 0) {
            Vec3 eye = client.player.getEyePosition();
            net.minecraft.client.Camera camera = client.gameRenderer.mainCamera();
            org.joml.Vector3fc forward = camera.forwardVector();
            Vec3 start = camera.position().add(forward.x() * 0.6D, forward.y() * 0.6D, forward.z() * 0.6D);
            int lines = Math.min(MAX_TRACERS, positions.length);
            for (int i = 0; i < lines; i++) {
                BlockPos pos = positions[i];
                double distance = Math.sqrt(pos.distToCenterSqr(eye));
                int alpha = (int) Math.round(230 - Math.min(1.0D, distance / 96.0D) * 170);
                Gizmos.line(start, Vec3.atCenterOf(pos), (alpha << 24) | (colors[i] & 0xFFFFFF), 1.5F).setAlwaysOnTop();
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, "Advanced Stash Finder", "", List.of(
                new ModOptionsMenu.Slider("Min. storage", 2.0D, 128.0D, 1.0D, () -> minStorage,
                        v -> {
                            minStorage = (int) Math.round(v);
                            resetScan();
                        }, v -> String.valueOf(Math.round(v))),
                new ModOptionsMenu.Toggle("Only highlight stash chunks", () -> onlyStashes, v -> {
                    onlyStashes = v;
                    refreshTicks = REFRESH_TICKS;
                }),
                new ModOptionsMenu.Toggle("Sound on toast", () -> sound, v -> sound = v),
                new ModOptionsMenu.Slider("Toast duration", 2.0D, 20.0D, 1.0D, () -> duration,
                        v -> duration = (int) Math.round(v), v -> Math.round(v) + " s"),
                new ModOptionsMenu.Action(() -> "Storage types, tracers & fill (" + SELECTED.size() + "/" + Type.values().length + ")",
                        () -> Minecraft.getInstance().gui.setScreen(new StorageEspMenu(Minecraft.getInstance().gui.screen(),
                                "Stash Finder Types", TARGET))),
                new ModOptionsMenu.Action(() -> "Report all stashes again", AdvancedStashFinderMod::resetScan),
                new ModOptionsMenu.Info(() -> stashCount() + " stash chunk(s) in view")));
    }

    private static int stashCount() {
        int n = 0;
        for (List<BlockPos> list : FOUND.values()) {
            if (list.size() >= minStorage) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        List<String> names = new ArrayList<>();
        for (Type type : SELECTED) {
            names.add(type.name());
        }
        properties.setProperty(prefix + "selected", String.join(",", names));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
        properties.setProperty(prefix + "minStorage", String.valueOf(minStorage));
        properties.setProperty(prefix + "onlyStashes", String.valueOf(onlyStashes));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
        properties.setProperty(prefix + "duration", String.valueOf(duration));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String selected = properties.getProperty(prefix + "selected");
        if (selected != null) {
            SELECTED.clear();
            for (String name : selected.split(",")) {
                try {
                    SELECTED.add(Type.valueOf(name.trim()));
                } catch (IllegalArgumentException ignored) {
                    // unbekannt / leer
                }
            }
        }
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)));
        onlyStashes = Boolean.parseBoolean(properties.getProperty(prefix + "onlyStashes", String.valueOf(onlyStashes)));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
        try {
            opacity = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "opacity", String.valueOf(opacity)).trim()), 0, 100);
            minStorage = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "minStorage", String.valueOf(minStorage)).trim()), 2, 128);
            duration = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "duration", String.valueOf(duration)).trim()), 2, 20);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        resetScan();
    }
}
