package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;

/**
 * Zielt beim Spannen eines Bogens (oder mit geladener Armbrust) automatisch auf das Ziel,
 * das am naechsten am Fadenkreuz ist. Beruecksichtigt Flugbahn (Schwerkraft) und Bewegung des Ziels.
 */
public class BowAimbotMod extends GameTickMod {
    private static final double RANGE = 64.0D;
    /** Maximaler Winkel zum Fadenkreuz, in dem Ziele erfasst werden. */
    private static final double MAX_ANGLE = 60.0D;
    /** Angenaeherte Pfeil-Schwerkraft fuer die Formel (inkl. Luftwiderstand, Werte wie bei Wurst). */
    private static final double GRAVITY = 0.006D;
    private static final float CROSSBOW_VELOCITY = 1.05F;

    private Entity lastTarget;
    private int messageCooldown;

    public BowAimbotMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        lastTarget = null;
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || FreecamMod.isEnabled()) {
            return;
        }

        float velocity = arrowVelocity(player);
        if (velocity <= 0.0F) {
            lastTarget = null;
            return;
        }

        LivingEntity target = findTarget(client, player);
        if (target == null) {
            lastTarget = null;
            return;
        }

        aimAt(player, target, velocity);

        if (target != lastTarget || --messageCooldown <= 0) {
            lastTarget = target;
            messageCooldown = 10;
            int distance = (int) Math.round(player.distanceTo(target));
            Compat.actionBar(player, Component.literal("Target: ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(target.getName().getString()).withStyle(ChatFormatting.RED))
                    .append(Component.literal("  " + distance + "m").withStyle(ChatFormatting.GRAY)));
        }
    }

    /** Aktuelle Pfeilgeschwindigkeit (0..1), 0 = gerade nicht am Schiessen. */
    private static float arrowVelocity(LocalPlayer player) {
        if (player.isUsingItem() && player.getUseItem().getItem() instanceof BowItem) {
            float power = BowItem.getPowerForTime(player.getTicksUsingItem());
            return power < 0.1F ? 0.0F : power;
        }
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(main)) {
            return CROSSBOW_VELOCITY;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(off)) {
            return CROSSBOW_VELOCITY;
        }
        return 0.0F;
    }

    private static LivingEntity findTarget(Minecraft client, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        LivingEntity best = null;
        double bestAngle = MAX_ANGLE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity == player || !living.isAlive()) {
                continue;
            }
            if (!(entity instanceof Player || entity instanceof Enemy)) {
                continue;
            }
            if (entity instanceof Player other && (other.isSpectator() || other.isCreative())) {
                continue;
            }
            if (player.distanceToSqr(entity) > RANGE * RANGE || !player.hasLineOfSight(entity)) {
                continue;
            }

            Vec3 toTarget = entity.getBoundingBox().getCenter().subtract(eye).normalize();
            double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(toTarget), -1.0D, 1.0D)));
            if (angle < bestAngle) {
                bestAngle = angle;
                best = living;
            }
        }
        return best;
    }

    private static void aimAt(LocalPlayer player, LivingEntity target, float velocity) {
        Vec3 eye = player.getEyePosition();

        // Bewegung des Ziels vorhersagen (Flugzeit grob: Entfernung / Pfeilgeschwindigkeit)
        double distance = player.distanceTo(target);
        double flightTicks = distance / Math.max(0.5D, velocity * 3.0D);
        double targetX = target.getX() + (target.getX() - target.xo) * flightTicks;
        double targetY = target.getY() + target.getBbHeight() * 0.5D + (target.getY() - target.yo) * flightTicks;
        double targetZ = target.getZ() + (target.getZ() - target.zo) * flightTicks;

        double dx = targetX - eye.x;
        double dy = targetY - eye.y;
        double dz = targetZ - eye.z;
        double horizontalSq = dx * dx + dz * dz;
        double horizontal = Math.sqrt(horizontalSq);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;

        double v2 = velocity * velocity;
        double root = v2 * v2 - GRAVITY * (GRAVITY * horizontalSq + 2.0D * dy * v2);
        float pitch;
        if (root < 0.0D || horizontal < 1.0E-4D) {
            // Ziel ausser Reichweite der Flugbahn: direkt anvisieren
            pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        } else {
            pitch = (float) -Math.toDegrees(Math.atan((v2 - Math.sqrt(root)) / (GRAVITY * horizontal)));
        }

        player.setYRot(yaw);
        player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
    }
}
