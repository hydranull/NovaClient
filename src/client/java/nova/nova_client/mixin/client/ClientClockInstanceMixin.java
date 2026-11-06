package nova.nova_client.mixin.client;

//? if >=26.1 {
import net.minecraft.client.ClientClockManager;
import nova.nova_client.client.NovaClock;
import nova.nova_client.client.mods.TimeChangerMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Time Changer (26.3): die Ticks der Oberwelt-Uhr (markiert in ClientClockManagerMixin) ersetzen. */
@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class ClientClockInstanceMixin implements NovaClock {
    @Unique
    private boolean nova_client$overworld;

    @Override
    public void nova_client$setOverworld(boolean overworld) {
        nova_client$overworld = overworld;
    }

    @Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true)
    private void nova_client$applyTimeChanger(CallbackInfoReturnable<Long> cir) {
        if (nova_client$overworld && TimeChangerMod.isEnabled()) {
            cir.setReturnValue(TimeChangerMod.apply(cir.getReturnValue()));
        }
    }
}
//?} else {
/*import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

// 1.21.11: Time Changer laeuft ueber Level.getDayTime (ClientClockManagerMixin), hier nichts
@Mixin(Level.class)
public abstract class ClientClockInstanceMixin {
}
*///?}
