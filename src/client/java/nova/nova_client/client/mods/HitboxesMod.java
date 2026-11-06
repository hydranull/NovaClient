package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.HitboxesMenu;
import nova.nova_client.client.ModCategory;

import java.util.Properties;

/**
 * Hitboxes: zeigt die Hitbox wie mit F3+B, aber nur bei Spielern - Kasten, Linie auf Augenhoehe und ein Strich in
 * Blickrichtung. Alles einstellbar: Farben, Deckkraft, Fuellung, Linienstaerke, Glow, Teamfarben,
 * durch Waende.
 */
public class HitboxesMod extends GameTickMod {
    // Einstellungen: Farben (RGB)
    public static int boxColor = 0xFFFFFF;
    public static int eyeColor = 0xFF3B3B;
    public static int lookColor = 0x3B7BFF;
    // Einstellungen: Aussehen
    /** Deckkraft der Linien (0 - 100). */
    public static int opacity = 100;
    /** Deckkraft der Fuellung (0 - 100, 0 = keine). */
    public static int fillOpacity = 0;
    /** Linienstaerke in Pixeln. */
    public static float lineWidth = 1.5F;
    /** Staerke des Leuchtens (0 - 100, 0 = aus). */
    public static int glow = 0;
    /** Breite des Leuchtens in Pixeln. */
    public static int glowSize = 6;
    // Einstellungen: Schalter
    public static boolean throughWalls = false;
    public static boolean showEye = true;
    public static boolean showLook = true;
    public static boolean showSelf = true;
    public static boolean teamColors = false;

    public static final float MIN_WIDTH = 0.5F;
    public static final float MAX_WIDTH = 5.0F;
    public static final int MIN_GLOW_SIZE = 1;
    public static final int MAX_GLOW_SIZE = 12;

    private static volatile boolean enabled;

    public HitboxesMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        HitboxesMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static int argb(int rgb, float alpha) {
        return (Mth.clamp(Math.round(alpha * 255.0F), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    private static void apply(GizmoProperties properties) {
        if (throughWalls) {
            properties.setAlwaysOnTop();
        }
    }

    /** Farbe des Kastens fuer diesen Spieler (Teamfarbe oder eingestellt). */
    private static int boxColorFor(Player player) {
        if (teamColors && player.getTeam() != null && player.getTeamColor() != 0xFFFFFF) {
            return player.getTeamColor();
        }
        return boxColor;
    }

    /** Hitboxen; wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null || client.player == null) {
            return;
        }
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        boolean thirdPerson = client.gameRenderer.mainCamera().isDetached();
        float lineAlpha = opacity / 100.0F;

        for (Player player : client.level.players()) {
            if (player.isSpectator() || player.isInvisible()
                    || player == client.player && (!thirdPerson || !showSelf)) {
                continue;
            }
            Vec3 feet = player.getPosition(partialTick);
            AABB box = player.getBoundingBox().move(feet.subtract(player.position()));
            int rgb = boxColorFor(player);

            // Leuchten: zwei breite, durchsichtige Schichten hinter der Linie
            if (glow > 0) {
                float strength = glow / 100.0F;
                apply(Gizmos.cuboid(box, GizmoStyle.stroke(argb(rgb, lineAlpha * strength * 0.18F), lineWidth + glowSize)));
                apply(Gizmos.cuboid(box, GizmoStyle.stroke(argb(rgb, lineAlpha * strength * 0.35F), lineWidth + glowSize * 0.45F)));
            }
            if (fillOpacity > 0) {
                apply(Gizmos.cuboid(box, GizmoStyle.fill(argb(rgb, fillOpacity / 100.0F * 0.6F))));
            }
            if (lineAlpha > 0.0F) {
                apply(Gizmos.cuboid(box, GizmoStyle.stroke(argb(rgb, lineAlpha), lineWidth)));
            }

            double eyeY = feet.y + player.getEyeHeight();
            if (showEye && lineAlpha > 0.0F) {
                apply(Gizmos.rect(new Vec3(box.minX, eyeY, box.minZ), new Vec3(box.maxX, eyeY, box.maxZ),
                        Direction.UP, GizmoStyle.stroke(argb(eyeColor, lineAlpha), lineWidth)));
            }
            if (showLook && lineAlpha > 0.0F) {
                Vec3 eye = new Vec3(feet.x, eyeY, feet.z);
                Vec3 look = player.getViewVector(partialTick).scale(2.0D);
                if (glow > 0) {
                    apply(Gizmos.line(eye, eye.add(look), argb(lookColor, lineAlpha * glow / 100.0F * 0.3F),
                            lineWidth + 1.0F + glowSize * 0.6F));
                }
                apply(Gizmos.line(eye, eye.add(look), argb(lookColor, lineAlpha), lineWidth + 0.5F));
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
        return new HitboxesMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "boxColor", String.valueOf(boxColor));
        properties.setProperty(prefix + "eyeColor", String.valueOf(eyeColor));
        properties.setProperty(prefix + "lookColor", String.valueOf(lookColor));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
        properties.setProperty(prefix + "fillOpacity", String.valueOf(fillOpacity));
        properties.setProperty(prefix + "lineWidth", String.valueOf(lineWidth));
        properties.setProperty(prefix + "glow", String.valueOf(glow));
        properties.setProperty(prefix + "glowSize", String.valueOf(glowSize));
        properties.setProperty(prefix + "throughWalls", String.valueOf(throughWalls));
        properties.setProperty(prefix + "showEye", String.valueOf(showEye));
        properties.setProperty(prefix + "showLook", String.valueOf(showLook));
        properties.setProperty(prefix + "showSelf", String.valueOf(showSelf));
        properties.setProperty(prefix + "teamColors", String.valueOf(teamColors));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        boxColor = color(properties, prefix + "boxColor", boxColor);
        eyeColor = color(properties, prefix + "eyeColor", eyeColor);
        lookColor = color(properties, prefix + "lookColor", lookColor);
        opacity = Mth.clamp(integer(properties, prefix + "opacity", opacity), 0, 100);
        fillOpacity = Mth.clamp(integer(properties, prefix + "fillOpacity", fillOpacity), 0, 100);
        glow = Mth.clamp(integer(properties, prefix + "glow", glow), 0, 100);
        glowSize = Mth.clamp(integer(properties, prefix + "glowSize", glowSize), MIN_GLOW_SIZE, MAX_GLOW_SIZE);
        try {
            lineWidth = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "lineWidth", String.valueOf(lineWidth)).trim()),
                    MIN_WIDTH, MAX_WIDTH);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        throughWalls = bool(properties, prefix + "throughWalls", throughWalls);
        showEye = bool(properties, prefix + "showEye", showEye);
        showLook = bool(properties, prefix + "showLook", showLook);
        showSelf = bool(properties, prefix + "showSelf", showSelf);
        teamColors = bool(properties, prefix + "teamColors", teamColors);
    }

    private static int color(Properties properties, String key, int fallback) {
        return integer(properties, key, fallback) & 0xFFFFFF;
    }

    private static int integer(Properties properties, String key, int fallback) {
        try {
            String value = properties.getProperty(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
