package nova.nova_client.mixin.client;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import nova.nova_client.client.toolskins.SaberMoves;
//? if >=26.1 {
import net.minecraft.world.item.component.SwingAnimation;
//?}
import nova.nova_client.client.mods.SwingSpeedMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Swing Speed: der eigene Schlag dauert laenger oder kuerzer. 26.3 fragt die Dauer einmal beim Start des Schlags ab
 * (getModifiedSwingDuration); 1.21.11 zaehlt selbst mit Kommazahl-Fortschritt, damit krumme Faktoren fluessig laufen.
 * <p>
 * Hyper Tools: beginnt ein neuer Schwung des eigenen Spielers (Haupthand), startet das Laserschwert seinen naechsten
 * Move (SaberMoves).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    //? if >=26.1 {
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
            at = @At("RETURN"))
    private void nova_client$saberSwing(InteractionHand hand, SwingAnimation animation, boolean fromServer,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && hand == InteractionHand.MAIN_HAND
                && (Object) this == Minecraft.getInstance().player) {
            SaberMoves.onSwing();
        }
    }

    @Inject(method = "getModifiedSwingDuration", at = @At("RETURN"), cancellable = true)
    private void nova_client$scaleSwingDuration(SwingAnimation animation, CallbackInfoReturnable<Integer> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (SwingSpeedMod.applies(self)) {
            cir.setReturnValue(Math.max(1, Math.round(SwingSpeedMod.duration(cir.getReturnValue()))));
        }
    }
    //?} else {
    /*@Shadow
    private int getCurrentSwingDuration() {
        throw new AssertionError();
    }

    // Vanilla-Dauer der letzten Abfrage (inkl. Haste / Mining Fatigue).
    @Unique
    private int nova_client$vanillaSwing = 6;
    // Fortschritt in Ticks als Kommazahl, damit auch krumme Faktoren fluessig laufen.
    @Unique
    private float nova_client$swingProgress;

    // Gerundete Dauer: swing() startet die Animation erst neu, wenn die Haelfte vorbei ist
    @Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true)
    private void nova_client$scaleSwingDuration(CallbackInfoReturnable<Integer> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        nova_client$vanillaSwing = cir.getReturnValue();
        if (SwingSpeedMod.applies(self)) {
            cir.setReturnValue(Math.max(1, Math.round(SwingSpeedMod.duration(nova_client$vanillaSwing))));
        }
    }

    // Eigener Ablauf mit Kommazahl-Fortschritt statt ganzer Ticks
    @Inject(method = "updateSwingTime", at = @At("HEAD"), cancellable = true)
    private void nova_client$updateSwingTime(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!SwingSpeedMod.applies(self)) {
            nova_client$swingProgress = self.swingTime;
            return;
        }
        ci.cancel();
        getCurrentSwingDuration();
        float duration = SwingSpeedMod.duration(nova_client$vanillaSwing);
        if (self.swinging) {
            // swing() setzt swingTime auf -1 = neuer Schlag
            if (self.swingTime < 0) {
                nova_client$swingProgress = -1.0F;
            }
            nova_client$swingProgress += 1.0F;
            if (nova_client$swingProgress >= duration) {
                nova_client$swingProgress = 0.0F;
                self.swinging = false;
            }
        } else {
            nova_client$swingProgress = 0.0F;
        }
        self.swingTime = (int) nova_client$swingProgress;
        self.attackAnim = nova_client$swingProgress / duration;
    }

    // Hyper Tools: swing() startet einen neuen Schwung, wenn swingTime danach -1 ist
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("RETURN"))
    private void nova_client$saberSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.swingTime == -1 && hand == InteractionHand.MAIN_HAND && self == Minecraft.getInstance().player) {
            SaberMoves.onSwing();
        }
    }
    *///?}
}
