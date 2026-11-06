package nova.nova_client.mixin.client;

import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Ueberschrift, Schritt und Prozent (Welt erstellen, ...) fuer den Nova-Ladebildschirm. */
@Mixin(ProgressScreen.class)
public interface ProgressScreenAccessor {
    @Accessor("header")
    Component nova_client$header();

    @Accessor("stage")
    Component nova_client$stage();

    @Accessor("progress")
    int nova_client$progress();

    @Accessor("stop")
    boolean nova_client$stop();
}
