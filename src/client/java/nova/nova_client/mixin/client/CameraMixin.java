package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
//? if >=26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} else {
/*import net.minecraft.world.level.Level;
*///?}
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.mods.ClearLiquidsMod;
import nova.nova_client.client.mods.FreecamMod;
import nova.nova_client.client.mods.FreelookMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    //? if >=26.1 {
    private static final String ALIGN = "alignWithEntity";
    //?} else {
    /*private static final String ALIGN = "setup";
    *///?}

    // Freelook: die Kamera nimmt die eigene Drehung statt der des Spielers (gilt auch fuer die 3rd-Person-Bahn)
    @WrapOperation(method = ALIGN, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float nova_client$freelookYaw(Entity entity, float partialTick, Operation<Float> original) {
        if (FreelookMod.isActive() && entity == Minecraft.getInstance().player) {
            return FreelookMod.yaw();
        }
        return original.call(entity, partialTick);
    }

    @WrapOperation(method = ALIGN, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float nova_client$freelookPitch(Entity entity, float partialTick, Operation<Float> original) {
        if (FreelookMod.isActive() && entity == Minecraft.getInstance().player) {
            return FreelookMod.pitch();
        }
        return original.call(entity, partialTick);
    }

    // Freelook: 3rd-Person-Kamera geht durch Waende (kein Heranzoomen an Hindernisse)
    @WrapOperation(method = ALIGN, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"))
    private float nova_client$freelookWalls(Camera camera, float distance, Operation<Float> original) {
        return FreelookMod.ignoresWalls() ? distance : original.call(camera, distance);
    }

    //? if >=26.1 {
    @Inject(method = "alignWithEntity", at = @At("RETURN"))
    private void nova_client$applyFreecam(float partialTick, CallbackInfo ci) {
    //?} else {
    /*@Inject(method = "setup", at = @At("RETURN"))
    private void nova_client$applyFreecam(Level level, Entity entity, boolean detachedView, boolean mirrored,
                                          float partialTick, CallbackInfo ci) {
    *///?}
        if (!FreecamMod.isEnabled()) {
            return;
        }

        // detached = true -> der eigene Spieler wird wie in der 3rd-Person gerendert
        detached = FreecamMod.showsBody();
        setPosition(FreecamMod.getCameraPosition(partialTick));
        setRotation(FreecamMod.getCameraYaw(), FreecamMod.getCameraPitch());
    }

    //? if >=26.1 {
    // Im Freecam kein Occlusion-Culling (wie Vanilla im Zuschauermodus): steckt die Kamera in Bloecken
    // oder fliegt durch den Boden, haelt Minecraft sonst fast alles fuer verdeckt und rendert nur die Naehe.
    // (1.21.11: im LevelRendererMixin)
    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void nova_client$freecamNoCulling(CameraRenderState state, net.minecraft.client.DeltaTracker deltaTracker,
                                              CallbackInfo ci) {
        if (FreecamMod.isEnabled()) {
            state.smartCull = false;
        }
    }
    //?}

    // Clear Liquids: fuer die Darstellung steckt die Kamera nicht in Wasser/Lava -> Nebel, Farbe und Himmel wie an
    // der Luft, kein Zoom. Nur Rendering fragt das ab (Nebel, FOV, Himmel-Pass), am Spiel aendert sich nichts.
    @Inject(method = "getFluidInCamera", at = @At("RETURN"), cancellable = true)
    private void nova_client$clearLiquids(CallbackInfoReturnable<FogType> cir) {
        if (ClearLiquidsMod.clears(cir.getReturnValue())) {
            cir.setReturnValue(FogType.NONE);
        }
    }
}
