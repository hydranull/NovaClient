package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.NovaWidgets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Nova-Look fuer Minecrafts Checkboxen: runde Box, angehakt in der Akzentfarbe. */
@Mixin(Checkbox.class)
public abstract class NovaCheckboxStyleMixin {
    private static final String BLIT = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V";

    //? if >=26.1 {
    @WrapOperation(method = "extractContents", at = @At(value = "INVOKE", target = BLIT, ordinal = 0))
    //?} else {
    /*@WrapOperation(method = "renderContents", at = @At(value = "INVOKE", target = BLIT, ordinal = 0))
    *///?}
    private void nova_client$box(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y,
                                 int width, int height, int color, Operation<Void> original) {
        if (NovaWidgets.enabled()) {
            Checkbox self = (Checkbox) (Object) this;
            NovaWidgets.checkbox(graphics, x, y, width, self.selected(), self.isHoveredOrFocused());
        } else {
            original.call(graphics, pipeline, sprite, x, y, width, height, color);
        }
    }
}
