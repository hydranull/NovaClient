package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.item.TridentItem;
import nova.nova_client.client.mods.TridentUtilMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Trident Util: Riptide ohne Wasser/Regen und kuerzere Aufladezeit (nur fuer den eigenen Spieler auf dem Client). */
@Mixin(TridentItem.class)
public abstract class TridentUtilMixin {
    @ModifyExpressionValue(method = {"use", "releaseUsing"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"), require = 0)
    private boolean nova_client$riptideAnywhere(boolean wet) {
        return wet || TridentUtilMod.riptideAnywhere();
    }

    @ModifyConstant(method = "releaseUsing", constant = @Constant(intValue = TridentUtilMod.VANILLA_CHARGE), require = 0)
    private int nova_client$chargeTime(int vanilla) {
        return TridentUtilMod.chargeTicks(vanilla);
    }
}
