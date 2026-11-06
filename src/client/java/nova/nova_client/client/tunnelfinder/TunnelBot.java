package nova.nova_client.client.tunnelfinder;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.farmbuilder.HumanAim;
import nova.nova_client.client.farmbuilder.Placer;
import nova.nova_client.client.farmbuilder.Tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graebt einen 1x2-Tunnel geradeaus in die Richtung, in die man beim Start schaut. Die Hoehe wird beim Start
 * automatisch erkannt (zwei ueber dem hoechsten Bedrock); ist man hoeher, geht es zuerst treppenartig nach unten.
 * Gezielt wird wie ein Mensch mit {@link HumanAim} (wie der Schematic Builder). Abgebaut wird wie mit gehaltener
 * linker Maustaste: alles im Fadenkreuz, das zum Tunnel gehoert - schon waehrend die Kamera hinschwenkt und beim
 * Laufen (Sprinten) auch der naechste Schritt.
 * <p>
 * Drill-Modus: Ein Drill baut 3x3 quer zur getroffenen Seite ab. Der Bot zielt dann von vorne auf den Kopf-Block
 * (raeumt Fuesse, Kopf und eine Reihe darueber in einem Schlag) und, falls noetig, von oben auf den Fuss-Block -
 * nie so, dass das 3x3 den Boden trifft.
 * <p>
 * Hindernisse fuehren NIE zum Stopp:
 * <ul>
 * <li>Wasser (optional): der Bot graebt einfach in eine andere Richtung weiter (die Seite mit dem freiesten Weg).</li>
 * <li>Lava, unzerstoerbare Bloecke, Fluessigkeit als Boden, tiefe Loecher: Umweg planen (seitlich, notfalls ein paar
 * Bloecke zurueck und dann seitlich), bis der Weg in der urspruenglichen Richtung wieder frei ist. Gibt es keinen
 * Umweg, wechselt er ebenfalls die Richtung.</li>
 * <li>Loecher im Boden: mit Fuellbloecken aus der Hotbar schliessen; geht das nicht, einfach weiterlaufen - flache
 * Loecher (bis {@value #MAX_DROP} Bloecke, kein Fallschaden) hinein und per Stufe wieder auf Tunnel-Hoehe.</li>
 * <li>Feststecken, Block nicht erreichbar/abbaubar: ebenfalls Umweg bzw. andere Richtung.</li>
 * </ul>
 * Aufgehoert wird nur bei einer gefundenen Base (im Modul), wenig Leben, Tod, Lava am eigenen Koerper, fehlendem
 * Werkzeug oder erreichter Maximal-Laenge.
 * <p>
 * {@link #tick} gibt einen Grund zurueck, sobald der Bot aufhoeren muss (sonst null).
 */
public final class TunnelBot {
    /** Bloecke, mit denen Loecher im Boden geschlossen werden. */
    private static final Set<Block> FILLERS = Set.of(Blocks.COBBLED_DEEPSLATE, Blocks.COBBLESTONE, Blocks.DEEPSLATE,
            Blocks.STONE, Blocks.TUFF, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE, Blocks.NETHERRACK,
            Blocks.DIRT, Blocks.BLACKSTONE);
    /** Nach so vielen Ticks ohne Fortschritt aufgeben. */
    private static final int STUCK_TICKS = 400;
    /** So viele Ticks darf ein einzelner Block brauchen (Deepslate mit Holzspitzhacke ~ 6 s). */
    private static final int MAX_MINE_TICKS = 300;
    /** So weit darf man seitlich von der Tunnelmitte weg sein, um geradeaus zu laufen. */
    private static final double CENTER_WALK = 0.15D;
    /** Umweg: so viele Bloecke seitlich wird nach einem freien Weg geradeaus gesucht. */
    private static final int DETOUR_SEARCH = 24;
    /** Umweg: so viele Bloecke darf er zuerst zurueckgehen, um seitlich ausweichen zu koennen. */
    private static final int DETOUR_BACK = 4;
    /** So viele Schritte in der Hauptrichtung muessen frei sein, bevor es wieder geradeaus geht. */
    private static final int CLEAR_AHEAD = 3;
    /** Insgesamt hoechstens so viele Bloecke Umweg am Stueck, danach wird die Richtung gewechselt. */
    private static final int MAX_DETOUR = 96;
    /** In so flache Loecher laeuft er einfach hinein (3 Bloecke Fallhoehe = kein Fallschaden). */
    private static final int MAX_DROP = 3;
    /** So weit wird beim Richtungswechsel jede Richtung auf einen freien Weg geprueft. */
    private static final int TURN_LOOKAHEAD = 12;
    /** So oft darf er an derselben Stelle die Richtung wechseln, bevor er eingeschlossen ist. */
    private static final int MAX_TURNS_HERE = 4;
    /** Ringsum Wasser: so lange (Ticks) wird Wasser ignoriert und einfach durchgegraben. */
    private static final int WATER_GRACE_TICKS = 200;
    private static final Direction[] ALL_DIRECTIONS = Direction.values();
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** Markiert einen Rueckgabewert als Hindernis (ausweichen statt aufhoeren), siehe {@link #blocked}. */
    private static final String BLOCKED = "!blocked:";

    private final Minecraft mc = Minecraft.getInstance();
    private final HumanAim aim = new HumanAim();

    private boolean running;
    /** Aktuelle Laufrichtung (beim Umweg seitlich oder zurueck). */
    private Direction heading = Direction.NORTH;
    /** Die Richtung, in die der Tunnel eigentlich geht (beim Start). */
    private Direction mainHeading = Direction.NORTH;
    /** Geplante Einzelschritte (zurueckgehen) vor dem seitlichen Ausweichen. */
    private final java.util.ArrayDeque<Direction> route = new java.util.ArrayDeque<>();
    private BlockPos routeFrom;
    /** Seitliche Richtung waehrend eines Umwegs (null = geradeaus). */
    private Direction detourDir;
    private int detourBlocks;
    private String detourReason = "";
    /** Richtungswechsel an derselben Stelle (gegen endloses Hin-und-her und Chat-Spam). */
    private BlockPos turnFeet;
    private int turnsHere;
    /** > 0: Wasser wird gerade ignoriert (ringsum Wasser - durchgraben statt ewig drehen). */
    private int waterGrace;
    private Input input = Input.EMPTY;
    private String status = "";

    // Abbauen
    private boolean mining;
    private BlockPos miningPos;
    private int miningTicks;
    private int settledTicks;
    private int aimTicks;
    private BlockPos aimPos;
    private int mineRestarts;
    private int placeCooldown;
    private int placeAttempts;

    // Fortschritt
    private BlockPos lastFeet;
    private int idleTicks;
    private int blocksMined;
    private int distance;

    // Hoehe (wird beim Start automatisch erkannt)
    private int targetY = -58;
    private int bedrockTop = Integer.MIN_VALUE;

    // Drill: Spitzhacke, die 3x3 auf einmal abbaut (Server-Item)
    private int drillSlot = -1;
    private Item drillItem;

    // Einstellungen (setzt das Modul vor dem Start)
    public boolean drill;
    public float minHealth = 10.0F;
    public boolean bridge = true;
    /** Wasser meiden (ausweichen); aus = durch Wasser hindurch. */
    public boolean stopOnWater = true;
    public int maxLength;
    /** Feste Tunnel-Hoehe (Fuesse), Integer.MIN_VALUE = automatisch (zwei ueber Bedrock). */
    public int fixedY = Integer.MIN_VALUE;

    public boolean running() {
        return running;
    }

    public String status() {
        return status;
    }

    public Direction heading() {
        return mainHeading;
    }

    public int distance() {
        return distance;
    }

    public int blocksMined() {
        return blocksMined;
    }

    /** Erkannte Tunnel-Hoehe (Fuesse). */
    public int targetY() {
        return targetY;
    }

    /** Hoechster Bedrock-Block in der Naehe (Integer.MIN_VALUE = keiner gefunden). */
    public int bedrockTop() {
        return bedrockTop;
    }

    /** Eingabe fuer KeyboardInputMixin (null = Spieler steuert selbst). */
    public Input inputOverride() {
        return running ? input : null;
    }

    /** Startet den Bot. Rueckgabe: Grund, warum es nicht geht (sonst null). */
    public String start(LocalPlayer player, ClientLevel level, float aimSpeed) {
        if (drill && !findDrill(player)) {
            return "§cno drill found - put it in your hotbar (name with \"Drill\") or hold it when starting";
        }
        detectTunnelY(level, player.blockPosition());
        if (fixedY != Integer.MIN_VALUE) {
            targetY = fixedY;
        }
        running = true;
        heading = player.getDirection();
        mainHeading = heading;
        route.clear();
        routeFrom = null;
        detourDir = null;
        detourBlocks = 0;
        turnFeet = null;
        turnsHere = 0;
        waterGrace = 0;
        input = Input.EMPTY;
        aim.reset();
        aim.setSpeed(aimSpeed);
        mining = false;
        miningPos = null;
        aimPos = null;
        lastFeet = player.blockPosition();
        idleTicks = 0;
        blocksMined = 0;
        distance = 0;
        placeCooldown = 0;
        status = "starting";
        return null;
    }

    /**
     * Tunnel-Hoehe automatisch: zwei Bloecke ueber dem hoechsten Bedrock der Umgebung (Vanilla: Bedrock bis Y -60,
     * Tunnel auf Y -58). So sind Boden und Waende nie Bedrock - auch nicht beim 3x3 des Drills, der bis Fuesse+2 reicht.
     */
    private void detectTunnelY(ClientLevel level, BlockPos around) {
        int minY = level.getMinY();
        int top = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -24; dx <= 24; dx += 2) {
            for (int dz = -24; dz <= 24; dz += 2) {
                int x = around.getX() + dx;
                int z = around.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= minY + 10; y++) {
                    if (level.getBlockState(pos.set(x, y, z)).is(Blocks.BEDROCK)) {
                        top = Math.max(top, y);
                    }
                }
            }
        }
        bedrockTop = top;
        targetY = top == Integer.MIN_VALUE ? minY + 6 : top + 2;
    }

    private static boolean isDrillName(ItemStack stack) {
        String name = stack.getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
        return name.contains("drill") || name.contains("bohrer");
    }

    /** Drill in der Hotbar suchen: Name mit "Drill", sonst das Werkzeug in der Hand. */
    private boolean findDrill(LocalPlayer player) {
        var inv = player.getInventory();
        drillSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (!inv.getItem(i).isEmpty() && isDrillName(inv.getItem(i))) {
                drillSlot = i;
                break;
            }
        }
        if (drillSlot < 0 && inv.getSelectedItem().getDestroySpeed(Blocks.DEEPSLATE.defaultBlockState()) > 1.0F) {
            drillSlot = inv.getSelectedSlot();
        }
        drillItem = drillSlot < 0 ? null : inv.getItem(drillSlot).getItem();
        return drillSlot >= 0;
    }

    public void stop() {
        releaseMining();
        running = false;
        input = Input.EMPTY;
        aim.reset();
        status = "stopped";
    }

    /** Pro Frame aus dem HUD: Kamera fluessig bewegen. */
    public void frame() {
        if (running && mc.gui.screen() == null) {
            aim.update(mc);
        }
    }

    // ================================================================ Tick

    /** Ein Schritt. Rueckgabe: Grund zum Aufhoeren, sonst null. Hindernisse fuehren nie zum Aufhoeren. */
    public String tick() {
        String reason = tickInner();
        if (reason != null && reason.startsWith(BLOCKED)) {
            return reroute(reason.substring(BLOCKED.length()));
        }
        return reason;
    }

    private String tickInner() {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!running || player == null || level == null || mc.gameMode == null) {
            return null;
        }
        // Menue offen (Chat, Inventar, ...): stehen bleiben, nichts tun
        if (mc.gui.screen() != null) {
            releaseMining();
            input = Input.EMPTY;
            status = "paused (menu open)";
            return null;
        }
        aim.update(mc);
        if (waterGrace > 0) {
            waterGrace--;
        }

        if (player.isDeadOrDying()) {
            return "§cyou died";
        }
        if (player.isInLava()) {
            return "§cstanding in lava!";
        }
        if (player.getHealth() < minHealth) {
            return "§clow health (" + Math.round(player.getHealth()) + " HP)";
        }

        BlockPos feet = player.blockPosition();
        if (!feet.equals(lastFeet)) {
            if (lastFeet != null && feet.getX() * mainHeading.getStepX() + feet.getZ() * mainHeading.getStepZ()
                    > lastFeet.getX() * mainHeading.getStepX() + lastFeet.getZ() * mainHeading.getStepZ()) {
                distance++;
            }
            if (lastFeet != null && (detourDir != null || !route.isEmpty())) {
                detourBlocks++;
            }
            lastFeet = feet;
            idleTicks = 0;
        }
        if (++idleTicks > STUCK_TICKS) {
            idleTicks = 0;
            return blocked("being stuck");
        }
        if (maxLength > 0 && distance >= maxLength) {
            return "§areached the max length (" + maxLength + " blocks)";
        }
        // Waehrend des Fallens (Treppe runter, Loch) nichts tun
        if (!player.onGround()) {
            releaseMining();
            input = Input.EMPTY;
            return null;
        }

        boolean descending = feet.getY() > targetY;
        // Richtung: geplanter Umweg (erst zurueck, dann seitlich), sonst geradeaus
        if (!descending) {
            steer(player, level, feet);
        }
        status = (descending ? "going down to Y " + targetY + " (now " + feet.getY() + ")"
                : feet.getY() < targetY ? "climbing back to Y " + targetY + " (now " + feet.getY() + ")"
                : detourDir != null || !route.isEmpty() ? "going around " + detourReason + " (" + heading.getName() + ")"
                : "tunneling " + dirName()) + (drill ? " (drill)" : "");

        // Dieser Schritt und der danach (der wird im Laufen schon vorab abgebaut)
        Step cur = step(level, feet);
        Step look = step(level, cur.dest());

        // Fluessigkeiten im und neben dem Tunnel, auch einen Schritt voraus
        List<BlockPos> watch = new ArrayList<>();
        for (Step s : List.of(cur, look)) {
            watch.addAll(watchCells(s.clear(), s.next(), heading));
        }
        String danger = fluidDanger(level, watch);
        if (danger == null && !descending) {
            danger = hazard(level, player, feet, heading);
        }
        if ("unloaded chunks".equals(danger)) {
            // Vorne ist die Welt noch nicht geladen: warten statt ausweichen
            releaseMining();
            input = Input.EMPTY;
            status = "waiting for chunks to load";
            return null;
        }
        if (danger != null) {
            return blocked(danger); // nicht anhalten: Umweg oder andere Richtung
        }

        // Was "Linksklick halten" abbauen darf: beide Schritte (Drill: mit der Seite, die er treffen muss)
        Map<BlockPos, Direction> minable = new HashMap<>();
        for (Step s : List.of(cur, look)) {
            for (BlockPos pos : s.clear()) {
                minable.put(pos, drill ? drillFace(pos, s) : null);
            }
        }

        // 1. Bloecke im Weg abbauen
        BlockPos target = firstSolid(level, cur);
        if (target != null) {
            BlockState state = level.getBlockState(target);
            if (state.getDestroySpeed(level, target) < 0.0F) {
                return blocked("unbreakable " + state.getBlock().getName().getString());
            }
            // W bleibt gedrueckt, waehrend abgebaut wird: man drueckt gegen den Block und laeuft sofort weiter,
            // sobald er weg ist. Einzige Ausnahme: beim Runtergehen nicht ueber das Loch laufen, bevor die Stufe
            // darunter frei ist (sonst ginge es nie nach unten).
            boolean keepWalking = !(cur.descending() && target.equals(cur.next().below()));
            input = centerInput(player, keepWalking);
            String problem = aimAt(player, level, target, minable.get(target));
            return problem != null ? problem : hold(player, level, minable);
        }

        // 2. Boden pruefen
        BlockState floorState = level.getBlockState(cur.floor());
        if (!BaseBlocks.isSolid(floorState)) {
            if (!floorState.getFluidState().isEmpty()) {
                return blocked("liquid below the tunnel");
            }
            if (!floorState.isAir() && !floorState.canBeReplaced()) {
                // Schiene, Teppich o.Ae. statt Boden: erst weg damit (ohne Drill), dann auffuellen
                minable.put(cur.floor(), null);
                input = centerInput(player, false);
                String problem = aimAt(player, level, cur.floor(), null);
                return problem != null ? problem : hold(player, level, minable);
            }
            // Loch: zuschuetten, wenn moeglich - sonst einfach weiterlaufen
            if (bridge && hasFiller(player)) {
                return placeFloor(player, level, cur);
            }
            return stepInto(player, level, cur);
        }
        placeAttempts = 0;

        // 3. Weiterlaufen (sprinten) und dabei schon den naechsten Schritt abbauen; Stufe hoch: springen
        walk(player, level, look);
        if (cur.ascending() && input.forward()) {
            input = new Input(true, false, input.left(), input.right(), true, false, false);
        }
        return hold(player, level, minable);
    }

    // ================================================================ Ausweichen

    /**
     * Laufrichtung fuer diesen Tick festlegen: erst die geplanten Schritte zurueck, dann seitlich, bis es in der
     * Hauptrichtung wieder CLEAR_AHEAD Schritte frei ist - dann wieder geradeaus. Dauert der Umweg zu lange, wird die
     * Richtung gewechselt.
     */
    private void steer(LocalPlayer player, ClientLevel level, BlockPos feet) {
        if (!route.isEmpty()) {
            if (routeFrom == null) {
                routeFrom = feet;
            }
            if (!feet.equals(routeFrom)) {
                route.pollFirst(); // einen Block geschafft
                routeFrom = feet;
            }
        }
        if (!route.isEmpty()) {
            heading = route.peekFirst();
            return;
        }
        routeFrom = null;
        if (detourDir == null) {
            heading = mainHeading;
            return;
        }
        if (detourBlocks > MAX_DETOUR) {
            turn(player, level, feet, "no way around " + detourReason); // er bewegt sich, also nie eingeschlossen
            return;
        }
        if (centered(player) && clearAhead(level, player, feet, mainHeading, CLEAR_AHEAD)) {
            NovaNotifyBridge.chat("§7Around " + detourReason + " - straight " + mainHeading.getName() + " again.");
            detourDir = null;
            detourBlocks = 0;
            heading = mainHeading;
            return;
        }
        heading = detourDir;
    }

    /** Steht man mittig im Block (nur dann wird abgebogen - sonst haengt man an der Ecke)? */
    private static boolean centered(LocalPlayer player) {
        return Math.abs(player.getX() - (Mth.floor(player.getX()) + 0.5D)) < 0.25D
                && Math.abs(player.getZ() - (Mth.floor(player.getZ()) + 0.5D)) < 0.25D;
    }

    /**
     * Ein Hindernis voraus: Umweg suchen. Fuer 0..DETOUR_BACK Bloecke zurueck (der Tunnel dort ist schon frei) und beide
     * Seiten: so weit seitlich gehen, wie es sicher ist, bis in der Hauptrichtung CLEAR_AHEAD Schritte frei sind. Der
     * kuerzeste Umweg gewinnt. Rueckgabe: Grund zum Aufhoeren (es geht nirgends weiter), sonst null.
     */
    private String planDetour(LocalPlayer player, ClientLevel level, BlockPos feet, String why) {
        String what = clean(why);
        Direction[] sides = detourDir != null
                // schon seitlich unterwegs und dort geht es nicht weiter: andere Seite (zurueck und darueber hinaus)
                ? new Direction[]{detourDir.getOpposite(), detourDir}
                : new Direction[]{mainHeading.getClockWise(), mainHeading.getCounterClockWise()};
        int bestCost = Integer.MAX_VALUE;
        int bestBack = 0;
        Direction bestSide = null;
        Direction fallbackSide = null;
        int fallbackRun = 0;
        for (int back = 0; back <= DETOUR_BACK; back++) {
            BlockPos from = feet.relative(mainHeading.getOpposite(), back);
            if (back > 0 && !walkable(level, from)) {
                break; // dort ist kein freier Tunnel (z.B. ganz am Anfang)
            }
            for (Direction side : sides) {
                BlockPos p = from;
                for (int k = 1; k <= DETOUR_SEARCH; k++) {
                    if (hazard(level, player, p, side) != null) {
                        if (back == 0 && k - 1 > fallbackRun) {
                            fallbackRun = k - 1;
                            fallbackSide = side;
                        }
                        break;
                    }
                    p = p.relative(side);
                    if (clearAhead(level, player, p, mainHeading, CLEAR_AHEAD)) {
                        int cost = back * 2 + k;
                        if (cost < bestCost) {
                            bestCost = cost;
                            bestBack = back;
                            bestSide = side;
                        }
                        break;
                    }
                    if (back == 0 && k > fallbackRun) {
                        fallbackRun = k;
                        fallbackSide = side;
                    }
                }
            }
        }
        if (bestSide == null && fallbackSide != null && fallbackRun >= 2) {
            bestSide = fallbackSide; // noch kein freier Weg in Sicht - seitlich weiter und unterwegs neu pruefen
        }
        if (bestSide == null) {
            return "§c" + what + " ahead and no way around it";
        }
        boolean first = detourDir == null;
        detourReason = what;
        detourDir = bestSide;
        route.clear();
        for (int i = 0; i < bestBack; i++) {
            route.add(mainHeading.getOpposite());
        }
        routeFrom = null;
        heading = route.isEmpty() ? bestSide : route.peekFirst();
        if (first) {
            detourBlocks = 0;
            NovaNotifyBridge.chat("§e" + what + " ahead §7- going around " + bestSide.getName()
                    + (bestBack > 0 ? " §8(" + bestBack + " back first)" : "") + "§7.");
        }
        return null;
    }

    /** Rueckgabewert fuer ein Hindernis: {@link #tick} weicht dann aus, statt aufzuhoeren. */
    private static String blocked(String what) {
        return BLOCKED + what;
    }

    /** Hindernis-Text ohne Farbcodes und Koordinaten. */
    private static String clean(String why) {
        return why.replaceAll("§.", "").replaceAll(" at -?\\d+ -?\\d+ -?\\d+", "").replace(" ahead", "").trim();
    }

    /**
     * Einem Hindernis ausweichen, statt aufzuhoeren: Wasser (und alles auf der Treppe nach unten) -> in eine andere
     * Richtung weitergraben; sonst Umweg, und wenn es keinen gibt, ebenfalls andere Richtung. Rueckgabe: Grund zum
     * Aufhoeren - nur wenn er ringsum von Lava/unzerstoerbaren Bloecken eingeschlossen ist, sonst null.
     */
    private String reroute(String why) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        releaseMining();
        input = Input.EMPTY;
        aimPos = null;
        aimTicks = 0;
        miningPos = null;
        mineRestarts = 0;
        placeAttempts = 0;
        if (player == null || level == null) {
            return null;
        }
        BlockPos feet = player.blockPosition();
        boolean water = why.contains("water");
        if (water || feet.getY() > targetY || planDetour(player, level, feet, why) != null) {
            return turn(player, level, feet, why);
        }
        return null;
    }

    /**
     * Hauptrichtung wechseln: die Richtung mit dem laengsten freien Weg (lieber seitlich als zurueck durch den eigenen
     * Tunnel). Ist nirgends etwas frei, geht es dorthin zurueck, wo man herkam. Dreht er sich an derselben Stelle
     * immer wieder: bei Wasser ringsum wird eine Weile einfach durchgegraben; bei Lava/unzerstoerbaren Bloecken
     * ringsum hoert er auf (Rueckgabe: Grund), sonst null.
     */
    private String turn(LocalPlayer player, ClientLevel level, BlockPos feet, String why) {
        if (feet.equals(turnFeet)) {
            turnsHere++;
        } else {
            turnFeet = feet;
            turnsHere = 1;
        }
        if (turnsHere > MAX_TURNS_HERE) {
            turnsHere = 0;
            if (why.contains("water") && stopOnWater) {
                waterGrace = WATER_GRACE_TICKS;
                NovaNotifyBridge.chat("§eWater all around §7- digging through it.");
                return null;
            }
            return "§cboxed in - " + clean(why) + " on every side";
        }
        Direction back = mainHeading.getOpposite();
        Direction best = null;
        int bestRun = 0;
        int bestScore = Integer.MIN_VALUE;
        for (Direction dir : HORIZONTAL) {
            if (dir == heading) {
                continue; // dort ist ja das Hindernis
            }
            int run = clearRun(level, player, feet, dir, TURN_LOOKAHEAD);
            int score = run * 10 - (dir == back ? 55 : 0);
            if (score > bestScore) {
                bestScore = score;
                bestRun = run;
                best = dir;
            }
        }
        if (best == null || bestRun == 0) {
            best = heading.getOpposite();
        }
        mainHeading = best;
        heading = best;
        detourDir = null;
        detourBlocks = 0;
        route.clear();
        routeFrom = null;
        if (turnsHere == 1) {
            NovaNotifyBridge.chat("§e" + clean(why) + " §7- now tunneling §f" + dirName() + "§7.");
        }
        return null;
    }

    /** Wie viele Schritte (hoechstens max) von from aus in Richtung dir sicher sind. */
    private int clearRun(ClientLevel level, LocalPlayer player, BlockPos from, Direction dir, int max) {
        BlockPos p = from;
        for (int i = 0; i < max; i++) {
            if (hazard(level, player, p, dir) != null) {
                return i;
            }
            p = p.relative(dir);
        }
        return max;
    }

    /**
     * Wie tief man faellt, wenn der Boden bei floor fehlt (1 = ein Block). Fluessigkeit unten oder tiefer als
     * {@link #MAX_DROP} = zu tief (MAX_DROP + 1).
     */
    private static int holeDepth(ClientLevel level, BlockPos floor) {
        BlockPos.MutableBlockPos p = floor.mutable();
        for (int depth = 1; depth <= MAX_DROP + 1; depth++) {
            BlockState state = level.getBlockState(p);
            if (!state.getFluidState().isEmpty()) {
                return MAX_DROP + 1;
            }
            if (BaseBlocks.isSolid(state)) {
                return depth - 1;
            }
            p.move(Direction.DOWN);
        }
        return MAX_DROP + 1;
    }

    /**
     * Loch, das nicht zugeschuettet werden kann: ist es flach, einfach hineinlaufen und weiter (danach geht es per
     * Stufe wieder auf Tunnel-Hoehe). Ein tiefes Loch wird umgangen.
     */
    private String stepInto(LocalPlayer player, ClientLevel level, Step cur) {
        releaseMining();
        // Auch mehrere flache Loecher hintereinander nicht tiefer als MAX_DROP unter die Tunnel-Hoehe
        int depth = holeDepth(level, cur.floor());
        int landing = cur.floor().getY() - depth + 1;
        if (depth > MAX_DROP || targetY - landing > MAX_DROP) {
            return blocked("a deep hole");
        }
        aimPos = null;
        aim.follow(heading.toYRot(), 20.0F);
        input = centerInput(player, true);
        return null;
    }

    /** Ist der Weg von from aus in Richtung dir die naechsten n Schritte sicher? */
    private boolean clearAhead(ClientLevel level, LocalPlayer player, BlockPos from, Direction dir, int n) {
        BlockPos p = from;
        for (int i = 0; i < n; i++) {
            if (hazard(level, player, p, dir) != null) {
                return false;
            }
            p = p.relative(dir);
        }
        return true;
    }

    /** Kann man in feet stehen (Tunnel schon frei, Boden da)? */
    private static boolean walkable(ClientLevel level, BlockPos feet) {
        return passable(level, feet, level.getBlockState(feet)) && passable(level, feet.above(), level.getBlockState(feet.above()))
                && BaseBlocks.isSolid(level.getBlockState(feet.below()));
    }

    /**
     * Warum ein Schritt von feet in Richtung dir (auf gleicher Hoehe) nicht geht - null = sicher. Fluessigkeit in oder
     * neben den Feldern, die frei werden (auch das 3x3 des Drills), ein unzerstoerbarer Block, Fluessigkeit als Boden,
     * ein tiefes Loch ohne Fuellbloecke, nicht geladene Chunks. Flache Loecher sind kein Hindernis.
     */
    private String hazard(ClientLevel level, LocalPlayer player, BlockPos feet, Direction dir) {
        BlockPos next = feet.relative(dir);
        if (!level.hasChunk(next.getX() >> 4, next.getZ() >> 4)) {
            return "unloaded chunks";
        }
        List<BlockPos> clear = List.of(next.above(), next);
        String fluid = fluidDanger(level, watchCells(clear, next, dir));
        if (fluid != null) {
            return fluid;
        }
        for (BlockPos c : clear) {
            BlockState state = level.getBlockState(c);
            if (!passable(level, c, state) && state.getDestroySpeed(level, c) < 0.0F) {
                return "unbreakable " + state.getBlock().getName().getString();
            }
        }
        BlockState floor = level.getBlockState(next.below());
        if (!floor.getFluidState().isEmpty()) {
            return "liquid below the tunnel";
        }
        if (!BaseBlocks.isSolid(floor) && (floor.isAir() || floor.canBeReplaced()) && (!bridge || !hasFiller(player))
                && holeDepth(level, next.below()) > MAX_DROP) {
            return "a deep hole";
        }
        return null;
    }

    /** Felder, die frei werden: die Tunnel-Felder, beim Drill dazu dessen 3x3 (Seiten und die Reihe darueber). */
    private List<BlockPos> watchCells(List<BlockPos> clear, BlockPos next, Direction dir) {
        List<BlockPos> watch = new ArrayList<>(clear);
        if (drill) {
            Direction side = dir.getClockWise();
            List<BlockPos> center = new ArrayList<>(clear);
            center.add(next.above(2));
            for (BlockPos c : center) {
                watch.add(c);
                watch.add(c.relative(side));
                watch.add(c.relative(side.getOpposite()));
            }
        }
        return watch;
    }

    private static boolean hasFiller(LocalPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() instanceof BlockItem item && FILLERS.contains(item.getBlock())) {
                return true;
            }
        }
        return false;
    }

    /** Ein Schritt nach vorne: was frei sein muss, wo der Boden ist und wo man danach steht. */
    private record Step(BlockPos next, List<BlockPos> clear, BlockPos floor, BlockPos dest, boolean descending,
                        boolean ascending) {
    }

    private Step step(ClientLevel level, BlockPos feet) {
        BlockPos next = feet.relative(heading);
        if (feet.getY() > targetY) {
            // Treppe runter
            return new Step(next, List.of(next.above(), next, next.below()), next.below(2), next.below(), true, false);
        }
        if (feet.getY() < targetY && BaseBlocks.isSolid(level.getBlockState(next))) {
            // Unter Tunnel-Hoehe (z.B. in ein Loch gelaufen): Stufe hoch auf den Block vorne. Frei sein muessen der
            // Platz darueber und ueber dem eigenen Kopf (zum Springen).
            return new Step(next, List.of(feet.above(2), next.above(2), next.above()), next, next.above(), false, true);
        }
        return new Step(next, List.of(next.above(), next), next.below(), next, false, false);
    }

    /**
     * Erster Block, der im Weg ist. Spitzhacke: oben zuerst (fallender Kies landet so im naechsten Durchgang).
     * Drill: zuerst der, dessen 3x3 am meisten frei macht (Kopf; beim Runtergehen die Fuesse).
     */
    private BlockPos firstSolid(ClientLevel level, Step s) {
        BlockPos next = s.next();
        List<BlockPos> order = !drill ? s.clear()
                : s.descending() ? List.of(next, next.above(), next.below())
                : s.ascending() ? List.of(next.above(2), next.above(), next.relative(heading.getOpposite()).above(2))
                : List.of(next.above(), next);
        for (BlockPos pos : order) {
            if (!passable(level, pos, level.getBlockState(pos))) {
                return pos;
            }
        }
        return null;
    }

    private String dirName() {
        return mainHeading.getName().toUpperCase(java.util.Locale.ROOT);
    }

    /** Chat-Meldungen des Bots (eigene Klasse, damit TunnelBot ohne das Menue-Paket testbar bleibt). */
    private static final class NovaNotifyBridge {
        static void chat(String text) {
            nova.nova_client.client.NovaNotify.chat("Tunnel Base Finder", text);
        }
    }

    /** Kann man durch den Block laufen, ohne ihn abzubauen? (Luft, Fackeln, Gras - aber kein Spinnennetz o.Ae.) */
    private static boolean passable(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true; // Wasser: darf nur ohne stopOnWater vorkommen
        }
        Block block = state.getBlock();
        if (block == Blocks.COBWEB || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POWDER_SNOW
                || block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.WITHER_ROSE) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** Lava (oder Wasser) in einem der Felder oder direkt daneben? */
    private String fluidDanger(ClientLevel level, List<BlockPos> positions) {
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (BlockPos pos : positions) {
            for (int i = -1; i < ALL_DIRECTIONS.length; i++) {
                if (i < 0) {
                    n.set(pos);
                } else {
                    n.setWithOffset(pos, ALL_DIRECTIONS[i]);
                }
                FluidState fluid = level.getFluidState(n);
                if (fluid.isEmpty()) {
                    continue;
                }
                if (fluid.is(FluidTags.LAVA)) {
                    return "§clava ahead at " + n.getX() + " " + n.getY() + " " + n.getZ();
                }
                if (stopOnWater && waterGrace <= 0 && fluid.is(FluidTags.WATER)) {
                    return "§ewater ahead at " + n.getX() + " " + n.getY() + " " + n.getZ();
                }
            }
        }
        return null;
    }

    // ================================================================ Abbauen

    /**
     * Seite, auf die der Drill zielen muss: Er baut 3x3 quer zur getroffenen Seite ab. Von vorne (waagerecht) reicht
     * das 3x3 einen Block unter das Ziel - das darf den Boden nicht treffen. Sonst von oben (3x3 liegt flach); beim
     * Hochsteigen den Block ueber dem Kopf von unten.
     */
    private Direction drillFace(BlockPos pos, Step s) {
        if (s.ascending() && pos.equals(s.next().relative(heading.getOpposite()).above(2))) {
            return Direction.DOWN; // ueber dem eigenen Kopf: von unten (3x3 liegt flach, weit weg vom Boden)
        }
        return pos.getY() - 1 > s.floor().getY() ? heading.getOpposite() : Direction.UP;
    }

    /** Kamera auf eine sichtbare Seite von pos (Drill: genau auf face). Rueckgabe: Problem, sonst null. */
    private String aimAt(LocalPlayer player, ClientLevel level, BlockPos pos, Direction face) {
        if (!pos.equals(aimPos)) {
            aimPos = pos;
            aimTicks = 0;
        }
        Vec3 eye = player.getEyePosition();
        BlockHitResult target = visibleFace(level, player, eye, pos, face);
        if (target == null) {
            // Kein Teil des Blocks sichtbar (und nichts Kleines davor, das man wegmachen koennte)
            if (++aimTicks > 60) {
                return blocked("cannot reach the block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            return null;
        }
        float[] rot = rotationTo(eye, target.getLocation());
        aim.lookAt(player, rot[0], rot[1]);
        if (!mining && ++aimTicks > 100) {
            return blocked("cannot aim at the block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        }
        return null;
    }

    /**
     * Linksklick gedrueckt halten: abbauen, was gerade im Fadenkreuz ist - genau wie Vanilla beim Halten der
     * Maustaste (continueDestroyBlock, inkl. der kurzen Pause nach jedem Block). Ohne auf das "Ankommen" der Kamera
     * zu warten, und auch im Laufen auf den naechsten Schritt. Aber nur Bloecke aus minable (Tunnel), nie Waende;
     * mit dem Drill nur, wenn die richtige Seite getroffen ist (sonst laege das 3x3 falsch).
     */
    private String hold(LocalPlayer player, ClientLevel level, Map<BlockPos, Direction> minable) {
        // Fertig abgebauter Block?
        if (miningPos != null && passable(level, miningPos, level.getBlockState(miningPos))) {
            blocksMined++;
            miningPos = null;
        }
        BlockHitResult hit = crosshair(player, level);
        BlockPos pos = hit == null ? null : hit.getBlockPos();
        BlockState state = pos == null ? null : level.getBlockState(pos);
        boolean ok = pos != null && minable.containsKey(pos) && !state.isAir() && state.getDestroySpeed(level, pos) >= 0.0F;
        if (ok && minable.get(pos) != null && drill && hit.getDirection() != minable.get(pos)) {
            ok = false;
        }
        if (!ok) {
            releaseMining(); // wie Vanilla: Fadenkreuz woanders -> Abbauen bricht ab
            return null;
        }
        if (!pos.equals(miningPos)) {
            String toolProblem = drill && minable.get(pos) != null ? selectDrill(player) : selectTool(player, state);
            if (toolProblem != null) {
                return toolProblem;
            }
            miningPos = pos.immutable();
            miningTicks = 0;
            mineRestarts = 0;
        }
        mc.gameMode.continueDestroyBlock(pos, hit.getDirection());
        mining = true;
        Compat.swing(player, InteractionHand.MAIN_HAND);
        idleTicks = 0; // Abbauen ist Fortschritt (harte Bloecke dauern)
        aimTicks = 0;
        if (++miningTicks > MAX_MINE_TICKS) {
            // Neu ansetzen (z.B. Server hat den Abbau nicht angenommen) - aber nicht ewig
            if (++mineRestarts >= 3) {
                return blocked("cannot break the block at " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            }
            mc.gameMode.stopDestroyBlock();
            miningTicks = 0;
        }
        return null;
    }

    private void releaseMining() {
        if (mining && mc.gameMode != null && mc.gameMode.isDestroying()) {
            mc.gameMode.stopDestroyBlock();
        }
        mining = false;
    }

    /** Bestes Werkzeug aus der Hotbar in die Hand. Rueckgabe: Problem, sonst null. */
    private String selectTool(LocalPlayer player, BlockState state) {
        var inv = player.getInventory();
        // Im Drill-Modus den Drill hier nicht nehmen (sein 3x3 wuerde z.B. den Boden mit abbauen)
        int exclude = drill ? drillSlot : -1;
        int best = -1;
        float bestSpeed = 1.0F;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (i == exclude || !Tools.usable(stack)) {
                continue; // fast kaputt - nicht benutzen
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed + 0.01F) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best < 0 && state.requiresCorrectToolForDrops()) {
            return "§cno usable pickaxe in the hotbar";
        }
        if (best < 0 && exclude >= 0 && inv.getSelectedSlot() == exclude) {
            // Mit der Hand abbauen: leeren (sonst irgendeinen anderen) Platz nehmen
            best = exclude == 0 ? 1 : 0;
            for (int i = 0; i < 9; i++) {
                if (i != exclude && inv.getItem(i).isEmpty()) {
                    best = i;
                    break;
                }
            }
        }
        if (best >= 0 && inv.getSelectedSlot() != best) {
            inv.setSelectedSlot(best);
        }
        return null;
    }

    /** Drill in die Hand. Rueckgabe: Problem, sonst null. */
    private String selectDrill(LocalPlayer player) {
        var inv = player.getInventory();
        if (drillSlot < 0 || inv.getItem(drillSlot).getItem() != drillItem) {
            // Verschoben? In der Hotbar neu suchen
            drillSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (drillItem != null && inv.getItem(i).getItem() == drillItem) {
                    drillSlot = i;
                    break;
                }
            }
            if (drillSlot < 0) {
                return "§cdrill is not in the hotbar anymore";
            }
        }
        if (!Tools.usable(inv.getItem(drillSlot))) {
            return "§cdrill is almost broken";
        }
        if (inv.getSelectedSlot() != drillSlot) {
            inv.setSelectedSlot(drillSlot);
        }
        return null;
    }

    // ================================================================ Loch im Boden schliessen

    /** Loch im Boden zuschuetten. Geht das nicht, einfach weiterlaufen ({@link #stepInto}). */
    private String placeFloor(LocalPlayer player, ClientLevel level, Step cur) {
        BlockPos floor = cur.floor();
        releaseMining();
        input = new Input(false, false, false, false, false, true, false); // schleichen: nicht abrutschen
        if (placeCooldown > 0) {
            placeCooldown--;
            return null;
        }
        var inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() instanceof BlockItem item && FILLERS.contains(item.getBlock())) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return stepInto(player, level, cur);
        }
        Vec3 eye = player.getEyePosition();
        BlockHitResult target = null;
        // Flaechen, an die man den neuen Block setzen kann (von unten, von vorne, von den Seiten)
        Direction[] supports = {Direction.DOWN, heading, heading.getClockWise(), heading.getCounterClockWise()};
        for (Direction side : supports) {
            BlockPos support = floor.relative(side);
            if (!BaseBlocks.isSolid(level.getBlockState(support))) {
                continue;
            }
            Direction face = side.getOpposite();
            Vec3 point = Vec3.atCenterOf(support).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
            BlockHitResult hit = clip(level, player, eye, point);
            if (hit != null && hit.getBlockPos().equals(support) && hit.getDirection() == face) {
                target = hit;
                break;
            }
        }
        if (target == null) {
            return stepInto(player, level, cur); // nichts zum Anlegen: einfach weiter
        }
        if (inv.getSelectedSlot() != slot) {
            inv.setSelectedSlot(slot);
            placeCooldown = 2;
            return null;
        }
        float[] rot = rotationTo(eye, target.getLocation());
        aim.lookAt(player, rot[0], rot[1]);
        if (!aim.settled(player) || ++settledTicks < 2) {
            return null;
        }
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !hit.getBlockPos().equals(target.getBlockPos()) || hit.getDirection() != target.getDirection()) {
            settledTicks = 0;
            return null;
        }
        if (placeAttempts > 6) {
            return stepInto(player, level, cur); // klappt nicht: einfach weiter
        }
        placeAttempts++;
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            Compat.swingUse(player, InteractionHand.MAIN_HAND);
        }
        settledTicks = 0;
        placeCooldown = 4;
        idleTicks = 0;
        return null;
    }

    // ================================================================ Laufen

    /** Geradeaus sprinten; die Kamera schaut dabei schon auf den naechsten Block im Weg (wird im Laufen abgebaut). */
    private void walk(LocalPlayer player, ClientLevel level, Step look) {
        aimPos = null;
        float pitch = look.descending() ? 35.0F : 12.0F;
        BlockPos ahead = firstSolid(level, look);
        if (ahead != null) {
            Vec3 eye = player.getEyePosition();
            BlockHitResult face = visibleFace(level, player, eye, ahead, drill ? drillFace(ahead, look) : null);
            if (face != null) {
                pitch = rotationTo(eye, face.getLocation())[1];
            }
        }
        aim.follow(heading.toYRot(), pitch);
        input = centerInput(player, true);
    }

    /**
     * Seitlich zur Tunnelmitte; forward (mit Sprinten) nur, wenn man mittig genug steht - sonst haengt man an der
     * Kante.
     */
    private Input centerInput(LocalPlayer player, boolean forward) {
        BlockPos feet = player.blockPosition();
        Direction left = heading.getCounterClockWise();
        double centerX = feet.getX() + 0.5D;
        double centerZ = feet.getZ() + 0.5D;
        // Versatz zur Mitte in Richtung "links"
        double offset = (player.getX() - centerX) * left.getStepX() + (player.getZ() - centerZ) * left.getStepZ();
        boolean goLeft = offset < -0.06D;
        boolean goRight = offset > 0.06D;
        boolean walk = forward && Math.abs(offset) <= CENTER_WALK;
        // Laufen nur, wenn die Kamera ungefaehr geradeaus schaut
        if (walk && Math.abs(Mth.wrapDegrees(player.getYRot() - heading.toYRot())) > 25.0F) {
            walk = false;
        }
        boolean sprint = walk && player.getFoodData().getFoodLevel() > 6;
        return new Input(walk, false, goLeft, goRight, false, false, sprint);
    }

    // ================================================================ Zielen

    /** Eine Seite von pos, die man vom Auge aus sieht (die zum Spieler zuerst; only = nur diese). null = keine. */
    private BlockHitResult visibleFace(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos pos, Direction only) {
        Direction[] order = only != null ? new Direction[]{only} : new Direction[]{heading.getOpposite(), Direction.UP,
                Direction.DOWN, heading.getClockWise(), heading.getCounterClockWise()};
        for (Direction face : order) {
            Vec3 point = Vec3.atCenterOf(pos).add(face.getStepX() * 0.49D, face.getStepY() * 0.49D, face.getStepZ() * 0.49D);
            Vec3 toEye = eye.subtract(point);
            if (toEye.x * face.getStepX() + toEye.y * face.getStepY() + toEye.z * face.getStepZ() <= 0.0D) {
                continue; // Seite zeigt vom Auge weg
            }
            BlockHitResult hit = clip(level, player, eye, point);
            if (hit != null && hit.getBlockPos().equals(pos)) {
                return hit;
            }
            // Auch ok: davor ist nur etwas ohne Hitbox (wird dann zuerst abgebaut)
            if (hit != null && level.getBlockState(hit.getBlockPos()).getCollisionShape(level, hit.getBlockPos()).isEmpty()
                    && hit.getBlockPos().distSqr(pos) <= 2) {
                return new BlockHitResult(point, face, pos, false);
            }
        }
        return null;
    }

    private static BlockHitResult clip(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 point) {
        Vec3 dir = point.subtract(eye).normalize();
        Vec3 end = eye.add(dir.scale(Placer.reach(player) + 0.3D));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** Strahl aus dem Auge in Blickrichtung (wie das Fadenkreuz), ohne Fluessigkeiten. */
    private static BlockHitResult crosshair(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(Placer.reach(player) + 0.3D));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    /** {yaw, pitch} von eye zu target (Minecraft-Winkel). */
    private static float[] rotationTo(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }
}
