package nova.nova_client.mixin.client;

import net.minecraft.world.level.chunk.LevelChunk;
import nova.nova_client.client.mods.SusChunkFinderMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    // Chunk ist komplett eingelesen: die Finder merken sich den Chunk zur Analyse vor
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void nova_client$susChunkFinderLoaded(CallbackInfo ci) {
        SusChunkFinderMod.onChunkLoaded((LevelChunk) (Object) this);
        nova.nova_client.client.mods.NetheriteFinderMod.onChunkLoaded((LevelChunk) (Object) this);
        nova.nova_client.client.mods.StorageEspMod.onChunkLoaded((LevelChunk) (Object) this);
    }
}
