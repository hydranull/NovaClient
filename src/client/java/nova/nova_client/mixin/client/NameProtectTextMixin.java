package nova.nova_client.mixin.client;

import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import nova.nova_client.client.mods.FakeMediaMod;
import nova.nova_client.client.mods.NameProtectMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NameProtect: jeder Text, den Minecraft zeichnet oder misst (auch Components aus Chat, Tab-Liste, Nametags,
 * Scoreboard), laeuft am Ende durch diese Methode - hier wird der eigene Name ersetzt. Weil auch die Breiten-
 * Berechnung hier durchlaeuft, stimmen Ausrichtung und Hintergruende automatisch.
 * Fake Media haengt sich an dieselbe Stelle und fuegt vor dem eigenen Namen das Media-Symbol ein.
 */
@Mixin(StringDecomposer.class)
public abstract class NameProtectTextMixin {
    @Shadow
    public static boolean iterateFormatted(String text, int offset, Style currentStyle, Style resetStyle,
                                           FormattedCharSink sink) {
        throw new AssertionError();
    }

    @ModifyVariable(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static String nova_client$protectName(String text) {
        return NameProtectMod.protect(text);
    }

    // Mit eigenem Sink nochmal durchlaufen, der das Symbol vor dem Namen einfuegt (der Sink verhindert eine Schleife)
    @Inject(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void nova_client$fakeMedia(String text, int offset, Style currentStyle, Style resetStyle,
                                             FormattedCharSink sink, CallbackInfoReturnable<Boolean> cir) {
        // Laeuft fuer jeden Text: ohne Fake Media gar nicht erst den Namen suchen (namePositions waere ohnehin null)
        if (sink instanceof FakeMediaMod.Sink || !FakeMediaMod.isEnabled()) {
            return;
        }
        String shown = NameProtectMod.protect(text);
        int[] positions = FakeMediaMod.namePositions(shown);
        if (positions != null) {
            cir.setReturnValue(iterateFormatted(shown, offset, currentStyle, resetStyle,
                    new FakeMediaMod.Sink(sink, positions)));
        }
    }
}
