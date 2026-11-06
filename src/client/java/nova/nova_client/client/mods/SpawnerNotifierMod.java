package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * SpawnerNotifier: sobald in den geladenen Chunks ein Spawner auftaucht, poppt rechts oben ein Toast mit Mob,
 * Entfernung und Koordinaten auf (gleicher Stil wie Advanced Stash Finder). Spawner sind Block-Entities, daher reicht
 * ein Blick in die Block-Entity-Liste der Chunks (kein Block-fuer-Block-Scan). Jeder Spawner meldet sich nur einmal
 * pro Welt.
 */
public class SpawnerNotifierMod extends GameTickMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/SpawnerNotifier");
    public static final int MAX_RANGE = 8;
    private static final int SCAN_TICKS = 5;
    private static final int MAX_TOASTS = 4;
    private static final String TAG = "SpawnerNotifier";

    // Einstellungen
    /** Suchradius in Chunks. */
    public static int range = 8;
    /** Auch Trial Spawner (Trial Chambers) melden. */
    public static boolean trialSpawners = false;
    public static boolean sound = true;
    /** Zusaetzlich eine Zeile im Chat (bleibt nachlesbar, wenn der Toast weg ist). */
    public static boolean chat = false;
    /** Wie lange ein Toast sichtbar bleibt (Sekunden). */
    public static int duration = 8;

    private static volatile boolean enabled;
    /** Schon gemeldete Spawner dieser Welt. */
    private static final Set<Long> REPORTED = new HashSet<>();
    private static final List<Toast> TOASTS = new ArrayList<>();
    private static ClientLevel lastLevel;
    private static int scanTimer;

    private record Toast(BlockPos pos, String mob, boolean trial, long created) {
    }

    public SpawnerNotifierMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        SpawnerNotifierMod.enabled = enabled;
        REPORTED.clear();
        scanTimer = 0;
        if (!enabled) {
            TOASTS.clear();
        }
    }

    // ---------------------------------------------------------------- Suchen (im Tick)

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            return;
        }
        if (level != lastLevel) {
            lastLevel = level;
            REPORTED.clear();
            TOASTS.clear();
        }
        if (--scanTimer > 0) {
            return;
        }
        scanTimer = SCAN_TICKS;

        List<BlockEntity> found = findSpawners(client, level);
        if (found.isEmpty()) {
            return;
        }
        // Weiteste zuerst melden: der naechste Spawner landet oben im Stapel
        BlockPos player = client.player.blockPosition();
        found.sort(Comparator.comparingDouble((BlockEntity e) -> e.getBlockPos().distSqr(player)).reversed());
        for (BlockEntity entity : found) {
            announce(client, level, entity);
        }
        while (TOASTS.size() > MAX_TOASTS) {
            TOASTS.remove(TOASTS.size() - 1);
        }
        if (sound) {
            NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 1.2F);
        }
    }

    /** Alle noch nicht gemeldeten Spawner im Suchradius. */
    private static List<BlockEntity> findSpawners(Minecraft client, ClientLevel level) {
        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;
        int radius = Math.min(range, client.options.getEffectiveRenderDistance());
        List<BlockEntity> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    boolean spawner = entity instanceof SpawnerBlockEntity
                            || trialSpawners && entity instanceof TrialSpawnerBlockEntity;
                    if (spawner && !REPORTED.contains(entity.getBlockPos().asLong())) {
                        result.add(entity);
                    }
                }
            }
        }
        return result;
    }

    private static void announce(Minecraft client, ClientLevel level, BlockEntity entity) {
        BlockPos pos = entity.getBlockPos().immutable();
        REPORTED.add(pos.asLong());
        boolean trial = entity instanceof TrialSpawnerBlockEntity;
        String mob = mobName(level, entity);
        int distance = (int) Math.round(Math.sqrt(pos.distSqr(client.player.blockPosition())));
        String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        LOGGER.info("{} found at {} ({}m away)", mob != null ? mob + " spawner" : "Spawner", coords, distance);
        TOASTS.add(0, new Toast(pos, mob, trial, Util.getMillis()));
        if (chat) {
            String what = (mob != null ? mob + " " : "") + (trial ? "trial spawner" : "spawner");
            NovaNotify.chat(TAG, "§f" + Character.toUpperCase(what.charAt(0)) + what.substring(1) + "§7 at §f" + coords
                    + " §7(" + distance + "m)");
        }
    }

    /** Mob im Spawner (z.B. "Zombie"), oder null wenn unbekannt. */
    private static String mobName(ClientLevel level, BlockEntity entity) {
        if (entity instanceof SpawnerBlockEntity spawner) {
            try {
                Entity display = spawner.getSpawner().getOrCreateDisplayEntity(level, spawner.getBlockPos());
                if (display != null) {
                    return display.getType().getDescription().getString();
                }
            } catch (RuntimeException e) {
                // unlesbare Spawner-Daten: ohne Mob-Namen melden
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Toasts

    /** Toasts rechts oben (jeden Frame vom HUD), im Stil von Advanced Stash Finder. */
    public static void renderToasts(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || TOASTS.isEmpty() || client.player == null) {
            return;
        }
        Font font = client.font;
        long now = Util.getMillis();
        long life = duration * 1000L;
        TOASTS.removeIf(t -> now - t.created() > life);

        int y = NovaToasts.top();
        for (Toast toast : TOASTS) {
            float in = NovaGui.easeOutCubic((now - toast.created()) / 280.0F);
            float out = Mth.clamp((life - (now - toast.created())) / 280.0F, 0.0F, 1.0F);
            float show = in * out;
            int distance = (int) Math.round(Math.sqrt(toast.pos().distSqr(client.player.blockPosition())));
            String where = distance + "m  ·  " + toast.pos().getX() + " " + toast.pos().getY() + " " + toast.pos().getZ();
            String badge = toast.mob() != null ? toast.mob() : toast.trial() ? "trial" : "spawner";
            NovaToasts.card(g, font, NovaToasts.slideX(NovaToasts.CARD_W, show), y, show,
                    toast.trial() ? "Trial spawner found" : "Spawner found", badge, where,
                    List.of(new ItemStack(toast.trial() ? Items.TRIAL_SPAWNER : Items.SPAWNER)));
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
        return new ModOptionsMenu(parent, "SpawnerNotifier", "Toast with coordinates when a spawner shows up", List.of(
                new ModOptionsMenu.Slider("Range", 1.0D, MAX_RANGE, 1.0D, () -> range,
                        v -> range = (int) Math.round(v), v -> Math.round(v) + (Math.round(v) == 1 ? " chunk" : " chunks")),
                new ModOptionsMenu.Toggle("Also trial spawners", () -> trialSpawners, v -> trialSpawners = v),
                new ModOptionsMenu.Toggle("Sound on toast", () -> sound, v -> sound = v),
                new ModOptionsMenu.Toggle("Also in chat", () -> chat, v -> chat = v),
                new ModOptionsMenu.Slider("Toast duration", 2.0D, 20.0D, 1.0D, () -> duration,
                        v -> duration = (int) Math.round(v), v -> Math.round(v) + " s"),
                new ModOptionsMenu.Action(() -> "Report all spawners again", REPORTED::clear),
                new ModOptionsMenu.Info(() -> REPORTED.size() + " spawner(s) reported in this world")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "range", String.valueOf(range));
        properties.setProperty(prefix + "trial", String.valueOf(trialSpawners));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
        properties.setProperty(prefix + "chat", String.valueOf(chat));
        properties.setProperty(prefix + "duration", String.valueOf(duration));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        range = number(properties, prefix + "range", range, 1, MAX_RANGE);
        duration = number(properties, prefix + "duration", duration, 2, 20);
        trialSpawners = Boolean.parseBoolean(properties.getProperty(prefix + "trial", String.valueOf(trialSpawners)));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
        chat = Boolean.parseBoolean(properties.getProperty(prefix + "chat", String.valueOf(chat)));
    }

    private static int number(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Mth.clamp(Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
