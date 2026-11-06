package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModConfig;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.Mods;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.spawnerprotect.SpawnerBot;
import nova.nova_client.client.spawnerprotect.SpawnerWebhook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Spawner Protect: bewacht ausgewaehlte Spawner. Kommt ein anderer Spieler in die Naehe oder werden in der Umgebung
 * mehrere Bloecke abgebaut (jemand graebt sich heran), baut der Mod alle ausgewaehlten Spawner ab, sammelt sie ein und
 * legt sie in die Ender-Kiste (siehe {@link SpawnerBot}). Fehlt die Ender-Kiste, setzt der Bot eine aus dem Inventar
 * oder kauft eine per /shop. Optional meldet ein Discord-Webhook Ankoemmlinge, den Start und das Ergebnis.
 * Ausgewaehlt wird wie beim Schematic Builder: im Menue "Select" druecken, dann im Spiel die Spawner und eine
 * Ender-Kiste mit Rechtsklick anklicken.
 */
public class SpawnerProtectMod extends GameTickMod {
    public static final SpawnerBot BOT = new SpawnerBot();
    public static final String[] TRIGGERS = {"Players & block breaks", "Players", "Block breaks"};

    // Einstellungen (gespeichert)
    public static final Set<BlockPos> SPAWNERS = new LinkedHashSet<>();
    public static BlockPos enderChest;
    public static int range = 32;
    /** NPCs (Spieler-Entities, die nicht in der Tab-Liste stehen, z.B. Citizens) ignorieren. */
    public static boolean ignoreNpcs = true;
    public static boolean disconnectWhenDone = false;
    /** Spieler, die nichts ausloesen (Freunde), durch Komma getrennt. */
    public static String ignoredPlayers = "";
    /** Test: diese Taste loest alles aus, als waere ein Spieler gekommen (NO_KEY = keine). */
    public static int testKey = NO_KEY;
    /** 0 = Spieler und Abbau, 1 = nur Spieler, 2 = nur Abbau (Spieler werden dann nur gemeldet). */
    public static int trigger = 0;
    /** So viele abgebaute Bloecke in der Umgebung ... */
    public static int breakThreshold = 5;
    /** ... innerhalb so vieler Sekunden loesen aus. */
    public static int breakWindow = 60;
    /** Fehlt die Ender-Kiste: eine aus dem Inventar setzen ... */
    public static boolean autoEnderChest = true;
    /** ... oder notfalls per /shop kaufen (DonutSMP). */
    public static boolean buyEnderChest = true;
    public static boolean toasts = true;
    public static String webhookUrl = "";

    // Laufzeit
    public static boolean selecting;
    private static SpawnerProtectMod instance;
    /** Nach einem Durchlauf erst wieder scharf, wenn niemand mehr in der Naehe ist (sonst Endlosschleife). */
    private static boolean armed = true;
    private static boolean testKeyDown;
    /** Test-Taste wurde gedrueckt: beim naechsten Tick starten. */
    private static boolean testRequested;
    /** Abbau in der Umgebung: Zaehler und Zeit des letzten Abbaus. */
    private static int breakCount;
    private static long lastBreak;
    /** Ausgeloest durch Abbau (wird im naechsten Tick gestartet). */
    private static String breakReason;
    /** Spieler, die gerade in Reichweite sind (fuer "neu angekommen"). */
    private static final Set<UUID> NEARBY = new HashSet<>();
    private static final NovaToasts.Feed TOASTS = new NovaToasts.Feed(3);

    public SpawnerProtectMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    // ================================================================ An / Aus

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (enabled) {
            String problem = setupProblem();
            if (problem != null) {
                NovaNotify.chat(name, "§c" + problem);
                active = false;
                return;
            }
            selecting = false;
            armed = true;
            breakCount = 0;
            breakReason = null;
            NEARBY.clear();
            String what = switch (trigger) {
                case 1 -> "a player within §f" + range + "m§7 starts it.";
                case 2 -> "§f" + breakThreshold + "§7 blocks broken within §f" + range + "m§7 start it.";
                default -> "a player within §f" + range + "m§7 or §f" + breakThreshold + "§7 broken blocks start it.";
            };
            NovaNotify.chat(name, "§7Watching §f" + SPAWNERS.size() + "§7 spawners - " + what);
        } else {
            BOT.reset();
        }
    }

    /** Warum kann nicht gestartet werden? null = alles bereit. */
    public static String setupProblem() {
        if (SPAWNERS.isEmpty()) {
            return "Select your spawners first (settings: \"Select spawners & ender chest\").";
        }
        if (enderChest == null && !autoEnderChest) {
            return "Select an ender chest first (or turn on \"Place / buy ender chest if missing\").";
        }
        return null;
    }

    @Override
    public void execute(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        if (BOT.running()) {
            BOT.tick();
        }
        switch (BOT.phase()) {
            case DONE -> {
                finished(client);
                return;
            }
            case FAILED -> {
                failed(client);
                return;
            }
            case IDLE -> {
            }
            default -> {
                return; // laeuft noch
            }
        }
        if (testRequested) {
            testRequested = false;
            if (anySpawnerLeft(client.level)) {
                begin(client, "Test key pressed", "Test key");
            } else {
                NovaNotify.chat(name, "§7Test: none of your selected spawners is there (anymore).");
            }
            return;
        }
        notifyArrivals(client);
        Player intruder = trigger == 2 ? null : intruder(client);
        if (!armed) {
            armed = intruder == null;
            breakReason = null;
            return;
        }
        if (breakReason != null) {
            String reason = breakReason;
            breakReason = null;
            if (anySpawnerLeft(client.level)) {
                begin(client, reason, "Block breaking");
            }
            return;
        }
        if (intruder != null && anySpawnerLeft(client.level)) {
            begin(client, intruder.getPlainTextName() + " is " + Math.round(intruder.distanceTo(client.player)) + "m away",
                    "Player nearby");
        }
    }

    /** Neu in Reichweite gekommene Spieler melden (Webhook, und Toast wenn sie nicht ausloesen). */
    private void notifyArrivals(Minecraft client) {
        Set<UUID> present = new HashSet<>();
        for (Player player : nearbyPlayers(client)) {
            present.add(player.getUUID());
            if (NEARBY.contains(player.getUUID())) {
                continue;
            }
            String who = player.getPlainTextName();
            String distance = String.format(Locale.ROOT, "%.1f blocks", player.distanceTo(client.player));
            SpawnerWebhook.send(webhookUrl, "A player entered your range",
                    trigger == 2 ? "A new player is nearby. This only sends a notification." : "A new player is nearby.",
                    SpawnerWebhook.BLUE,
                    new SpawnerWebhook.Field("Player", "`" + who + "`", true),
                    new SpawnerWebhook.Field("Distance", distance, true),
                    new SpawnerWebhook.Field("Protection", BOT.running() ? "In progress" : "Standby", true),
                    new SpawnerWebhook.Field("Player location", SpawnerWebhook.coordinates(player.blockPosition()), false));
            if (trigger == 2) {
                NovaNotify.chat(name, "§e" + who + "§7 is nearby (" + distance + ")");
                if (toasts) {
                    TOASTS.push("player:" + who, who + " is nearby", "PLAYER", player.blockPosition(), null,
                            List.of(new ItemStack(Items.PLAYER_HEAD)));
                }
            }
        }
        NEARBY.clear();
        NEARBY.addAll(present);
    }

    // ================================================================ Abbau in der Umgebung

    /**
     * Block-Update vom Server, bevor es angewendet wird (alter Zustand steht noch in der Welt). Viele abgebaute Bloecke in
     * Reichweite (nicht bei dir und nicht bei Freunden) = jemand graebt sich heran.
     */
    public static void onBlockUpdate(BlockPos pos, BlockState now) {
        Minecraft client = Minecraft.getInstance();
        if (instance == null || !instance.active || trigger == 1 || !armed || BOT.running() || breakReason != null
                || client.player == null || client.level == null || !now.isAir()) {
            return;
        }
        BlockState was = client.level.getBlockState(pos);
        if (was.isAir() || was.getBlock() instanceof ShulkerBoxBlock || pos.getY() <= client.level.getMinY()) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        if (client.player.getEyePosition().distanceTo(center) < 6.0D) {
            return; // das bist du selbst
        }
        double dx = client.player.getX() - center.x;
        double dz = client.player.getZ() - center.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > range) {
            return;
        }
        Set<String> ignored = ignoredNames();
        for (Player player : client.level.players()) {
            if (player != client.player && ignored.contains(player.getPlainTextName().toLowerCase(Locale.ROOT))
                    && player.getEyePosition().distanceTo(center) < 8.0D) {
                return; // ein Freund baut ab
            }
        }
        long time = System.currentTimeMillis();
        if (time - lastBreak > breakWindow * 1000L) {
            breakCount = 0;
        }
        breakCount++;
        lastBreak = time;
        if (breakCount >= breakThreshold) {
            breakCount = 0;
            breakReason = breakThreshold + " blocks broken nearby (" + Math.round(horizontal) + "m away, Y " + pos.getY() + ")";
        }
    }

    private void begin(Minecraft client, String reason, String cause) {
        // Der Bot braucht die echte Sicht und die Haende frei: Freecam aus, offene Menues zu
        for (Mod mod : Mods.mods) {
            if (mod instanceof FreecamMod && mod.active) {
                mod.toggle(client);
            }
        }
        Screen screen = client.gui.screen();
        if (screen instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        } else if (screen != null) {
            client.gui.setScreen(null);
        }
        selecting = false;
        BOT.start(SPAWNERS, enderChest, autoEnderChest, buyEnderChest);
        NovaNotify.chat(name, "§c" + reason + " - securing " + BOT.total() + " spawners!");
        NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 0.6F);
        if (toasts) {
            TOASTS.push("state", "Securing " + BOT.total() + " spawners", "ALERT", null, reason,
                    List.of(new ItemStack(Items.SPAWNER), new ItemStack(Items.ENDER_CHEST)));
        }
        SpawnerWebhook.send(webhookUrl, "Protection activated", "Spawner collection has started.", SpawnerWebhook.RED,
                new SpawnerWebhook.Field("Trigger", cause, true),
                new SpawnerWebhook.Field("Spawners", String.valueOf(BOT.total()), true),
                new SpawnerWebhook.Field("Reason", reason, false));
    }

    private void finished(Minecraft client) {
        StringBuilder text = new StringBuilder("§aDone: §f" + BOT.stored() + "§a spawners are in your ender chest.");
        if (BOT.skipped() > 0) {
            text.append(" §e").append(BOT.skipped()).append(" could not be reached.");
        }
        if (BOT.leftOver() > 0) {
            text.append(" §cEnder chest full - ").append(BOT.leftOver()).append(" are still in your inventory.");
        }
        NovaNotify.chat(name, text.toString());
        NovaNotify.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F);
        if (toasts) {
            TOASTS.push("state", "Spawners secured", "DONE", null, BOT.stored() + " in your ender chest"
                    + (BOT.leftOver() > 0 ? ", " + BOT.leftOver() + " left over" : ""), List.of(new ItemStack(Items.ENDER_CHEST)));
        }
        boolean leftOver = BOT.leftOver() > 0;
        SpawnerWebhook.send(webhookUrl, leftOver ? "Spawners remain in inventory" : "Task completed",
                leftOver ? "Some spawners did not fit into the ender chest." : "All spawners were put into your ender chest.",
                leftOver ? SpawnerWebhook.YELLOW : SpawnerWebhook.GREEN,
                new SpawnerWebhook.Field("Stored", String.valueOf(BOT.stored()), true),
                new SpawnerWebhook.Field("Left in inventory", String.valueOf(BOT.leftOver()), true),
                new SpawnerWebhook.Field("Not reached", String.valueOf(BOT.skipped()), true),
                new SpawnerWebhook.Field("Next step", disconnectWhenDone ? "Disconnect" : "Stay online", true));
        end(client, "All spawners were put into your ender chest.");
    }

    private void failed(Minecraft client) {
        NovaNotify.chat(name, "§cStopped: " + BOT.failure() + " (" + BOT.mined() + " / " + BOT.total() + " mined)");
        if (toasts) {
            TOASTS.push("state", "Spawner Protect stopped", "FAIL", null, BOT.failure(), List.of(new ItemStack(Items.SPAWNER)));
        }
        SpawnerWebhook.send(webhookUrl, "Protection stopped", "The bot could not finish: " + BOT.failure() + ".",
                SpawnerWebhook.YELLOW,
                new SpawnerWebhook.Field("Mined", BOT.mined() + " / " + BOT.total(), true),
                new SpawnerWebhook.Field("Stored", String.valueOf(BOT.stored()), true));
        end(client, "Stopped: " + BOT.failure());
    }

    private void end(Minecraft client, String reason) {
        BOT.reset();
        armed = false;
        ClientPacketListener connection = client.getConnection();
        if (disconnectWhenDone && connection != null) {
            connection.getConnection().disconnect(Component.literal("Spawner Protect").withStyle(ChatFormatting.RED)
                    .append(Component.literal("\n\n" + reason).withStyle(ChatFormatting.WHITE)));
        }
    }

    /** Naechster fremde Spieler in Reichweite, sonst null. */
    private static Player intruder(Minecraft client) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : nearbyPlayers(client)) {
            double distance = player.distanceTo(client.player);
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /** Fremde Spieler in Reichweite (ohne Zuschauer, Freunde und - wenn eingestellt - NPCs). */
    private static List<Player> nearbyPlayers(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        List<Player> result = new ArrayList<>();
        if (connection == null) {
            return result;
        }
        Set<String> ignored = ignoredNames();
        for (Player player : client.level.players()) {
            if (player == client.player || player.isSpectator()) {
                continue;
            }
            if (ignoreNpcs && connection.getPlayerInfo(player.getUUID()) == null) {
                continue;
            }
            if (ignored.contains(player.getPlainTextName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (player.distanceTo(client.player) <= range) {
                result.add(player);
            }
        }
        return result;
    }

    private static Set<String> ignoredNames() {
        Set<String> names = new HashSet<>();
        for (String part : ignoredPlayers.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                names.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static boolean anySpawnerLeft(ClientLevel level) {
        for (BlockPos pos : SPAWNERS) {
            if (level.isLoaded(pos) && SpawnerBot.isSpawner(level, pos)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ Bot-Steuerung (Mixins)

    /** Arbeitet der Bot gerade? Dann gehoeren Maus, Klicks und Bewegung ihm. */
    public static boolean locksInput() {
        return instance != null && instance.active && BOT.running();
    }

    /** Eingabe fuer KeyboardInputMixin (null = Spieler steuert selbst). */
    public static Input inputOverride() {
        return locksInput() ? BOT.inputOverride() : null;
    }

    /** Pro Frame aus dem HUD: Kamera fluessig bewegen. */
    public static void frame() {
        if (locksInput()) {
            BOT.frame();
        }
    }

    // ================================================================ Auswahl (wie Schematic Builder)

    /**
     * Vor Minecrafts Tastenverarbeitung: im Auswahl-Modus waehlt Rechtsklick den Block im Fadenkreuz aus (Spawner an/ab,
     * Ender-Kiste setzen), statt ihn zu benutzen.
     */
    public static void startTick(Minecraft mc) {
        checkTestKey(mc);
        if (!selecting || mc.player == null || mc.level == null || mc.gui.screen() != null) {
            return;
        }
        KeyMapping use = mc.options.keyUse;
        boolean clicked = false;
        while (use.consumeClick()) {
            clicked = true;
        }
        use.setDown(false); // kein Vanilla-Rechtsklick (Kiste oeffnen, Block setzen ...)
        if (!clicked) {
            return;
        }
        String tag = instance != null ? instance.name : "Spawner Protect";
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            NovaNotify.chat(tag, "§7Look at a spawner or an ender chest.");
            return;
        }
        BlockPos pos = hit.getBlockPos().immutable();
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.SPAWNER)) {
            if (SPAWNERS.remove(pos)) {
                NovaNotify.chat(tag, "§eSpawner removed §7(" + SPAWNERS.size() + " selected)");
            } else {
                SPAWNERS.add(pos);
                NovaNotify.chat(tag, "§aSpawner added §7(" + SPAWNERS.size() + " selected)");
                NovaNotify.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4F);
            }
        } else if (state.getBlock() instanceof EnderChestBlock) {
            enderChest = pos;
            NovaNotify.chat(tag, "§aEnder chest set §7(" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")");
            NovaNotify.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F);
        } else {
            NovaNotify.chat(tag, "§7That is not a spawner or an ender chest.");
        }
        ModConfig.save();
    }

    /** Test-Taste (nur im Spiel, nur beim Druecken): startet den Ablauf wie bei einem erkannten Spieler. */
    private static void checkTestKey(Minecraft mc) {
        boolean down = testKey != NO_KEY && mc.player != null && InputConstants.isKeyDown(testKey);
        boolean pressed = down && !testKeyDown;
        testKeyDown = down;
        if (!pressed || mc.gui.screen() != null || instance == null) {
            return;
        }
        if (!instance.active) {
            NovaNotify.chat(instance.name, "§7Test: turn Spawner Protect on first.");
        } else if (!BOT.running()) {
            testRequested = true;
        }
    }

    // ================================================================ Markierungen + HUD

    public static boolean wantsGizmos() {
        return selecting || locksInput();
    }

    /** Pro Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        if (!wantsGizmos()) {
            return;
        }
        for (BlockPos pos : SPAWNERS) {
            Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(0xFFFF5470, 2.0F, 0x30FF5470)).setAlwaysOnTop();
        }
        if (enderChest != null) {
            Gizmos.cuboid(enderChest, GizmoStyle.strokeAndFill(0xFFB45CF6, 2.0F, 0x30B45CF6)).setAlwaysOnTop();
        }
        BlockPos target = BOT.currentTarget();
        if (locksInput() && target != null) {
            Gizmos.cuboid(target, GizmoStyle.strokeAndFill(0xFFFFFFFF, 2.5F, 0x40FFFFFF)).setAlwaysOnTop();
        }
    }

    /** Toasts rechts oben (wie Advanced Stash Finder). */
    public static void renderToasts(GuiGraphicsExtractor g) {
        TOASTS.render(g, 8);
    }

    public static void renderHud(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        boolean running = locksInput();
        if (mc.player == null || mc.getDebugOverlay().showDebugScreen() || !selecting && !running) {
            return;
        }
        Font font = mc.font;
        int accent = NovaGui.accent(ModCategory.DONUT);
        List<String> lines = new ArrayList<>();
        if (running) {
            lines.add(BOT.status());
            String key = instance != null && instance.hasKey() ? instance.keyName() : "";
            lines.add(key.isEmpty() ? "§7Turn the module off to stop" : "§7Press §f" + key + "§7 to stop");
        } else {
            lines.add("Right click spawners to add / remove");
            lines.add("Right click an ender chest to use it");
            lines.add(SPAWNERS.size() + " spawners  ·  ender chest: " + (enderChest == null ? "§cnone" : "§aset"));
            lines.add("§7Open the menu again to finish");
        }
        int width = 150;
        for (String line : lines) {
            width = Math.max(width, font.width(line) + 12);
        }
        int x0 = 6;
        int y0 = mc.getWindow().getGuiScaledHeight() / 2 - 30;
        int height = 18 + lines.size() * 11;
        NovaGui.hudBackground(g, x0, y0, x0 + width, y0 + height);
        g.fill(x0, y0, x0 + 2, y0 + height, accent);
        NovaGui.text(g, font, running ? "Spawner Protect" : "Selecting spawners", x0 + 7, y0 + 5, accent);
        int y = y0 + 17;
        for (String line : lines) {
            NovaGui.text(g, font, line, x0 + 7, y, NovaGui.TEXT_DIM);
            y += 11;
        }
    }

    // ================================================================ Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Action(() -> selecting ? "Done selecting" : "Select spawners & ender chest", () -> {
                    selecting = !selecting;
                    if (selecting) {
                        NovaNotify.chat(name, "§7Right click your spawners (again = remove) and one ender chest."
                                + " Open this menu again when you are done.");
                        Minecraft.getInstance().gui.setScreen(null);
                    }
                }),
                new ModOptionsMenu.Info(() -> SPAWNERS.size() + " spawners selected  ·  ender chest: "
                        + (enderChest == null ? "none" : enderChest.getX() + " " + enderChest.getY() + " " + enderChest.getZ())),
                new ModOptionsMenu.Action(() -> "Clear selection", () -> {
                    SPAWNERS.clear();
                    enderChest = null;
                }),
                new ModOptionsMenu.Slider("Range", 8.0D, 128.0D, 4.0D, () -> range,
                        v -> range = (int) Math.round(v), v -> Math.round(v) + " m"),
                new ModOptionsMenu.Toggle("Ignore NPCs (not in tab list)", () -> ignoreNpcs, v -> ignoreNpcs = v),
                new ModOptionsMenu.Text("Ignore players (a, b, ...)", () -> ignoredPlayers, v -> ignoredPlayers = v, 256),
                new ModOptionsMenu.Toggle("Place / buy ender chest if missing", () -> autoEnderChest, v -> autoEnderChest = v),
                new ModOptionsMenu.Toggle("Disconnect when done", () -> disconnectWhenDone, v -> disconnectWhenDone = v),
                new ModOptionsMenu.KeyBind("Test key (acts like a player came)", () -> testKey, v -> testKey = v),
                new ModOptionsMenu.Action(() -> "Triggers, alerts & webhook ...",
                        () -> Minecraft.getInstance().gui.setScreen(alertsMenu(Minecraft.getInstance().gui.screen()))),
                new ModOptionsMenu.Info(SpawnerProtectMod::statusLine)));
    }

    private Screen alertsMenu(Screen parent) {
        return new ModOptionsMenu(parent, name + " · Alerts", "", List.of(
                new ModOptionsMenu.Choice("Starts on", TRIGGERS, () -> trigger, v -> trigger = v),
                new ModOptionsMenu.Info(() -> trigger == 2 ? "Players nearby are only reported (chat, toast, webhook)"
                        : "Block breaks: someone digging within the range"),
                new ModOptionsMenu.Slider("Blocks broken", 1.0D, 20.0D, 1.0D, () -> breakThreshold,
                        v -> breakThreshold = (int) Math.round(v), v -> Math.round(v) + " blocks"),
                new ModOptionsMenu.Slider("... within", 5.0D, 600.0D, 5.0D, () -> breakWindow,
                        v -> breakWindow = (int) Math.round(v), v -> Math.round(v) + " s"),
                new ModOptionsMenu.Toggle("Buy ender chest via /shop (DonutSMP)", () -> buyEnderChest, v -> buyEnderChest = v),
                new ModOptionsMenu.Toggle("Toasts", () -> toasts, v -> toasts = v),
                new ModOptionsMenu.Text("Discord webhook URL", () -> webhookUrl, v -> webhookUrl = v.trim(), 512),
                new ModOptionsMenu.Action(() -> webhookUrl.isBlank() ? "Test webhook (no URL set)" : "Test webhook", () -> {
                    if (!webhookUrl.isBlank()) {
                        SpawnerWebhook.send(webhookUrl, "Webhook connected",
                                "Spawner Protect will post here when players come or the protection starts.", SpawnerWebhook.BLUE);
                    }
                })));
    }

    private static String statusLine() {
        if (instance == null || !instance.active) {
            return "Module is off";
        }
        if (BOT.running()) {
            return BOT.status();
        }
        return armed ? "Watching - nobody close" : "Waiting until nobody is close";
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        List<String> positions = new ArrayList<>();
        for (BlockPos pos : SPAWNERS) {
            positions.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        properties.setProperty(prefix + "spawners", String.join(";", positions));
        properties.setProperty(prefix + "enderChest",
                enderChest == null ? "" : enderChest.getX() + "," + enderChest.getY() + "," + enderChest.getZ());
        properties.setProperty(prefix + "range", String.valueOf(range));
        properties.setProperty(prefix + "ignoreNpcs", String.valueOf(ignoreNpcs));
        properties.setProperty(prefix + "disconnect", String.valueOf(disconnectWhenDone));
        properties.setProperty(prefix + "ignoredPlayers", ignoredPlayers);
        properties.setProperty(prefix + "testKey", String.valueOf(testKey));
        properties.setProperty(prefix + "trigger", String.valueOf(trigger));
        properties.setProperty(prefix + "breakThreshold", String.valueOf(breakThreshold));
        properties.setProperty(prefix + "breakWindow", String.valueOf(breakWindow));
        properties.setProperty(prefix + "autoEnderChest", String.valueOf(autoEnderChest));
        properties.setProperty(prefix + "buyEnderChest", String.valueOf(buyEnderChest));
        properties.setProperty(prefix + "toasts", String.valueOf(toasts));
        properties.setProperty(prefix + "webhookUrl", webhookUrl);
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String spawners = properties.getProperty(prefix + "spawners");
        if (spawners != null) {
            SPAWNERS.clear();
            for (String entry : spawners.split(";")) {
                BlockPos pos = parse(entry);
                if (pos != null) {
                    SPAWNERS.add(pos);
                }
            }
        }
        String chest = properties.getProperty(prefix + "enderChest");
        if (chest != null) {
            enderChest = parse(chest);
        }
        try {
            range = Math.max(8, Math.min(128, Integer.parseInt(properties.getProperty(prefix + "range", String.valueOf(range)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        ignoreNpcs = Boolean.parseBoolean(properties.getProperty(prefix + "ignoreNpcs", String.valueOf(ignoreNpcs)));
        disconnectWhenDone = Boolean.parseBoolean(properties.getProperty(prefix + "disconnect", String.valueOf(disconnectWhenDone)));
        ignoredPlayers = properties.getProperty(prefix + "ignoredPlayers", ignoredPlayers);
        try {
            testKey = Integer.parseInt(properties.getProperty(prefix + "testKey", String.valueOf(testKey)).trim());
            trigger = Math.max(0, Math.min(TRIGGERS.length - 1, Integer.parseInt(properties.getProperty(prefix + "trigger", String.valueOf(trigger)).trim())));
            breakThreshold = Math.max(1, Math.min(20, Integer.parseInt(properties.getProperty(prefix + "breakThreshold", String.valueOf(breakThreshold)).trim())));
            breakWindow = Math.max(5, Math.min(600, Integer.parseInt(properties.getProperty(prefix + "breakWindow", String.valueOf(breakWindow)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        autoEnderChest = Boolean.parseBoolean(properties.getProperty(prefix + "autoEnderChest", String.valueOf(autoEnderChest)));
        buyEnderChest = Boolean.parseBoolean(properties.getProperty(prefix + "buyEnderChest", String.valueOf(buyEnderChest)));
        toasts = Boolean.parseBoolean(properties.getProperty(prefix + "toasts", String.valueOf(toasts)));
        webhookUrl = properties.getProperty(prefix + "webhookUrl", webhookUrl);
    }

    private static BlockPos parse(String text) {
        String[] xyz = text.split(",");
        if (xyz.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
