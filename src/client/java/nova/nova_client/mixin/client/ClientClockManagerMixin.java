package nova.nova_client.mixin.client;

//? if >=26.1 {
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import nova.nova_client.client.mods.TimeChangerMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientClockManager.class)
public abstract class ClientClockManagerMixin {
    // Alle Timelines (Sonne, Mond, Sterne, Himmelsfarbe, Licht) holen sich die Uhr hier: die Oberwelt-Uhr markieren,
    // ihre Ticks aendert dann ClientClockInstanceMixin
    @Inject(method = "getInstance(Lnet/minecraft/core/Holder;)Lnet/minecraft/client/ClientClockManager$ClientClockInstance;",
            at = @At("RETURN"))
    private void nova_client$markOverworld(Holder<WorldClock> clock,
                                           CallbackInfoReturnable<ClientClockManager.ClientClockInstance> cir) {
        if (cir.getReturnValue() instanceof nova.nova_client.client.NovaClock marked && clock.is(WorldClocks.OVERWORLD)) {
            marked.nova_client$setOverworld(true);
        }
    }
}
//?} else {
/*import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import nova.nova_client.client.mods.TimeChangerMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class ClientClockManagerMixin {
    // 1.21.11: alle Timelines (Sonne, Mond, Sterne, Himmelsfarbe, Licht) lesen die Zeit ueber Level.getDayTime.
    // Nur die Client-Welt der Oberwelt; das Hochzaehlen im Tick liest die Zeit direkt und bleibt unberuehrt.
    @Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true)
    private void nova_client$applyTimeChanger(CallbackInfoReturnable<Long> cir) {
        Level level = (Level) (Object) this;
        if (TimeChangerMod.isEnabled() && level instanceof ClientLevel && level.dimension() == Level.OVERWORLD) {
            cir.setReturnValue(TimeChangerMod.apply(cir.getReturnValue()));
        }
    }
}
*///?}
