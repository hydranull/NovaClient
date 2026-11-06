package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.NovaWidgets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Nova-Look fuer Minecrafts Regler (Sichtweite, Lautstaerke, ...): Bahn mit Akzent-Fuellung und schmaler Griff. */
@Mixin(AbstractSliderButton.class)
public abstract class NovaSliderStyleMixin {
    private static final String BLIT = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V";

    @Shadow
    protected double value;

    //? if >=26.1 {
    @WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = BLIT, ordinal = 0))
    //?} else {
    /*@WrapOperation(method = "renderWidget", at = @At(value = "INVOKE", target = BLIT, ordinal = 0))
    *///?}
    private void nova_client$track(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y,
                                   int width, int height, int color, Operation<Void> original) {
        if (NovaWidgets.enabled()) {
            AbstractSliderButton self = (AbstractSliderButton) (Object) this;
            NovaWidgets.sliderTrack(graphics, self, value, self.isHoveredOrFocused());
        } else {
            original.call(graphics, pipeline, sprite, x, y, width, height, color);
        }
    }

    //? if >=26.1 {
    @WrapOperation(method = "extractWidgetRenderState", at = @At(value = "INVOKE", target = BLIT, ordinal = 1))
    //?} else {
    /*@WrapOperation(method = "renderWidget", at = @At(value = "INVOKE", target = BLIT, ordinal = 1))
    *///?}
    private void nova_client$handle(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y,
                                    int width, int height, int color, Operation<Void> original) {
        if (NovaWidgets.enabled()) {
            NovaWidgets.sliderHandle(graphics, x, y, width, height, ((AbstractSliderButton) (Object) this).isHoveredOrFocused());
        } else {
            original.call(graphics, pipeline, sprite, x, y, width, height, color);
        }
    }
}
