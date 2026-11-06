package nova.nova_client.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import nova.nova_client.client.AntiXray;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import nova.nova_client.client.mods.XrayMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/*
 * Hinweis: Fabric API (Indigo) leitet ModelBlockRenderer.tesselateBlock beim Chunk-Bau auf einen eigenen
 * Renderer um. Deshalb wird hier VOR diesem Aufruf gefiltert (ueber die RenderShape), das funktioniert
 * mit und ohne Fabric-Renderer.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    // Nicht-Xray-Bloecke gar nicht erst zeichnen
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
    private RenderShape nova_client$xrayHideBlocks(BlockState state, Operation<RenderShape> original,
                                                   @Local(argsOnly = true) SectionPos sectionPos,
                                                   @Local(argsOnly = true) RenderSectionRegion region,
                                                   //? if >=26.1 {
                                                   @Local(name = "pos") BlockPos pos) {
                                                   //?} else {
                                                   /*// 1.21.11: lokale Namen sind nicht vorhanden -> 3. BlockPos (Schleifen-Variable)
                                                   @Local(ordinal = 2) BlockPos pos) {
                                                   *///?}
        if (XrayMod.isEnabled()) {
            if (!XrayMod.shouldRender(state)) {
                return RenderShape.INVISIBLE;
            }
            // Anti-Xray: Erze ohne Verbindung zu einem echten Hohlraum sind (evtl.) Fakes vom Server.
            // Die Region kennt nur diese Section +-1 Section und prueft keine Grenzen selbst:
            // alles ausserhalb wird als Stein behandelt.
            int margin = RenderSectionRegion.RADIUS * 16;
            int minX = sectionPos.minBlockX() - margin;
            int minY = sectionPos.minBlockY() - margin;
            int minZ = sectionPos.minBlockZ() - margin;
            int maxX = sectionPos.maxBlockX() + margin;
            int maxY = sectionPos.maxBlockY() + margin;
            int maxZ = sectionPos.maxBlockZ() + margin;
            boolean real = AntiXray.isReal(pos, p -> p.getX() < minX || p.getX() > maxX
                    || p.getY() < minY || p.getY() > maxY || p.getZ() < minZ || p.getZ() > maxZ
                    ? STONE : region.getBlockState(p));
            if (!real) {
                return RenderShape.INVISIBLE;
            }
        }
        return original.call(state);
    }

    private static final BlockState STONE = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();

    // Sonst haelt Minecraft Stein fuer undurchsichtig und blendet ganze Hoehlen-/Chunkbereiche aus
    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"))
    private boolean nova_client$xrayVisibility(BlockState state, Operation<Boolean> original) {
        return !XrayMod.isEnabled() && original.call(state);
    }
}
