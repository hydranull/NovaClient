package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.NovaTracers;
import nova.nova_client.client.ViewCull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Netherite Finder: findet im Nether die 16x16x16-Abschnitte, in denen Ancient Debris versteckt ist.
 * <p>
 * Anti-Xray ersetzt den Debris beim Senden durch Netherrack - laesst den Debris aber in der Palette des Abschnitts
 * stehen (die Liste der Blockarten, die darin vorkommen). Steht Debris in der Palette, aber kein einziger Block im
 * Abschnitt ist Debris, wurde er versteckt: dort lohnt sich das Graben. Wird ein Debris aufgedeckt (Block-Update),
 * verschwindet die Markierung.
 * <p>
 * Advanced: steht man in Reichweite eines solchen Abschnitts, tippt Nova dort Bloecke kurz an (Abbauen starten und
 * sofort abbrechen, in einem Raster mit 3 Bloecken Abstand). Anti-Xray schickt dabei die echten Bloecke drumherum -
 * so taucht der versteckte Debris als einzelner Block auf und wird hell markiert. Nur im Ueberlebensmodus und nur mit
 * einem Nicht-Werkzeug in der Hand (sonst koennte ein Block wirklich abgebaut werden).
 */
public class NetheriteFinderMod extends GameTickMod {
    private static final int MAX_SECTIONS = 512;
    private static final int MAX_DRAWN = 128;
    private static final long NOTIFY_EVERY_MS = 2000L;
    private static final int NEAR = 0xFF8A2B;
    private static final int DEBRIS = 0xFFB347;
    /** Reichweite fuer das Antippen (Bloecke, ab Augenhoehe). */
    private static final double PROBE_REACH = 4.5D;
    private static final int MAX_REVEALED = 256;
    private static final int MAX_PROBED = 100_000;
    private static final int FAR = 0x4C7BFF;

    public static int range = 384;
    public static int fadeDistance = 192;
    public static boolean tracers = true;
    public static int tracerCount = 3;
    public static boolean notify = true;
    public static boolean advanced = false;
    public static int probesPerTick = 6;

    private static volatile boolean enabled;
    /** Abschnitte mit verstecktem Debris (SectionPos.asLong), aelteste zuerst. */
    private static final Set<Long> SECTIONS = new LinkedHashSet<>();
    private static final NovaToasts.Feed TOASTS = new NovaToasts.Feed(3);
    /** Schon angetippte Bloecke (BlockPos.asLong). */
    private static final Set<Long> PROBED = new java.util.HashSet<>();
    /** Aufgedeckter Debris (BlockPos.asLong), neueste zuletzt. */
    private static final Set<Long> REVEALED = new LinkedHashSet<>();
    private static String advancedStatus = "";
    private static ClientLevel lastLevel;
    private static int pending;
    private static long nearestPending;
    private static long lastNotify;

    public NetheriteFinderMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        NetheriteFinderMod.enabled = enabled;
        SECTIONS.clear();
        TOASTS.clear();
        PROBED.clear();
        REVEALED.clear();
        pending = 0;
        if (enabled && client.level != null && client.player != null) {
            // Schon geladene Chunks gleich mitpruefen
            int radius = client.options.getEffectiveRenderDistance();
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
            pending = 0;
        }
    }

    private static boolean isNether(ClientLevel level) {
        return level.dimension() == Level.NETHER || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER);
    }

    /** Frisch vom Server geladener Chunk (LevelChunkMixin). */
    public static void onChunkLoaded(LevelChunk chunk) {
        if (!enabled || !(chunk.getLevel() instanceof ClientLevel level) || !isNether(level)) {
            return;
        }
        syncLevel(level);
        LevelChunkSection[] sections = chunk.getSections();
        int chunkX = chunk.getPos().getMinBlockX() >> 4;
        int chunkZ = chunk.getPos().getMinBlockZ() >> 4;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            long key = SectionPos.asLong(chunkX, chunk.getSectionYFromSectionIndex(i), chunkZ);
            if (hidesDebris(section)) {
                add(key);
            } else {
                SECTIONS.remove(key);
            }
        }
    }

    /**
     * Debris in der Palette, aber kein Block ist Debris? Eine globale Palette (sehr viele Blockarten) sagt bei jeder
     * Frage "vielleicht" - die zaehlt nicht.
     */
    private static boolean hidesDebris(LevelChunkSection section) {
        if (section == null || section.hasOnlyAir() || !section.maybeHas(NetheriteFinderMod::isDebris)
                || section.maybeHas(state -> false)) {
            return false;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (isDebris(section.getBlockState(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isDebris(BlockState state) {
        return state.is(Blocks.ANCIENT_DEBRIS);
    }

    private static void add(long key) {
        if (!SECTIONS.add(key)) {
            return;
        }
        if (SECTIONS.size() > MAX_SECTIONS) {
            SECTIONS.remove(SECTIONS.iterator().next());
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            double distance = center(key).distanceToSqr(client.player.position());
            if (pending == 0 || distance < center(nearestPending).distanceToSqr(client.player.position())) {
                nearestPending = key;
            }
            pending++;
        }
    }

    /** Block vom Server geaendert (SusChunkFinderPacketMixin): aufgedeckter Debris loest die Markierung auf. */
    public static void onBlockChanged(BlockPos pos, BlockState state) {
        if (!enabled || !isDebris(state)) {
            return;
        }
        boolean hidden = SECTIONS.remove(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        if ((hidden || advanced) && REVEALED.add(pos.asLong())) {
            if (REVEALED.size() > MAX_REVEALED) {
                REVEALED.remove(REVEALED.iterator().next());
            }
            if (notify) {
                TOASTS.push("debris:" + pos.asLong(), "Ancient debris", "Found", pos.immutable(), null,
                        List.of(new ItemStack(Items.ANCIENT_DEBRIS)));
                NovaNotify.sound(SoundEvents.NOTE_BLOCK_BELL, 1.6F);
            }
        }
    }

    private static void syncLevel(ClientLevel level) {
        if (level != lastLevel) {
            lastLevel = level;
            SECTIONS.clear();
            PROBED.clear();
            REVEALED.clear();
            pending = 0;
        }
    }

    private static Vec3 center(long key) {
        return new Vec3(SectionPos.x(key) * 16 + 8, SectionPos.y(key) * 16 + 8, SectionPos.z(key) * 16 + 8);
    }

    @Override
    public void execute(Minecraft client) {
        if (client.level != null) {
            syncLevel(client.level);
        }
        if (advanced) {
            probe(client);
        }
        long now = System.currentTimeMillis();
        if (pending > 0 && now - lastNotify >= NOTIFY_EVERY_MS) {
            lastNotify = now;
            if (notify) {
                Vec3 at = center(nearestPending);
                TOASTS.push(null, pending == 1 ? "Hidden debris" : pending + " hidden debris", "Netherite",
                        BlockPos.containing(at), null, List.of(new ItemStack(Items.ANCIENT_DEBRIS)));
                NovaNotify.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.4F);
            }
            pending = 0;
        }
    }

    /** Advanced: Bloecke in Reichweite kurz antippen, damit Anti-Xray die echten Bloecke drumherum schickt. */
    private static void probe(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (client.player == null || client.level == null || connection == null || !isNether(client.level)) {
            advancedStatus = "";
            return;
        }
        if (client.player.isCreative() || client.player.isSpectator()) {
            advancedStatus = "Probing only works in survival";
            return;
        }
        if (client.player.getMainHandItem().has(DataComponents.TOOL)) {
            advancedStatus = "Hold a non-tool item to probe";
            return;
        }
        Vec3 eye = client.player.getEyePosition();
        BlockPos center = BlockPos.containing(eye);
        int reach = (int) Math.ceil(PROBE_REACH);
        int sent = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -reach; dy <= reach && sent < probesPerTick; dy++) {
            for (int dx = -reach; dx <= reach && sent < probesPerTick; dx++) {
                for (int dz = -reach; dz <= reach && sent < probesPerTick; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    // Raster mit 3 Bloecken Abstand: Anti-Xray deckt je Antippen etwa 2 Bloecke im Umkreis auf
                    if (Math.floorMod(pos.getX(), 3) != 0 || Math.floorMod(pos.getY(), 3) != 0
                            || Math.floorMod(pos.getZ(), 3) != 0 || Vec3.atCenterOf(pos).distanceToSqr(eye) > PROBE_REACH * PROBE_REACH
                            || !SECTIONS.contains(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4))
                            || client.level.getBlockState(pos).isAir() || PROBED.contains(pos.asLong())) {
                        continue;
                    }
                    BlockPos target = pos.immutable();
                    connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                            target, Direction.UP, 0));
                    connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                            target, Direction.UP, 0));
                    if (PROBED.size() >= MAX_PROBED) {
                        PROBED.clear();
                    }
                    PROBED.add(target.asLong());
                    sent++;
                }
            }
        }
        advancedStatus = sent > 0 ? "Probing ..." : "Walk into a marked section";
    }

    public static void renderToasts(GuiGraphicsExtractor g) {
        if (enabled) {
            TOASTS.render(g, 6);
        }
    }

    /** Jeden Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null || client.player == null || !isNether(client.level)) {
            return;
        }
        Vec3 eye = client.player.getEyePosition();
        // Aufgedeckter Debris: einzelner Block, hell und durch Waende
        for (long key : REVEALED) {
            BlockPos pos = BlockPos.of(key);
            if (client.level.getBlockState(pos).is(Blocks.ANCIENT_DEBRIS)) {
                Gizmos.cuboid(new AABB(pos).inflate(0.02D), GizmoStyle.strokeAndFill(0xFF000000 | DEBRIS, 2.5F,
                        0x55000000 | DEBRIS)).setAlwaysOnTop();
            }
        }
        List<long[]> shown = new ArrayList<>();
        for (long key : SECTIONS) {
            double distance = Math.sqrt(center(key).distanceToSqr(eye));
            if (distance <= range) {
                shown.add(new long[]{key, Double.doubleToLongBits(distance)});
            }
        }
        shown.sort(Comparator.comparingDouble(entry -> Double.longBitsToDouble(entry[1])));
        net.minecraft.client.renderer.culling.Frustum view = ViewCull.frustum();
        Vec3 start = NovaTracers.start();
        for (int i = 0; i < shown.size() && i < MAX_DRAWN; i++) {
            long key = shown.get(i)[0];
            double distance = Double.longBitsToDouble(shown.get(i)[1]);
            float far = (float) Math.min(1.0D, distance / Math.max(1, fadeDistance));
            int rgb = NovaGui.mix(0xFF000000 | NEAR, 0xFF000000 | FAR, far) & 0xFFFFFF;
            double x0 = SectionPos.x(key) * 16;
            double y0 = SectionPos.y(key) * 16;
            double z0 = SectionPos.z(key) * 16;
            AABB box = new AABB(x0, y0, z0, x0 + 16, y0 + 16, z0 + 16);
            if (ViewCull.visible(view, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 0.0F)) {
                Gizmos.cuboid(box, GizmoStyle.strokeAndFill(0xE6000000 | rgb, 2.0F, 0x22000000 | rgb)).setAlwaysOnTop();
            }
            if (tracers && i < tracerCount) {
                NovaTracers.line(start, center(key), rgb, fadeDistance);
            }
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Boxes Nether sections that hide ancient debris", List.of(
                new ModOptionsMenu.Slider("Range", 64.0D, 1024.0D, 32.0D, () -> range, v -> range = (int) Math.round(v),
                        v -> Math.round(v) + " blocks"),
                new ModOptionsMenu.Slider("Color fade", 32.0D, 512.0D, 16.0D, () -> fadeDistance,
                        v -> fadeDistance = (int) Math.round(v), v -> "orange → blue at " + Math.round(v) + " m"),
                new ModOptionsMenu.Toggle("Tracers", () -> tracers, v -> tracers = v),
                new ModOptionsMenu.Slider("Tracers to nearest", 1.0D, 8.0D, 1.0D, () -> tracerCount,
                        v -> tracerCount = (int) Math.round(v), v -> String.valueOf(Math.round(v))),
                new ModOptionsMenu.Toggle("Notifications", () -> notify, v -> notify = v),
                new ModOptionsMenu.Toggle("Advanced: find the exact block", () -> advanced, v -> advanced = v),
                new ModOptionsMenu.Slider("Probes per tick", 1.0D, 12.0D, 1.0D, () -> probesPerTick,
                        v -> probesPerTick = (int) Math.round(v), v -> String.valueOf(Math.round(v))),
                new ModOptionsMenu.Info(() -> advanced && !advancedStatus.isEmpty() ? advancedStatus
                        : REVEALED.size() + " debris revealed"),
                new ModOptionsMenu.Info(() -> SECTIONS.size() + " hidden-debris sections remembered")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "range", String.valueOf(range));
        properties.setProperty(prefix + "fadeDistance", String.valueOf(fadeDistance));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "tracerCount", String.valueOf(tracerCount));
        properties.setProperty(prefix + "notify", String.valueOf(notify));
        properties.setProperty(prefix + "advanced", String.valueOf(advanced));
        properties.setProperty(prefix + "probesPerTick", String.valueOf(probesPerTick));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            range = Math.max(64, Math.min(1024, Integer.parseInt(properties.getProperty(prefix + "range", String.valueOf(range)).trim())));
            fadeDistance = Math.max(32, Math.min(512, Integer.parseInt(properties.getProperty(prefix + "fadeDistance", String.valueOf(fadeDistance)).trim())));
            tracerCount = Math.max(1, Math.min(8, Integer.parseInt(properties.getProperty(prefix + "tracerCount", String.valueOf(tracerCount)).trim())));
            probesPerTick = Math.max(1, Math.min(12, Integer.parseInt(properties.getProperty(prefix + "probesPerTick", String.valueOf(probesPerTick)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        tracers = Boolean.parseBoolean(properties.getProperty(prefix + "tracers", String.valueOf(tracers)).trim());
        notify = Boolean.parseBoolean(properties.getProperty(prefix + "notify", String.valueOf(notify)).trim());
        advanced = Boolean.parseBoolean(properties.getProperty(prefix + "advanced", String.valueOf(advanced)).trim());
    }
}
