package nova.nova_client.mixin.client;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import nova.nova_client.client.FontWeights;
import org.lwjgl.util.freetype.FT_Face;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Glatte Schriften: variable Schriften in ihrer gewuenschten Strichstaerke laden (FontWeights), sauberes Fett und
 * feiner Schatten statt verschmierter Striche, Zeichen auf ganzen Pixeln (CleanGlyphs). Der Versatz ("shift") wird auf
 * ganze Raster-Pixel gerundet: FreeType verschiebt die Umrisse erst nach dem Ausrichten am Pixel-Raster - ein halber
 * Pixel (z.B. 0,5 x GUI-Scale 3) setzt jede waagrechte Kante zwischen zwei Pixelreihen, die Schrift wird unscharf.
 */
@Mixin(TrueTypeGlyphProvider.class)
public abstract class FontWeightMixin {
    private static final String SET_VECTOR =
            "Lnet/minecraft/client/gui/font/providers/FreeTypeUtil;setVector(Lorg/lwjgl/util/freetype/FT_Vector;FF)Lorg/lwjgl/util/freetype/FT_Vector;";

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = SET_VECTOR),
            index = 1, require = 0)
    private float nova_client$wholePixelShiftX(float x) {
        return Math.round(x);
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = SET_VECTOR),
            index = 2, require = 0)
    private float nova_client$wholePixelShiftY(float y) {
        return Math.round(y);
    }

    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    private float oversample;

    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getGlyph", at = @At("RETURN"))
    private com.mojang.blaze3d.font.UnbakedGlyph nova_client$cleanGlyph(com.mojang.blaze3d.font.UnbakedGlyph glyph) {
        return nova.nova_client.client.CleanGlyphs.wrap(glyph, oversample);
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/util/freetype/FreeType;FT_Set_Pixel_Sizes(Lorg/lwjgl/util/freetype/FT_Face;II)I"),
            require = 0)
    private void nova_client$fontWeight(ByteBuffer fontMemory, FT_Face face, float size, float oversample, float shiftX,
                                        float shiftY, String skip, CallbackInfo ci) {
        FontWeights.apply(fontMemory, face);
    }
}
