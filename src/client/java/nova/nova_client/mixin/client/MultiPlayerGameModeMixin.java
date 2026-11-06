package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import nova.nova_client.client.mods.AutoToolMod;
import nova.nova_client.client.mods.LootPreviewMod;
import nova.nova_client.client.mods.ShieldBreakerMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    // Loot Preview: merken, welcher Behaelter geoeffnet bzw. wo ein gefuellter Shulker platziert wird
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void nova_client$lootPreviewUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                            CallbackInfoReturnable<InteractionResult> cir) {
        LootPreviewMod.onUseItemOn(player, hand, hit);
    }

    // AutoTool: vor dem Abbauen auf das beste Werkzeug wechseln
    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void nova_client$autoToolStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        AutoToolMod.onDestroyBlock(pos);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void nova_client$autoToolContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        AutoToolMod.onDestroyBlock(pos);
    }

    @Shadow
    private void ensureHasSentCarriedItem() {
        throw new AssertionError();
    }

    // Shield Breaker: vor dem Schlag zur Axt wechseln (attack schickt den Slot selbst vor dem Angriffs-Paket) ...
    @Inject(method = "attack", at = @At("HEAD"))
    private void nova_client$shieldBreakerBefore(Player player, Entity target, CallbackInfo ci) {
        if (player instanceof LocalPlayer local) {
            ShieldBreakerMod.beforeAttack(local, target);
        }
    }

    // ... und direkt danach zurueck, der alte Slot geht sofort an den Server
    @Inject(method = "attack", at = @At("RETURN"))
    private void nova_client$shieldBreakerAfter(Player player, Entity target, CallbackInfo ci) {
        if (player instanceof LocalPlayer local && ShieldBreakerMod.afterAttack(local)) {
            ensureHasSentCarriedItem();
        }
    }
}
