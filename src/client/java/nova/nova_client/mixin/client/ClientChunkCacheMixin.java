package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    // Chunk wird entladen: Chunk-Finder vergessen ihn
    @Inject(method = "drop", at = @At("HEAD"))
    private void nova_client$deepViewDrop(ChunkPos pos, CallbackInfo ci) {
        nova.nova_client.client.mods.ActivityInspectorMod.onChunkUnloaded(pos);
    }
}
