package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import nova.nova_client.client.NovaLoadingScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nova-Ladebildschirm beim Spielstart und beim Neuladen der Ressourcen (F3+T, Ressourcenpakete). Minecraft regelt das
 * Ein-/Ausblenden und das Ende selbst - Nova legt sich darueber, mit derselben Deckkraft. Den roten Hintergrund, das
 * Mojang-Logo und den Balken von Minecraft gibt es dann nicht (sonst schienen sie beim Einblenden durch).
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    @Shadow
    @Final
    private boolean fadeIn;
    @Shadow
    private float currentProgress;
    @Shadow
    private long fadeOutStart;
    @Shadow
    private long fadeInStart;

    //? if >=26.1 {
    @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"), index = 4)
    //?} else {
    /*@ModifyArg(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"), index = 4)
    *///?}
    private int nova_client$hideBackground(int color) {
        return NovaLoadingScreen.enabled() ? 0 : color;
    }

    //? if >=26.1 {
    @ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;white(F)I"))
    //?} else {
    /*@ModifyArg(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ARGB;white(F)I"))
    *///?}
    private float nova_client$hideLogo(float alpha) {
        return NovaLoadingScreen.enabled() ? 0.0F : alpha;
    }

    //? if >=26.1 {
    @Inject(method = "extractProgressBar", at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "drawProgressBar", at = @At("HEAD"), cancellable = true)
    *///?}
    private void nova_client$hideBar(CallbackInfo ci) {
        if (NovaLoadingScreen.enabled()) {
            ci.cancel();
        }
    }

    //? if >=26.1 {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    //?} else {
    /*@Inject(method = "render", at = @At("TAIL"))
    *///?}
    private void nova_client$novaLoading(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        if (!NovaLoadingScreen.enabled()) {
            return;
        }
        long now = Util.getMillis();
        float fadeOut = fadeOutStart > -1L ? (now - fadeOutStart) / 1000.0F : -1.0F;
        float alpha;
        if (fadeOut >= 1.0F) {
            alpha = 1.0F - Mth.clamp(fadeOut - 1.0F, 0.0F, 1.0F);
        } else if (fadeIn) {
            alpha = fadeInStart > -1L ? Mth.clamp((now - fadeInStart) / 500.0F, 0.15F, 1.0F) : 0.15F;
        } else {
            alpha = 1.0F;
        }
        graphics.nextStratum();
        // Beim Spielstart gibt es noch keine Schriften: dann nur Logo und Balken
        NovaLoadingScreen.draw(graphics, graphics.guiWidth(), graphics.guiHeight(), alpha,
                fadeOut >= 0.0F ? 1.0F : currentProgress, fadeIn ? "Reloading resources" : null);
    }
}
