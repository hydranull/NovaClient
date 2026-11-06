package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import nova.nova_client.client.mods.AntiVanishMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti Vanish: Tab-Liste, System-Chat und die eigene Vervollstaendigungs-Abfrage mitlesen. Tab-Aenderungen werden vor
 * dem Anwenden gelesen (Namen stehen noch in der Liste); die Antwort auf die eigene Abfrage wird verworfen, damit sie
 * nicht im Chat-Fenster auftaucht.
 */
@Mixin(ClientPacketListener.class)
public abstract class AntiVanishPacketMixin {
    private static final String MAIN_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Inject(method = "handlePlayerInfoRemove", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishRemove(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
        AntiVanishMod.onTabRemove(packet.profileIds());
    }

    @Inject(method = "handlePlayerInfoUpdate", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishUpdate(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
        AntiVanishMod.onTabUpdate(packet);
        // Adminlist: Spielmodus-Wechsel kommen nur fuer Spieler in deiner Region
        nova.nova_client.client.mods.AdminListMod.onTabUpdate(packet);
    }

    @Inject(method = "handleSystemChat", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (!packet.overlay()) {
            AntiVanishMod.onSystemChat(packet.content());
        }
        nova.nova_client.client.mods.PlayerBypassMod.onSystemChat(packet.content());
    }

    // Antwort auf die eigene /msg-Abfrage: abfangen (laeuft auch auf dem Netzwerk-Thread - dort schon verwerfen)
    @Inject(method = "handleCommandSuggestions", at = @At("HEAD"), cancellable = true)
    private void nova_client$antiVanishProbe(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
        if (AntiVanishMod.consumeSuggestions(packet)) {
            ci.cancel();
        }
    }

    // DonutSMP-Teams "NN-name-true/false": Rang und Ankunft/Abgang in deinem Teil der Welt
    @Inject(method = "handleSetPlayerTeamPacket", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishTeam(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
        AntiVanishMod.onTeam(packet);
    }

    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        AntiVanishMod.onLogin();
    }

    // Sensoren: Geraeusche, Partikel, Explosionen (Filter) und Wesen-Daten (Unsichtbar-Flag)

    @Inject(method = "handleSoundEvent", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishSound(ClientboundSoundPacket packet, CallbackInfo ci) {
        AntiVanishMod.onSound(packet);
    }

    @Inject(method = "handleSoundEntityEvent", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishEntitySound(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        AntiVanishMod.onEntitySound(packet);
    }

    @Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        AntiVanishMod.onParticles(packet);
    }

    @Inject(method = "handleExplosion", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void nova_client$antiVanishExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        AntiVanishMod.onExplosion(packet);
    }

    // erst nach dem Anwenden, damit isInvisible() schon den neuen Wert liefert
    @Inject(method = "handleSetEntityData", at = @At("RETURN"))
    private void nova_client$antiVanishEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        AntiVanishMod.onEntityData(packet.id());
    }
}
