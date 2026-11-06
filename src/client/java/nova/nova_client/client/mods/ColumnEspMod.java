package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.EspOptionsMenu;
import nova.nova_client.client.ModCategory;
import org.joml.Vector3fc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Basis fuer ESPs auf senkrechte "Saeulen" eines Blocks (lange Ranken, Kelp bis zur Oberflaeche).
 * Lange gewachsene Pflanzen verraten, dass die Chunks lange geladen waren (Spieleraktivitaet).
 * Die Umgebung wird wie bei BlockESP nach und nach durchsucht, ein paar Chunks pro Tick.
 */
public abstract class ColumnEspMod extends GameTickMod {
    private static final List<ColumnEspMod> ALL = new ArrayList<>();
    private static final int MAX_CHUNK_RADIUS = 12;
    private static final int CHUNKS_PER_TICK = 4;
    private static final int MAX_SHOWN = 400;
    private static final int MAX_TRACERS = 48;
    private static final int REFRESH_TICKS = 10;

    // Chunk-Ansicht: durchsichtige Fuellung, leuchtender Rand aus mehreren Umrissen (aussen breit und schwach)
    private static final int CHUNK_FILL_ALPHA = 0x50;
    private static final int[] GLOW_ALPHAS = {0x14, 0x2C, 0x5C, 0xFF};
    private static final float[] GLOW_WIDTHS = {14.0F, 9.0F, 5.0F, 2.5F};

    // Einstellungen (je Mod)
    public boolean tracers = true;
    public int minLength;

    private final int color;
    private final String subtitle;
    private final int[] lengthPresets;
    private final GizmoStyle style;
    private final GizmoStyle chunkFill;
    private final GizmoStyle[] chunkGlow;

    /** Eine gefundene Saeule: unterster Block und Hoehe. */
    public record Column(BlockPos bottom, int height) {
        AABB box() {
            return new AABB(bottom.getX(), bottom.getY(), bottom.getZ(),
                    bottom.getX() + 1, bottom.getY() + height, bottom.getZ() + 1);
        }

        Vec3 center() {
            return new Vec3(bottom.getX() + 0.5D, bottom.getY() + height * 0.5D, bottom.getZ() + 0.5D);
        }
    }

    /** Gefundene Saeulen je Chunk (Schluessel = ChunkPos.pack). */
    private final Map<Long, List<Column>> found = new HashMap<>();
    private final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private boolean dirty = true;
    private boolean foundChanged;
    private int refreshTicks;
    private Column[] shown = new Column[0];
    /** Chunk-Ansicht: je Chunk ein 16x16-Deckel auf Hoehe der hoechsten Saeule (Box: unterste bis oberste Hoehe, gezeichnet wird nur die Oberseite). */
    private AABB[] shownChunks = new AABB[0];

    protected ColumnEspMod(String name, String subtitle, int color, int defaultMinLength, int[] lengthPresets) {
        super(name, ModCategory.RENDER);
        this.subtitle = subtitle;
        this.color = color;
        this.minLength = defaultMinLength;
        this.lengthPresets = lengthPresets;
        this.style = GizmoStyle.strokeAndFill(0xFF000000 | color, 2.0F, 0x30000000 | color);
        this.chunkFill = GizmoStyle.fill((CHUNK_FILL_ALPHA << 24) | color);
        this.chunkGlow = new GizmoStyle[GLOW_ALPHAS.length];
        for (int i = 0; i < GLOW_ALPHAS.length; i++) {
            chunkGlow[i] = GizmoStyle.stroke((GLOW_ALPHAS[i] << 24) | color, GLOW_WIDTHS[i]);
        }
        ALL.add(this);
    }

    /** Gehoert dieser Block zur Saeule? */
    protected abstract boolean isPart(BlockState state);

    /** Ganze Chunks markieren statt einzelner Saeulen. */
    protected boolean chunkView() {
        return false;
    }

    /** Zusaetzliche Bedingung fuer eine Saeule (top = oberster Block der Saeule). */
    protected boolean accepts(ClientLevel level, BlockPos top) {
        return true;
    }

    public static boolean anyActive() {
        for (ColumnEspMod mod : ALL) {
            if (mod.active) {
                return true;
            }
        }
        return false;
    }

    private void markDirty() {
        dirty = true;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        found.clear();
        queue.clear();
        shown = new Column[0];
        shownChunks = new AABB[0];
        dirty = true;
    }

    // ---------------------------------------------------------------- Suchen

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
            found.clear();
            queue.clear();
            shown = new Column[0];
            shownChunks = new AABB[0];
            dirty = false;
        }

        // Chunks ausserhalb des Radius vergessen
        if (found.keySet().removeIf(key -> {
            ChunkPos pos = Compat.chunkPos(key);
            return Math.abs(Compat.chunkX(pos) - centerX) > radius + 1 || Math.abs(Compat.chunkZ(pos) - centerZ) > radius + 1;
        })) {
            foundChanged = true;
        }

        // Neue Runde: alle Chunks im Radius, nahe zuerst
        if (queue.isEmpty()) {
            List<long[]> chunks = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new long[]{centerX + dx, centerZ + dz, (long) dx * dx + (long) dz * dz});
                }
            }
            chunks.sort(Comparator.comparingLong(a -> a[2]));
            queue.addAll(chunks);
        }

        long deadline = nova.nova_client.client.TickBudget.deadline();
        for (int i = 0; i < CHUNKS_PER_TICK && !queue.isEmpty() && nova.nova_client.client.TickBudget.more(i, deadline); i++) {
            long[] next = queue.poll();
            scanChunk(level, (int) next[0], (int) next[1]);
        }

        if (foundChanged || ++refreshTicks >= REFRESH_TICKS) {
            rebuildShown(client.player.getEyePosition());
            refreshTicks = 0;
            foundChanged = false;
        }
    }

    private void scanChunk(ClientLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (!level.hasChunk(chunkX, chunkZ)) {
            if (found.remove(key) != null) {
                foundChanged = true;
            }
            return;
        }

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        LevelChunkSection[] sections = chunk.getSections();
        List<Column> result = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int maxY = level.getMaxY();

        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(this::isPart)) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(index) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!isPart(section.getBlockState(x, y, z))) {
                            continue;
                        }
                        // Nur am unteren Ende einer Saeule anfangen
                        cursor.set(baseX + x, baseY + y - 1, baseZ + z);
                        if (isPart(level.getBlockState(cursor))) {
                            continue;
                        }
                        int height = 1;
                        cursor.set(baseX + x, baseY + y + 1, baseZ + z);
                        while (cursor.getY() <= maxY && isPart(level.getBlockState(cursor))) {
                            height++;
                            cursor.move(0, 1, 0);
                        }
                        BlockPos bottom = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        if (height >= minLength && accepts(level, bottom.above(height - 1))) {
                            result.add(new Column(bottom, height));
                        }
                    }
                }
            }
        }

        List<Column> previous = result.isEmpty() ? found.remove(key) : found.put(key, result);
        if (!result.equals(previous == null ? List.of() : previous)) {
            foundChanged = true;
        }
    }

    private void rebuildShown(Vec3 eye) {
        List<Column> all = new ArrayList<>();
        for (List<Column> list : found.values()) {
            all.addAll(list);
        }
        all.sort(Comparator.comparingDouble(column -> column.center().distanceToSqr(eye)));
        shown = all.subList(0, Math.min(MAX_SHOWN, all.size())).toArray(new Column[0]);

        if (chunkView()) {
            List<AABB> chunks = new ArrayList<>();
            for (Map.Entry<Long, List<Column>> entry : found.entrySet()) {
                ChunkPos pos = Compat.chunkPos(entry.getKey());
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (Column column : entry.getValue()) {
                    minY = Math.min(minY, column.bottom().getY());
                    maxY = Math.max(maxY, column.bottom().getY() + column.height());
                }
                int x0 = Compat.chunkX(pos) << 4;
                int z0 = Compat.chunkZ(pos) << 4;
                chunks.add(new AABB(x0, minY, z0, x0 + 16, maxY, z0 + 16));
            }
            chunks.sort(Comparator.comparingDouble(box -> box.getCenter().distanceToSqr(eye)));
            shownChunks = chunks.subList(0, Math.min(MAX_SHOWN, chunks.size())).toArray(new AABB[0]);
        }
    }

    // ---------------------------------------------------------------- Rendering

    /** Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return;
        }
        Camera camera = client.gameRenderer.mainCamera();
        Vector3fc forward = camera.forwardVector();
        Vec3 start = camera.position().add(forward.x() * 0.6D, forward.y() * 0.6D, forward.z() * 0.6D);
        Vec3 eye = client.player.getEyePosition();

        for (ColumnEspMod mod : ALL) {
            if (mod.active && mod.chunkView()) {
                emitChunks(mod, start, eye);
                continue;
            }
            Column[] columns = mod.shown;
            if (!mod.active || columns.length == 0) {
                continue;
            }
            for (Column column : columns) {
                Gizmos.cuboid(column.box(), mod.style).setAlwaysOnTop();
            }
            if (mod.tracers) {
                int lines = Math.min(MAX_TRACERS, columns.length);
                for (int i = 0; i < lines; i++) {
                    Vec3 target = columns[i].center();
                    double distance = target.distanceTo(eye);
                    int alpha = (int) Math.round(230 - Math.min(1.0D, distance / 160.0D) * 160);
                    Gizmos.line(start, target, (alpha << 24) | mod.color, 1.5F).setAlwaysOnTop();
                }
            }
        }
    }

    private static void emitChunks(ColumnEspMod mod, Vec3 start, Vec3 eye) {
        AABB[] chunks = mod.shownChunks;
        for (AABB box : chunks) {
            // Nur der Deckel: flache Flaeche auf Hoehe der hoechsten Kelp-Spitze
            Vec3 min = new Vec3(box.minX, box.maxY, box.minZ);
            Vec3 max = new Vec3(box.maxX, box.maxY, box.maxZ);
            Gizmos.rect(min, max, Direction.UP, mod.chunkFill).setAlwaysOnTop();
            for (GizmoStyle glow : mod.chunkGlow) {
                Gizmos.rect(min, max, Direction.UP, glow).setAlwaysOnTop();
            }
        }
        if (mod.tracers) {
            int lines = Math.min(MAX_TRACERS, chunks.length);
            for (int i = 0; i < lines; i++) {
                Vec3 target = new Vec3(chunks[i].getCenter().x, chunks[i].maxY, chunks[i].getCenter().z);
                double distance = target.distanceTo(eye);
                int alpha = (int) Math.round(230 - Math.min(1.0D, distance / 160.0D) * 160);
                Gizmos.line(start, target, (alpha << 24) | mod.color, 1.5F).setAlwaysOnTop();
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
        List<EspOptionsMenu.Toggle> toggles = List.of(
                new EspOptionsMenu.Toggle("Tracers", () -> tracers, () -> tracers = !tracers));
        EspOptionsMenu.Presets presets = new EspOptionsMenu.Presets("Minimum length (blocks)", lengthPresets,
                () -> minLength, value -> {
                    minLength = value;
                    markDirty();
                }, "");
        return new EspOptionsMenu(parent, name, subtitle, color, toggles, presets);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "minLength", String.valueOf(minLength));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "tracers");
        if (value != null) {
            tracers = Boolean.parseBoolean(value);
        }
        String length = properties.getProperty(prefix + "minLength");
        if (length != null) {
            try {
                minLength = Math.max(1, Integer.parseInt(length.trim()));
                markDirty();
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
