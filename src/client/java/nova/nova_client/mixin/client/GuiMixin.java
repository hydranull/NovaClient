package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.Gui;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import nova.nova_client.client.NovaScale;
import nova.nova_client.client.NovaTitleScreen;
import nova.nova_client.client.UiSettings;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.x verwaltet Bildschirme in Gui, 1.21.11 direkt in Minecraft
//? if >=26.1 {
@Mixin(Gui.class)
//?} else {
/*@Mixin(Minecraft.class)
*///?}
public abstract class GuiMixin {
    //? if >=26.1 {
    @Shadow
    @Final
    private Minecraft minecraft;
    //?}

    @Shadow
    private boolean clientLevelTeardownInProgress;

    @Shadow
    public abstract void setScreen(Screen screen);

    // Titelbildschirm im Nova-Stil: wo immer Minecraft den normalen Titelbildschirm oeffnen will - ausdruecklich
    // oder weil ohne Welt "kein Bildschirm" gesetzt wird -, wird stattdessen der eigene geoeffnet
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void nova_client$titleScreen(Screen screen, CallbackInfo ci) {
        //? if >=26.1 {
        Minecraft minecraft = this.minecraft;
        //?} else {
        /*Minecraft minecraft = (Minecraft) (Object) this;
        *///?}
        // Pausemenue im Nova-Stil (wie der Titelbildschirm; "Classic menu" schaltet beides zurueck)
        if (UiSettings.novaScreens && screen != null && screen.getClass() == net.minecraft.client.gui.screens.PauseScreen.class
                && ((net.minecraft.client.gui.screens.PauseScreen) screen).showsPauseMenu()) {
            setScreen(new nova.nova_client.client.NovaPauseScreen());
            ci.cancel();
            return;
        }
        boolean title = screen == null
                ? minecraft.level == null && !clientLevelTeardownInProgress
                : screen.getClass() == TitleScreen.class;
        if (!UiSettings.novaScreens) {
            return;
        }
        if (title) {
            setScreen(new NovaTitleScreen());
            ci.cancel();
        }
    }

    // Eigene Menue-Groesse: GUI-Scale umstellen, bevor der neue Bildschirm aufgebaut wird
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void nova_client$menuScale(Screen screen, CallbackInfo ci) {
        NovaScale.beforeSetScreen(screen);
    }
}
