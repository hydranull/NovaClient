package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import nova.nova_client.client.mods.ActivityInspectorMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Activity Inspector: Himmelslicht-Daten aus Chunk- und Licht-Paketen. Eingehakt direkt nach
 * ensureRunningOnSameThread, damit jedes Paket nur einmal (auf dem Haupt-Thread) ankommt.
 */
@Mixin(ClientPacketListener.class)
public abstract class ActivityInspectorPacketMixin {
    private static final String MAIN_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleLevelChunkWithLight", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$activityInspectorChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ActivityInspectorMod.onLightData(packet.x(), packet.z(), packet.lightData());
    }

    @Inject(method = "handleLightUpdatePacket", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$activityInspectorLight(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
        ActivityInspectorMod.onLightData(packet.x(), packet.z(), packet.lightData());
    }
}
