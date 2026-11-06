package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.KillAuraMenu;
import nova.nova_client.client.ModCategory;

import java.util.Comparator;
import java.util.Locale;
import java.util.Properties;

/**
 * KillAura: greift automatisch das beste Ziel in Reichweite an, sobald der Angriff aufgeladen ist.
 * Ziele (Spieler / Monster / Tiere), Reichweite, Blickwinkel (FOV), Prioritaet, Sichtlinie und
 * Drehen zum Ziel sind einstellbar. */
public class KillAuraMod extends GameTickMod {
    public enum Priority {
        DISTANCE("Distance"), HEALTH("Health"), ANGLE("Angle");

        public final String label;

        Priority(String label) {
            this.label = label;
        }
    }

    // Einstellungen
    /** Reichweite in Bloecken; ueber 3 lehnen Server Schlaege meist ab (ausser zusammen mit Reach). */
    public static float range = 3.0F;
    /** Nur Ziele innerhalb dieses Blickwinkels (Grad, 360 = rundherum). */
    public static float fov = 360.0F;
    /** Angriff erst ab dieser Aufladung (0.5 - 1.0), 1.0 = voller Schaden. */
    public static float cooldown = 0.95F;
    /** Drehgeschwindigkeit in Grad pro Tick, 180 = sofort. */
    public static float rotationSpeed = 60.0F;
    public static Priority priority = Priority.DISTANCE;
    public static boolean players = true;
    public static boolean hostiles = true;
    public static boolean passives = false;
    public static boolean invisibles = false;
    public static boolean throughWalls = false;
    public static boolean weaponOnly = false;
    /** Kamera zum Ziel drehen (sichtbar); aus = nur schlagen. */
    public static boolean rotate = true;
    /** Spieler im eigenen Team auslassen. */
    public static boolean ignoreTeam = true;

    public KillAuraMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null || client.gui.screen() != null
                || player.isSpectator() || player.isUsingItem() || player.isDeadOrDying()) {
            return;
        }
        if (weaponOnly && !player.getMainHandItem().has(DataComponents.WEAPON)) {
            return;
        }

        LivingEntity target = findTarget(client, player);
        if (target == null) {
            return;
        }
        if (rotate) {
            turnTowards(player, target);
        }
        if (player.getAttackStrengthScale(0.5F) >= cooldown) {
            client.gameMode.attack(player, target);
            Compat.swing(player, InteractionHand.MAIN_HAND);
        }
    }

    private static LivingEntity findTarget(Minecraft client, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Comparator<LivingEntity> order = switch (priority) {
            case HEALTH -> Comparator.comparingDouble(e -> e.getHealth() + e.getAbsorptionAmount());
            case ANGLE -> Comparator.comparingDouble(e -> angleTo(player, e));
            default -> Comparator.comparingDouble(e -> e.distanceToSqr(eye));
        };
        LivingEntity best = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !isValid(player, living, eye)) {
                continue;
            }
            if (best == null || order.compare(living, best) < 0) {
                best = living;
            }
        }
        return best;
    }

    private static boolean isValid(LocalPlayer player, LivingEntity entity, Vec3 eye) {
        if (entity == player || !entity.isAlive() || entity.isDeadOrDying() || entity instanceof ArmorStand) {
            return false;
        }
        if (entity instanceof Player other) {
            if (!players || other.isSpectator() || other.isCreative() || ignoreTeam && player.isAlliedTo(other)) {
                return false;
            }
        } else if (entity instanceof Enemy) {
            if (!hostiles) {
                return false;
            }
        } else if (!passives) {
            return false;
        }
        if (!invisibles && entity.isInvisible()) {
            return false;
        }
        // Naechster Punkt der Hitbox zaehlt (wie beim echten Schlagen)
        Vec3 closest = new Vec3(
                Mth.clamp(eye.x, entity.getBoundingBox().minX, entity.getBoundingBox().maxX),
                Mth.clamp(eye.y, entity.getBoundingBox().minY, entity.getBoundingBox().maxY),
                Mth.clamp(eye.z, entity.getBoundingBox().minZ, entity.getBoundingBox().maxZ));
        if (closest.distanceToSqr(eye) > range * range) {
            return false;
        }
        if (fov < 360.0F && angleTo(player, entity) > fov / 2.0F) {
            return false;
        }
        return throughWalls || player.hasLineOfSight(entity);
    }

    /** Winkel zwischen Blickrichtung und Richtung zum Ziel (Grad). */
    private static double angleTo(LocalPlayer player, Entity entity) {
        Vec3 look = player.getViewVector(1.0F);
        Vec3 to = entity.getBoundingBox().getCenter().subtract(player.getEyePosition()).normalize();
        return Math.toDegrees(Math.acos(Mth.clamp(look.dot(to), -1.0D, 1.0D)));
    }

    /** Kamera schrittweise (rotationSpeed Grad pro Tick) zum Ziel drehen. */
    private static void turnTowards(LocalPlayer player, Entity target) {
        Vec3 eye = player.getEyePosition();
        Vec3 aim = target.getBoundingBox().getCenter();
        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        float wantedYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float wantedPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        float yawDiff = Mth.wrapDegrees(wantedYaw - player.getYRot());
        float pitchDiff = wantedPitch - player.getXRot();
        float step = rotationSpeed;
        player.setYRot(player.getYRot() + Mth.clamp(yawDiff, -step, step));
        player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(pitchDiff, -step, step), -90.0F, 90.0F));
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new KillAuraMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "range", format(range));
        properties.setProperty(prefix + "fov", format(fov));
        properties.setProperty(prefix + "cooldown", format(cooldown));
        properties.setProperty(prefix + "rotationSpeed", format(rotationSpeed));
        properties.setProperty(prefix + "priority", priority.name());
        properties.setProperty(prefix + "players", String.valueOf(players));
        properties.setProperty(prefix + "hostiles", String.valueOf(hostiles));
        properties.setProperty(prefix + "passives", String.valueOf(passives));
        properties.setProperty(prefix + "invisibles", String.valueOf(invisibles));
        properties.setProperty(prefix + "throughWalls", String.valueOf(throughWalls));
        properties.setProperty(prefix + "weaponOnly", String.valueOf(weaponOnly));
        properties.setProperty(prefix + "rotate", String.valueOf(rotate));
        properties.setProperty(prefix + "ignoreTeam", String.valueOf(ignoreTeam));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        range = number(properties, prefix + "range", range, 2.0F, 6.0F);
        fov = number(properties, prefix + "fov", fov, 30.0F, 360.0F);
        cooldown = number(properties, prefix + "cooldown", cooldown, 0.5F, 1.0F);
        rotationSpeed = number(properties, prefix + "rotationSpeed", rotationSpeed, 5.0F, 180.0F);
        try {
            priority = Priority.valueOf(properties.getProperty(prefix + "priority", priority.name()));
        } catch (IllegalArgumentException ignored) {
            // Standard behalten
        }
        players = bool(properties, prefix + "players", players);
        hostiles = bool(properties, prefix + "hostiles", hostiles);
        passives = bool(properties, prefix + "passives", passives);
        invisibles = bool(properties, prefix + "invisibles", invisibles);
        throughWalls = bool(properties, prefix + "throughWalls", throughWalls);
        weaponOnly = bool(properties, prefix + "weaponOnly", weaponOnly);
        rotate = bool(properties, prefix + "rotate", rotate);
        ignoreTeam = bool(properties, prefix + "ignoreTeam", ignoreTeam);
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static float number(Properties properties, String key, float fallback, float min, float max) {
        try {
            return Mth.clamp(Float.parseFloat(properties.getProperty(key, String.valueOf(fallback)).trim()), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)));
    }
}
