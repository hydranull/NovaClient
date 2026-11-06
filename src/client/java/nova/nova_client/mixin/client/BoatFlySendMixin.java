package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import nova.nova_client.client.mods.BoatFlyMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Boat Fly Anti-Kick: ausgehende Fahrzeug-Positionen ab und zu leicht absenken. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class BoatFlySendMixin {
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), argsOnly = true)
    private Packet<?> nova_client$boatFlyAntiKick(Packet<?> packet) {
        return BoatFlyMod.onSend(packet);
    }
}
