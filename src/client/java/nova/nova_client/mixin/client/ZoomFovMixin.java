package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import nova.nova_client.client.mods.ZoomMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if >=26.1 {
import org.spongepowered.asm.mixin.Shadow;

/**
 * Zoom aendert nur das Sichtfeld der Welt. Die Hand rechnet Minecraft mit einem eigenen, festen Sichtfeld
 * (calculateHudFov, 70 Grad) - sie bleibt deshalb an ihrem gewohnten Platz.
 */
@Mixin(Camera.class)
public abstract class ZoomFovMixin {
    @Shadow
    private boolean isPanoramicMode;

    @ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
    private float nova_client$zoom(float fov) {
        return isPanoramicMode ? fov : ZoomMod.applyFov(fov);
    }
}
//?} else {
/*import net.minecraft.client.renderer.GameRenderer;

/^*
 * Zoom aendert nur das Sichtfeld der Welt (getFov mit useFovSetting). Die Hand holt sich ihr Sichtfeld ohne die
 * Einstellung (fest 70 Grad) und bleibt deshalb an ihrem gewohnten Platz.
 ^/
@Mixin(GameRenderer.class)
public abstract class ZoomFovMixin {
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float nova_client$zoom(float fov, Camera camera, float partialTick, boolean useFovSetting) {
        return useFovSetting ? ZoomMod.applyFov(fov) : fov;
    }
}
*///?}
