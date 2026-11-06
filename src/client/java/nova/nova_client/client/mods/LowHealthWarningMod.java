package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaShapes;

import java.util.List;
import java.util.Properties;

/**
 * Low Health Warning: bei wenig Leben leuchten die Bildschirmraender rot im Herzschlag - je weniger Leben, desto
 * schneller und kraeftiger. Weich ein- und ausgeblendet, die Mitte bleibt frei.
 */
public class LowHealthWarningMod extends RenderMod {
    public static final String[] PULSES = {"Heartbeat", "Breathing", "Steady"};
    public static final String[] COLORS = {"Red", "Accent"};
    private static final int RED = 0xFFFF2D3F;
    private static final float CALM_BPM = 62.0F;
    private static final float PANIC_BPM = 150.0F;

    /** Warnen unter so vielen Lebenspunkten (2 = 1 Herz). */
    public static int below = 8;
    public static boolean absorption = true;
    public static int pulse = 0;
    /** Staerke in Prozent. */
    public static int intensity = 70;
    public static int color = 0;

    private float shown;
    private float danger;
    private float phase;
    private long lastNanos;

    public LowHealthWarningMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Math.min(0.1F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;

        LocalPlayer player = client.player;
        boolean warn = false;
        if (player != null && !player.isDeadOrDying() && !player.isSpectator() && !player.getAbilities().invulnerable) {
            float health = player.getHealth() + (absorption ? player.getAbsorptionAmount() : 0.0F);
            if (health < below) {
                warn = true;
                danger = Mth.clamp((below - health) / below, 0.0F, 1.0F);
            }
        }
        // weich rein (schnell) und raus (langsamer)
        shown = NovaGui.approach(shown, warn ? 1.0F : 0.0F, warn ? 5.0F : 2.5F, dt);
        if (shown <= 0.002F) {
            phase = 0.0F;
            return;
        }
        phase = (phase + dt * bpm(danger) / 60.0F) % 1.0F;
        glow(graphics, graphics.guiWidth(), graphics.guiHeight(), danger, beat(phase), NovaGui.easeOutCubic(shown));
    }

    /** Vorschau im Nova-Menue: ein kleiner Bildschirm von x0..x1 / y0..y1, dessen Raender im eingestellten Takt leuchten. */
    public static void drawPreview(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float t, float a) {
        float danger = 0.6F;
        g.pose().pushMatrix();
        g.pose().translate(x0, y0);
        glow(g, x1 - x0, y1 - y0, danger, beat(t * bpm(danger) / 60.0F % 1.0F), a);
        g.pose().popMatrix();
    }

    /** Schlaege pro Minute: je weniger Leben, desto schneller (Atmen halb so schnell). */
    private static float bpm(float danger) {
        float bpm = CALM_BPM + (PANIC_BPM - CALM_BPM) * danger;
        return pulse == 1 ? bpm * 0.5F : bpm;
    }

    /** Beide Rand-Schichten ueber einen width x height grossen Bereich ab (0, 0); shown = Einblenden. */
    private static void glow(GuiGraphicsExtractor g, int width, int height, float danger, float beat, float shown) {
        int base = color == 1 ? NovaGui.BRAND : RED;
        float strength = intensity / 100.0F * shown * (0.5F + 0.5F * danger);
        int depth = Math.round(Math.min(width, height) * (0.11F + 0.05F * danger) * (1.0F + 0.1F * beat));
        edges(g, width, height, depth, NovaGui.alpha(base, strength * (0.35F + 0.4F * beat)));
        edges(g, width, height, Math.max(2, depth / 4), NovaGui.alpha(base, strength * (0.25F + 0.45F * beat)));
    }

    /** 0..1: Doppelschlag (Herz), ruhiges Atmen oder gleichmaessig. */
    private static float beat(float phase) {
        if (pulse == 2) {
            return 0.4F;
        }
        if (pulse == 1) {
            return 0.5F - 0.5F * Mth.cos(phase * Mth.TWO_PI);
        }
        return Math.min(1.0F, bump(phase, 0.0F) + 0.6F * bump(phase, 0.22F));
    }

    private static float bump(float at, float center) {
        float d = Math.abs(at - center);
        d = Math.min(d, 1.0F - d) / 0.07F;
        return (float) Math.exp(-d * d);
    }

    /** Verlauf von allen vier Raendern nach innen. */
    private static void edges(GuiGraphicsExtractor g, int width, int height, int depth, int color) {
        if (depth < 1 || (color >>> 24) < 2) {
            return;
        }
        int clear = color & 0x00FFFFFF;
        g.fillGradient(0, 0, width, depth, color, clear);
        g.fillGradient(0, height - depth, width, height, clear, color);
        NovaShapes.fade(g, 0, 0, depth, height, NovaShapes.FADE_LEFT, color);
        NovaShapes.fade(g, width - depth, 0, width, height, NovaShapes.FADE_RIGHT, color);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        shown = 0.0F;
        lastNanos = 0L;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Glowing screen edges when your health is low", List.of(
                new ModOptionsMenu.Slider("Warn below", 2.0D, 20.0D, 1.0D, () -> below, v -> below = (int) Math.round(v),
                        v -> String.format(java.util.Locale.ROOT, "%.1f hearts", v / 2.0D)),
                new ModOptionsMenu.Toggle("Count absorption", () -> absorption, v -> absorption = v),
                new ModOptionsMenu.Choice("Pulse", PULSES, () -> pulse, v -> pulse = v),
                new ModOptionsMenu.Choice("Color", COLORS, () -> color, v -> color = v),
                new ModOptionsMenu.Slider("Intensity", 20.0D, 100.0D, 5.0D, () -> intensity,
                        v -> intensity = (int) Math.round(v), v -> Math.round(v) + "%")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "below", String.valueOf(below));
        properties.setProperty(prefix + "absorption", String.valueOf(absorption));
        properties.setProperty(prefix + "pulse", String.valueOf(pulse));
        properties.setProperty(prefix + "intensity", String.valueOf(intensity));
        properties.setProperty(prefix + "color", String.valueOf(color));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            below = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "below", String.valueOf(below)).trim()), 2, 20);
            pulse = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "pulse", String.valueOf(pulse)).trim()), 0, PULSES.length - 1);
            intensity = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "intensity", String.valueOf(intensity)).trim()), 20, 100);
            color = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "color", String.valueOf(color)).trim()), 0, COLORS.length - 1);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        absorption = Boolean.parseBoolean(properties.getProperty(prefix + "absorption", String.valueOf(absorption)).trim());
    }
}
