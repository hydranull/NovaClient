package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.util.FormattedCharSequence;
import nova.nova_client.client.NovaFonts;
import org.joml.Matrix3x2fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GUI-Text wird erst beim Zeichnen in Glyphen zerlegt. Dabei die echte Pixeldichte (GUI-Scale x Skalierung des
 * Textes) bekannt machen, damit Nova-Schriften genau passend gerastert werden (siehe NovaFonts), und den Text-Anfang
 * auf einen ganzen Bildschirm-Pixel setzen, damit das Raster genau auf den Bildschirm-Pixeln liegt.
 */
@Mixin(GuiTextRenderState.class)
public abstract class GuiTextRenderStateMixin {
    @Shadow
    @Final
    public Matrix3x2fc pose;

    @Inject(method = "ensurePrepared", at = @At("HEAD"))
    private void nova_client$beginText(CallbackInfoReturnable<?> cir) {
        NovaFonts.beginText(pose);
    }

    @Inject(method = "ensurePrepared", at = @At("RETURN"))
    private void nova_client$endText(CallbackInfoReturnable<?> cir) {
        NovaFonts.endText();
    }

    @WrapOperation(method = "ensurePrepared", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;"),
            require = 0)
    private Font.PreparedText nova_client$pixelAligned(Font font, FormattedCharSequence text, float x, float y, int color,
                                                       boolean dropShadow, boolean includeEmpty, int backgroundColor,
                                                       Operation<Font.PreparedText> original) {
        return original.call(font, text, NovaFonts.snapX(pose, x, y), NovaFonts.snapY(pose, x, y), color, dropShadow,
                includeEmpty, backgroundColor);
    }
}
