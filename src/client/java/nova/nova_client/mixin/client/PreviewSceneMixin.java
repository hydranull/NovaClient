package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import nova.nova_client.client.PreviewScene;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Modul-Vorschau: nach dem (unsichtbaren) Anker alle Teile der Szene ins selbe Entity-Bild (siehe PreviewScene). */
@Mixin(GuiEntityRenderer.class)
public abstract class PreviewSceneMixin {
    @WrapOperation(method = "renderToTexture", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void nova_client$sceneParts(EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState camera,
                                        double x, double y, double z, PoseStack pose, SubmitNodeCollector collector,
                                        Operation<Void> original) {
        original.call(dispatcher, state, camera, x, y, z, pose, collector);
        PreviewScene.submitParts(state, dispatcher, camera, pose, collector);
    }
}
