package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hinter Nova-Menues ist die Spielwelt immer leicht unscharf - mit einer festen, leichten Staerke statt der
 * Minecraft-Einstellung (die gilt weiter fuer alle anderen Menues).
 */
@Mixin(Options.class)
public abstract class MenuBlurMixin {
    @Inject(method = "getMenuBackgroundBlurriness", at = @At("HEAD"), cancellable = true)
    private void nova_client$lightBlur(CallbackInfoReturnable<Integer> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.gui != null && NovaScale.isNovaScreen(minecraft.gui.screen())) {
            cir.setReturnValue(NovaGui.MENU_BLUR);
        }
    }
}
