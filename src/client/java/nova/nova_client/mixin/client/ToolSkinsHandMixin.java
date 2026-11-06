package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.toolskins.SaberMoves;
import nova.nova_client.client.toolskins.ToolSkins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//? if >=26.1 {
import net.minecraft.client.Minecraft;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
*///?}

/**
 * Hyper Tools in der Ego-Sicht:
 * <ul>
 *     <li>Nach dem Wechseln hebt Vanilla das Werkzeug erst mit der Abklingzeit hoch (fast eine Sekunde unter dem
 *     Bildrand). Waehrend der eigenen Zieh-Animation kommt die Hoehe stattdessen von ToolSkinDraw.</li>
 *     <li>Statt des Vanilla-Schwungs (swingArm) laufen mit dem Laserschwert die eigenen Moves (SaberMoves).</li>
 * </ul>
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class ToolSkinsHandMixin {
    //? if >=26.1 {
    @ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private float nova_client$toolSkinsArm(float inverseArmHeight, @Local(argsOnly = true) InteractionHand hand,
                                           @Local(argsOnly = true) ItemStack stack,
                                           @Local(argsOnly = true, ordinal = 2) float attack) {
        return ToolSkins.armHeight(Minecraft.getInstance().player, hand, stack, attack, inverseArmHeight);
    }

    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"))
    //?} else {
    /*@ModifyVariable(method = "renderArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private float nova_client$toolSkinsArm(float inverseArmHeight, @Local(argsOnly = true) AbstractClientPlayer player,
                                           @Local(argsOnly = true) InteractionHand hand,
                                           @Local(argsOnly = true) ItemStack stack,
                                           @Local(argsOnly = true, ordinal = 2) float attack) {
        return ToolSkins.armHeight(player, hand, stack, attack, inverseArmHeight);
    }

    @WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V"))
    *///?}
    private void nova_client$saberNoVanillaSwing(FirstPersonHandsAndItemsRenderer renderer, float attack, PoseStack poseStack,
                                                 int side, HumanoidArm arm, Operation<Void> original,
                                                 @Local(argsOnly = true) InteractionHand hand,
                                                 @Local(argsOnly = true) ItemStack stack) {
        // Laserschwert: kein Vanilla-Schwung, die eigenen Moves kommen in applyItemArmTransform dazu
        if (!ToolSkins.moves(stack, hand)) {
            original.call(renderer, attack, poseStack, side, arm);
        }
    }

    //? if >=26.1 {
    @WrapOperation(method = "submitArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
    //?} else {
    /*@WrapOperation(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
    *///?}
    private void nova_client$saberMoves(FirstPersonHandsAndItemsRenderer renderer, PoseStack poseStack, HumanoidArm arm,
                                        float inverseArmHeight, Operation<Void> original,
                                        @Local(argsOnly = true) InteractionHand hand, @Local(argsOnly = true) ItemStack stack) {
        original.call(renderer, poseStack, arm, inverseArmHeight);
        if (ToolSkins.moves(stack, hand)) {
            // jedes Bild: Ruhe-Pose des Laserschwerts, dazu der laufende Move
            SaberMoves.applyHand(poseStack, arm == HumanoidArm.RIGHT ? 1 : -1);
        }
    }
}
