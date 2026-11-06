package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.NovaTitleScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Alle Menues ausserhalb einer Welt (Optionen, Einzel-/Mehrspieler, ...) bekommen statt des Minecraft-Panoramas
 * den Hintergrund des Nova-Titelbildschirms - ohne Text, ohne Unschaerfe und ohne die Vanilla-Abdunklung.
 */
@Mixin(Screen.class)
public abstract class MenuBackgroundMixin {
    @Shadow
    @Final
    protected Minecraft minecraft;
    @Shadow
    public int width;
    @Shadow
    public int height;

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void nova_client$novaBackground(GuiGraphicsExtractor graphics, float delta, CallbackInfo ci) {
        if (NovaTitleScreen.replacesMenuBackground(minecraft)) {
            NovaTitleScreen.drawMenuBackground(graphics, width, height);
            ci.cancel();
        }
    }

    @Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void nova_client$noBlur(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (NovaTitleScreen.replacesMenuBackground(minecraft)) {
            ci.cancel();
        }
    }

    @Inject(method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void nova_client$noDarken(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (NovaTitleScreen.replacesMenuBackground(minecraft)) {
            ci.cancel();
        }
    }
}
