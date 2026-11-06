package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Abbau-Fortschritt 0..1 des aktuellen Blocks (Block Overlay). */
@Mixin(MultiPlayerGameMode.class)
public interface GameModeProgressAccessor {
    @Accessor("destroyProgress")
    float nova_client$destroyProgress();
}
