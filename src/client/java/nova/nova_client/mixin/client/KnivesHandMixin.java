package nova.nova_client.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.KnifeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
//?} else {
/*import net.minecraft.client.player.AbstractClientPlayer;
*///?}

/** Knives: Schwert in der Ego-Sicht durch Arme mit Shadow Daggers ersetzen (submitArmWithItem bzw. renderArmWithItem). */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class KnivesHandMixin {
    //? if >=26.1 {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void nova_client$knives(PlayerRenderState player, FirstPersonHandsAndItemsRenderState hands, float partialTick,
                                    float xRot, InteractionHand hand, float attack, ItemStack stack, float inverseArmHeight,
                                    PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (Minecraft.getInstance().player != null && KnifeRenderer.render(Minecraft.getInstance().player, hand, attack,
                stack, inverseArmHeight, poseStack, collector, light)) {
            ci.cancel();
        }
    }
    //?} else {
    /*@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
    private void nova_client$knives(AbstractClientPlayer player, float partialTick, float xRot, InteractionHand hand,
                                    float attack, ItemStack stack, float inverseArmHeight, PoseStack poseStack,
                                    SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (KnifeRenderer.render(player, hand, attack, stack, inverseArmHeight, poseStack, collector, light)) {
            ci.cancel();
        }
    }
    *///?}
}
