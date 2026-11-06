package nova.nova_client.client.farmbuilder;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schuetzt die Standplaetze der noch offenen Bloecke. Viele Bloecke lassen sich nur von wenigen Stellen aus richtig
 * setzen (Werfer nach unten: nur von UNTEN mit Blick nach oben, Trichter: nur mit Klick auf den Block, in den er zeigt,
 * Treppen, Beobachter, Kolben ...). Baut der Bot vorher alles drumherum zu, steht er am Ende vor einem Block, den er
 * von nirgends mehr setzen kann.
 * <p>
 * Deshalb wird VOR jedem Block X geprueft: Gibt es einen offenen Block T, der danach keinen einzigen brauchbaren
 * Standplatz mehr haette (passend ausgerichtet, sichtbar, Kopf frei, man kommt von dort wieder weg)? Dann wartet X,
 * bis T steht. Fehlt T noch der Nachbar zum Dagegenklicken, wird gerechnet, als staenden seine offenen Nachbarn schon
 * (die kommen typischerweise zusammen mit ihm) - so werden auch Bloecke geschuetzt, die erst spaeter dran sind.
 * <p>
 * Gerechnet wird exakt mit Minecrafts Platzier-Logik in einer gedachten Welt ({@link WorldOverlay}). Die
 * Standplatz-Listen werden je Block zwischengespeichert und nur neu berechnet, wenn sich in der Naehe etwas aendert;
 * die Rechenzeit je Tick ist begrenzt.
 */
public final class AccessGuard {
    /** Was der Bot dem Waechter sagen muss. */
    public interface Host {
        Collection<BuildPlan.Target> pending();

        /**
         * Wasser/Lava/nasses Laub, die jetzt gefuellt werden koennen und spaeter eingemauert waeren (Art FLUID). Ihre
         * Standplaetze werden genauso geschuetzt - ein Block, der dem Eimer den letzten Weg nimmt, wartet.
         */
        default Collection<BuildPlan.Target> fluidTasks() {
            return List.of();
        }

        /**
         * Offene Bloecke ungefaehr in der Naehe (Obermenge - genau filtert der Aufrufer). Bei grossen Farmen mit 16000
         * offenen Bloecken war das Durchgehen ALLER Bloecke je Pruefung der Hauptgrund fuer "GUARD stalled".
         */
        default Collection<BuildPlan.Target> pendingNear(BlockPos center, int range) {
            return pending();
        }

        BuildPlan.Target pendingAt(BlockPos pos);

        /** Wird gerade nicht gebaut (Material fehlt, aufgegeben)? */
        boolean ignored(BuildPlan.Target t);

        /** Kommt man von diesem Standplatz wieder aus der Farm heraus? (beachtet die gedachte Welt) */
        boolean leavable(BlockPos stand);

        /** Platz, den der Bot meiden soll (dort war er schon eingesperrt)? */
        boolean avoid(BlockPos stand);

        /**
         * Lohnt es sich, die Standplaetze von t zu schuetzen? Nur bei Bloecken, die von wenigen Plaetzen aus gehen
         * (Ausrichtung haengt vom Blick ab) oder die schon gescheitert sind / auf die andere warten - einfache Bloecke
         * lassen sich fast von ueberall setzen, dafuer zu rechnen kostet nur Zeit.
         */
        boolean protect(BuildPlan.Target t);
    }

    /** Ein Standplatz, von dem aus ein Block geht: Fuesse + Felder zwischen Auge und Klickpunkt. */
    public record Stand(BlockPos feet, Set<BlockPos> ray) {
    }

    /** Ergebnis der Pruefung, wenn das Zeitbudget nicht gereicht hat - spaeter noch mal fragen. */
    public static final BuildPlan.Target UNDECIDED = new BuildPlan.Target(BlockPos.ZERO, Blocks.AIR.defaultBlockState(),
            null, false);

    /** Standplaetze je Block hoechstens so viele sammeln (mehr braucht man nicht, um "genug" zu wissen). */
    private static final int MAX_STANDS = 10;
    /** Ab so vielen Standplaetzen ist ein Block nicht eingeschraenkt (ausser X liegt direkt daneben). */
    private static final int PLENTY = 6;
    /** So viele moegliche Standplaetze je Block hoechstens durchrechnen. */
    private static final int MAX_PLANS = 24;
    /** Bloecke so weit (in X/Z) um X koennen betroffen sein (Standplatz-Bereich +-4). */
    private static final int RANGE = 4;
    /** Je Pruefung hoechstens so viele Standplatz-Listen neu rechnen (der Rest gilt als unbedenklich). */
    private static final int MAX_NEW_INFOS = 6;
    /** Ein Block so weit weg kann einem Standplatz den Ausgang nehmen (Wand um eine Grube, Gang zumachen). */
    private static final int EXIT_RANGE = 16;

    private static final class Info {
        final List<Stand> stands;
        /**
         * Gedachte Welt, in der die Standplaetze gelten: leerer Zielplatz und - wenn es in der echten Welt noch nichts
         * zum Dagegenklicken gibt - EIN offener Nachbar laut Vorlage (einer reicht zum Anklicken).
         */
        final Map<BlockPos, BlockState> hypothesis;
        /** Der angenommene Nachbar (null = echte Welt). */
        final BlockPos assumed;
        /** Je Standplatz: kommt man (in der gedachten Welt) wieder weg? Wird erst bei Bedarf gerechnet. */
        final Map<BlockPos, Boolean> exits = new HashMap<>();
        boolean stale;

        Info(List<Stand> stands, Map<BlockPos, BlockState> hypothesis, BlockPos assumed) {
            this.stands = stands;
            this.hypothesis = hypothesis;
            this.assumed = assumed;
        }
    }

    private final Host host;
    private final Placer placer = new Placer();
    private final Map<BlockPos, Info> infos = new HashMap<>();
    /** Rechenbudget je Tick und was davon schon verbraucht ist - gezaehlt wird nur die eigene Rechenzeit. */
    private long budget;
    private long spent;
    /** Beginn des laufenden Aufrufs (0 = gerade keiner). */
    private long callStart;

    public AccessGuard(Host host) {
        this.host = host;
    }

    public void clear() {
        infos.clear();
    }

    /**
     * Neues Rechenbudget fuer diesen Tick. Frueher galt es ab Tick-Beginn (Uhrzeit): hatte vorher im selben Tick die
     * Wegsuche Zeit gebraucht, war es schon "aufgebraucht", der Waechter sagte immer "noch am Rechnen" und wurde nach
     * 5 Sekunden abgeschaltet ("GUARD stalled") - obwohl er kaum gerechnet hatte.
     */
    public void beginTick(long nanos) {
        budget = nanos;
        spent = 0L;
    }

    private void enter() {
        callStart = System.nanoTime();
    }

    private void leave() {
        spent += System.nanoTime() - callStart;
        callStart = 0L;
    }

    private boolean outOfTime() {
        return spent + (System.nanoTime() - callStart) > budget;
    }

    /** In der Welt hat sich bei pos etwas geaendert: betroffene Standplatz-Listen neu berechnen. */
    public void touch(BlockPos pos) {
        for (Map.Entry<BlockPos, Info> e : infos.entrySet()) {
            BlockPos t = e.getKey();
            int dx = Math.abs(t.getX() - pos.getX());
            int dz = Math.abs(t.getZ() - pos.getZ());
            int dy = Math.abs(t.getY() - pos.getY());
            if (dx <= RANGE + 1 && dz <= RANGE + 1 && dy <= RANGE + 1) {
                e.getValue().stale = true;
            }
            // Ausgaenge koennen sich auch weiter weg aendern - aber nicht am anderen Ende einer grossen Farm. Frueher
            // wurden bei jedem Block ALLE Ausgaenge neu gesucht: bei grossen Farmen hing jeder Block dann 2 s am Waechter.
            if (dx <= EXIT_RANGE && dz <= EXIT_RANGE && dy <= EXIT_RANGE / 2) {
                e.getValue().exits.clear();
            }
        }
    }

    /** Ein Block ist fertig: seine Liste wird nicht mehr gebraucht. */
    public void done(BlockPos pos) {
        infos.remove(pos);
    }

    /** Der Nachbar, den t zum Anklicken braucht (laut letzter Rechnung), sonst null. */
    public BlockPos neededNeighbor(BlockPos t) {
        Info info = infos.get(t);
        return info == null ? null : info.assumed;
    }

    /** Wie viele Standplaetze t hat (bekannt), -1 = noch nicht berechnet. */
    public int knownStands(BuildPlan.Target t) {
        Info info = infos.get(t.pos());
        return info == null || info.stale ? -1 : info.stands.size();
    }

    /** In freier Zeit Listen fuer offene Bloecke nahe pos vorberechnen (naechste zuerst). */
    public void prefetch(ClientLevel level, LocalPlayer player, BlockPos near, int count) {
        enter();
        try {
            prefetchTimed(level, player, near, count);
        } finally {
            leave();
        }
    }

    private void prefetchTimed(ClientLevel level, LocalPlayer player, BlockPos near, int count) {
        List<BuildPlan.Target> list = new ArrayList<>();
        for (Collection<BuildPlan.Target> group : List.of(host.pendingNear(near, 12), host.fluidTasks())) {
            for (BuildPlan.Target t : group) {
                if (t.need() != null && t.pos().distManhattan(near) <= 12 && !host.ignored(t) && host.protect(t)) {
                    Info info = infos.get(t.pos());
                    if (info == null || info.stale) {
                        list.add(t);
                    }
                }
            }
        }
        list.sort(Comparator.comparingInt(t -> t.pos().distManhattan(near)));
        for (int i = 0; i < Math.min(count, list.size()) && !outOfTime(); i++) {
            info(level, player, list.get(i));
        }
    }

    // ---------------------------------------------------------------- Die Pruefung

    /**
     * Wuerde ein Block x (Zustand state) einem offenen Block alle Standplaetze nehmen? Liefert diesen Block, null =
     * unbedenklich, {@link #UNDECIDED} = Zeitbudget aufgebraucht (im naechsten Tick weiterrechnen).
     */
    public BuildPlan.Target cutsOff(ClientLevel level, LocalPlayer player, BlockPos x, BlockState state) {
        enter();
        try {
            return cutsOffTimed(level, player, x, state);
        } finally {
            leave();
        }
    }

    private BuildPlan.Target cutsOffTimed(ClientLevel level, LocalPlayer player, BlockPos x, BlockState state) {
        List<BuildPlan.Target> near = new ArrayList<>();
        for (Collection<BuildPlan.Target> group : List.of(host.pendingNear(x, RANGE + 4), host.fluidTasks())) {
            for (BuildPlan.Target t : group) {
                BlockPos p = t.pos();
                // Standplaetze liegen 3 unter bis 2 ueber T (Koerper bis 4 darueber) - nur dort kann X etwas aendern
                if (Math.abs(p.getX() - x.getX()) > RANGE || Math.abs(p.getZ() - x.getZ()) > RANGE
                        || p.getY() - x.getY() < -4 || p.getY() - x.getY() > 3) {
                    continue;
                }
                if (p.equals(x) || t.need() == null || host.ignored(t) || !host.protect(t)) {
                    continue;
                }
                near.add(t);
            }
        }
        near.sort(Comparator.comparingInt(t -> t.pos().distManhattan(x)));
        Map<BlockPos, BlockState> withX = Map.of(x.immutable(), Placer.placedState(state));
        int computed = 0;
        for (BuildPlan.Target t : near) {
            Info info = infos.get(t.pos());
            if (info == null || info.stale) {
                if (computed >= MAX_NEW_INFOS) {
                    continue; // Zeit sparen: weiter weg und noch nie gerechnet - gilt als unbedenklich
                }
                if (outOfTime()) {
                    return UNDECIDED;
                }
                info = info(level, player, t);
                computed++;
            }
            if (info.stands.isEmpty() || x.equals(info.assumed)) {
                continue; // nichts zu schuetzen bzw. X ist genau der Nachbar, den T zum Anklicken braucht
            }
            boolean adjacent = t.pos().distManhattan(x) <= 1;
            if (info.stands.size() >= PLENTY && !adjacent) {
                continue;
            }
            Boolean keeps = survives(level, player, t, info, x, state, withX, adjacent);
            if (keeps == null) {
                return UNDECIDED;
            }
            if (!keeps) {
                return t;
            }
        }
        return null;
    }

    /** Bleibt t mit Block x ein brauchbarer Standplatz, von dem man wieder wegkommt? null = keine Zeit mehr. */
    private Boolean survives(ClientLevel level, LocalPlayer player, BuildPlan.Target t, Info info, BlockPos x,
                             BlockState state, Map<BlockPos, BlockState> withX, boolean adjacent) {
        boolean anyLeavable = false;
        boolean solid = !state.getCollisionShape(level, x).isEmpty();
        Map<BlockPos, BlockState> overlay = new HashMap<>(info.hypothesis);
        overlay.putAll(withX);
        for (Stand stand : info.stands) {
            BlockPos s = stand.feet();
            Boolean exit = info.exits.get(s);
            if (exit == null) {
                if (outOfTime()) {
                    return null;
                }
                exit = WorldOverlay.with(info.hypothesis, () -> host.leavable(s));
                info.exits.put(s, exit);
            }
            anyLeavable |= exit;
            if (!exit) {
                continue;
            }
            if (solid && (x.equals(s) || Placer.intersectsPlayer(level, s, x))) {
                continue; // X steht genau dort, wo man stehen muesste (bzw. der Kopf waere)
            }
            boolean touched = adjacent || x.equals(s) || x.equals(s.below()) || stand.ray().contains(x);
            if (touched) {
                if (outOfTime()) {
                    return null;
                }
                // X liegt im Blick (oder ist der Boden): genau nachrechnen - evtl. geht es mit anderem Klickpunkt
                if (!WorldOverlay.with(overlay, () -> validStand(level, player, t, s) != null)) {
                    continue;
                }
            }
            // Kommt man mit X noch von dort weg? X in der Naehe kann den einzigen Ausgang zumachen (Wand um eine Grube)
            if (solid && s.distManhattan(x) <= 4) {
                if (outOfTime()) {
                    return null;
                }
                if (!WorldOverlay.with(overlay, () -> host.leavable(s))) {
                    continue;
                }
            }
            return true;
        }
        // Hatte t ueberhaupt einen Platz mit Ausgang? Sonst ist da nichts zu schuetzen.
        return !anyLeavable;
    }

    /**
     * Laesst sich t in der aktuellen (evtl. gedachten) Welt von einem Platz mit Ausgang aus setzen? Ohne Cache - fuer
     * einmalige Was-waere-wenn-Fragen ("kann ich den Block, den ich gleich abbaue, danach wieder setzen?").
     */
    public boolean placeable(ClientLevel level, LocalPlayer player, BuildPlan.Target t) {
        List<Stand> stands = WorldOverlay.with(base(level, t), () -> searchStands(level, player, t));
        int checks = 0;
        for (Stand stand : stands) {
            if (++checks > 6) {
                break;
            }
            if (host.leavable(stand.feet())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Standplaetze berechnen

    private Info info(ClientLevel level, LocalPlayer player, BuildPlan.Target t) {
        Map<BlockPos, BlockState> base = base(level, t);
        List<Stand> stands = WorldOverlay.with(base, () -> searchStands(level, player, t));
        Info info = new Info(stands, base, null);
        if (stands.isEmpty()) {
            // Noch kein Nachbar zum Dagegenklicken: Je offenen Nachbarn laut Vorlage rechnen, als stuende NUR er schon
            // (einer reicht zum Anklicken) - der mit den meisten Standplaetzen zaehlt. Alle auf einmal anzunehmen waere
            // zu pessimistisch: sie wuerden Plaetze und Ausgaenge zustellen, die es zum richtigen Zeitpunkt noch gibt.
            // Hoechstens zwei Annahmen (unten zuerst) und fertig, sobald eine genug Plaetze hat - jede Annahme ist eine
            // komplette Standplatz-Suche, bei Bloecken ohne Nachbarn waren das bis zu sechs je Block
            List<Map.Entry<BlockPos, BlockState>> neighbors = new ArrayList<>(assumedNeighbors(level, t).entrySet());
            neighbors.sort(Comparator.comparingInt(e -> e.getKey().getY() - t.pos().getY()));
            int tried = 0;
            for (Map.Entry<BlockPos, BlockState> neighbor : neighbors) {
                if (tried++ >= 2 || info.stands.size() >= PLENTY) {
                    break;
                }
                Map<BlockPos, BlockState> hypothesis = new HashMap<>(base);
                hypothesis.put(neighbor.getKey(), neighbor.getValue());
                List<Stand> found = WorldOverlay.with(hypothesis, () -> searchStands(level, player, t));
                if (found.size() > info.stands.size()) {
                    info = new Info(found, hypothesis, neighbor.getKey());
                }
            }
        }
        infos.put(t.pos(), info);
        return info;
    }

    /** Steht auf dem Zielplatz noch etwas Falsches (wird vorher abgebaut), gilt er als leer. */
    private static Map<BlockPos, BlockState> base(ClientLevel level, BuildPlan.Target t) {
        BlockState have = level.getBlockState(t.pos());
        if (!have.isAir() && !have.canBeReplaced() && !have.is(t.state().getBlock())) {
            return Map.of(t.pos(), Blocks.AIR.defaultBlockState());
        }
        return Map.of();
    }

    /** Offene Nachbarn laut Vorlage, die etwas zum Dagegenklicken waeren. */
    private Map<BlockPos, BlockState> assumedNeighbors(ClientLevel level, BuildPlan.Target t) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (Direction d : Direction.values()) {
            BlockPos n = t.pos().relative(d);
            BuildPlan.Target nt = host.pendingAt(n);
            if (nt == null || host.ignored(nt) || !level.getBlockState(n).canBeReplaced()
                    || nt.state().getShape(level, n).isEmpty()) {
                continue;
            }
            out.put(n.immutable(), Placer.placedState(nt.state()));
        }
        return out;
    }

    /**
     * Alle Standplaetze (hoechstens MAX_STANDS), von denen t in der aktuellen (gedachten) Welt geht. Versetzte/geduckte
     * Stellen (Ueber-Kopf-Bloecke) rechnet der Waechter bewusst nicht: bei vielen Kolben nach unten wurde er dafuer zu
     * langsam. Deren Nachbarn warten ueber eine billige feste Regel (FarmBot.overheadNeighborWaits).
     */
    private List<Stand> searchStands(ClientLevel level, LocalPlayer player, BuildPlan.Target t) {
        BlockPos pos = t.pos();
        Vec3 center = Vec3.atCenterOf(pos);
        double reach = Placer.reach(player) + 0.4D;
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    BlockPos s = pos.offset(dx, dy, dz);
                    if (host.avoid(s) || !Pathfinder.standable(level, s) || Placer.intersectsPlayer(level, s, pos)
                            || Placer.eyeAt(level, s).distanceToSqr(center) > reach * reach) {
                        continue;
                    }
                    candidates.add(s.immutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(s -> s.distSqr(pos)));
        List<Stand> out = new ArrayList<>();
        int planned = 0;
        for (BlockPos s : candidates) {
            if (planned++ >= MAX_PLANS || out.size() >= MAX_STANDS) {
                break;
            }
            Stand stand = validStand(level, player, t, s);
            if (stand != null) {
                out.add(stand);
            }
        }
        return out;
    }

    /** Geht t von s aus (Kopf frei, sichtbar, richtig ausgerichtet)? Wasser/Lava: Eimer-Klick. null = nein. */
    private Stand validStand(ClientLevel level, LocalPlayer player, BuildPlan.Target t, BlockPos s) {
        if (!Pathfinder.standable(level, s) || Placer.intersectsPlayer(level, s, t.pos())) {
            return null;
        }
        Vec3 eye = Placer.eyeAt(level, s);
        Placer.Aim aim = t.need().kind() == Materials.Kind.FLUID
                ? placer.planFluid(level, player, eye, t.pos(), t.waterlogged(), !t.state().is(Blocks.LAVA))
                : placer.planPlacement(level, player, eye, t.pos(), t.state(), new ItemStack(t.need().item()), false,
                false);
        return aim == null ? null : new Stand(s, Placer.rayCells(eye, aim.hit(), aim.clicked()));
    }
}
