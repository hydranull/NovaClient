package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.MotionBlur;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Motion Blur: "Smooth" (Standard) zieht beim Drehen eine weiche Spur, "Per-pixel" verwischt jeden Pixel entlang
 * seiner eigenen Bewegung wie in Videoschnitt-Programmen. Gerechnet und gezeichnet wird in {@link MotionBlur}, hier
 * stehen nur die Einstellungen.
 */
public class MotionBlurMod extends GameTickMod {
    public static final String[] MODES = {"Smooth", "Per-pixel"};
    public static final int MODE_SMOOTH = 0;
    public static final String[] QUALITIES = {"Low", "Medium", "High", "Ultra"};
    private static final int[] SAMPLES = {8, 16, 28, 48};

    public static int mode = MODE_SMOOTH;
    /** Smooth: wie viel vom letzten Bild bei 60 FPS stehen bleibt (Prozent). */
    public static int smoothStrength = 50;
    /** Per-pixel: Staerke (1 = so viel, wie der Verschlusswinkel vorgibt). */
    public static float strength = 1.0F;
    /** Verschlusswinkel in Grad wie bei einer Kamera: 180 = klassischer Film-Look, 360 = maximal. */
    public static float shutterAngle = 180.0F;
    public static int quality = 1;
    /** Gleicher Look bei 30, 60 oder 240 FPS (sonst: je hoeher die FPS, desto weniger Unschaerfe - wie eine echte Kamera). */
    public static boolean fpsIndependent = true;
    /** Laengste Unschaerfe in Prozent der Bildbreite (Teleporte, sehr schnelle Schwenks). */
    public static float maxBlur = 8.0F;

    private static volatile boolean enabled;

    public MotionBlurMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int samples() {
        return SAMPLES[Mth.clamp(quality, 0, SAMPLES.length - 1)];
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        MotionBlurMod.enabled = enabled;
        if (enabled) {
            MotionBlur.retry();
        }
    }

    @Override
    public void execute(Minecraft client) {
        // alles passiert beim Zeichnen (MotionBlur)
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, "Motion Blur", "Soft trails when you turn", List.of(
                new ModOptionsMenu.Choice("Mode", MODES, () -> mode, v -> {
                    mode = v;
                    MotionBlur.retry();
                }),
                new ModOptionsMenu.Slider("Smooth strength", 10.0D, 90.0D, 5.0D, () -> smoothStrength,
                        v -> smoothStrength = (int) Math.round(v), v -> Math.round(v) + "%"),
                new ModOptionsMenu.Slider("Per-pixel strength", 0.1D, 2.0D, 0.05D, () -> strength,
                        v -> strength = (float) v, v -> Math.round(v * 100.0D) + "%"),
                new ModOptionsMenu.Slider("Shutter angle", 45.0D, 360.0D, 15.0D, () -> shutterAngle,
                        v -> shutterAngle = (float) v, v -> Math.round(v) + "°"),
                new ModOptionsMenu.Choice("Quality", QUALITIES, () -> quality, v -> quality = v),
                new ModOptionsMenu.Toggle("Same look at any FPS", () -> fpsIndependent, v -> fpsIndependent = v),
                new ModOptionsMenu.Slider("Max blur length", 2.0D, 20.0D, 1.0D, () -> maxBlur,
                        v -> maxBlur = (float) v, v -> Math.round(v) + "% of screen"),
                new ModOptionsMenu.Info(() -> MotionBlur.failed() ? "Shader failed on this GPU - see log (toggle to retry)"
                        : mode == MODE_SMOOTH ? "Smooth: same trail at any FPS · hand and HUD stay sharp"
                        : String.format(Locale.ROOT, "%d samples per pixel · hand and HUD stay sharp", samples()))));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "mode", String.valueOf(mode));
        properties.setProperty(prefix + "smoothStrength", String.valueOf(smoothStrength));
        properties.setProperty(prefix + "strength", String.valueOf(strength));
        properties.setProperty(prefix + "shutterAngle", String.valueOf(shutterAngle));
        properties.setProperty(prefix + "quality", String.valueOf(quality));
        properties.setProperty(prefix + "fpsIndependent", String.valueOf(fpsIndependent));
        properties.setProperty(prefix + "maxBlur", String.valueOf(maxBlur));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            mode = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "mode", String.valueOf(mode)).trim()), 0, MODES.length - 1);
            smoothStrength = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "smoothStrength", String.valueOf(smoothStrength)).trim()), 10, 90);
            strength = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "strength", String.valueOf(strength)).trim()), 0.1F, 2.0F);
            shutterAngle = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "shutterAngle", String.valueOf(shutterAngle)).trim()), 45.0F, 360.0F);
            quality = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "quality", String.valueOf(quality)).trim()), 0, QUALITIES.length - 1);
            maxBlur = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "maxBlur", String.valueOf(maxBlur)).trim()), 2.0F, 20.0F);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        fpsIndependent = Boolean.parseBoolean(properties.getProperty(prefix + "fpsIndependent", String.valueOf(fpsIndependent)).trim());
    }
}
