package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import nova.nova_client.client.MotionBlur;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Motion Blur: in renderLevel direkt bevor die Tiefe fuer die Hand geleert wird (beide Versionen gleich aufgebaut).
 * Genau hier liegen Farbe und Tiefe der Welt vor; Hand, Bildschirm-Effekte und HUD kommen danach und bleiben scharf.
 * Die Projektion ist die erste Matrix4f-Variable der Methode (inkl. Wackeln beim Laufen).
 */
@Mixin(GameRenderer.class)
public abstract class MotionBlurMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V",
            ordinal = 0), require = 0)
    private void nova_client$motionBlur(DeltaTracker deltaTracker, CallbackInfo ci, @Local(ordinal = 0) Matrix4f projection) {
        MotionBlur.apply(projection);
    }
}
