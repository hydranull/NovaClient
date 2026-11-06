package nova.nova_client.mixin.client;

import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fortschritt beim Laden der Welt fuer den Nova-Ladebildschirm. */
@Mixin(LevelLoadingScreen.class)
public interface LevelLoadingScreenAccessor {
    @Accessor("loadTracker")
    LevelLoadTracker nova_client$loadTracker();

    @Accessor("smoothedProgress")
    float nova_client$smoothedProgress();
}
