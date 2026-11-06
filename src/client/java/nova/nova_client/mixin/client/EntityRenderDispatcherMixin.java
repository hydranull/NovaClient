package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import nova.nova_client.client.mods.NameTagsMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    // Name tags: Server-Nametags (Text-Displays / unsichtbare Armorstands ueber Spielern) nicht zeichnen
    //? if >=26.1 {
    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z",
            at = @At("HEAD"), cancellable = true)
    private void nova_client$hideServerTags(Entity entity, Frustum frustum, double x, double y, double z, float partialTick,
                                            CallbackInfoReturnable<Boolean> cir) {
    //?} else {
    /*@Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void nova_client$hideServerTags(Entity entity, Frustum frustum, double x, double y, double z,
                                            CallbackInfoReturnable<Boolean> cir) {
    *///?}
        if (NameTagsMod.isServerTag(entity)) {
            cir.setReturnValue(false);
        }
    }
}
