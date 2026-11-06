package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.VrMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** VR Menu: Nova-Menues nach dem HUD abtrennen und ohne Vanilla-Unschaerfe/-Abdunklung aufbauen. */
@Mixin(Screen.class)
public abstract class VrMenuScreenMixin {
    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;nextStratum()V", ordinal = 0, shift = At.Shift.AFTER))
    private void nova_client$vrSplit(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (VrMenu.beginFrame((Screen) (Object) this)) {
            try {
                // Alles ab hier landet im Glasfenster (der GuiRenderer trennt an der Unschaerfe-Stelle)
                graphics.blurBeforeThisStratum();
            } catch (IllegalStateException ignored) {
                // schon gesetzt - die Trennstelle existiert bereits
            }
        }
    }

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void nova_client$vrNoBlur(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (VrMenu.drawsScreen((Screen) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void nova_client$vrNoDarken(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (VrMenu.drawsScreen((Screen) (Object) this)) {
            ci.cancel();
        }
    }
}
