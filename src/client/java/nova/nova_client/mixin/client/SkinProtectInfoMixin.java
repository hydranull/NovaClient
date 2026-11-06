package nova.nova_client.mixin.client;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import nova.nova_client.client.mods.NameProtectMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skin Protect: auch der eigene Kopf in der Tab-Liste (und ueberall, wo der Skin ueber PlayerInfo kommt). */
@Mixin(PlayerInfo.class)
public abstract class SkinProtectInfoMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void nova_client$skinProtect(CallbackInfoReturnable<PlayerSkin> cir) {
        if (NameProtectMod.isEnabled() && NameProtectMod.isSelf(((PlayerInfo) (Object) this).getProfile().id())) {
            PlayerSkin skin = NameProtectMod.skin(cir.getReturnValue());
            if (skin != null) {
                cir.setReturnValue(skin);
            }
        }
    }
}
