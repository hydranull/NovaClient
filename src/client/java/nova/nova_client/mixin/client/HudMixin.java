package nova.nova_client.mixin.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.gui.Hud;
//?} else {
/*import net.minecraft.client.gui.Gui;
*///?}
import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.Objective;
import nova.nova_client.client.mods.CrosshairMod;
import nova.nova_client.client.mods.ScoreboardMod;
import nova.nova_client.client.mods.StreamProtectMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >=26.1 {
@Mixin(Hud.class)
//?} else {
/*@Mixin(Gui.class)
*///?}
public abstract class HudMixin {
    // Scoreboard-Designer: eigenes (rein visuelles) Scoreboard statt dem vom Server
    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void nova_client$customScoreboard(GuiGraphicsExtractor graphics, Objective objective, CallbackInfo ci) {
        if (ScoreboardMod.isOn()) {
            ScoreboardMod.draw(graphics, Minecraft.getInstance().font, objective, graphics.guiWidth(), graphics.guiHeight() / 2);
            ci.cancel();
        }
    }

    // Crosshair-Designer: nur das Fadenkreuz-Sprite ersetzen; Vanilla-Regeln (nur 1. Person, Angriffsanzeige) bleiben
    //? if >=26.1 {
    @Redirect(method = "extractCrosshair", at = @At(value = "INVOKE",
    //?} else {
    /*@Redirect(method = "renderCrosshair", at = @At(value = "INVOKE",
    *///?}
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V",
            ordinal = 0))
    private void nova_client$customCrosshair(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                             int x, int y, int width, int height) {
        if (StreamProtectMod.hidesCrosshair()) {
            return;
        }
        if (CrosshairMod.isOn()) {
            CrosshairMod.drawHud(graphics);
        } else {
            graphics.blitSprite(pipeline, sprite, x, y, width, height);
        }
    }
}
