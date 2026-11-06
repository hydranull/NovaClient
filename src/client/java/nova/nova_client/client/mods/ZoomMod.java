package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import nova.nova_client.client.Compat;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Zoom: solange die Zoom-Taste gedrueckt ist (Standard C), wird das Sichtfeld kleiner - weich ein- und ausgeblendet.
 * Mit dem Mausrad laesst sich die Staerke waehrenddessen aendern. Die Maus dreht beim Zoomen passend langsamer, die
 * Hand bleibt, wo sie ist (sie hat ihr eigenes, festes Sichtfeld).
 */
public class ZoomMod extends GameTickMod {
    public static final float MIN_ZOOM = 1.5F;
    public static final float MAX_ZOOM = 50.0F;
    /** Ein Mausrad-Schritt veraendert die Staerke um diesen Faktor. */
    private static final float SCROLL_STEP = 1.15F;

    // Einstellungen
    public static int zoomKey = InputConstants.KEY_C;
    public static float strength = 4.0F;
    /** Statt Halten: Taste druecken = an, nochmal = aus. */
    public static boolean toggleMode = false;
    public static boolean scroll = true;
    /** Mit dem Mausrad eingestellte Staerke beim Loslassen vergessen. */
    public static boolean resetScroll = true;
    public static boolean smooth = true;
    public static float animationSpeed = 1.0F;
    public static boolean slowerMouse = true;
    public static boolean cinematic = false;
    public static boolean hideHand = false;

    private static volatile boolean enabled;
    private static boolean zooming;
    private static boolean keyWasDown;
    private static float scrolled = strength;
    /** Aktueller (animierter) Zoom-Faktor, 1 = kein Zoom. */
    private static float current = 1.0F;
    private static long lastFrame;

    public ZoomMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        if (!on) {
            zooming = false;
        }
    }

    @Override
    public void execute(Minecraft client) {
        // Kein Zoom beim Tippen (Chat, Schilder) oder in Menues
        boolean down = client.gui.screen() == null && zoomKey != NO_KEY
                && InputConstants.isKeyDown(zoomKey);
        boolean wasZooming = zooming;
        if (toggleMode) {
            if (down && !keyWasDown) {
                zooming = !zooming;
            }
        } else {
            zooming = down;
        }
        keyWasDown = down;
        if (zooming && !wasZooming && resetScroll) {
            scrolled = strength;
        }
    }

    /** Wird gerade gezoomt (Taste gedrueckt bzw. eingeschaltet)? */
    public static boolean zooming() {
        return enabled && zooming;
    }

    /**
     * Zoom-Faktor fuer diesen Frame (1 = kein Zoom). Animiert im logarithmischen Raum, damit 2x->4x genauso weich
     * wirkt wie 8x->16x.
     */
    public static float factor() {
        long now = Util.getNanos();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.1F, (now - lastFrame) / 1.0E9F);
        lastFrame = now;
        float target = zooming() ? Mth.clamp(scrolled, MIN_ZOOM, MAX_ZOOM) : 1.0F;
        if (!smooth || !enabled) {
            current = enabled ? target : 1.0F;
        } else if (current != target) {
            float k = 1.0F - (float) Math.exp(-dt * 14.0F * animationSpeed);
            float log = Mth.lerp(k, (float) Math.log(current), (float) Math.log(target));
            current = (float) Math.exp(log);
            if (Math.abs(current - target) < 0.002F * target) {
                current = target;
            }
        }
        return current;
    }

    /** Welt-Sichtfeld mit Zoom (die Hand ist nicht betroffen). */
    public static float applyFov(float fov) {
        float f = factor();
        return f == 1.0F ? fov : fov / f;
    }

    /** Mausbewegung beim Zoomen entsprechend langsamer (gleiches Gefuehl wie ohne Zoom). */
    public static double mouseScale() {
        return enabled && slowerMouse && current > 1.0F ? 1.0D / current : 1.0D;
    }

    /** Weiche Kamera (wie F8) waehrend des Zooms. */
    public static boolean cinematic() {
        return cinematic && zooming();
    }

    /** Hand waehrend des Zooms ausblenden. */
    public static boolean hidesHand() {
        return hideHand && enabled && current > 1.01F;
    }

    /** Mausrad im Spiel: beim Zoomen die Staerke aendern statt den Hotbar-Platz. true = verbraucht. */
    public static boolean onScroll(double amount) {
        if (!scroll || !zooming() || amount == 0.0D) {
            return false;
        }
        scrolled = Mth.clamp(amount > 0.0D ? scrolled * SCROLL_STEP : scrolled / SCROLL_STEP, MIN_ZOOM, MAX_ZOOM);
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Compat.actionBar(client.player, Component.literal("Zoom " + format(scrolled)));
        }
        return true;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1fx", value);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.KeyBind("Zoom key", () -> zoomKey, v -> zoomKey = v),
                new ModOptionsMenu.Slider("Zoom strength", MIN_ZOOM, 20.0D, 0.5D, () -> strength, v -> {
                    strength = (float) v;
                    scrolled = strength;
                }, ZoomMod::format),
                new ModOptionsMenu.Toggle("Toggle instead of hold", () -> toggleMode, v -> toggleMode = v),
                new ModOptionsMenu.Toggle("Mouse wheel changes zoom", () -> scroll, v -> scroll = v),
                new ModOptionsMenu.Toggle("Reset wheel zoom on release", () -> resetScroll, v -> resetScroll = v),
                new ModOptionsMenu.Toggle("Smooth animation", () -> smooth, v -> smooth = v),
                new ModOptionsMenu.Slider("Animation speed", 0.25D, 3.0D, 0.25D, () -> animationSpeed,
                        v -> animationSpeed = (float) v, ZoomMod::format),
                new ModOptionsMenu.Toggle("Slower mouse while zoomed", () -> slowerMouse, v -> slowerMouse = v),
                new ModOptionsMenu.Toggle("Cinematic camera while zoomed", () -> cinematic, v -> cinematic = v),
                new ModOptionsMenu.Toggle("Hide hand while zoomed", () -> hideHand, v -> hideHand = v)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "key", String.valueOf(zoomKey));
        properties.setProperty(prefix + "strength", String.valueOf(strength));
        properties.setProperty(prefix + "toggle", String.valueOf(toggleMode));
        properties.setProperty(prefix + "scroll", String.valueOf(scroll));
        properties.setProperty(prefix + "resetScroll", String.valueOf(resetScroll));
        properties.setProperty(prefix + "smooth", String.valueOf(smooth));
        properties.setProperty(prefix + "animationSpeed", String.valueOf(animationSpeed));
        properties.setProperty(prefix + "slowerMouse", String.valueOf(slowerMouse));
        properties.setProperty(prefix + "cinematic", String.valueOf(cinematic));
        properties.setProperty(prefix + "hideHand", String.valueOf(hideHand));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            zoomKey = Integer.parseInt(properties.getProperty(prefix + "key", String.valueOf(zoomKey)).trim());
            strength = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "strength", String.valueOf(strength)).trim()),
                    MIN_ZOOM, MAX_ZOOM);
            animationSpeed = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "animationSpeed",
                    String.valueOf(animationSpeed)).trim()), 0.25F, 3.0F);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        scrolled = strength;
        toggleMode = Boolean.parseBoolean(properties.getProperty(prefix + "toggle", String.valueOf(toggleMode)));
        scroll = Boolean.parseBoolean(properties.getProperty(prefix + "scroll", String.valueOf(scroll)));
        resetScroll = Boolean.parseBoolean(properties.getProperty(prefix + "resetScroll", String.valueOf(resetScroll)));
        smooth = Boolean.parseBoolean(properties.getProperty(prefix + "smooth", String.valueOf(smooth)));
        slowerMouse = Boolean.parseBoolean(properties.getProperty(prefix + "slowerMouse", String.valueOf(slowerMouse)));
        cinematic = Boolean.parseBoolean(properties.getProperty(prefix + "cinematic", String.valueOf(cinematic)));
        hideHand = Boolean.parseBoolean(properties.getProperty(prefix + "hideHand", String.valueOf(hideHand)));
    }
}
