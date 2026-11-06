package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.tunnelfinder.BaseDetector;
import nova.nova_client.client.tunnelfinder.TunnelBot;

import java.util.List;
import java.util.Properties;

/**
 * Tunnel Base Finder: graebt knapp ueber Bedrock (Hoehe automatisch, Vanilla Y -58) einen Tunnel geradeaus - in die
 * Richtung, in die man beim Einschalten schaut. Ist man hoeher, geht es erst treppenartig nach unten. Mit Drill
 * (Spitzhacke, die 3x3 abbaut) reicht ein Schlag pro Schritt.
 * Waehrenddessen werden alle geladenen Chunks nach echten Bases durchsucht ({@link BaseDetector}: viele Punkte aus
 * mehreren Kategorien, keine einzelnen Kisten, keine Dungeons/Mineshafts/Ancient Cities).
 * <p>
 * Hindernisse halten den Bot nicht auf: Bei Wasser graebt er in eine andere Richtung weiter, ueber Loecher geht er
 * einfach weiter (zuschuetten oder flach hinein und wieder hoch), Lava und unzerstoerbare Bloecke umgeht er. Er hoert
 * erst auf, sobald eine Base gefunden ist - oder bei der Stopp-Taste (bzw. der normalen Modul-Taste), wenig Leben,
 * fehlendem Werkzeug oder erreichter Maximal-Laenge. Gefundene Bases bleiben umrandet, bis man die Welt wechselt.
 */
public class TunnelBaseFinderMod extends GameTickMod {
    public static final String[] AIM_SPEEDS = {"Relaxed", "Normal", "Fast"};
    private static final float[] AIM_SPEED_VALUES = {1.0F, 1.4F, 2.0F};
    public static final String[] TOOLS = {"Pickaxe", "Drill (3x3)"};

    // Einstellungen
    /** 0 = normale Spitzhacke, 1 = Drill (baut 3x3 auf einmal ab). */
    public static int tool = 0;
    public static int stopKey = com.mojang.blaze3d.platform.InputConstants.KEY_END;
    public static int minScore = 12;
    public static int maxBaseY = 30;
    public static boolean bridge = true;
    public static boolean stopOnWater = true;
    public static int minHealth = 10;
    public static int maxLength = 0;
    public static int aimSpeed = 1;
    public static boolean highlight = true;
    /** Wert fuer "Tunnel-Hoehe automatisch" (zwei ueber dem hoechsten Bedrock). */
    public static final int AUTO_Y = -65;
    /** Tunnel-Hoehe (Fuesse); AUTO_Y = automatisch. */
    public static int tunnelY = AUTO_Y;

    private static final TunnelBot BOT = new TunnelBot();
    private static final BaseDetector DETECTOR = new BaseDetector();
    private static TunnelBaseFinderMod instance;
    private static ClientLevel lastLevel;
    private static boolean stopKeyWasDown;
    private static int statusTicks;
    /** Zuletzt gefundene Base (bekommt die Tracer-Linie). */
    private static BaseDetector.Base latest;

    public TunnelBaseFinderMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    /** Steuert der Bot gerade (Maus, Angriff, Bewegung gesperrt)? */
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

    public static String stopKeyName() {
        return stopKey == NO_KEY ? "None" : InputConstants.Type.KEYBOARD.getOrCreate(stopKey).getDisplayName().getString();
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (!enabled) {
            BOT.stop();
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            active = false;
            return;
        }
        if (client.level != lastLevel) {
            lastLevel = client.level;
            DETECTOR.reset();
            latest = null;
        }
        BOT.drill = tool == 1;
        BOT.minHealth = minHealth;
        BOT.bridge = bridge;
        BOT.stopOnWater = stopOnWater;
        BOT.maxLength = maxLength;
        BOT.fixedY = tunnelY == AUTO_Y ? Integer.MIN_VALUE : tunnelY;
        String problem = BOT.start(player, client.level, AIM_SPEED_VALUES[Mth.clamp(aimSpeed, 0, AIM_SPEED_VALUES.length - 1)]);
        if (problem != null) {
            active = false;
            NovaNotify.chat("Tunnel Base Finder", problem);
            return;
        }
        // Taste, mit der man gerade eingeschaltet hat, soll nicht sofort wieder stoppen
        stopKeyWasDown = true;
        int y = BOT.targetY();
        String height = "§7Height: §fY " + y + (tunnelY != AUTO_Y ? " §8(set in the menu)"
                : BOT.bedrockTop() == Integer.MIN_VALUE ? " §8(no bedrock seen)" : " §8(bedrock up to " + BOT.bedrockTop() + ")");
        String where = player.blockPosition().getY() > y ? "going down, then " : "";
        NovaNotify.chat("Tunnel Base Finder", "§7Started: " + where + "tunneling §f"
                + BOT.heading().getName().toUpperCase(java.util.Locale.ROOT) + (BOT.drill ? " §7with drill" : "")
                + "§7. " + height + "§7. Stop with §f[" + stopKeyName() + "]§7.");
    }

    /** Bot anhalten und Modul ausschalten. */
    private void finish(Minecraft client, String reason) {
        int length = BOT.distance();
        active = false;
        onStateChanged(client, false);
        NovaNotify.chat("Tunnel Base Finder", "§7Stopped: " + reason + " §8(" + length + " blocks tunneled)");
    }

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        if (level == null || player == null) {
            return;
        }
        if (level != lastLevel) {
            // Welt-/Server-Wechsel: alte Funde gelten nicht mehr, Bot nicht einfach weiterlaufen lassen
            lastLevel = level;
            DETECTOR.reset();
            latest = null;
            finish(client, "world changed");
            return;
        }
        // Kein FPS-Limit wegen "keine Eingabe", die Kamera soll fluessig bleiben
        client.getFramerateLimitTracker().onInputReceived();

        // Stopp-Taste (nur im Spiel, nicht beim Tippen im Chat)
        boolean down = stopKey != NO_KEY && InputConstants.isKeyDown(stopKey);
        if (down && !stopKeyWasDown && client.gui.screen() == null) {
            stopKeyWasDown = true;
            finish(client, "§fstop key pressed");
            return;
        }
        stopKeyWasDown = down;

        // Bases suchen
        BaseDetector.Base base = DETECTOR.tick(level, player, client.options.getEffectiveRenderDistance(), maxBaseY, minScore);
        if (base != null) {
            latest = base;
            announce(player, base);
            finish(client, "§abase found");
            return;
        }

        String reason = BOT.tick();
        if (reason != null) {
            if (reason.startsWith("§c")) {
                NovaNotify.sound(SoundEvents.NOTE_BLOCK_BASS, 0.6F);
            }
            finish(client, reason);
            return;
        }
        if (++statusTicks >= 10) {
            statusTicks = 0;
            Compat.actionBar(player, Component.literal("§dTunnel Base Finder §7· " + BOT.status() + " · §f"
                    + BOT.distance() + "§7 blocks · stop: §f" + stopKeyName()));
        }
    }

    private static void announce(LocalPlayer player, BaseDetector.Base base) {
        Vec3 from = player.position();
        Vec3 to = Vec3.atCenterOf(base.center());
        NovaNotify.chat("Tunnel Base Finder", "§aBase found! §f" + base.center().getX() + " " + base.center().getY()
                + " " + base.center().getZ() + " §7(" + NovaNotify.where(from, to) + ", score §f" + base.score() + "§7)");
        NovaNotify.chat("Tunnel Base Finder", "§7" + base.summary());
        NovaNotify.sound(SoundEvents.PLAYER_LEVELUP, 1.0F);
    }

    // ---------------------------------------------------------------- Zeichnen

    public static boolean wantsGizmos() {
        return highlight && !DETECTOR.bases().isEmpty() && Minecraft.getInstance().level == lastLevel;
    }

    /** Gefundene Bases umranden (jeden Frame, waehrend ein Gizmo-Collector aktiv ist). */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!wantsGizmos() || client.level == null || client.player == null) {
            return;
        }
        int color = NovaGui.accent(ModCategory.BASEFINDING) & 0xFFFFFF;
        for (BaseDetector.Base base : DETECTOR.bases()) {
            Gizmos.cuboid(base.box().inflate(0.05D), GizmoStyle.strokeAndFill(0xFF000000 | color, 2.5F, 0x30000000 | color))
                    .setAlwaysOnTop();
        }
        BaseDetector.Base target = latest;
        if (target != null) {
            net.minecraft.client.Camera camera = client.gameRenderer.mainCamera();
            org.joml.Vector3fc forward = camera.forwardVector();
            Vec3 start = camera.position().add(forward.x() * 0.6D, forward.y() * 0.6D, forward.z() * 0.6D);
            Gizmos.line(start, Vec3.atCenterOf(target.center()), 0xE0000000 | color, 1.5F).setAlwaysOnTop();
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, "Tunnel Base Finder", "", List.of(
                new ModOptionsMenu.KeyBind("Stop key", () -> stopKey, v -> stopKey = v),
                new ModOptionsMenu.Choice("Mining tool", TOOLS, () -> tool, v -> tool = v),
                new ModOptionsMenu.Slider("Tunnel at Y", AUTO_Y, 320.0D, 1.0D, () -> tunnelY,
                        v -> tunnelY = (int) Math.round(v), v -> Math.round(v) == AUTO_Y ? "Auto" : String.valueOf(Math.round(v))),
                new ModOptionsMenu.Slider("Min. base score", 5.0D, 60.0D, 1.0D, () -> minScore,
                        v -> minScore = (int) Math.round(v), v -> String.valueOf(Math.round(v))),
                new ModOptionsMenu.Slider("Bases up to Y", -64.0D, 320.0D, 1.0D, () -> maxBaseY,
                        v -> maxBaseY = (int) Math.round(v), v -> String.valueOf(Math.round(v))),
                new ModOptionsMenu.Slider("Stop below health", 0.0D, 20.0D, 1.0D, () -> minHealth,
                        v -> minHealth = (int) Math.round(v), v -> Math.round(v) == 0 ? "Off" : Math.round(v) + " HP"),
                new ModOptionsMenu.Slider("Max. tunnel length", 0.0D, 5000.0D, 50.0D, () -> maxLength,
                        v -> maxLength = (int) Math.round(v), v -> Math.round(v) == 0 ? "Unlimited" : Math.round(v) + " blocks"),
                new ModOptionsMenu.Toggle("Bridge holes (cobble/deepslate in hotbar)", () -> bridge, v -> bridge = v),
                new ModOptionsMenu.Toggle("Water: tunnel in another direction (off = dig through)", () -> stopOnWater, v -> stopOnWater = v),
                new ModOptionsMenu.Choice("Aim speed", AIM_SPEEDS, () -> aimSpeed, v -> aimSpeed = v),
                new ModOptionsMenu.Toggle("Highlight found bases + tracer", () -> highlight, v -> highlight = v),
                new ModOptionsMenu.Action(() -> "Forget found bases (" + DETECTOR.bases().size() + ")", () -> {
                    DETECTOR.reset();
                    latest = null;
                }),
                new ModOptionsMenu.Info(() -> BOT.running() ? "Running: " + BOT.status() + ", " + BOT.distance() + " blocks"
                        : "Water: turns · holes: keeps going · stops only at a base · sliders: scroll = ±1, right-click = type")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "tool", String.valueOf(tool));
        properties.setProperty(prefix + "stopKey", String.valueOf(stopKey));
        properties.setProperty(prefix + "minScore", String.valueOf(minScore));
        properties.setProperty(prefix + "maxBaseY", String.valueOf(maxBaseY));
        properties.setProperty(prefix + "bridge", String.valueOf(bridge));
        properties.setProperty(prefix + "stopOnWater", String.valueOf(stopOnWater));
        properties.setProperty(prefix + "minHealth", String.valueOf(minHealth));
        properties.setProperty(prefix + "maxLength", String.valueOf(maxLength));
        properties.setProperty(prefix + "aimSpeed", String.valueOf(aimSpeed));
        properties.setProperty(prefix + "highlight", String.valueOf(highlight));
        properties.setProperty(prefix + "tunnelY", String.valueOf(tunnelY));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        bridge = Boolean.parseBoolean(properties.getProperty(prefix + "bridge", String.valueOf(bridge)));
        stopOnWater = Boolean.parseBoolean(properties.getProperty(prefix + "stopOnWater", String.valueOf(stopOnWater)));
        highlight = Boolean.parseBoolean(properties.getProperty(prefix + "highlight", String.valueOf(highlight)));
        tool = readInt(properties, prefix + "tool", tool, 0, TOOLS.length - 1);
        stopKey = readInt(properties, prefix + "stopKey", stopKey, Integer.MIN_VALUE, Integer.MAX_VALUE);
        minScore = readInt(properties, prefix + "minScore", minScore, 5, 60);
        maxBaseY = readInt(properties, prefix + "maxBaseY", maxBaseY, -64, 320);
        minHealth = readInt(properties, prefix + "minHealth", minHealth, 0, 20);
        maxLength = readInt(properties, prefix + "maxLength", maxLength, 0, 5000);
        aimSpeed = readInt(properties, prefix + "aimSpeed", aimSpeed, 0, AIM_SPEEDS.length - 1);
        tunnelY = readInt(properties, prefix + "tunnelY", tunnelY, AUTO_Y, 320);
    }

    private static int readInt(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Mth.clamp(Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback; // Standard behalten
        }
    }
}
