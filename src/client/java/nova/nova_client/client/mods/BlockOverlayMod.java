package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.mixin.client.GameModeProgressAccessor;

import java.util.List;
import java.util.Properties;

/**
 * Block Overlay: statt des duennen schwarzen Rahmens bekommt der Block unter dem Fadenkreuz einen klaren farbigen
 * Rahmen mit leichter Fuellung. Der Rahmen gleitet weich von Block zu Block, blendet ein und aus, und beim Abbauen
 * waechst die Fuellung von innen mit dem Fortschritt.
 */
public class BlockOverlayMod extends GameTickMod {
    public static final String[] STYLES = {"Outline + fill", "Outline", "Fill"};
    public static final String[] COLORS = {"Accent", "White", "Cyan", "Violet", "Rose", "Lime", "Gold", "Rainbow"};
    private static final int[] COLOR_VALUES = {0, 0xFFFFFF, 0x5CE1FF, 0x8E4BFF, 0xFF5C8A, 0x7CFF6B, 0xFFC94D, 0};
    private static final int RAINBOW = 7;
    private static final double INFLATE = 0.002D;

    public static int style = 0;
    public static int color = 0;
    /** Deckkraft der Fuellung in Prozent. */
    public static int fillOpacity = 16;
    public static double lineWidth = 2.0D;
    public static boolean glow = true;
    public static boolean smooth = true;
    public static boolean miningProgress = true;
    public static boolean seeThrough = false;

    private static volatile boolean enabled;
    private static AABB shown;
    private static float visible;
    private static float progress;
    private static long lastNanos;

    public BlockOverlayMod(String name, ModCategory category) {
        super(name, category);
    }

    /** Vanilla-Rahmen weglassen (LevelExtractorMixin). */
    public static boolean hidesVanillaOutline() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        BlockOverlayMod.enabled = enabled;
        shown = null;
        visible = 0.0F;
    }

    @Override
    public void execute(Minecraft client) {
        // gezeichnet wird pro Frame (emitGizmos)
    }

    /** Jeden Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.level == null || client.player == null) {
            return;
        }
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Math.min(0.1F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;

        AABB target = target(client);
        if (target != null) {
            float k = smooth && shown != null && visible > 0.05F ? 1.0F - (float) Math.exp(-18.0F * dt) : 1.0F;
            shown = shown == null ? target : lerp(shown, target, k);
        }
        visible = NovaGui.approach(visible, target != null ? 1.0F : 0.0F, target != null ? 14.0F : 9.0F, dt);
        boolean mining = miningProgress && target != null && client.gameMode != null && client.gameMode.isDestroying();
        float wanted = mining ? ((GameModeProgressAccessor) client.gameMode).nova_client$destroyProgress() : 0.0F;
        progress = wanted < progress ? wanted : progress + (wanted - progress) * Math.min(1.0F, dt * 20.0F);
        if (shown == null || visible <= 0.01F) {
            return;
        }

        int rgb = rgb();
        float a = visible;
        int line = NovaGui.alpha(0xFF000000 | rgb, a);
        int fill = NovaGui.alpha(0xFF000000 | rgb, a * fillOpacity / 100.0F);
        float width = (float) lineWidth;
        if (glow && style != 2) {
            draw(shown, GizmoStyle.stroke(NovaGui.alpha(0xFF000000 | rgb, a * 0.22F), width * 3.0F));
        }
        switch (style) {
            case 1 -> draw(shown, GizmoStyle.stroke(line, width));
            case 2 -> draw(shown, GizmoStyle.fill(NovaGui.alpha(0xFF000000 | rgb, a * Math.max(0.2F, fillOpacity / 100.0F))));
            default -> draw(shown, GizmoStyle.strokeAndFill(line, width, fill));
        }
        if (progress > 0.01F) {
            AABB inner = scale(shown, Mth.clamp(progress, 0.0F, 1.0F));
            draw(inner, GizmoStyle.fill(NovaGui.alpha(0xFF000000 | rgb, a * 0.35F)));
        }
    }

    private static void draw(AABB box, GizmoStyle gizmoStyle) {
        GizmoProperties properties = Gizmos.cuboid(box, gizmoStyle);
        if (seeThrough) {
            properties.setAlwaysOnTop();
        }
    }

    /** Umriss des Blocks unter dem Fadenkreuz (wie Vanilla), oder null. */
    private static AABB target(Minecraft client) {
        if (client.player.isSpectator()
                || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || !client.level.getWorldBorder().isWithinBounds(pos)) {
            return null;
        }
        VoxelShape shape = state.getShape(client.level, pos, CollisionContext.of(client.player));
        return shape.isEmpty() ? null : shape.bounds().move(pos).inflate(INFLATE);
    }

    /** Farbe der Umrandung (RGB), auch fuer die Vorschau im Nova-Menue. */
    public static int rgb() {
        if (color == RAINBOW) {
            float hue = (System.currentTimeMillis() % 6000L) / 6000.0F;
            return java.awt.Color.HSBtoRGB(hue, 0.55F, 1.0F) & 0xFFFFFF;
        }
        return color == 0 ? NovaGui.BRAND & 0xFFFFFF : COLOR_VALUES[color];
    }

    private static AABB lerp(AABB from, AABB to, float k) {
        return new AABB(Mth.lerp(k, from.minX, to.minX), Mth.lerp(k, from.minY, to.minY), Mth.lerp(k, from.minZ, to.minZ),
                Mth.lerp(k, from.maxX, to.maxX), Mth.lerp(k, from.maxY, to.maxY), Mth.lerp(k, from.maxZ, to.maxZ));
    }

    /** Box um ihre Mitte auf factor verkleinert. */
    private static AABB scale(AABB box, float factor) {
        double cx = (box.minX + box.maxX) / 2.0D;
        double cy = (box.minY + box.maxY) / 2.0D;
        double cz = (box.minZ + box.maxZ) / 2.0D;
        double hx = (box.maxX - box.minX) / 2.0D * factor;
        double hy = (box.maxY - box.minY) / 2.0D * factor;
        double hz = (box.maxZ - box.minZ) / 2.0D * factor;
        return new AABB(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Clean animated outline on the block you look at", List.of(
                new ModOptionsMenu.Choice("Style", STYLES, () -> style, v -> style = v),
                new ModOptionsMenu.Choice("Color", COLORS, () -> color, v -> color = v),
                new ModOptionsMenu.Slider("Fill opacity", 0.0D, 60.0D, 2.0D, () -> fillOpacity,
                        v -> fillOpacity = (int) Math.round(v), v -> Math.round(v) + "%"),
                new ModOptionsMenu.Slider("Line width", 1.0D, 5.0D, 0.5D, () -> lineWidth, v -> lineWidth = v,
                        v -> String.format(java.util.Locale.ROOT, "%.1f", v)),
                new ModOptionsMenu.Toggle("Glow", () -> glow, v -> glow = v),
                new ModOptionsMenu.Toggle("Smooth movement", () -> smooth, v -> smooth = v),
                new ModOptionsMenu.Toggle("Mining progress", () -> miningProgress, v -> miningProgress = v),
                new ModOptionsMenu.Toggle("See through walls", () -> seeThrough, v -> seeThrough = v)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "style", String.valueOf(style));
        properties.setProperty(prefix + "color", String.valueOf(color));
        properties.setProperty(prefix + "fillOpacity", String.valueOf(fillOpacity));
        properties.setProperty(prefix + "lineWidth", String.valueOf(lineWidth));
        properties.setProperty(prefix + "glow", String.valueOf(glow));
        properties.setProperty(prefix + "smooth", String.valueOf(smooth));
        properties.setProperty(prefix + "miningProgress", String.valueOf(miningProgress));
        properties.setProperty(prefix + "seeThrough", String.valueOf(seeThrough));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            style = Math.floorMod(Integer.parseInt(properties.getProperty(prefix + "style", String.valueOf(style)).trim()), STYLES.length);
            color = Math.floorMod(Integer.parseInt(properties.getProperty(prefix + "color", String.valueOf(color)).trim()), COLORS.length);
            fillOpacity = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "fillOpacity", String.valueOf(fillOpacity)).trim()), 0, 60);
            lineWidth = Mth.clamp(Double.parseDouble(properties.getProperty(prefix + "lineWidth", String.valueOf(lineWidth)).trim()), 1.0D, 5.0D);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        glow = Boolean.parseBoolean(properties.getProperty(prefix + "glow", String.valueOf(glow)).trim());
        smooth = Boolean.parseBoolean(properties.getProperty(prefix + "smooth", String.valueOf(smooth)).trim());
        miningProgress = Boolean.parseBoolean(properties.getProperty(prefix + "miningProgress", String.valueOf(miningProgress)).trim());
        seeThrough = Boolean.parseBoolean(properties.getProperty(prefix + "seeThrough", String.valueOf(seeThrough)).trim());
    }
}
