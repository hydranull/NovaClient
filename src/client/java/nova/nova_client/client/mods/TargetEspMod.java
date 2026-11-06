package nova.nova_client.client.mods;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.EspOptionsMenu;
import nova.nova_client.client.ModCategory;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Basis fuer ESPs auf eine bestimmte Entity-Art (Villager, Wandering Trader, Pillager, Item Frames):
 * farbiger Umriss durch Waende, Linie vom Fadenkreuz und Name + Entfernung ueber dem Ziel.
 */
public abstract class TargetEspMod extends GameTickMod {
    private static final List<TargetEspMod> ALL = new ArrayList<>();
    private static final double TRACER_RANGE = 192.0D;

    // Einstellungen (je Mod)
    public boolean outline = true;
    public boolean tracers = true;
    public boolean labels = true;

    private final int color;
    private final GizmoStyle boxStyle;

    protected TargetEspMod(String name, int color) {
        super(name, ModCategory.RENDER);
        this.color = color;
        this.boxStyle = GizmoStyle.strokeAndFill(0xFF000000 | color, 2.0F, 0x30000000 | color);
        ALL.add(this);
    }

    /** Gehoert dieses Entity zu diesem ESP? */
    protected abstract boolean matches(Entity entity);

    /** Text ueber dem Ziel (ohne Entfernung). */
    protected abstract String label(Entity entity);

    /** Zusaetzlich eine Box zeichnen (fuer Entities, bei denen der Glow-Umriss kaum auffaellt). */
    protected boolean drawBox() {
        return false;
    }

    public int color() {
        return color;
    }

    @Override
    public void execute(Minecraft client) {
    }

    // ---------------------------------------------------------------- Abfragen fuer Mixins

    /** Das aktive ESP, das dieses Entity markiert, oder null. */
    public static TargetEspMod find(Entity entity) {
        if (entity == null || !entity.isAlive() || entity == Minecraft.getInstance().player) {
            return null;
        }
        for (TargetEspMod mod : ALL) {
            if (mod.active && mod.matches(entity)) {
                return mod;
            }
        }
        return null;
    }

    public static boolean anyActive() {
        for (TargetEspMod mod : ALL) {
            if (mod.active) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldGlow(Entity entity) {
        TargetEspMod mod = find(entity);
        return mod != null && mod.outline;
    }

    // ---------------------------------------------------------------- Rendering

    /** Linien, Boxen und Namen; wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!anyActive() || client.level == null || client.player == null) {
            return;
        }

        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = client.gameRenderer.mainCamera();
        Vector3fc forward = camera.forwardVector();
        Vec3 cameraPos = camera.position();
        Vec3 start = cameraPos.add(forward.x() * 0.6D, forward.y() * 0.6D, forward.z() * 0.6D);

        for (Entity entity : client.level.entitiesForRendering()) {
            TargetEspMod mod = find(entity);
            if (mod == null) {
                continue;
            }
            double distance = Math.sqrt(client.player.distanceToSqr(entity));
            Vec3 feet = entity.getPosition(partialTick);
            int rgb = mod.color;

            if (mod.outline && mod.drawBox()) {
                Vec3 offset = feet.subtract(entity.position());
                Gizmos.cuboid(entity.getBoundingBox().move(offset).inflate(0.05D), mod.boxStyle).setAlwaysOnTop();
            }

            if (mod.tracers) {
                Vec3 target = feet.add(0.0D, entity.getBbHeight() * 0.5D, 0.0D);
                int alpha = (int) Math.round(235 - Math.min(1.0D, distance / TRACER_RANGE) * 165);
                Gizmos.line(start, target, (alpha << 24) | rgb, distance < 24.0D ? 2.0F : 1.4F).setAlwaysOnTop();
            }

            if (mod.labels) {
                Vec3 top = feet.add(0.0D, entity.getBbHeight() + 0.45D, 0.0D);
                // Mit der Entfernung mitwachsen, damit der Text auch weit weg lesbar bleibt
                float scale = TextGizmo.Style.DEFAULT_SCALE * (float) Math.max(1.0D, top.distanceTo(cameraPos) / 10.0D);
                String text = mod.label(entity) + " " + Math.round(distance) + "m";
                Gizmos.billboardText(text, top, TextGizmo.Style.forColorAndCentered(0xFF000000 | rgb).withScale(scale))
                        .setAlwaysOnTop();
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    /** Schalter im Einstellungs-Menue; Unterklassen koennen weitere anhaengen. */
    protected List<EspOptionsMenu.Toggle> toggles() {
        List<EspOptionsMenu.Toggle> list = new ArrayList<>();
        list.add(new EspOptionsMenu.Toggle("Outline", () -> outline, () -> outline = !outline));
        list.add(new EspOptionsMenu.Toggle("Tracers", () -> tracers, () -> tracers = !tracers));
        list.add(new EspOptionsMenu.Toggle("Name + distance", () -> labels, () -> labels = !labels));
        return list;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new EspOptionsMenu(parent, name, "", color, toggles(), null);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "outline", String.valueOf(outline));
        properties.setProperty(prefix + "tracers", String.valueOf(tracers));
        properties.setProperty(prefix + "labels", String.valueOf(labels));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        outline = bool(properties, prefix + "outline", outline);
        tracers = bool(properties, prefix + "tracers", tracers);
        labels = bool(properties, prefix + "labels", labels);
    }

    protected static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
