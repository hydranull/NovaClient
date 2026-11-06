package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import nova.nova_client.client.NovaWidgets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nova-Look fuer Minecrafts normale Knoepfe: statt der Knopf-Textur eine Nova-Flaeche (Text bleibt von Minecraft). */
@Mixin(AbstractButton.class)
public abstract class NovaButtonStyleMixin {
    //? if >=26.1 {
    @Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "renderDefaultSprite", at = @At("HEAD"), cancellable = true)
    *///?}
    private void nova_client$novaButton(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (NovaWidgets.enabled()) {
            AbstractButton self = (AbstractButton) (Object) this;
            NovaWidgets.button(graphics, self, self.isHoveredOrFocused());
            ci.cancel();
        }
    }
}
