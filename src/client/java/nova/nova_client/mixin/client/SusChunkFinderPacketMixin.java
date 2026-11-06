package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import nova.nova_client.client.mods.SchematicBuilderMod;
import nova.nova_client.client.mods.SusChunkFinderMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sus Chunk Finder, Schematic Builder: Blockaenderungen vom Server. Eingehakt direkt nach ensureRunningOnSameThread (einmal, auf dem
 * Haupt-Thread) und bevor die Aenderung angewendet wird - so ist auch der alte Zustand noch lesbar.
 */
@Mixin(ClientPacketListener.class)
public abstract class SusChunkFinderPacketMixin {
    private static final String MAIN_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$susChunkFinderBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (SusChunkFinderMod.isEnabled()) {
            SusChunkFinderMod.onBlockChanged(packet.getPos(), packet.getBlockState());
        }
        SchematicBuilderMod.onBlockChanged(packet.getPos(), packet.getBlockState());
        nova.nova_client.client.mods.NetheriteFinderMod.onBlockChanged(packet.getPos(), packet.getBlockState());
        nova.nova_client.client.mods.StorageEspMod.onBlockChanged(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$susChunkFinderSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (SusChunkFinderMod.isEnabled()) {
            packet.runUpdates(SusChunkFinderMod::onBlockChanged);
        }
        packet.runUpdates(SchematicBuilderMod::onBlockChanged);
        if (nova.nova_client.client.mods.NetheriteFinderMod.isEnabled()) {
            packet.runUpdates(nova.nova_client.client.mods.NetheriteFinderMod::onBlockChanged);
        }
        if (nova.nova_client.client.mods.StorageEspMod.isEnabled()) {
            packet.runUpdates(nova.nova_client.client.mods.StorageEspMod::onBlockChanged);
        }
    }

    // Sus Chunk Finder: neues Licht fuer einen Chunk (wird kurz danach angewendet) -> Chunk nochmal pruefen
    @Inject(method = "handleLightUpdatePacket", at = @At("TAIL"), require = 0)
    private void nova_client$susChunkFinderLight(net.minecraft.network.protocol.game.ClientboundLightUpdatePacket packet,
                                                 CallbackInfo ci) {
        if (SusChunkFinderMod.isEnabled()) {
            SusChunkFinderMod.onLightUpdate(packet.x(), packet.z());
        }
    }
}
