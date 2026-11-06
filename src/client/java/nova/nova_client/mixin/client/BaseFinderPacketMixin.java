package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import nova.nova_client.client.Compat;
import nova.nova_client.client.mods.AmethystBypassMod;
import nova.nova_client.client.mods.BlockEntityDebugMod;
import nova.nova_client.client.mods.PlayerBypassMod;
import nova.nova_client.client.mods.PrimeChunkFinderMod;
import nova.nova_client.client.mods.QuantumMod;
import nova.nova_client.client.mods.SpawnerProtectMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pakete fuer Quantum, Player Bypass, Amethyst Bypass, Block Entity Debug, Prime Chunk Finder und Spawner
 * Protect.
 * Alles nach ensureRunningOnSameThread (Hauptthread, Paket noch nicht angewendet - alte Bloecke stehen noch in der
 * Welt), ausser dem Verwerfen von Block-Updates (HEAD, auch auf dem Netzwerk-Thread) und dem Chunk-Vergleich von
 * Quantum (RETURN: Chunk ist eingelesen).
 */
@Mixin(ClientPacketListener.class)
public abstract class BaseFinderPacketMixin {
    private static final String MAIN_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$baseFinderChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        BlockEntityDebugMod.onChunkPacket(packet);
        PlayerBypassMod.onLightData(packet.x(), packet.z(), packet.lightData());
        PrimeChunkFinderMod.onChunkLight(packet.x(), packet.z(), packet.lightData());
        AmethystBypassMod.markDirty(packet.x(), packet.z());
    }

    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void nova_client$quantumChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        QuantumMod.onChunkLoaded(packet.x(), packet.z());
    }

    @Inject(method = "handleLightUpdatePacket", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$baseFinderLight(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
        PlayerBypassMod.onLightData(packet.x(), packet.z(), packet.lightData());
        PrimeChunkFinderMod.onLightUpdate(packet.x(), packet.z(), packet.lightData());
    }

    // Block Entity Debug: Updates verwerfen, die eine gefundene Block-Entity-Stelle zu Luft/Stein "verstecken" sollen
    @Inject(method = "handleBlockUpdate", at = @At("HEAD"), cancellable = true)
    private void nova_client$keepLeakedBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (BlockEntityDebugMod.hidesUpdate(packet.getPos(), packet.getBlockState())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$baseFinderBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        SpawnerProtectMod.onBlockUpdate(packet.getPos(), packet.getBlockState());
        PrimeChunkFinderMod.onBlockUpdate(packet.getPos(), packet.getBlockState());
        QuantumMod.onBlockUpdate(packet.getPos(), packet.getBlockState());
        AmethystBypassMod.markDirty(packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4);
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$baseFinderSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates((pos, state) -> {
            SpawnerProtectMod.onBlockUpdate(pos, state);
            PrimeChunkFinderMod.onBlockUpdate(pos, state);
            QuantumMod.onBlockUpdate(pos, state);
            AmethystBypassMod.markDirty(pos.getX() >> 4, pos.getZ() >> 4);
        });
    }

    @Inject(method = "handleBlockEntityData", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$blockEntityLeak(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        BlockEntityDebugMod.onBlockEntityData(packet.getPos(), packet.getType());
    }

    @Inject(method = "handleForgetLevelChunk", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$forgetChunk(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
        BlockEntityDebugMod.onForgetChunk(Compat.chunkX(packet.pos()), Compat.chunkZ(packet.pos()));
    }

    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$baseFinderLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        BlockEntityDebugMod.clear();
        PrimeChunkFinderMod.onLogin();
    }
}
