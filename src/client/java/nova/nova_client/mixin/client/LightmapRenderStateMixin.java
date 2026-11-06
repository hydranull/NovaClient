package nova.nova_client.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightmapRenderState.class)
public interface LightmapRenderStateMixin {
    @Accessor("brightness")
    void nova_client$setBrightness(float brightness);
}
//?} else {
/*import net.minecraft.client.renderer.LightTexture;

/^* 1.21.11: Fullbright setzt die Helligkeit direkt in LightTexture (LightmapRenderStateExtractorMixin). ^/
@Mixin(LightTexture.class)
public interface LightmapRenderStateMixin {
}
*///?}
