package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import nova.nova_client.client.NovaWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fenstertitel "Nova Client · Minecraft <Version>" statt "Minecraft* <Version>". */
@Mixin(Minecraft.class)
public abstract class NovaTitleBarMixin {
    @Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
    private void nova_client$novaTitle(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(NovaWindow.title());
    }
}
