package nova.nova_client.mixin.client;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.LootPreviewMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    // Loot Preview: Inhalt von Shulker-Boxen/Endertruhe als Tooltip-Bild
    @Inject(method = "getTooltipImage()Ljava/util/Optional;", at = @At("RETURN"), cancellable = true)
    private void nova_client$lootPreview(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
        if (cir.getReturnValue().isEmpty() && LootPreviewMod.isOn()) {
            Optional<TooltipComponent> preview = LootPreviewMod.preview((ItemStack) (Object) this);
            if (preview.isPresent()) {
                cir.setReturnValue(preview);
            }
        }
    }
}
