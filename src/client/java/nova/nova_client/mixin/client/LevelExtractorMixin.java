package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.world.entity.Entity;
import nova.nova_client.client.mods.BlockEspMod;
import nova.nova_client.client.mods.ColumnEspMod;
import nova.nova_client.client.mods.SusChunkFinderMod;
import nova.nova_client.client.mods.HoleEspMod;
import nova.nova_client.client.mods.PearlTrajectoryMod;
import nova.nova_client.client.mods.HitboxesMod;
import nova.nova_client.client.mods.StorageEspMod;
import nova.nova_client.client.mods.TargetEspMod;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.1 {
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
*///?}

/**
 * Welt-Markierungen aller ESP-Module als Gizmos einreihen, direkt bevor Minecraft sie fuer das Bild einsammelt, und
 * ESP-Ziele immer rendern. 26.x: im LevelExtractor, 1.21.11: im LevelRenderer.
 */
//? if >=26.1 {
@Mixin(LevelExtractor.class)
//?} else {
/*@Mixin(LevelRenderer.class)
*///?}
public abstract class LevelExtractorMixin {
    //? if >=26.1 {
    @Shadow
    @Final
    private SimpleGizmoCollector mainThreadGizmos;

    // BlockESP-Umrisse als Gizmos einreihen, direkt bevor Minecraft sie fuer diesen Frame einsammelt
    @Inject(method = "extractGizmos", at = @At("HEAD"))
    private void nova_client$blockEsp(CallbackInfo ci) {
        nova_client$emitGizmos(mainThreadGizmos);
    }

    // ESP-Ziele immer rendern: Vanilla laesst Entities weg, deren Abschnitt verdeckt ist (z.B. Spieler unter der
    // Erde) oder die weiter als ~64 Bloecke weg sind -> dann fehlt auch der Umriss.
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void nova_client$espAlwaysVisible(Entity entity, Frustum frustum, double x, double y, double z,
                                              float partialTick, long gameTime, CallbackInfoReturnable<Boolean> cir) {
        if (nova_client$espTarget(entity)) {
            cir.setReturnValue(true);
        }
    }
    //?} else {
    /*@Shadow
    @Final
    private SimpleGizmoCollector collectedGizmos;

    // BlockESP-Umrisse als Gizmos einreihen, direkt bevor Minecraft sie fuer diesen Frame einsammelt
    @Inject(method = "finalizeGizmoCollection", at = @At("HEAD"))
    private void nova_client$blockEsp(CallbackInfo ci) {
        nova_client$emitGizmos(collectedGizmos);
    }

    // ESP-Ziele immer rendern: Vanilla laesst Entities weg, die ausserhalb der Sicht/Entfernung liegen ...
    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean nova_client$espAlwaysVisible(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum,
                                                 double x, double y, double z, Operation<Boolean> original) {
        return nova_client$espTarget(entity) || original.call(dispatcher, entity, frustum, x, y, z);
    }

    // ... oder deren Abschnitt verdeckt ist (z.B. Spieler unter der Erde) -> dann fehlt auch der Umriss
    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean nova_client$espVisibleSection(LevelRenderer renderer, BlockPos pos, Operation<Boolean> original,
                                                  @Local(ordinal = 0) Entity entity) {
        return nova_client$espTarget(entity) || original.call(renderer, pos);
    }
    *///?}

    // Block Overlay: eigener Rahmen statt des schwarzen Vanilla-Rahmens
    @Inject(method = "extractBlockOutline", at = @At("HEAD"), cancellable = true)
    private void nova_client$blockOverlay(CallbackInfo ci) {
        if (nova.nova_client.client.mods.BlockOverlayMod.hidesVanillaOutline()) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void nova_client$emitGizmos(SimpleGizmoCollector collector) {
        // Beide aus (Normalfall): gar keinen Collector oeffnen
        if (!BlockEspMod.isEnabled() && !nova.nova_client.client.mods.EspMod.isEnabled()
                && !TargetEspMod.anyActive() && !ColumnEspMod.anyActive()
                && !HoleEspMod.isEnabled() && !SusChunkFinderMod.isEnabled() && !PearlTrajectoryMod.isEnabled()
                && !HitboxesMod.isEnabled() && !StorageEspMod.isEnabled() && !nova_client$newModsActive()) {
            return;
        }
        try (Gizmos.TemporaryCollection ignored = Gizmos.withCollector(collector)) {
            BlockEspMod.emitGizmos();
            nova.nova_client.client.mods.EspMod.emitGizmos();
            TargetEspMod.emitGizmos();
            ColumnEspMod.emitGizmos();
            HoleEspMod.emitGizmos();
            SusChunkFinderMod.emitGizmos();
            PearlTrajectoryMod.emitGizmos();
            HitboxesMod.emitGizmos();
            StorageEspMod.emitGizmos();
            nova.nova_client.client.mods.SchematicBuilderMod.emitGizmos();
            nova.nova_client.client.mods.AdvancedStashFinderMod.emitGizmos();
            nova.nova_client.client.tunnelfinder.BotControl.emitGizmos();
            nova.nova_client.client.mods.ActivityInspectorMod.emitGizmos();
            nova.nova_client.client.mods.SpawnerProtectMod.emitGizmos();
            nova.nova_client.client.mods.QuantumMod.emitGizmos();
            nova.nova_client.client.mods.PlayerBypassMod.emitGizmos();
            nova.nova_client.client.mods.AmethystBypassMod.emitGizmos();
            nova.nova_client.client.mods.BlockEntityDebugMod.emitGizmos();
            nova.nova_client.client.mods.PrimeChunkFinderMod.emitGizmos();
            nova.nova_client.client.mods.WaypointsMod.emitGizmos();
            nova.nova_client.client.mods.NetheriteFinderMod.emitGizmos();
            nova.nova_client.client.mods.BlockOverlayMod.emitGizmos();
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean nova_client$newModsActive() {
        return nova.nova_client.client.mods.SchematicBuilderMod.wantsGizmos()
                || nova.nova_client.client.mods.AdvancedStashFinderMod.isEnabled()
                || nova.nova_client.client.tunnelfinder.BotControl.wantsGizmos()
                || nova.nova_client.client.mods.ActivityInspectorMod.isEnabled()
                || nova.nova_client.client.mods.SpawnerProtectMod.wantsGizmos()
                || nova.nova_client.client.mods.QuantumMod.isEnabled()
                || nova.nova_client.client.mods.PlayerBypassMod.isEnabled()
                || nova.nova_client.client.mods.AmethystBypassMod.isEnabled()
                || nova.nova_client.client.mods.BlockEntityDebugMod.isEnabled()
                || nova.nova_client.client.mods.PrimeChunkFinderMod.isEnabled()
                || nova.nova_client.client.mods.WaypointsMod.isEnabled()
                || nova.nova_client.client.mods.NetheriteFinderMod.isEnabled()
                || nova.nova_client.client.mods.BlockOverlayMod.hidesVanillaOutline();
    }

    /** Soll dieses Entity immer gerendert werden (ESP-Umriss/Leuchten)? */
    @org.spongepowered.asm.mixin.Unique
    private static boolean nova_client$espTarget(Entity entity) {
        return nova.nova_client.client.mods.EspMod.shouldGlow(entity) || TargetEspMod.shouldGlow(entity);
    }
}
