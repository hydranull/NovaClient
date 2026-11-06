package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import nova.nova_client.client.VrMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VR Menu: das Menue in ein eigenes Bild zeichnen und am Ende als schwebendes Glasfenster einsetzen. */
@Mixin(GuiRenderer.class)
public abstract class VrMenuGuiRendererMixin {
    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"))
    private void nova_client$vrSkipBlur(GameRenderer renderer, Operation<Void> original) {
        if (!VrMenu.skipVanillaBlur()) {
            original.call(renderer);
        }
    }

    //? if >=26.1 {
    @ModifyArg(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;II)V",
            ordinal = 1), index = 1)
    //?} else {
    /*@ModifyArg(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/vertex/VertexFormat$IndexType;II)V",
            ordinal = 1), index = 1)
    *///?}
    private RenderTarget nova_client$vrContentTarget(RenderTarget target) {
        return VrMenu.contentTarget(target);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void nova_client$vrComposite(CallbackInfo ci) {
        VrMenu.composite();
    }
}
