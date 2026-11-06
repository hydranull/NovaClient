package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import nova.nova_client.client.mods.XrayMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=26.1 {
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.BlockAndTintGetter;

import java.util.List;
*///?}

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    //? if >=26.1 {
    @Inject(method = "tesselateBlock", at = @At("HEAD"), cancellable = true)
    private void nova_client$filterBlocks(
            BlockQuadOutput output,
            float x,
            float y,
            float z,
            BlockAndTintGetter level,
            BlockPos pos,
            BlockState state,
            net.minecraft.client.renderer.block.dispatch.BlockStateModel model,
            long seed,
            CallbackInfo ci
    ) {
        if (XrayMod.isEnabled() && !XrayMod.shouldRender(state)) {
            ci.cancel();
        }
    }

    // Erz-Seiten nicht wegcullen, nur weil Stein daneben liegt (der ist im Xray ja unsichtbar)
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private void nova_client$showAllFaces(BlockAndTintGetter level, BlockState state, Direction direction,
                                          BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (XrayMod.isEnabled()) {
            cir.setReturnValue(true);
        }
    }
    //?} else {
    /*@Inject(method = "tesselateBlock", at = @At("HEAD"), cancellable = true)
    private void nova_client$filterBlocks(BlockAndTintGetter level, List<BlockModelPart> parts, BlockState state,
                                          BlockPos pos, PoseStack poseStack, VertexConsumer consumer, boolean cull,
                                          int overlay, CallbackInfo ci) {
        if (XrayMod.isEnabled() && !XrayMod.shouldRender(state)) {
            ci.cancel();
        }
    }

    // Erz-Seiten nicht wegcullen, nur weil Stein daneben liegt (der ist im Xray ja unsichtbar)
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void nova_client$showAllFaces(BlockAndTintGetter level, BlockState state, boolean cull,
                                                 Direction direction, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (XrayMod.isEnabled()) {
            cir.setReturnValue(true);
        }
    }

    // Ohne Ambient Occlusion werden Erze im Xray nicht von den (unsichtbaren) Nachbarbloecken abgedunkelt
    @Redirect(method = "tesselateBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;useAmbientOcclusion()Z"))
    private boolean nova_client$xrayNoAmbientOcclusion() {
        return Minecraft.useAmbientOcclusion() && !XrayMod.isEnabled();
    }
    *///?}
}
