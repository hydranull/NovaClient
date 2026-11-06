package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.StreamProtectMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stream Protect in Menues: deine eigenen Plaetze, das Item an der Maus und ihre Tooltips verstecken. */
@Mixin(AbstractContainerScreen.class)
public abstract class StreamProtectScreenMixin {
    //? if >=26.1 {
    private static final String SLOT = "extractSlot";
    private static final String FLOATING = "extractFloatingItem";
    private static final String TOOLTIP = "extractTooltip";
    //?} else {
    /*private static final String SLOT = "renderSlot";
    private static final String FLOATING = "renderFloatingItem";
    private static final String TOOLTIP = "renderTooltip";
    *///?}

    @Shadow
    protected Slot hoveredSlot;

    @WrapOperation(method = SLOT, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/inventory/Slot;getItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack nova_client$streamProtectSlot(Slot slot, Operation<ItemStack> original) {
        return StreamProtectMod.container(original.call(slot), slot);
    }

    @ModifyVariable(method = FLOATING, at = @At("HEAD"), argsOnly = true)
    private ItemStack nova_client$streamProtectCarried(ItemStack stack) {
        return StreamProtectMod.carried(stack);
    }

    @Inject(method = TOOLTIP, at = @At("HEAD"), cancellable = true)
    private void nova_client$streamProtectTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (StreamProtectMod.hidesTooltip(hoveredSlot)) {
            ci.cancel();
        }
    }
}
