package nova.nova_client.client.toolskins;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import nova.nova_client.client.Compat;
import nova.nova_client.client.mods.ToolSkinsMod;
import org.joml.Quaternionf;

/**
 * Hyper Tools: Zieh-Animation des Laserschwerts in der Ego-Sicht. Startet, wenn ein Schwert neu in die Hand kommt
 * (anderer Slot, anderes Schwert oder vorher nicht sichtbar): die Hand kommt in der ersten Haelfte von unten hoch
 * (statt des langsamen Vanilla-Hebens mit der Abklingzeit), der Griff dreht sich einmal um die Klinge, die Klinge
 * zuendet aus dem Emitter, und ein Glanzstreifen laeuft ueber das Metall. Beim Inspect "Anschauen" geht die Klinge
 * hier aus und wieder an (SaberMoves).
 */
final class ToolSkinDraw {
    private static final float DURATION = 0.7F;
    /** Hoehe des Griffs (Werkzeug-Rahmen), um die sich das Schwert beim Ziehen dreht. */
    private static final float GRIP = 2.8F;
    /** Anteil der Animation, in dem die Hand von unten hochkommt. */
    private static final float RISE = 0.45F;

    private static final double[] START = {-1.0D, -1.0D};
    private static final double[] LAST_SEEN = {-10.0D, -10.0D};
    private static final ToolSkinMesh[] LAST_MESH = new ToolSkinMesh[2];
    /** Nach dem Ziehen oben bleiben, bis Vanilla (Abklingzeit) selbst oben ist oder geschlagen wird. */
    private static final boolean[] HOLD = new boolean[2];
    private static int lastSlot = -1;
    /** Wie weit die Klinge im letzten apply() ausgefahren ist (0..1). */
    private static float lastExtend = 1.0F;

    private ToolSkinDraw() {
    }

    static void reset() {
        START[0] = START[1] = -1.0D;
        LAST_SEEN[0] = LAST_SEEN[1] = -10.0D;
        LAST_MESH[0] = LAST_MESH[1] = null;
        HOLD[0] = HOLD[1] = false;
        lastSlot = -1;
    }

    /** Hoehe der Hand fuer die Ego-Sicht (0 = oben). held = sichtbares Schwert ist das gehaltene. */
    static float armHeight(int side, boolean held, float attack, float vanilla) {
        if (!held) {
            // Altes Werkzeug geht wie Vanilla nach unten, eine laufende Animation bricht ab
            START[side] = -1.0D;
            HOLD[side] = false;
            return vanilla;
        }
        if (START[side] >= 0.0D) {
            float t = progress(side);
            return 1.0F - easeOutCubic(t / RISE);
        }
        if (HOLD[side]) {
            if (attack > 0.0F || vanilla < 0.001F || SaberMoves.active()) {
                HOLD[side] = false;
                return SaberMoves.active() ? 0.0F : vanilla;
            }
            return 0.0F;
        }
        return SaberMoves.active() ? 0.0F : vanilla;
    }

    private static float progress(int side) {
        double now = System.nanoTime() / 1.0E9D;
        return (float) ((now - START[side]) * ToolSkinsMod.drawSpeed / DURATION);
    }

    /** Wie weit die Klinge gerade ausgefahren ist (Zuenden beim Ziehen, Inspect). */
    static float extension() {
        return lastExtend;
    }

    /**
     * Legt Zieh-Animation und Inspect-Drehung auf die Pose (Werkzeug-Rahmen). Rueckgabe: Hoehe des Glanzstreifens
     * oder NaN.
     */
    static float apply(ToolSkinRenderer.Arg arg, PoseStack poseStack, boolean mainHand) {
        lastExtend = 1.0F;
        int hand = arg.context() == net.minecraft.world.item.ItemDisplayContext.FIRST_PERSON_RIGHT_HAND ? 0 : 1;
        float side = hand == 0 ? 1.0F : -1.0F;
        double now = System.nanoTime() / 1.0E9D;
        LocalPlayer player = Minecraft.getInstance().player;
        int slot = mainHand && player != null ? player.getInventory().getSelectedSlot() : -1;
        boolean fresh = now - LAST_SEEN[hand] > 0.25D || LAST_MESH[hand] != arg.mesh() || (mainHand && slot != lastSlot);
        LAST_SEEN[hand] = now;
        LAST_MESH[hand] = arg.mesh();
        if (mainHand) {
            lastSlot = slot;
        }
        if (fresh) {
            START[hand] = ToolSkinsMod.drawAnimation ? now : -1.0D;
            HOLD[hand] = false;
            if (mainHand) {
                SaberMoves.reset();
            }
        }

        float shineAt = Float.NaN;
        if (START[hand] >= 0.0D) {
            float t = progress(hand);
            if (t >= 1.0F) {
                START[hand] = -1.0D;
                HOLD[hand] = true;
            } else {
                lastExtend = easeOutCubic((t - 0.05F) / 0.35F);
                // Zuenden: Griff kommt hoch, dreht sich einmal um die Klinge und schwingt kurz nach
                float k = easeOutCubic(t);
                float back = 1.0F - k;
                rotateAround(poseStack, GRIP, new Quaternionf().rotationZ(side * Mth.DEG_TO_RAD
                        * (-30.0F * back * back + 14.0F * Mth.sin(Mth.PI * k) * back)));
                rotateAround(poseStack, GRIP, new Quaternionf().rotationY(side * Mth.TWO_PI * back * back));
                if (ToolSkinsMod.shine) {
                    float u = (t - 0.35F) / 0.55F;
                    if (u > 0.0F && u < 1.0F) {
                        shineAt = -1.5F + (arg.mesh().height() + 3.0F) * u;
                    }
                }
            }
        }

        // Inspect: Klinge aus- und wieder einschalten (die Drehung um die eigene Achse macht SaberMoves im Hand-Raum)
        if (mainHand) {
            lastExtend *= SaberMoves.extension();
        }
        return shineAt;
    }

    private static void rotateAround(PoseStack poseStack, float y, Quaternionf rotation) {
        poseStack.translate(0.0F, y, 0.0F);
        Compat.rotate(poseStack, rotation);
        poseStack.translate(0.0F, -y, 0.0F);
    }

    private static float easeOutCubic(float t) {
        float u = 1.0F - Mth.clamp(t, 0.0F, 1.0F);
        return 1.0F - u * u * u;
    }
}
