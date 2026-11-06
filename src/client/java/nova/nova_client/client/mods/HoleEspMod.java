package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.HoleEspMenu;
import nova.nova_client.client.ModCategory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Findet senkrechte Schaechte: 1x1 oder 1x3 (drei Bloecke in einer Reihe), mindestens 4 Bloecke hoch,
 * rundherum Wand. Egal ob offen zum Himmel, wieder zugemacht oder komplett unter der Erde; gesucht wird bis
 * 100 Bloecke unter der Oberflaeche. Groessere Gruben, Hoehlen und Schluchten zaehlen nicht.
 * Die Umgebung wird wie bei BlockESP nach und nach durchsucht, ein paar Chunks pro Tick.
 */
public class HoleEspMod extends GameTickMod {
    private static final int MAX_CHUNK_RADIUS = 12;
    private static final int CHUNKS_PER_TICK = 2; // jede Spalte wird bis 100 Bloecke tief gelesen
    private static final int MAX_SHOWN = 300;
    private static final int REFRESH_TICKS = 10;
    /** Pause zwischen zwei kompletten Runden (Ticks) - die Umgebung aendert sich nicht staendig. */
    private static final int ROUND_PAUSE_TICKS = 60;
    /** So viele freie Bloecke muss der Schacht mindestens hoch sein. */
    private static final int MIN_LENGTH = 4;
    /** So tief (ab Oberflaeche) wird hoechstens gesucht; tiefere Teile werden hier abgeschnitten. */
    private static final int MAX_DEPTH = 100;
    /** Anteil der gepruefte Hoehen, an denen die Wand dicht sein muss (Hoehlen duerfen den Schacht kreuzen). */
    private static final float MIN_WALL_RATIO = 0.7F;

    // Einstellungen
    public static int color1x1 = 0xFF4D6A;
    public static int color1x3 = 0xFFB000;
    public static boolean show1x1 = true;
    public static boolean show1x3 = true;
    /** Deckkraft der Fuellung in Prozent. */
    public static int opacity = 25;

    private static volatile boolean enabled;

    /** Ein gefundener Schacht: Grundflaeche (x0/z0 inklusive, x1/z1 exklusive), Boden und Oberkante. */
    public record Hole(int x0, int z0, int x1, int z1, int bottom, int top, boolean wide) {
        AABB box() {
            return new AABB(x0, bottom, z0, x1, top, z1);
        }

        Vec3 topCenter() {
            return new Vec3((x0 + x1) * 0.5D, top, (z0 + z1) * 0.5D);
        }
    }

    private static final Map<Long, List<Hole>> FOUND = new HashMap<>();
    private static final ArrayDeque<long[]> QUEUE = new ArrayDeque<>();
    private static boolean dirty = true;
    private static boolean foundChanged;
    private static int pauseTicks;
    private static int refreshTicks;
    private static Hole[] shown = new Hole[0];

    public HoleEspMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setOpacity(int percent) {
        opacity = Math.max(0, Math.min(100, percent));
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        HoleEspMod.enabled = enabled;
        FOUND.clear();
        QUEUE.clear();
        shown = new Hole[0];
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
            FOUND.clear();
            QUEUE.clear();
            pauseTicks = 0;
            shown = new Hole[0];
            dirty = false;
        }

        // Chunks ausserhalb des Radius vergessen
        if (FOUND.keySet().removeIf(key -> {
            ChunkPos pos = Compat.chunkPos(key);
            return Math.abs(Compat.chunkX(pos) - centerX) > radius + 1 || Math.abs(Compat.chunkZ(pos) - centerZ) > radius + 1;
        })) {
            foundChanged = true;
        }

        // Neue Runde (nach einer kurzen Pause): alle Chunks im Radius, nahe zuerst
        if (QUEUE.isEmpty() && pauseTicks > 0) {
            pauseTicks--;
        } else if (QUEUE.isEmpty()) {
            pauseTicks = ROUND_PAUSE_TICKS;
            List<long[]> chunks = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new long[]{centerX + dx, centerZ + dz, (long) dx * dx + (long) dz * dz});
                }
            }
            chunks.sort(Comparator.comparingLong(a -> a[2]));
            QUEUE.addAll(chunks);
        }

        long deadline = nova.nova_client.client.TickBudget.deadline();
        for (int i = 0; i < CHUNKS_PER_TICK && !QUEUE.isEmpty() && nova.nova_client.client.TickBudget.more(i, deadline); i++) {
            long[] next = QUEUE.poll();
            scanChunk(level, (int) next[0], (int) next[1]);
        }

        if (foundChanged || ++refreshTicks >= REFRESH_TICKS) {
            rebuildShown(client.player.getEyePosition());
            refreshTicks = 0;
            foundChanged = false;
        }
    }

    private static void scanChunk(ClientLevel level, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (!level.hasChunk(chunkX, chunkZ)) {
            if (FOUND.remove(key) != null) {
                foundChanged = true;
            }
            return;
        }

        Scan scan = new Scan(level);
        List<Hole> result = new ArrayList<>();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int z = baseZ; z < baseZ + 16; z++) {
            for (int x = baseX; x < baseX + 16; x++) {
                scan.holesAt(x, z, result);
            }
        }

        List<Hole> previous = result.isEmpty() ? FOUND.remove(key) : FOUND.put(key, result);
        if (!result.equals(previous == null ? List.of() : previous)) {
            foundChanged = true;
        }
    }

    /** Suche in einem Chunk. */
    private static final class Scan {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        Scan(ClientLevel level) {
            this.level = level;
        }

        /**
         * Alle Schaechte, deren "Anker" diese Spalte ist (1x1 selbst, 1x3 die Mitte). Jeder freie senkrechte
         * Abschnitt der Spalte wird einzeln geprueft; die Form ergibt sich allein aus den Nachbarn auf dieser Hoehe.
         */
        void holesAt(int x, int z, List<Hole> out) {
            if (!level.hasChunk((x - 2) >> 4, z >> 4) || !level.hasChunk((x + 2) >> 4, z >> 4)
                    || !level.hasChunk(x >> 4, (z - 2) >> 4) || !level.hasChunk(x >> 4, (z + 2) >> 4)) {
                return;
            }
            int ground = Math.max(Math.max(surface(x - 1, z), surface(x + 1, z)),
                    Math.max(surface(x, z - 1), surface(x, z + 1)));
            int limit = Math.max(level.getMinY(), ground - MAX_DEPTH);

            int y = ground - 1;
            while (y >= limit) {
                if (!passable(x, y, z)) {
                    y--;
                    continue;
                }
                int top = y + 1;
                while (y >= limit && passable(x, y, z)) {
                    y--;
                }
                int bottom = y + 1;
                if (top - bottom >= MIN_LENGTH) {
                    Hole hole = shape(x, z, bottom, top);
                    if (hole != null) {
                        out.add(hole);
                    }
                }
            }
        }

        private Hole shape(int x, int z, int bottom, int top) {
            // 1x1: alle vier Seiten Wand
            Hole hole = check(new int[][]{{x, z}},
                    new int[][]{{x - 1, z}, {x + 1, z}, {x, z - 1}, {x, z + 1}},
                    x, z, x + 1, z + 1, bottom, top, false);
            if (hole != null) {
                return hole;
            }
            // 1x3 entlang X, diese Spalte ist die Mitte
            hole = check(new int[][]{{x - 1, z}, {x, z}, {x + 1, z}},
                    new int[][]{{x - 2, z}, {x + 2, z}, {x - 1, z - 1}, {x, z - 1}, {x + 1, z - 1},
                            {x - 1, z + 1}, {x, z + 1}, {x + 1, z + 1}},
                    x - 1, z, x + 2, z + 1, bottom, top, true);
            if (hole != null) {
                return hole;
            }
            // 1x3 entlang Z
            return check(new int[][]{{x, z - 1}, {x, z}, {x, z + 1}},
                    new int[][]{{x, z - 2}, {x, z + 2}, {x - 1, z - 1}, {x - 1, z}, {x - 1, z + 1},
                            {x + 1, z - 1}, {x + 1, z}, {x + 1, z + 1}},
                    x, z - 1, x + 1, z + 2, bottom, top, true);
        }

        /**
         * Passt die Form auf dieser Hoehe? An ~10 Stichproben (kurze Schaechte: jede Ebene) muessen die Zellen
         * frei und die Waende dicht sein; Hoehlen duerfen den Schacht an einzelnen Stellen kreuzen.
         */
        private Hole check(int[][] cells, int[][] walls, int x0, int z0, int x1, int z1, int bottom, int top,
                           boolean wide) {
            int samples = 0;
            int good = 0;
            int step = Math.max(1, (top - bottom) / 10);
            for (int y = bottom; y < top; y += step) {
                samples++;
                if (matches(cells, walls, y)) {
                    good++;
                }
            }
            return good >= samples * MIN_WALL_RATIO ? new Hole(x0, z0, x1, z1, bottom, top, wide) : null;
        }

        private boolean matches(int[][] cells, int[][] walls, int y) {
            for (int[] cell : cells) {
                if (!passable(cell[0], y, cell[1])) {
                    return false;
                }
            }
            for (int[] wall : walls) {
                if (passable(wall[0], y, wall[1])) {
                    return false;
                }
            }
            return true;
        }

        /** Oberflaeche (erster freier Block ueber dem Boden), Laub zaehlt nicht. */
        private int surface(int x, int z) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }

        /** Kann man hier durch den Schacht fallen/klettern? (Luft, Fackeln, Leitern, Ranken, ...; kein Wasser) */
        private boolean passable(int x, int y, int z) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                return true;
            }
            if (state.getBlock() instanceof LadderBlock || state.getBlock() instanceof ScaffoldingBlock) {
                return true;
            }
            // Wasser zaehlt als fest, sonst waeren Fluesse und Meeresboeden voller Loecher
            return state.getFluidState().isEmpty() && state.getCollisionShape(level, cursor).isEmpty();
        }
    }

    private static void rebuildShown(Vec3 eye) {
        List<Hole> all = new ArrayList<>();
        for (List<Hole> list : FOUND.values()) {
            all.addAll(list);
        }
        all.sort(Comparator.comparingDouble(hole -> hole.topCenter().distanceToSqr(eye)));
        shown = all.subList(0, Math.min(MAX_SHOWN, all.size())).toArray(new Hole[0]);
    }

    // ---------------------------------------------------------------- Rendering

    /** Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        Hole[] holes = shown;
        if (!enabled || client.level == null || client.player == null || holes.length == 0) {
            return;
        }
        int fillAlpha = Math.round(opacity * 2.55F) << 24;
        GizmoStyle style1x1 = GizmoStyle.strokeAndFill(0xFF000000 | color1x1, 2.0F, fillAlpha | color1x1);
        GizmoStyle style1x3 = GizmoStyle.strokeAndFill(0xFF000000 | color1x3, 2.0F, fillAlpha | color1x3);
        for (Hole hole : holes) {
            if (hole.wide() ? !show1x3 : !show1x1) {
                continue;
            }
            Gizmos.cuboid(hole.box(), hole.wide() ? style1x3 : style1x1).setAlwaysOnTop();
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new HoleEspMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "color1x1", String.format("%06X", color1x1 & 0xFFFFFF));
        properties.setProperty(prefix + "color1x3", String.format("%06X", color1x3 & 0xFFFFFF));
        properties.setProperty(prefix + "show1x1", String.valueOf(show1x1));
        properties.setProperty(prefix + "show1x3", String.valueOf(show1x3));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        color1x1 = readColor(properties.getProperty(prefix + "color1x1"), color1x1);
        color1x3 = readColor(properties.getProperty(prefix + "color1x3"), color1x3);
        show1x1 = readBoolean(properties.getProperty(prefix + "show1x1"), show1x1);
        show1x3 = readBoolean(properties.getProperty(prefix + "show1x3"), show1x3);
        String value = properties.getProperty(prefix + "opacity");
        if (value != null) {
            try {
                setOpacity(Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }

    private static int readColor(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim(), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean readBoolean(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
