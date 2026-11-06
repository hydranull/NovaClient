package nova.nova_client.mixin.client;

import nova.nova_client.client.mods.FullbrightMod;
import nova.nova_client.client.mods.XrayMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void nova_client$applyFullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
        // Xray braucht Licht, sonst sind die Erze unter Tage schwarz
        if (FullbrightMod.isEnabled() || XrayMod.isEnabled()) {
            ((LightmapRenderStateMixin) (Object) state).nova_client$setBrightness(16.0F);
        }
    }
}
//?} else {
/*import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LightTexture.class)
public abstract class LightmapRenderStateExtractorMixin {
    // 1.21.11: der 7. Wert im Lightmap-Uniform ist die Helligkeit (Gamma) - wie "brightness" im 26.x-Renderzustand
    @ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;",
            ordinal = 6))
    private float nova_client$applyFullbright(float brightness) {
        // Xray braucht Licht, sonst sind die Erze unter Tage schwarz
        return FullbrightMod.isEnabled() || XrayMod.isEnabled() ? 16.0F : brightness;
    }
}
*///?}
