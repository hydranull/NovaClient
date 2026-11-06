package nova.nova_client.mixin.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    /** Grundeinstellungen der Debug-Flaechen (Globals, Matrizen, position_color, durchsichtig, ohne Culling). */
    @Accessor("DEBUG_FILLED_SNIPPET")
    static RenderPipeline.Snippet nova_client$debugFilledSnippet() {
        throw new AssertionError();
    }

    /** Grundeinstellungen der Entity-Modelle (Matrizen, Nebel, Licht, Sampler0/2, Entity-Format) - fuer Hyper Tools. */
    @Accessor("ENTITY_SNIPPET")
    static RenderPipeline.Snippet nova_client$entitySnippet() {
        throw new AssertionError();
    }
}
