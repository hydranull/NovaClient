package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ChunkOverlayRenderer;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.NovaTracers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Prime Chunk Finder (nur unter Y=0): merkt sich das Blocklicht jedes Chunks und vergleicht es mit jedem neuen
 * Licht-Paket. Aendert sich unter Y=0 Licht, das sich nicht durch Sichtbares erklaeren laesst (Amethyst, angeregtes
 * Redstone-Erz, sichtbare Fackeln/Leuchtbeeren, gerade entfernte Fackeln), hat dort jemand eine Lichtquelle gesetzt
 * oder entfernt - auch wenn Anti-Xray den Block selbst versteckt. Dazu meldet er Redstone-Bloecke, die sich unter Y=0
 * aendern (Farmen, Maschinen). Chunks, durch die du selbst gelaufen bist, zaehlen nicht.
 */
public class PrimeChunkFinderMod extends GameTickMod {
    private static final String TAG = "Prime Chunk Finder";
    private static final int SECTION_BYTES = 2048;
    private static final byte[] ZERO = new byte[SECTION_BYTES];
    private static final int MAX_FLAGGED = 256;
    private static final int MAX_CHUNKS = 5000;
    private static final int SNAPSHOT_RADIUS = 32;
    private static final int AMETHYST_LIGHT = 5;
    private static final int ORE_LIGHT = 9;
    private static final int LAMP_LIGHT = 14;
    private static final long GONE_LAMP_MS = 5000L;
    private static final long REDSTONE_COOLDOWN_MS = 15000L;
    private static final double PLATE_Y = 0.0D;
    private static final int COLOR = 0x2BD96A;
    private static final Direction[] SIDES = Direction.values();

    // Einstellungen
    public static boolean tracers = true;
    public static boolean toasts = true;
    public static boolean sound = true;
    public static boolean redstone = true;

    private static volatile boolean enabled;
    /** Blocklicht je Chunk: Abschnitt-Y -> 2048 Bytes (ZERO = dunkel). Zuletzt benutzt = hinten. */
    private static final LinkedHashMap<Long, Map<Integer, byte[]>> LIGHT = new LinkedHashMap<>(256, 0.75F, true);
    /** Chunks, durch die du unter Y=0 gelaufen bist. */
    private static final Set<Long> PASSED = new HashSet<>();
    /** Markierte Chunks -> staerkste Aenderung (fuer Tracer und Toast). */
    private static final LinkedHashMap<Long, BlockPos> FLAGGED = new LinkedHashMap<>();
    /** Gerade entfernte Lampen: Position -> (Ablauf << 4 | Lichtstaerke). */
    private static final Map<Long, Long> GONE_LAMPS = new HashMap<>();
    private static final Map<Long, Long> REDSTONE_NOTIFIED = new HashMap<>();
    // Caches pro Licht-Paket
    private static final Map<Long, Boolean> AMETHYST_SECTIONS = new HashMap<>();
    private static final Map<Long, long[]> ORE_SECTIONS = new HashMap<>();
    private static final Map<Long, int[]> LAMP_SECTIONS = new HashMap<>();

    private static long[] shownKeys = new long[0];
    private static Set<Long> shownSet = Set.of();
    private static final ChunkOverlayRenderer OVERLAY = new ChunkOverlayRenderer();
    private static final NovaToasts.Feed TOASTS = new NovaToasts.Feed(4);
    private static ClientLevel lastLevel;

    public PrimeChunkFinderMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        if (on && (client.player == null || client.level == null || client.player.getY() >= 0.0D)) {
            // Nur auf Tiefenschiefer-Hoehe sinnvoll
            active = false;
            enabled = false;
            TOASTS.push("deepslate", "Deepslate level only", TAG, null, "Go below Y 0 first",
                    List.of(new ItemStack(Items.DEEPSLATE)));
            NovaNotify.chat(name, "§7Go below §fY 0§7 first.");
            return;
        }
        enabled = on;
        reset();
        if (on) {
            lastLevel = client.level;
            snapshotLoaded(client);
        }
    }

    private static void reset() {
        LIGHT.clear();
        PASSED.clear();
        FLAGGED.clear();
        GONE_LAMPS.clear();
        REDSTONE_NOTIFIED.clear();
        shownKeys = new long[0];
        shownSet = Set.of();
    }

    /** Neue Welt / neuer Server. */
    public static void onLogin() {
        if (enabled) {
            reset();
        }
    }

    // ---------------------------------------------------------------- Licht merken

    private static void snapshotLoaded(Minecraft client) {
        int cx = client.player.getBlockX() >> 4;
        int cz = client.player.getBlockZ() >> 4;
        for (int x = cx - SNAPSHOT_RADIUS; x <= cx + SNAPSHOT_RADIUS; x++) {
            for (int z = cz - SNAPSHOT_RADIUS; z <= cz + SNAPSHOT_RADIUS; z++) {
                Map<Integer, byte[]> sections = snapshotChunk(client.level, x, z);
                if (sections != null) {
                    LIGHT.put(ChunkPos.pack(x, z), sections);
                }
            }
        }
        trim();
    }

    private static Map<Integer, byte[]> snapshotChunk(ClientLevel level, int x, int z) {
        if (!level.getChunkSource().hasChunk(x, z)) {
            return null;
        }
        LayerLightEventListener light = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
        Map<Integer, byte[]> sections = zeroed(level);
        for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
            DataLayer layer = light.getDataLayerData(SectionPos.of(x, sy, z));
            if (layer != null && !layer.isDefinitelyFilledWith(0)) {
                sections.put(sy, Arrays.copyOf(layer.getData(), SECTION_BYTES));
            }
        }
        return sections;
    }

    private static Map<Integer, byte[]> zeroed(ClientLevel level) {
        Map<Integer, byte[]> sections = new HashMap<>();
        for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
            sections.put(sy, ZERO);
        }
        return sections;
    }

    private static void trim() {
        Iterator<Long> it = LIGHT.keySet().iterator();
        while (LIGHT.size() > MAX_CHUNKS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    // ---------------------------------------------------------------- Pakete (Hauptthread)

    /** Chunk-Paket: Ausgangsstand, kein Vergleich. */
    public static void onChunkLight(int x, int z, ClientboundLightUpdatePacketData data) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!enabled || level == null || data == null) {
            return;
        }
        Map<Integer, byte[]> sections = zeroed(level);
        apply(level, data, sections, x, z, null);
        LIGHT.put(ChunkPos.pack(x, z), sections);
        trim();
    }

    /** Licht-Update: mit dem gemerkten Stand vergleichen. */
    public static void onLightUpdate(int x, int z, ClientboundLightUpdatePacketData data) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!enabled || level == null || data == null) {
            return;
        }
        long key = ChunkPos.pack(x, z);
        AMETHYST_SECTIONS.clear();
        ORE_SECTIONS.clear();
        LAMP_SECTIONS.clear();
        Map<Integer, byte[]> sections = LIGHT.get(key);
        if (sections == null) {
            sections = snapshotChunk(level, x, z);
        }
        boolean compare = sections != null && !own(key);
        if (sections == null) {
            sections = zeroed(level);
        }
        Summary summary = compare ? new Summary() : null;
        apply(level, data, sections, x, z, summary);
        LIGHT.put(key, sections);
        trim();
        if (summary != null && summary.count > 0) {
            flag(key, new BlockPos(summary.x, summary.y, summary.z), "Light", Items.TORCH);
        }
    }

    private static void apply(ClientLevel level, ClientboundLightUpdatePacketData data, Map<Integer, byte[]> sections,
                              int x, int z, Summary summary) {
        int minLight = level.getChunkSource().getLightEngine().getMinLightSection();
        BitSet mask = data.blockYMask();
        List<byte[]> updates = data.blockUpdates();
        int next = 0;
        for (int bit = mask.nextSetBit(0); bit >= 0 && next < updates.size(); bit = mask.nextSetBit(bit + 1)) {
            byte[] update = updates.get(next++);
            int sy = minLight + bit;
            if (sy >= level.getMinSectionY() && sy <= level.getMaxSectionY() && update.length == SECTION_BYTES) {
                update(level, sections, x, z, sy, Arrays.copyOf(update, SECTION_BYTES), summary);
            }
        }
        BitSet empty = data.emptyBlockYMask();
        for (int bit = empty.nextSetBit(0); bit >= 0; bit = empty.nextSetBit(bit + 1)) {
            int sy = minLight + bit;
            if (sy >= level.getMinSectionY() && sy <= level.getMaxSectionY()) {
                update(level, sections, x, z, sy, ZERO, summary);
            }
        }
    }

    private static void update(ClientLevel level, Map<Integer, byte[]> sections, int x, int z, int sy, byte[] light,
                               Summary summary) {
        byte[] previous = sections.get(sy);
        if (summary != null && previous != null && sy < 0) {
            for (int i = 0; i < SECTION_BYTES; i++) {
                int was = previous[i] & 0xFF;
                int now = light[i] & 0xFF;
                int changed = was ^ now;
                if ((changed & 0x0F) != 0) {
                    record(level, x, z, sy, i << 1, was & 0x0F, now & 0x0F, summary);
                }
                if ((changed & 0xF0) != 0) {
                    record(level, x, z, sy, (i << 1) + 1, was >>> 4, now >>> 4, summary);
                }
            }
        }
        sections.put(sy, light);
    }

    private static void record(ClientLevel level, int chunkX, int chunkZ, int sy, int index, int was, int now, Summary summary) {
        int x = (chunkX << 4) + (index & 15);
        int y = (sy << 4) + (index >>> 8 & 15);
        int z = (chunkZ << 4) + (index >>> 4 & 15);
        int value = Math.max(was, now);
        if (y >= 0 || fromAmethyst(level, x, y, z, value) || fromRedstoneOre(level, x, y, z, value) || fromLamp(level, x, y, z, value)) {
            return;
        }
        summary.add(x, y, z, was, now);
    }

    // ---------------------------------------------------------------- Erklaerbares Licht

    private static LevelChunkSection section(ClientLevel level, int sx, int sy, int sz) {
        if (sy < level.getMinSectionY() || sy > level.getMaxSectionY() || !level.getChunkSource().hasChunk(sx, sz)) {
            return null;
        }
        LevelChunk chunk = level.getChunk(sx, sz);
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
        return section.hasOnlyAir() ? null : section;
    }

    private static boolean isAmethyst(BlockState state) {
        return state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
                || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.BUDDING_AMETHYST) || state.is(Blocks.AMETHYST_BLOCK);
    }

    private static boolean fromAmethyst(ClientLevel level, int x, int y, int z, int value) {
        if (value > AMETHYST_LIGHT) {
            return false;
        }
        boolean near = false;
        for (int sx = x - 5 >> 4; sx <= x + 5 >> 4 && !near; sx++) {
            for (int sy = y - 5 >> 4; sy <= y + 5 >> 4 && !near; sy++) {
                for (int sz = z - 5 >> 4; sz <= z + 5 >> 4 && !near; sz++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    final int fx = sx;
                    final int fy = sy;
                    final int fz = sz;
                    near = AMETHYST_SECTIONS.computeIfAbsent(key, k -> {
                        LevelChunkSection section = section(level, fx, fy, fz);
                        return section != null && section.getStates().maybeHas(PrimeChunkFinderMod::isAmethyst);
                    });
                }
            }
        }
        if (!near) {
            return false;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -5; dx <= 5; dx++) {
            int restX = 5 - Math.abs(dx);
            for (int dy = -restX; dy <= restX; dy++) {
                int restY = restX - Math.abs(dy);
                for (int dz = -restY; dz <= restY; dz++) {
                    if (isAmethyst(level.getBlockState(cursor.set(x + dx, y + dy, z + dz)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isRedstoneOre(BlockState state) {
        return state.is(Blocks.REDSTONE_ORE) || state.is(Blocks.DEEPSLATE_REDSTONE_ORE);
    }

    /** Angeregtes Redstone-Erz leuchtet mit 9 - aber nur, wenn man drankommt (offene Seite). */
    private static boolean fromRedstoneOre(ClientLevel level, int x, int y, int z, int value) {
        if (value > ORE_LIGHT) {
            return false;
        }
        int reach = ORE_LIGHT - value;
        for (int sx = x - reach >> 4; sx <= x + reach >> 4; sx++) {
            for (int sy = y - reach >> 4; sy <= y + reach >> 4; sy++) {
                for (int sz = z - reach >> 4; sz <= z + reach >> 4; sz++) {
                    for (long ore : exposedOres(level, sx, sy, sz)) {
                        int d = Math.abs(BlockPos.getX(ore) - x) + Math.abs(BlockPos.getY(ore) - y) + Math.abs(BlockPos.getZ(ore) - z);
                        if (d <= reach) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static long[] exposedOres(ClientLevel level, int sx, int sy, int sz) {
        return ORE_SECTIONS.computeIfAbsent(SectionPos.asLong(sx, sy, sz), k -> {
            LevelChunkSection section = section(level, sx, sy, sz);
            if (section == null || !section.getStates().maybeHas(PrimeChunkFinderMod::isRedstoneOre)) {
                return new long[0];
            }
            List<Long> ores = new ArrayList<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (!isRedstoneOre(section.getBlockState(lx, ly, lz))) {
                            continue;
                        }
                        int wx = (sx << 4) + lx;
                        int wy = (sy << 4) + ly;
                        int wz = (sz << 4) + lz;
                        for (Direction d : SIDES) {
                            BlockState neighbour = level.getBlockState(cursor.set(wx + d.getStepX(), wy + d.getStepY(), wz + d.getStepZ()));
                            if (neighbour.isAir() || !neighbour.canOcclude()) {
                                ores.add(BlockPos.asLong(wx, wy, wz));
                                break;
                            }
                        }
                    }
                }
            }
            return ores.stream().mapToLong(Long::longValue).toArray();
        });
    }

    private static boolean isLamp(BlockState state) {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH) || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH) || state.is(Blocks.COPPER_TORCH) || state.is(Blocks.COPPER_WALL_TORCH)
                || CaveVines.hasGlowBerries(state);
    }

    /** Sichtbare Fackel/Leuchtbeere (oder gerade entfernte) in Reichweite, die diesen Lichtwert erklaert. */
    private static boolean fromLamp(ClientLevel level, int x, int y, int z, int value) {
        if (value > LAMP_LIGHT) {
            return false;
        }
        int reach = LAMP_LIGHT - value;
        for (int sx = x - reach >> 4; sx <= x + reach >> 4; sx++) {
            for (int sy = y - reach >> 4; sy <= y + reach >> 4; sy++) {
                for (int sz = z - reach >> 4; sz <= z + reach >> 4; sz++) {
                    for (int lamp : lamps(level, sx, sy, sz)) {
                        int index = lamp >>> 4;
                        int lx = (sx << 4) + (index & 15);
                        int ly = (sy << 4) + (index >>> 8);
                        int lz = (sz << 4) + (index >>> 4 & 15);
                        if (Math.abs(lx - x) + Math.abs(ly - y) + Math.abs(lz - z) <= (lamp & 15) - value) {
                            return true;
                        }
                    }
                }
            }
        }
        long now = Util.getMillis();
        for (Map.Entry<Long, Long> gone : GONE_LAMPS.entrySet()) {
            long packed = gone.getValue();
            if (packed >>> 4 < now) {
                continue;
            }
            long pos = gone.getKey();
            int d = Math.abs(BlockPos.getX(pos) - x) + Math.abs(BlockPos.getY(pos) - y) + Math.abs(BlockPos.getZ(pos) - z);
            if (d <= (int) (packed & 15L) - value) {
                return true;
            }
        }
        return false;
    }

    private static int[] lamps(ClientLevel level, int sx, int sy, int sz) {
        return LAMP_SECTIONS.computeIfAbsent(SectionPos.asLong(sx, sy, sz), k -> {
            LevelChunkSection section = section(level, sx, sy, sz);
            if (section == null || !section.getStates().maybeHas(PrimeChunkFinderMod::isLamp)) {
                return new int[0];
            }
            List<Integer> found = new ArrayList<>();
            for (int index = 0; index < 4096; index++) {
                BlockState state = section.getBlockState(index & 15, index >>> 8, index >>> 4 & 15);
                if (isLamp(state)) {
                    found.add(index << 4 | state.getLightEmission());
                }
            }
            return found.stream().mapToInt(Integer::intValue).toArray();
        });
    }

    // ---------------------------------------------------------------- Block-Updates (alter Zustand noch in der Welt)

    public static void onBlockUpdate(BlockPos pos, BlockState now) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!enabled || level == null) {
            return;
        }
        BlockState was = level.getBlockState(pos);
        if (was == now) {
            return;
        }
        if (isLamp(was) && (!isLamp(now) || now.getLightEmission() < was.getLightEmission())) {
            GONE_LAMPS.put(pos.asLong(), (Util.getMillis() + GONE_LAMP_MS) << 4 | was.getLightEmission());
        }
        if (redstone && pos.getY() < 0 && (isRedstone(now) || isRedstone(was))) {
            long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
            if (!own(key)) {
                long time = Util.getMillis();
                boolean notify = time - REDSTONE_NOTIFIED.getOrDefault(key, Long.MIN_VALUE / 2) >= REDSTONE_COOLDOWN_MS;
                if (notify) {
                    REDSTONE_NOTIFIED.put(key, time);
                }
                flag(key, pos.immutable(), notify ? "Redstone" : null, Items.REDSTONE);
            }
        }
    }

    private static boolean isRedstone(BlockState state) {
        Block block = state.getBlock();
        return block instanceof RedstoneWireBlock || block instanceof DiodeBlock || block instanceof RedstoneTorchBlock
                || block instanceof RedstoneLampBlock || block instanceof ObserverBlock || block instanceof PistonBaseBlock
                || block instanceof PistonHeadBlock || block instanceof MovingPistonBlock || block instanceof DispenserBlock
                || block instanceof HopperBlock || block instanceof NoteBlock || block instanceof DaylightDetectorBlock
                || block instanceof LeverBlock || block instanceof ButtonBlock || block instanceof BasePressurePlateBlock
                || block instanceof TripWireHookBlock || block instanceof TargetBlock || block instanceof CrafterBlock
                || block instanceof PoweredBlock;
    }

    private static boolean own(long key) {
        Minecraft client = Minecraft.getInstance();
        if (PASSED.contains(key)) {
            return true;
        }
        return client.player != null && ChunkPos.pack(client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4) == key;
    }

    /** Chunk markieren; badge != null = melden (Toast/Chat/Ton). */
    private static void flag(long key, BlockPos where, String badge, Item icon) {
        FLAGGED.remove(key);
        FLAGGED.put(key, where);
        while (FLAGGED.size() > MAX_FLAGGED) {
            FLAGGED.remove(FLAGGED.keySet().iterator().next());
        }
        if (badge == null) {
            return;
        }
        NovaNotify.chat(TAG, "§a" + badge + "§7 change at §f" + where.getX() + " " + where.getY() + " " + where.getZ());
        if (toasts) {
            TOASTS.push("chunk:" + key + ":" + badge, "Prime chunk", badge, where, null, List.of(new ItemStack(icon)));
        }
        if (sound) {
            NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 1.2F);
        }
    }

    // ---------------------------------------------------------------- Tick

    @Override
    public void execute(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset();
        }
        if (client.player.getY() < 0.0D) {
            PASSED.add(ChunkPos.pack(client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4));
        }
        long now = Util.getMillis();
        GONE_LAMPS.values().removeIf(packed -> packed >>> 4 < now);
        List<Long> shown = new ArrayList<>();
        for (long key : FLAGGED.keySet()) {
            if (client.level.getChunkSource().hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                shown.add(key);
            }
        }
        shownKeys = shown.stream().mapToLong(Long::longValue).toArray();
        shownSet = new HashSet<>(shown);
    }

    // ---------------------------------------------------------------- Zeichnen

    public static void renderToasts(GuiGraphicsExtractor g) {
        // Auch bei ausgeschaltetem Modul: der "erst unter Y 0"-Hinweis soll sichtbar sein
        TOASTS.render(g, 6);
    }

    /** Jeden Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        long[] keys = shownKeys;
        if (!enabled || client.level == null || keys.length == 0) {
            return;
        }
        OVERLAY.emit(keys, shownSet, PLATE_Y, COLOR, 0.3F, 1.0F);
        if (tracers) {
            Vec3 start = NovaTracers.start();
            for (long key : keys) {
                BlockPos where = FLAGGED.get(key);
                if (where != null) {
                    NovaTracers.line(start, Vec3.atCenterOf(where), COLOR, 128.0D);
                }
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    private static final class Summary {
        int count;
        int x;
        int y;
        int z;
        int delta = -1;

        void add(int x, int y, int z, int was, int now) {
            int d = Math.abs(now - was);
            if (d > delta) {
                delta = d;
                this.x = x;
                this.y = y;
                this.z = z;
            }
            count++;
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Toggle("Tracers", () -> tracers, v -> tracers = v),
                new ModOptionsMenu.Toggle("Toasts", () -> toasts, v -> toasts = v),
                new ModOptionsMenu.Toggle("Sound", () -> sound, v -> sound = v),
                new ModOptionsMenu.Toggle("Redstone changes", () -> redstone, v -> redstone = v),
                new ModOptionsMenu.Info(() -> "Only works below Y 0 - turns itself off above"),
                new ModOptionsMenu.Action(() -> "Clear marks", () -> {
                    FLAGGED.clear();
                    shownKeys = new long[0];
                    shownSet = Set.of();
                }),
                new ModOptionsMenu.Info(() -> enabled ? FLAGGED.size() + " prime chunks  ·  " + LIGHT.size() + " chunks watched"
                        : "Module is off")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "toasts", String.valueOf(toasts));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
        properties.setProperty(prefix + "redstone", String.valueOf(redstone));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)));
        toasts = Boolean.parseBoolean(properties.getProperty(prefix + "toasts", String.valueOf(toasts)));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
        redstone = Boolean.parseBoolean(properties.getProperty(prefix + "redstone", String.valueOf(redstone)));
    }
}
