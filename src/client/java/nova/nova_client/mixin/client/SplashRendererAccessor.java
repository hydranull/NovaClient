package nova.nova_client.mixin.client;

import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Nova-Titelbildschirm: Text des Splash-Spruchs lesen, um ihn im eigenen Stil zu zeichnen. */
@Mixin(SplashRenderer.class)
public interface SplashRendererAccessor {
    @Accessor("splash")
    Component nova_client$splash();
}
