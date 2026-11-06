package nova.nova_client.mixin.client;

import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import nova.nova_client.client.mods.AutoWalkMod;
import nova.nova_client.client.mods.FreecamMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    // Im Freecam steuern die Tasten die Kamera; der Spieler bekommt nur, was Freecam ihm laesst (weiterlaufen/schleichen)
    @Inject(method = "tick", at = @At("RETURN"))
    private void nova_client$blockFreecamInput(CallbackInfo ci) {
        // Spawner Protect sichert gerade die Spawner: seine "Tasten" gehen vor (auch vor Freecam)
        Input protect = nova.nova_client.client.mods.SpawnerProtectMod.inputOverride();
        if (protect != null) {
            nova_client$apply(protect);
            return;
        }
        if (FreecamMod.isEnabled()) {
            nova_client$apply(FreecamMod.bodyInput(keyPresses));
            return;
        }
        // Schematic Builder: der Bot laeuft mit echten "Tasten"
        Input bot = nova.nova_client.client.mods.SchematicBuilderMod.inputOverride();
        // Tunnel Base Finder genauso
        if (bot == null) {
            bot = nova.nova_client.client.tunnelfinder.BotControl.inputOverride();
        }
        if (bot != null) {
            nova_client$apply(bot);
            return;
        }
        // Auto Walk: immer vorwaerts (auch wenn S gedrueckt wird), seitlich laufen, springen usw. bleiben moeglich
        if (AutoWalkMod.shouldWalk()) {
            Input keys = keyPresses;
            keyPresses = new Input(true, false, keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint());
            float strafe = keys.left() == keys.right() ? 0.0F : keys.left() ? 1.0F : -1.0F;
            moveVector = new Vec2(strafe, 1.0F).normalized();
        }
    }

    /** Bot-"Tasten" uebernehmen (Bewegung wie von der Tastatur). */
    @org.spongepowered.asm.mixin.Unique
    private void nova_client$apply(Input bot) {
        keyPresses = bot;
        float forward = bot.forward() == bot.backward() ? 0.0F : bot.forward() ? 1.0F : -1.0F;
        float strafe = bot.left() == bot.right() ? 0.0F : bot.left() ? 1.0F : -1.0F;
        Vec2 move = new Vec2(strafe, forward);
        moveVector = forward == 0.0F && strafe == 0.0F ? Vec2.ZERO : move.normalized();
    }
}
