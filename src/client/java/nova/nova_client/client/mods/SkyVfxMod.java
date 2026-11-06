package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.SkyImages;
import nova.nova_client.client.SkyStyle;
import nova.nova_client.client.SkyVfxMenu;

import java.util.Locale;
import java.util.Properties;

/**
 * Ersetzt den Himmel der Oberwelt durch einen animierten Nova-Himmel (Black Hole, Ringed Giant, Dusk, Moonlit),
 * gezeichnet im Shader rundherum (auch unter dem Horizont), und blendet die Wolken aus. Welcher Himmel, ist im
 * Einstellungs-Menue waehlbar (unabhaengig vom Theme). In jeden Himmel lassen sich eigene PNGs aus
 * .minecraft/nova_client/sky setzen (siehe {@link SkyImages}, {@link nova.nova_client.client.SkyImageEditor}). Nur
 * clientseitig.
 */
public class SkyVfxMod extends GameTickMod {
    public static final float MIN_SPEED = 0.25F;
    public static final float MAX_SPEED = 3.0F;

    public static SkyStyle style = SkyStyle.BLACK_HOLE;
    /** Geschwindigkeit der Animation (1 = wie im Menue). */
    public static float speed = 1.0F;
    public static final float MIN_BRIGHTNESS = 0.4F;
    public static final float MAX_BRIGHTNESS = 1.4F;
    /** Helligkeit je Himmel (1 = normal). */
    private static final java.util.Map<SkyStyle, Float> BRIGHTNESS = new java.util.EnumMap<>(SkyStyle.class);

    // volatile Flag: wird jeden Frame vom Himmel-, Nebel- und Wolken-Mixin abgefragt
    private static volatile boolean enabled;

    public SkyVfxMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        SkyVfxMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static float brightness(SkyStyle sky) {
        return BRIGHTNESS.getOrDefault(sky, 1.0F);
    }

    public static void setBrightness(SkyStyle sky, float value) {
        BRIGHTNESS.put(sky, Math.round(Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, value)) * 20.0F) / 20.0F);
    }

    public static void setSpeed(float value) {
        speed = Math.round(Math.max(MIN_SPEED, Math.min(MAX_SPEED, value)) * 20.0F) / 20.0F;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new SkyVfxMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "style", style.name());
        properties.setProperty(prefix + "speed", String.format(Locale.ROOT, "%.2f", speed));
        for (SkyStyle sky : SkyStyle.values()) {
            properties.setProperty(prefix + "images." + sky.name().toLowerCase(Locale.ROOT), SkyImages.save(sky));
            properties.setProperty(prefix + "brightness." + sky.name().toLowerCase(Locale.ROOT),
                    String.format(Locale.ROOT, "%.2f", brightness(sky)));
        }
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "style");
        if (value != null) {
            style = SkyStyle.byName(value.trim(), style);
        }
        String speedValue = properties.getProperty(prefix + "speed");
        if (speedValue != null) {
            try {
                setSpeed(Float.parseFloat(speedValue.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
        for (SkyStyle sky : SkyStyle.values()) {
            String images = properties.getProperty(prefix + "images." + sky.name().toLowerCase(Locale.ROOT));
            if (images != null) {
                SkyImages.load(sky, images);
            }
            String light = properties.getProperty(prefix + "brightness." + sky.name().toLowerCase(Locale.ROOT));
            if (light != null) {
                try {
                    setBrightness(sky, Float.parseFloat(light.trim()));
                } catch (NumberFormatException ignored) {
                    // Standard behalten
                }
            }
        }
    }
}
