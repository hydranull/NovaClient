package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
//? if <26.1 {
/*import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

/** Textur-Ansicht direkt ins GUI zeichnen: in 1.21.11 nur intern vorhanden, ab 26.1 oeffentlich (GUI blit). */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsInvoker {
    //? if <26.1 {
    /*@Invoker("submitBlit")
    void nova_client$submitBlit(RenderPipeline pipeline, GpuTextureView view, GpuSampler sampler, int x0, int y0,
                                int x1, int y1, float u0, float u1, float v0, float v1, int color);
    *///?}
}
