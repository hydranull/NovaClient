package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.AntiXray;
import nova.nova_client.client.BlockEspMenu;
import nova.nova_client.client.ModCategory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zeigt ausgewaehlte Bloecke mit farbigem Umriss durch Waende.
 * Die Umgebung wird nach und nach (ein paar Chunks pro Tick) durchsucht, damit es nicht ruckelt.
 */
public class BlockEspMod extends GameTickMod {
    private static final int MAX_CHUNK_RADIUS = 8;
    private static final int CHUNKS_PER_TICK = 6;
    /** Kurze Pause zwischen zwei kompletten Runden (Ticks) statt Dauerscan. */
    private static final int ROUND_PAUSE_TICKS = 40;
    private static int restTicks;
    private static final int MAX_OUTLINES = 2000;
    /** Schutz, falls ein haeufiger Block (z.B. Stein) ausgewaehlt ist: sonst Millionen Treffer. */
    private static final int MAX_PER_CHUNK = 256;
    /** Anzeige-Liste spaetestens alle so viele Ticks neu sortieren/pruefen (abgebaute Bloecke). */
    private static final int REFRESH_TICKS = 5;

    private static final Set<Block> SELECTED = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<Block, Integer> COLORS = new IdentityHashMap<>();

    private static volatile boolean enabled;
    /** Linien (Tracer) zu den naechsten gefundenen Bloecken. */
    public static boolean tracers = true;
    private static final int MAX_TRACERS = 64;
    /** Deckkraft der Box-Fuellung in Prozent (0 = nur Umriss, 100 = voll deckend). */
    public static final int DEFAULT_OPACITY = 20;
    public static int opacity = DEFAULT_OPACITY;

    /** Gefundene Bloecke je Chunk (Schluessel = ChunkPos.pack). */
    private static final Map<Long, List<BlockPos>> FOUND = new HashMap<>();
    private static final ArrayDeque<long[]> QUEUE = new ArrayDeque<>();
    private static boolean dirty = true;

    // Fertig sortierte Anzeige (im Tick gebaut, pro Frame nur abgelaufen)
    private static BlockPos[] shown = new BlockPos[0];
    private static int[] shownColors = new int[0];
    private static GizmoStyle[] shownStyles = new GizmoStyle[0];
    private static final Map<Integer, GizmoStyle> STYLES = new HashMap<>();
    /** true, sobald sich FOUND geaendert hat -> Anzeige neu bauen. */
    private static boolean foundChanged;
    private static int refreshTicks;

    static {
        // Standard-Auswahl
        Collections.addAll(SELECTED,
                Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
                Blocks.SPAWNER, Blocks.CHEST, Blocks.TRAPPED_CHEST);

        color(0x5CE1E6, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DIAMOND_BLOCK);
        color(0x3DDC97, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.EMERALD_BLOCK);
        color(0xFFC857, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE, Blocks.GOLD_BLOCK);
        color(0xD8AF93, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.IRON_BLOCK);
        color(0xE07A3F, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        color(0xFF3B3B, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE);
        color(0x3B6BFF, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE);
        color(0x505050, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE);
        color(0xF0E6DA, Blocks.NETHER_QUARTZ_ORE);
        color(0xA0522D, Blocks.ANCIENT_DEBRIS);
        color(0xFF5470, Blocks.SPAWNER);
        color(0xFFA040, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
        color(0xB45CF6, Blocks.ENDER_CHEST);
    }

    public BlockEspMod(String name, ModCategory category) {
        super(name, category);
    }

    private static void color(int rgb, Block... blocks) {
        for (Block block : blocks) {
            COLORS.put(block, rgb);
        }
    }

    // ---------------------------------------------------------------- Auswahl

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isSelected(Block block) {
        return SELECTED.contains(block);
    }

    public static void setSelected(Block block, boolean selected) {
        if (selected) {
            SELECTED.add(block);
        } else {
            SELECTED.remove(block);
        }
        markDirty();
    }

    public static int selectedCount() {
        return SELECTED.size();
    }

    public static void clearSelection() {
        SELECTED.clear();
        markDirty();
    }

    /** Auswahl als Block-IDs (fuer die Config). */
    public static List<String> selectedIds() {
        List<String> ids = new ArrayList<>();
        for (Block block : SELECTED) {
            ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        Collections.sort(ids);
        return ids;
    }

    public static void loadSelectedIds(Iterable<String> ids) {
        SELECTED.clear();
        for (String raw : ids) {
            Identifier id = Identifier.tryParse(raw.trim());
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
                SELECTED.add(BuiltInRegistries.BLOCK.getValue(id));
            }
        }
        markDirty();
    }

    public static int colorOf(Block block) {
        Integer rgb = COLORS.get(block);
        if (rgb != null) {
            return 0xFF000000 | rgb;
        }
        int mapColor = block.defaultMapColor().col;
        return 0xFF000000 | (mapColor == 0 ? 0xFFFFFF : mapColor);
    }

    public static void setOpacity(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped != opacity) {
            opacity = clamped;
            // Styles haengen an der Deckkraft -> neu bauen, Anzeige sofort aktualisieren
            STYLES.clear();
            foundChanged = true;
        }
    }

    /** Neu scannen (z.B. nach Aenderung des Fake-Filters). */
    public static void rescan() {
        markDirty();
    }

    private static void markDirty() {
        dirty = true;
    }

    // ---------------------------------------------------------------- Mod

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new BlockEspMenu(parent);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        BlockEspMod.enabled = enabled;
        FOUND.clear();
        QUEUE.clear();
        restTicks = 0;
        dirty = true;
        clearShown();
    }

    private static void clearShown() {
        shown = new BlockPos[0];
        shownColors = new int[0];
        shownStyles = new GizmoStyle[0];
        foundChanged = false;
    }

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }

        int radius = Math.min(MAX_CHUNK_RADIUS, client.options.getEffectiveRenderDistance());
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;

        if (dirty) {
            FOUND.clear();
            QUEUE.clear();
            restTicks = 0;
            clearShown();
            dirty = false;
        }

        // Chunks ausserhalb des Radius vergessen
        if (FOUND.keySet().removeIf(key -> {
            ChunkPos pos = Compat.chunkPos(key);
            return Math.abs(Compat.chunkX(pos) - centerX) > radius + 1 || Math.abs(Compat.chunkZ(pos) - centerZ) > radius + 1;
        })) {
            foundChanged = true;
        }

        // Neue Runde: alle Chunks im Radius, nahe zuerst
        if (QUEUE.isEmpty() && restTicks > 0) {
            restTicks--;
        } else if (QUEUE.isEmpty()) {
            restTicks = ROUND_PAUSE_TICKS;
            List<long[]> chunks = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new long[]{centerX + dx, centerZ + dz, (long) dx * dx + (long) dz * dz});
                }
            }
            chunks.sort((a, b) -> Long.compare(a[2], b[2]));
            QUEUE.addAll(chunks);
        }

        long deadline = nova.nova_client.client.TickBudget.deadline();
        for (int i = 0; i < CHUNKS_PER_TICK && !QUEUE.isEmpty() && nova.nova_client.client.TickBudget.more(i, deadline); i++) {
            long[] next = QUEUE.poll();
            scanChunk(level, (int) next[0], (int) next[1]);
        }

        // Anzeige nur neu bauen, wenn neue Funde da sind, sonst alle REFRESH_TICKS (Sortierung beim Laufen)
        if (foundChanged || ++refreshTicks >= REFRESH_TICKS) {
            rebuildShown(level, client.player.getEyePosition());
            refreshTicks = 0;
            foundChanged = false;
        }
    }

    private record Hit(BlockPos pos, double distance) {
    }

    /** Sortiert die gefundenen Bloecke nach Entfernung und prueft, ob sie noch da sind (1x pro Tick max.). */
    private static void rebuildShown(ClientLevel level, Vec3 eye) {
        List<Hit> hits = new ArrayList<>();
        for (List<BlockPos> list : FOUND.values()) {
            for (BlockPos pos : list) {
                hits.add(new Hit(pos, pos.distToCenterSqr(eye)));
            }
        }
        hits.sort(java.util.Comparator.comparingDouble(Hit::distance));

        int count = Math.min(MAX_OUTLINES, hits.size());
        List<BlockPos> positions = new ArrayList<>(count);
        List<Block> blocks = new ArrayList<>(count);
        for (Hit hit : hits) {
            if (positions.size() >= count) {
                break;
            }
            Block block = level.getBlockState(hit.pos()).getBlock();
            if (SELECTED.contains(block)) { // sonst inzwischen abgebaut
                positions.add(hit.pos());
                blocks.add(block);
            }
        }

        int n = positions.size();
        BlockPos[] newShown = new BlockPos[n];
        int[] newColors = new int[n];
        GizmoStyle[] newStyles = new GizmoStyle[n];
        for (int i = 0; i < n; i++) {
            int color = colorOf(blocks.get(i));
            newShown[i] = positions.get(i);
            newColors[i] = color;
            int fillAlpha = Math.round(opacity * 2.55F) << 24;
            newStyles[i] = STYLES.computeIfAbsent(color,
                    c -> GizmoStyle.strokeAndFill(c, 2.0F, (c & 0x00FFFFFF) | fillAlpha));
        }
        shown = newShown;
        shownColors = newColors;
        shownStyles = newStyles;
    }

    private static void scanChunk(ClientLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (!level.hasChunk(chunkX, chunkZ) || SELECTED.isEmpty()) {
            if (FOUND.remove(key) != null) {
                foundChanged = true;
            }
            return;
        }

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        LevelChunkSection[] sections = chunk.getSections();
        List<BlockPos> result = new ArrayList<>();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        scan:
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()
                    || !section.getStates().maybeHas(state -> SELECTED.contains(state.getBlock()))) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(index) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (SELECTED.contains(state.getBlock())) {
                            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                            // Anti-Xray: komplett eingeschlossene Bloecke koennen Fakes sein
                            if (AntiXray.isReal(pos, level::getBlockState)) {
                                result.add(pos);
                                if (result.size() >= MAX_PER_CHUNK) {
                                    break scan;
                                }
                            }
                        }
                    }
                }
            }
        }

        List<BlockPos> previous = result.isEmpty() ? FOUND.remove(key) : FOUND.put(key, result);
        if (!result.equals(previous == null ? List.of() : previous)) {
            foundChanged = true;
        }
    }

    // ---------------------------------------------------------------- Rendering

    /** Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        BlockPos[] positions = shown;
        if (!enabled || client.level == null || client.player == null || positions.length == 0) {
            return;
        }
        // Sortieren/Pruefen passiert im Tick (rebuildShown); hier nur die fertige Liste zeichnen
        int[] colors = shownColors;
        GizmoStyle[] styles = shownStyles;
        for (int i = 0; i < positions.length; i++) {
            Gizmos.cuboid(positions[i], styles[i]).setAlwaysOnTop();
        }

        if (tracers) {
            Vec3 eye = client.player.getEyePosition();
            net.minecraft.client.Camera camera = client.gameRenderer.mainCamera();
            org.joml.Vector3fc forward = camera.forwardVector();
            Vec3 start = camera.position().add(forward.x() * 0.6D, forward.y() * 0.6D, forward.z() * 0.6D);
            int lines = Math.min(MAX_TRACERS, positions.length);
            for (int i = 0; i < lines; i++) {
                BlockPos pos = positions[i];
                double distance = Math.sqrt(pos.distToCenterSqr(eye));
                int alpha = (int) Math.round(230 - Math.min(1.0D, distance / 96.0D) * 170);
                Gizmos.line(start, Vec3.atCenterOf(pos), (alpha << 24) | (colors[i] & 0x00FFFFFF), 1.5F).setAlwaysOnTop();
            }
        }
    }

    @Override
    public void saveSettings(java.util.Properties properties, String prefix) {
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
    }

    @Override
    public void loadSettings(java.util.Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "tracers");
        if (value != null) {
            tracers = Boolean.parseBoolean(value);
        }
        String opacityValue = properties.getProperty(prefix + "opacity");
        if (opacityValue != null) {
            try {
                setOpacity(Integer.parseInt(opacityValue.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
