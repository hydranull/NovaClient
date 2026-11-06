package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import nova.nova_client.client.AntiXray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Merkt sich Bloecke, die der Server einzeln aktualisiert. Anti-Xray schickt dabei immer die echten
 * Bloecke (z.B. im Umkreis eines abgebauten Blocks), deshalb gelten sie als bestaetigt echt.
 */
@Mixin(ClientPacketListener.class)
public abstract class AntiXrayPacketMixin {
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"))
    private void nova_client$confirmBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        AntiXray.confirm(packet.getPos());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("HEAD"))
    private void nova_client$confirmBlocks(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates((pos, state) -> AntiXray.confirm(pos));
    }

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void nova_client$resetOnLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        AntiXray.clearConfirmed();
    }

    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void nova_client$resetOnRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        AntiXray.clearConfirmed();
    }
}
