package nova.nova_client.client.toolskins;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.ToolSkinsMod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
//? if <26.1 {
/*import net.minecraft.client.renderer.rendertype.RenderTypes;
*///?}

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Hyper Tools: zeichnet das Laserschwert statt des flachen Sprites. Haengt als "Spezialmodell" in der Ebene, die das
 * Vanilla-Modell angelegt hat (ToolSkinItemModel) - Halte-Position und Ego-Sicht bleiben Vanilla, die Bewegungen
 * kommen dazu (ToolSkinDraw, SaberMoves).
 * <p>
 * Drei Durchgaenge: Griff mit dem PBR-Shader (hyper_tool), Klinge mit saber_blade (glatt, aus dem Abstand zur
 * Achse gerechnet) und in der Ego-Sicht der Schweif beim Schlagen (saber_trail).
 */
public final class ToolSkinRenderer implements SpecialModelRenderer<ToolSkinRenderer.Arg> {
    public static final ToolSkinRenderer INSTANCE = new ToolSkinRenderer();

    /** Werkzeug-Rahmen (Sprite-Pixel, Griffende bei y = 0, Klinge Richtung +Y) -> Modellraum des Vanilla-Schwerts. */
    private static final Matrix4f FRAME = spriteFrame();

    /** Was eine Ebene zum Zeichnen braucht (gleich = GUI-Cache darf das alte Bild nehmen). */
    public record Arg(ToolSkinMesh mesh, ItemDisplayContext context, Vector3fc[] extents) {
    }

    private ToolSkinRenderer() {
    }

    static Arg arg(ToolSkinMesh mesh, ItemDisplayContext context) {
        return new Arg(mesh, context, mesh.extents(FRAME));
    }

    //? if >=26.1 {
    @Override
    public void submit(Arg arg, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
                       boolean glint, int outline) {
        submitTool(arg, poseStack, collector, light, overlay, glint);
    }
    //?} else {
    /*@Override
    public void submit(Arg arg, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector,
                       int light, int overlay, boolean glint, int outline) {
        submitTool(arg, poseStack, collector, light, overlay, glint);
    }
    *///?}

    private static void submitTool(Arg arg, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay,
                                   boolean glint) {
        RenderType solid = arg == null ? null : ToolSkinAssets.renderType();
        if (solid == null) {
            return;
        }
        ToolSkinMesh mesh = arg.mesh();
        poseStack.pushPose();
        poseStack.mulPose(FRAME);
        boolean firstPerson = arg.context() == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || arg.context() == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        boolean mainHand = firstPerson && isMainHand(arg.context());
        float shineAt = Float.NaN;
        float extend = 1.0F;
        if (firstPerson) {
            shineAt = ToolSkinDraw.apply(arg, poseStack, mainHand);
            extend = ToolSkinDraw.extension();
        }
        float time = (float) ((System.nanoTime() / 1.0E9D) % 3600.0D);
        float shine = shineAt;
        boolean glow = ToolSkinsMod.glow;
        collector.submitCustomGeometry(poseStack, solid,
                (pose, buffer) -> mesh.emit(pose, buffer, light, overlay, shine, time, glow, null));

        RenderType bladeType = ToolSkinAssets.bladeType();
        if (bladeType != null && mesh.hasBlade() && extend > 0.0F) {
            Matrix4f m = poseStack.last().pose();
            Vector3f emitter = new Vector3f();
            Vector3f tip = new Vector3f();
            ToolSkinBlade.place(m, mesh.bladeStart(), mesh.bladeEnd(), extend, emitter, tip);
            float radius = mesh.bladeRadius() * ToolSkinBlade.scale(m) * ToolSkinsMod.glowSize;
            float flash = mainHand ? SaberMoves.flash() : 0.0F;
            float[] base = mesh.bladeColor();
            float[] color = {Mth.lerp(flash * 0.7F, base[0], 1.0F), Mth.lerp(flash * 0.7F, base[1], 1.0F),
                    Mth.lerp(flash * 0.7F, base[2], 1.0F)};
            // leichtes Summen: die Klinge lebt, ohne zu flackern
            float hum = 0.95F + 0.03F * Mth.sin(time * 37.0F) + 0.02F * Mth.sin(time * 13.7F);
            float bright = Mth.clamp(hum * Math.min(1.0F, extend * 4.0F) + flash * 0.3F, 0.0F, 1.0F);
            boolean gui = arg.context() == ItemDisplayContext.GUI;
            collector.submitCustomGeometry(poseStack, bladeType,
                    (pose, buffer) -> ToolSkinBlade.emit(buffer, emitter, tip, radius, color, bright, gui));
            if (mainHand) {
                SaberMoves.recordTrail(emitter, tip);
                RenderType trailType = ToolSkinAssets.trailType();
                if (trailType != null && SaberMoves.trailSize() > 1) {
                    collector.submitCustomGeometry(poseStack, trailType,
                            (pose, buffer) -> ToolSkinBlade.emitTrail(buffer, SaberMoves.trail(), base, 1.0F));
                }
            }
        }
        //? if <26.1 {
        /*if (glint) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entityGlint(),
                    (pose, buffer) -> mesh.emitPlain(pose, buffer, light, overlay, null));
        }
        *///?}
        poseStack.popPose();
    }

    /** Ist diese Ego-Hand die Haupthand des Spielers? */
    private static boolean isMainHand(ItemDisplayContext context) {
        LocalPlayer player = Minecraft.getInstance().player;
        boolean right = context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        return player != null && (player.getMainArm() == HumanoidArm.RIGHT) == right;
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
    }

    @Override
    public Arg extractArgument(ItemStack stack) {
        return null;
    }

    /** Diagonale des Schwert-Sprites (16 Pixel) als Werkzeug-Achse. */
    private static Matrix4f spriteFrame() {
        float s = 0.70710677F / 16.0F;
        return new Matrix4f(s, -s, 0.0F, 0.0F, s, s, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F / 16.0F, 0.0F,
                1.3F / 16.0F, 1.3F / 16.0F, 0.5F, 1.0F);
    }

    /** Ecken der Huelle im Modellraum (fuer GUI-Groesse und Bodenhoehe). */
    static Vector3fc[] corners(float[] b, Matrix4f frame) {
        Vector3fc[] out = new Vector3fc[8];
        for (int i = 0; i < 8; i++) {
            out[i] = frame.transformPosition(new Vector3f((i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4],
                    (i & 4) == 0 ? b[2] : b[5]));
        }
        return out;
    }

    static Supplier<Vector3fc[]> supplier(Vector3fc[] extents) {
        return () -> extents;
    }
}
