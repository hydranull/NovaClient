package nova.nova_client.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import nova.nova_client.client.mods.AssistantMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {
    @Shadow
    protected EditBox input;

    protected ChatScreenMixin(Component title) {
        super(title);
    }

    // Nova Assistant: Vorschlaege ueber der Eingabe, sobald mit "." getippt wird
    //? if >=26.1 {
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    //?} else {
    /*@Inject(method = "render", at = @At("RETURN"))
    *///?}
    private void nova_client$assistantSuggestions(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
                                                  CallbackInfo ci) {
        String value = input.getValue();
        List<String> suggestions = AssistantMod.suggestions(value);
        if (suggestions.isEmpty()) {
            return;
        }
        int count = Math.min(8, suggestions.size());
        String prefix = value.substring(0, value.lastIndexOf(' ') + 1);
        int x = 4 + font.width(prefix);
        int width = 0;
        for (int i = 0; i < count; i++) {
            width = Math.max(width, font.width(nova_client$hint(prefix, suggestions.get(i))));
        }
        int y = height - 14 - count * 12;
        graphics.fill(x - 2, y - 2, x + width + 4, height - 14, 0xD0101014);
        for (int i = 0; i < count; i++) {
            String text = nova_client$hint(prefix, suggestions.get(i));
            graphics.text(font, text, x + 1, y + i * 12 + 1, i == 0 ? 0xFFFFE066 : 0xFFA8A8B8, false);
        }
    }

    /** Befehl + Argument-Hinweis ("toggle <module>"), sonst nur der Vorschlag. */
    private static String nova_client$hint(String prefix, String suggestion) {
        if (prefix.isEmpty()) {
            AssistantMod.Command command = AssistantMod.command(suggestion);
            if (command != null && !command.args().isEmpty()) {
                return AssistantMod.PREFIX + suggestion + " " + command.args();
            }
            return AssistantMod.PREFIX + suggestion;
        }
        return suggestion;
    }

    // Tab ergaenzt den ersten Vorschlag
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void nova_client$assistantTab(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != InputConstants.KEY_TAB) {
            return;
        }
        String value = input.getValue();
        List<String> suggestions = AssistantMod.suggestions(value);
        if (suggestions.isEmpty()) {
            return;
        }
        int space = value.lastIndexOf(' ');
        String completed = space < 0
                ? AssistantMod.PREFIX + suggestions.get(0) + " "
                : value.substring(0, space + 1) + suggestions.get(0) + " ";
        input.setValue(completed);
        cir.setReturnValue(true);
    }
}
