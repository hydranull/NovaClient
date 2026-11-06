package nova.nova_client.client.mods;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ChunkOverlayRenderer;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.NovaTracers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Sus Chunk Finder: Amethyst und viele Pflanzen wachsen nur in Chunks, die der Server tickt - also nur, solange ein
 * Spieler innerhalb seiner Simulation Distance ist. Was ausgewachsen ist, heisst: hier war lange jemand in der Naehe.
 * <p>
 * So wird gerechnet:
 * <ul>
 *     <li>Jede Wachstums-Art zaehlt je Chunk ihre ausgewachsenen Funde ("Waerme"): Amethyst-Cluster, Seetang mit Alter 25
 *     oder ohne Wasser darueber, Hoehlenranken bis zum Boden, Ranken ohne Platz nach unten, reifer Kakao, volle
 *     Bienennester, Bambus mit Wachstums-Stopp. Was noch wachsen kann, setzt ein "Veto" - aber nur fuer die eigene Art:
 *     junger Seetang streicht keinen ausgewachsenen Amethyst.</li>
 *     <li>Von Anti-Xray versteckte Cluster und Knospen werden ueber ihr Blocklicht erkannt (Cluster leuchten mit 5,
 *     Knospen mit 1, 2 oder 4).</li>
 *     <li>Waerme und Veto gelten fuer das ganze Quadrat der Simulation Distance um den Chunk - dort irgendwo muss der
 *     Spieler gestanden haben. Ueberlagern sich viele Quadrate, steigt die Waerme.</li>
 *     <li>Markiert werden nur Chunks mit genug Waerme (alle Arten ohne Veto zusammen), deren Nachbarn schon geprueft
 *     sind und die in ihrem Umkreis die waermsten sind.</li>
 *     <li>Abgeerntete Geoden (optional, eigene Farbe): grosse Geoden, an deren Wand kein Cluster und keine Knospe mehr
 *     sitzt und an der hoechstens Fackellicht ist - dort hat jemand geerntet.</li>
 * </ul>
 * Gepruefte Chunks warten, bis ihr Licht fertig berechnet ist, sonst fehlen die versteckten Funde. Weit entfernte Chunks
 * werden wieder vergessen.
 */
public class SusChunkFinderMod extends GameTickMod {
    public static final String[] COLORS = {"Cyan", "Violet", "Rose", "Gold", "Lime"};
    private static final int[] COLOR_VALUES = {0x1EE3FF, 0x9B6BFF, 0xFF4F8B, 0xFFC23D, 0x7CFF6B};
    /** Licht spaetestens nach so vielen Ticks als fertig ansehen. */
    private static final int LIGHT_WAIT_TICKS = 200;
    /** Alle so viele Ticks alles Geladene nochmal pruefen (Sicherheitsnetz). */
    private static final int FULL_RESCAN_TICKS = 1200;
    /** Alle so viele Ticks Chunks vergessen, die weit hinter der Sichtweite liegen. */
    private static final int FORGET_TICKS = 200;
    /** Rechenzeit pro Tick fuer das Pruefen von Chunks. */
    private static final long BUDGET_NANOS = 2_000_000L;
    /** In diesem Hoehenbereich muss ein Chunk Bloecke haben, damit die volle Empfindlichkeit gilt. */
    private static final int DEEP_MIN_Y = -16;
    private static final int DEEP_MAX_Y = 30;
    private static final int MAX_TRACERS = 8;
    private static final Direction[] DIRECTIONS = Direction.values();

    // Abgeerntete Geoden
    /** Geoden liegen nur unterhalb dieser Hoehe. */
    private static final int GEODE_MAX_Y = 47;
    /** Gross genug: so viele freiliegende knospende Amethyste - oder ohne solche so viele freie Wand-Bloecke. */
    private static final int GEODE_MIN_BUDDING = 10;
    private static final int GEODE_MIN_WALL = 160;
    /** Alle Chunks unter einer Geode muessen seit so vielen Ticks geprueft sein (Nachbarn fertig geladen). */
    private static final int GEODE_SETTLE_TICKS = 40;
    /** Fackeln leuchten mit 14: das einzige Licht, das an einer abgeernteten Geode sein darf. */
    private static final int TORCH_LIGHT = 14;

    /** Wachstums-Arten. Amethyst zaehlt immer, die anderen lassen sich einzeln abschalten. */
    private enum Growth {
        AMETHYST("Amethyst", "signAmethyst", true),
        KELP("Kelp", "signKelp", true),
        CAVE_VINES("Cave vines", "signCaveVines", true),
        VINES("Vines", "signVines", true),
        // Natuerlicher Bambus steht schon mit Wachstums-Stopp da - im Bambus-Dschungel nur Rauschen, daher aus
        BAMBOO("Bamboo", "signBamboo", false),
        COCOA("Cocoa", "signCocoa", true),
        BEE_NEST("Bee nests", "signBeeNests", true);

        final String label;
        final String key;
        boolean on;

        Growth(String label, String key, boolean on) {
            this.label = label;
            this.key = key;
            this.on = on;
        }

        int bit() {
            return 1 << ordinal();
        }

        ItemStack icon() {
            return new ItemStack(switch (this) {
                case AMETHYST -> Items.AMETHYST_CLUSTER;
                case KELP -> Items.KELP;
                case CAVE_VINES -> Items.GLOW_BERRIES;
                case VINES -> Items.VINE;
                case BAMBOO -> Items.BAMBOO;
                case COCOA -> Items.COCOA_BEANS;
                case BEE_NEST -> Items.BEE_NEST;
            });
        }
    }

    private static final Growth[] GROWTHS = Growth.values();

    // Einstellungen
    /** Simulation Distance des Servers in Chunks. */
    public static int simulationDistance = 5;
    /** So viel Waerme (ausgewachsene Funde im Umkreis) braucht ein Chunk mindestens. */
    public static int sensitivity = 6;
    public static int color = 0;
    public static double opacity = 0.4D;
    public static int plateY = 63;
    public static boolean tracers = true;
    public static boolean notify = true;
    /** Abgeerntete Geoden in eigener Farbe markieren. */
    public static boolean minedGeodes = false;
    public static int minedColor = 3;

    private static volatile boolean enabled;
    private static ClientLevel lastLevel;
    private static int ticks;
    private static int appliedRadius = simulationDistance;

    /** Waerme und Veto je Art und Chunk (aus allen Quadraten zusammengezaehlt). */
    private static final Long2IntOpenHashMap[] HEAT = perGrowth();
    private static final Long2IntOpenHashMap[] VETO = perGrowth();
    /** Was jeder gepruefte Chunk selbst beitraegt (zum Wieder-Abziehen): Funde je Art, Arten mit Veto als Bits. */
    private static final Long2ObjectOpenHashMap<int[]> OWN_HEAT = new Long2ObjectOpenHashMap<>();
    private static final Long2IntOpenHashMap OWN_VETO = new Long2IntOpenHashMap();
    /** Beste Licht-Funde je Chunk (Licht kommt manchmal in Teilen an - die vollstaendigste Messung zaehlt). */
    private static final Long2IntOpenHashMap LIGHT_CLUSTERS = new Long2IntOpenHashMap();
    private static final Long2IntOpenHashMap LIGHT_BUDS = new Long2IntOpenHashMap();
    private static final LongOpenHashSet DEEP = new LongOpenHashSet();
    /** Gepruefte Chunks -> Tick der letzten Pruefung. */
    private static final Long2IntOpenHashMap SCANNED = new Long2IntOpenHashMap();

    private static final LongArrayFIFOQueue QUEUE = new LongArrayFIFOQueue();
    private static final LongOpenHashSet QUEUED = new LongOpenHashSet();
    private static final LongOpenHashSet LIGHT_DIRTY = new LongOpenHashSet();
    private static final Long2IntOpenHashMap WAITING_SINCE = new Long2IntOpenHashMap();

    /** Freiliegende Amethyst-Wand eines Chunks (Positionen als asLong), davon knospend. */
    private record Wall(long[] blocks, long[] budding) {
    }
    /** Eine zusammenhaengende Geode ueber alle Chunks; chunk = Chunk ihrer Mitte. */
    private record Geode(long[] wall, int budding, int minX, int minZ, int maxX, int maxZ, long chunk) {
    }
    private static final Long2ObjectOpenHashMap<Wall> WALLS = new Long2ObjectOpenHashMap<>();
    private static boolean wallsChanged;
    private static List<Geode> geodes = List.of();

    // Anzeige
    private static final ChunkOverlayRenderer OVERLAY = new ChunkOverlayRenderer();
    private static final ChunkOverlayRenderer MINED_OVERLAY = new ChunkOverlayRenderer();
    private static final NovaToasts.Feed TOASTS = new NovaToasts.Feed(3);
    private static Set<Long> visible = Set.of();
    private static long[] visibleKeys = new long[0];
    private static Set<Long> mined = Set.of();
    private static long[] minedKeys = new long[0];
    private static boolean visibleDirty;
    private static int visibleAge;

    public SusChunkFinderMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        SusChunkFinderMod.enabled = enabled;
        clearAll();
        lastLevel = client.level;
        if (enabled) {
            queueLoaded(client);
        }
    }

    private static Long2IntOpenHashMap[] perGrowth() {
        Long2IntOpenHashMap[] maps = new Long2IntOpenHashMap[Growth.values().length];
        for (int i = 0; i < maps.length; i++) {
            maps[i] = new Long2IntOpenHashMap();
        }
        return maps;
    }

    private static void clearAll() {
        for (Growth growth : GROWTHS) {
            HEAT[growth.ordinal()].clear();
            VETO[growth.ordinal()].clear();
        }
        OWN_HEAT.clear();
        OWN_VETO.clear();
        LIGHT_CLUSTERS.clear();
        LIGHT_BUDS.clear();
        DEEP.clear();
        SCANNED.clear();
        QUEUE.clear();
        QUEUED.clear();
        LIGHT_DIRTY.clear();
        WAITING_SINCE.clear();
        WALLS.clear();
        wallsChanged = false;
        geodes = List.of();
        TOASTS.clear();
        visible = Set.of();
        visibleKeys = new long[0];
        mined = Set.of();
        minedKeys = new long[0];
        visibleDirty = true;
        appliedRadius = simulationDistance;
    }

    // ---------------------------------------------------------------- Ereignisse

    /** Chunk ist komplett eingelesen (LevelChunkMixin). */
    public static void onChunkLoaded(LevelChunk chunk) {
        if (enabled) {
            int x = chunk.getPos().getMinBlockX() >> 4;
            int z = chunk.getPos().getMinBlockZ() >> 4;
            enqueue(ChunkPos.pack(x, z));
            lightChanged(x, z);
        }
    }

    /** Licht-Update vom Server: der Chunk und seine Nachbarn werden nochmal geprueft. */
    public static void onLightUpdate(int chunkX, int chunkZ) {
        if (enabled) {
            lightChanged(chunkX, chunkZ);
        }
    }

    /** Block-Update vom Server (noch nicht angewendet): den Chunk neu pruefen, wenn vorher oder nachher etwas zaehlt. */
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        if (!enabled) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (relevant(state) || level != null && relevant(level.getBlockState(pos))) {
            enqueue(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        }
    }

    private static boolean relevant(BlockState state) {
        return counted(state) || isLining(state);
    }

    private static void lightChanged(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LIGHT_DIRTY.add(ChunkPos.pack(chunkX + dx, chunkZ + dz));
            }
        }
    }

    private static void enqueue(long key) {
        if (QUEUED.add(key)) {
            QUEUE.enqueue(key);
        }
    }

    private static void queueLoaded(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        int radius = client.options.getEffectiveRenderDistance() + 1;
        int cx = client.player.blockPosition().getX() >> 4;
        int cz = client.player.blockPosition().getZ() >> 4;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (client.level.hasChunk(x, z)) {
                    enqueue(ChunkPos.pack(x, z));
                }
            }
        }
    }

    // ---------------------------------------------------------------- Tick

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        if (level != lastLevel || simulationDistance != appliedRadius) {
            clearAll();
            lastLevel = level;
            queueLoaded(client);
        }
        if (level == null) {
            return;
        }
        ticks++;
        if (ticks % FULL_RESCAN_TICKS == 0) {
            LIGHT_DIRTY.clear();
            queueLoaded(client);
        } else if (ticks % 20 == 0 && !LIGHT_DIRTY.isEmpty()) {
            LongIterator keys = LIGHT_DIRTY.iterator();
            while (keys.hasNext()) {
                long key = keys.nextLong();
                if (level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                    enqueue(key);
                }
            }
            LIGHT_DIRTY.clear();
        }
        if (ticks % FORGET_TICKS == 0) {
            forgetFar(client);
        }

        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
            long key = QUEUE.dequeueLong();
            QUEUED.remove(key);
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            if (!level.hasChunk(x, z)) {
                WAITING_SINCE.remove(key);
            } else if (!lightSettled(level, key)) {
                LIGHT_DIRTY.add(key); // spaeter nochmal
            } else {
                WAITING_SINCE.remove(key);
                scan(level, key, level.getChunk(x, z));
                visibleDirty = true;
            }
        }

        if (++visibleAge >= 20 || visibleDirty && visibleAge >= 5) {
            updateVisible(client, level);
            updateMined(client, level);
            visibleDirty = false;
            visibleAge = 0;
        }
    }

    /** Licht des Chunks und seiner Nachbarn fertig? (spaetestens nach LIGHT_WAIT_TICKS) */
    private static boolean lightSettled(ClientLevel level, long key) {
        if (!WAITING_SINCE.containsKey(key)) {
            WAITING_SINCE.put(key, ticks);
        }
        if (ticks - WAITING_SINCE.get(key) >= LIGHT_WAIT_TICKS) {
            return true;
        }
        LevelLightEngine light = level.getLightEngine();
        if (light.hasLightWork()) {
            return false;
        }
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.hasChunk(cx + dx, cz + dz) || !light.lightOnInColumn(SectionPos.getZeroNode(cx + dx, cz + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Chunks weit hinter der Sichtweite (plus zwei Quadrate) vergessen - ihr Beitrag wird wieder abgezogen. */
    private static void forgetFar(Minecraft client) {
        if (client.player == null) {
            return;
        }
        int reach = client.options.getEffectiveRenderDistance() + 2 * appliedRadius + 4;
        int px = client.player.blockPosition().getX() >> 4;
        int pz = client.player.blockPosition().getZ() >> 4;
        LongArrayList far = new LongArrayList();
        LongIterator keys = SCANNED.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            if (Math.max(Math.abs(ChunkPos.getX(key) - px), Math.abs(ChunkPos.getZ(key) - pz)) > reach) {
                far.add(key);
            }
        }
        for (int i = 0; i < far.size(); i++) {
            long key = far.getLong(i);
            withdraw(key, appliedRadius);
            SCANNED.remove(key);
            DEEP.remove(key);
            LIGHT_CLUSTERS.remove(key);
            LIGHT_BUDS.remove(key);
            WAITING_SINCE.remove(key);
            if (WALLS.remove(key) != null) {
                wallsChanged = true;
            }
        }
        if (!far.isEmpty()) {
            visibleDirty = true;
        }
    }

    // ---------------------------------------------------------------- Pruefen

    /** Chunk (neu) bewerten: alten Beitrag abziehen, neuen im Quadrat der Simulation Distance verteilen. */
    private static void scan(ClientLevel level, long key, LevelChunk chunk) {
        int r = appliedRadius;
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        withdraw(key, r);

        if (hasBlocks(chunk)) {
            DEEP.add(key);
        } else {
            DEEP.remove(key);
        }
        int[] grown = new int[GROWTHS.length];
        int[] hidden = new int[2];
        int veto = count(level, chunk, grown, hidden);
        // Licht kommt manchmal in Teilen an: die beste Messung je Chunk behalten
        if (hidden[0] > LIGHT_CLUSTERS.get(key) || hidden[0] == LIGHT_CLUSTERS.get(key) && hidden[1] > LIGHT_BUDS.get(key)) {
            LIGHT_CLUSTERS.put(key, hidden[0]);
            LIGHT_BUDS.put(key, hidden[1]);
        }
        grown[Growth.AMETHYST.ordinal()] += LIGHT_CLUSTERS.get(key);
        if (LIGHT_BUDS.get(key) > 0) {
            veto |= Growth.AMETHYST.bit();
        }

        boolean warm = false;
        for (Growth growth : GROWTHS) {
            int i = growth.ordinal();
            if (grown[i] > 0) {
                area(cx, cz, r, HEAT[i], grown[i]);
                warm = true;
            }
            if ((veto & growth.bit()) != 0) {
                area(cx, cz, r, VETO[i], 1);
            }
        }
        if (warm) {
            OWN_HEAT.put(key, grown);
        }
        if (veto != 0) {
            OWN_VETO.put(key, veto);
        }
        SCANNED.put(key, ticks);
        if (minedGeodes) {
            collectWall(level, key, chunk);
        }
    }

    /** Bisherigen Beitrag des Chunks aus allen Quadraten abziehen. */
    private static void withdraw(long key, int r) {
        int[] own = OWN_HEAT.remove(key);
        int veto = OWN_VETO.remove(key);
        if (own == null && veto == 0) {
            return;
        }
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        for (Growth growth : GROWTHS) {
            int i = growth.ordinal();
            if (own != null && own[i] > 0) {
                area(cx, cz, r, HEAT[i], -own[i]);
            }
            if ((veto & growth.bit()) != 0) {
                area(cx, cz, r, VETO[i], -1);
            }
        }
    }

    private static void area(int cx, int cz, int r, Long2IntOpenHashMap map, int amount) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long key = ChunkPos.pack(cx + dx, cz + dz);
                int value = map.get(key) + amount;
                if (value > 0) {
                    map.put(key, value);
                } else {
                    map.remove(key);
                }
            }
        }
    }

    /** Hat der Chunk in der Tiefe (Y -16..30) ueberhaupt Bloecke? Sonst reicht schon wenig Waerme. */
    private static boolean hasBlocks(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            if (baseY + 15 >= DEEP_MIN_Y && baseY <= DEEP_MAX_Y && sections[i] != null && !sections[i].hasOnlyAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zaehlt die ausgewachsenen Funde je Art in grown und die versteckten Amethyste in hidden ({Cluster, Knospen}); gibt
     * die Arten mit Veto als Bits zurueck. Versteckt: Lichtquelle 5 (Cluster) bzw. 1/2/4 (Knospe) direkt am Amethyst,
     * wo kein leuchtender Block steht.
     */
    private static int count(ClientLevel level, LevelChunk chunk, int[] grown, int[] hidden) {
        int veto = 0;
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            boolean lining = section.maybeHas(SusChunkFinderMod::isLining);
            boolean signs = section.maybeHas(SusChunkFinderMod::counted);
            if (!lining && !signs) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Growth growth = signs ? growthOf(state) : null;
                        if (growth != null && growth.on) {
                            cursor.set(minX + x, baseY + y, minZ + z);
                            int sign = sign(level, cursor, state, growth);
                            if (sign > 0) {
                                grown[growth.ordinal()]++;
                            } else if (sign < 0) {
                                veto |= growth.bit();
                            }
                        } else if (lining && state.getLightEmission() <= 0 && !isLining(state)) {
                            cursor.set(minX + x, baseY + y, minZ + z);
                            int light = hiddenSource(level, cursor);
                            if (light == 5) {
                                hidden[0]++;
                            } else if (light > 0) {
                                hidden[1]++;
                            }
                        }
                    }
                }
            }
        }
        return veto;
    }

    /** +1 = ausgewachsen, -1 = kann noch wachsen (Veto), 0 = sagt nichts. Verschiebt at. */
    private static int sign(ClientLevel level, BlockPos.MutableBlockPos at, BlockState state, Growth growth) {
        return switch (growth) {
            // Knospe an knospendem Amethyst waechst noch, Knospe ohne ist abgebrochen (ausgewachsen)
            case AMETHYST -> {
                if (state.is(Blocks.AMETHYST_CLUSTER) || !state.hasProperty(BlockStateProperties.FACING)) {
                    yield 1;
                }
                Direction behind = state.getValue(BlockStateProperties.FACING).getOpposite();
                yield level.getBlockState(at.move(behind)).is(Blocks.BUDDING_AMETHYST) ? -1 : 1;
            }
            // die Spitze waechst bis Alter 25 nach oben ins Wasser (der Weltgenerator setzt hoechstens 23)
            case KELP -> state.getValue(BlockStateProperties.AGE_25) < 25
                    && level.getBlockState(at.move(Direction.UP)).is(Blocks.WATER) ? -1 : 1;
            // die Spitze waechst bis Alter 25 nach unten in die Luft
            case CAVE_VINES -> state.getValue(BlockStateProperties.AGE_25) < 25
                    && level.getBlockState(at.move(Direction.DOWN)).isAir() ? -1 : 1;
            case VINES -> vine(level, at, state);
            case BAMBOO -> bamboo(level, at, state);
            case COCOA -> state.getValue(BlockStateProperties.AGE_2) == 2 ? 1 : -1;
            case BEE_NEST -> state.getValue(BeehiveBlock.HONEY_LEVEL) == BeehiveBlock.MAX_HONEY_LEVELS ? 1 : -1;
        };
    }

    /** Nur das unterste Stueck zaehlt: am Boden = ausgewachsen; frei haengend mit Seitenhalt = waechst noch. */
    private static int vine(ClientLevel level, BlockPos.MutableBlockPos at, BlockState state) {
        BlockState below = level.getBlockState(at.move(Direction.DOWN));
        if (below.is(Blocks.VINE)) {
            return 0;
        }
        if (!below.isAir()) {
            return 1;
        }
        // nach unten waechst eine Ranke nur mit Seitenflaechen, die sie weitergeben kann
        boolean sides = state.getValue(BlockStateProperties.NORTH) || state.getValue(BlockStateProperties.EAST)
                || state.getValue(BlockStateProperties.SOUTH) || state.getValue(BlockStateProperties.WEST);
        return sides ? -1 : 1;
    }

    /** Nur die Spitze zaehlt: Stufe 1 = Wachstums-Stopp; Luft darueber, hell genug und unter 16 hoch = waechst noch. */
    private static int bamboo(ClientLevel level, BlockPos.MutableBlockPos at, BlockState state) {
        int x = at.getX();
        int y = at.getY();
        int z = at.getZ();
        BlockState above = level.getBlockState(at.move(Direction.UP));
        if (above.is(Blocks.BAMBOO)) {
            return 0;
        }
        if (state.getValue(BlockStateProperties.STAGE) == 1) {
            return 1;
        }
        int light = Math.max(level.getBrightness(LightLayer.BLOCK, at), level.getBrightness(LightLayer.SKY, at));
        if (!above.isAir() || light < 9) {
            return 0;
        }
        int height = 1;
        while (height < 16 && level.getBlockState(at.set(x, y - height, z)).is(Blocks.BAMBOO)) {
            height++;
        }
        return height < 16 ? -1 : 0;
    }

    /** Lichtwert einer versteckten Amethyst-Lichtquelle an pos (lokales Maximum direkt am Amethyst), sonst 0. */
    private static int hiddenSource(ClientLevel level, BlockPos pos) {
        int light = level.getBrightness(LightLayer.BLOCK, pos);
        if (light != 1 && light != 2 && light != 4 && light != 5) {
            return 0;
        }
        boolean lining = false;
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            next.setWithOffset(pos, direction);
            if (level.getBrightness(LightLayer.BLOCK, next) >= light) {
                return 0;
            }
            lining |= isLining(level.getBlockState(next));
        }
        return lining ? light : 0;
    }

    private static Growth growthOf(BlockState state) {
        if (isGrowth(state)) {
            return Growth.AMETHYST;
        } else if (state.is(Blocks.KELP)) {
            return Growth.KELP;
        } else if (state.is(Blocks.CAVE_VINES)) {
            return Growth.CAVE_VINES;
        } else if (state.is(Blocks.VINE)) {
            return Growth.VINES;
        } else if (state.is(Blocks.BAMBOO)) {
            return Growth.BAMBOO;
        } else if (state.is(Blocks.COCOA)) {
            return Growth.COCOA;
        } else if (state.is(Blocks.BEE_NEST)) {
            return Growth.BEE_NEST;
        }
        return null;
    }

    private static boolean counted(BlockState state) {
        Growth growth = growthOf(state);
        return growth != null && growth.on;
    }

    private static boolean isLining(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST);
    }

    private static boolean isBud(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD);
    }

    private static boolean isGrowth(BlockState state) {
        return state.is(Blocks.AMETHYST_CLUSTER) || isBud(state);
    }

    // ---------------------------------------------------------------- Abgeerntete Geoden

    /** Freiliegende Amethyst-Wand des Chunks merken (nur Overworld, unter GEODE_MAX_Y). */
    private static void collectWall(ClientLevel level, long key, LevelChunk chunk) {
        if (WALLS.remove(key) != null) {
            wallsChanged = true;
        }
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }
        LongArrayList blocks = new LongArrayList();
        LongArrayList budding = new LongArrayList();
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            if (baseY > GEODE_MAX_Y) {
                break;
            }
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(SusChunkFinderMod::isLining)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (isLining(state) && open(level, cursor.set(minX + x, baseY + y, minZ + z))) {
                            blocks.add(cursor.asLong());
                            if (state.is(Blocks.BUDDING_AMETHYST)) {
                                budding.add(cursor.asLong());
                            }
                        }
                    }
                }
            }
        }
        if (!blocks.isEmpty()) {
            WALLS.put(key, new Wall(blocks.toLongArray(), budding.toLongArray()));
            wallsChanged = true;
        }
    }

    /** Mindestens eine Seite frei (Luft, Wasser oder Amethyst-Wuchs)? Nachbarn in ungeladenen Chunks zaehlen nicht. */
    private static boolean open(ClientLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (Direction direction : DIRECTIONS) {
            next.setWithOffset(pos, direction);
            if (level.hasChunk(next.getX() >> 4, next.getZ() >> 4)) {
                BlockState state = level.getBlockState(next);
                if (state.isAir() || state.is(Blocks.WATER) || isGrowth(state)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Wand-Bloecke in 4er-Wuerfel einteilen; Wuerfel, die sich beruehren (auch schraeg), bilden eine Geode. */
    private static List<Geode> groupGeodes() {
        Long2ObjectOpenHashMap<LongArrayList> cells = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet budding = new LongOpenHashSet();
        for (Wall wall : WALLS.values()) {
            for (long block : wall.blocks()) {
                long cell = BlockPos.asLong(BlockPos.getX(block) >> 2, BlockPos.getY(block) >> 2, BlockPos.getZ(block) >> 2);
                LongArrayList members = cells.get(cell);
                if (members == null) {
                    members = new LongArrayList();
                    cells.put(cell, members);
                }
                members.add(block);
            }
            for (long block : wall.budding()) {
                budding.add(block);
            }
        }
        List<Geode> result = new ArrayList<>();
        LongOpenHashSet grouped = new LongOpenHashSet();
        LongArrayFIFOQueue next = new LongArrayFIFOQueue();
        LongIterator starts = cells.keySet().iterator();
        while (starts.hasNext()) {
            long start = starts.nextLong();
            if (!grouped.add(start)) {
                continue;
            }
            LongArrayList wall = new LongArrayList();
            next.enqueue(start);
            while (!next.isEmpty()) {
                long cell = next.dequeueLong();
                wall.addAll(cells.get(cell));
                int x = BlockPos.getX(cell);
                int y = BlockPos.getY(cell);
                int z = BlockPos.getZ(cell);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long touching = BlockPos.asLong(x + dx, y + dy, z + dz);
                            if (cells.containsKey(touching) && grouped.add(touching)) {
                                next.enqueue(touching);
                            }
                        }
                    }
                }
            }
            result.add(geode(wall.toLongArray(), budding));
        }
        return result;
    }

    private static Geode geode(long[] wall, LongOpenHashSet budding) {
        int count = 0;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        long sumX = 0L;
        long sumZ = 0L;
        for (long block : wall) {
            int x = BlockPos.getX(block);
            int z = BlockPos.getZ(block);
            minX = Math.min(minX, x);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxZ = Math.max(maxZ, z);
            sumX += x;
            sumZ += z;
            if (budding.contains(block)) {
                count++;
            }
        }
        int centerX = (int) Math.floorDiv(sumX, (long) wall.length);
        int centerZ = (int) Math.floorDiv(sumZ, (long) wall.length);
        return new Geode(wall, count, minX, minZ, maxX, maxZ, ChunkPos.pack(centerX >> 4, centerZ >> 4));
    }

    /** Alle Chunks unter der Geode (mit einem Block Rand) geladen und seit GEODE_SETTLE_TICKS geprueft? */
    private static boolean settled(ClientLevel level, Geode geode) {
        for (int cx = (geode.minX() - 1) >> 4; cx <= (geode.maxX() + 1) >> 4; cx++) {
            for (int cz = (geode.minZ() - 1) >> 4; cz <= (geode.maxZ() + 1) >> 4; cz++) {
                long key = ChunkPos.pack(cx, cz);
                if (!level.hasChunk(cx, cz) || !SCANNED.containsKey(key) || ticks - SCANNED.get(key) < GEODE_SETTLE_TICKS) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Kein Cluster und keine Knospe an der Wand, und jedes Licht dort stammt von einer Fackel. */
    private static boolean harvested(ClientLevel level, long[] wall) {
        Long2IntOpenHashMap sources = new Long2IntOpenHashMap();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (long block : wall) {
            int x = BlockPos.getX(block);
            int y = BlockPos.getY(block);
            int z = BlockPos.getZ(block);
            for (Direction direction : DIRECTIONS) {
                next.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                if (isGrowth(level.getBlockState(next))) {
                    return false;
                }
                if (level.getBrightness(LightLayer.BLOCK, next) > 0 && lightSource(level, next, sources) != TORCH_LIGHT) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Staerke der Lichtquelle hinter einem hellen Feld: immer zum hellsten Nachbarn weitergehen, bis keiner heller ist
     * (Licht faellt je Block um 1, nach hoechstens 15 Schritten ist man da). Gelaufene Felder kommen in den Cache.
     */
    private static int lightSource(ClientLevel level, BlockPos from, Long2IntOpenHashMap cache) {
        BlockPos.MutableBlockPos at = from.mutable();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        LongArrayList walked = new LongArrayList();
        int value = level.getBrightness(LightLayer.BLOCK, at);
        int source = -1;
        for (int step = 0; step <= 15 && source < 0; step++) {
            long here = at.asLong();
            if (cache.containsKey(here)) {
                source = cache.get(here);
                break;
            }
            walked.add(here);
            Direction brighter = null;
            for (Direction direction : DIRECTIONS) {
                int light = level.getBrightness(LightLayer.BLOCK, probe.setWithOffset(at, direction));
                if (light > value) {
                    value = light;
                    brighter = direction;
                }
            }
            if (brighter == null) {
                source = value;
            } else {
                at.move(brighter);
            }
        }
        if (source < 0) {
            source = value;
        }
        for (int i = 0; i < walked.size(); i++) {
            cache.put(walked.getLong(i), source);
        }
        return source;
    }

    // ---------------------------------------------------------------- Auswerten

    /** Waermste Chunks ohne Veto (lokale Spitzen) markieren, neue melden. */
    private static void updateVisible(Minecraft client, ClientLevel level) {
        int reach = appliedRadius;
        // Waerme je Chunk: alle Arten zusammen, die dort kein Veto haben
        Long2IntOpenHashMap total = new Long2IntOpenHashMap();
        for (Growth growth : GROWTHS) {
            Long2IntOpenHashMap veto = VETO[growth.ordinal()];
            for (Long2IntMap.Entry entry : HEAT[growth.ordinal()].long2IntEntrySet()) {
                if (!veto.containsKey(entry.getLongKey())) {
                    total.addTo(entry.getLongKey(), entry.getIntValue());
                }
            }
        }
        Long2IntOpenHashMap eligible = new Long2IntOpenHashMap();
        for (Long2IntMap.Entry entry : total.long2IntEntrySet()) {
            long key = entry.getLongKey();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            if (level.hasChunk(x, z) && scannedNeighbours(x, z) >= 3) {
                eligible.put(key, entry.getIntValue());
            }
        }
        Set<Long> next = new HashSet<>();
        for (Long2IntMap.Entry entry : eligible.long2IntEntrySet()) {
            long key = entry.getLongKey();
            int heat = entry.getIntValue();
            // ohne Bloecke in der Tiefe (versteckt) reicht schon ein einziger Amethyst-Fund
            boolean enough = heat >= sensitivity || !DEEP.contains(key) && warmth(Growth.AMETHYST, key) >= 1;
            if (enough && isPeak(eligible, key, heat, reach)) {
                next.add(key);
            }
        }
        if (notify && client.player != null) {
            for (long key : next) {
                if (!visible.contains(key)) {
                    announce(key);
                }
            }
        }
        visible = next;
        visibleKeys = next.stream().mapToLong(Long::longValue).toArray();
    }

    /** Waerme einer Art im Chunk (0 bei Veto). */
    private static int warmth(Growth growth, long key) {
        return VETO[growth.ordinal()].containsKey(key) ? 0 : HEAT[growth.ordinal()].get(key);
    }

    /** Neuer Sus Chunk: Toast mit den (hoechstens zwei) staerksten Arten. */
    private static void announce(long key) {
        List<Growth> strongest = new ArrayList<>();
        for (Growth growth : GROWTHS) {
            if (warmth(growth, key) > 0) {
                strongest.add(growth);
            }
        }
        strongest.sort((a, b) -> Integer.compare(warmth(b, key), warmth(a, key)));
        List<String> labels = new ArrayList<>();
        List<ItemStack> icons = new ArrayList<>();
        for (Growth growth : strongest.subList(0, Math.min(2, strongest.size()))) {
            labels.add(growth.label);
            icons.add(growth.icon());
        }
        int x = (ChunkPos.getX(key) << 4) + 8;
        int z = (ChunkPos.getZ(key) << 4) + 8;
        TOASTS.push("sus:" + key, "Sus chunk", String.join(", ", labels), new BlockPos(x, plateY, z), null, icons);
        NovaNotify.sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2F);
    }

    /** Abgeerntete Geoden neu bewerten (gruppiert wird nur, wenn sich eine Wand geaendert hat). */
    private static void updateMined(Minecraft client, ClientLevel level) {
        if (!minedGeodes) {
            if (minedKeys.length > 0) {
                mined = Set.of();
                minedKeys = new long[0];
            }
            return;
        }
        if (wallsChanged) {
            geodes = groupGeodes();
            wallsChanged = false;
        }
        Set<Long> next = new HashSet<>();
        for (Geode geode : geodes) {
            boolean big = geode.budding() > 0 ? geode.budding() >= GEODE_MIN_BUDDING : geode.wall().length >= GEODE_MIN_WALL;
            if (big && !next.contains(geode.chunk()) && settled(level, geode) && harvested(level, geode.wall())) {
                next.add(geode.chunk());
            }
        }
        if (notify && client.player != null) {
            for (long key : next) {
                if (!mined.contains(key)) {
                    int x = (ChunkPos.getX(key) << 4) + 8;
                    int z = (ChunkPos.getZ(key) << 4) + 8;
                    TOASTS.push("mined:" + key, "Mined geode", "Harvested", new BlockPos(x, plateY, z), null,
                            List.of(new ItemStack(Items.AMETHYST_SHARD)));
                    NovaNotify.sound(SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F);
                }
            }
        }
        mined = next;
        minedKeys = next.stream().mapToLong(Long::longValue).toArray();
    }

    private static boolean isPeak(Long2IntOpenHashMap field, long key, int value, int reach) {
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                if ((dx != 0 || dz != 0) && field.get(ChunkPos.pack(cx + dx, cz + dz)) > value) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int scannedNeighbours(int cx, int cz) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx != 0 || dz != 0) && SCANNED.containsKey(ChunkPos.pack(cx + dx, cz + dz))) {
                    count++;
                }
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- Zeichnen

    public static void renderToasts(GuiGraphicsExtractor g) {
        if (enabled) {
            TOASTS.render(g, 6);
        }
    }

    /** Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        long[] keys = visibleKeys;
        long[] minedNow = minedKeys;
        if (!enabled || client.level == null || client.player == null || keys.length == 0 && minedNow.length == 0) {
            return;
        }
        if (keys.length > 0) {
            int rgb = COLOR_VALUES[color];
            OVERLAY.emit(keys, visible, plateY, rgb, (float) opacity, 1.0F);
            if (tracers) {
                tracers(client, keys, rgb);
            }
        }
        if (minedNow.length > 0) {
            int rgb = COLOR_VALUES[minedColor];
            MINED_OVERLAY.emit(minedNow, mined, plateY, rgb, (float) opacity, 1.0F);
            if (tracers) {
                tracers(client, minedNow, rgb);
            }
        }
    }

    /** Linien zu den naechsten markierten Chunks. */
    private static void tracers(Minecraft client, long[] keys, int rgb) {
        Vec3 start = NovaTracers.start();
        Vec3 eye = client.player.getEyePosition();
        List<Vec3> targets = new ArrayList<>();
        for (long key : keys) {
            targets.add(new Vec3((ChunkPos.getX(key) << 4) + 8, plateY, (ChunkPos.getZ(key) << 4) + 8));
        }
        targets.sort((a, b) -> Double.compare(a.distanceToSqr(eye), b.distanceToSqr(eye)));
        for (int i = 0; i < Math.min(MAX_TRACERS, targets.size()); i++) {
            NovaTracers.line(start, targets.get(i), rgb, 160.0D);
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    private static String status() {
        if (!enabled) {
            return "Module is off";
        }
        if (SCANNED.isEmpty()) {
            return "Scanning loaded chunks ...";
        }
        String geodeText = minedGeodes ? " · " + mined.size() + " mined geode" + (mined.size() == 1 ? "" : "s") : "";
        return visible.size() + " sus chunk" + (visible.size() == 1 ? "" : "s") + geodeText + " · " + SCANNED.size()
                + " chunks checked" + (QUEUE.isEmpty() ? "" : " · " + QUEUE.size() + " waiting");
    }

    private static void rescan() {
        clearAll();
        if (enabled) {
            queueLoaded(Minecraft.getInstance());
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        List<ModOptionsMenu.Option> options = new ArrayList<>();
        options.add(new ModOptionsMenu.Slider("Simulation distance", 2.0D, 16.0D, 1.0D, () -> simulationDistance,
                v -> simulationDistance = (int) Math.round(v), v -> Math.round(v) + " chunks"));
        options.add(new ModOptionsMenu.Slider("Sensitivity", 1.0D, 20.0D, 1.0D, () -> sensitivity, v -> {
            sensitivity = (int) Math.round(v);
            visibleDirty = true;
        }, v -> Math.round(v) + " grown finds"));
        options.add(new ModOptionsMenu.Section("Growth signs"));
        for (Growth growth : GROWTHS) {
            if (growth != Growth.AMETHYST) {
                options.add(new ModOptionsMenu.Toggle(growth.label, () -> growth.on, v -> {
                    growth.on = v;
                    rescan();
                }));
            }
        }
        options.add(new ModOptionsMenu.Section("Mined geodes"));
        options.add(new ModOptionsMenu.Toggle("Mark harvested geodes", () -> minedGeodes, v -> {
            minedGeodes = v;
            rescan();
        }));
        options.add(new ModOptionsMenu.Choice("Geode color", COLORS, () -> minedColor, v -> minedColor = v));
        options.add(new ModOptionsMenu.Section("Display"));
        options.add(new ModOptionsMenu.Choice("Color", COLORS, () -> color, v -> color = v));
        options.add(new ModOptionsMenu.Slider("Opacity", 0.1D, 1.0D, 0.05D, () -> opacity, v -> opacity = v,
                v -> Math.round(v * 100.0D) + "%"));
        options.add(new ModOptionsMenu.Slider("Marker height", -64.0D, 320.0D, 1.0D, () -> plateY,
                v -> plateY = (int) Math.round(v), v -> "Y " + Math.round(v)));
        options.add(new ModOptionsMenu.Toggle("Tracers", () -> tracers, v -> tracers = v));
        options.add(new ModOptionsMenu.Toggle("Notifications", () -> notify, v -> notify = v));
        options.add(new ModOptionsMenu.Action(() -> "Rescan", SusChunkFinderMod::rescan));
        options.add(new ModOptionsMenu.Info(SusChunkFinderMod::status));
        return new ModOptionsMenu(parent, "Sus Chunk Finder", "Finds where players spent time, from grown amethyst and plants",
                options);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "simulationDistance", String.valueOf(simulationDistance));
        properties.setProperty(prefix + "heatSensitivity", String.valueOf(sensitivity));
        properties.setProperty(prefix + "color", String.valueOf(color));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
        properties.setProperty(prefix + "plateY", String.valueOf(plateY));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "notify", String.valueOf(notify));
        properties.setProperty(prefix + "minedGeodes", String.valueOf(minedGeodes));
        properties.setProperty(prefix + "minedColor", String.valueOf(minedColor));
        for (Growth growth : GROWTHS) {
            if (growth != Growth.AMETHYST) {
                properties.setProperty(prefix + growth.key, String.valueOf(growth.on));
            }
        }
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        simulationDistance = readInt(properties, prefix + "simulationDistance", simulationDistance, 2, 16);
        sensitivity = readInt(properties, prefix + "heatSensitivity", sensitivity, 1, 20);
        color = readInt(properties, prefix + "color", color, 0, COLORS.length - 1);
        plateY = readInt(properties, prefix + "plateY", plateY, -64, 320);
        minedColor = readInt(properties, prefix + "minedColor", minedColor, 0, COLORS.length - 1);
        try {
            opacity = Mth.clamp(Double.parseDouble(properties.getProperty(prefix + "opacity", String.valueOf(opacity)).trim()), 0.1D, 1.0D);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)));
        notify = Boolean.parseBoolean(properties.getProperty(prefix + "notify", String.valueOf(notify)));
        minedGeodes = Boolean.parseBoolean(properties.getProperty(prefix + "minedGeodes", String.valueOf(minedGeodes)));
        for (Growth growth : GROWTHS) {
            if (growth != Growth.AMETHYST) {
                growth.on = Boolean.parseBoolean(properties.getProperty(prefix + growth.key, String.valueOf(growth.on)));
            }
        }
    }

    private static int readInt(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Mth.clamp(Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
