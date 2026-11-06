package nova.nova_client.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import nova.nova_client.mixin.client.GameRendererAccessor;
import nova.nova_client.mixin.client.GuiGraphicsInvoker;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.nio.file.Path;
import java.util.stream.Stream;
//? if >=26.1 {
import net.minecraft.world.item.component.SwingAnimation;
//?}

/**
 * Kleine Bruecke zwischen den Minecraft-Versionen (26.x und 1.21.11): alles, was sich nicht nur im Namen, sondern in
 * der Form unterscheidet. Die versionsabhaengigen Zeilen stehen in Stonecutter-Bloecken ("//? if"), der Rest des
 * Codes ruft nur diese Methoden auf. Reine Umbenennungen erledigt stonecutter.gradle.kts automatisch.
 */
public final class Compat {
    private Compat() {
    }

    // ---------------------------------------------------------------- ChunkPos

    public static int chunkX(ChunkPos pos) {
        //? if >=26.1 {
        return pos.x();
        //?} else {
        /*return pos.x;
        *///?}
    }

    public static int chunkZ(ChunkPos pos) {
        //? if >=26.1 {
        return pos.z();
        //?} else {
        /*return pos.z;
        *///?}
    }

    /** Chunk aus dem gepackten long (ChunkPos.pack / asLong). */
    public static ChunkPos chunkPos(long key) {
        //? if >=26.1 {
        return ChunkPos.unpack(key);
        //?} else {
        /*return new ChunkPos(key);
        *///?}
    }

    // ---------------------------------------------------------------- Items

    /** Alle Plaetze eines Behaelter-Items (auch leere), als Kopien. */
    public static Stream<ItemStack> containerItems(ItemContainerContents contents) {
        //? if >=26.1 {
        return contents.itemCopies();
        //?} else {
        /*return contents.stream();
        *///?}
    }

    /** Nur die belegten Plaetze, als Kopien. */
    public static Stream<ItemStack> containerNonEmpty(ItemContainerContents contents) {
        //? if >=26.1 {
        return contents.nonEmptyItemCopyStream();
        //?} else {
        /*return contents.nonEmptyStream();
        *///?}
    }

    // ---------------------------------------------------------------- Rendering

    /**
     * Projektion x Kamera-Drehung des aktuellen Bildes (Welt relativ zur Kamera -> Bildschirm), wie fuer Weltpunkte
     * auf dem HUD gebraucht.
     */
    public static Matrix4f viewProjection(Matrix4f dest) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.mainCamera();
        //? if >=26.1 {
        return camera.getViewRotationProjectionMatrix(dest);
        //?} else {
        /*// 1.21.11 rechnet die Matrizen erst im Render-Durchlauf aus: genauso nachbauen wie GameRenderer.renderLevel
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float fov = ((GameRendererAccessor) mc.gameRenderer).nova_client$getFov(camera, partialTick, true);
        Matrix4f projection = mc.gameRenderer.getProjectionMatrix(fov);
        Matrix4f view = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
        return dest.set(projection).mul(view);
        *///?}
    }

    /** Textur (z.B. eigenes Render-Ziel) ins GUI zeichnen, Ecken x0/y0 - x1/y1. */
    public static void blit(GuiGraphicsExtractor g, GpuTextureView view, GpuSampler sampler, int x0, int y0, int x1, int y1,
                            float u0, float u1, float v0, float v1) {
        //? if >=26.1 {
        g.blit(view, sampler, x0, y0, x1, y1, u0, u1, v0, v1);
        //?} else {
        /*((GuiGraphicsInvoker) g).nova_client$submitBlit(RenderPipelines.GUI_TEXTURED, view, sampler, x0, y0, x1, y1,
                u0, u1, v0, v1, -1);
        *///?}
    }

    // ---------------------------------------------------------------- Schlagen

    /**
     * Arm schwingen wie nach einem Angriff. 26.3 nimmt die Schlag-Animation des gehaltenen Items und schickt kein
     * eigenes Schwing-Paket mehr (der Server leitet es aus dem Angriff ab); 1.21.11 schickt es selbst.
     */
    public static void swing(LivingEntity entity, InteractionHand hand) {
        //? if >=26.1 {
        entity.swing(hand, entity.getItemInHand(hand).getAttackAnimation(), false);
        //?} else {
        /*entity.swing(hand);
        *///?}
    }

    /** Arm schwingen wie nach dem Benutzen (Block setzen, Item werfen, ...). */
    public static void swingUse(LivingEntity entity, InteractionHand hand) {
        //? if >=26.1 {
        entity.swing(hand, entity.getItemInHand(hand).getInteractAnimation(), false);
        //?} else {
        /*entity.swing(hand);
        *///?}
    }

    /**
     * Vorschau-Figuren: Arm der Haupthand mitten im Schlag (progress 0..1, ausserhalb = Ruhe). type = Art des Schlags
     * (WHACK, STAB, ...).
     */
    public static void previewSwing(ArmedEntityRenderState state, float progress, SwingAnimationType type) {
        boolean active = progress > 0.0F && progress < 1.0F;
        //? if >=26.1 {
        state.currentSwing = active ? new LivingEntity.SwingDescription(InteractionHand.MAIN_HAND,
                new SwingAnimation(type, 6), 6) : null;
        state.swingAnimation = active ? progress : 0.0F;
        //?} else {
        /*if (state instanceof net.minecraft.client.renderer.entity.state.HumanoidRenderState humanoid) {
            humanoid.attackArm = state.mainArm;
        }
        state.attackTime = active ? progress : 0.0F;
        state.swingAnimationType = type;
        *///?}
    }

    /** Laeuft in der Vorschau gerade ein Schlag mit Animation? */
    public static boolean previewSwinging(ArmedEntityRenderState state) {
        //? if >=26.1 {
        return state.currentSwing != null;
        //?} else {
        /*return state.attackTime > 0.0F;
        *///?}
    }

    // ---------------------------------------------------------------- Drehen, Oeffnen

    /** Drehung auf den PoseStack legen (26.3: rotate, 1.21.11: mulPose). */
    public static void rotate(PoseStack pose, Quaternionfc rotation) {
        //? if >=26.1 {
        pose.rotate(rotation);
        //?} else {
        /*pose.mulPose(rotation);
        *///?}
    }

    /** Link im Browser oeffnen. */
    public static void openUri(String uri) {
        //? if >=26.1 {
        com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(uri));
        //?} else {
        /*net.minecraft.util.Util.getPlatform().openUri(uri);
        *///?}
    }

    /** Ordner (oder Datei) im Explorer oeffnen. */
    public static void openPath(Path path) {
        //? if >=26.1 {
        com.mojang.blaze3d.Blaze3D.openPath(path);
        //?} else {
        /*net.minecraft.util.Util.getPlatform().openPath(path);
        *///?}
    }

    // ---------------------------------------------------------------- Nachrichten nur fuer dich

    /** Zeile im Chat. */
    public static void chat(LocalPlayer player, Component message) {
        //? if >=26.1 {
        player.sendSystemMessage(message);
        //?} else {
        /*player.displayClientMessage(message, false);
        *///?}
    }

    /** Text ueber der Hotbar. */
    public static void actionBar(LocalPlayer player, Component message) {
        //? if >=26.1 {
        player.sendOverlayMessage(message);
        //?} else {
        /*player.displayClientMessage(message, true);
        *///?}
    }
}
