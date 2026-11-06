package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.NovaLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nova-Ladebildschirm fuer kurze Meldungen ohne Knoepfe ("Welt wird gespeichert", "Laden ..."). GenericMessageScreen
 * zeichnet nichts selbst (nur ein Text-Element), deshalb wird hier in Screen eingehakt.
 */
@Mixin(Screen.class)
public abstract class GenericMessageLoadingMixin {
    //? if >=26.1 {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "render", at = @At("HEAD"), cancellable = true)
    *///?}
    private void nova_client$novaMessage(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        if ((Object) this instanceof GenericMessageScreen && NovaLoadingScreen.enabled()) {
            Screen screen = (Screen) (Object) this;
            NovaLoadingScreen.draw(graphics, screen.width, screen.height, 1.0F, -1.0F, screen.getTitle().getString());
            ci.cancel();
        }
    }
}
