package nova.nova_client.client.tunnelfinder;

import nova.nova_client.client.Compat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Erkennt echte Bases in den geladenen Chunks - nicht jede einzelne Kiste. Ablauf:
 * <ol>
 *     <li>Block-Entities aller Chunks im Radius einsammeln (schnell, verteilt ueber Ticks). Nur Bloecke, die etwas
 *     ueber eine Base aussagen (Lager, Shulker, Betten, Oefen, Schilder, ...), und nur bis zur eingestellten Hoehe.</li>
 *     <li>Nahe Funde zu Gruppen zusammenfassen (8er-Zellen, Nachbarzellen gehoeren zusammen). Gruppen mit zu wenig
 *     Punkten (z.B. eine einzelne Kiste) werden gar nicht weiter angeschaut.</li>
 *     <li>Den Bereich um eine Gruppe Block fuer Block auswerten: Punkte je Kategorie (Lager, Persoenliches,
 *     Werkbaenke, Redstone, Licht, Entities wie Item Frames/Villager), jeweils gedeckelt. Gezaehlt wird nur, was an
 *     einen offenen Block grenzt - Anti-X-Ray faelscht nur verdeckte Bloecke.</li>
 *     <li>Natuerliche Strukturen (Dungeon, Mineshaft, Ancient City, Trial Chamber, Stronghold) erkennt man an
 *     Spawnern, Vaults, Sculk-Shriekern, Reinforced Deepslate, Cobwebs usw. Dort zaehlt es nur als Base, wenn
 *     zusaetzlich deutlich Spieler-Kram herumsteht.</li>
 * </ol>
 * Base = genug Punkte und mindestens zwei Kategorien (oder ein grosses reines Lager).
 */
public final class BaseDetector {
    /** Eine gefundene Base: Bereich, Punkte und eine kurze Beschreibung ("23x storage, 4x shulker, ..."). */
    public record Base(BlockPos center, AABB box, int score, String summary) {
    }

    // Kategorien
    private static final int STORAGE = 0;
    private static final int PERSONAL = 1;
    private static final int UTILITY = 2;
    private static final int REDSTONE = 3;
    private static final int LIGHT = 4;
    private static final int ENTITY = 5;
    private static final int CATEGORIES = 6;
    /** Hoechstpunkte je Kategorie (200 Fackeln allein sind keine Base). */
    private static final float[] CAP = {40.0F, 100.0F, 15.0F, 8.0F, 5.0F, 15.0F};
    /** Ab so vielen sichtbaren Lager-Bloecken zaehlt auch ein reines Lager als Base. */
    private static final int STASH_STORAGE = 12;
    /** Gruppen unter diesen (ungedeckelten) Punkten werden nicht genauer angeschaut. */
    private static final float QUICK_MIN = 3.0F;

    private static final int MAX_CHUNK_RADIUS = 16;
    /** Mehr als das braucht kein Modul (der Donut Base Finder darf bis 32 Chunks scannen). */
    private static final int HARD_CHUNK_RADIUS = 32;
    private static final int MAX_BASES = 128;
    private static final int CHUNKS_PER_TICK = 8;
    private static final int CELL_SHIFT = 3;
    private static final int GROUP_TICKS = 40;
    private static final int RECHECK_TICKS = 200;
    private static final int MAX_BOX = 48;

    /** Was ein Block ueber eine Base aussagt. perUnit: so viele Bloecke sind ein Stueck (Bett = 2 Haelften). */
    private record Evidence(int category, float points, String label, int perUnit) {
    }

    private static final Evidence LIGHT_EVIDENCE = new Evidence(LIGHT, 0.4F, "light", 1);
    private static Map<Block, Evidence> table;

    private final Map<Long, List<BlockPos>> found = new HashMap<>();
    private final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private final ArrayDeque<List<BlockPos>> toEvaluate = new ArrayDeque<>();
    /** Zuletzt ausgewertete Gruppen: Anker-Zelle -> {Tick, Punkte*10}. */
    private final Map<Long, long[]> evaluated = new HashMap<>();
    private final List<Base> bases = new ArrayList<>();
    private int tick;
    private int groupTicks;

    public void reset() {
        found.clear();
        queue.clear();
        toEvaluate.clear();
        evaluated.clear();
        bases.clear();
    }

    /** Alle bisher gefundenen Bases (fuer Anzeige und "schon gemeldet"). */
    public List<Base> bases() {
        return bases;
    }

    /** Liegt hier schon eine bekannte Base? */
    private Base known(AABB box) {
        for (Base base : bases) {
            if (base.box().inflate(8.0D).intersects(box)) {
                return base;
            }
        }
        return null;
    }

    /**
     * Einen Schritt weitersuchen. Gibt eine neu gefundene Base zurueck (jede nur einmal), sonst null.
     * maxY: nur Bases bis zu dieser Hoehe, minScore: so viele Punkte braucht eine Base.
     */
    public Base tick(ClientLevel level, LocalPlayer player, int renderDistance, int maxY, int minScore) {
        return tick(level, player, renderDistance, MAX_CHUNK_RADIUS, maxY, minScore);
    }

    /**
     * Wie {@link #tick(ClientLevel, LocalPlayer, int, int, int)}, aber mit eigenem Radius (hoechstens 32). So kann der
     * Donut Base Finder die ganze Sichtweite absuchen, waehrend der Tunnel-Bot bei seinen 16 Chunks bleibt.
     */
    public Base tick(ClientLevel level, LocalPlayer player, int renderDistance, int maxRadius, int maxY, int minScore) {
        tick++;
        int radius = Math.max(1, Math.min(Math.min(HARD_CHUNK_RADIUS, maxRadius), renderDistance));
        int centerX = player.blockPosition().getX() >> 4;
        int centerZ = player.blockPosition().getZ() >> 4;
        found.keySet().removeIf(key -> {
            ChunkPos pos = Compat.chunkPos(key);
            return Math.abs(Compat.chunkX(pos) - centerX) > radius + 1 || Math.abs(Compat.chunkZ(pos) - centerZ) > radius + 1;
        });

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
            scanChunk(level, (int) next[0], (int) next[1], maxY);
        }

        if (++groupTicks >= GROUP_TICKS) {
            groupTicks = 0;
            group(level);
        }
        // Hoechstens eine Gruppe pro Tick genau auswerten (das liest bis zu 48^3 Bloecke)
        List<BlockPos> group = toEvaluate.poll();
        if (group == null) {
            return null;
        }
        Base base = evaluate(level, group, maxY, minScore);
        if (base == null || known(base.box()) != null) {
            return null;
        }
        bases.add(base);
        while (bases.size() > MAX_BASES) {
            bases.remove(0);
        }
        return base;
    }

    // ---------------------------------------------------------------- 1. Block-Entities einsammeln

    private void scanChunk(ClientLevel level, int chunkX, int chunkZ, int maxY) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (!level.hasChunk(chunkX, chunkZ)) {
            found.remove(key);
            return;
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            if (pos.getY() <= maxY && evidence(chunk.getBlockState(pos).getBlock()) != null) {
                result.add(pos.immutable());
            }
        }
        if (result.isEmpty()) {
            found.remove(key);
        } else {
            found.put(key, result);
        }
    }

    // ---------------------------------------------------------------- 2. Gruppen bilden

    private void group(ClientLevel level) {
        // Funde je 8er-Zelle
        Map<Long, List<BlockPos>> cells = new HashMap<>();
        for (List<BlockPos> list : found.values()) {
            for (BlockPos pos : list) {
                long cell = BlockPos.asLong(pos.getX() >> CELL_SHIFT, pos.getY() >> CELL_SHIFT, pos.getZ() >> CELL_SHIFT);
                cells.computeIfAbsent(cell, k -> new ArrayList<>()).add(pos);
            }
        }
        // Zusammenhaengende Zellen (auch diagonal) zu Gruppen
        Map<Long, Boolean> seen = new HashMap<>();
        for (Long start : cells.keySet()) {
            if (seen.containsKey(start)) {
                continue;
            }
            List<BlockPos> members = new ArrayList<>();
            ArrayDeque<Long> open = new ArrayDeque<>();
            open.add(start);
            seen.put(start, true);
            long anchor = start;
            while (!open.isEmpty()) {
                long cell = open.poll();
                anchor = Math.min(anchor, cell);
                members.addAll(cells.get(cell));
                int cx = BlockPos.getX(cell);
                int cy = BlockPos.getY(cell);
                int cz = BlockPos.getZ(cell);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long other = BlockPos.asLong(cx + dx, cy + dy, cz + dz);
                            if (cells.containsKey(other) && seen.putIfAbsent(other, true) == null) {
                                open.add(other);
                            }
                        }
                    }
                }
            }
            float quick = 0.0F;
            for (BlockPos pos : members) {
                Evidence e = evidence(level.getBlockState(pos).getBlock());
                if (e != null) {
                    quick += e.points();
                }
            }
            if (quick < QUICK_MIN) {
                continue; // z.B. eine einzelne Kiste
            }
            // Gleiche Gruppe nicht dauernd neu auswerten - nur wenn sie gewachsen ist oder laenger her
            long[] last = evaluated.get(anchor);
            long quickKey = Math.round(quick * 10.0F);
            if (last != null && tick - last[0] < RECHECK_TICKS && last[1] == quickKey) {
                continue;
            }
            evaluated.put(anchor, new long[]{tick, quickKey});
            toEvaluate.add(members);
        }
        evaluated.values().removeIf(v -> tick - v[0] > RECHECK_TICKS * 4L);
    }

    // ---------------------------------------------------------------- 3. Genau auswerten

    private static Base evaluate(ClientLevel level, List<BlockPos> members, int maxY, int minScore) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxYb = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : members) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxYb = Math.max(maxYb, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        // Umgebung mit auswerten (Werkbaenke, Fackeln, ... haben keine Block-Entity), Groesse begrenzen
        int[] x = clampRange(minX - 6, maxX + 6);
        int[] z = clampRange(minZ - 6, maxZ + 6);
        int worldTop = level.getMinY() + level.getHeight() - 1;
        int[] y = clampRange(Math.max(level.getMinY(), minY - 4), Math.min(Math.min(worldTop, maxY + 4), maxYb + 4));

        BaseBlocks.Dimension dimension = BaseBlocks.dimensionOf(level);
        float[] points = new float[CATEGORIES];
        Map<String, float[]> labels = new LinkedHashMap<>();
        int storage = 0;
        int structure = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = x[0]; bx <= x[1]; bx++) {
            for (int bz = z[0]; bz <= z[1]; bz++) {
                if (!level.hasChunk(bx >> 4, bz >> 4)) {
                    continue;
                }
                for (int by = y[0]; by <= y[1]; by++) {
                    pos.set(bx, by, bz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    Block block = state.getBlock();
                    int flags = BaseBlocks.flags(state);
                    structure += structureWeight(block, flags);
                    Evidence e = evidence(block);
                    if (e == null && BaseBlocks.isArtificialLight(state, flags, dimension)) {
                        e = LIGHT_EVIDENCE;
                    }
                    if (e == null || !exposed(level, pos)) {
                        continue;
                    }
                    Evidence hit = e;
                    points[hit.category()] += hit.points();
                    if (hit.category() == STORAGE) {
                        storage++;
                    }
                    float[] entry = labels.computeIfAbsent(hit.label(), k -> new float[]{0.0F, 0.0F, hit.perUnit()});
                    entry[0] += 1.0F;
                    entry[1] += hit.points();
                }
            }
        }

        // Entities: Item Frames, Armor Stands, Villager (unter der Erde nur von Spielern hergebracht)
        AABB area = new AABB(x[0], y[0], z[0], x[1] + 1, y[1] + 1, z[1] + 1).inflate(4.0D);
        for (Entity entity : level.entitiesForRendering()) {
            if (!area.contains(entity.position())) {
                continue;
            }
            String label = null;
            float value = 0.0F;
            if (entity instanceof ItemFrame) { // inkl. Glow Item Frame
                label = "item frame";
                value = 2.0F;
            } else if (entity instanceof ArmorStand) {
                label = "armor stand";
                value = 2.0F;
            } else if (entity instanceof Villager && entity.getY() <= maxY) {
                label = "villager";
                value = 3.0F;
            }
            if (label != null) {
                points[ENTITY] += value;
                float[] entry = labels.computeIfAbsent(label, k -> new float[]{0.0F, 0.0F, 1});
                entry[0] += 1.0F;
                entry[1] += value;
            }
        }

        float score = 0.0F;
        int categories = 0;
        for (int i = 0; i < CATEGORIES; i++) {
            float capped = Math.min(CAP[i], points[i]);
            score += capped;
            if (capped >= 0.99F) {
                categories++;
            }
        }
        // Natuerliche Struktur? Dann nur mit deutlich Spieler-Kram
        float playerStuff = points[PERSONAL] + points[UTILITY] + points[REDSTONE] + points[ENTITY];
        if (structure >= 3 && playerStuff < 8.0F) {
            return null;
        }
        if (score < minScore || categories < 2 && storage < STASH_STORAGE) {
            return null;
        }
        AABB box = new AABB(minX, minY, minZ, maxX + 1, maxYb + 1, maxZ + 1);
        BlockPos center = BlockPos.containing(box.getCenter());
        return new Base(center, box, Math.round(score), summary(labels));
    }

    private static int[] clampRange(int from, int to) {
        if (to - from + 1 > MAX_BOX) {
            int mid = (from + to) / 2;
            from = mid - MAX_BOX / 2;
            to = from + MAX_BOX - 1;
        }
        return new int[]{from, to};
    }

    /** Grenzt der Block an etwas Offenes? (Anti-X-Ray ersetzt nur komplett verdeckte Bloecke.) */
    private static boolean exposed(ClientLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(pos, d);
            if (!BaseBlocks.isSolid(level.getBlockState(n))) {
                return true;
            }
        }
        return false;
    }

    /** Wie stark spricht der Block fuer eine natuerliche Struktur? (Schienen allein nicht - die bauen auch Spieler.) */
    private static int structureWeight(Block block, int flags) {
        if (block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER || block == Blocks.VAULT
                || block == Blocks.REINFORCED_DEEPSLATE || block == Blocks.SCULK_SHRIEKER
                || block == Blocks.END_PORTAL_FRAME) {
            return 3;
        }
        if ((flags & BaseBlocks.STRONG_MARKER) != 0 && block != Blocks.RAIL) {
            return 1; // Cobweb, Infested, Suspicious Sand/Gravel, Mud Bricks
        }
        return 0;
    }

    /** "23x storage, 4x shulker, 2x bed, ..." - die wichtigsten Funde zuerst. */
    private static String summary(Map<String, float[]> labels) {
        List<Map.Entry<String, float[]>> sorted = new ArrayList<>(labels.entrySet());
        sorted.sort((a, b) -> Float.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(6, sorted.size()); i++) {
            float[] v = sorted.get(i).getValue();
            int count = (int) Math.ceil(v[0] / v[2]);
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(count).append("x ").append(sorted.get(i).getKey());
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- Einordnung der Bloecke

    private static Evidence evidence(Block block) {
        if (table == null) {
            Map<Block, Evidence> map = new IdentityHashMap<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                Evidence e = classify(BuiltInRegistries.BLOCK.getKey(b).getPath());
                if (e != null) {
                    map.put(b, e);
                }
            }
            table = map;
        }
        return table.get(block);
    }

    private static Evidence classify(String p) {
        // Lager (natuerlich auch in Strukturen - zaehlt nur einfach)
        if (p.equals("chest") || p.equals("trapped_chest") || p.equals("barrel")) {
            return new Evidence(STORAGE, 1.0F, "storage", 1);
        }
        // Persoenliches: wird unter der Erde nie generiert
        if (p.endsWith("shulker_box")) {
            return new Evidence(PERSONAL, 5.0F, "shulker", 1);
        }
        if (p.endsWith("_bed")) {
            return new Evidence(PERSONAL, 2.0F, "bed", 2);
        }
        if (p.endsWith("_sign")) {
            return new Evidence(PERSONAL, 1.5F, "sign", 1);
        }
        if (p.endsWith("_banner")) {
            return new Evidence(PERSONAL, 1.5F, "banner", 1);
        }
        switch (p) {
            case "ender_chest":
                return new Evidence(PERSONAL, 4.0F, "ender chest", 1);
            case "beacon":
                return new Evidence(PERSONAL, 8.0F, "beacon", 1);
            case "enchanting_table":
                return new Evidence(PERSONAL, 4.0F, "enchanting table", 1);
            case "anvil":
            case "chipped_anvil":
            case "damaged_anvil":
                return new Evidence(PERSONAL, 3.0F, "anvil", 1);
            case "brewing_stand":
                return new Evidence(PERSONAL, 3.0F, "brewing stand", 1);
            case "crafting_table":
                return new Evidence(PERSONAL, 2.0F, "crafting table", 1);
            case "respawn_anchor":
                return new Evidence(PERSONAL, 4.0F, "respawn anchor", 1);
            case "lodestone":
                return new Evidence(PERSONAL, 3.0F, "lodestone", 1);
            case "conduit":
                return new Evidence(PERSONAL, 4.0F, "conduit", 1);
            case "jukebox":
                return new Evidence(PERSONAL, 2.0F, "jukebox", 1);
            case "smithing_table":
            case "grindstone":
            case "stonecutter":
            case "loom":
            case "cartography_table":
            case "fletching_table":
            case "lectern":
                return new Evidence(PERSONAL, 1.5F, "workstation", 1);
            case "netherite_block":
                return new Evidence(PERSONAL, 6.0F, "netherite block", 1);
            case "diamond_block":
                return new Evidence(PERSONAL, 5.0F, "diamond block", 1);
            case "emerald_block":
                return new Evidence(PERSONAL, 4.0F, "emerald block", 1);
            case "gold_block":
                return new Evidence(PERSONAL, 3.0F, "gold block", 1);
            case "iron_block":
                return new Evidence(PERSONAL, 2.0F, "iron block", 1);
            case "nether_portal":
                return new Evidence(PERSONAL, 1.0F, "nether portal", 4);
            // Nuetzliches (Oefen, Trichter, ...)
            case "furnace":
            case "smoker":
            case "blast_furnace":
                return new Evidence(UTILITY, 2.0F, "furnace", 1);
            case "hopper":
                return new Evidence(UTILITY, 1.0F, "hopper", 1);
            case "crafter":
                return new Evidence(UTILITY, 1.0F, "crafter", 1);
            case "dropper":
            case "dispenser":
                return new Evidence(UTILITY, 0.5F, "dispenser", 1);
            case "cauldron":
            case "water_cauldron":
            case "lava_cauldron":
            case "composter":
            case "chiseled_bookshelf":
                return new Evidence(UTILITY, 0.5F, "utility", 1);
            // Redstone (Farmen, Tueren, Lager-Sortierer)
            case "redstone_wire":
            case "repeater":
            case "comparator":
            case "observer":
            case "piston":
            case "sticky_piston":
            case "lever":
            case "redstone_lamp":
            case "target":
            case "daylight_detector":
            case "note_block":
            case "tripwire_hook":
            case "redstone_torch":
            case "redstone_wall_torch":
            case "redstone_block":
                return new Evidence(REDSTONE, 0.5F, "redstone", 1);
            default:
                return null;
        }
    }
}
