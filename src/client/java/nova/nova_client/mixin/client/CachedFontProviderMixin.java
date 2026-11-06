package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GlyphSource;
import net.minecraft.network.chat.FontDescription;
import nova.nova_client.client.NovaFonts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft merkt sich die zuletzt benutzte Schrift. Fuer Nova-Schriften haengt die passende Variante aber von
 * GUI-Scale und Skalierung ab - deshalb bei ihnen jedes Mal neu nachschlagen (siehe NovaFonts).
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
public abstract class CachedFontProviderMixin {
    @Shadow
    private GlyphSource getGlyphSource(FontDescription description) {
        throw new AssertionError();
    }

    @Inject(method = "glyphs", at = @At("HEAD"), cancellable = true)
    private void nova_client$noCacheForNovaFonts(FontDescription description, CallbackInfoReturnable<GlyphSource> cir) {
        if (NovaFonts.isSharpened(description) || nova.nova_client.client.mods.CustomFontMod.replaces(description)) {
            cir.setReturnValue(getGlyphSource(description));
        }
    }

    // Der weisse Effekt-Glyph (Unterstreichen, Hintergruende) wird fuer immer zwischengespeichert: immer aus der
    // Minecraft-Schrift holen, nie aus einer Custom-Font-Variante, die spaeter freigegeben werden kann
    @Inject(method = "effect", at = @At("HEAD"), require = 0)
    private void nova_client$effectBegin(CallbackInfoReturnable<?> cir) {
        NovaFonts.effectLookup = true;
    }

    @Inject(method = "effect", at = @At("RETURN"), require = 0)
    private void nova_client$effectEnd(CallbackInfoReturnable<?> cir) {
        NovaFonts.effectLookup = false;
    }
}
