package nova.nova_client.mixin.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.NovaLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nova-Ladebildschirm statt der Minecraft-Ladebildschirme: Server verbinden, Welt/Terrain laden und Welt erstellen
 * (Fortschritt in %). Knoepfe wie "Abbrechen" bleiben bedienbar. ("Welt wird gespeichert" & Co.: GenericMessageLoadingMixin)
 */
@Mixin({ConnectScreen.class, LevelLoadingScreen.class, ProgressScreen.class})
public abstract class LoadingScreensMixin {
    //? if >=26.1 {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "render", at = @At("HEAD"), cancellable = true)
    *///?}
    private void nova_client$novaLoading(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        if (!NovaLoadingScreen.enabled()) {
            return;
        }
        Object self = this;
        float progress = -1.0F;
        Component status;
        if (self instanceof ConnectScreen) {
            status = ((ConnectScreenAccessor) self).nova_client$status();
        } else if (self instanceof LevelLoadingScreen) {
            LevelLoadingScreenAccessor level = (LevelLoadingScreenAccessor) self;
            if (level.nova_client$loadTracker() != null && level.nova_client$loadTracker().hasProgress()) {
                progress = level.nova_client$smoothedProgress();
            }
            status = Component.translatable("multiplayer.downloadingTerrain");
        } else if (self instanceof ProgressScreen) {
            ProgressScreenAccessor screen = (ProgressScreenAccessor) self;
            if (screen.nova_client$stop()) {
                return; // fertig: Minecraft schliesst den Bildschirm selbst
            }
            if (screen.nova_client$stage() != null && screen.nova_client$progress() != 0) {
                progress = screen.nova_client$progress() / 100.0F;
                status = screen.nova_client$stage();
            } else {
                status = screen.nova_client$header();
            }
        } else {
            return;
        }
        Screen screen = (Screen) self;
        java.util.List<Renderable> widgets = ((ScreenRenderablesAccessor) screen).nova_client$renderables();
        NovaLoadingScreen.drawScreen(graphics, screen, widgets, mouseX, mouseY, partialTick, progress, status);
        ci.cancel();
    }
}
