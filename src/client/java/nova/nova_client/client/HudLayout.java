package nova.nova_client.client;

import net.minecraft.client.Minecraft;

/**
 * Anordnung der HUD-Elemente an ihrer Standard-Position: jedes hat einen festen Platz in der linken oder rechten Spalte
 * und steht direkt unter den Elementen davor, die gerade sichtbar sind - egal welche Module an sind, nichts ueberlappt.
 * Elemente, die im HUD-Editor verschoben wurden, bleiben wo sie sind und melden sich hier nicht.
 * <p>
 * Weil die Elemente nacheinander gezeichnet werden, zaehlen die Hoehen des letzten Bildes (ein Bild Verzoegerung
 * sieht man nicht).
 */
public final class HudLayout {
    public enum Slot {
        WATERMARK(false), HUD_INFO_LEFT(false), REGION_MAP(false), ADMIN_LIST(false),
        RADAR(true), HUD_INFO_RIGHT(true), SPOTIFY(true), POTIONS(true), HOTKEYS(true);

        final boolean right;

        Slot(boolean right) {
            this.right = right;
        }
    }

    private static final int MARGIN = 6;
    private static final int GAP = 5;
    /** Platz fuer Minecrafts Effekt-Symbole oben rechts. */
    private static final int EFFECT_ICONS = 50;
    private static final Slot[] SLOTS = Slot.values();
    private static final int[] HEIGHT = new int[SLOTS.length];
    private static final long[] SEEN = new long[SLOTS.length];
    private static long frame = 1;

    private HudLayout() {
    }

    /** Am Anfang jedes HUD-Bildes. */
    public static void beginFrame() {
        frame++;
    }

    /** Oberkante fuer ein Element der Hoehe height (GUI-Pixel) an seinem Platz. */
    public static int top(Slot slot, int height) {
        HEIGHT[slot.ordinal()] = height;
        SEEN[slot.ordinal()] = frame;
        int y = start(slot.right);
        for (Slot other : SLOTS) {
            if (other == slot) {
                break;
            }
            if (other.right == slot.right && visible(other)) {
                y += HEIGHT[other.ordinal()] + GAP;
            }
        }
        return y;
    }

    /** Unterkante der rechten Spalte (z.B. fuer Toasts darunter). */
    public static int rightBottom() {
        int y = start(true);
        for (Slot slot : SLOTS) {
            if (slot.right && visible(slot)) {
                y += HEIGHT[slot.ordinal()] + GAP;
            }
        }
        return y;
    }

    private static boolean visible(Slot slot) {
        return SEEN[slot.ordinal()] >= frame - 1 && HEIGHT[slot.ordinal()] > 0;
    }

    private static int start(boolean right) {
        Minecraft client = Minecraft.getInstance();
        boolean effects = right && client.player != null && !client.player.getActiveEffects().isEmpty();
        return MARGIN + (effects ? EFFECT_ICONS : 0);
    }
}
