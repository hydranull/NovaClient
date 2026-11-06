package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import nova.nova_client.client.mods.NameTagsMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    // Name tags: normalen Namen ueber Spielern (und ueber Server-Nametag-Entities) ausblenden
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("HEAD"), cancellable = true)
    private void nova_client$hideName(Entity entity, double distanceSqr, CallbackInfoReturnable<Boolean> cir) {
        if (NameTagsMod.isEnabled() && (entity instanceof Player || NameTagsMod.isServerTag(entity))) {
            cir.setReturnValue(false);
        }
    }
}
