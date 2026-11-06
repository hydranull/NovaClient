package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import nova.nova_client.client.mods.BoatFlyMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boat Fly: Fahrzeug-Korrekturen des Servers verwerfen (optional) bzw. den Anti-Kick neu starten. */
@Mixin(ClientPacketListener.class)
public abstract class BoatFlyPacketMixin {
    private static final String MAIN_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void nova_client$boatFlyCancel(ClientboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (BoatFlyMod.cancelsServerMove()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$boatFlySetback(ClientboundMoveVehiclePacket packet, CallbackInfo ci) {
        BoatFlyMod.onSetback();
    }
}
