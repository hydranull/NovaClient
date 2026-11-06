package nova.nova_client.mixin.client;

import nova.nova_client.client.Compat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemClusterRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import nova.nova_client.client.ItemPhysicsState;
import nova.nova_client.client.mods.ItemPhysicsMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Item Physics: statt Schweben und Drehen liegen Items flach auf dem Boden, im Fallen ueberschlagen sie sich und
 * kippen beim Landen um. Vanilla verschiebt und dreht das Item vor dem Zeichnen - genau dort wird die eigene Lage
 * eingesetzt (danach zeichnet Vanilla ganz normal weiter).
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
            at = @At("RETURN"))
    private void nova_client$itemPhysicsExtract(ItemEntity entity, ItemEntityRenderState state, float partialTick,
                                               CallbackInfo ci) {
        if (ItemPhysicsMod.isEnabled() && state instanceof ItemPhysicsState physics) {
            float[] pose = ItemPhysicsMod.update(entity, state.ageInTicks);
            physics.nova_client$set(pose[0], pose[1]);
        }
    }

    @Redirect(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
    private void nova_client$itemPhysicsSubmit(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                              ItemClusterRenderState state, RandomSource random, AABB box) {
        if (ItemPhysicsMod.isEnabled() && state instanceof ItemPhysicsState physics) {
            // Vanilla-Schweben und -Drehen verwerfen (Vanilla hat davor gepusht und poppt danach selbst)
            poseStack.popPose();
            poseStack.pushPose();
            float tilt = physics.nova_client$tilt();
            double rad = Math.toRadians(tilt);
            double halfHeight = (box.maxY - box.minY) * 0.5D;
            double halfDepth = (box.maxZ - box.minZ) * 0.5D;
            // So hoch anheben, dass die gekippte Form genau auf dem Boden aufliegt
            double lift = Math.abs(Math.cos(rad)) * halfHeight + Math.abs(Math.sin(rad)) * halfDepth + 0.01D;
            poseStack.translate(0.0D, lift, 0.0D);
            Compat.rotate(poseStack, Axis.YP.rotationDegrees(physics.nova_client$yaw()));
            Compat.rotate(poseStack, Axis.XP.rotationDegrees(tilt));
            // um die Mitte des Modells drehen
            poseStack.translate(-(box.minX + box.maxX) * 0.5D, -(box.minY + box.maxY) * 0.5D, -(box.minZ + box.maxZ) * 0.5D);
        }
        ItemEntityRenderer.submitMultipleFromCount(poseStack, collector, light, state, random, box);
    }
}
