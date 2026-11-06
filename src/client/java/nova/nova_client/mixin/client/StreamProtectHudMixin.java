package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.gui.Hud;
//?} else {
/*import net.minecraft.client.gui.Gui;
*///?}
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.StreamProtectMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stream Protect im HUD: Hotbar-Items ersetzen, Item-Namen und Auswahl-Rahmen ausblenden. */
//? if >=26.1 {
@Mixin(Hud.class)
//?} else {
/*@Mixin(Gui.class)
*///?}
public abstract class StreamProtectHudMixin {
    //? if >=26.1 {
    private static final String SLOT = "extractSlot";
    private static final String ITEM_NAME = "extractSelectedItemName";
    private static final String HOTBAR = "extractItemHotbar";
    //?} else {
    /*private static final String SLOT = "renderSlot";
    private static final String ITEM_NAME = "renderSelectedItemName";
    private static final String HOTBAR = "renderItemHotbar";
    *///?}

    // seed: 1..9 = Hotbar-Plaetze, 10 = Neben-Hand
    @ModifyVariable(method = SLOT, at = @At("HEAD"), argsOnly = true)
    private ItemStack nova_client$streamProtectHotbar(ItemStack stack, @Local(argsOnly = true, ordinal = 2) int seed) {
        return StreamProtectMod.hotbar(stack, seed);
    }

    @Inject(method = ITEM_NAME, at = @At("HEAD"), cancellable = true)
    private void nova_client$streamProtectItemName(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (StreamProtectMod.hidesItemName()) {
            ci.cancel();
        }
    }

    // Zweites Sprite der Hotbar = Rahmen um den gewaehlten Platz
    @WrapWithCondition(method = HOTBAR, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
            ordinal = 1))
    private boolean nova_client$streamProtectSelection(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                                       int x, int y, int width, int height) {
        return !StreamProtectMod.hidesSelectedSlot();
    }
}
