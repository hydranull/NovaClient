package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import nova.nova_client.client.CombatEvents;
import nova.nova_client.client.mods.AutoTotemMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    private int nova_client$lastTotemEntityId = Integer.MIN_VALUE;
    private long nova_client$lastTotemGameTime = Long.MIN_VALUE;

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void nova_client$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (!nova_client$isTotemPopEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || packet.getEventId() != 35) {
            return;
        }

        // Der erste Aufruf kommt vom Netzwerk-Thread (schnellste Reaktion), waehrend der Haupt-Thread die Entity-Liste
        // aendern kann. Ein Fehler hier darf nie nach oben durchschlagen - sonst trennt Minecraft die Verbindung.
        // Klappt es nicht, erledigt das der zweite Aufruf auf dem Haupt-Thread.
        Entity entity;
        try {
            entity = packet.getEntity(client.level);
        } catch (RuntimeException e) {
            return;
        }
        if (!(entity instanceof Player player) || client.player == null) {
            return;
        }

        // Das Paket kommt zweimal an (Netzwerk-Thread, dann Haupt-Thread) - nur einmal zaehlen
        long gameTime = client.level.getGameTime();
        if (player.getId() == nova_client$lastTotemEntityId
                && gameTime == nova_client$lastTotemGameTime) {
            return;
        }

        nova_client$lastTotemEntityId = player.getId();
        nova_client$lastTotemGameTime = gameTime;

        if (player == client.player) {
            AutoTotemMod.onTotemPop();
        }
    }

    private static boolean nova_client$isTotemPopEnabled() {
        return AutoTotemMod.isEnabled();
    }

    // Kampf-Ereignisse fuer Pop Counter, Damage Numbers und Kill Effects. Die Pakete kommen erst auf dem
    // Netzwerk-Thread und dann noch einmal auf dem Haupt-Thread an - nur der Haupt-Thread zaehlt.

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void nova_client$combatEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.isSameThread() && client.level != null) {
            CombatEvents.onEntityEvent(packet.getEntity(client.level), packet.getEventId());
        }
    }

    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void nova_client$combatDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            CombatEvents.onDamage(packet.entityId(), packet.sourceCauseId());
        }
    }

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void nova_client$combatAnimate(ClientboundAnimatePacket packet, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) {
            CombatEvents.onAnimate(packet.getId(), packet.getAction());
        }
    }
}
