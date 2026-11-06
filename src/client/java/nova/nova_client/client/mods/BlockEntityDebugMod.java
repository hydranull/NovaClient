package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaTracers;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block Entity Debug: Anti-Xray versteckt Bloecke, aber nicht ihre Block-Entities - Kisten, Spawner, Oefen, Schilder
 * ... kommen mit Position im Chunk-Paket (bzw. als eigenes Paket) mit. Diese Positionen werden immer mitgeschrieben (auch
 * bei ausgeschaltetem Modul) und als ESP angezeigt, egal welcher Block dort scheinbar steht. Optional werden Block-Updates
 * verworfen, mit denen der Server so eine Stelle nachtraeglich zu Luft/Stein "verstecken" will.
 */
public class BlockEntityDebugMod extends GameTickMod {
    public static final String[] DETECTION = {"All", "Below 0", "Below -20", "Below -30"};
    private static final int[] DETECTION_Y = {Integer.MAX_VALUE, 0, -20, -30};
    private static final int MAX_SHOWN = 4000;
    private static final int MAX_TRACERS = 96;

    /** Arten von Block-Entities (Farbe je Art). */
    public enum Kind {
        SPAWNER("Spawners (no trial spawners)", 0xFF4060),
        STORAGE("Storage (chests, barrels, shulkers ...)", 0xFFA21F),
        UTILITY("Furnaces, brewing, campfires", 0x9AD0FF),
        SIGN("Signs & banners", 0xFFE066),
        BED("Beds", 0xFF6EB4),
        PISTON("Moving pistons", 0x7CFC00),
        OTHER("Everything else", 0xE0E0E0);

        public final String label;
        public final int color;

        Kind(String label, int color) {
            this.label = label;
            this.color = color;
        }

        /** Art des Block-Entities; null = gar nicht markieren (Trial Spawner: Trial Chambers, keine Basen). */
        static Kind of(BlockEntityType<?> type) {
            Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
            String path = id == null ? "" : id.getPath();
            return switch (path) {
                case "trial_spawner" -> null;
                case "mob_spawner" -> SPAWNER;
                case "chest", "trapped_chest", "barrel", "shulker_box", "ender_chest", "hopper", "dropper", "dispenser",
                     "crafter", "chiseled_bookshelf", "decorated_pot" -> STORAGE;
                case "furnace", "blast_furnace", "smoker", "brewing_stand", "campfire", "beacon", "enchanting_table",
                     "lectern", "jukebox" -> UTILITY;
                case "sign", "hanging_sign", "banner" -> SIGN;
                case "bed" -> BED;
                case "piston" -> PISTON;
                default -> OTHER;
            };
        }
    }

    // Einstellungen
    public static int detection = 0;
    public static final Set<Kind> SHOWN = EnumSet.allOf(Kind.class);
    public static boolean tracers = true;
    public static boolean spawnerBeams = true;
    /** Block-Updates verwerfen, die eine gefundene Stelle zu Luft/Stein/Tiefenschiefer machen wollen. */
    public static boolean keepHidden = true;
    public static int range = 256;

    private static volatile boolean enabled;
    /** Chunk -> (Position -> Art). Wird auch vom Netzwerk-Thread gelesen. */
    private static final Map<Long, Map<Long, Kind>> LEAKS = new ConcurrentHashMap<>();
    private static ClientLevel captureLevel;

    private static long[] shown = new long[0];
    private static Kind[] shownKinds = new Kind[0];
    /** Zaehlt jede Aenderung an LEAKS (auch vom Netzwerk-Thread) - dann wird die Anzeige neu gebaut. */
    private static volatile int version;
    private static int ticks;
    private static int builtTick;
    private static int builtVersion = -1;
    private static int builtSettings = Integer.MIN_VALUE;
    private static double builtX;
    private static double builtY;
    private static double builtZ;
    private static long[] sortKeys = new long[1024];
    private static long[] sortPositions = new long[1024];

    public BlockEntityDebugMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        builtSettings = Integer.MIN_VALUE;
        if (!on) {
            shown = new long[0];
            shownKinds = new Kind[0];
        }
    }

    // ---------------------------------------------------------------- Mitschreiben (Hauptthread, immer)

    /** Andere Welt/Dimension: alte Funde gehoeren nicht mehr dazu (beim Tod bleibt die Welt dieselbe). */
    private static void syncLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != captureLevel) {
            captureLevel = level;
            LEAKS.clear();
            version++;
        }
    }

    public static void onChunkPacket(ClientboundLevelChunkWithLightPacket packet) {
        syncLevel();
        int x = packet.x();
        int z = packet.z();
        try {
            //? if >=26.1 {
            packet.chunkData().forEachBlockEntityTag(x, z, (pos, type, tag) -> {
                if (type != null) {
                    put(pos, type);
                }
            });
            //?} else {
            /*packet.chunkData().getBlockEntitiesTagsConsumer(x, z).accept((pos, type, tag) -> {
                if (type != null) {
                    put(pos, type);
                }
            });
            *///?}
        } catch (RuntimeException ignored) {
            // kaputte Daten: Chunk ueberspringen
        }
    }

    public static void onBlockEntityData(BlockPos pos, BlockEntityType<?> type) {
        syncLevel();
        if (type != null) {
            put(pos, type);
        }
    }

    private static void put(BlockPos pos, BlockEntityType<?> type) {
        Kind kind = Kind.of(type);
        if (kind == null) {
            return;
        }
        LEAKS.computeIfAbsent(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), k -> new ConcurrentHashMap<>())
                .put(pos.asLong(), kind);
        version++;
    }

    /** Chunk entladen: seine Eintraege vergessen (kommen beim naechsten Laden wieder mit). */
    public static void onForgetChunk(int x, int z) {
        LEAKS.remove(ChunkPos.pack(x, z));
        version++;
    }

    /** Neuer Server. */
    public static void clear() {
        LEAKS.clear();
        captureLevel = null;
        version++;
    }

    /** Netzwerk-Thread: dieses Block-Update verwerfen? */
    public static boolean hidesUpdate(BlockPos pos, BlockState state) {
        if (!enabled || !keepHidden || !(state.isAir() || state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE))) {
            return false;
        }
        Map<Long, Kind> chunk = LEAKS.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
        return chunk != null && chunk.containsKey(pos.asLong());
    }

    // ---------------------------------------------------------------- Tick: Anzeige bauen

    @Override
    public void execute(Minecraft client) {
        if (client.player == null) {
            return;
        }
        Vec3 eye = client.player.getEyePosition();
        int maxY = DETECTION_Y[Math.max(0, Math.min(DETECTION.length - 1, detection))];
        // Neu sortieren nur, wenn noetig: Einstellungen geaendert sofort, neue Funde/4 Bloecke bewegt hoechstens alle
        // 5 Ticks, sonst jede Sekunde (vorher jeden Tick alle Funde - bei Zehntausenden Kisten teuer)
        int settings = (shownMask() * 31 + range) * 31 + maxY;
        ticks++;
        boolean moved = eye.distanceToSqr(builtX, builtY, builtZ) > 16.0D;
        boolean changed = version != builtVersion;
        if (settings == builtSettings && ticks - builtTick < 20 && !((moved || changed) && ticks - builtTick >= 5)) {
            return;
        }
        builtSettings = settings;
        builtTick = ticks;
        builtVersion = version;
        builtX = eye.x;
        builtY = eye.y;
        builtZ = eye.z;

        double rangeSq = (double) range * range;
        int count = 0;
        for (Map<Long, Kind> chunk : LEAKS.values()) {
            for (Map.Entry<Long, Kind> entry : chunk.entrySet()) {
                Kind kind = entry.getValue();
                if (!SHOWN.contains(kind)) {
                    continue;
                }
                long packed = entry.getKey();
                int y = BlockPos.getY(packed);
                if (y >= maxY) {
                    continue;
                }
                double dx = BlockPos.getX(packed) + 0.5D - eye.x;
                double dy = y + 0.5D - eye.y;
                double dz = BlockPos.getZ(packed) + 0.5D - eye.z;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq <= rangeSq && count < (1 << 28)) {
                    if (count == sortKeys.length) {
                        sortKeys = java.util.Arrays.copyOf(sortKeys, count * 2);
                        sortPositions = java.util.Arrays.copyOf(sortPositions, count * 2);
                    }
                    // nach Abstand (ganze Zahl wie vorher), bei Gleichstand in Fund-Reihenfolge; Art und Index mit drin
                    sortKeys[count] = ((long) distSq << 32) | ((long) kind.ordinal() << 28) | count;
                    sortPositions[count] = packed;
                    count++;
                }
            }
        }
        java.util.Arrays.sort(sortKeys, 0, count);
        int n = Math.min(MAX_SHOWN, count);
        long[] positions = new long[n];
        Kind[] kinds = new Kind[n];
        Kind[] all = Kind.values();
        for (int i = 0; i < n; i++) {
            long key = sortKeys[i];
            positions[i] = sortPositions[(int) (key & 0x0FFFFFFFL)];
            kinds[i] = all[(int) ((key >>> 28) & 0xF)];
        }
        shown = positions;
        shownKinds = kinds;
    }

    private static int shownMask() {
        int mask = 0;
        for (Kind kind : SHOWN) {
            mask |= 1 << kind.ordinal();
        }
        return mask;
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Jeden Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        long[] positions = shown;
        Kind[] kinds = shownKinds;
        if (!enabled || client.player == null || positions.length == 0) {
            return;
        }
        GizmoStyle[] styles = new GizmoStyle[Kind.values().length];
        for (Kind kind : Kind.values()) {
            styles[kind.ordinal()] = GizmoStyle.strokeAndFill(0xFF000000 | kind.color, 1.5F, 0x55000000 | kind.color);
        }
        Vec3 start = tracers ? NovaTracers.start() : null;
        GizmoStyle beam = GizmoStyle.stroke(0xFF000000 | Kind.SPAWNER.color, 1.5F);
        net.minecraft.client.renderer.culling.Frustum view = nova.nova_client.client.ViewCull.frustum();
        for (int i = 0; i < positions.length && i < kinds.length; i++) {
            BlockPos pos = BlockPos.of(positions[i]);
            if (nova.nova_client.client.ViewCull.visible(view, pos)) {
                Gizmos.cuboid(new AABB(pos), styles[kinds[i].ordinal()]).setAlwaysOnTop();
            }
            if (start != null && i < MAX_TRACERS) {
                NovaTracers.line(start, Vec3.atCenterOf(pos), kinds[i].color, range * 0.6D);
            }
            if (spawnerBeams && kinds[i] == Kind.SPAWNER && nova.nova_client.client.ViewCull.visible(view, pos.getX() + 0.3D,
                    pos.getY() + 1.0D, pos.getZ() + 0.3D, pos.getX() + 0.7D, pos.getY() + 257.0D, pos.getZ() + 0.7D, 1.5F)) {
                Gizmos.cuboid(new AABB(pos.getX() + 0.3D, pos.getY() + 1.0D, pos.getZ() + 0.3D,
                        pos.getX() + 0.7D, pos.getY() + 257.0D, pos.getZ() + 0.7D), beam).setAlwaysOnTop();
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    private static int total() {
        int n = 0;
        for (Map<Long, Kind> chunk : LEAKS.values()) {
            n += chunk.size();
        }
        return n;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        int accent = NovaGui.accent(ModCategory.BASEFINDING);
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Choice("Detection", DETECTION, () -> detection, v -> detection = v),
                new ModOptionsMenu.Action(() -> "Block entity types (" + SHOWN.size() + "/" + Kind.values().length + ")",
                        () -> Minecraft.getInstance().gui.setScreen(typesMenu(Minecraft.getInstance().gui.screen(), accent))),
                new ModOptionsMenu.Slider("Range", 32.0D, 512.0D, 16.0D, () -> range,
                        v -> range = (int) Math.round(v), v -> Math.round(v) + " blocks"),
                new ModOptionsMenu.Toggle("Tracers", () -> tracers, v -> tracers = v),
                new ModOptionsMenu.Toggle("Spawner beams", () -> spawnerBeams, v -> spawnerBeams = v),
                new ModOptionsMenu.Toggle("Ignore hide updates (air/stone)", () -> keepHidden, v -> keepHidden = v),
                new ModOptionsMenu.Info(() -> "Shows block entities the server leaks under anti-xray"),
                new ModOptionsMenu.Info(() -> enabled ? shown.length + " shown  ·  " + total() + " captured" : "Module is off")));
    }

    private Screen typesMenu(Screen parent, int accent) {
        List<ModOptionsMenu.Option> options = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            options.add(new ModOptionsMenu.Toggle(kind.label, () -> SHOWN.contains(kind), v -> {
                if (v) {
                    SHOWN.add(kind);
                } else {
                    SHOWN.remove(kind);
                }
            }));
        }
        return new ModOptionsMenu(parent, name + " · Types", "", options);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "detection", String.valueOf(detection));
        List<String> kinds = new ArrayList<>();
        for (Kind kind : SHOWN) {
            kinds.add(kind.name());
        }
        properties.setProperty(prefix + "kinds", String.join(",", kinds));
        properties.setProperty(prefix + "range", String.valueOf(range));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "spawnerBeams", String.valueOf(spawnerBeams));
        properties.setProperty(prefix + "keepHidden", String.valueOf(keepHidden));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            detection = Math.max(0, Math.min(DETECTION.length - 1, Integer.parseInt(properties.getProperty(prefix + "detection", String.valueOf(detection)).trim())));
            range = Math.max(32, Math.min(512, Integer.parseInt(properties.getProperty(prefix + "range", String.valueOf(range)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        String kinds = properties.getProperty(prefix + "kinds");
        if (kinds != null) {
            SHOWN.clear();
            for (String part : kinds.split(",")) {
                try {
                    SHOWN.add(Kind.valueOf(part.trim()));
                } catch (IllegalArgumentException ignored) {
                    // unbekannte Art ueberspringen
                }
            }
        }
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)));
        spawnerBeams = Boolean.parseBoolean(properties.getProperty(prefix + "spawnerBeams", String.valueOf(spawnerBeams)));
        keepHidden = Boolean.parseBoolean(properties.getProperty(prefix + "keepHidden", String.valueOf(keepHidden)));
    }
}
