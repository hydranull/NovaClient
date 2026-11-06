package nova.nova_client.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import nova.nova_client.client.HandGlowRenderer;
import nova.nova_client.client.mods.XrayMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
//? if <26.1 {
/*import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.injection.Redirect;
*///?}
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    @Final
    private RenderTarget entityOutlineTarget;

    //? if >=26.1 {
    // Ohne Ambient Occlusion werden Erze im Xray nicht von den (unsichtbaren) Nachbarbloecken abgedunkelt.
    // Der SectionCompiler wird bei jedem Neuaufbau (allChanged) hier neu erstellt.
    @ModifyArg(method = "invalidateCompiledGeometry", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;<init>(ZZLnet/minecraft/client/renderer/block/BlockStateModelSet;Lnet/minecraft/client/renderer/block/FluidStateModelSet;Lnet/minecraft/client/color/block/BlockColors;)V"),
            index = 0)
    private boolean nova_client$xrayNoAmbientOcclusion(boolean ambientOcclusion) {
        return ambientOcclusion && !XrayMod.isEnabled();
    }
    //?} else {
    /*// Glowing Outlines: ausserhalb des Welt-Frames (Hand-Pass) liefert Vanilla hier null -> dauerhaftes Target nehmen,
    // damit Outline-Rendertypen und HandGlowRenderer ein Ziel haben (26.3 holt sich das Ziel selbst)
    @Inject(method = "entityOutlineTarget", at = @At("RETURN"), cancellable = true)
    private void nova_client$handGlowOutlineTarget(CallbackInfoReturnable<RenderTarget> cir) {
        if (cir.getReturnValue() == null && HandGlowRenderer.inHandPass()) {
            cir.setReturnValue(entityOutlineTarget);
        }
    }

    // Freecam: kein Occlusion-Culling (wie Vanilla im Zuschauermodus) - steckt die Kamera in Bloecken oder fliegt
    // durch den Boden, haelt Minecraft sonst fast alles fuer verdeckt. (26.x: im CameraMixin ueber den Kamera-Zustand)
    @Redirect(method = "cullTerrain", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;smartCull:Z"))
    private boolean nova_client$freecamNoCulling(Minecraft minecraft) {
        return minecraft.smartCull && !nova.nova_client.client.mods.FreecamMod.isEnabled();
    }

    // ESP-Boxen: Sichtfeld dieses Frames merken (26.x hat es an der Kamera)
    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "prepareCullFrustum", at = @At("RETURN"), require = 0)
    private net.minecraft.client.renderer.culling.Frustum nova_client$viewCull(net.minecraft.client.renderer.culling.Frustum frustum) {
        nova.nova_client.client.ViewCull.capture(frustum);
        return frustum;
    }
    *///?}
}
