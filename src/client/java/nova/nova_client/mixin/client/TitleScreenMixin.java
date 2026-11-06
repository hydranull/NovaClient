package nova.nova_client.mixin.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.ModConfig;
import nova.nova_client.client.UiSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Klassischer Titelbildschirm (Nova-Look abgeschaltet): kleiner Knopf oben links, um ihn wieder einzuschalten. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void nova_client$novaLookButton(CallbackInfo ci) {
        if (UiSettings.novaScreens || ((Object) this).getClass() != TitleScreen.class) {
            return;
        }
        if (nova.nova_client.client.update.UpdateManager.ready()) {
            // Gut sichtbar oben in der Mitte, gruen hervorgehoben
            String version = nova.nova_client.client.update.UpdateManager.newVersion();
            int w = Math.min(260, width - 160);
            addRenderableWidget(Button.builder(Component.literal("Nova " + version + " ready - Quit & install")
                            .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), button -> minecraft.stop())
                    .bounds((width - w) / 2, 4, w, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Nova look"), button -> {
            UiSettings.novaScreens = true;
            ModConfig.save();
            minecraft.gui.setScreen(new TitleScreen());
        }).bounds(4, 4, 70, 20).build());
    }
}
