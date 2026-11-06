package nova.nova_client.client.farmbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * "Was waere, wenn ..." fuer den Schematic Builder: Waehrend {@link #with} laeuft, sieht der Client an den angegebenen
 * Stellen die gedachten Bloecke (WorldOverlayMixin auf Level.getBlockState/getFluidState). So rechnen Wegsuche,
 * Sichtlinien und Minecrafts eigene Platzier-Logik exakt mit einer Welt, in der z.B. ein Block schon steht oder fehlt -
 * ohne etwas am echten Spiel zu aendern.
 * <p>
 * Gilt nur fuer den Thread, der es eingeschaltet hat (Client-Thread), und nur innerhalb von with(...).
 */
public final class WorldOverlay {
    /** Schneller Test fuer den Mixin: ist irgendwo ein Overlay aktiv? */
    public static boolean active; // nur der Client-Thread schreibt und nutzt es (andere Threads sehen nie etwas)
    private static Map<BlockPos, BlockState> states = Map.of();
    private static Thread owner;

    private WorldOverlay() {
    }

    /** Gedachter Zustand an pos oder null (= echte Welt). Nur vom Mixin aufgerufen. */
    public static BlockState get(BlockPos pos) {
        if (Thread.currentThread() != owner) {
            return null;
        }
        return states.get(pos);
    }

    /**
     * Strahl wie level.clip, der die gedachten Bloecke sieht. Noetig, weil Lithium (world.raycast) Bloecke beim Strahl
     * direkt aus den Chunk-Sektionen liest statt ueber Level.getBlockState - ohne das waeren gedachte Bloecke fuer jede
     * Sichtpruefung unsichtbar (unstick, "Platz schaffen" und der Standplatz-Waechter fanden dann nie etwas). Ohne
     * aktives Overlay einfach level.clip (schnell, identisch). Sonst Minecrafts eigene Rasterdurchquerung
     * (BlockGetter.traverseBlocks/clip) nachgebaut, mit Block-Abfragen ueber Level.getBlockState.
     */
    public static BlockHitResult clip(Level level, Vec3 from, Vec3 to, ClipContext.Block block, ClipContext.Fluid fluid,
                                      Entity entity) {
        ClipContext context = new ClipContext(from, to, block, fluid, entity);
        if (!active || Thread.currentThread() != owner) {
            return level.clip(context);
        }
        if (from.equals(to)) {
            return miss(from, to);
        }
        double toX = Mth.lerp(-1.0E-7D, to.x, from.x);
        double toY = Mth.lerp(-1.0E-7D, to.y, from.y);
        double toZ = Mth.lerp(-1.0E-7D, to.z, from.z);
        double fromX = Mth.lerp(-1.0E-7D, from.x, to.x);
        double fromY = Mth.lerp(-1.0E-7D, from.y, to.y);
        double fromZ = Mth.lerp(-1.0E-7D, from.z, to.z);
        int x = Mth.floor(fromX);
        int y = Mth.floor(fromY);
        int z = Mth.floor(fromZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        BlockHitResult hit = hitCell(level, context, from, to, pos);
        if (hit != null) {
            return hit;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        int signX = Mth.sign(dx);
        int signY = Mth.sign(dy);
        int signZ = Mth.sign(dz);
        double deltaX = signX == 0 ? Double.MAX_VALUE : signX / dx;
        double deltaY = signY == 0 ? Double.MAX_VALUE : signY / dy;
        double deltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / dz;
        double maxX = deltaX * (signX > 0 ? 1.0D - Mth.frac(fromX) : Mth.frac(fromX));
        double maxY = deltaY * (signY > 0 ? 1.0D - Mth.frac(fromY) : Mth.frac(fromY));
        double maxZ = deltaZ * (signZ > 0 ? 1.0D - Mth.frac(fromZ) : Mth.frac(fromZ));
        while (maxX <= 1.0D || maxY <= 1.0D || maxZ <= 1.0D) {
            if (maxX < maxY) {
                if (maxX < maxZ) {
                    x += signX;
                    maxX += deltaX;
                } else {
                    z += signZ;
                    maxZ += deltaZ;
                }
            } else if (maxY < maxZ) {
                y += signY;
                maxY += deltaY;
            } else {
                z += signZ;
                maxZ += deltaZ;
            }
            hit = hitCell(level, context, from, to, pos.set(x, y, z));
            if (hit != null) {
                return hit;
            }
        }
        return miss(from, to);
    }

    /** Wie Minecrafts clip fuer eine Zelle: Block (mit Interaktions-Form) oder Fluessigkeit, was naeher ist. */
    private static BlockHitResult hitCell(Level level, ClipContext context, Vec3 from, Vec3 to, BlockPos pos) {
        // Gedachten Zustand direkt nehmen (haengt nicht davon ab, dass der Mixin neben Lithiums getBlockState greift)
        BlockState assumed = states.get(pos);
        BlockState state = assumed != null ? assumed : level.getBlockState(pos);
        FluidState fluidState = assumed != null ? assumed.getFluidState() : level.getFluidState(pos);
        VoxelShape shape = context.getBlockShape(state, level, pos);
        BlockHitResult blockHit = level.clipWithInteractionOverride(from, to, pos, shape, state);
        BlockHitResult fluidHit = context.getFluidShape(fluidState, level, pos).clip(from, to, pos);
        double blockDistance = blockHit == null ? Double.MAX_VALUE : from.distanceToSqr(blockHit.getLocation());
        double fluidDistance = fluidHit == null ? Double.MAX_VALUE : from.distanceToSqr(fluidHit.getLocation());
        return blockDistance <= fluidDistance ? blockHit : fluidHit;
    }

    private static BlockHitResult miss(Vec3 from, Vec3 to) {
        Vec3 d = from.subtract(to);
        return BlockHitResult.miss(to, Direction.getApproximateNearest(d.x, d.y, d.z), BlockPos.containing(to));
    }

    /** body ausfuehren, als staenden an diesen Stellen diese Bloecke (verschachtelt: inneres gewinnt). */
    public static <T> T with(Map<BlockPos, BlockState> overlay, Supplier<T> body) {
        if (overlay == null || overlay.isEmpty()) {
            return body.get();
        }
        Map<BlockPos, BlockState> oldStates = states;
        Thread oldOwner = owner;
        boolean oldActive = active;
        Map<BlockPos, BlockState> merged = new HashMap<>(oldStates);
        for (Map.Entry<BlockPos, BlockState> e : overlay.entrySet()) {
            merged.put(e.getKey().immutable(), e.getValue());
        }
        states = merged;
        owner = Thread.currentThread();
        active = true;
        try {
            return body.get();
        } finally {
            states = oldStates;
            owner = oldOwner;
            active = oldActive;
        }
    }
}
