package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import nova.nova_client.client.mods.SchematicBuilderMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Schematic Builder + Tunnel Base Finder: Vanilla darf das Abbauen des Bots nicht abbrechen, und Alt-Tab oeffnet kein Pause-Menue. */
@Mixin(Minecraft.class)
public abstract class FarmBuilderMinecraftMixin {
    // Ohne gedrueckte Angriffstaste ruft Vanilla jeden Tick stopDestroyBlock() auf - das wuerde jedes Abbauen
    // des Bots sofort wieder abbrechen (nur sofort zerbrechende Bloecke gingen dann noch kaputt).
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void nova_client$farmBuilderMining(boolean leftClick, CallbackInfo ci) {
        if (SchematicBuilderMod.locksInput() || nova.nova_client.client.tunnelfinder.BotControl.locksInput()
                || nova.nova_client.client.mods.SpawnerProtectMod.locksInput()) {
            ci.cancel();
        }
    }

    // Waehrend der Bot isst, ist die Benutzen-Taste gedrueckt. Vanilla darf damit nie selbst einen Rechtsklick
    // ausloesen (Kiste oeffnen, Hebel umlegen ...) - der Bot klickt immer selbst und gezielt.
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void nova_client$farmBuilderNoVanillaUse(CallbackInfo ci) {
        if (SchematicBuilderMod.locksInput() || nova.nova_client.client.tunnelfinder.BotControl.locksInput()
                || nova.nova_client.client.mods.SpawnerProtectMod.locksInput()) {
            ci.cancel();
        }
    }

    // Fenster verliert den Fokus: kein Pause-Menue, sonst steht der Bot still
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void nova_client$farmBuilderNoAutoPause(boolean showMenu, CallbackInfo ci) {
        if ((SchematicBuilderMod.locksInput() || nova.nova_client.client.tunnelfinder.BotControl.locksInput()
                || nova.nova_client.client.mods.SpawnerProtectMod.locksInput()) && !((Minecraft) (Object) this).isWindowActive()) {
            ci.cancel();
        }
    }
}
