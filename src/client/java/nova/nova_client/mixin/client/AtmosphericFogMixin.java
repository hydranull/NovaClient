package nova.nova_client.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.world.level.dimension.DimensionType;
import nova.nova_client.client.SkyVfx;
import nova.nova_client.client.mods.SkyVfxMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogMixin {
    // Ferne Landschaft in die Horizontfarbe des Sky-VFX-Himmels ausblenden statt in Vanilla-Himmelblau
    //? if >=26.3 {
    @Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
    private void nova_client$skyVfxFog(ClientLevel level, Camera camera, int renderDistance, float partialTick,
                                       CallbackInfoReturnable<org.joml.Vector3fc> cir) {
        if (SkyVfxMod.isEnabled() && level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD) {
            int c = SkyVfx.horizonColor();
            cir.setReturnValue(new org.joml.Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f));
        }
    }
    //?} else {
    /*@Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
    private void nova_client$skyVfxFog(ClientLevel level, Camera camera, int renderDistance, float partialTick,
                                       CallbackInfoReturnable<Integer> cir) {
        if (SkyVfxMod.isEnabled() && level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD) {
            cir.setReturnValue(0xFF000000 | SkyVfx.horizonColor());
        }
    }
    *///?}
}
