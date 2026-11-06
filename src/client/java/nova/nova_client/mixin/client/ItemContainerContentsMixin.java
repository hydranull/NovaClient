package nova.nova_client.mixin.client;

import net.minecraft.world.item.component.ItemContainerContents;
import nova.nova_client.client.mods.LootPreviewMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemContainerContents.class)
public abstract class ItemContainerContentsMixin {
    // Loot Preview zeigt den Inhalt als Raster -> die Vanilla-Textzeilen ("Diamond x5 ...") waeren doppelt
    @Inject(method = "addToTooltip", at = @At("HEAD"), cancellable = true)
    private void nova_client$hideContentLines(CallbackInfo ci) {
        if (LootPreviewMod.showing()) {
            ci.cancel();
        }
    }
}
