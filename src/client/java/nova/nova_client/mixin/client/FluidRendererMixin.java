package nova.nova_client.mixin.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import nova.nova_client.client.mods.XrayMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
*///?}

//? if >=26.1 {
@Mixin(FluidRenderer.class)
//?} else {
/*@Mixin(LiquidBlockRenderer.class)
*///?}
public abstract class FluidRendererMixin {
    // Wasser und Lava verdecken im Xray sonst die Erze
    @Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
    //? if >=26.1 {
    private void nova_client$hideFluids(BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output,
                                        BlockState blockState, FluidState fluidState, CallbackInfo ci) {
    //?} else {
    /*private void nova_client$hideFluids(BlockAndTintGetter level, BlockPos pos, VertexConsumer output,
                                        BlockState blockState, FluidState fluidState, CallbackInfo ci) {
    *///?}
        if (XrayMod.isEnabled()) {
            ci.cancel();
        }
    }
}
