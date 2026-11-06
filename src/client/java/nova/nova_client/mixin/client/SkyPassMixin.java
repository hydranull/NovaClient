package nova.nova_client.mixin.client;

import nova.nova_client.client.SkyVfx;
import nova.nova_client.client.mods.SkyVfxMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;

@Mixin(LevelRenderer.class)
public abstract class SkyPassMixin {
    // Lambda des Himmel-Passes (addSkyPass): statt des Vanilla-Himmels Sky VFX zeichnen (bleibt Vanilla, falls der
    // Sky-VFX-Shader nicht gebaut werden konnte)
    @WrapOperation(method = "lambda$addSkyPass$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SkyRenderer;render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V"))
    private void nova_client$skyVfx(SkyRenderer sky, GpuBufferSlice fog, SkyRenderState state, Operation<Void> original) {
        if (SkyVfxMod.isEnabled() && state.skybox == DimensionType.Skybox.OVERWORLD) {
            RenderSystem.setShaderFog(fog);
            if (SkyVfx.render()) {
                return;
            }
        }
        original.call(sky, fog, state);
    }
}
//?} else {
/*import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/^*
 * 1.21.11: der Himmel-Pass ruft fuer den Oberwelt-Himmel nacheinander diese SkyRenderer-Methoden auf. Die
 * Himmelsscheibe wird durch Sky VFX ersetzt, danach entfallen Sonnenauf-/untergang, Sonne/Mond/Sterne und die dunkle
 * Scheibe (bleibt alles Vanilla, falls der Sky-VFX-Shader nicht gebaut werden konnte).
 ^/
@Mixin(SkyRenderer.class)
public abstract class SkyPassMixin {
    @org.spongepowered.asm.mixin.Unique
    private static boolean nova_client$replaced;

    @Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
    private void nova_client$skyVfx(int skyColor, CallbackInfo ci) {
        nova_client$replaced = SkyVfxMod.isEnabled() && SkyVfx.render();
        if (nova_client$replaced) {
            ci.cancel();
        }
    }

    @Inject(method = {"renderSunriseAndSunset", "renderSunMoonAndStars", "renderDarkDisc"}, at = @At("HEAD"),
            cancellable = true)
    private void nova_client$skyVfxRest(CallbackInfo ci) {
        if (nova_client$replaced && SkyVfxMod.isEnabled()) {
            ci.cancel();
        }
    }
}
*///?}
