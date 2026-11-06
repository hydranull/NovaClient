package nova.nova_client.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.1 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.DisplayRenderer;

import java.util.HashMap;
import java.util.Map;
//?}

/**
 * Mehrere Entities und Bloecke in einem Entity-Bild des GUI (Minecraft zeichnet je Bild nur eines): die Modul-Vorschau
 * reicht ein unsichtbares Anker-Entity ein, PreviewSceneMixin zeichnet direkt danach alle Teile der Szene mit derselben
 * Kamera - Charakter, Mobs, Items und echte Block-Modelle (wie in der Welt), jeweils an ihrem Platz in der Szene.
 */
public final class PreviewScene {
    private static final int MAX = 96;
    private static final int FULL_BRIGHT = 15728880;
    private static final Part[] PARTS = new Part[MAX];
    private static int count;
    /** Anker der aktuellen Szene; nur dessen Bild bekommt die Teile. */
    private static EntityRenderState anchor;

    private PreviewScene() {
    }

    /** Ein Teil: Entity oder Block, Platz in der Szene, Groesse und Drehung (Bogenmass, erst Hochachse, dann gekippt). */
    private static final class Part {
        EntityRenderState state;
        BlockState block;
        float x;
        float y;
        float z;
        float size;
        float yaw;
        float pitch;
    }

    static void begin(EntityRenderState anchorState) {
        anchor = anchorState;
        count = 0;
    }

    static void add(EntityRenderState state, float x, float y, float z, float size, float yaw, float pitch) {
        Part part = next();
        if (part != null) {
            part.state = state;
            part.block = null;
            set(part, x, y, z, size, yaw, pitch);
        }
    }

    /** Block mit der Unterseite bei y, mittig ueber (x, z); size waechst um diese Stelle. */
    static void addBlock(BlockState block, float x, float y, float z, float size) {
        Part part = next();
        if (part != null) {
            part.state = null;
            part.block = block;
            set(part, x, y, z, size, 0.0F, 0.0F);
        }
    }

    private static Part next() {
        if (count == MAX) {
            return null;
        }
        Part part = PARTS[count];
        if (part == null) {
            part = PARTS[count] = new Part();
        }
        count++;
        return part;
    }

    private static void set(Part part, float x, float y, float z, float size, float yaw, float pitch) {
        part.x = x;
        part.y = y;
        part.z = z;
        part.size = size;
        part.yaw = yaw;
        part.pitch = pitch;
    }

    /** Aus PreviewSceneMixin: nach dem Anker die Teile der Szene einreichen. */
    public static void submitParts(EntityRenderState state, EntityRenderDispatcher dispatcher, CameraRenderState camera, PoseStack pose,
                                   SubmitNodeCollector collector) {
        if (state != anchor) {
            return;
        }
        for (int i = 0; i < count; i++) {
            Part part = PARTS[i];
            pose.pushPose();
            pose.translate(part.x, part.y, part.z);
            if (part.yaw != 0.0F) {
                Compat.rotate(pose, Axis.YP.rotation(part.yaw));
            }
            if (part.pitch != 0.0F) {
                Compat.rotate(pose, Axis.XP.rotation(part.pitch));
            }
            if (part.size != 1.0F) {
                pose.scale(part.size, part.size, part.size);
            }
            if (part.block != null) {
                // Block-Modelle liegen von 0 bis 1: Mitte der Unterseite auf den Platz
                pose.translate(-0.5F, 0.0F, -0.5F);
                submitBlock(part.block, pose, collector);
            } else {
                dispatcher.submit(part.state, camera, 0.0D, 0.0D, 0.0D, pose, collector);
            }
            pose.popPose();
        }
    }

    //? if >=26.1 {
    private static final Map<BlockState, BlockModelRenderState> MODELS = new HashMap<>();
    private static BlockModelResolver resolver;

    private static void submitBlock(BlockState block, PoseStack pose, SubmitNodeCollector collector) {
        BlockModelRenderState model = MODELS.get(block);
        if (model == null) {
            if (resolver == null) {
                resolver = new BlockModelResolver(Minecraft.getInstance().getModelManager());
            }
            model = new BlockModelRenderState();
            resolver.update(model, block, DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
            MODELS.put(block, model);
        }
        model.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
    }

    /** Neue Welt (oder neue Ressourcen): Block-Modelle neu holen. */
    static void forgetBlocks() {
        MODELS.clear();
        resolver = null;
    }
    //?} else {
    /*private static void submitBlock(BlockState block, PoseStack pose, SubmitNodeCollector collector) {
        collector.submitBlock(pose, block, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
    }

    static void forgetBlocks() {
    }
    *///?}
}
