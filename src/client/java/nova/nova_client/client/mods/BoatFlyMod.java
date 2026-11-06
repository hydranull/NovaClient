package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Boat Fly: das Boot (oder jedes Fahrzeug, das du steuerst) fliegt. WASD in Blickrichtung, Springen = hoch,
 * Sprinten = runter, sonst sinkt es mit "Fall speed". Anti-Kick: alle paar Ticks meldet es dem Server kurz eine etwas
 * tiefere Position (wie ein Fall) und gleich danach wieder die echte - sonst wirft der Server einen wegen Fliegens raus.
 */
public class BoatFlyMod extends GameTickMod {
    private static final double KICK_DROP = 0.0313D;
    private static final double DIAGONAL = 1.0D / Math.sqrt(2.0D);

    // Einstellungen
    public static float speed = 10.0F;
    public static float verticalSpeed = 6.0F;
    public static float fallSpeed = 0.0F;
    public static boolean antiKick = true;
    public static int kickDelay = 40;
    /** Positions-Korrekturen des Servers fuer das Fahrzeug ignorieren. */
    public static boolean cancelServerPackets = false;

    private static volatile boolean enabled;

    // Anti-Kick
    private static int delayLeft;
    private static double lastPacketY = Double.MAX_VALUE;
    private static boolean lowered;
    private static Entity trackedVehicle;
    private static boolean sendingOwn;

    public BoatFlyMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        delayLeft = kickDelay;
        forget();
        trackedVehicle = client.player != null ? client.player.getVehicle() : null;
    }

    private static void forget() {
        lastPacketY = Double.MAX_VALUE;
        lowered = false;
    }

    // ---------------------------------------------------------------- Bewegung (Entity.move)

    /** Bewegung des Fahrzeugs, das du gerade steuerst, ersetzen. */
    public static Vec3 onEntityMove(Entity entity, MoverType type, Vec3 movement) {
        if (!enabled || type != MoverType.SELF || !entity.level().isClientSide()) {
            return movement;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.getVehicle() != entity || entity.getControllingPassenger() != player) {
            return movement;
        }
        Vec3 velocity = entity.getDeltaMovement();
        Vec3 horizontal = horizontal(player, speed);
        Vec3 result = new Vec3(horizontal.x, vertical(), horizontal.z);
        entity.setYRot(player.getYRot());
        if (movement == velocity) {
            entity.setDeltaMovement(result);
        }
        return result;
    }

    private static Vec3 horizontal(LocalPlayer player, double blocksPerSecond) {
        float yaw = player.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = Vec3.directionFromRotation(0.0F, yaw + 90.0F);
        Input input = player.input.keyPresses;
        double step = blocksPerSecond / 20.0D;
        double x = 0.0D;
        double z = 0.0D;
        boolean straight = input.forward() != input.backward();
        boolean side = input.left() != input.right();
        if (input.forward()) {
            x += forward.x * step;
            z += forward.z * step;
        }
        if (input.backward()) {
            x -= forward.x * step;
            z -= forward.z * step;
        }
        if (input.right()) {
            x += right.x * step;
            z += right.z * step;
        }
        if (input.left()) {
            x -= right.x * step;
            z -= right.z * step;
        }
        if (straight && side) {
            x *= DIAGONAL;
            z *= DIAGONAL;
        }
        return new Vec3(x, 0.0D, z);
    }

    private static double vertical() {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() != null || FreecamMod.isEnabled()) {
            return -fallSpeed / 20.0D;
        }
        double y = 0.0D;
        if (client.options.keyJump.isDown()) {
            y += verticalSpeed / 20.0D;
        }
        if (client.options.keySprint.isDown()) {
            y -= verticalSpeed / 20.0D;
        } else {
            y -= fallSpeed / 20.0D;
        }
        return y;
    }

    /** Boot nicht mit A/D drehen (es schaut immer in deine Blickrichtung). */
    public static boolean locksBoatTurning(AbstractBoat boat) {
        return enabled && boat.level().isClientSide();
    }

    /** Kein Pferde-Sprung (Leertaste ist "hoch"). */
    public static boolean cancelsMountJump(LocalPlayer player) {
        return enabled && player.getVehicle() instanceof PlayerRideableJumping;
    }

    // ---------------------------------------------------------------- Pakete

    /** Netzwerk-Thread moeglich: Fahrzeug-Korrektur des Servers verwerfen? */
    public static boolean cancelsServerMove() {
        return enabled && cancelServerPackets;
    }

    /** Server hat das Fahrzeug zurueckgesetzt: Anti-Kick neu anfangen. */
    public static void onSetback() {
        if (enabled) {
            forget();
        }
    }

    /** Ausgehendes Paket (Hauptthread): Anti-Kick senkt ab und zu die gemeldete Hoehe ab. */
    public static Packet<?> onSend(Packet<?> packet) {
        if (!enabled || !antiKick || sendingOwn || !(packet instanceof ServerboundMoveVehiclePacket move)) {
            return packet;
        }
        Minecraft client = Minecraft.getInstance();
        if (!client.isSameThread() || client.player == null || client.player.getVehicle() == null) {
            return packet;
        }
        Entity vehicle = client.player.getVehicle();
        track(vehicle);
        double y = packetPos(move).y;
        Packet<?> result = packet;
        if (delayLeft <= 0 && !lowered && shouldDrop(y) && inAir(vehicle) && !vehicle.isFlyingVehicle()) {
            result = withY(move, lastPacketY - KICK_DROP);
            lowered = true;
            delayLeft = kickDelay;
        }
        lastPacketY = y;
        return result;
    }

    private static Vec3 packetPos(ServerboundMoveVehiclePacket move) {
        //? if >=26.1 {
        return move.movingTo().position();
        //?} else {
        /*return move.position();
        *///?}
    }

    /** Dasselbe Fahrzeug-Paket mit anderer Hoehe. */
    private static ServerboundMoveVehiclePacket withY(ServerboundMoveVehiclePacket move, double y) {
        //? if >=26.1 {
        net.minecraft.core.PositionAndRotation at = move.movingTo();
        return new ServerboundMoveVehiclePacket(net.minecraft.core.PositionAndRotation.of(
                new Vec3(at.position().x, y, at.position().z), at.yRot(), at.xRot()), move.onGround());
        //?} else {
        /*return new ServerboundMoveVehiclePacket(new Vec3(move.position().x, y, move.position().z), move.yRot(), move.xRot(),
                move.onGround());
        *///?}
    }

    private static boolean shouldDrop(double y) {
        return lastPacketY != Double.MAX_VALUE && (y >= lastPacketY || lastPacketY - y < KICK_DROP);
    }

    private static boolean inAir(Entity vehicle) {
        return vehicle.level().getBlockStates(vehicle.getBoundingBox().inflate(0.0625D).expandTowards(0.0D, -0.55D, 0.0D))
                .allMatch(BlockState::isAir);
    }

    private static void track(Entity vehicle) {
        if (vehicle != trackedVehicle) {
            trackedVehicle = vehicle;
            forget();
        }
    }

    @Override
    public void execute(Minecraft client) {
        if (!antiKick || client.player == null) {
            return;
        }
        Entity vehicle = client.player.getVehicle();
        track(vehicle);
        if (lowered) {
            // Gleich danach wieder die echte Hoehe melden
            ClientPacketListener connection = client.getConnection();
            if (vehicle != null && connection != null && lastPacketY != Double.MAX_VALUE) {
                ServerboundMoveVehiclePacket now = ServerboundMoveVehiclePacket.fromEntity(vehicle);
                sendingOwn = true;
                try {
                    connection.send(withY(now, lastPacketY));
                } finally {
                    sendingOwn = false;
                }
            }
            lowered = false;
        }
        if (delayLeft > 0) {
            delayLeft--;
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Slider("Speed", 0.0D, 50.0D, 0.5D, () -> speed, v -> speed = (float) v,
                        v -> String.format(Locale.ROOT, "%.1f blocks/s", v)),
                new ModOptionsMenu.Slider("Vertical speed", 0.0D, 20.0D, 0.5D, () -> verticalSpeed, v -> verticalSpeed = (float) v,
                        v -> String.format(Locale.ROOT, "%.1f blocks/s", v)),
                new ModOptionsMenu.Slider("Fall speed", 0.0D, 10.0D, 0.1D, () -> fallSpeed, v -> fallSpeed = (float) v,
                        v -> String.format(Locale.ROOT, "%.1f blocks/s", v)),
                new ModOptionsMenu.Toggle("Anti fly kick", () -> antiKick, v -> {
                    antiKick = v;
                    forget();
                }),
                new ModOptionsMenu.Slider("Anti kick delay", 1.0D, 80.0D, 1.0D, () -> kickDelay,
                        v -> kickDelay = (int) Math.round(v), v -> Math.round(v) + " ticks"),
                new ModOptionsMenu.Toggle("Cancel server packets", () -> cancelServerPackets, v -> cancelServerPackets = v),
                new ModOptionsMenu.Info(() -> "Jump = up  ·  sprint = down  ·  WASD = fly")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "speed", String.valueOf(speed));
        properties.setProperty(prefix + "verticalSpeed", String.valueOf(verticalSpeed));
        properties.setProperty(prefix + "fallSpeed", String.valueOf(fallSpeed));
        properties.setProperty(prefix + "antiKick", String.valueOf(antiKick));
        properties.setProperty(prefix + "kickDelay", String.valueOf(kickDelay));
        properties.setProperty(prefix + "cancelServerPackets", String.valueOf(cancelServerPackets));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            speed = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "speed", String.valueOf(speed)).trim()), 0.0F, 50.0F);
            verticalSpeed = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "verticalSpeed", String.valueOf(verticalSpeed)).trim()), 0.0F, 20.0F);
            fallSpeed = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "fallSpeed", String.valueOf(fallSpeed)).trim()), 0.0F, 10.0F);
            kickDelay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "kickDelay", String.valueOf(kickDelay)).trim()), 1, 80);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        antiKick = Boolean.parseBoolean(properties.getProperty(prefix + "antiKick", String.valueOf(antiKick)));
        cancelServerPackets = Boolean.parseBoolean(properties.getProperty(prefix + "cancelServerPackets", String.valueOf(cancelServerPackets)));
    }
}
