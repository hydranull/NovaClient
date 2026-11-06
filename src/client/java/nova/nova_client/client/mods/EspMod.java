package nova.nova_client.client.mods;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.EspMenu;
import nova.nova_client.client.ModCategory;
import org.joml.Vector3fc;

import java.util.Properties;

/**
 * Zeigt Spieler und Mobs durch Waende: farbiger Umriss (Glow) und Linien (Tracer) vom Fadenkreuz zum Ziel.
 */
public class EspMod extends GameTickMod {
    public static final int PLAYER_COLOR = 0xFF5470;
    public static final int HOSTILE_COLOR = 0xFFA040;
    public static final int PASSIVE_COLOR = 0x3DDC97;
    /** Ab dieser Entfernung sind Tracer am blassesten (keine Grenze: alles, was der Server schickt, wird gezeigt). */
    private static final double FADE_RANGE = 256.0D;

    // Einstellungen
    public static boolean outline = true;
    public static boolean tracers = true;
    public static boolean players = true;
    public static boolean hostiles = true;
    public static boolean others = true;
    /** Name + Entfernung ueber Spielern (durch Waende, beliebig weit). */
    public static boolean names = true;

    private static volatile boolean enabled;

    public EspMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        EspMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Soll dieses Entity markiert werden (Typ-Filter)? */
    private static boolean isTarget(Entity entity) {
        if (!enabled || !(entity instanceof LivingEntity) || !entity.isAlive()) {
            return false;
        }
        if (entity == Minecraft.getInstance().player) {
            return false;
        }
        if (entity instanceof Player) {
            return players && !entity.isSpectator();
        }
        if (entity instanceof Enemy) {
            return hostiles;
        }
        return others;
    }

    public static boolean shouldGlow(Entity entity) {
        return outline && isTarget(entity);
    }

    public static int color(Entity entity) {
        if (entity instanceof Player) {
            return PLAYER_COLOR;
        }
        if (entity instanceof Enemy) {
            return HOSTILE_COLOR;
        }
        return PASSIVE_COLOR;
    }

    /** Tracer-Linien; wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || !tracers && !names || client.level == null || client.player == null) {
            return;
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = client.gameRenderer.mainCamera();
        Vector3fc forward = camera.forwardVector();
        // Start knapp vor der Kamera, damit die Linie aus dem Fadenkreuz kommt
        Vec3 start = camera.position().add(forward.x() * 0.6D, forward.y() * 0.6D, forward.z() * 0.6D);

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!isTarget(entity)) {
                continue;
            }
            double distanceSqr = client.player.distanceToSqr(entity);
            Vec3 feet = entity.getPosition(partialTick);
            Vec3 target = feet.add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
            // Teamfarbe hat Vorrang, sonst Typ-Farbe
            int rgb = entity.getTeam() != null && entity.getTeamColor() != 0xFFFFFF ? entity.getTeamColor() : color(entity);
            // Nahe Ziele kraeftig, weit entfernte blasser (weniger Durcheinander)
            double distance = Math.sqrt(distanceSqr);
            if (tracers) {
                int alpha = (int) Math.round(235 - Math.min(1.0D, distance / FADE_RANGE) * 155);
                Gizmos.line(start, target, (alpha << 24) | (rgb & 0xFFFFFF), distance < 24.0D ? 2.0F : 1.2F).setAlwaysOnTop();
            }
            if (names && entity instanceof Player player) {
                Vec3 top = feet.add(0.0D, entity.getBbHeight() + 0.5D, 0.0D);
                // Mit der Entfernung mitwachsen, damit der Name auch weit weg lesbar bleibt
                float scale = TextGizmo.Style.DEFAULT_SCALE * (float) Math.max(1.0D, top.distanceTo(camera.position()) / 10.0D);
                Gizmos.billboardText(player.getPlainTextName() + " " + Math.round(distance) + "m", top,
                        TextGizmo.Style.forColorAndCentered(0xFF000000 | rgb).withScale(scale)).setAlwaysOnTop();
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new EspMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "outline", String.valueOf(outline));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "players", String.valueOf(players));
        properties.setProperty(prefix + "hostiles", String.valueOf(hostiles));
        properties.setProperty(prefix + "others", String.valueOf(others));
        properties.setProperty(prefix + "names", String.valueOf(names));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        outline = bool(properties, prefix + "outline", outline);
        tracers = bool(properties, prefix + "tracers", tracers);
        players = bool(properties, prefix + "players", players);
        hostiles = bool(properties, prefix + "hostiles", hostiles);
        others = bool(properties, prefix + "others", others);
        names = bool(properties, prefix + "names", names);
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
