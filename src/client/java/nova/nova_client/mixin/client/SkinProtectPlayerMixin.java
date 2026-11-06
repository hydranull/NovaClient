package nova.nova_client.mixin.client;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import nova.nova_client.client.NovaCape;
import nova.nova_client.client.mods.NameProtectMod;
import nova.nova_client.client.mods.NovaCapeMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skin Protect: der eigene Spieler (Welt, Inventar, Third Person) bekommt den gewaehlten Skin. */
@Mixin(AbstractClientPlayer.class)
public abstract class SkinProtectPlayerMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void nova_client$skinProtect(CallbackInfoReturnable<PlayerSkin> cir) {
        if (NameProtectMod.isEnabled() && NameProtectMod.isSelf(((AbstractClientPlayer) (Object) this).getUUID())) {
            PlayerSkin skin = NameProtectMod.skin(cir.getReturnValue());
            if (skin != null) {
                cir.setReturnValue(skin);
            }
        }
        // Nova Cape: eigenes animiertes Cape (nach Skin Protect, damit beides zusammen geht)
        if (NovaCapeMod.isEnabled() && NameProtectMod.isSelf(((AbstractClientPlayer) (Object) this).getUUID())) {
            cir.setReturnValue(NovaCape.withCape(cir.getReturnValue()));
        }
    }
}
