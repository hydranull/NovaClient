package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import nova.nova_client.client.NovaWidgets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nova-Look fuer Listen in Minecrafts Menues (Optionen, Server, Welten): leichter Hintergrund, feine Akzentlinien. */
@Mixin(AbstractSelectionList.class)
public abstract class NovaListStyleMixin {
    //? if >=26.1 {
    @Inject(method = "extractListBackground", at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "renderListBackground", at = @At("HEAD"), cancellable = true)
    *///?}
    private void nova_client$listBackground(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (NovaWidgets.enabled()) {
            NovaWidgets.listBackground(graphics, (AbstractSelectionList<?>) (Object) this);
            ci.cancel();
        }
    }

    //? if >=26.1 {
    @Inject(method = "extractListSeparators", at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "renderListSeparators", at = @At("HEAD"), cancellable = true)
    *///?}
    private void nova_client$listSeparators(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (NovaWidgets.enabled()) {
            NovaWidgets.listSeparators(graphics, (AbstractSelectionList<?>) (Object) this);
            ci.cancel();
        }
    }
}
