package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import net.minecraft.client.gui.font.FontTexture;
import nova.nova_client.client.NovaFonts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Supplier;

/**
 * Minecraft zeichnet Schrift-Texturen ohne Glaettung (fuer die Pixel-Schrift richtig). Die Nova-Schriften werden
 * geglaettet: wo ihr Raster genau auf den Bildschirm-Pixeln liegt (der Normalfall), sieht das gleich aus - gestochen
 * scharf. Wo es nicht genau passt (Ersatz-Variante, bis die passende fertig ist, Zoom- und Einblend-Animationen, Schrift
 * in der Welt), bleibt der Text glatt, statt pixelig und abgehackt zu werden (siehe NovaFonts.isNovaTexture).
 */
@Mixin(FontTexture.class)
public abstract class FontTextureMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/SamplerCache;getRepeat(Lcom/mojang/renderpearl/api/textures/FilterMode;)Lcom/mojang/renderpearl/api/textures/GpuSampler;"),
            require = 0)
    private GpuSampler nova_client$smoothNovaFont(SamplerCache cache, FilterMode mode, Operation<GpuSampler> original,
                                                  @Local(argsOnly = true) Supplier<String> label) {
        return original.call(cache, NovaFonts.isNovaTexture(label.get()) ? FilterMode.LINEAR : mode);
    }
}
