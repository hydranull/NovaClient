package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import nova.nova_client.client.NovaScale;
import nova.nova_client.client.mods.EspMod;
import nova.nova_client.client.mods.AnchorMacroMod;
import nova.nova_client.client.mods.LegitCrystalMacroMod;
import nova.nova_client.client.mods.TargetEspMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    // ESP: Entities leuchten (Umriss durch Waende), nur clientseitig
    @Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
    private void nova_client$esp(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (EspMod.shouldGlow(entity) || TargetEspMod.shouldGlow(entity)) {
            cir.setReturnValue(true);
        }
    }

    // LegitCrystalMacro / AnchorMacro setzen selbst -> Vanilla-Rechtsklick waehrenddessen aus
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void nova_client$macroNoUse(CallbackInfo ci) {
        if (LegitCrystalMacroMod.blocksVanillaUse() || AnchorMacroMod.blocksVanillaUse()) {
            ci.cancel();
        }
    }

    // Eigene Menue-Groesse: bei Fenster-/Einstellungsaenderungen fuer Nova-Menues den gewaehlten Scale behalten
    @ModifyArg(method = "resizeGui", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;setGuiScale(I)V"))
    private int nova_client$menuScale(int scale) {
        return NovaScale.scaleFor(((Minecraft) (Object) this).gui.screen(), scale);
    }
}
