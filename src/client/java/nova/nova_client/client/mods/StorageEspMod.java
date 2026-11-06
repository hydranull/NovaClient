package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.StorageEspMenu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Storage ESP: zeigt Lager-Bloecke (Kisten, Faesser, Shulker, Spawner, ...) mit farbigem Umriss durch Waende.
 * <p>
 * Gefunden wird direkt beim Laden eines Chunks und bei jeder Blockaenderung vom Server - nichts wird immer wieder
 * durchsucht. Was einmal gefunden wurde, merkt sich Nova je Server und Dimension auch dann, wenn der Chunk wieder
 * entladen ist (blasser gezeichnet), bis zur eingestellten Reichweite. Doppelkisten bekommen eine gemeinsame Box.
 */
public class StorageEspMod extends GameTickMod {
    public enum Type {
        BARREL("Barrel", 0xC98B4F, Items.BARREL, b -> b == Blocks.BARREL),
        BLAST_FURNACE("Blast Furnace", 0x8C9AAE, Items.BLAST_FURNACE, b -> b == Blocks.BLAST_FURNACE),
        BREWING_STAND("Brewing Stand", 0xE0C040, Items.BREWING_STAND, b -> b == Blocks.BREWING_STAND),
        CAMPFIRE("Campfire", 0xFF8A3D, Items.CAMPFIRE, b -> b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE),
        CHEST("Chest", 0xFFA040, Items.CHEST, b -> b == Blocks.CHEST),
        CHISELED_BOOKSHELF("Chiseled Bookshelf", 0xB5895A, Items.CHISELED_BOOKSHELF, b -> b == Blocks.CHISELED_BOOKSHELF),
        CRAFTER("Crafter", 0xD06A4A, Items.CRAFTER, b -> b == Blocks.CRAFTER),
        DISPENSER("Dispenser", 0xA8A8A8, Items.DISPENSER, b -> b == Blocks.DISPENSER),
        DECORATED_POT("Decorated Pot", 0xD9825B, Items.DECORATED_POT, b -> b == Blocks.DECORATED_POT),
        DROPPER("Dropper", 0x9A9A9A, Items.DROPPER, b -> b == Blocks.DROPPER),
        ENCHANTING_TABLE("Enchanting Table", 0xE0415F, Items.ENCHANTING_TABLE, b -> b == Blocks.ENCHANTING_TABLE),
        ENDER_CHEST("Ender Chest", 0xB45CF6, Items.ENDER_CHEST, b -> b == Blocks.ENDER_CHEST),
        FURNACE("Furnace", 0x7F7F7F, Items.FURNACE, b -> b == Blocks.FURNACE),
        HOPPER("Hopper", 0x5A6470, Items.HOPPER, b -> b == Blocks.HOPPER),
        SHULKER_BOX("Shulker Box", 0xFF5FD2, Items.SHULKER_BOX, b -> b instanceof ShulkerBoxBlock),
        SMOKER("Smoker", 0x6E5A48, Items.SMOKER, b -> b == Blocks.SMOKER),
        SPAWNER("Spawner", 0x8D63FF, Items.SPAWNER, b -> b == Blocks.SPAWNER),
        TRAPPED_CHEST("Trapped Chest", 0xFF4D4D, Items.TRAPPED_CHEST, b -> b == Blocks.TRAPPED_CHEST);

        public final String label;
        public final int color;
        private final Item icon;
        private final Predicate<Block> matches;

        Type(String label, int color, Item icon, Predicate<Block> matches) {
            this.label = label;
            this.color = color;
            this.icon = icon;
            this.matches = matches;
        }

        /** Symbol fuers Menue (im Hauptmenue noch leer - dort gibt es noch keine Item-Daten). */
        public ItemStack icon() {
            return NovaGui.stack(icon);
        }

        public static Type of(Block block) {
            for (Type type : values()) {
                if (type.matches.test(block)) {
                    return type;
                }
            }
            return null;
        }
    }

    private static final int MAX_OUTLINES = 2000;
    private static final int MAX_TRACERS = 64;
    private static final int REFRESH_TICKS = 5;
    /** So viele Chunks mit Fundstellen je Server und Dimension hoechstens (die aeltesten fliegen zuerst raus). */
    private static final int MAX_CHUNKS = 20_000;

    // Einstellungen
    public static final Set<Type> SELECTED = EnumSet.allOf(Type.class);
    public static boolean tracers = false;
    /** Deckkraft der Box-Fuellung in Prozent (0 = nur Umriss). */
    public static int opacity = 20;
    /** Bis zu dieser Entfernung (Bloecke) werden Fundstellen gezeigt - auch gemerkte aus entladenen Chunks. */
    public static int range = 256;

    private static volatile boolean enabled;
    /** Fundstellen je Dimension des aktuellen Servers, nach Chunk sortiert: Chunk -> (BlockPos.asLong -> Art). */
    private static final Map<ResourceKey<Level>, Map<Long, Map<Long, Type>>> FOUND = new HashMap<>();
    private static String server = "";
    private static boolean dirty = true;
    private static int refreshTicks;

    // Fertige Anzeige (im Tick gebaut, pro Frame nur gezeichnet)
    private static AABB[] shown = new AABB[0];
    private static int[] shownColors = new int[0];
    private static GizmoStyle[] shownStyles = new GizmoStyle[0];

    public StorageEspMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Type type) {
        if (!SELECTED.remove(type)) {
            SELECTED.add(type);
        }
        dirty = true;
    }

    public static void selectAll(boolean all) {
        SELECTED.clear();
        if (all) {
            SELECTED.addAll(EnumSet.allOf(Type.class));
        }
        dirty = true;
    }

    public static void setOpacity(int percent) {
        opacity = Math.max(0, Math.min(100, percent));
        dirty = true;
    }

    public static void setRange(int blocks) {
        range = Math.max(32, Math.min(1024, blocks));
        dirty = true;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        StorageEspMod.enabled = enabled;
        FOUND.clear();
        shown = new AABB[0];
        dirty = true;
        if (enabled && client.level != null && client.player != null) {
            // Was schon geladen ist, sofort einlesen
            int radius = client.options.getEffectiveRenderDistance() + 1;
            int cx = client.player.blockPosition().getX() >> 4;
            int cz = client.player.blockPosition().getZ() >> 4;
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    LevelChunk chunk = client.level.getChunkSource().getChunkNow(x, z);
                    if (chunk != null) {
                        onChunkLoaded(chunk);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Finden (Ereignisse)

    /** Frisch vom Server geladener Chunk (LevelChunkMixin): Fundstellen dieses Chunks neu einlesen. */
    public static void onChunkLoaded(LevelChunk chunk) {
        if (!enabled || !(chunk.getLevel() instanceof ClientLevel level)) {
            return;
        }
        Map<Long, Map<Long, Type>> found = found(level);
        long chunkKey = chunkKey(chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
        // Alte Eintraege dieses Chunks verwerfen (abgebaut, waehrend er entladen war) und neu einlesen
        Map<Long, Type> inChunk = new HashMap<>();
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            Type type = Type.of(blockEntity.getBlockState().getBlock());
            if (type != null) {
                inChunk.put(blockEntity.getBlockPos().asLong(), type);
            }
        }
        Map<Long, Type> previous = found.remove(chunkKey);
        if (!inChunk.isEmpty()) {
            remember(found, chunkKey, inChunk);
        }
        if (previous != null || !inChunk.isEmpty()) {
            dirty = true;
        }
    }

    /** Block vom Server geaendert (SusChunkFinderPacketMixin): Lager dazu oder weg. */
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null) {
            return;
        }
        Map<Long, Map<Long, Type>> found = found(client.level);
        long chunkKey = chunkKey(pos.getX(), pos.getZ());
        Type type = Type.of(state.getBlock());
        Map<Long, Type> inChunk = found.get(chunkKey);
        if (type != null) {
            if (inChunk == null) {
                inChunk = new HashMap<>();
                remember(found, chunkKey, inChunk);
            }
            inChunk.put(pos.asLong(), type);
            dirty = true;
        } else if (inChunk != null && inChunk.remove(pos.asLong()) != null) {
            if (inChunk.isEmpty()) {
                found.remove(chunkKey);
            }
            dirty = true;
        }
    }

    private static long chunkKey(int blockX, int blockZ) {
        return ((long) (blockX >> 4) << 32) | ((blockZ >> 4) & 0xFFFFFFFFL);
    }

    private static void remember(Map<Long, Map<Long, Type>> found, long chunkKey, Map<Long, Type> inChunk) {
        found.put(chunkKey, inChunk);
        if (found.size() > MAX_CHUNKS) {
            Iterator<Long> oldest = found.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Fundstellen der aktuellen Dimension; anderer Server = alles vergessen. */
    private static Map<Long, Map<Long, Type>> found(ClientLevel level) {
        String id = NovaNotify.serverId();
        if (!id.equals(server)) {
            server = id;
            FOUND.clear();
        }
        return FOUND.computeIfAbsent(level.dimension(), key -> new java.util.LinkedHashMap<>());
    }

    // ---------------------------------------------------------------- Anzeige (im Tick)

    @Override
    public void execute(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        if (dirty || ++refreshTicks >= REFRESH_TICKS) {
            dirty = false;
            refreshTicks = 0;
            rebuildShown(client.level, client.player.getEyePosition());
        }
    }

    private record Hit(long key, Type type, double distance) {
    }

    /** Sortiert nach Entfernung, Doppelkisten zusammengefasst, entladene Fundstellen blasser. */
    private static void rebuildShown(ClientLevel level, Vec3 eye) {
        List<Hit> hits = new ArrayList<>();
        double maxSq = (double) range * range;
        for (Map<Long, Type> inChunk : found(level).values()) {
            for (Map.Entry<Long, Type> entry : inChunk.entrySet()) {
                if (!SELECTED.contains(entry.getValue())) {
                    continue;
                }
                long key = entry.getKey();
                double dx = BlockPos.getX(key) + 0.5D - eye.x;
                double dy = BlockPos.getY(key) + 0.5D - eye.y;
                double dz = BlockPos.getZ(key) + 0.5D - eye.z;
                double distance = dx * dx + dy * dy + dz * dz;
                if (distance <= maxSq) {
                    hits.add(new Hit(key, entry.getValue(), distance));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        int fillAlpha = Math.round(opacity * 2.55F);
        List<AABB> boxes = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<GizmoStyle> styles = new ArrayList<>();
        Map<Integer, GizmoStyle> cache = new HashMap<>();
        for (Hit hit : hits) {
            if (boxes.size() >= MAX_OUTLINES) {
                break;
            }
            BlockPos pos = BlockPos.of(hit.key());
            boolean loaded = level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
            AABB box = loaded ? box(level, pos) : new AABB(pos);
            if (box == null) {
                continue; // zweite Haelfte einer Doppelkiste
            }
            int strokeAlpha = loaded ? 0xFF : 0x80;
            int key = hit.type().ordinal() * 2 + (loaded ? 0 : 1);
            boxes.add(box);
            colors.add((strokeAlpha << 24) | hit.type().color);
            styles.add(cache.computeIfAbsent(key, k -> GizmoStyle.strokeAndFill((strokeAlpha << 24) | hit.type().color,
                    2.0F, ((loaded ? fillAlpha : fillAlpha / 2) << 24) | hit.type().color)));
        }
        shown = boxes.toArray(new AABB[0]);
        shownColors = colors.stream().mapToInt(Integer::intValue).toArray();
        shownStyles = styles.toArray(new GizmoStyle[0]);
    }

    /** Umriss des Blocks (Kiste = schmaler als ein Block); Doppelkiste einmal ueber beide Haelften, sonst null. */
    private static AABB box(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) {
                return null;
            }
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            VoxelShape otherShape = level.getBlockState(other).getShape(level, other);
            box = box.minmax(otherShape.isEmpty() ? new AABB(other) : otherShape.bounds().move(other));
        }
        return box;
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        AABB[] boxes = shown;
        if (!enabled || client.level == null || client.player == null || boxes.length == 0) {
            return;
        }
        int[] colors = shownColors;
        GizmoStyle[] styles = shownStyles;
        net.minecraft.client.renderer.culling.Frustum view = nova.nova_client.client.ViewCull.frustum();
        for (int i = 0; i < boxes.length; i++) {
            AABB box = boxes[i];
            if (nova.nova_client.client.ViewCull.visible(view, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 0.0F)) {
                Gizmos.cuboid(box, styles[i]).setAlwaysOnTop();
            }
        }
        if (tracers) {
            Vec3 start = nova.nova_client.client.NovaTracers.start();
            for (int i = 0; i < Math.min(MAX_TRACERS, boxes.length); i++) {
                nova.nova_client.client.NovaTracers.line(start, boxes[i].getCenter(), colors[i], 96.0D);
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
        return new StorageEspMenu(parent);
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
        properties.setProperty(prefix + "range", String.valueOf(range));
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
            // Neue Arten (Spawner, Zaubertisch) sind bei alten Einstellungen erst mal an
            if (properties.getProperty(prefix + "range") == null) {
                SELECTED.add(Type.SPAWNER);
                SELECTED.add(Type.ENCHANTING_TABLE);
            }
            dirty = true;
        }
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)));
        try {
            setOpacity(Integer.parseInt(properties.getProperty(prefix + "opacity", String.valueOf(opacity)).trim()));
            setRange(Integer.parseInt(properties.getProperty(prefix + "range", String.valueOf(range)).trim()));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
