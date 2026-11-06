package nova.nova_client.client.farmbuilder;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import nova.nova_client.mixin.client.BlockItemInvoker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Plant einen Klick: welcher Nachbarblock, welche Seite, welcher Punkt und mit welchem Blickwinkel - so dass der
 * Block genau so ausgerichtet entsteht wie in der Vorlage. Dazu wird die Platzierung vorher mit Minecrafts eigener
 * Logik durchgerechnet (inkl. Wand-/Boden-Varianten und Schleichen) und nur Punkte genommen, die man auch wirklich
 * sieht und erreicht.
 */
public final class Placer {
    /** Eigenschaften, die beim Setzen ueber Blickrichtung/Klickpunkt entstehen und stimmen muessen. */
    private static final Set<String> ORIENTATION = Set.of("facing", "axis", "half", "face", "hinge",
            "rotation", "orientation", "attachment", "vertical_direction", "hanging");
    /** Grid der getesteten Punkte je Seite (Anteil der Seitenflaeche). */
    private static final double[] GRID = {0.22D, 0.5D, 0.78D};
    /**
     * Feineres Grid fuer Bloecke, die nur mit steilem Blick nach oben richtig entstehen: der passende Punkt liegt dann
     * oft nah an einer Kante (steiler Winkel UND richtige Himmelsrichtung).
     */
    private static final double[] GRID_FINE = {0.08D, 0.29D, 0.5D, 0.71D, 0.92D};
    /** Augenhoehe geduckt (statt 1,62 stehend). */
    public static final double CROUCH_EYE = 1.27D;
    /** Bloecke, bei denen ein Rechtsklick etwas oeffnet/umschaltet (dann beim Setzen schleichen). Lernt dazu. */
    private static final Set<Block> LEARNED_INTERACTIVE = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Random random = new Random();

    public record Aim(BlockPos clicked, Direction face, Vec3 hit, float yaw, float pitch, boolean sneak) {
        public BlockHitResult hitResult() {
            return new BlockHitResult(hit, face, clicked, false);
        }
    }

    // ---------------------------------------------------------------- Vergleiche

    /**
     * Haengt das Ergebnis beim Setzen von Blickrichtung oder Klickpunkt ab (Werfer, Trichter, Kisten, Treppen, Stufen,
     * Beobachter, Kolben, Verstaerker ...)? Solche Bloecke gehen oft nur von wenigen Plaetzen aus. Einfache Bloecke
     * (Stein, Beton, Glas ...) lassen sich dagegen fast von ueberall setzen.
     */
    public static boolean orientationSensitive(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            if (ORIENTATION.contains(name) && !name.equals("rotation") || property == BlockStateProperties.SLAB_TYPE
                    || property == BlockStateProperties.CHEST_TYPE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Zustand direkt nach dem Setzen (fuer gedachte Welten): Eine Kiste steht zuerst allein (die Doppelkiste entsteht
     * erst mit der zweiten Haelfte) - mit dem fertigen Zustand wuerde die Simulation die zweite Haelfte falsch rechnen.
     */
    public static BlockState placedState(BlockState want) {
        if (want.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            return want.setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE);
        }
        return want;
    }

    /** Stimmt der Block in der Welt mit der Vorlage ueberein (Art + Ausrichtung)? */
    public static boolean orientationMatches(BlockState have, BlockState want, boolean strictRotation) {
        if (have.getBlock() != want.getBlock()) {
            // Erde wird neben Gras von selbst zu Gras, Gras/Myzel unter einem Block von selbst zu Erde - zaehlt
            // trotzdem als richtig (sonst baut der Bot den Block ab, setzt ihn neu, er wandelt sich wieder ... endlos)
            return naturalSoil(want) && sameFamily(have, want);
        }
        for (Property<?> property : want.getProperties()) {
            String name = property.getName();
            if (!have.hasProperty(property)) {
                continue;
            }
            if (property == BlockStateProperties.SLAB_TYPE) {
                // Oben/unten muss stimmen; eine Doppelstufe entsteht durch zweites Setzen
                SlabType w = want.getValue(BlockStateProperties.SLAB_TYPE);
                if (w != SlabType.DOUBLE && have.getValue(BlockStateProperties.SLAB_TYPE) != w) {
                    return false;
                }
                continue;
            }
            if (!ORIENTATION.contains(name)) {
                continue;
            }
            if (!strictRotation && (name.equals("rotation") || name.equals("hinge"))) {
                continue;
            }
            if (!have.getValue(property).equals(want.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Darf ein Klick, der result an target erzeugt, gemacht werden? Wie orientationMatches, dazu: bei einer Doppelstufe
     * kommt die erste Haelfte UNTEN hin - die zweite wird dann von oben dazugeklickt. Oben nur, wenn darunter frei ist
     * (sonst kommt man an die zweite Haelfte nie wieder heran, z.B. wenn darunter ein Trichter steht).
     */
    public static boolean acceptable(Level level, BlockPos target, BlockState result, BlockState want, boolean strict) {
        if (!orientationMatches(result, want, strict)) {
            return false;
        }
        if (want.hasProperty(BlockStateProperties.SLAB_TYPE) && want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
                && result.hasProperty(BlockStateProperties.SLAB_TYPE)
                && result.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP) {
            return level.getBlockState(target.below()).canBeReplaced();
        }
        // Doppelkiste: steht die andere Haelfte schon, muss die neue sich mit ihr verbinden (Klick mit Schleichen auf
        // die erste Kiste ergaebe zwei einzelne Kisten). Die erste Haelfte darf allein stehen - sie verbindet sich
        // automatisch, sobald die zweite richtig dazukommt.
        if (want.getBlock() instanceof ChestBlock && want.hasProperty(BlockStateProperties.CHEST_TYPE)
                && want.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE
                && result.hasProperty(BlockStateProperties.CHEST_TYPE)) {
            BlockPos partner = target.relative(ChestBlock.getConnectedDirection(want));
            if (level.getBlockState(partner).is(want.getBlock())) {
                return result.getValue(BlockStateProperties.CHEST_TYPE) == want.getValue(BlockStateProperties.CHEST_TYPE);
            }
        }
        return true;
    }

    /** Gleicher Block oder "Erd-Familie" (Erde, Gras, Myzel, Podsol, Ackerboden, Trampelpfad). */
    public static boolean sameFamily(BlockState have, BlockState want) {
        if (have.getBlock() == want.getBlock()) {
            return true;
        }
        return dirtLike(have) && dirtLike(want);
    }

    /** Erde, Gras, Myzel, Podsol: wandeln sich in der Welt von selbst ineinander um. */
    private static boolean naturalSoil(BlockState state) {
        Block b = state.getBlock();
        return b == net.minecraft.world.level.block.Blocks.DIRT || b == net.minecraft.world.level.block.Blocks.GRASS_BLOCK
                || b == net.minecraft.world.level.block.Blocks.MYCELIUM || b == net.minecraft.world.level.block.Blocks.PODZOL;
    }

    /**
     * Wird aus have mit Werkzeug-Rechtsklick want (Hacke: Ackerboden, Schaufel: Trampelpfad)? Dann nicht abbauen,
     * sondern bearbeiten.
     */
    public static boolean toolConvertible(BlockState have, BlockState want) {
        Block b = have.getBlock();
        if (want.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
            return b == net.minecraft.world.level.block.Blocks.DIRT || b == net.minecraft.world.level.block.Blocks.GRASS_BLOCK
                    || b == net.minecraft.world.level.block.Blocks.DIRT_PATH;
        }
        if (want.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) {
            return naturalSoil(have) || b == net.minecraft.world.level.block.Blocks.COARSE_DIRT
                    || b == net.minecraft.world.level.block.Blocks.ROOTED_DIRT;
        }
        return false;
    }

    private static boolean dirtLike(BlockState state) {
        Block b = state.getBlock();
        return b == net.minecraft.world.level.block.Blocks.DIRT || b == net.minecraft.world.level.block.Blocks.GRASS_BLOCK
                || b == net.minecraft.world.level.block.Blocks.MYCELIUM || b == net.minecraft.world.level.block.Blocks.PODZOL
                || b == net.minecraft.world.level.block.Blocks.FARMLAND || b == net.minecraft.world.level.block.Blocks.DIRT_PATH;
    }

    /** Ist der Block fertig (Art, Ausrichtung, Verzoegerung, Modus, Anzahl ...)? Strom, Wasser usw. zaehlen nicht. */
    public static boolean done(BlockState have, BlockState want) {
        if (!orientationMatches(have, want, false)) {
            return false;
        }
        return fixup(have, want) == null && countMissing(have, want) == 0;
    }

    /** Eigenschaft, die man per Rechtsklick auf den Block noch anpassen muss (null = alles passt). */
    public static Property<?> fixup(BlockState have, BlockState want) {
        Block block = want.getBlock();
        if (block instanceof DiodeBlock && differs(have, want, BlockStateProperties.DELAY)) {
            return BlockStateProperties.DELAY;
        }
        if (differs(have, want, BlockStateProperties.MODE_COMPARATOR)) {
            return BlockStateProperties.MODE_COMPARATOR;
        }
        if (block instanceof NoteBlock && differs(have, want, BlockStateProperties.NOTE)) {
            return BlockStateProperties.NOTE;
        }
        if (block instanceof DaylightDetectorBlock && differs(have, want, BlockStateProperties.INVERTED)) {
            return BlockStateProperties.INVERTED;
        }
        if (block instanceof LeverBlock && differs(have, want, BlockStateProperties.POWERED)) {
            return BlockStateProperties.POWERED;
        }
        boolean openable = block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock;
        if (openable && !isIron(block) && differs(have, want, BlockStateProperties.OPEN)) {
            return BlockStateProperties.OPEN;
        }
        return null;
    }

    /** Wie viele Klicks braucht die Korrektur? (Notenblock: Toene, Verstaerker: Stufen) */
    public static int fixupClicks(BlockState have, BlockState want, Property<?> property) {
        if (property == BlockStateProperties.DELAY) {
            return Math.floorMod(want.getValue(BlockStateProperties.DELAY) - have.getValue(BlockStateProperties.DELAY), 4);
        }
        if (property == BlockStateProperties.NOTE) {
            return Math.floorMod(want.getValue(BlockStateProperties.NOTE) - have.getValue(BlockStateProperties.NOTE), 25);
        }
        return 1;
    }

    /** Fehlende Anzahl (Doppelstufe, Kerzen, Seegurken, Schnee ...), 0 = passt. */
    public static int countMissing(BlockState have, BlockState want) {
        if (have.getBlock() != want.getBlock()) {
            return 0;
        }
        if (want.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            boolean wantDouble = want.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
            boolean haveDouble = have.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE;
            return wantDouble && !haveDouble ? 1 : 0;
        }
        for (Property<?> property : new Property<?>[]{BlockStateProperties.CANDLES, BlockStateProperties.PICKLES,
                BlockStateProperties.EGGS, BlockStateProperties.LAYERS, BlockStateProperties.FLOWER_AMOUNT,
                BlockStateProperties.SEGMENT_AMOUNT}) {
            if (want.hasProperty(property) && have.hasProperty(property)
                    && want.getValue(property) instanceof Integer w && have.getValue(property) instanceof Integer h) {
                return Math.max(0, w - h);
            }
        }
        return 0;
    }

    private static boolean differs(BlockState have, BlockState want, Property<?> property) {
        return want.hasProperty(property) && have.hasProperty(property) && !have.getValue(property).equals(want.getValue(property));
    }

    private static boolean isIron(Block block) {
        return block == net.minecraft.world.level.block.Blocks.IRON_DOOR
                || block == net.minecraft.world.level.block.Blocks.IRON_TRAPDOOR;
    }

    // ---------------------------------------------------------------- Interaktive Bloecke

    /** Oeffnet/schaltet ein Rechtsklick auf diesen Block etwas? Dann muss man zum Daranbauen schleichen. */
    public static boolean interactive(BlockState state) {
        Block block = state.getBlock();
        if (state.hasBlockEntity() || LEARNED_INTERACTIVE.contains(block)) {
            return true;
        }
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock || block instanceof DiodeBlock
                || block instanceof NoteBlock || block instanceof CraftingTableBlock || block instanceof BedBlock
                || block instanceof CakeBlock || block instanceof AnvilBlock || block instanceof BellBlock
                || block instanceof DaylightDetectorBlock || block instanceof RedstoneWireBlock
                || block instanceof GrindstoneBlock || block instanceof StonecutterBlock || block instanceof LoomBlock
                || block instanceof CartographyTableBlock || block instanceof SmithingTableBlock
                || block instanceof ComposterBlock || block instanceof AbstractCauldronBlock
                || block instanceof FlowerPotBlock || block instanceof RespawnAnchorBlock;
    }

    /** Ein Klick auf diesen Block hat unerwartet ein Menue geoeffnet: kuenftig dagegen schleichen. */
    public static void learnInteractive(Block block) {
        LEARNED_INTERACTIVE.add(block);
    }

    // ---------------------------------------------------------------- Blickwinkel

    public static float yawTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
    }

    public static float pitchTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        return (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG);
    }

    public static double reach(LocalPlayer player) {
        return Math.min(player.blockInteractionRange(), 4.5D) - 0.3D;
    }

    /**
     * Augenhoehe an einem Standplatz - mit der ECHTEN Bodenhoehe: in einer unteren Stufe steht man 0.5 hoeher, in der
     * Mulde eines Trichters 0.31 tiefer, auf Ackerboden etwas tiefer. Ohne das haelt die Planung Plaetze fuer gut, von
     * denen aus es in Wirklichkeit nicht geht (Kopf steckt im Zielblock, Blickwinkel stimmt nicht).
     */
    public static Vec3 eyeAt(BlockGetter level, BlockPos feet) {
        double floor = Pathfinder.floorHeight(level, feet);
        return new Vec3(feet.getX() + 0.5D, feet.getY() + floor + 1.62D, feet.getZ() + 0.5D);
    }

    /** So weit neben der Feldmitte kann man stehen, ohne dass der Koerper (0,6 breit) ins Nachbarfeld ragt. */
    public static final double SPOT = 0.18D;

    /**
     * Augen an einem Standplatz: Mitte zuerst, dann 8 versetzte Stellen im selben Feld. Manche Bloecke entstehen nur
     * mit einem ganz bestimmten Blick richtig (Crafter nach unten: schraeg von unten und nach Norden) - aus der
     * Feldmitte zeigt der Blick dann in die falsche Himmelsrichtung, ein Stueck daneben passt es.
     */
    public static Vec3[] eyeSpots(BlockGetter level, BlockPos feet) {
        return eyeSpots(level, feet, false);
    }

    /** Wie eyeSpots, mit crouch zusaetzlich alle 9 Stellen geduckt (Auge 0,35 tiefer - fuer steilen Blick nach oben). */
    public static Vec3[] eyeSpots(BlockGetter level, BlockPos feet, boolean crouch) {
        Vec3 c = eyeAt(level, feet);
        Vec3[] out = new Vec3[crouch ? 18 : 9];
        int i = 0;
        for (int h = 0; h < (crouch ? 2 : 1); h++) {
            Vec3 base = h == 0 ? c : c.subtract(0.0D, 1.62D - CROUCH_EYE, 0.0D);
            out[i++] = base;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        out[i++] = base.add(dx * SPOT, 0.0D, dz * SPOT);
                    }
                }
            }
        }
        return out;
    }

    /** Ist dieses Auge (aus eyeSpots) die geduckte Variante? */
    public static boolean crouched(BlockGetter level, BlockPos feet, Vec3 eye) {
        return eye.y < eyeAt(level, feet).y - 0.2D;
    }

    /**
     * Entsteht dieser Zustand nur mit steilem Blick nach oben (vom Spieler weg nach unten zeigend: Crafter/Werfer/Kolben
     * nach unten, Beobachter nach oben)? Dann braucht es oft einen Block darueber zum Anklicken und Ducken.
     */
    public static boolean needsLookUp(BlockState state) {
        if (state.hasProperty(BlockStateProperties.ORIENTATION)) {
            return state.getValue(BlockStateProperties.ORIENTATION).front() == Direction.DOWN; // Crafter
        }
        if (!state.hasProperty(BlockStateProperties.FACING)) {
            return false;
        }
        Direction facing = state.getValue(BlockStateProperties.FACING);
        Block b = state.getBlock();
        if (b instanceof net.minecraft.world.level.block.ObserverBlock) {
            return facing == Direction.UP;
        }
        return facing == Direction.DOWN && (b instanceof net.minecraft.world.level.block.DispenserBlock
                || b instanceof net.minecraft.world.level.block.piston.PistonBaseBlock);
    }

    /**
     * Braucht der Block evtl. eine versetzte Stelle im Feld? Nur wenn Blick nach oben/unten UND Himmelsrichtung zugleich
     * stimmen muessen (Crafter mit Vorderseite oben/unten) - fuer alles andere reicht die Feldmitte, und das Rechnen
     * kostet viel Zeit.
     */
    public static boolean needsSpot(BlockState state) {
        return state.hasProperty(BlockStateProperties.ORIENTATION)
                && state.getValue(BlockStateProperties.ORIENTATION).front().getAxis() == Direction.Axis.Y;
    }

    /** Versetzte Stellen lohnen sich nur nah am Block (sonst aendert ein paar Zentimeter am Blick nichts). */
    public static boolean spotRange(BlockPos stand, BlockPos target) {
        return Math.abs(stand.getX() - target.getX()) <= 2 && Math.abs(stand.getZ() - target.getZ()) <= 2
                && stand.getY() - target.getY() >= -3 && stand.getY() - target.getY() <= 1;
    }

    /** Wuerde der Spieler (an diesem Platz stehend, echte Bodenhoehe) mit dem Block kollidieren? */
    public static boolean intersectsPlayer(BlockGetter level, BlockPos feet, BlockPos block) {
        double floor = Pathfinder.floorHeight(level, feet);
        AABB body = new AABB(feet.getX() + 0.2D, feet.getY() + floor, feet.getZ() + 0.2D,
                feet.getX() + 0.8D, feet.getY() + floor + 1.8D, feet.getZ() + 0.8D);
        return body.intersects(new AABB(block));
    }

    // ---------------------------------------------------------------- Planung

    /**
     * Klick zum Setzen von want an target, gesehen von eye aus. null = von hier aus nicht moeglich.
     * Unter mehreren passenden Punkten wird der genommen, der am wenigsten Kamera-Drehung braucht.
     */
    public Aim planPlacement(Level level, LocalPlayer player, Vec3 eye, BlockPos target, BlockState want, ItemStack stack,
                             boolean strict) {
        return planPlacement(level, player, eye, target, want, stack, strict, needsLookUp(want) || needsSpot(want));
    }

    /** fine = feineres Raster (Ueber-Kopf-Bloecke); der Waechter rechnet grob - er muss schnell bleiben. */
    public Aim planPlacement(Level level, LocalPlayer player, Vec3 eye, BlockPos target, BlockState want, ItemStack stack,
                             boolean strict, boolean fine) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        double reach = reach(player);
        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > (reach + 1.0D) * (reach + 1.0D)) {
            return null;
        }
        Aim best = null;
        AABB bestBounds = null;
        double bestScore = Double.MAX_VALUE;
        // Ist schon ein Teil da (halbe Stufe, Kerzen ...), kann man auch direkt auf ihn klicken
        boolean partial = !level.getBlockState(target).isAir() && countMissing(level.getBlockState(target), want) > 0;
        for (int pass = 0; pass < (partial ? 2 : 1); pass++) {
            for (Direction side : Direction.values()) {
                BlockPos clicked = pass == 0 ? target.relative(side) : target;
                Direction face = pass == 0 ? side.getOpposite() : side;
                BlockState clickedState = level.getBlockState(clicked);
                if (pass == 0 && (clickedState.isAir() || clickedState.canBeReplaced())) {
                    continue;
                }
                VoxelShape shape = clickedState.getShape(level, clicked);
                if (shape.isEmpty()) {
                    continue;
                }
                boolean sneak = pass == 0 && interactive(clickedState);
                for (Vec3 hit : facePoints(clicked, shape.bounds(), face, fine)) {
                    if (eye.distanceToSqr(hit) > reach * reach || !visible(level, player, eye, hit, clicked, face)) {
                        continue;
                    }
                    float yaw = yawTo(eye, hit);
                    float pitch = pitchTo(eye, hit);
                    BlockHitResult hitResult = new BlockHitResult(hit, face, clicked, false);
                    BlockState result = simulate(level, player, stack, hitResult, yaw, pitch, sneak, target, true);
                    if (result == null || !acceptable(level, target, result, want, strict)) {
                        continue;
                    }
                    double score = angleFrom(player, yaw, pitch) + random.nextDouble() * 6.0D;
                    if (fine) {
                        // Enges Fenster (Crafter nach unten ...): Punkte bevorzugen, die auch bei etwas ungenauem Zielen
                        // noch richtig herum werden - sonst lag der Punkt an der Kante und es hiess "lost angle"
                        score += (4 - aimMargin(level, player, stack, hitResult, yaw, pitch, sneak, target, want, strict))
                                * 30.0D;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Aim(clicked, face, hit, yaw, pitch, sneak);
                        bestBounds = shape.bounds();
                    }
                }
            }
        }
        if (best == null) {
            return null;
        }
        // Nie exakt das Raster treffen: Punkt leicht verschieben, wenn das Ergebnis dabei gleich bleibt
        Vec3 moved = jitter(best.hit(), best.clicked(), bestBounds, best.face());
        float yaw = yawTo(eye, moved);
        float pitch = pitchTo(eye, moved);
        BlockState result = simulate(level, player, stack, new BlockHitResult(moved, best.face(), best.clicked(), false),
                yaw, pitch, best.sneak(), target, true);
        if (result != null && acceptable(level, target, result, want, strict)
                && visible(level, player, eye, moved, best.clicked(), best.face())) {
            return new Aim(best.clicked(), best.face(), moved, yaw, pitch, best.sneak());
        }
        return best;
    }

    /** Wie viele der 4 leicht verrissenen Blicke (je 1 Grad) ergeben noch den richtigen Block? */
    private int aimMargin(Level level, LocalPlayer player, ItemStack stack, BlockHitResult hit, float yaw, float pitch,
                          boolean sneak, BlockPos target, BlockState want, boolean strict) {
        int ok = 0;
        for (int i = 0; i < 4; i++) {
            float y = yaw + (i == 0 ? 1.0F : i == 1 ? -1.0F : 0.0F);
            float p = pitch + (i == 2 ? 1.0F : i == 3 ? -1.0F : 0.0F);
            BlockState r = simulate(level, player, stack, hit, y, p, sneak, target, true);
            if (r != null && acceptable(level, target, r, want, strict)) {
                ok++;
            }
        }
        return ok;
    }

    /** Ein Klick, fuer den erst ein paar Bloecke im Blickfeld weg muessen (Reihenfolge: vom Auge aus gesehen). */
    public record Through(Aim aim, List<BlockPos> obstacles) {
    }

    /**
     * Wie planPlacement, aber Bloecke zwischen Auge und Klickpunkt duerfen im Weg sein, wenn breakable sie erlaubt
     * (hoechstens maxObstacles). Fuer Bloecke, die schon zugebaut sind (z.B. Trichter unter einer Stufe).
     * null = auch mit Abbauen nicht moeglich. Nur Ergebnisse mit mindestens einem Hindernis.
     */
    public Through planPlacementThrough(Level level, LocalPlayer player, Vec3 eye, BlockPos target, BlockState want,
                                        ItemStack stack, boolean strict, Predicate<BlockPos> breakable, int maxObstacles) {
        if (!(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        double reach = reach(player);
        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > (reach + 1.0D) * (reach + 1.0D)) {
            return null;
        }
        Through best = null;
        double bestScore = Double.MAX_VALUE;
        boolean partial = !level.getBlockState(target).isAir() && countMissing(level.getBlockState(target), want) > 0;
        for (int i = 0; i < (partial ? 12 : 6); i++) {
            Direction side = Direction.values()[i % 6];
            boolean self = i >= 6; // zweiter Durchgang: direkt auf den vorhandenen Teil klicken
            BlockPos clicked = self ? target : target.relative(side);
            Direction face = self ? side : side.getOpposite();
            BlockState clickedState = level.getBlockState(clicked);
            if (!self && (clickedState.isAir() || clickedState.canBeReplaced())) {
                continue;
            }
            VoxelShape shape = clickedState.getShape(level, clicked);
            if (shape.isEmpty()) {
                continue;
            }
            boolean sneak = !self && interactive(clickedState);
            for (Vec3 hit : facePoints(clicked, shape.bounds(), face)) {
                if (eye.distanceToSqr(hit) > reach * reach) {
                    continue;
                }
                List<BlockPos> obstacles = obstacles(level, eye, hit, clicked, face, target, breakable, maxObstacles);
                if (obstacles == null || obstacles.isEmpty()) {
                    continue;
                }
                float yaw = yawTo(eye, hit);
                float pitch = pitchTo(eye, hit);
                BlockState result = simulate(level, player, stack, new BlockHitResult(hit, face, clicked, false), yaw, pitch,
                        sneak, target, true);
                if (result == null || !acceptable(level, target, result, want, strict)) {
                    continue;
                }
                double score = obstacles.size() * 1000.0D + angleFrom(player, yaw, pitch);
                if (score < bestScore) {
                    bestScore = score;
                    best = new Through(new Aim(clicked, face, hit, yaw, pitch, sneak), obstacles);
                }
            }
        }
        return best;
    }

    /**
     * Bloecke auf dem Blick von eye zu hit (ohne target und clicked). null = ein Block im Weg darf nicht weg, zu viele,
     * oder der Strahl trifft clicked nicht auf der richtigen Seite.
     */
    private static List<BlockPos> obstacles(Level level, Vec3 eye, Vec3 hit, BlockPos clicked, Direction face,
                                            BlockPos target, Predicate<BlockPos> breakable, int max) {
        Vec3 dir = hit.subtract(eye);
        double length = dir.length();
        if (length < 1.0E-4D) {
            return null;
        }
        Vec3 end = eye.add(dir.scale((length + 0.08D) / length));
        BlockHitResult onClicked = level.getBlockState(clicked).getShape(level, clicked).clip(eye, end, clicked);
        if (onClicked == null || onClicked.getDirection() != face) {
            return null;
        }
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos cell : cellsOnRay(eye, end)) {
            if (cell.equals(clicked)) {
                break;
            }
            if (cell.equals(target)) {
                continue; // wird beim Setzen ersetzt (Gras o.Ae.) bzw. ist leer
            }
            BlockState state = level.getBlockState(cell);
            VoxelShape shape = state.getShape(level, cell);
            if (shape.isEmpty() || shape.clip(eye, end, cell) == null) {
                continue;
            }
            if (!breakable.test(cell) || out.size() >= max) {
                return null;
            }
            out.add(cell.immutable());
        }
        return out;
    }

    /** Blockfelder zwischen Auge und Klickpunkt (ohne das angeklickte Feld) - was dort hinkommt, verdeckt den Klick. */
    public static Set<BlockPos> rayCells(Vec3 eye, Vec3 hit, BlockPos clicked) {
        Set<BlockPos> out = new java.util.HashSet<>();
        for (BlockPos cell : cellsOnRay(eye, hit)) {
            if (cell.equals(clicked)) {
                break;
            }
            out.add(cell);
        }
        return out;
    }

    /** Alle Blockfelder, durch die die Strecke von a nach b laeuft (in dieser Reihenfolge). */
    private static List<BlockPos> cellsOnRay(Vec3 a, Vec3 b) {
        List<BlockPos> out = new ArrayList<>();
        int x = Mth.floor(a.x);
        int y = Mth.floor(a.y);
        int z = Mth.floor(a.z);
        int ex = Mth.floor(b.x);
        int ey = Mth.floor(b.y);
        int ez = Mth.floor(b.z);
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dx);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dy);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1.0D / dz);
        double tmx = sx > 0 ? (x + 1 - a.x) * tdx : sx < 0 ? (a.x - x) * tdx : Double.MAX_VALUE;
        double tmy = sy > 0 ? (y + 1 - a.y) * tdy : sy < 0 ? (a.y - y) * tdy : Double.MAX_VALUE;
        double tmz = sz > 0 ? (z + 1 - a.z) * tdz : sz < 0 ? (a.z - z) * tdz : Double.MAX_VALUE;
        out.add(new BlockPos(x, y, z));
        for (int i = 0; i < 64 && (x != ex || y != ey || z != ez); i++) {
            if (tmx <= tmy && tmx <= tmz) {
                x += sx;
                tmx += tdx;
            } else if (tmy <= tmz) {
                y += sy;
                tmy += tdy;
            } else {
                z += sz;
                tmz += tdz;
            }
            out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    /** Klick direkt auf einen vorhandenen Block (Kiste oeffnen, Verstaerker umstellen, zweite Stufe ...). */
    public Aim planClickOn(Level level, LocalPlayer player, Vec3 eye, BlockPos pos, Direction requiredFace, boolean sneak) {
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            shape = net.minecraft.world.phys.shapes.Shapes.block();
        }
        double reach = reach(player);
        Aim best = null;
        double bestScore = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            if (requiredFace != null && face != requiredFace) {
                continue;
            }
            for (Vec3 hit : facePoints(pos, shape.bounds(), face)) {
                if (eye.distanceToSqr(hit) > reach * reach || !visible(level, player, eye, hit, pos, face)) {
                    continue;
                }
                float yaw = yawTo(eye, hit);
                float pitch = pitchTo(eye, hit);
                double score = angleFrom(player, yaw, pitch) + random.nextDouble() * 5.0D;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Aim(pos, face, hit, yaw, pitch, sneak);
                }
            }
        }
        return best;
    }

    /** Sichtbare Stelle eines Blocks zum Abbauen. */
    public Aim planMining(Level level, LocalPlayer player, Vec3 eye, BlockPos pos) {
        return planClickOn(level, player, eye, pos, null, false);
    }

    /**
     * Eimer: Blick auf eine Seite eines Nachbarn, so dass das Wasser genau in target landet. intoBlock = target nimmt
     * das Wasser selbst auf (wassergeloggt): Klick direkt auf ihn. Schleichen entscheidet beim Eimer mit, wohin es
     * geht: geschlichen landet Wasser VOR einem wassergeloggbaren Block statt in ihm. Aim.sneak = so muss man stehen.
     */
    public Aim planFluid(Level level, LocalPlayer player, Vec3 eye, BlockPos target, boolean intoBlock, boolean water) {
        double reach = reach(player);
        Aim best = null;
        double bestScore = Double.MAX_VALUE;
        if (intoBlock) {
            // In den Block selbst (wassergeloggt): auf eine Stelle, die der Server sicher trifft (siehe rayMargin)
            BlockState state = level.getBlockState(target);
            VoxelShape shape = state.getShape(level, target);
            if (shape.isEmpty()) {
                return planClickOn(level, player, eye, target, null, false);
            }
            for (Direction face : Direction.values()) {
                for (Vec3 hit : facePoints(target, shape.bounds(), face, true)) {
                    if (eye.distanceToSqr(hit) > reach * reach || !visible(level, player, eye, hit, target, face)) {
                        continue;
                    }
                    float yaw = yawTo(eye, hit);
                    float pitch = pitchTo(eye, hit);
                    double score = angleFrom(player, yaw, pitch) + random.nextDouble() * 5.0D
                            + (6 - rayMargin(level, player, eye, yaw, pitch, target, face)) * 30.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Aim(target, face, hit, yaw, pitch, false);
                    }
                }
            }
            return best;
        }
        for (Direction side : Direction.values()) {
            BlockPos clicked = target.relative(side);
            Direction face = side.getOpposite();
            BlockState clickedState = level.getBlockState(clicked);
            if (clickedState.isAir() || !clickedState.getFluidState().isEmpty() && clickedState.getCollisionShape(level, clicked).isEmpty()) {
                continue;
            }
            VoxelShape shape = clickedState.getShape(level, clicked);
            if (shape.isEmpty()) {
                continue;
            }
            // Wasser auf einen wassergeloggbaren Nachbarn: nur geschlichen landet es davor (sonst in ihm)
            boolean sneak = water && clickedState.getBlock() instanceof LiquidBlockContainer;
            for (Vec3 hit : facePoints(clicked, shape.bounds(), face, true)) {
                if (eye.distanceToSqr(hit) > reach * reach || !visible(level, player, eye, hit, clicked, face)) {
                    continue;
                }
                float yaw = yawTo(eye, hit);
                float pitch = pitchTo(eye, hit);
                double score = angleFrom(player, yaw, pitch) + random.nextDouble() * 5.0D
                        + (6 - rayMargin(level, player, eye, yaw, pitch, clicked, face)) * 30.0D;
                if (score < bestScore) {
                    bestScore = score;
                    best = new Aim(clicked, face, hit, yaw, pitch, sneak);
                }
            }
        }
        return best;
    }

    /**
     * Wie viele von 6 leicht verschobenen Blicken (+-1,5 Grad, Auge 0,35 hoeher/tiefer - so weit weichen Server-Blick und
     * Ducken ab) treffen noch denselben Block auf derselben Seite? Den Eimer setzt der Server mit SEINEM Blick: knapp an
     * einer Kante landete das Wasser auf dem Nachbarblock, lief ueber den Generator und machte aus Lava Obsidian.
     */
    private static int rayMargin(Level level, LocalPlayer player, Vec3 eye, float yaw, float pitch, BlockPos clicked,
                                 Direction face) {
        int ok = 0;
        float[][] turns = {{1.5F, 0.0F}, {-1.5F, 0.0F}, {0.0F, 1.5F}, {0.0F, -1.5F}};
        for (float[] t : turns) {
            if (rayHits(level, player, eye, yaw + t[0], pitch + t[1], clicked, face)) {
                ok++;
            }
        }
        for (double dy : new double[]{0.35D, -0.35D}) {
            if (rayHits(level, player, eye.add(0.0D, dy, 0.0D), yaw, pitch, clicked, face)) {
                ok++;
            }
        }
        return ok;
    }

    private static boolean rayHits(Level level, LocalPlayer player, Vec3 eye, float yaw, float pitch, BlockPos clicked,
                                   Direction face) {
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(reach(player) + 0.6D));
        BlockHitResult r = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(clicked) && r.getDirection() == face;
    }

    // ---------------------------------------------------------------- Hilfen

    private static double angleFrom(LocalPlayer player, float yaw, float pitch) {
        return Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) + Math.abs(pitch - player.getXRot()) * 1.2D;
    }

    private static Vec3[] facePoints(BlockPos pos, AABB b, Direction face) {
        return facePoints(pos, b, face, false);
    }

    private static Vec3[] facePoints(BlockPos pos, AABB b, Direction face, boolean fine) {
        double[] grid = fine ? GRID_FINE : GRID;
        Vec3[] points = new Vec3[grid.length * grid.length];
        int i = 0;
        for (double u : grid) {
            for (double v : grid) {
                double x, y, z;
                switch (face.getAxis()) {
                    case X -> {
                        x = face == Direction.EAST ? b.maxX : b.minX;
                        y = Mth.lerp(u, b.minY, b.maxY);
                        z = Mth.lerp(v, b.minZ, b.maxZ);
                    }
                    case Y -> {
                        y = face == Direction.UP ? b.maxY : b.minY;
                        x = Mth.lerp(u, b.minX, b.maxX);
                        z = Mth.lerp(v, b.minZ, b.maxZ);
                    }
                    default -> {
                        z = face == Direction.SOUTH ? b.maxZ : b.minZ;
                        x = Mth.lerp(u, b.minX, b.maxX);
                        y = Mth.lerp(v, b.minY, b.maxY);
                    }
                }
                points[i++] = new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
            }
        }
        return points;
    }

    /** Ein Mensch trifft nie exakt dieselbe Stelle: etwas verschieben, aber auf der Flaeche bleiben. */
    private Vec3 jitter(Vec3 hit, BlockPos pos, AABB b, Direction face) {
        double j = 0.09D;
        double x = hit.x, y = hit.y, z = hit.z;
        if (face.getAxis() != Direction.Axis.X) {
            x = Mth.clamp(x + (random.nextDouble() - 0.5D) * 2.0D * j, pos.getX() + b.minX + 0.08D, pos.getX() + b.maxX - 0.08D);
        }
        if (face.getAxis() != Direction.Axis.Y) {
            // Hoehe (oben/unten) nicht ueber die Mitte schieben - entscheidet bei Stufen/Falltueren die Haelfte
            double mid = pos.getY() + (b.minY + b.maxY) / 2.0D;
            double ny = y + (random.nextDouble() - 0.5D) * 2.0D * j;
            if ((y - mid) * (ny - mid) > 0 || Math.abs(y - mid) < 1.0E-4D) {
                y = Mth.clamp(ny, pos.getY() + b.minY + 0.08D, pos.getY() + b.maxY - 0.08D);
            }
        }
        if (face.getAxis() != Direction.Axis.Z) {
            z = Mth.clamp(z + (random.nextDouble() - 0.5D) * 2.0D * j, pos.getZ() + b.minZ + 0.08D, pos.getZ() + b.maxZ - 0.08D);
        }
        return new Vec3(x, y, z);
    }

    /** Trifft ein Blick von eye zum Punkt genau diesen Block auf dieser Seite (nichts dazwischen)? */
    public static boolean visible(Level level, Player player, Vec3 eye, Vec3 hit, BlockPos pos, Direction face) {
        Vec3 dir = hit.subtract(eye);
        double length = dir.length();
        if (length < 1.0E-4D) {
            return false;
        }
        Vec3 end = eye.add(dir.scale((length + 0.08D) / length));
        // Ueber WorldOverlay: sieht auch gedachte Bloecke (Lithium umgeht sonst Level.getBlockState beim Strahl)
        BlockHitResult result = WorldOverlay.clip(level, eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(pos) && result.getDirection() == face;
    }

    /** Rechnet mit Minecrafts Logik aus, welcher Zustand entstehen wuerde (null = Setzen nicht moeglich). */
    public static BlockState simulate(Level level, LocalPlayer player, ItemStack stack, BlockHitResult hit, float yaw,
                                      float pitch, boolean sneak, BlockPos expectedPos) {
        return simulate(level, player, stack, hit, yaw, pitch, sneak, expectedPos, false);
    }

    /**
     * ignoreSelf: der eigene Koerper zaehlt nicht als "im Weg". Beim PLANEN von einem anderen Standplatz aus steht der
     * Spieler ja gar nicht dort, wo er gerade ist - sonst gilt jeder Platz als unmoeglich, solange er selbst mit einer
     * Fussspitze im Zielfeld steht (z.B. auf der Kante einer Kiste neben einem Trichter-Platz).
     */
    public static BlockState simulate(Level level, LocalPlayer player, ItemStack stack, BlockHitResult hit, float yaw,
                                      float pitch, boolean sneak, BlockPos expectedPos, boolean ignoreSelf) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        AABB oldBox = player.getBoundingBox();
        try {
            player.setYRot(yaw);
            player.setXRot(pitch);
            if (ignoreSelf) {
                player.setBoundingBox(new AABB(oldBox.minX, -100000.0D, oldBox.minZ, oldBox.minX + 0.01D, -99999.0D,
                        oldBox.minZ + 0.01D));
            }
            SimContext context = new SimContext(level, player, stack, hit, sneak);
            if (!context.canPlace() || !context.getClickedPos().equals(expectedPos)) {
                return null;
            }
            return ((BlockItemInvoker) blockItem).nova_client$getPlacementState(context);
        } catch (RuntimeException e) {
            return null;
        } finally {
            player.setBoundingBox(oldBox);
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
    }

    /** Platzier-Kontext mit frei waehlbarem Schleichen. */
    private static final class SimContext extends BlockPlaceContext {
        private final boolean sneak;

        SimContext(Level level, Player player, ItemStack stack, BlockHitResult hit, boolean sneak) {
            super(level, player, InteractionHand.MAIN_HAND, stack, hit);
            this.sneak = sneak;
        }

        @Override
        public boolean isSecondaryUseActive() {
            return sneak;
        }
    }
}
