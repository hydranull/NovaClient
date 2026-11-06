package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaTracers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Amethyst Bypass: zeigt Amethyst-Geoden, die Anti-Xray versteckt. Drei Wege:
 * <ul>
 *     <li><b>Palette</b>: Anti-Xray tauscht die Bloecke aus, laesst aber die Block-Liste (Palette) der 16er-Sektion
 *     stehen. Steht dort eine Knospe drin, liegt in der Sektion eine Geode - auch wenn man sie nicht sieht.</li>
 *     <li><b>Light</b>: Knospen leuchten (1, 2, 4, 5). Lichtwerte, die genau so hoch sind, ein lokales Maximum bilden und
 *     an Amethyst grenzen, verraten die Knospen.</li>
 *     <li><b>Glow</b>: der Rand des Cluster-Leuchtens (dunkle Luft direkt neben Licht 4) - ab "Min cells" Treffern pro
 *     Chunk gilt der Chunk als Geode.</li>
 * </ul>
 */
public class AmethystBypassMod extends GameTickMod {
    public static final String[] METHODS = {"Palette", "Light", "Glow"};
    private static final int COLOR = 0xC882FF;
    private static final int RESCAN_TICKS = 20;
    private static final long BUDGET_NANOS = 2_000_000L;
    private static final int MAX_GLOW_MARKS = 200;
    private static final int GLOW_MIN_Y = -58;
    private static final int GLOW_MAX_Y = 30;
    private static final Direction[] SIDES = Direction.values();

    // Einstellungen
    public static int method = 0;
    public static int minCells = 12;
    public static boolean tracers = false;

    private static volatile boolean enabled;
    private static ClientLevel lastLevel;
    private static final ArrayDeque<Long> QUEUE = new ArrayDeque<>();
    /** Palette: fertig gepruefte Chunks und ihre Markierung. */
    private static final Set<Long> SCANNED = new HashSet<>();
    private static final Map<Long, BlockPos> MARKERS = new HashMap<>();
    /** Light/Glow: Treffer des laufenden Durchgangs. */
    private static final Set<Long> FOUND = new HashSet<>();
    private static long[] hits = new long[0];
    private static int tick;

    public AmethystBypassMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        restart();
    }

    private static void restart() {
        QUEUE.clear();
        SCANNED.clear();
        MARKERS.clear();
        FOUND.clear();
        hits = new long[0];
        tick = RESCAN_TICKS;
    }

    // ---------------------------------------------------------------- Pakete (Hauptthread)

    /** Chunk neu geschickt oder Block geaendert: Palette-Ergebnis dieses Chunks verwerfen. */
    public static void markDirty(int chunkX, int chunkZ) {
        if (enabled && method == 0) {
            long key = ChunkPos.pack(chunkX, chunkZ);
            if (SCANNED.remove(key)) {
                MARKERS.remove(key);
            }
        }
    }

    // ---------------------------------------------------------------- Tick

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        if (level != lastLevel) {
            lastLevel = level;
            restart();
        }
        boolean palette = method == 0;
        if (QUEUE.isEmpty()) {
            if (++tick < RESCAN_TICKS) {
                return;
            }
            tick = 0;
            int cx = client.player.getBlockX() >> 4;
            int cz = client.player.getBlockZ() >> 4;
            int radius = client.options.getEffectiveRenderDistance();
            if (palette) {
                SCANNED.removeIf(key -> !near(key, cx, cz, radius) || !loaded(level, key));
                MARKERS.keySet().retainAll(SCANNED);
            } else {
                FOUND.clear();
            }
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    long key = ChunkPos.pack(x, z);
                    if (level.getChunkSource().hasChunk(x, z) && (!palette || !SCANNED.contains(key))) {
                        QUEUE.add(key);
                    }
                }
            }
        }
        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
            long key = QUEUE.poll();
            if (!loaded(level, key)) {
                continue;
            }
            LevelChunk chunk = level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            switch (method) {
                case 0 -> {
                    BlockPos marker = paletteLeak(level, chunk, ChunkPos.getX(key), ChunkPos.getZ(key));
                    if (marker != null) {
                        MARKERS.put(key, marker);
                    }
                    SCANNED.add(key);
                }
                case 1 -> hiddenBuds(level, chunk, ChunkPos.getX(key), ChunkPos.getZ(key));
                default -> glow(level, chunk, ChunkPos.getX(key), ChunkPos.getZ(key));
            }
        }
        if (QUEUE.isEmpty()) {
            hits = palette ? MARKERS.values().stream().mapToLong(BlockPos::asLong).toArray()
                    : FOUND.stream().mapToLong(Long::longValue).toArray();
        }
    }

    private static boolean near(long key, int cx, int cz, int radius) {
        return Math.abs(ChunkPos.getX(key) - cx) <= radius && Math.abs(ChunkPos.getZ(key) - cz) <= radius;
    }

    private static boolean loaded(ClientLevel level, long key) {
        return level.getChunkSource().hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
    }

    private static boolean isBud(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isGeode(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST);
    }

    // ---------------------------------------------------------------- Palette

    /** Markierung fuer die erste Sektion, deren Palette Knospen enthaelt (die globale Palette sagt nichts aus). */
    private static BlockPos paletteLeak(ClientLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }
            PalettedContainer<BlockState> states = section.getStates();
            if (states.maybeHas(state -> false) || !states.maybeHas(AmethystBypassMod::isBud)) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            BlockPos marker = pickMarker(section, chunkX, chunkZ, baseY);
            return marker != null ? marker : new BlockPos((chunkX << 4) + 8, baseY + 8, (chunkZ << 4) + 8);
        }
        return null;
    }

    /** Ein Luftblock an der Geoden-Huelle (immer derselbe, damit die Markierung nicht springt). */
    private static BlockPos pickMarker(LevelChunkSection section, int chunkX, int chunkZ, int baseY) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (!section.getBlockState(x, y, z).isAir() || !touchesGeodeInSection(section, x, y, z)) {
                        continue;
                    }
                    int wx = (chunkX << 4) + x;
                    int wy = baseY + y;
                    int wz = (chunkZ << 4) + z;
                    int score = scatter(wx, wy, wz);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(wx, wy, wz);
                    }
                }
            }
        }
        return best;
    }

    private static boolean touchesGeodeInSection(LevelChunkSection section, int x, int y, int z) {
        for (Direction d : SIDES) {
            int nx = x + d.getStepX();
            int ny = y + d.getStepY();
            int nz = z + d.getStepZ();
            if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16 && isGeode(section.getBlockState(nx, ny, nz))) {
                return true;
            }
        }
        return false;
    }

    /** Fester Pseudo-Zufall je Position (0..99). */
    private static int scatter(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) Math.floorMod(h, 100L);
    }

    // ---------------------------------------------------------------- Light

    private static void hiddenBuds(ClientLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(AmethystBypassMod::isGeode)) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (section.getBlockState(x, y, z).getLightEmission() > 0) {
                            continue;
                        }
                        cursor.set((chunkX << 4) + x, baseY + y, (chunkZ << 4) + z);
                        int light = level.getBrightness(LightLayer.BLOCK, cursor);
                        if ((light == 1 || light == 2 || light == 4 || light == 5) && touchesGeode(level, cursor)
                                && localMaximum(level, cursor, light)) {
                            FOUND.add(cursor.asLong());
                        }
                    }
                }
            }
        }
    }

    private static boolean touchesGeode(ClientLevel level, BlockPos pos) {
        for (Direction d : SIDES) {
            if (isGeode(level.getBlockState(pos.relative(d)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean localMaximum(ClientLevel level, BlockPos pos, int light) {
        for (Direction d : SIDES) {
            if (level.getBrightness(LightLayer.BLOCK, pos.relative(d)) >= light) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- Glow

    /** Dunkle, offene Zellen am Rand des Cluster-Leuchtens (Nachbar mit genau Licht 4, keiner heller). */
    private static void glow(ClientLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        List<Long> cells = new ArrayList<>();
        for (int sy = GLOW_MIN_Y >> 4; sy <= GLOW_MAX_Y >> 4; sy++) {
            int index = chunk.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= sections.length || sections[index] == null) {
                continue;
            }
            LevelChunkSection section = sections[index];
            int from = Math.max(GLOW_MIN_Y, sy << 4);
            int to = Math.min(GLOW_MAX_Y, (sy << 4) + 15);
            for (int y = from; y <= to; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!open(section.getBlockState(x, y & 15, z))) {
                            continue;
                        }
                        cursor.set((chunkX << 4) + x, y, (chunkZ << 4) + z);
                        if (level.getBrightness(LightLayer.BLOCK, cursor) == 0 && glowEdge(level, cursor, neighbour)) {
                            cells.add(cursor.asLong());
                        }
                    }
                }
            }
        }
        if (cells.size() >= minCells) {
            for (int i = 0; i < Math.min(cells.size(), MAX_GLOW_MARKS); i++) {
                FOUND.add(cells.get(i));
            }
        }
    }

    private static boolean glowEdge(ClientLevel level, BlockPos pos, BlockPos.MutableBlockPos neighbour) {
        boolean litOpening = false;
        int brightest = 0;
        for (Direction d : SIDES) {
            neighbour.setWithOffset(pos, d);
            int value = level.getBrightness(LightLayer.BLOCK, neighbour);
            if (value > 4) {
                return false;
            }
            brightest = Math.max(brightest, value);
            if (value == 4 && !litOpening && open(level.getBlockState(neighbour))) {
                litOpening = true;
            }
        }
        return brightest == 4 && litOpening;
    }

    private static boolean open(BlockState state) {
        return state.isAir() || state.is(Blocks.AMETHYST_CLUSTER);
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Jeden Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        long[] current = hits;
        if (!enabled || client.level == null || current.length == 0) {
            return;
        }
        GizmoStyle style = GizmoStyle.strokeAndFill(0xFF000000 | COLOR, 1.5F, 0x66000000 | COLOR);
        Vec3 start = tracers ? NovaTracers.start() : null;
        for (long packed : current) {
            BlockPos pos = BlockPos.of(packed);
            if (!client.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            Gizmos.cuboid(new AABB(pos), style).setAlwaysOnTop();
            if (start != null) {
                NovaTracers.line(start, Vec3.atCenterOf(pos), COLOR, 96.0D);
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    private static String status() {
        if (!enabled) {
            return "Module is off";
        }
        return method == 0 ? MARKERS.size() + " hidden geodes" : hits.length + " marks";
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Choice("Method", METHODS, () -> method, v -> {
                    method = v;
                    restart();
                }),
                new ModOptionsMenu.Info(() -> switch (method) {
                    case 0 -> "Palette: buds listed in a section = hidden geode";
                    case 1 -> "Light: bud light (1/2/4/5) next to amethyst";
                    default -> "Glow: edge of the cluster glow, needs Min cells";
                }),
                new ModOptionsMenu.Slider("Min cells (Glow)", 1.0D, 100.0D, 1.0D, () -> minCells,
                        v -> minCells = (int) Math.round(v), v -> Math.round(v) + " cells"),
                new ModOptionsMenu.Toggle("Tracers", () -> tracers, v -> tracers = v),
                new ModOptionsMenu.Action(() -> "Rescan", AmethystBypassMod::restart),
                new ModOptionsMenu.Info(AmethystBypassMod::status)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "method", String.valueOf(method));
        properties.setProperty(prefix + "minCells", String.valueOf(minCells));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            method = Math.max(0, Math.min(METHODS.length - 1, Integer.parseInt(properties.getProperty(prefix + "method", String.valueOf(method)).trim())));
            minCells = Math.max(1, Math.min(100, Integer.parseInt(properties.getProperty(prefix + "minCells", String.valueOf(minCells)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)));
    }
}
