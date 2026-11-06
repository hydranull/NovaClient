package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sus Chunk Finder: Simulation Distance des Servers lesen (in so vielen Chunks um jeden Spieler tickt die Welt). */
@Mixin(ClientPacketListener.class)
public interface ClientPacketListenerAccessor {
    @Accessor("serverSimulationDistance")
    int nova_client$simulationDistance();

    /** Vom Server erlaubte Sichtweite - mehr Chunks kann er gar nicht schicken. */
    @Accessor("serverChunkRadius")
    int nova_client$serverChunkRadius();
}
