package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Util;

import java.util.ArrayDeque;

/**
 * Anti-Vanish-Erscheinung ueber das ganze Bild ({@link CosmicAlert}: Galaxie und Supernova), die Welt wird dabei nicht
 * abgedunkelt. Am Ende steht immer "NAME is here".
 * Haelt die Warteschlange (mehrere Meldungen laufen nacheinander, hoechstens drei warten), spielt das klingelnde
 * Hoehlengeraeusch und stellt gemeinsame Hilfen (Kurven, Zufall, Schrift, Toene) bereit.
 */
public final class VanishAlert {
    /** Eine Erscheinung; t = ms seit Beginn. */
    interface Scene {
        int length();

        void draw(GuiGraphicsExtractor g, Font font, int width, int height, float t, String name, String detail);

        /** Zusaetzliche Toene im Ablauf (z.B. Donner beim Einschlag); previous .. t = seit dem letzten Bild. */
        default void sounds(float previous, float t) {
        }
    }

    static final String SCRAMBLE = "#%&@$?!/<>*+=0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int MAX_QUEUE = 3;
    private static final Identifier RING = Identifier.fromNamespaceAndPath("nova_client", "antivanish.ring");

    private record Alert(String name, String detail, boolean sound) {
    }

    private static final ArrayDeque<Alert> QUEUE = new ArrayDeque<>();
    private static Alert current;
    private static Scene scene;
    private static long start;
    private static float lastT;

    private VanishAlert() {
    }

    /** Erscheinung anmelden; detail = was erkannt wurde (klein unter dem Namen). Doppelte Namen werden uebersprungen. */
    public static void show(String name, String detail, boolean sound) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (current != null && current.name().equalsIgnoreCase(name)) {
            return;
        }
        for (Alert alert : QUEUE) {
            if (alert.name().equalsIgnoreCase(name)) {
                return;
            }
        }
        if (QUEUE.size() < MAX_QUEUE) {
            QUEUE.add(new Alert(name, detail == null ? "" : detail, sound));
        }
    }

    public static void clear() {
        QUEUE.clear();
        current = null;
    }

    /** Nur das klingelnde Hoehlengeraeusch (cave2), ohne Bild. */
    public static void ring() {
        play(SoundEvent.createVariableRangeEvent(RING), 1.0F, 1.0F);
    }

    /** Jedes Bild aus dem HUD (zuletzt, damit es ueber allem liegt). */
    public static void render(GuiGraphicsExtractor g) {
        long now = Util.getMillis();
        if (current != null && now - start >= scene.length()) {
            current = null;
        }
        if (current == null) {
            if (QUEUE.isEmpty()) {
                return;
            }
            current = QUEUE.poll();
            scene = CosmicAlert.SCENE;
            start = now;
            lastT = -1.0F;
            if (current.sound()) {
                ring();
            }
        }
        float t = now - start;
        if (current.sound()) {
            scene.sounds(lastT, t);
        }
        lastT = t;
        Minecraft client = Minecraft.getInstance();
        scene.draw(g, client.font, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight(), t,
                current.name(), current.detail());
    }

    // ---------------------------------------------------------------- Hilfen fuer die Erscheinungen

    static Style font(String id) {
        return Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("nova_client", id)));
    }

    static Component styled(String text, Style style) {
        return Component.literal(text).setStyle(style);
    }

    static int width(Font font, Style style, String text) {
        return font.width(styled(text, style));
    }

    static void text(GuiGraphicsExtractor g, Font font, Style style, String text, int x, int y, int color, boolean shadow) {
        if ((color >>> 24) >= 8) {
            g.text(font, styled(text, style), x, y, color, shadow);
        }
    }

    static void play(SoundEvent sound, float pitch, float volume) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** Zufallszeichen fuer noch nicht entschluesselte Buchstaben. */
    static String scramble(int seed) {
        return String.valueOf(SCRAMBLE.charAt(Math.min(SCRAMBLE.length() - 1, (int) (hash01(seed) * SCRAMBLE.length()))));
    }

    static String spaced(String text) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(text.charAt(i));
        }
        return builder.toString();
    }

    /** Ruckeln: Versatz je Achse, alle 16 ms neu. */
    static float jitter(float t, int axis, float amount) {
        return amount * (hash01((int) (t / 16.0F) * 2 + axis) - 0.5F) * 2.0F;
    }

    static float decay(float since, float ms) {
        return since < 0.0F ? 0.0F : (float) Math.exp(-since / ms);
    }

    static float hash01(int n) {
        n = (n << 13) ^ n;
        int m = n * (n * n * 15731 + 789221) + 1376312589;
        return (m & 0x7FFFFFFF) / (float) 0x7FFFFFFF;
    }

    static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float smooth(float t) {
        float x = clamp01(t);
        return x * x * (3.0F - 2.0F * x);
    }

    static float easeOutCubic(float t) {
        float f = 1.0F - clamp01(t);
        return 1.0F - f * f * f;
    }

    static float easeInCubic(float t) {
        float x = clamp01(t);
        return x * x * x;
    }

    static float easeInOutCubic(float t) {
        float x = clamp01(t);
        return x < 0.5F ? 4.0F * x * x * x : 1.0F - (float) Math.pow(-2.0F * x + 2.0F, 3.0) / 2.0F;
    }

    static float easeOutBack(float t) {
        float x = clamp01(t);
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        return 1.0F + c3 * (x - 1.0F) * (x - 1.0F) * (x - 1.0F) + c1 * (x - 1.0F) * (x - 1.0F);
    }
}
