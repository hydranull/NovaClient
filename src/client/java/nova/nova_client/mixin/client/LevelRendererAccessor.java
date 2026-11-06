package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.gen.Accessor;
//?}

/** Glowing Outlines (26.3): dauerhaftes Entity-Outline-Ziel und ob Vanilla es in diesem Frame einblendet. */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    //? if >=26.1 {
    @Accessor("entityOutlineTarget")
    RenderTarget nova_client$outlineTarget();

    @Accessor("currentFrameRendersEntityOutline")
    boolean nova_client$rendersOutline();

    @Accessor("currentFrameRendersEntityOutline")
    void nova_client$setRendersOutline(boolean value);
    //?}
}
