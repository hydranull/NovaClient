package nova.nova_client.client.farmbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * A*-Wegsuche auf Fuss-Positionen. Neben Laufen (auch schraeg), 1 Block hochspringen und Herunterfallen kennt sie
 * Bau-Bewegungen wie ein Spieler sie macht, um ueberall hinzukommen:
 * <ul>
 *     <li>PILLAR - hochspringen und einen Hilfsblock unter sich setzen (Turm bauen)</li>
 *     <li>BRIDGE - an der Kante schleichen und einen Hilfsblock vor sich setzen (Bruecke bauen)</li>
 *     <li>DIG - den eigenen Hilfsblock unter sich abbauen und einen Block tiefer fallen (Turm wieder runter)</li>
 * </ul>
 * Hilfsbloecke duerfen nur dort hin, wo laut Vorlage Luft ist (oder ausserhalb der Farm).
 * Arbeitet nur mit dem, was der Client von der Welt kennt (ungeladene Chunks sind Waende).
 */
public final class Pathfinder {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final Set<Block> DANGER = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK,
            Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.COBWEB,
            Blocks.POWDER_SNOW, Blocks.WITHER_ROSE, Blocks.POINTED_DRIPSTONE);

    // Kosten (1 = ein Block laufen)
    private static final double COST_JUMP = 2.2D;
    private static final double COST_PILLAR = 4.5D;
    private static final double COST_BRIDGE = 4.0D;
    /**
     * Eigenen Hilfsblock unter sich abbauen kostet kaum mehr als Laufen: sonst stieg er fast nie ueber seinen Turm ab
     * (Sprung ins Becken war billiger), und fuer jeden Aufstieg blieb ein neuer Turm stehen (91 an einem Tag).
     */
    private static final double COST_DIG = 1.3D;
    private static final double COST_BREAK = 3.0D;
    /** Hoechster Sprung in Bloecken (Vanilla ~1.25, mit etwas Luft). */
    private static final double MAX_JUMP = 1.2D;
    /** So tief springt er hoechstens ins Wasser (fangt den Fall ganz ab). */
    private static final int WATER_DROP = 24;
    private static final double PLAYER_HEIGHT = 1.8D;
    /** Suche etwas zielstrebiger als "perfektes" A* (schneller, Weg fast genauso gut). */
    private static final double GREED = 1.35D;

    public enum Move {
        START, WALK, JUMP, DROP, PILLAR, BRIDGE, DIG, BREAK;

        public boolean builds() {
            return this == PILLAR || this == BRIDGE;
        }
    }

    /**
     * Was die Suche ausser Laufen noch darf. canBreak != null: Bloecke im Weg abbauen und hindurchlaufen (BREAK),
     * breakCost sagt, wie teuer das je Block ist.
     */
    public record Options(boolean pillar, boolean bridge, boolean dig, int maxDrop, Predicate<BlockPos> canScaffold,
                          ToDoubleFunction<BlockPos> scaffoldCost, Set<BlockPos> scaffolds, int pillarRadius,
                          Predicate<BlockPos> canBreak, ToDoubleFunction<BlockPos> breakCost) {
        /** Nur laufen, springen, bis 3 Bloecke fallen. */
        public static final Options WALK_ONLY = new Options(false, false, false, 3, p -> false, p -> 0.0D, Set.of(), 0,
                null, null);
        /** Tuerme normalerweise nur in der Naehe des Ziels (wie ein Mensch - und die Suche bleibt schnell). */
        public static final int NEAR = 8;

        public Options(boolean pillar, boolean bridge, boolean dig, int maxDrop, Predicate<BlockPos> canScaffold,
                       ToDoubleFunction<BlockPos> scaffoldCost, Set<BlockPos> scaffolds) {
            this(pillar, bridge, dig, maxDrop, canScaffold, scaffoldCost, scaffolds, NEAR, null, null);
        }

        public Options withMaxDrop(int drop) {
            return new Options(pillar, bridge, dig, drop, canScaffold, scaffoldCost, scaffolds, pillarRadius, canBreak, breakCost);
        }

        /** Ohne Tuerme und Bruecken (keine Hilfsbloecke dabei). */
        public Options withoutBuilding() {
            return new Options(false, false, dig, maxDrop, canScaffold, scaffoldCost, scaffolds, pillarRadius, canBreak,
                    breakCost);
        }

        /** Tuerme ueberall erlauben (gruendlichere zweite Suche). */
        public Options anywhere() {
            return new Options(pillar, bridge, dig, maxDrop, canScaffold, scaffoldCost, scaffolds, Integer.MAX_VALUE,
                    canBreak, breakCost);
        }

        /** Darf sich auch durch Bloecke graben (nur als letzter Ausweg, wenn sonst kein Weg da ist). */
        public Options withBreaking(Predicate<BlockPos> canBreak, ToDoubleFunction<BlockPos> breakCost) {
            return new Options(pillar, bridge, dig, maxDrop, canScaffold, scaffoldCost, scaffolds, pillarRadius,
                    canBreak, breakCost);
        }
    }

    /** Ein gefundener Weg: nodes.get(i) wird mit moves.get(i) erreicht (moves.get(0) = START). */
    public record Path(List<BlockPos> nodes, List<Move> moves) {
        public int buildMoves() {
            int n = 0;
            for (Move m : moves) {
                if (m.builds()) {
                    n++;
                }
            }
            return n;
        }

        public boolean breaks() {
            return moves.contains(Move.BREAK);
        }

        public BlockPos end() {
            return nodes.get(nodes.size() - 1);
        }
    }

    private record Node(BlockPos pos, double g, double f) {
    }

    /** Gedachte Bloecke fuer "Was waere wenn"-Pruefungen (nur Client-Thread, siehe assuming). */
    private static BlockPos assumeSolid;
    private static Set<BlockPos> assumeAir = Set.of();

    private Pathfinder() {
    }

    /**
     * Rechnet so, als waere solid ein fester Block und alles in air leer (z.B. "sperre ich mich ein, wenn ich hier
     * einen Block setze?" oder "komme ich raus, wenn ich diese Bloecke abbaue?").
     */
    public static <T> T assuming(BlockPos solid, Set<BlockPos> air, Supplier<T> body) {
        BlockPos oldSolid = assumeSolid;
        Set<BlockPos> oldAir = assumeAir;
        assumeSolid = solid == null ? null : solid.immutable();
        assumeAir = air == null ? Set.of() : air;
        try {
            return body.get();
        } finally {
            assumeSolid = oldSolid;
            assumeAir = oldAir;
        }
    }

    private static BlockState state(BlockGetter level, BlockPos pos) {
        if (assumeSolid != null && assumeSolid.equals(pos)) {
            return Blocks.STONE.defaultBlockState();
        }
        if (!assumeAir.isEmpty() && assumeAir.contains(pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        if (WorldOverlay.active) {
            BlockState assumed = WorldOverlay.get(pos); // auch ohne den Mixin (Lithium ersetzt Level.getBlockState)
            if (assumed != null) {
                return assumed;
            }
        }
        return level.getBlockState(pos);
    }

    // ---------------------------------------------------------------- Begehbarkeit

    private static boolean loaded(BlockGetter level, BlockPos pos) {
        if (pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) {
            return false;
        }
        return !(level instanceof Level world) || world.isLoaded(pos);
    }

    public static boolean danger(BlockState state) {
        return DANGER.contains(state.getBlock());
    }

    private static double height(BlockGetter level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() ? 0.0D : shape.max(Direction.Axis.Y);
    }

    /** Kopf/Koerper passt hier durch (keine Kollision, nichts Gefaehrliches). */
    public static boolean passable(BlockGetter level, BlockPos pos) {
        if (!loaded(level, pos)) {
            return false;
        }
        BlockState state = state(level, pos);
        if (danger(state) || state.getFluidState().is(Fluids.LAVA) || state.getFluidState().is(Fluids.FLOWING_LAVA)) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** Kann man mit den Fuessen hier stehen (Teppich, Druckplatte usw. zaehlen als frei)? */
    public static boolean feetFree(BlockGetter level, BlockPos pos) {
        if (!loaded(level, pos)) {
            return false;
        }
        BlockState state = state(level, pos);
        // Auch fliessendes Wasser (Fluids.WATER ist nur die Quelle): sonst galt er in einem Becken, das gerade
        // vollaeuft, als eingesperrt und hielt an
        if (danger(state) || !state.getFluidState().isEmpty() && !state.getFluidState().is(Fluids.WATER)
                && !state.getFluidState().is(Fluids.FLOWING_WATER)) {
            return false;
        }
        return height(level, pos, state) <= 0.2D;
    }

    /** Ist der Block eine tragende Unterlage (oben etwa auf voller Hoehe, kein Zaun)? */
    public static boolean supports(BlockGetter level, BlockPos pos) {
        if (!loaded(level, pos)) {
            return false;
        }
        BlockState state = state(level, pos);
        if (danger(state)) {
            return false;
        }
        // Glasscheiben/Gitter sind zu schmal zum Drauflaufen: man rutscht seitlich ab (in eine Wasserrinne, aus der er
        // dann nicht mehr herauskam)
        if (state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock) {
            return false;
        }
        double h = height(level, pos, state);
        return h >= 0.8D && h <= 1.0D;
    }

    /** Kann der Spieler mit den Fuessen in diesem Block stehen? */
    public static boolean standable(BlockGetter level, BlockPos feet) {
        if (feetFree(level, feet) && passable(level, feet.above()) && supports(level, feet.below())) {
            return true;
        }
        // Untere Stufe als Boden (Fuesse stehen im Stufen-Block)
        if (!loaded(level, feet)) {
            return false;
        }
        BlockState state = state(level, feet);
        return state.getBlock() instanceof SlabBlock && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM
                && passable(level, feet.above()) && passable(level, feet.above(2));
    }

    /**
     * Auf welcher Hoehe stehen die Fuesse, relativ zu feet.getY()? Normal 0, in einer unteren Stufe 0.5, auf Teppich
     * 0.06, auf Seelensand/Ackerboden etwas darunter.
     */
    public static double floorHeight(BlockGetter level, BlockPos feet) {
        BlockState here = state(level, feet);
        double h = footHeight(level, feet, here);
        if (h > 0.0D) {
            return h;
        }
        BlockPos below = feet.below();
        double b = footHeight(level, below, state(level, below));
        return b > 0.0D ? b - 1.0D : 0.0D; // nichts darunter (z.B. Startpunkt mitten im Sprung): wie normaler Boden
    }

    /**
     * Hoehe, auf der ein Spieler in der Blockmitte tatsaechlich steht. Bei Trichter/Kessel ist das die Mulde (11/16
     * bzw. 4/16), nicht der Rand - von dort kommt man keinen ganzen Block hoch.
     */
    private static double footHeight(BlockGetter level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0D;
        }
        double top = 0.0D;
        for (AABB box : shape.toAabbs()) {
            if (box.maxX > 0.2D && box.minX < 0.8D && box.maxZ > 0.2D && box.minZ < 0.8D) {
                top = Math.max(top, box.maxY);
            }
        }
        return top;
    }

    /**
     * Schafft ein Sprung von from auf to (Nachbarfeld, eins hoeher)? Ein Spieler kommt hoechstens ~1.25 Bloecke hoch -
     * vom normalen Boden also NICHT auf eine untere Stufe, die einen Block hoeher liegt (das sind 1.5). Ausserdem muss
     * ueber from Platz fuer den Kopf sein, bis er oben aufkommt.
     */
    static boolean canJump(BlockGetter level, BlockPos from, BlockPos to) {
        double start = from.getY() + floorHeight(level, from);
        if (inWater(level, from)) {
            // Im Wasser schwimmt man mit gehaltener Leertaste hoch, bis nur noch 0,4 eingetaucht sind, und springt erst
            // dann - die Mulde eines Trichters darunter zaehlt dann nicht (sonst galt der Kanal darueber als Falle).
            // Ganz flaches fliessendes Wasser hilft dagegen nicht.
            double surface = from.getY() + state(level, from).getFluidState().getHeight(level, from);
            start = Math.max(start, surface - 0.4D);
        }
        double land = to.getY() + floorHeight(level, to);
        // Auf Honig (Sprungfaktor 0,5) kommt man nur etwa ein Viertel so hoch - dort keinen Block hochspringen
        float factor = state(level, from.below()).getBlock().getJumpFactor();
        if (land - start > MAX_JUMP * factor * factor) {
            return false;
        }
        double headTop = land + PLAYER_HEIGHT;
        for (int y = from.getY() + 2; y < headTop; y++) {
            if (!passable(level, new BlockPos(from.getX(), y, from.getZ()))) {
                return false;
            }
        }
        return true;
    }

    /** Hier passt ein Hilfsblock hin (leer bzw. ersetzbar, kein Lava). */
    public static boolean replaceable(BlockGetter level, BlockPos pos) {
        if (!loaded(level, pos)) {
            return false;
        }
        BlockState state = state(level, pos);
        return state.canBeReplaced() && !state.getFluidState().is(Fluids.LAVA) && !state.getFluidState().is(Fluids.FLOWING_LAVA);
    }

    /** Geht es hinter c mehr als 3 Bloecke frei hinunter (ohne Wasser zum Auffangen)? */
    private static boolean deepDrop(BlockGetter level, BlockPos c) {
        if (!feetFree(level, c) || !passable(level, c.above()) || inWater(level, c)) {
            return false;
        }
        for (int k = 1; k <= 3; k++) {
            BlockPos b = c.below(k);
            if (supports(level, b) || inWater(level, b) || !passable(level, b)) {
                return false;
            }
        }
        return true;
    }

    public static boolean inWater(BlockGetter level, BlockPos feet) {
        if (!loaded(level, feet)) {
            return false;
        }
        var fluid = state(level, feet).getFluidState();
        return fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER);
    }

    /** Neben diesem Platz geht es tief runter oder ist Lava (zum Schleichen an Kanten - so rutscht man nicht hinein). */
    public static boolean nearDrop(BlockGetter level, BlockPos feet) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (passable(level, side) && passable(level, side.below()) && passable(level, side.below(2))) {
                return true;
            }
            if (lava(level, side) || lava(level, side.below())) {
                return true;
            }
        }
        return false;
    }

    private static boolean lava(BlockGetter level, BlockPos pos) {
        return loaded(level, pos) && state(level, pos).getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    // ---------------------------------------------------------------- Suche

    /** Einfache Suche ohne Bau-Bewegungen (Liste der Fuss-Positionen). */
    public static List<BlockPos> find(BlockGetter level, BlockPos start, Predicate<BlockPos> goal, BlockPos heuristicTarget,
                                      int maxNodes) {
        Path path = find(level, start, goal, heuristicTarget, maxNodes, Options.WALK_ONLY);
        return path == null ? null : path.nodes();
    }

    /**
     * Weg von start bis zu einer Position, die goal erfuellt. heuristicTarget lenkt die Suche (darf ungefaehr sein).
     * null = nichts gefunden (oder Knoten-Limit erreicht).
     */
    public static Path find(BlockGetter level, BlockPos start, Predicate<BlockPos> goal, BlockPos heuristicTarget,
                            int maxNodes, Options options) {
        start = start.immutable();
        if (goal.test(start)) {
            return new Path(new ArrayList<>(List.of(start)), new ArrayList<>(List.of(Move.START)));
        }
        // Darf er Tuerme bauen, kostet jeder Block nach oben etwa so viel wie ein Turmblock
        double up = options.pillar() ? 3.4D : COST_JUMP;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Move> via = new HashMap<>();
        open.add(new Node(start, 0.0D, heuristic(start, heuristicTarget, up)));
        best.put(start, 0.0D);
        via.put(start, Move.START);
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node node = open.poll();
            Double known = best.get(node.pos);
            if (known != null && node.g > known + 1.0E-6D) {
                continue;
            }
            expanded++;
            if (goal.test(node.pos)) {
                return rebuild(parent, via, node.pos);
            }
            expand(level, node, options, open, best, parent, via, heuristicTarget, up);
        }
        return null;
    }

    /**
     * Alle Fuss-Positionen, die man von start aus erreicht (mit denselben Bewegungen wie bei der Wegsuche).
     * Positionen ausserhalb von area werden noch aufgenommen, aber nicht weiter erkundet.
     */
    public static Set<BlockPos> explore(BlockGetter level, BlockPos start, Options options, Predicate<BlockPos> area,
                                        int maxNodes) {
        start = start.immutable();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<BlockPos, Double> best = new HashMap<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Move> via = new HashMap<>();
        Set<BlockPos> seen = new HashSet<>();
        open.add(new Node(start, 0.0D, 0.0D));
        best.put(start, 0.0D);
        while (!open.isEmpty() && seen.size() < maxNodes) {
            Node node = open.poll();
            Double known = best.get(node.pos);
            if (known != null && node.g > known + 1.0E-6D || !seen.add(node.pos)) {
                continue;
            }
            if (area.test(node.pos)) {
                expand(level, node, options, open, best, parent, via, null, 1.0D);
            }
        }
        return seen;
    }

    private static void expand(BlockGetter level, Node node, Options options, PriorityQueue<Node> open,
                               Map<BlockPos, Double> best, Map<BlockPos, BlockPos> parent, Map<BlockPos, Move> via,
                               BlockPos target, double upCost) {
        BlockPos pos = node.pos;
        for (int[] d : DIRS) {
            boolean diagonal = d[0] != 0 && d[1] != 0;
            BlockPos flat = pos.offset(d[0], 0, d[1]);
            // Geradeaus / schraeg auf gleicher Hoehe
            if (standable(level, flat)) {
                if (!diagonal || cornerFree(level, pos, d)) {
                    double cost = (diagonal ? 1.414D : 1.0D) + (inWater(level, flat) ? 3.0D : 0.0D);
                    relax(open, best, parent, via, node, flat, cost, Move.WALK, target, upCost);
                }
                continue;
            }
            if (diagonal) {
                continue;
            }
            // Einen Block hochspringen
            BlockPos up = flat.above();
            if (standable(level, up) && canJump(level, pos, up)) {
                relax(open, best, parent, via, node, up, COST_JUMP, Move.JUMP, target, upCost);
                continue;
            }
            if (!feetFree(level, flat) || !passable(level, flat.above())) {
                continue;
            }
            // Herunterfallen (ab 4 Bloecken nimmt man Schaden - nur wenn ausdruecklich erlaubt). Ins Wasser auch tiefer und
            // ohne Schaden (Wasser faengt jeden Fall ab) - so kommt er z.B. vom Steg unter dem Generator ins Becken
            for (int k = 1; k <= Math.max(options.maxDrop(), WATER_DROP); k++) {
                BlockPos down = flat.below(k);
                if (standable(level, down)) {
                    boolean water = inWater(level, down);
                    if (k > options.maxDrop() && !water) {
                        break;
                    }
                    double cost = 1.2D + 0.6D * k + (k > 3 && !water ? 12.0D * (k - 3) : 0.0D);
                    if (deepDrop(level, down.offset(d[0], 0, d[1]))) {
                        // Landung z.B. auf einer Turmspitze, dahinter geht es tief hinunter: mit dem Schwung rutscht man
                        // leicht hinueber (9 Herzen Fallschaden) - nur wenn es anders nicht geht
                        cost += 30.0D;
                    }
                    relax(open, best, parent, via, node, down, cost, Move.DROP, target, upCost);
                    break;
                }
                if (!passable(level, down)) {
                    break;
                }
            }
            // Bruecke: Hilfsblock unter das naechste Feld setzen
            if (options.bridge() && replaceable(level, flat.below()) && options.canScaffold().test(flat.below())) {
                relax(open, best, parent, via, node, flat.immutable(),
                        COST_BRIDGE + options.scaffoldCost().applyAsDouble(flat.below()), Move.BRIDGE, target, upCost);
            }
        }
        // Durchbruch (nur wenn erlaubt): Bloecke im Weg abbauen - geradeaus, eine Stufe hoch oder eine runter
        if (options.canBreak() != null) {
            for (int i = 0; i < 4; i++) {
                BlockPos flat = pos.offset(DIRS[i][0], 0, DIRS[i][1]);
                breakMove(level, node, flat, options, open, best, parent, via, target, upCost);
                breakMove(level, node, flat.above(), options, open, best, parent, via, target, upCost);
                breakMove(level, node, flat.below(), options, open, best, parent, via, target, upCost);
            }
        }
        // Turm: Hilfsblock dort setzen, wo die Fuesse sind, und eins hoeher stehen
        if (options.pillar() && nearTarget(pos, target, options.pillarRadius()) && replaceable(level, pos)
                && options.canScaffold().test(pos) && passable(level, pos.above(2))) {
            relax(open, best, parent, via, node, pos.above(), COST_PILLAR + options.scaffoldCost().applyAsDouble(pos),
                    Move.PILLAR, target, upCost);
        }
        // Eigenen Hilfsblock unter sich abbauen und eins tiefer landen
        // Nur wenn der Hilfsblock wirklich noch steht (die gemerkte Liste kann veraltet sein - dann plante er ein Abgraben
        // durch Luft und hing fest, weil er nie auf der erwarteten Hoehe ankam)
        if (options.dig() && options.scaffolds().contains(pos.below()) && !replaceable(level, pos.below())
                && (supports(level, pos.below(2))
                || options.scaffolds().contains(pos.below(2)) && !replaceable(level, pos.below(2)))) {
            relax(open, best, parent, via, node, pos.below(), COST_DIG, Move.DIG, target, upCost);
        }
    }

    private static void breakMove(BlockGetter level, Node node, BlockPos dest, Options options, PriorityQueue<Node> open,
                                  Map<BlockPos, Double> best, Map<BlockPos, BlockPos> parent, Map<BlockPos, Move> via,
                                  BlockPos target, double upCost) {
        List<BlockPos> cells = breakCells(level, node.pos, dest);
        if (cells.isEmpty() || !supports(level, dest.below())) {
            return; // nichts im Weg (das decken die normalen Bewegungen ab) bzw. kein Boden
        }
        double cost = COST_BREAK;
        for (BlockPos c : cells) {
            if (!options.canBreak().test(c)) {
                return;
            }
            cost += options.breakCost().applyAsDouble(c);
        }
        // Darueber nichts, das nachrutscht (Sand, Kies ...) - sonst ist das Loch gleich wieder zu
        if (state(level, cells.get(0).above()).getBlock() instanceof FallingBlock) {
            return;
        }
        relax(open, best, parent, via, node, dest.immutable(), cost, Move.BREAK, target, upCost);
    }

    /**
     * Welche Bloecke muessen weg, um von from auf das Nachbarfeld to (gleiche Hoehe, eins hoeher oder tiefer) zu
     * kommen? Oberster zuerst (so wird von oben nach unten abgebaut).
     */
    public static List<BlockPos> breakCells(BlockGetter level, BlockPos from, BlockPos to) {
        BlockPos[] cells;
        if (to.getY() > from.getY()) {
            cells = new BlockPos[]{from.above(2), to.above(), to}; // Kopffreiheit zum Hochspringen
        } else if (to.getY() < from.getY()) {
            cells = new BlockPos[]{to.above(2), to.above(), to};
        } else {
            cells = new BlockPos[]{to.above(), to};
        }
        List<BlockPos> out = new ArrayList<>(3);
        for (BlockPos c : cells) {
            boolean free = c.equals(to) ? feetFree(level, c) : passable(level, c);
            if (!free) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean nearTarget(BlockPos pos, BlockPos target, int radius) {
        return target == null || radius == Integer.MAX_VALUE
                || Math.max(Math.abs(pos.getX() - target.getX()), Math.abs(pos.getZ() - target.getZ())) <= radius;
    }

    private static boolean cornerFree(BlockGetter level, BlockPos from, int[] d) {
        BlockPos a = from.offset(d[0], 0, 0);
        BlockPos b = from.offset(0, 0, d[1]);
        // Schraeg nur ueber Boden: ein Loch im Eckfeld (offene Rinne) - dort rutscht man beim Schraeglaufen leicht hinein
        return feetFree(level, a) && passable(level, a.above()) && feetFree(level, b) && passable(level, b.above())
                && supports(level, a.below()) && supports(level, b.below());
    }

    private static void relax(PriorityQueue<Node> open, Map<BlockPos, Double> best, Map<BlockPos, BlockPos> parent,
                              Map<BlockPos, Move> via, Node from, BlockPos to, double cost, Move move, BlockPos target,
                              double up) {
        double g = from.g + cost;
        Double known = best.get(to);
        if (known == null || g < known - 1.0E-6D) {
            BlockPos key = to.immutable();
            best.put(key, g);
            parent.put(key, from.pos);
            via.put(key, move);
            open.add(new Node(key, g, g + GREED * heuristic(key, target, up)));
        }
    }

    private static double heuristic(BlockPos a, BlockPos b, double up) {
        if (b == null) {
            return 0.0D;
        }
        int dx = Math.abs(a.getX() - b.getX());
        int dz = Math.abs(a.getZ() - b.getZ());
        int dy = b.getY() - a.getY();
        // Hoch ist teuer (springen oder bauen), runter billig
        return Math.max(dx, dz) + 0.414D * Math.min(dx, dz) + (dy > 0 ? dy * up : -dy * 1.0D);
    }

    private static Path rebuild(Map<BlockPos, BlockPos> parent, Map<BlockPos, Move> via, BlockPos end) {
        List<BlockPos> nodes = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        BlockPos cursor = end;
        while (cursor != null) {
            nodes.add(cursor);
            moves.add(via.getOrDefault(cursor, Move.WALK));
            cursor = parent.get(cursor);
        }
        Collections.reverse(nodes);
        Collections.reverse(moves);
        return new Path(nodes, moves);
    }
}
