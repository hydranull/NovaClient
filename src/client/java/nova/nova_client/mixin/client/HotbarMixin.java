package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.gui.Hud;
//?} else {
/*import net.minecraft.client.gui.Gui;
*///?}
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.NovaHotbar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hotbar im Nova-Stil: die Bilder der Leiste, der Auswahl und der Neben-Hand sowie die Stueckzahl zeichnet
 * {@link NovaHotbar}. (Verkettbare Wrapper statt Redirect - Stream Protect haengt an denselben Aufrufen.)
 */
//? if >=26.1 {
@Mixin(Hud.class)
//?} else {
/*@Mixin(Gui.class)
*///?}
public abstract class HotbarMixin {
    //? if >=26.1 {
    private static final String HOTBAR = "extractItemHotbar";
    private static final String SLOT = "extractSlot";
    private static final String DECORATIONS = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V";
    //?} else {
    /*private static final String HOTBAR = "renderItemHotbar";
    private static final String SLOT = "renderSlot";
    private static final String DECORATIONS = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;renderItemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V";
    *///?}

    @WrapOperation(method = HOTBAR, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void nova_client$hotbarSprite(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite, int x, int y,
                                          int width, int height, Operation<Void> original) {
        if (!NovaHotbar.sprite(graphics, sprite, x, y, width, height)) {
            original.call(graphics, pipeline, sprite, x, y, width, height);
        }
    }

    @WrapOperation(method = SLOT, at = @At(value = "INVOKE", target = DECORATIONS))
    private void nova_client$hotbarCount(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y,
                                         Operation<Void> original) {
        NovaHotbar.decorations(graphics, font, stack, x, y);
    }
}
