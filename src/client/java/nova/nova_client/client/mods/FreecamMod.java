package nova.nova_client.client.mods;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.FreecamMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.NovaType;

import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * Freecam: die Kamera loest sich vom Spieler und fliegt frei. WASD fliegt waagerecht in Blickrichtung (wie Laufen),
 * Leertaste hoch, Shift runter, Sprint-Taste schneller, das Mausrad stellt das Tempo ein (gilt bis zum Ausschalten).
 * <p>
 * Der Koerper bleibt stehen und ist zu sehen. Lief er beim Einschalten, laeuft er weiter ("Keep walking", auch mit
 * Auto Walk); wer gerade schlich, schleicht weiter - so faellt niemand von einer Kante, nur weil die Kamera losfliegt.
 * <p>
 * Abbauen, Bauen und Schlagen bleiben wie normal: dort, wo dein Spieler hinschaut. Er dreht sich mit der Kamera nicht
 * mit - wer beim Einschalten einen Block abbaut, baut dort weiter ab, waehrend die Kamera herumfliegt.
 */
public class FreecamMod extends GameTickMod {
    public static final float MIN_SPEED = 1.0F;
    public static final float MAX_SPEED = 100.0F;
    /** Normales Tempo in Bloecken pro Sekunde. */
    public static float speed = 10.0F;

    public static final float MIN_BOOST = 20.0F;
    public static final float MAX_BOOST = 300.0F;
    /** Tempo mit gedrueckter Sprint-Taste in Bloecken pro Sekunde. */
    public static float boostSpeed = 80.0F;

    /** Hoch/runter im Verhaeltnis zum Tempo (Prozent). */
    public static int verticalPercent = 100;
    /** Eigenen Spieler in der Welt zeigen. */
    public static boolean showBody = true;
    /** Hand mit Item unten rechts weiter zeigen. */
    public static boolean showHand = true;
    /** Lief der Spieler beim Einschalten, laeuft er weiter. */
    public static boolean keepWalking = true;
    /** WASD/Shift/Sprint steuern zusaetzlich den Koerper. */
    public static boolean keysMoveBody = false;
    /** Mausrad = Tempo statt Hotbar-Platz. */
    public static boolean scrollSpeed = true;

    /**
     * Frueher holte Freecam fuer das Abbauen an der Kamera mehr Reichweite (dieser Modifier auf block_interaction_range).
     * Reste davon (Absturz, Verbindung weg) nimmt {@link #globalTick} wieder weg.
     */
    private static final Identifier REACH_ID = Identifier.fromNamespaceAndPath("nova_client", "freecam_reach");
    /** Nicht oefter versuchen, den Rest wegzunehmen; nach so vielen Versuchen ohne Erfolg aufgeben. */
    private static final long RELEASE_EVERY = 2000L;
    private static final int MAX_RELEASES = 2;
    /** So lange nach einem eigenen Befehl dessen Rueckmeldung im Chat ausblenden. */
    private static final long FEEDBACK_MS = 4000L;

    /** Mausrad: pro Raste so viel schneller/langsamer; Grenzen fuer das Ergebnis. */
    private static final float SCROLL_STEP = 1.25F;
    private static final float SCROLL_MIN = 0.5F;
    private static final float SCROLL_MAX = 300.0F;
    /** So lange steht das Tempo nach dem Scrollen ueber der Hotbar. */
    private static final long HINT_MS = 1400L;
    /** Anfahren: Anteil am Zieltempo pro Tick (Loslassen = sofort stehen). */
    private static final double ACCEL = 0.5D;

    private static Vec3 position;
    private static Vec3 previous;
    /** Welt beim Start (Tod/Dimensionswechsel = neu beim Spieler anfangen). */
    private static Object level;
    private static Vec3 velocity = Vec3.ZERO;
    private static float yaw;
    private static float pitch;
    /** Faktor vom Mausrad (1 = Tempo aus den Einstellungen). */
    private static float scrollFactor = 1.0F;
    private static long scrolledAt = -HINT_MS;

    // Reste der alten Reichweite: wann/wie oft zuletzt versucht, sie wegzunehmen
    private static long reachReleasedAt = -RELEASE_EVERY;
    private static int reachReleases;
    private static long feedbackUntil;

    // Was der Koerper beim Einschalten gedrueckt hielt (bleibt bis zum Ausschalten)
    private static boolean walkForward;
    private static boolean walkBackward;
    private static boolean walkLeft;
    private static boolean walkRight;
    private static boolean walkSprint;
    private static boolean walkSneak;

    public FreecamMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        if (!active || client.player == null) {
            return;
        }
        if (position == null || level != client.level) {
            start(client.player);
        }
        Options options = client.options;
        double forward = axis(options.keyUp, options.keyDown);
        double strafe = axis(options.keyLeft, options.keyRight);
        double lift = axis(options.keyJump, options.keyShift);
        double perTick = currentSpeed(options.keySprint.isDown()) / 20.0D;

        // Waagerecht nach der Kamera-Drehung (Neigung zaehlt nicht), schraeg nicht schneller als gerade
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double x = strafe * cos - forward * sin;
        double z = forward * cos + strafe * sin;
        double flat = Math.sqrt(x * x + z * z);
        if (flat > 1.0D) {
            x /= flat;
            z /= flat;
        }
        Vec3 target = new Vec3(x * perTick, lift * perTick * verticalPercent / 100.0D, z * perTick);
        velocity = target.lengthSqr() == 0.0D ? Vec3.ZERO : velocity.lerp(target, ACCEL);
        previous = position;
        position = position.add(velocity);
    }

    private static double axis(KeyMapping plus, KeyMapping minus) {
        return (plus.isDown() ? 1.0D : 0.0D) - (minus.isDown() ? 1.0D : 0.0D);
    }

    /** Tempo in Bloecken pro Sekunde (mit Mausrad-Faktor). */
    private static float currentSpeed(boolean boost) {
        return Mth.clamp((boost ? boostSpeed : speed) * scrollFactor, SCROLL_MIN, SCROLL_MAX);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (enabled && client.player != null) {
            start(client.player);
        } else {
            position = null;
            previous = null;
            level = null;
            velocity = Vec3.ZERO;
            scrollFactor = 1.0F;
            scrolledAt = -HINT_MS;
        }
    }

    private static void start(LocalPlayer player) {
        position = player.getEyePosition();
        previous = position;
        level = Minecraft.getInstance().level;
        velocity = Vec3.ZERO;
        yaw = player.getYRot();
        pitch = player.getXRot();
        // Tasten dieses Ticks merken: daraus wird "weiterlaufen"
        Input keys = player.input.keyPresses;
        walkForward = keepWalking && keys.forward();
        walkBackward = keepWalking && keys.backward();
        walkLeft = keepWalking && keys.left();
        walkRight = keepWalking && keys.right();
        walkSprint = keepWalking && (keys.sprint() || player.isSprinting());
        // fliegend hiesse "schleichen" sinken - dann nicht merken
        walkSneak = !player.getAbilities().flying && (keys.shift() || player.isShiftKeyDown());
    }

    public static boolean isEnabled() {
        return position != null;
    }

    /**
     * Jeden Tick: hat der Spieler noch die Reichweite vom alten Abbauen an der Kamera, sie wieder wegnehmen - im
     * Einzelspieler direkt, auf Servern per /attribute (das geht nur mit OP, und nur dort konnte sie gesetzt werden).
     */
    public static void globalTick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        if (!hasReach(player)) {
            reachReleases = 0;
            return;
        }
        long now = Util.getMillis();
        if (now - reachReleasedAt < RELEASE_EVERY || reachReleases >= MAX_RELEASES) {
            return;
        }
        reachReleasedAt = now;
        reachReleases++;
        IntegratedServer server = client.getSingleplayerServer();
        if (server != null) {
            removeOnServer(server, player);
        } else if (player.connection.getCommands().getRoot().getChild("attribute") != null) {
            feedbackUntil = now + FEEDBACK_MS;
            player.connection.sendCommand("attribute @s minecraft:block_interaction_range modifier remove " + REACH_ID);
        }
    }

    private static boolean hasReach(LocalPlayer player) {
        AttributeInstance range = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        return range != null && range.hasModifier(REACH_ID);
    }

    /** Im Einzelspieler: den Modifier am eigenen Spieler auf dem eingebauten Server (dessen Thread) entfernen. */
    private static void removeOnServer(IntegratedServer server, LocalPlayer player) {
        UUID id = player.getUUID();
        server.execute(() -> {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(id);
            AttributeInstance range = serverPlayer == null ? null : serverPlayer.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (range != null) {
                range.removeModifier(REACH_ID);
            }
        });
    }

    /** Chat-Rueckmeldung unserer /attribute-Befehle ausblenden (kurz nach dem Senden). */
    public static boolean hidesFeedback(Component message) {
        return Util.getMillis() < feedbackUntil && message.getContents() instanceof TranslatableContents contents
                && contents.getKey().startsWith("commands.attribute.");
    }

    /** Eigenen Spieler rendern (Kamera gilt als losgeloest)? */
    public static boolean showsBody() {
        return isEnabled() && showBody;
    }

    /** Hand mit Item ausblenden? */
    public static boolean hidesHand() {
        return isEnabled() && !showHand;
    }

    /**
     * Tasten fuer den Koerper, solange Freecam an ist (keys = echte Tasten dieses Ticks): die gemerkte Laufrichtung,
     * Auto Walk und - mit "Keys move body" - die echten Tasten; die Gegenrichtung beendet dann das Weiterlaufen.
     */
    public static Input bodyInput(Input keys) {
        if (keysMoveBody) {
            walkForward &= !keys.backward();
            walkBackward &= !keys.forward();
            walkLeft &= !keys.right();
            walkRight &= !keys.left();
        }
        boolean live = keysMoveBody;
        boolean forward = walkForward || AutoWalkMod.shouldWalk() || live && keys.forward();
        boolean backward = walkBackward || live && keys.backward();
        boolean left = walkLeft || live && keys.left();
        boolean right = walkRight || live && keys.right();
        boolean sneak = walkSneak || live && keys.shift();
        boolean sprint = walkSprint || live && keys.sprint();
        return new Input(forward, backward, left, right, false, sneak, sprint);
    }

    /** Mausrad im Spiel: true = als Tempo verbraucht. */
    public static boolean onScroll(double amount) {
        if (!isEnabled() || !scrollSpeed || amount == 0.0D) {
            return false;
        }
        float factor = amount > 0.0D ? SCROLL_STEP : 1.0F / SCROLL_STEP;
        // nur so weit, wie das Tempo in den Grenzen bleibt
        float next = scrollFactor * factor;
        if (speed * next >= SCROLL_MIN && speed * next <= SCROLL_MAX) {
            scrollFactor = next;
        }
        scrolledAt = Util.getMillis();
        return true;
    }

    /** Kurz nach dem Scrollen das neue Tempo als kleine Pille ueber der Hotbar. */
    public static void drawHud(GuiGraphicsExtractor g) {
        long age = Util.getMillis() - scrolledAt;
        if (!isEnabled() || age >= HINT_MS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        float a = Math.min(1.0F, Math.min(age / 120.0F, (HINT_MS - age) / 300.0F));
        float value = currentSpeed(false);
        String number = value < 10.0F ? String.format(Locale.ROOT, "%.1f", value) : String.valueOf(Math.round(value));
        String unit = " blocks/s";
        NovaType.Face face = NovaType.Face.STRONG;
        NovaType.Face small = NovaType.Face.BODY;
        int w = NovaType.width(font, face, number) + NovaType.width(font, small, unit) + 16;
        int h = 16;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int y0 = mc.getWindow().getGuiScaledHeight() - 74;
        int x0 = cx - w / 2;
        NovaShapes.rect(g, x0, y0, x0 + w, y0 + h, h / 2, NovaGui.alpha(NovaGui.HUD_BACKGROUND, a));
        NovaShapes.outline(g, x0, y0, x0 + w, y0 + h, h / 2, NovaGui.alpha(NovaGui.HUD_BORDER, a));
        int x = x0 + 8;
        NovaType.draw(g, font, face, number, x, NovaType.centerY(face, y0, h), NovaGui.alpha(NovaGui.TEXT, a));
        NovaType.draw(g, font, small, unit, x + NovaType.width(font, face, number), NovaType.centerY(small, y0, h),
                NovaGui.alpha(NovaGui.TEXT_DIM, a));
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new FreecamMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "boostSpeed", String.valueOf(Math.round(boostSpeed)));
        properties.setProperty(prefix + "speed", String.valueOf(Math.round(speed)));
        properties.setProperty(prefix + "vertical", String.valueOf(verticalPercent));
        properties.setProperty(prefix + "body", String.valueOf(showBody));
        properties.setProperty(prefix + "hand", String.valueOf(showHand));
        properties.setProperty(prefix + "keepWalking", String.valueOf(keepWalking));
        properties.setProperty(prefix + "keysMoveBody", String.valueOf(keysMoveBody));
        properties.setProperty(prefix + "scroll", String.valueOf(scrollSpeed));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        showBody = bool(properties, prefix + "body", showBody);
        showHand = bool(properties, prefix + "hand", showHand);
        keepWalking = bool(properties, prefix + "keepWalking", keepWalking);
        keysMoveBody = bool(properties, prefix + "keysMoveBody", keysMoveBody);
        scrollSpeed = bool(properties, prefix + "scroll", scrollSpeed);
        boostSpeed = number(properties, prefix + "boostSpeed", boostSpeed, MIN_BOOST, MAX_BOOST);
        speed = number(properties, prefix + "speed", speed, MIN_SPEED, MAX_SPEED);
        verticalPercent = Math.round(number(properties, prefix + "vertical", verticalPercent, 20.0F, 300.0F));
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static float number(Properties properties, String key, float fallback, float min, float max) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Mth.clamp(Float.parseFloat(value.trim()), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Kameraposition zwischen zwei Ticks interpoliert, damit die Bewegung pro Frame fluessig ist. */
    public static Vec3 getCameraPosition(float partialTick) {
        return previous.lerp(position, partialTick);
    }

    public static float getCameraYaw() {
        return yaw;
    }

    public static float getCameraPitch() {
        return pitch;
    }

    /** Wird pro Frame von der Maus aufgerufen (gleiche Skalierung wie Entity.turn). */
    public static void turn(double yawDelta, double pitchDelta) {
        yaw += (float) yawDelta * 0.15F;
        pitch = Mth.clamp(pitch + (float) pitchDelta * 0.15F, -90.0F, 90.0F);
    }
}
