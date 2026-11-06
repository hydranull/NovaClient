package nova.nova_client.mixin.client;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Die sichtbaren Knoepfe eines Bildschirms (Nova-Ladebildschirm zeichnet nur diese ueber sich). */
@Mixin(Screen.class)
public interface ScreenRenderablesAccessor {
    @Accessor("renderables")
    List<Renderable> nova_client$renderables();
}
