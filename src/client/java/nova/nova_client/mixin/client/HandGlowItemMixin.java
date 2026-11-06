package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import nova.nova_client.client.HandGlowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Glowing Outlines: Items in der Ego-Sicht mit Outline-Farbe einreichen (Haupt- und Zweithand). */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandGlowItemMixin {
    //? if >=26.1 {
    @ModifyArg(method = "submitArmWithItem", at = @At(value = "INVOKE",
    //?} else {
    /*@ModifyArg(method = "renderItem", at = @At(value = "INVOKE",
    *///?}
            target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"),
            index = 4)
    private int nova_client$handGlowOutline(int outlineColor) {
        return HandGlowRenderer.outlineColor(outlineColor);
    }
}
