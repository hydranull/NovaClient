package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.HandViewMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
*///?}

/**
 * HandView: jeden Aufruf "eine Hand zeichnen" (submitArmWithItem bzw. 1.21.11 renderArmWithItem) in eine eigene Pose
 * packen und dort verschieben - so wandert die Hand samt Item, und andere Hand-Mods (Knives) gleich mit.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandViewMixin {
    //? if >=26.1 {
    @WrapOperation(method = "submitHandsWithItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitArmWithItem(Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void nova_client$handView(FirstPersonHandsAndItemsRenderer renderer, PlayerRenderState player,
                                      FirstPersonHandsAndItemsRenderState hands, float partialTick, float xRot,
                                      InteractionHand hand, float attack, ItemStack stack, float inverseArmHeight,
                                      PoseStack poseStack, SubmitNodeCollector collector, int light, Operation<Void> original) {
        if (!HandViewMod.isEnabled() || Minecraft.getInstance().player == null) {
            original.call(renderer, player, hands, partialTick, xRot, hand, attack, stack, inverseArmHeight, poseStack,
                    collector, light);
            return;
        }
        poseStack.pushPose();
        HandViewMod.apply(poseStack, hand, Minecraft.getInstance().player.getMainArm());
        original.call(renderer, player, hands, partialTick, xRot, hand, attack, stack, inverseArmHeight, poseStack,
                collector, light);
        poseStack.popPose();
    }
    //?} else {
    /*@WrapOperation(method = "renderHandsWithItems", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"))
    private void nova_client$handView(FirstPersonHandsAndItemsRenderer renderer, AbstractClientPlayer player, float partialTick,
                                      float xRot, InteractionHand hand, float attack, ItemStack stack, float inverseArmHeight,
                                      PoseStack poseStack, SubmitNodeCollector collector, int light, Operation<Void> original) {
        if (!HandViewMod.isEnabled()) {
            original.call(renderer, player, partialTick, xRot, hand, attack, stack, inverseArmHeight, poseStack, collector, light);
            return;
        }
        poseStack.pushPose();
        HandViewMod.apply(poseStack, hand, player.getMainArm());
        original.call(renderer, player, partialTick, xRot, hand, attack, stack, inverseArmHeight, poseStack, collector, light);
        poseStack.popPose();
    }
    *///?}
}
