package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import nova.nova_client.client.AntiXray;
import nova.nova_client.client.BlockNotifierMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaToasts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * BlockNotifier: durchsucht die geladenen Chunks nach ausgewaehlten Bloecken (wie BlockESP) und zeigt fuer
 * jeden neu entdeckten Block rechts oben einen Toast (Icon, Name, Anzahl, Entfernung). Mehrere Funde derselben
 * Art kurz hintereinander werden in einem Toast zusammengefasst. Jeder Block meldet sich nur einmal pro Welt.
 */
public class BlockNotifierMod extends GameTickMod {
    private static final int MAX_CHUNK_RADIUS = 8;
    private static final int CHUNKS_PER_TICK = 6;
    /** Kurze Pause zwischen zwei kompletten Runden (Ticks) statt Dauerscan. */
    private static final int ROUND_PAUSE_TICKS = 40;
    private static int restTicks;
    private static final int MAX_TOASTS = 5;
    /** Ein neuer Fund derselben Art innerhalb dieser Zeit landet im vorhandenen Toast. */
    private static final long MERGE_MS = 2500L;
    private static final int MAX_REMEMBERED = 50_000;

    private static final Set<Block> SELECTED = Collections.newSetFromMap(new IdentityHashMap<>());

    // Einstellungen
    /** Wie lange ein Toast sichtbar bleibt (Sekunden). */
    public static int duration = 5;
    public static boolean sound = true;

    private static boolean enabled;
    private static final Set<Long> NOTIFIED = new HashSet<>();
    private static final ArrayDeque<long[]> QUEUE = new ArrayDeque<>();
    private static ClientLevel lastLevel;
    private static final List<Toast> TOASTS = new ArrayList<>();

    private static final class Toast {
        final Block block;
        final ItemStack icon;
        final String name;
        int count;
        BlockPos nearest;
        final long created;
        long updated;

        Toast(Block block, BlockPos pos, long now) {
            this.block = block;
            this.icon = block.asItem() == Items.AIR ? ItemStack.EMPTY : new ItemStack(block.asItem());
            this.name = block.getName().getString();
            this.nearest = pos;
            this.created = now;
            this.updated = now;
        }
    }

    static {
        SELECTED.add(Blocks.DIAMOND_ORE);
        SELECTED.add(Blocks.DEEPSLATE_DIAMOND_ORE);
        SELECTED.add(Blocks.ANCIENT_DEBRIS);
        SELECTED.add(Blocks.SPAWNER);
    }

    public BlockNotifierMod(String name, ModCategory category) {
        super(name, category);
    }

    // ---------------------------------------------------------------- Auswahl

    public static boolean isSelected(Block block) {
        return SELECTED.contains(block);
    }

    public static void setSelected(Block block, boolean selected) {
        if (selected) {
            SELECTED.add(block);
        } else {
            SELECTED.remove(block);
        }
        // Neu ausgewaehlte Bloecke sollen sich auch melden, wenn sie schon mal gesehen wurden
        NOTIFIED.clear();
        QUEUE.clear();
        restTicks = 0;
    }

    public static int selectedCount() {
        return SELECTED.size();
    }

    public static void clearSelection() {
        SELECTED.clear();
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        BlockNotifierMod.enabled = enabled;
        NOTIFIED.clear();
        QUEUE.clear();
        restTicks = 0;
        TOASTS.clear();
    }

    // ---------------------------------------------------------------- Suche

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        if (level != lastLevel) {
            // Neue Welt / Dimension: alles darf sich wieder melden
            lastLevel = level;
            NOTIFIED.clear();
            QUEUE.clear();
            restTicks = 0;
        }
        if (SELECTED.isEmpty()) {
            return;
        }

        int radius = Math.min(MAX_CHUNK_RADIUS, client.options.getEffectiveRenderDistance());
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;
        if (QUEUE.isEmpty() && restTicks > 0) {
            restTicks--;
        } else if (QUEUE.isEmpty()) {
            restTicks = ROUND_PAUSE_TICKS;
            List<long[]> chunks = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new long[]{centerX + dx, centerZ + dz, (long) dx * dx + (long) dz * dz});
                }
            }
            chunks.sort((a, b) -> Long.compare(a[2], b[2]));
            QUEUE.addAll(chunks);
        }

        Map<Block, List<BlockPos>> found = new IdentityHashMap<>();
        long deadline = nova.nova_client.client.TickBudget.deadline();
        for (int i = 0; i < CHUNKS_PER_TICK && !QUEUE.isEmpty() && nova.nova_client.client.TickBudget.more(i, deadline); i++) {
            long[] next = QUEUE.poll();
            scanChunk(level, (int) next[0], (int) next[1], found);
        }
        if (!found.isEmpty()) {
            notify(client, found);
        }
        if (NOTIFIED.size() > MAX_REMEMBERED) {
            NOTIFIED.clear();
        }
    }

    private static void scanChunk(ClientLevel level, int chunkX, int chunkZ, Map<Block, List<BlockPos>> found) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return;
        }
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        LevelChunkSection[] sections = chunk.getSections();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()
                    || !section.getStates().maybeHas(state -> SELECTED.contains(state.getBlock()))) {
                continue;
            }
            int baseY = level.getSectionYFromSectionIndex(index) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!SELECTED.contains(state.getBlock())) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        // Anti-Xray: komplett eingeschlossene Bloecke koennen Fakes sein
                        if (!NOTIFIED.contains(pos.asLong()) && AntiXray.isReal(pos, level::getBlockState)) {
                            NOTIFIED.add(pos.asLong());
                            found.computeIfAbsent(state.getBlock(), b -> new ArrayList<>()).add(pos);
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Toasts

    private static void notify(Minecraft client, Map<Block, List<BlockPos>> found) {
        long now = Util.getMillis();
        BlockPos eye = client.player.blockPosition();
        for (Map.Entry<Block, List<BlockPos>> entry : found.entrySet()) {
            BlockPos nearest = null;
            for (BlockPos pos : entry.getValue()) {
                if (nearest == null || pos.distSqr(eye) < nearest.distSqr(eye)) {
                    nearest = pos;
                }
            }
            Toast toast = null;
            for (Toast t : TOASTS) {
                if (t.block == entry.getKey() && now - t.updated < MERGE_MS) {
                    toast = t;
                    break;
                }
            }
            if (toast == null) {
                toast = new Toast(entry.getKey(), nearest, now);
                TOASTS.add(0, toast);
                if (sound) {
                    client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.6F));
                }
            } else if (nearest.distSqr(eye) < toast.nearest.distSqr(eye)) {
                toast.nearest = nearest;
            }
            toast.count += entry.getValue().size();
            toast.updated = now;
        }
        while (TOASTS.size() > MAX_TOASTS) {
            TOASTS.remove(TOASTS.size() - 1);
        }
    }

    /** Zeichnet die Toasts rechts oben; wird jeden Frame vom HUD aufgerufen. */
    public static void renderToasts(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || TOASTS.isEmpty() || client.player == null) {
            return;
        }
        Font font = client.font;
        long now = Util.getMillis();
        long life = duration * 1000L;
        TOASTS.removeIf(t -> now - t.updated > life);

        int y = NovaToasts.top();
        for (Toast toast : TOASTS) {
            // Fund-Karte wie bei Advanced Stash Finder: Blockname, Anzahl als Badge, Ort und Block-Icon
            float in = NovaGui.easeOutCubic((now - toast.created) / 280.0F);
            float out = Mth.clamp((life - (now - toast.updated)) / 280.0F, 0.0F, 1.0F);
            float show = in * out;
            int distance = (int) Math.round(Math.sqrt(toast.nearest.distSqr(client.player.blockPosition())));
            String where = distance + "m  ·  " + toast.nearest.getX() + " " + toast.nearest.getY() + " " + toast.nearest.getZ();
            NovaToasts.card(g, font, NovaToasts.slideX(NovaToasts.CARD_W, show), y, show, toast.name,
                    toast.count + " found", where, toast.icon.isEmpty() ? List.of() : List.of(toast.icon));
            y += NovaToasts.CARD_H + NovaToasts.GAP;
        }
        NovaToasts.setTop(y);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new BlockNotifierMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        List<String> ids = new ArrayList<>();
        for (Block block : SELECTED) {
            ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        Collections.sort(ids);
        properties.setProperty(prefix + "blocks", String.join(",", ids));
        properties.setProperty(prefix + "duration", String.valueOf(duration));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String blocks = properties.getProperty(prefix + "blocks");
        if (blocks != null) {
            SELECTED.clear();
            for (String id : blocks.split(",")) {
                Identifier key = Identifier.tryParse(id.trim());
                if (key != null && BuiltInRegistries.BLOCK.containsKey(key)) {
                    SELECTED.add(BuiltInRegistries.BLOCK.getValue(key));
                }
            }
        }
        try {
            duration = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "duration", String.valueOf(duration)).trim()), 1, 15);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
    }
}
