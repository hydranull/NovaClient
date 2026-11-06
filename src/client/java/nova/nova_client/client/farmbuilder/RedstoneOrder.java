package nova.nova_client.client.farmbuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redstone-sichere Reihenfolge fuer grosse Farmen: Waehrend des Baus soll nichts losgehen - kein Kolben, der halb fertige
 * Teile wegschiebt, kein Beobachter, der beim Setzen eines Nachbarn pulst, kein TNT, das zuendet.
 * <ul>
 * <li>Stromquellen (Redstone-Fackel, Redstone-Block, Tageslicht- und Sculk-Sensor) erst, wenn alles im Umkreis 2 steht.</li>
 * <li>Beobachter erst nach dem Block, den sie sehen (verbindet der sich mit Nachbarn - Redstone, Schienen, Zaeune ... -
 * auch nach diesen), und nach den Stromquellen, die diesen Block schalten koennten.</li>
 * <li>TNT zuletzt (Umkreis 3) - und nur, wenn die Stelle keinen Strom hat (prueft der Bot beim Setzen).</li>
 * </ul>
 * Jeder Block hat eine Stufe; gewartet wird nur auf Bloecke mit kleinerer Stufe - so kann nie etwas im Kreis aufeinander
 * warten. Was an einem Block haengt (Fackel, Schiene, Staub ...), wartet nie auf ihn: es braucht ihn zum Setzen.
 */
public final class RedstoneOrder {
    private static final int PLAIN = 0;
    private static final int SOURCE = 1;
    private static final int OBSERVER = 2;
    private static final int TNT = 3;
    private static final BlockPos[] NONE = new BlockPos[0];

    /** Je Block: was vorher stehen muss. */
    private final Map<BlockPos, BlockPos[]> before = new HashMap<>();
    /** Umgekehrt: wer auf diesen Block wartet. */
    private final Map<BlockPos, List<BlockPos>> after = new HashMap<>();
    /** Darf trotzdem sofort: sonst verliert er seinen letzten Standplatz (siehe AccessGuard). */
    private final Set<BlockPos> early = new HashSet<>();
    public final int sources;
    public final int observers;
    public final int tnt;

    public RedstoneOrder(List<BuildPlan.Target> targets) {
        Map<BlockPos, BuildPlan.Target> at = new HashMap<>();
        for (BuildPlan.Target t : targets) {
            if (t.need().kind() == Materials.Kind.PLACE) {
                at.put(t.pos(), t);
            }
        }
        Map<BlockPos, List<BlockPos>> watch = new HashMap<>();
        int s = 0;
        int o = 0;
        int n = 0;
        for (BuildPlan.Target t : at.values()) {
            switch (rank(t.state())) {
                case SOURCE -> s++;
                case OBSERVER -> {
                    o++;
                    watch.put(t.pos(), watched(at, t));
                }
                case TNT -> n++;
                default -> {
                }
            }
        }
        sources = s;
        observers = o;
        tnt = n;
        Map<BlockPos, Integer> stage = new HashMap<>();
        for (BlockPos p : watch.keySet()) {
            stage(p, at, watch, stage, new HashSet<>());
        }
        for (BuildPlan.Target t : at.values()) {
            int r = rank(t.state());
            if (r == PLAIN) {
                continue;
            }
            int own = stageOf(t, stage);
            Set<BlockPos> need = new LinkedHashSet<>();
            BlockPos p = t.pos();
            if (r == OBSERVER) {
                // Nur was er sieht - und Stromquellen, die das veraendern koennten (bis 2 Felder davon). Auf alle
                // Bloecke ringsum zu warten, wuerde ihn oft so einbauen, dass man nicht mehr an ihn herankommt.
                need.addAll(watch.get(p));
                BlockPos w = p.relative(t.state().getValue(BlockStateProperties.FACING));
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            BuildPlan.Target u = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2 ? null
                                    : at.get(w.offset(dx, dy, dz));
                            if (u != null && rank(u.state()) == SOURCE && stageOf(u, stage) < own && !hangsOn(u, t)) {
                                need.add(u.pos());
                            }
                        }
                    }
                }
            } else {
                int radius = r == TNT ? 3 : 2;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            BuildPlan.Target u = (dx | dy | dz) == 0 ? null : at.get(p.offset(dx, dy, dz));
                            if (u != null && rank(u.state()) < r && stageOf(u, stage) < own && !hangsOn(u, t)) {
                                need.add(u.pos());
                            }
                        }
                    }
                }
            }
            if (need.isEmpty()) {
                continue;
            }
            BlockPos[] list = need.toArray(NONE);
            before.put(p, list);
            for (BlockPos q : list) {
                after.computeIfAbsent(q, k -> new ArrayList<>()).add(p);
            }
        }
    }

    /** Was vor diesem Block stehen muss (leer = nichts). */
    public BlockPos[] before(BlockPos pos) {
        return before.getOrDefault(pos, NONE);
    }

    /** Wer auf diesen Block wartet. */
    public List<BlockPos> after(BlockPos pos) {
        return after.getOrDefault(pos, List.of());
    }

    /** Ohne Warten setzen lassen (sein letzter Standplatz waere sonst weg). */
    public void allowEarly(BlockPos pos) {
        early.add(pos.immutable());
    }

    public boolean early(BlockPos pos) {
        return early.contains(pos);
    }

    public void clearEarly() {
        early.clear();
    }

    public static boolean isTnt(BlockState state) {
        return state.getBlock() instanceof TntBlock;
    }

    private static int rank(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof TntBlock) {
            return TNT;
        }
        if (b instanceof ObserverBlock) {
            return OBSERVER;
        }
        if (b instanceof RedstoneTorchBlock || b == Blocks.REDSTONE_BLOCK || b instanceof DaylightDetectorBlock
                || b instanceof SculkSensorBlock) {
            return SOURCE;
        }
        return PLAIN;
    }

    private static int stageOf(BuildPlan.Target t, Map<BlockPos, Integer> stage) {
        Integer known = stage.get(t.pos());
        return known != null ? known : rank(t.state()) * 1000;
    }

    /** Beobachter: hoeher als alles, was er sieht. Sehen sich Beobachter im Kreis, faellt die schliessende Kante weg. */
    private static int stage(BlockPos p, Map<BlockPos, BuildPlan.Target> at, Map<BlockPos, List<BlockPos>> watch,
                             Map<BlockPos, Integer> stage, Set<BlockPos> active) {
        Integer known = stage.get(p);
        if (known != null) {
            return known;
        }
        List<BlockPos> sees = watch.get(p);
        if (sees == null) {
            BuildPlan.Target t = at.get(p);
            return t == null ? 0 : rank(t.state()) * 1000;
        }
        active.add(p);
        int value = OBSERVER * 1000;
        Iterator<BlockPos> it = sees.iterator();
        while (it.hasNext()) {
            BlockPos w = it.next();
            if (active.contains(w)) {
                it.remove();
                continue;
            }
            value = Math.max(value, stage(w, at, watch, stage, active) + 1);
        }
        active.remove(p);
        stage.put(p, value);
        return value;
    }

    /** Was ein Beobachter sieht: den Block vor ihm - und dessen Nachbarn, wenn der sich mit ihnen verbindet. */
    private static List<BlockPos> watched(Map<BlockPos, BuildPlan.Target> at, BuildPlan.Target observer) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos w = observer.pos().relative(observer.state().getValue(BlockStateProperties.FACING));
        BuildPlan.Target seen = at.get(w);
        if (seen == null) {
            return list;
        }
        if (!hangsOn(seen, observer)) {
            list.add(w);
        }
        if (connects(seen.state())) {
            for (Direction d : Direction.values()) {
                BuildPlan.Target u = at.get(w.relative(d));
                if (u != null && !u.pos().equals(observer.pos()) && !hangsOn(u, observer)) {
                    list.add(u.pos());
                }
            }
        }
        return list;
    }

    /** Aendert sich der Block, wenn daneben etwas gesetzt wird (Form, Verbindung, Ton)? */
    private static boolean connects(BlockState state) {
        Block b = state.getBlock();
        return b instanceof RedstoneWireBlock || b instanceof BaseRailBlock || b instanceof CrossCollisionBlock
                || b instanceof WallBlock || b instanceof NoteBlock || b instanceof ChestBlock || b instanceof StairBlock
                || b instanceof TripWireBlock || b instanceof DoorBlock || b instanceof FenceGateBlock;
    }

    /** Kann u an t haengen (direkt daneben und kein voller Block: Fackel, Schiene, Staub, Knopf ...)? */
    private static boolean hangsOn(BuildPlan.Target u, BuildPlan.Target t) {
        return u.pos().distManhattan(t.pos()) == 1
                && !u.state().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}
