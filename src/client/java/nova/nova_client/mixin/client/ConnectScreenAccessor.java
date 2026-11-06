package nova.nova_client.mixin.client;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Statuszeile beim Verbinden ("Connecting...", "Logging in...") fuer den Nova-Ladebildschirm. */
@Mixin(ConnectScreen.class)
public interface ConnectScreenAccessor {
    @Accessor("status")
    Component nova_client$status();
}
