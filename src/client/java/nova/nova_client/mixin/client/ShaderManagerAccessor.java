package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//? if >=26.1 {
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
//?} else {
/*import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
*///?}

@Mixin(ShaderManager.class)
public interface ShaderManagerAccessor {
    //? if >=26.1 {
    @Accessor("postChainProjection")
    Projection nova_client$postChainProjection();

    @Accessor("postChainProjectionMatrixBuffer")
    ProjectionMatrixBuffer nova_client$postChainProjectionMatrixBuffer();
    //?} else {
    /*@Accessor("postChainProjectionMatrixBuffer")
    CachedOrthoProjectionMatrixBuffer nova_client$postChainProjectionMatrixBuffer();
    *///?}
}
