package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import nova.nova_client.client.mods.AntiVanishMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Anti Vanish: eigene Klicks/Schlaege mitlesen, damit eigene Geraeusche und Partikel keinen Fehlalarm ausloesen. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class AntiVanishSendMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void nova_client$antiVanishSend(Packet<?> packet, CallbackInfo ci) {
        AntiVanishMod.onSend(packet);
    }
}
