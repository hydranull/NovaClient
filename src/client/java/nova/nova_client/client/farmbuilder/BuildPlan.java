package nova.nova_client.client.farmbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eine Bauvorlage an einer festen Stelle in der Welt: Ursprung (Ecke unten Nord-West) und Drehung in 90-Grad-Schritten.
 * Kennt alle zu setzenden Bloecke mit Weltposition und gedrehtem Zustand sowie die benoetigten Materialien.
 */
public final class BuildPlan {
    public record Target(BlockPos pos, BlockState state, Materials.Need need, boolean waterlogged) {
    }

    public final Schematic schematic;
    public final BlockPos origin;
    /** 0 = wie in der Datei, 1 = 90 Grad im Uhrzeigersinn, 2 = 180, 3 = 270. */
    public final int rotation;
    /** Groesse in der Welt (nach dem Drehen). */
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    /** Zu setzende Bloecke, sortiert von unten nach oben. */
    public final List<Target> targets;
    /** Benoetigte Items, haeufigste zuerst. */
    public final Map<Item, Integer> materials;
    /** Zu platzierende Wasser-/Lava-Quellen (inkl. wassergeloggter Bloecke). */
    public final int fluidCount;
    /** Bloecke, die der Bot nicht baut (Feuer, Portale, Seegras ...). */
    public final int skippedCount;
    /** Gesperrte Crafter-Felder (0..8) je Weltposition - stellt der Bot nach dem Setzen ein. */
    public final Map<BlockPos, int[]> crafterSlots;
    private RedstoneOrder order;
    public final boolean needsHoe;
    public final boolean needsShovel;
    /** Hoechster Farmblock je Spalte (Welt-Y), Integer.MIN_VALUE = Spalte ist leer. */
    private final int[] top;

    public BuildPlan(Schematic schematic, BlockPos origin, int rotation) {
        this.schematic = schematic;
        this.origin = origin;
        this.rotation = Math.floorMod(rotation, 4);
        boolean swap = this.rotation % 2 == 1;
        this.sizeX = swap ? schematic.sizeZ : schematic.sizeX;
        this.sizeY = schematic.sizeY;
        this.sizeZ = swap ? schematic.sizeX : schematic.sizeZ;

        Rotation rot = switch (this.rotation) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        List<Target> list = new ArrayList<>();
        Map<Item, Integer> counts = new HashMap<>();
        int fluids = 0;
        int skipped = 0;
        boolean hoe = false;
        boolean shovel = false;
        for (int y = 0; y < schematic.sizeY; y++) {
            for (int z = 0; z < schematic.sizeZ; z++) {
                for (int x = 0; x < schematic.sizeX; x++) {
                    BlockState raw = schematic.get(x, y, z);
                    if (raw == null) {
                        continue;
                    }
                    BlockState state = raw.rotate(rot);
                    Materials.Need need = Materials.of(state);
                    if (need.kind() == Materials.Kind.SKIP) {
                        skipped++;
                        continue;
                    }
                    boolean logged = Materials.waterlogged(state);
                    BlockPos pos = toWorld(x, y, z);
                    list.add(new Target(pos, state, need, logged));
                    counts.merge(need.item(), need.count(), Integer::sum);
                    if (need.kind() == Materials.Kind.FLUID) {
                        fluids++;
                    }
                    if (logged) {
                        fluids++;
                        counts.merge(Items.WATER_BUCKET, 1, Integer::sum);
                    }
                    hoe |= Materials.needsHoe(state);
                    shovel |= Materials.needsShovel(state);
                }
            }
        }
        this.targets = Collections.unmodifiableList(list);
        this.top = new int[sizeX * sizeZ];
        java.util.Arrays.fill(top, Integer.MIN_VALUE);
        for (Target t : list) {
            int i = (t.pos().getZ() - origin.getZ()) * sizeX + (t.pos().getX() - origin.getX());
            top[i] = Math.max(top[i], t.pos().getY());
        }
        this.fluidCount = fluids;
        this.skippedCount = skipped;
        Map<BlockPos, int[]> crafters = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : schematic.extras.crafterSlots().entrySet()) {
            int i = e.getKey();
            int x = i % schematic.sizeX;
            int z = i / schematic.sizeX % schematic.sizeZ;
            int y = i / (schematic.sizeX * schematic.sizeZ);
            BlockState raw = schematic.get(x, y, z);
            if (raw != null && raw.getBlock() instanceof CrafterBlock) {
                // Das Raster dreht sich mit dem Crafter - die Feldnummern bleiben gleich
                crafters.put(toWorld(x, y, z), e.getValue());
            }
        }
        this.crafterSlots = Collections.unmodifiableMap(crafters);
        this.needsHoe = hoe;
        this.needsShovel = shovel;

        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        Map<Item, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : entries) {
            sorted.put(e.getKey(), e.getValue());
        }
        this.materials = Collections.unmodifiableMap(sorted);
    }

    /** Redstone-sichere Reihenfolge (erst beim Start gerechnet - beim Verschieben der Vorschau nicht noetig). */
    public RedstoneOrder order() {
        if (order == null) {
            order = new RedstoneOrder(targets);
        }
        return order;
    }

    /** Lokale Schematic-Koordinate -> Weltposition. */
    public BlockPos toWorld(int x, int y, int z) {
        int sx = schematic.sizeX;
        int sz = schematic.sizeZ;
        int wx;
        int wz;
        switch (rotation) {
            case 1 -> {
                wx = sz - 1 - z;
                wz = x;
            }
            case 2 -> {
                wx = sx - 1 - x;
                wz = sz - 1 - z;
            }
            case 3 -> {
                wx = z;
                wz = sx - 1 - x;
            }
            default -> {
                wx = x;
                wz = z;
            }
        }
        return origin.offset(wx, y, wz);
    }

    public boolean contains(BlockPos pos) {
        int dx = pos.getX() - origin.getX();
        int dy = pos.getY() - origin.getY();
        int dz = pos.getZ() - origin.getZ();
        return dx >= 0 && dy >= 0 && dz >= 0 && dx < sizeX && dy < sizeY && dz < sizeZ;
    }

    /** Gewuenschter (gedrehter) Zustand an einer Weltposition, null = Luft oder ausserhalb. */
    public BlockState stateAt(BlockPos pos) {
        if (!contains(pos)) {
            return null;
        }
        int lx = pos.getX() - origin.getX();
        int y = pos.getY() - origin.getY();
        int lz = pos.getZ() - origin.getZ();
        int sx = schematic.sizeX;
        int sz = schematic.sizeZ;
        int x;
        int z;
        switch (rotation) {
            case 1 -> {
                x = lz;
                z = sz - 1 - lx;
            }
            case 2 -> {
                x = sx - 1 - lx;
                z = sz - 1 - lz;
            }
            case 3 -> {
                x = sx - 1 - lz;
                z = lx;
            }
            default -> {
                x = lx;
                z = lz;
            }
        }
        BlockState raw = schematic.get(x, y, z);
        if (raw == null) {
            return null;
        }
        return switch (rotation) {
            case 1 -> raw.rotate(Rotation.CLOCKWISE_90);
            case 2 -> raw.rotate(Rotation.CLOCKWISE_180);
            case 3 -> raw.rotate(Rotation.COUNTERCLOCKWISE_90);
            default -> raw;
        };
    }

    /** Hoechster Farmblock in dieser Spalte, Integer.MIN_VALUE = ausserhalb der Grundflaeche oder leer. */
    public int topY(int x, int z) {
        int dx = x - origin.getX();
        int dz = z - origin.getZ();
        if (dx < 0 || dz < 0 || dx >= sizeX || dz >= sizeZ) {
            return Integer.MIN_VALUE;
        }
        return top[dz * sizeX + dx];
    }

    public AABB box() {
        return new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + sizeX, origin.getY() + sizeY, origin.getZ() + sizeZ);
    }

    public BlockPos max() {
        return origin.offset(sizeX - 1, sizeY - 1, sizeZ - 1);
    }

    /** Anzahl Items insgesamt (ohne Fluessigkeiten). */
    public int totalPlacements() {
        int total = 0;
        for (Target target : targets) {
            if (target.need().kind() == Materials.Kind.PLACE) {
                total += target.need().count();
            }
        }
        return total;
    }
}
