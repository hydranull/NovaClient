package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import nova.nova_client.client.TpsTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class TpsTrackerMixin {
    @Inject(method = "handleSetTime", at = @At("HEAD"))
    private void nova_client$onSetTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        // Handler laeuft zuerst im Netzwerk-Thread und wird dann an den Hauptthread weitergereicht -> nur dort zaehlen
        if (Minecraft.getInstance().isSameThread()) {
            TpsTracker.onTimePacket();
        }
    }
}
