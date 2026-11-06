package nova.nova_client.client.mods;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * HandView: verschiebt die Haende (samt gehaltenem Item) in der Ego-Sicht, je Hand auf X (seitlich), Y (hoch/runter)
 * und Z (vor/zurueck). X zaehlt nach aussen - derselbe Wert schiebt beide Haende symmetrisch zur Seite. Greift um den
 * Aufruf, der eine Hand zeichnet ({@link nova.nova_client.mixin.client.HandViewMixin}), wirkt damit auch mit Knives,
 * Hand Glow & Co.
 */
public class HandViewMod extends GameTickMod {
    /** Reglerbereich (in Bloecken der Ego-Sicht). */
    public static final float RANGE = 1.0F;

    public static float mainX;
    public static float mainY;
    public static float mainZ;
    public static float offX;
    public static float offY;
    public static float offZ;

    private static volatile boolean enabled;

    public HandViewMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
    }

    @Override
    public void execute(Minecraft client) {
    }

    /** Vor dem Zeichnen einer Hand (Pose ist schon gesichert): um die eingestellten Werte verschieben. */
    public static void apply(PoseStack pose, InteractionHand hand, HumanoidArm mainArm) {
        boolean main = hand == InteractionHand.MAIN_HAND;
        HumanoidArm arm = main ? mainArm : mainArm.getOpposite();
        float outward = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        float x = main ? mainX : offX;
        float y = main ? mainY : offY;
        float z = main ? mainZ : offZ;
        // Ego-Sicht: -Z zeigt nach vorne
        pose.translate(outward * x, y, -z);
    }

    // ---------------------------------------------------------------- Einstellungen

    private static String format(double value) {
        return String.format(Locale.ROOT, "%+.2f", value);
    }

    private static float clamp(double value) {
        return (float) Math.max(-RANGE, Math.min(RANGE, Math.round(value * 100.0D) / 100.0D));
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Move your hands in first person", List.of(
                new ModOptionsMenu.Slider("Main hand X (outward)", -RANGE, RANGE, 0.01D, () -> mainX, v -> mainX = clamp(v),
                        HandViewMod::format),
                new ModOptionsMenu.Slider("Main hand Y (up)", -RANGE, RANGE, 0.01D, () -> mainY, v -> mainY = clamp(v),
                        HandViewMod::format),
                new ModOptionsMenu.Slider("Main hand Z (forward)", -RANGE, RANGE, 0.01D, () -> mainZ, v -> mainZ = clamp(v),
                        HandViewMod::format),
                new ModOptionsMenu.Slider("Off hand X (outward)", -RANGE, RANGE, 0.01D, () -> offX, v -> offX = clamp(v),
                        HandViewMod::format),
                new ModOptionsMenu.Slider("Off hand Y (up)", -RANGE, RANGE, 0.01D, () -> offY, v -> offY = clamp(v),
                        HandViewMod::format),
                new ModOptionsMenu.Slider("Off hand Z (forward)", -RANGE, RANGE, 0.01D, () -> offZ, v -> offZ = clamp(v),
                        HandViewMod::format),
                new ModOptionsMenu.Action(() -> "Off hand = main hand", () -> {
                    offX = mainX;
                    offY = mainY;
                    offZ = mainZ;
                }),
                new ModOptionsMenu.Action(() -> "Reset", () -> {
                    mainX = mainY = mainZ = 0.0F;
                    offX = offY = offZ = 0.0F;
                }),
                new ModOptionsMenu.Info(() -> "Mouse wheel over a slider = fine steps")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "mainX", String.valueOf(mainX));
        properties.setProperty(prefix + "mainY", String.valueOf(mainY));
        properties.setProperty(prefix + "mainZ", String.valueOf(mainZ));
        properties.setProperty(prefix + "offX", String.valueOf(offX));
        properties.setProperty(prefix + "offY", String.valueOf(offY));
        properties.setProperty(prefix + "offZ", String.valueOf(offZ));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        mainX = read(properties, prefix + "mainX", mainX);
        mainY = read(properties, prefix + "mainY", mainY);
        mainZ = read(properties, prefix + "mainZ", mainZ);
        offX = read(properties, prefix + "offX", offX);
        offY = read(properties, prefix + "offY", offY);
        offZ = read(properties, prefix + "offZ", offZ);
    }

    private static float read(Properties properties, String key, float fallback) {
        try {
            String value = properties.getProperty(key);
            return value == null ? fallback : clamp(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
