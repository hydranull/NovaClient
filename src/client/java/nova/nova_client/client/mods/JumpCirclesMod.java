package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaFx;
import nova.nova_client.client.NovaFxMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Jump Circles: ein schlichter Kreis im Nova-Stil. Beim Abspringen und Landen breitet sich unter den Fuessen ein
 * runder Ring aus - eine feine helle Linie mit weichem Schein in der Nova-Farbe - und blendet sanft aus. Beim Landen
 * etwas groesser, je nach Fallhoehe. Farbe (Nova, Rainbow, Custom) und Groesse sind einstellbar.
 */
public class JumpCirclesMod extends GameTickMod {
    private static final long LIFE = 900L;
    /** Teilstuecke des Kreises - genug, dass er auch gross rund aussieht. */
    private static final int SEGMENTS = 72;

    // Einstellungen
    public static NovaFx.ColorMode colorMode = NovaFx.ColorMode.NOVA;
    public static float hue = 0.5F;
    /** Groesse des Kreises (0.5 - 2). */
    public static float size = 1.0F;

    /** Ein Kreis: Mitte am Boden, Endradius, Startzeit. */
    private record Ring(Vec3 pos, double radius, long born) {
    }

    private static final List<Ring> RINGS = new ArrayList<>();

    private boolean wasOnGround = true;
    private double fallSpeed;

    public JumpCirclesMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        RINGS.clear();
        wasOnGround = true;
    }

    public static boolean hasRings() {
        return !RINGS.isEmpty();
    }

    /** Jeden Tick: Absprung und Landung erkennen. */
    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.isSpectator()) {
            return;
        }
        boolean onGround = player.onGround();
        boolean flying = player.getAbilities().flying || player.isFallFlying() || player.isPassenger()
                || player.isInWater();
        if (!flying) {
            if (wasOnGround && !onGround && player.getDeltaMovement().y > 0.0D) {
                RINGS.add(new Ring(player.position(), 0.75D * size, System.currentTimeMillis()));
            } else if (!wasOnGround && onGround) {
                // Groesse nach Fallgeschwindigkeit kurz vor dem Aufprall
                double strength = Math.min(1.6D, 0.85D + fallSpeed * 0.6D);
                RINGS.add(new Ring(player.position(), 1.15D * strength * size, System.currentTimeMillis()));
            }
        }
        fallSpeed = Math.max(0.0D, -player.getDeltaMovement().y);
        wasOnGround = onGround;
    }

    // ---------------------------------------------------------------- Zeichnen (jeden Frame, aus NovaFx)

    public static void render(NovaFx.Painter painter) {
        long now = System.currentTimeMillis();
        float time = (now % 100_000L) / 1000.0F;
        RINGS.removeIf(ring -> now - ring.born() > LIFE);
        for (Ring ring : RINGS) {
            float t = (now - ring.born()) / (float) LIFE;
            // schnell auf, langsam aus
            float grow = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
            double radius = ring.radius() * (0.18D + 0.82D * grow);
            float alpha = (1.0F - t) * (1.0F - t);
            int color = NovaFx.color(colorMode, hue, time * 0.1F);
            double y = ring.pos().y + 0.02D;
            double glow = 0.16D * Math.max(0.6D, ring.radius());
            // weicher Schein innen und aussen, darin eine feine helle Linie
            band(painter, ring.pos(), y, radius - glow, radius, NovaFx.argb(color, 0.0F), NovaFx.argb(color, alpha * 0.3F));
            band(painter, ring.pos(), y, radius, radius + glow, NovaFx.argb(color, alpha * 0.3F), NovaFx.argb(color, 0.0F));
            int line = NovaFx.argb(NovaFx.mix(color, 0xFFFFFF, 0.35F), alpha * 0.95F);
            band(painter, ring.pos(), y + 0.001D, radius - 0.018D, radius + 0.018D, line, line);
        }
    }

    /** Flacher Ring zwischen r0 und r1 um center, Farbe innen/aussen (dazwischen weich). */
    private static void band(NovaFx.Painter painter, Vec3 center, double y, double r0, double r1, int inner, int outer) {
        double a0 = Math.max(0.0D, r0);
        for (int i = 0; i < SEGMENTS; i++) {
            double t0 = Math.PI * 2.0D * i / SEGMENTS;
            double t1 = Math.PI * 2.0D * (i + 1) / SEGMENTS;
            double c0 = Math.cos(t0);
            double s0 = Math.sin(t0);
            double c1 = Math.cos(t1);
            double s1 = Math.sin(t1);
            painter.quad(new Vec3(center.x + c0 * a0, y, center.z + s0 * a0),
                    new Vec3(center.x + c1 * a0, y, center.z + s1 * a0),
                    new Vec3(center.x + c1 * r1, y, center.z + s1 * r1),
                    new Vec3(center.x + c0 * r1, y, center.z + s0 * r1), inner, inner, outer, outer);
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new NovaFxMenu(parent, "Jump Circles", "",
                () -> colorMode, mode -> colorMode = mode, () -> hue, value -> hue = value,
                new NovaFxMenu.Slider("Size", 0.5F, 2.0F, 0.1F, () -> size, value -> size = value,
                        value -> String.format(Locale.ROOT, "%.1fx", value)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "color", colorMode.name());
        properties.setProperty(prefix + "hue", String.format(Locale.ROOT, "%.3f", hue));
        properties.setProperty(prefix + "size", String.format(Locale.ROOT, "%.2f", size));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        colorMode = NovaFx.ColorMode.byName(properties.getProperty(prefix + "color", colorMode.name()), colorMode);
        hue = NovaFx.number(properties, prefix + "hue", hue, 0.0F, 1.0F);
        size = NovaFx.number(properties, prefix + "size", size, 0.5F, 2.0F);
    }
}
