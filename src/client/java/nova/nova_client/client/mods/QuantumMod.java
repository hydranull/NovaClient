package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import nova.nova_client.client.ChunkOverlayRenderer;
import nova.nova_client.client.Compat;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Quantum: Manche Server (z.B. DonutSMP) schicken einen Chunk noch einmal, sobald man ihm naeher kommt - die erste
 * Fassung aus der Ferne ist "geschoent", die zweite echt. Quantum merkt sich jeden Chunk, der ins Fenster um den
 * Spieler kommt, und vergleicht ihn mit der nachgeschickten Fassung. Unterscheiden sich Bloecke, die sich nicht von
 * selbst aendern (also keine Erze/Anti-Xray, Blaetter, Feuer, Redstone-Zustaende ...), war dort kuerzlich ein Spieler
 * aktiv. Um jeden Fund wird ein Bereich von 19x19 Chunks markiert; weitere Funde schneiden den Bereich zusammen, bis nur
 * noch die Gegend uebrig bleibt, in der die Aktivitaet liegen muss.
 */
public class QuantumMod extends GameTickMod {
    private static final String TAG = "Quantum";
    /** Beobachtungsfenster um den Spieler (Chunks), Ecken abgeschnitten. */
    private static final int WINDOW = 8;
    private static final int WINDOW_CORNER = 13;
    /** Bereich um einen Fund (Chunks in jede Richtung). */
    private static final int FLAG_RADIUS = 9;
    /** Nur ab dieser Hoehe vergleichen: darunter verfaelscht Anti-Xray zu viel. */
    private static final int MIN_Y = 32;
    private static final double AREA_Y = 62.0D;
    private static final int AREA_COLOR = 0xFF9A1F;
    private static final int SOURCE_COLOR = 0xFF3B30;
    private static final int MAX_DEBUG_BLOCKS = 256;

    /** Bloecke, die sich von selbst aendern oder die Anti-Xray austauscht (neuer Zustand). */
    private static final Set<Block> IGNORED_NEW = Set.of(
            Blocks.FIRE, Blocks.FROSTED_ICE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.OAK_LEAVES, Blocks.ACACIA_LEAVES, Blocks.BIRCH_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.PALE_OAK_LEAVES, Blocks.CHERRY_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
            Blocks.SPRUCE_LEAVES, Blocks.MANGROVE_LEAVES,
            Blocks.MOSSY_COBBLESTONE, Blocks.RAW_COPPER_BLOCK, Blocks.STONE,
            Blocks.OBSERVER, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
            Blocks.REDSTONE_LAMP, Blocks.REDSTONE_WIRE, Blocks.DROPPER);
    /** ... und alter Zustand (Geoden-Huelle und Stein tauscht Anti-Xray ebenfalls). */
    private static final Set<Block> IGNORED_OLD = Set.of(Blocks.AMETHYST_BLOCK, Blocks.STONE, Blocks.FROSTED_ICE);

    // Einstellungen
    public static boolean renderArea = true;
    public static boolean toasts = true;
    public static boolean sound = true;
    /** Chat nennt alten/neuen Block, geaenderte Bloecke und nachgeschickte Chunks werden markiert. */
    public static boolean debug = false;

    private static volatile boolean enabled;
    private static ClientLevel session;
    /** Merkzettel: Chunk -> Abschnitte ab MIN_Y (null = nur Luft). */
    private static final Map<Long, PalettedContainer<BlockState>[]> SNAPSHOTS = new HashMap<>();
    /** Block-Updates seit dem Merken (gehen vor den Abschnitten). */
    private static final Map<Long, Map<Long, BlockState>> UPDATES = new HashMap<>();
    private static Set<Long> window = Set.of();
    private static long lastChunk = Long.MIN_VALUE;

    private static final Set<Long> AREA = new LinkedHashSet<>();
    private static final Set<Long> SOURCES = new LinkedHashSet<>();
    private static final Set<Long> RESENT = new HashSet<>();
    private static final List<BlockPos> CHANGED = new ArrayList<>();
    private static long[] areaKeys = new long[0];
    private static long[] sourceKeys = new long[0];
    private static final ChunkOverlayRenderer AREA_OVERLAY = new ChunkOverlayRenderer();
    private static final ChunkOverlayRenderer SOURCE_OVERLAY = new ChunkOverlayRenderer();
    private static final NovaToasts.Feed TOASTS = new NovaToasts.Feed(4);
    private static int detections;

    public QuantumMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        session = null;
        resetSession();
        if (!on) {
            TOASTS.clear();
        }
    }

    private static void resetSession() {
        SNAPSHOTS.clear();
        UPDATES.clear();
        window = Set.of();
        lastChunk = Long.MIN_VALUE;
        AREA.clear();
        SOURCES.clear();
        RESENT.clear();
        CHANGED.clear();
        areaKeys = new long[0];
        sourceKeys = new long[0];
        detections = 0;
    }

    private static boolean overworld(ClientLevel level) {
        return level != null && level.dimension() == Level.OVERWORLD;
    }

    private static boolean ready(Minecraft client) {
        if (!enabled || client.player == null || client.level == null) {
            return false;
        }
        if (client.level != session) {
            session = client.level;
            resetSession();
        }
        return overworld(client.level);
    }

    // ---------------------------------------------------------------- Fenster

    private static boolean inWindow(int dx, int dz) {
        int ax = Math.abs(dx);
        int az = Math.abs(dz);
        return ax <= WINDOW && az <= WINDOW && ax + az <= WINDOW_CORNER;
    }

    private static Set<Long> windowAround(int cx, int cz) {
        Set<Long> keys = new HashSet<>();
        for (int dx = -WINDOW; dx <= WINDOW; dx++) {
            for (int dz = -WINDOW; dz <= WINDOW; dz++) {
                if (inWindow(dx, dz)) {
                    keys.add(ChunkPos.pack(cx + dx, cz + dz));
                }
            }
        }
        return keys;
    }

    @Override
    public void execute(Minecraft client) {
        if (!ready(client)) {
            return;
        }
        int cx = client.player.getBlockX() >> 4;
        int cz = client.player.getBlockZ() >> 4;
        long current = ChunkPos.pack(cx, cz);
        if (current == lastChunk) {
            return;
        }
        Set<Long> next = windowAround(cx, cz);
        if (lastChunk != Long.MIN_VALUE) {
            // Neu ins Fenster gekommen: so merken, wie der Client sie gerade kennt
            for (long key : next) {
                if (!window.contains(key)) {
                    remember(client.level, key);
                }
            }
        }
        SNAPSHOTS.keySet().retainAll(next);
        UPDATES.keySet().retainAll(next);
        RESENT.retainAll(next);
        window = next;
        lastChunk = current;
    }

    private static void remember(ClientLevel level, long key) {
        int x = ChunkPos.getX(key);
        int z = ChunkPos.getZ(key);
        if (!level.getChunkSource().hasChunk(x, z)) {
            return;
        }
        SNAPSHOTS.put(key, copySections(level, level.getChunk(x, z)));
        UPDATES.remove(key);
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] copySections(ClientLevel level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        PalettedContainer<BlockState>[] copy = new PalettedContainer[sections.length];
        int first = Math.max(0, level.getSectionIndexFromSectionY(MIN_Y >> 4));
        for (int i = first; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section != null && !section.hasOnlyAir()) {
                copy[i] = section.getStates().copy();
            }
        }
        return copy;
    }

    // ---------------------------------------------------------------- Pakete (Hauptthread)

    /** Block-Update vom Server: gehoert ab jetzt zum gemerkten Stand (ist ja sichtbar passiert). */
    public static void onBlockUpdate(BlockPos pos, BlockState state) {
        if (!enabled) {
            return;
        }
        long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (SNAPSHOTS.containsKey(key)) {
            UPDATES.computeIfAbsent(key, k -> new HashMap<>()).put(pos.asLong(), state);
        }
    }

    /** Chunk-Paket ist eingelesen: mit dem gemerkten Stand vergleichen. */
    public static void onChunkLoaded(int x, int z) {
        Minecraft client = Minecraft.getInstance();
        if (!ready(client)) {
            return;
        }
        long key = ChunkPos.pack(x, z);
        int cx = client.player.getBlockX() >> 4;
        int cz = client.player.getBlockZ() >> 4;
        if (!inWindow(x - cx, z - cz) || !client.level.getChunkSource().hasChunk(x, z)) {
            return;
        }
        LevelChunk chunk = client.level.getChunk(x, z);
        PalettedContainer<BlockState>[] before = SNAPSHOTS.get(key);
        if (before != null) {
            Change change = firstChange(client.level, chunk, before, UPDATES.getOrDefault(key, Map.of()));
            if (change != null) {
                activity(x, z, change);
            }
        }
        RESENT.add(key);
        SNAPSHOTS.put(key, copySections(client.level, chunk));
        UPDATES.remove(key);
    }

    private record Change(BlockPos pos, BlockState was, BlockState now) {
    }

    private static Change firstChange(ClientLevel level, LevelChunk chunk, PalettedContainer<BlockState>[] before,
                                      Map<Long, BlockState> updates) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockState air = Blocks.AIR.defaultBlockState();
        int first = Math.max(0, level.getSectionIndexFromSectionY(MIN_Y >> 4));
        int baseX = Compat.chunkX(chunk.getPos()) << 4;
        int baseZ = Compat.chunkZ(chunk.getPos()) << 4;
        boolean anyBefore = false;
        for (int i = first; i < before.length; i++) {
            if (before[i] != null) {
                anyBefore = true;
                break;
            }
        }
        if (!anyBefore && updates.isEmpty()) {
            return null; // nie richtig gesehen (leer gemerkt)
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = first; i < sections.length && i < before.length; i++) {
            PalettedContainer<BlockState> old = before[i];
            LevelChunkSection section = sections[i];
            PalettedContainer<BlockState> now = section == null || section.hasOnlyAir() ? null : section.getStates();
            if (old == null && now == null) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState is = now == null ? air : now.get(x, y, z);
                        BlockState was = old == null ? air : old.get(x, y, z);
                        if (!updates.isEmpty()) {
                            BlockState updated = updates.get(cursor.set(baseX + x, baseY + y, baseZ + z).asLong());
                            if (updated != null) {
                                was = updated;
                            }
                        }
                        if (is == was || IGNORED_NEW.contains(is.getBlock()) || IGNORED_OLD.contains(was.getBlock())) {
                            continue;
                        }
                        return new Change(new BlockPos(baseX + x, baseY + y, baseZ + z), was, is);
                    }
                }
            }
        }
        return null;
    }

    private static void activity(int chunkX, int chunkZ, Change change) {
        detections++;
        String text = "§aActivity§7 in chunk §f" + chunkX + ", " + chunkZ;
        if (debug) {
            text += "§7: §f" + change.was().getBlock().getName().getString() + "§7 -> §f"
                    + change.now().getBlock().getName().getString();
        }
        NovaNotify.chat(TAG, text);
        if (CHANGED.size() < MAX_DEBUG_BLOCKS) {
            CHANGED.add(change.pos());
        }
        long source = ChunkPos.pack(chunkX, chunkZ);
        SOURCES.add(source);

        // Bereich um den Fund; mit dem bisherigen Bereich schneiden (leerer Schnitt = neu anfangen)
        Set<Long> around = new LinkedHashSet<>();
        for (int dx = -FLAG_RADIUS; dx <= FLAG_RADIUS; dx++) {
            for (int dz = -FLAG_RADIUS; dz <= FLAG_RADIUS; dz++) {
                around.add(ChunkPos.pack(chunkX + dx, chunkZ + dz));
            }
        }
        if (!AREA.isEmpty()) {
            Set<Long> overlap = new LinkedHashSet<>(around);
            overlap.retainAll(AREA);
            if (!overlap.isEmpty()) {
                around = overlap;
            }
        }
        AREA.clear();
        AREA.addAll(around);
        areaKeys = AREA.stream().mapToLong(Long::longValue).toArray();
        sourceKeys = SOURCES.stream().mapToLong(Long::longValue).toArray();

        if (toasts) {
            BlockPos center = new BlockPos((chunkX << 4) + 8, change.pos().getY(), (chunkZ << 4) + 8);
            Item changed = change.now().getBlock().asItem();
            TOASTS.push("chunk:" + source, "Activity detected", "Quantum", center, null,
                    changed == Items.AIR ? List.of(new ItemStack(Items.ENDER_EYE))
                            : List.of(new ItemStack(Items.ENDER_EYE), new ItemStack(changed)));
        }
        if (sound) {
            NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 1.4F);
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    public static void renderToasts(GuiGraphicsExtractor g) {
        if (enabled) {
            TOASTS.render(g, 6);
        }
    }

    /** Jeden Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null || !overworld(client.level)) {
            return;
        }
        if (renderArea && areaKeys.length > 0) {
            AREA_OVERLAY.emit(areaKeys, AREA, AREA_Y, AREA_COLOR, 0.16F, 0.8F);
            SOURCE_OVERLAY.emit(sourceKeys, SOURCES, AREA_Y + 0.05D, SOURCE_COLOR, 0.35F, 1.0F);
        }
        if (debug) {
            GizmoStyle block = GizmoStyle.strokeAndFill(0xFFFF2828, 1.5F, 0x29FF2828);
            for (BlockPos pos : CHANGED) {
                Gizmos.cuboid(new AABB(pos), block).setAlwaysOnTop();
            }
            GizmoStyle resent = GizmoStyle.strokeAndFill(0xFF00FF00, 1.5F, 0x2800FF00);
            for (long key : RESENT) {
                double x = ChunkPos.getX(key) * 16.0D;
                double z = ChunkPos.getZ(key) * 16.0D;
                Gizmos.cuboid(new AABB(x, 63.0D, z, x + 16.0D, 63.0D, z + 16.0D), resent).setAlwaysOnTop();
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    private static String status() {
        if (!enabled) {
            return "Module is off";
        }
        Minecraft client = Minecraft.getInstance();
        if (!overworld(client.level)) {
            return "Only works in the overworld";
        }
        if (detections == 0) {
            return "Watching " + SNAPSHOTS.size() + " chunks - nothing yet";
        }
        return detections + " detections  ·  area " + AREA.size() + " chunks";
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Toggle("Show area", () -> renderArea, v -> renderArea = v),
                new ModOptionsMenu.Toggle("Toasts", () -> toasts, v -> toasts = v),
                new ModOptionsMenu.Toggle("Sound", () -> sound, v -> sound = v),
                new ModOptionsMenu.Toggle("Advanced debug", () -> debug, v -> debug = v),
                new ModOptionsMenu.Info(() -> debug ? "Chat names the changed block, boxes on changed blocks"
                        : "Walk around - resent chunks are compared automatically"),
                new ModOptionsMenu.Action(() -> "Clear area", () -> {
                    AREA.clear();
                    SOURCES.clear();
                    CHANGED.clear();
                    areaKeys = new long[0];
                    sourceKeys = new long[0];
                    detections = 0;
                }),
                new ModOptionsMenu.Info(QuantumMod::status)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "renderArea", String.valueOf(renderArea));
        properties.setProperty(prefix + "toasts", String.valueOf(toasts));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
        properties.setProperty(prefix + "debug", String.valueOf(debug));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        renderArea = Boolean.parseBoolean(properties.getProperty(prefix + "renderArea", String.valueOf(renderArea)));
        toasts = Boolean.parseBoolean(properties.getProperty(prefix + "toasts", String.valueOf(toasts)));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
        debug = Boolean.parseBoolean(properties.getProperty(prefix + "debug", String.valueOf(debug)));
    }
}
