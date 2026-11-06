package nova.nova_client.client.farmbuilder;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.NovaNotify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Der eigentliche Bau-Bot. Ablauf: Kisten ansehen -> falsche Bloecke im Bereich abbauen -> von unten nach oben bauen
 * -> Wasser/Lava setzen -> zweite Runde fuer Bloecke, die Wasser brauchten -> Hilfsbloecke (Tuerme) wieder abbauen.
 *
 * Alles laeuft ueber echte Eingaben: Laufen per Tasten, Kamera per (gerundeter) Mausbewegung, Klicks wie beim
 * Spieler. Damit es nicht nach Bot aussieht: geschwungene Kamerabewegungen, keine exakten Wege, unterschiedliche
 * Pausen, Bloecke in der Naehe zuerst (statt Reihe fuer Reihe), gelegentliches Umschauen.
 */
public final class FarmBot {
    public enum Phase {
        IDLE("Idle"), SCAN("Checking chests"), CLEAR("Clearing area"), BUILD("Building"), FLUIDS("Placing water"),
        BUILD2("Finishing touches"), CLEANUP("Removing scaffolding"), DONE("Done");

        public final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    private enum Step {
        CHOOSE, WALK, SELECT, AIM, VERIFY, FIXUP, MINE, CHEST_OPEN, CHEST_WAIT, CHEST_LOOT, INV_OPEN, INV_SWAP,
        INV_CLOSE, DIG_DOWN, LOOK_AROUND, FLUID_AIM, FLUID_VERIFY, REFILL, EAT, CENTER, CRAFTER_OPEN, CRAFTER_WAIT,
        CRAFTER_SLOTS
    }

    /** Letzter Wegpunkt (Standplatz) beim Ankommen - zum Nachruecken in die Blockmitte. */
    private BlockPos arrivedNode;
    private boolean centered;
    /**
     * Genaue Fussposition im Standfeld, falls der Block nur von dort aus richtig ausgerichtet entsteht (null = egal).
     * Nach dem Ankommen rueckt er (geschlichen, also genau) dorthin.
     */
    private Vec3 arriveSpot;
    private boolean spotCentered;
    /** Dort geht es nur geduckt (Auge tiefer, steiler Blick nach oben). */
    private boolean arriveCrouch;
    private int crouchWait;
    /** Bei der letzten Standplatz-Suche: welche Plaetze nur versetzt gehen, und wohin genau (bzw. geduckt). */
    private final Map<BlockPos, Vec3> standSpots = new HashMap<>();
    private final Set<BlockPos> standCrouch = new HashSet<>();

    /** Ab diesem Hungerwert wird gegessen (20 = satt; unter 18 heilt man nicht mehr, ab 6 kein Sprinten). */
    private static final int EAT_BELOW = 15;
    private int nextEatCheck;
    private boolean eatStarted;
    private boolean noFoodWarned;

    /** Was nach dem Laufen passieren soll. */
    private enum Goal {
        PLACE, MINE, CHEST, FLUID, REFILL, CLICK, ESCAPE, MOVE, CRAFTER
    }

    private static final Item[] SCAFFOLD_ITEMS = {Items.DIRT, Items.COBBLESTONE, Items.NETHERRACK, Items.COBBLED_DEEPSLATE,
            Items.STONE, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF, Items.BLACKSTONE, Items.END_STONE,
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS};
    private static final int PATH_NODES = 9000;

    private final Minecraft mc = Minecraft.getInstance();
    private final Random random = new Random();
    public final HumanAim aim = new HumanAim();
    private final Placer placer = new Placer();

    private BuildPlan plan;
    private ChestStore store;
    private Settings settings = new Settings();

    // ---------------------------------------------------------------- Zustand
    private Phase phase = Phase.IDLE;
    private Step step = Step.CHOOSE;
    private boolean paused;
    private String pauseReason = "";
    private String status = "";
    private int tick;
    private int wait;
    private int stepTicks;

    // Fortschritt
    private int doneCount;
    private int totalCount;
    private int remainingItems;
    private int placedSession;
    private long activeMs;
    private long lastTickMs;
    private final Set<Item> missing = new LinkedHashSet<>();
    private final Set<BlockPos> givenUp = new LinkedHashSet<>();
    private final Set<BlockPos> scaffolds = new LinkedHashSet<>();
    private final Map<BlockPos, Integer> deferredUntil = new HashMap<>();
    private final Map<BlockPos, Integer> attempts = new HashMap<>();
    private List<BuildPlan.Target> pending = new ArrayList<>();
    private int nextRefresh;
    /** Letzter Tick, in dem wirklich etwas weiterging (Block gesetzt/abgebaut, Kiste, Turmblock ...). */
    private int lastProgress;
    /** Letzter Tick, in dem er sichtbar unterwegs war (Wegpunkt erreicht, Block wird abgebaut) - fuer den Wachhund. */
    private int lastActivity;
    /** Ohne Fortschritt und ohne Bewegung so lange, dann ueberspringen (10 Sekunden). */
    private static final int STUCK_LIMIT = 200;
    /** So lange (Ticks) ganz ohne echten Fortschritt, dann greift die zweite Notbremse. */
    private static final int NO_PROGRESS_LIMIT = 3600;
    /** Wie oft fuer diesen Block schon etwas im Blickfeld abgebaut wurde. */
    private final Map<BlockPos, Integer> clearTries = new HashMap<>();
    /** Schnelle Suche: noch fehlender Block an dieser Stelle (aus pending). */
    private final Map<BlockPos, BuildPlan.Target> pendingAt = new HashMap<>();
    /** Runden der Phase "Finishing touches" (es wird wiederholt, solange noch etwas fehlt und es vorangeht). */
    private int build2Rounds;
    private int roundPlaced;
    /** Hebel, die schon stehen und erst am Ende umgelegt werden. */
    private int switchesWaiting;
    private static final int MAX_BUILD2_ROUNDS = 8;
    /** Seit wann auf dieser Schicht nur gewartet wird. */
    private int waitingLayer = Integer.MIN_VALUE;
    private int waitingSince;
    /** Wann zuletzt extra Hilfsbloecke geholt wurden (verhindert Endlos-Holen bei vollem Inventar). */
    private int lastScaffoldRestock = -100000;
    /** Wie oft der Wachhund ohne echten Fortschritt dazwischen eingreifen musste. */
    private int watchdogStrikes;
    /** Werkzeuge (Spitzhacke/Schaufel ...) schon aus den Kisten geholt bzw. geprueft? */
    private boolean toolsChecked;
    /** Wie oft ein Block schon per Rechtsklick nachbearbeitet wurde (Hacke, Verzoegerung ...) - begrenzt Endlos-Klicken. */
    private final Map<BlockPos, Integer> fixupTries = new HashMap<>();
    /** Schild-Editor (kommt nach dem Setzen eines Schilds): wann er geschlossen wird (-1 = noch nicht geplant). */
    private int signCloseAt = -1;

    // Eingemauert: Ausgang freigraben
    /** Aufgaben in Folge, die nicht geklappt haben (seit dem letzten echten Fortschritt). */
    private int failStreak;
    private int nextTrapCheck;
    /** Bloecke, die zum Rauskommen noch abgebaut werden muessen (null = gerade kein Freigraben). */
    private List<BlockPos> escapeCells;
    private int escapeTries;
    private int escapeMines;
    /** Wo der Bot beim Laufen zuletzt haengen blieb und wie oft in Folge an dieser Stelle. */
    private BlockPos stuckSpot;
    private int stuckHere;
    /** Erzwungene Ausbrueche seit dem letzten echten Fortschritt (danach anhalten statt endlos weiter). */
    private int forcedEscapes;
    private boolean escapeForced;
    /** Wie viele Standplaetze die letzte Suche gefunden hat, von denen aus die Aufgabe klappen wuerde. */
    private int lastValidStands;
    /** Fuer das Log: was die letzte Standplatz-Suche gefunden hat. */
    private String lastStandInfo = "";
    /** Fuer Goal.CLICK: welche Seite des Blocks man sehen muss (Hacke: oben), null = egal. */
    private Direction clickFace;
    /** Wie oft fuer einen Klick/ein Vervollstaendigen schon hingelaufen wurde (gegen Hin-und-her-Laufen). */
    private final Map<BlockPos, Integer> clickWalks = new HashMap<>();
    /** Beim Laufen: Stelle und Zeit, seit der er sich nicht mehr wirklich bewegt hat. */
    private Vec3 walkAnchor;
    private int walkAnchorTick;
    private int lastWalkTick;
    /** Pro Tick: Ergebnisse von "sperrt mich das ein?" (die Welt aendert sich innerhalb eines Ticks nicht). */
    private final Map<List<BlockPos>, Boolean> trapCache = new HashMap<>();
    private int trapCacheTick = -1;

    // Erreichbarkeit (damit der Bot sich nicht den Weg zu Stellen zubaut, an denen noch etwas fehlt)
    private static final int REACH_NODES = 8000;
    private Set<BlockPos> reachCache;
    private BlockPos reachCacheFeet;
    private int reachCacheTick;
    private int reachBudgetTick = -1;
    private int reachBudget;

    // Bau-Reihenfolge: Standplaetze offener Bloecke schuetzen (siehe AccessGuard)
    private final AccessGuard guard = new AccessGuard(new GuardHost());
    /** Rechenzeit je Tick fuer den Waechter (ns). */
    private static final long GUARD_NANOS = 6_000_000L;
    /** Block X wartet auf Block T: X wuerde T sonst den letzten brauchbaren Standplatz nehmen. */
    private final Map<BlockPos, BlockPos> waitFor = new HashMap<>();
    /** Wie oft der Waechter einen Block schon zurueckgehalten hat (begrenzt - gegen gegenseitiges Warten). */
    private final Map<BlockPos, Integer> guardHolds = new HashMap<>();
    private static final int MAX_GUARD_HOLDS = 6;

    // Redstone-sichere Reihenfolge (siehe RedstoneOrder)
    private RedstoneOrder order;
    /** Tiefste Schicht mit eben freigegebenen Bloecken (MAX = keine): dorthin, sobald die aktuelle Schicht fertig ist. */
    private int freedLayer = Integer.MAX_VALUE;

    // Crafter: gesperrte Felder wie in der Vorlage einstellen
    /** Gesetzte Crafter, deren Felder noch eingestellt (oder nach einem Neustart geprueft) werden muessen. */
    private final Set<BlockPos> crafterTodo = new LinkedHashSet<>();
    private final Set<BlockPos> crafterOk = new HashSet<>();
    /** Fehlversuche je Crafter: unterwegs so viele, am Ende (mit Hinlaufen) noch einmal so viele - dann nur melden. */
    private final Map<BlockPos, Integer> crafterTries = new HashMap<>();
    private static final int CRAFTER_TRIES = 3;
    /** Crafter, der gerade eingestellt wird. */
    private BlockPos crafterPos;

    // Wasser/Lava, die am Ende eingemauert waeren: schon beim Bauen setzen (sobald nichts mehr auslaufen kann)
    private final List<BuildPlan.Target> inlineFluids = new ArrayList<>();
    /** Dieselben nach Position (fuer "deckt dieser Block eine noch leere Quelle zu?"). */
    private final Map<BlockPos, BuildPlan.Target> inlineAt = new HashMap<>();
    /** So oft darf eine eingemauerte Fluessigkeit scheitern, bevor Bloecke darueber nicht mehr auf sie warten. */
    private static final int INLINE_TRIES = 6;
    /** Pro Tick: ist die Fluessigkeit an dieser Stelle schon sicher setzbar? (BFS nicht mehrfach je Tick) */
    private final Map<BlockPos, Boolean> inlineReadyCache = new HashMap<>();
    private int inlineReadyTick = -1;
    /** Richtungen, in die Fluessigkeit fliesst (nicht nach oben). */
    private static final Direction[] FLOW_DIRS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
            Direction.DOWN};
    /** So weit (Felder seitlich) laeuft eine Quelle hoechstens aus (Wasser 7; Lava im Nether auch). */
    private static final int FLOW_REACH = 7;
    /** Beim Eimer-Klick: Nachbarfelder und ihr Zustand vorher - landet dort Fluessigkeit, ging der Klick daneben. */
    private final Map<BlockPos, BlockState> fluidWatch = new HashMap<>();
    /** Daneben gelandete Fluessigkeit: sofort wieder aufnehmen, bevor sie die Farm flutet. */
    private final Set<BlockPos> strayFluids = new LinkedHashSet<>();

    // Nie zweimal in dieselbe Falle
    /** Stellen, an denen er schon eingesperrt war -> Tick, bis zu dem sie als Standplatz tabu sind. */
    private final Map<BlockPos, Integer> trapSpots = new HashMap<>();
    private static final int TRAP_MEMORY_TICKS = 6000;
    /** Wie oft ein Farm-Block schon fuer Wege/Ausbrueche abgebaut wurde (danach nicht mehr - keine Endlosschleife). */
    private final Map<BlockPos, Integer> rebuilds = new HashMap<>();
    private static final int MAX_REBUILDS = 2;
    /** Je Block die Standplaetze, von denen es vor Ort dann doch nicht ging (dorthin nicht noch einmal laufen). */
    private final Map<BlockPos, Set<BlockPos>> badStands = new HashMap<>();
    /** Fehlversuche an versetzten Stellen je (Block, Platz) - erst beim zweiten kommt der Platz in badStands. */
    private final Map<Long, Integer> spotMisses = new HashMap<>();
    /** (Fussfeld, Zielgegend) ohne Weg -> bis zu diesem Tick nicht wieder suchen. */
    private final Map<Long, Integer> noPathCache = new HashMap<>();
    /** Wofuer er zuletzt losgelaufen ist (sperrt er sich dabei ein, wird genau das zurueckgestellt). */
    private BlockPos lastWalkTarget;

    /** Was der Waechter ueber den Bau wissen muss. */
    private final class GuardHost implements AccessGuard.Host {
        @Override
        public java.util.Collection<BuildPlan.Target> pending() {
            return pending;
        }

        @Override
        public java.util.Collection<BuildPlan.Target> fluidTasks() {
            if (inlineFluids.isEmpty() || mc.level == null || mc.player == null) {
                return List.of();
            }
            if (fluidTasksTick != tick) {
                fluidTasksTick = tick;
                fluidTaskList.clear();
                // Nur in der Naehe (der Waechter prueft ohnehin nur dort): die Fluss-Suche fuer ALLE ~1000 Quellen einer
                // grossen Farm je Tick liess ihn sonst sofort "stalled" melden
                BlockPos feet = mc.player.blockPosition();
                for (BuildPlan.Target f : inlineFluids) {
                    BlockPos p = f.pos();
                    if (Math.abs(p.getX() - feet.getX()) > 16 || Math.abs(p.getZ() - feet.getZ()) > 16
                            || Math.abs(p.getY() - feet.getY()) > 8) {
                        continue;
                    }
                    BuildPlan.Target task = fluidTask(mc.level, f);
                    if (task != null) {
                        fluidTaskList.add(task);
                    }
                }
            }
            return fluidTaskList;
        }

        @Override
        public java.util.Collection<BuildPlan.Target> pendingNear(BlockPos center, int range) {
            List<BuildPlan.Target> out = new ArrayList<>();
            int cy0 = (center.getY() - range) >> 2;
            int cy1 = (center.getY() + range) >> 2;
            for (int gx = (center.getX() - range) >> 3; gx <= (center.getX() + range) >> 3; gx++) {
                for (int gz = (center.getZ() - range) >> 3; gz <= (center.getZ() + range) >> 3; gz++) {
                    for (int gy = cy0; gy <= cy1; gy++) {
                        List<BuildPlan.Target> cell = pendingGrid.get(gridKey(gx, gy, gz));
                        if (cell != null) {
                            out.addAll(cell);
                        }
                    }
                }
            }
            return out;
        }

        @Override
        public BuildPlan.Target pendingAt(BlockPos pos) {
            return pendingAt.get(pos);
        }

        @Override
        public boolean ignored(BuildPlan.Target t) {
            return missing.contains(t.need().item()) || givenUp.contains(t.pos());
        }

        @Override
        public boolean leavable(BlockPos stand) {
            LocalPlayer player = mc.player;
            return player != null && mc.level != null && plan != null
                    && (outside(stand) || canGetOut(mc.level, stand, escapeOptions(player)));
        }

        @Override
        public boolean avoid(BlockPos stand) {
            return avoidStand(stand);
        }

        @Override
        public boolean protect(BuildPlan.Target t) {
            if (t.need().kind() == Materials.Kind.FLUID || attempts.getOrDefault(t.pos(), 0) > 0
                    || waitFor.containsValue(t.pos())) {
                return true;
            }
            // Kisten und Trichter nach unten gehen von fast ueberall - bei Farmen mit hunderten davon (Kisten-/Trichter-
            // Ebene) kostete ihr Schutz den Waechter fast die ganze Zeit und jeden Block eine Sekunde Warten
            BlockState s = t.state();
            if (s.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    || s.getBlock() instanceof net.minecraft.world.level.block.HopperBlock
                    && s.getValue(BlockStateProperties.FACING_HOPPER) == Direction.DOWN) {
                return false;
            }
            return Placer.orientationSensitive(s);
        }
    }

    private final List<BuildPlan.Target> fluidTaskList = new ArrayList<>();
    private int fluidTasksTick = -1;
    /** Offene Bloecke nach Gitterzelle (8 breit, 4 hoch) - fuer schnelle "was liegt in der Naehe"-Fragen. */
    private final Map<Long, List<BuildPlan.Target>> pendingGrid = new HashMap<>();

    private static long gridKey(int gx, int gy, int gz) {
        return ((long) gx & 0x1FFFFFL) << 42 | ((long) gy & 0x1FFFFFL) << 21 | (long) gz & 0x1FFFFFL;
    }
    private static final Materials.Need WATER_NEED = new Materials.Need(Items.WATER_BUCKET, 1, Materials.Kind.FLUID);

    /**
     * Eingemauerte Fluessigkeit als Aufgabe fuer den Waechter (Art FLUID), wenn sie jetzt gefuellt werden kann: Quelle
     * mit leerem Feld bzw. nasser Block, der schon steht. null = fertig, aufgegeben oder noch nicht dran.
     */
    private BuildPlan.Target fluidTask(ClientLevel level, BuildPlan.Target f) {
        boolean lava = f.state().is(Blocks.LAVA);
        if (givenUp.contains(f.pos()) || attempts.getOrDefault(f.pos(), 0) >= INLINE_TRIES
                || missing.contains(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET) || inlineDone(level, f)) {
            return null;
        }
        if (f.waterlogged()) {
            if (!Placer.done(level.getBlockState(f.pos()), f.state()) || !inlineReadyCached(level, f)) {
                return null;
            }
            return new BuildPlan.Target(f.pos(), f.state(), WATER_NEED, true);
        }
        return level.getBlockState(f.pos()).canBeReplaced() && inlineReadyCached(level, f) ? f : null;
    }

    /** Kommt man von diesem Platz wieder raus? - ueber Ticks gemerkt, bis sich in der Welt etwas aendert. */
    private final Map<BlockPos, Boolean> leaveCache = new HashMap<>();
    private boolean leaveCacheScaffolds;

    // Durchgraben
    /** Abgebaute Bloecke ausserhalb der Farm (Gelaende), die am Ende wieder hingesetzt werden: Position -> alter Zustand. */
    private final Map<BlockPos, BlockState> restore = new LinkedHashMap<>();
    /** Tiefste Schicht, in der fuer einen Weg ein Farm-Block abgebaut wurde (MAX = keiner) - dort geht es danach weiter. */
    private int brokenPlanLayer = Integer.MAX_VALUE;
    private int lastBreakSearch = -100;

    // Aktuelle Aufgabe
    private BuildPlan.Target target;
    private boolean targetIsScaffold;
    private BuildPlan.Target resumeTarget;
    private BlockPos minePos;
    private Direction mineFace;
    private boolean mining;
    private Placer.Aim currentAim;
    private Goal goal;
    private List<BlockPos> path;
    /** Wie jeder Wegpunkt erreicht wird (laufen, Turm, Bruecke, runtergraben ...). */
    private List<Pathfinder.Move> moves;
    private int moveStage;
    private int moveTicks;
    private int moveTries;
    private int moveWait;
    /** Aktuelle Bau-Schicht (geht in einer Runde nur nach oben, nie zurueck). */
    private int currentLayer = Integer.MIN_VALUE;
    /** Gewollter Sprung aus der Hoehe: kurz keinen Schaden-Stopp ausloesen. */
    private int ignoreDamageUntil;
    /** So viele Hilfsbloecke beim naechsten Kisten-Besuch mindestens mitnehmen. */
    private int scaffoldWanted;
    private int pathIndex;
    private int repaths;
    private double bestPathDistance;
    private int noProgressTicks;
    /** Bis zu diesem Tick vor einem Sprung erst in die Feldmitte ruecken. */
    private int centerJumpUntil;
    /** Zuletzt beim Laufen gewollte Blickrichtung (nur fuers Protokoll). */
    private float lastWalkYaw;
    private int lastWallLog = -1000;
    /** Bis zu diesem Tick aus einem Block heraus ruecken (Kopf steckte drin). */
    private int wallEscapeUntil;
    /** Wie oft der Kopf zuletzt an derselben Stelle im Block steckte (dann wird der Block abgebaut). */
    private int wallHits;
    private int wallHitTick;
    private BlockPos wallHitPos;
    /** Letzter Schritt des Weges setzt nur den Hilfsblock an der Kante (schwebender Block) - nicht darauf laufen. */
    private boolean leanOnly;
    /** Bis hierhin keine neue Steg-Suche fuer schwebende Bloecke (die letzte fand keinen Weg). */
    private int floatNoWayUntil;
    /** Beginn des aktuellen Weges und wie lange er hoechstens dauern darf (Ticks). */
    private int walkStartTick;
    private int walkLimit = Integer.MAX_VALUE;
    /** Eimer-Fuellen am Wasser: Fehlversuche in Folge, danach eine Weile nur aus der Kiste. */
    private int refillMisses;
    private int refillBlockedUntil;
    private int lastNoProgressLog;
    /** Naechste Suche nach daneben gelandeten Fluessigkeiten rund um den Bot. */
    private int nextStrayScan;
    /** Bloecke, die zu lange zum Abbauen braeuchten - schon gemeldet. */
    private final Set<BlockPos> slowWarned = new HashSet<>();
    /** Wie oft die zweite Notbremse hintereinander ohne jeden Fortschritt dazwischen gegriffen hat. */
    private int noProgressStrikes;
    private float walkPitch;
    private int settledTicks;
    private int reaction;
    private boolean sneakReplanned;
    private int fixupClicksLeft;
    private Step afterInventory;
    private int swapFrom;
    private int swapTo;
    private Item wantedHotbarItem;
    private Step afterLook;

    // Material holen
    private final List<BlockPos> chestQueue = new ArrayList<>();
    private BlockPos chestPos;
    private Map<Item, Integer> shopping = new LinkedHashMap<>();
    private boolean scanOnly;
    private int lootActions;
    /** Tausch gegen Kram, wenn Inventar und Kiste voll sind: 0 = keiner, 1/2 = naechster Klick (Kram-Platz/Kistenfeld). */
    private int swapStage;
    private int swapChest;
    private int swapInv;
    /** Bis zu diesem Tick ist Nachholen sinnlos (Inventar voll, nichts zum Tauschen) - erst Vorhandenes verbauen. */
    private int inventoryFullUntil;
    private int hotbarFixes;
    private Item restockReason;
    /** Wann zuletzt fuer ein Item geholt wurde: kommt beim Platzmachen nicht gleich wieder zurueck (sonst Ping-Pong). */
    private final Map<Item, Integer> fetchedAt = new HashMap<>();
    /** Bei diesem Nachholen schon geoeffnete Kisten (0 = keine erreicht). */
    private int chestsOpened;
    /** Das Gesuchte passte an der letzten Kiste nicht mehr ins Inventar: nicht noch weitere Kisten oeffnen. */
    private boolean inventoryStuck;
    /** Bis wann ein Item von unreachableFrom aus nicht nachgeholt wird (die Kisten waren von dort nicht erreichbar). */
    private final Map<Item, Integer> unreachableUntil = new HashMap<>();
    private BlockPos unreachableFrom;

    // Clear-Liste
    private List<BlockPos> clearList = new ArrayList<>();

    // Blick nach unten (Turm / runtergraben)
    private float downYaw;
    private float downPitch;

    // Fluessigkeiten
    private List<BuildPlan.Target> fluidPending = new ArrayList<>();
    /**
     * In der Wasser-Phase noch nicht sicher, weil ein Block der Vorlage fehlt (z.B. Kanalboden): nach den letzten
     * Runden noch einmal versuchen, statt sie aufzugeben.
     */
    private final List<BuildPlan.Target> fluidRetry = new ArrayList<>();
    private boolean finalFluids;
    /** Letzte "nicht sicher"-Pruefung scheiterte nur an einem noch fehlenden Block der Vorlage. */
    private boolean unsafeBlockMissing;

    // Eingabe fuer KeyboardInputMixin
    private volatile Input input;

    // Pausen-Erkennung
    private float lastHealth = -1.0F;
    private int playersAway;

    /** Einstellungen aus dem Modul. */
    public static final class Settings {
        public float speed = 1.0F;
        public boolean breakWrong = true;
        public boolean pauseNearPlayers = true;
        public int playerRadius = 24;
        public boolean humanPauses = true;
        public boolean storeJunk = true;
        public boolean useTowers = true;
        /** Kommt er nirgends hin: Bloecke im Weg abbauen (Spitzhacke/Schaufel) und spaeter wieder hinsetzen. */
        public boolean digThrough = true;
        /**
         * Streng Schicht fuer Schicht von unten nach oben: eine Schicht wird erst verlassen, wenn dort alles steht
         * (oder ein Block wirklich nicht geht - der kommt dann in den letzten Runden dran). Nichts wird vorgezogen.
         */
        public boolean layerByLayer = true;
    }

    /** Wie oft ein Block in einer Schicht scheitern darf, bevor es (im Schicht-Modus) trotzdem darueber weitergeht. */
    private static final int STRICT_TRIES = 5;
    /**
     * Ab so vielen Fehlversuchen haelt ein Block seine Schicht nicht mehr auf (er kommt in den letzten Runden wieder dran).
     * Mit 5 blieb der Bot bei grossen Farmen ueber eine Stunde auf einer Schicht mit ein paar unmoeglichen Bloecken.
     */
    private static final int LAYER_TRIES = 3;
    /** Frueheste Wiederholung desselben Blocks im Schicht-Modus (3 Sekunden). */
    private static final int STRICT_RETRY_TICKS = 60;
    /** Wann ein Block zuletzt gescheitert ist. */
    private final Map<BlockPos, Integer> lastTry = new HashMap<>();
    private int lastReposition = -100000;

    // ---------------------------------------------------------------- Festgefahrene Bloecke (unstick)
    /** Laengste Kette aus Hilfsbloecken, die fuer einen einzelnen Block gebaut wird. */
    private static final int MAX_CHAIN = 6;
    /** Rechenzeit je unstick-Versuch (die Platzier-Simulation fuer viele Standplaetze ist teuer). */
    private static final long UNSTICK_NANOS = 12_000_000L;
    /** Hilfsblock-Kette, die gerade gesetzt wird (vorderste zuerst, die erste liegt an einem festen Block). */
    private final ArrayDeque<BlockPos> supportQueue = new ArrayDeque<>();
    /** Hilfsbloecke IN der Farm, die nur fuer einen Block gesetzt wurden (Zielblock -> Zellen): danach sofort weg. */
    private final Map<BlockPos, List<BlockPos>> supportTemps = new HashMap<>();
    /** Sofort wieder abzubauende Hilfsbloecke (ihr Zielblock steht), naechster zuerst. */
    private final ArrayDeque<BlockPos> tempRemovals = new ArrayDeque<>();
    /** Hilfsbloecke, die noch stehen bleiben, solange daneben Ueber-Kopf-Bloecke fehlen (werden mitgenutzt). */
    private final Set<BlockPos> keptTemps = new LinkedHashSet<>();
    private int keptCheckTick;
    /** Schon probierte Nachbarn je Block: jeder Versuch nimmt einen anderen Weg statt immer denselben. */
    private final Map<BlockPos, Set<BlockPos>> triedAnchors = new HashMap<>();
    /**
     * Bloecke, fuer die es gerade gar keinen Weg gibt (kein Standplatz, kein brauchbarer Nachbar): erst wieder versuchen,
     * wenn sich daneben etwas aendert - statt sie im Sekundentakt erneut scheitern zu lassen. Letzte Runden: alle.
     */
    private final Set<BlockPos> needsChange = new HashSet<>();
    /** Bis zu diesem Tick gilt Schaden als Fallschaden (gerade gefallen). */
    private int recentFallUntil;
    /** unstick laeuft gerade (baut einen Plan-Nachbarn vor) - nicht verschachteln. */
    private boolean unsticking;

    // ---------------------------------------------------------------- Waechter darf nie haengen
    /** So lange darf der Waechter fuer einen Block "noch am Rechnen" sagen (0,75 s), dann wird ohne ihn gesetzt. */
    private static final int GUARD_PATIENCE_TICKS = 15;
    /** So lange am Stueck nur "planning" (5 s): Waechter pausiert ... */
    private static final int GUARD_STALL_TICKS = 100;
    /** ... fuer diese Zeit (60 s). */
    private static final int GUARD_SUSPEND_TICKS = 1200;
    /** Seit wann der Waechter fuer diesen Block unentschieden ist. */
    private final Map<BlockPos, Integer> guardLaterSince = new HashMap<>();
    /** Erster Tick einer ununterbrochenen Reihe von "noch am Rechnen", -1 = keine. */
    private int planningSince = -1;
    private int guardSuspendedUntil;

    // ================================================================ Steuerung von aussen

    public void start(BuildPlan plan, ChestStore store, Settings settings) {
        this.plan = plan;
        this.store = store;
        this.settings = settings;
        aim.setSpeed(settings.speed);
        aim.reset();
        missing.clear();
        givenUp.clear();
        deferredUntil.clear();
        attempts.clear();
        fixupTries.clear();
        clickWalks.clear();
        toolsChecked = false;
        signCloseAt = -1;
        resetEscape();
        pruneScaffolds();
        placedSession = 0;
        activeMs = 0L;
        lastTickMs = Util.getMillis();
        paused = false;
        lastHealth = -1.0F;
        target = null;
        resumeTarget = null;
        input = Input.EMPTY;
        nextRefresh = 0;
        lastProgress = tick;
        lastActivity = tick;
        waitingLayer = Integer.MIN_VALUE;
        clearTries.clear();
        build2Rounds = 0;
        nextEatCheck = 0;
        noFoodWarned = false;
        noScaffoldWarned = false;
        switchesWaiting = 0;
        lastTry.clear();
        lastReposition = -100000;
        guard.clear();
        waitFor.clear();
        guardHolds.clear();
        trapSpots.clear();
        rebuilds.clear();
        badStands.clear();
        spotMisses.clear();
        noPathCache.clear();
        fetchedAt.clear();
        unreachableUntil.clear();
        unreachableFrom = null;
        refillMisses = 0;
        refillBlockedUntil = 0;
        lastNoProgressLog = tick;
        noProgressStrikes = 0;
        slowWarned.clear();
        nextStrayScan = 0;
        leanOnly = false;
        floatNoWayUntil = 0;
        supportQueue.clear();
        supportTemps.clear();
        tempRemovals.clear();
        keptTemps.clear();
        triedAnchors.clear();
        needsChange.clear();
        recentFallUntil = 0;
        guardLaterSince.clear();
        planningSince = -1;
        guardSuspendedUntil = 0;
        lastWalkTarget = null;
        FarmLog.start("plan " + plan.sizeX + "x" + plan.sizeY + "x" + plan.sizeZ + " at " + plan.origin.toShortString()
                + ", " + plan.targets.size() + " targets");
        order = plan.order();
        order.clearEarly();
        freedLayer = Integer.MAX_VALUE;
        inlineFluids.clear();
        inlineAt.clear();
        fluidWatch.clear();
        strayFluids.clear();
        fluidRetry.clear();
        finalFluids = false;
        for (BuildPlan.Target t : plan.targets) {
            boolean fullWet = t.waterlogged()
                    && t.state().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (fullWet || (t.need().kind() == Materials.Kind.FLUID || t.waterlogged())
                    && (enclosedLater(t.pos()) || coveredLater(t.pos()))) {
                inlineFluids.add(t);
                inlineAt.put(t.pos(), t);
            }
        }
        crafterTodo.clear();
        crafterOk.clear();
        crafterTries.clear();
        crafterPos = null;
        if (mc.level != null) {
            // Schon stehende Crafter einmal ansehen: ihre Felder sieht man erst im Menue
            for (BlockPos p : plan.crafterSlots.keySet()) {
                if (mc.level.getBlockState(p).getBlock() instanceof CrafterBlock) {
                    crafterTodo.add(p);
                }
            }
        }
        FarmLog.line("ORDER sources=" + order.sources + " observers=" + order.observers + " tnt=" + order.tnt
                + " crafters=" + plan.crafterSlots.size() + " inlineFluids=" + inlineFluids.size());
        refreshProgress();
        // Erst alle Kisten einmal ansehen, damit klar ist, was wo liegt
        chestQueue.clear();
        for (BlockPos pos : store.chests()) {
            if (!store.indexed(pos)) {
                chestQueue.add(pos);
            }
        }
        if (!chestQueue.isEmpty()) {
            enter(Phase.SCAN);
            scanOnly = true;
            nextChest();
        } else {
            enter(settings.breakWrong ? Phase.CLEAR : Phase.BUILD);
        }
        wait = 15 + random.nextInt(15);
    }

    public void stop() {
        boolean botScreen = step == Step.CHEST_WAIT || step == Step.CHEST_LOOT || step == Step.INV_SWAP
                || step == Step.INV_CLOSE || step == Step.CRAFTER_WAIT || step == Step.CRAFTER_SLOTS;
        if (mc.player != null && botScreen && mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            mc.player.closeContainer();
        }
        releaseMining();
        stopEating(mc.player);
        input = null;
        phase = Phase.IDLE;
        step = Step.CHOOSE;
        target = null;
        path = null;
        aim.reset();
        FarmLog.line("STOP");
        FarmLog.stop();
    }

    public void setPaused(boolean paused, String reason) {
        this.paused = paused;
        this.pauseReason = reason;
        if (paused) {
            FarmLog.line("PAUSED " + reason);
            stopEating(mc.player);
            if (step == Step.EAT) {
                step = Step.CHOOSE;
            }
            releaseMining();
            input = null;
            aim.reset();
        } else {
            aim.reset();
            lastHealth = -1.0F;
            lastProgress = tick;
            input = Input.EMPTY;
            path = null;
            resetEscape(); // z.B. hat der Spieler den Bot gerade selbst befreit
            if (step == Step.WALK) {
                step = Step.CHOOSE;
            }
        }
    }

    private void resetEscape() {
        failStreak = 0;
        nextTrapCheck = 0;
        escapeCells = null;
        escapeTries = 0;
        escapeMines = 0;
        stuckSpot = null;
        stuckHere = 0;
        forcedEscapes = 0;
    }

    public boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    public boolean paused() {
        return paused;
    }

    public Phase phase() {
        return phase;
    }

    public String status() {
        if (paused) {
            return "Paused: " + pauseReason;
        }
        return phase.label + (status.isEmpty() ? "" : " - " + status);
    }

    public int doneCount() {
        return doneCount;
    }

    public int totalCount() {
        return totalCount;
    }

    public Set<Item> missing() {
        return missing;
    }

    public BuildPlan.Target currentTarget() {
        return target;
    }

    public BlockPos currentMine() {
        return mining || step == Step.MINE ? minePos : null;
    }

    public Set<BlockPos> scaffolds() {
        return scaffolds;
    }

    /** Restzeit: am Anfang aus der Schaetzung, dann immer mehr aus der echten Geschwindigkeit. */
    public long etaSeconds() {
        if (plan == null) {
            return 0L;
        }
        long estimate = Estimate.seconds(plan, remainingItems, store == null ? 12.0D : store.averageDistance(plan.origin), settings.speed);
        if (placedSession < 20 || activeMs < 20_000L) {
            return estimate;
        }
        double perBlock = activeMs / 1000.0D / placedSession;
        double measured = remainingItems * perBlock + plan.fluidCount * (phase.ordinal() <= Phase.BUILD.ordinal() ? 2.4D : 0.0D);
        double w = Math.min(1.0D, placedSession / 150.0D);
        return Math.round(measured * w + estimate * (1.0D - w));
    }

    /** Eingabe, die KeyboardInputMixin statt der Tastatur benutzt (null = Spieler steuert selbst). */
    public Input inputOverride() {
        if (phase == Phase.IDLE || phase == Phase.DONE || paused) {
            return null;
        }
        return input;
    }

    /** Pro Frame aus dem HUD: Kamera fluessig bewegen. */
    public void frame() {
        if (running() && !paused) {
            aim.update(mc);
        }
    }

    // ================================================================ Tick

    public void tick() {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || plan == null || !running()) {
            return;
        }
        tick++;
        long now = Util.getMillis();
        if (!paused) {
            activeMs += Math.min(500L, now - lastTickMs);
        }
        lastTickMs = now;

        if (player.isInLava() && (!paused || pauseReason.equals("took damage"))) {
            // Notfall: in Lava nicht stehen bleiben (anhalten = sterben), sondern sofort heraus
            if (paused) {
                setPaused(false, "");
            }
            escapeLava(player, level);
            lastHealth = player.getHealth();
            return;
        }
        if (!paused && tick < wallEscapeUntil && player.isInWall()) {
            escapeWall(player, level); // noch drin: weiter heraus, nicht zwischendurch zurueck ins alte Ziel laufen
            return;
        }
        if (!paused) {
            checkPauses(player, level);
        } else if (pauseReason.startsWith("Player")) {
            // Automatisch weiter, wenn niemand mehr da ist
            if (nearbyPlayer(player, level) == null) {
                if (++playersAway > 100) {
                    setPaused(false, "");
                    NovaNotify.chat("Schematic Builder", "§7Nobody around anymore - continuing.");
                }
            } else {
                playersAway = 0;
            }
        }
        if (paused) {
            return;
        }
        aim.update(mc);

        if (tick >= nextRefresh) {
            refreshProgress();
            nextRefresh = tick + 40;
        }

        // Nach dem Setzen eines Schilds oeffnet der Server den Text-Editor. Der Bot schreibt nichts hinein und
        // schliesst ihn nach kurzer "Lesezeit" wie mit "Fertig" (sonst wuerde er ewig davor stehen bleiben).
        if (mc.gui.screen() instanceof AbstractSignEditScreen signScreen) {
            input = Input.EMPTY;
            releaseMining();
            lastProgress = tick;
            if (signCloseAt < 0) {
                signCloseAt = tick + delay(6, 16);
            } else if (tick >= signCloseAt) {
                signCloseAt = -1;
                signScreen.onClose();
            }
            return;
        }
        signCloseAt = -1;

        // Ein Menue, das nicht vom Bot kommt (Chat, ESC ...): stehen bleiben und warten
        boolean botScreen = step == Step.CHEST_WAIT || step == Step.CHEST_LOOT || step == Step.INV_SWAP
                || step == Step.INV_CLOSE || step == Step.INV_OPEN || step == Step.CRAFTER_WAIT
                || step == Step.CRAFTER_SLOTS;
        if (mc.gui.screen() != null && !botScreen) {
            if (mc.gui.screen() instanceof AbstractContainerScreen<?> && (step == Step.VERIFY || step == Step.FIXUP)
                    && target != null) {
                // Klick hat unerwartet ein Menue geoeffnet -> Block merken und schliessen
                Placer.learnInteractive(level.getBlockState(currentAim == null ? target.pos() : currentAim.clicked()).getBlock());
                player.closeContainer();
                step = Step.CHOOSE;
                wait = delay(4, 10);
            }
            input = Input.EMPTY;
            releaseMining();
            lastProgress = tick; // Zeit im Menue zaehlt nicht
            return;
        }
        watchdog(player);
        if (paused) {
            return; // Wachhund hat angehalten (eingemauert)
        }

        stepTicks++;
        guard.beginTick(GUARD_NANOS);
        if (wait > 0) {
            wait--;
            if (step != Step.WALK) {
                input = idleInput();
            }
            think(player, level);
            return;
        }
        if (step == Step.WALK) {
            think(player, level);
        }

        switch (step) {
            case CHOOSE -> choose(player, level);
            case WALK -> walk(player, level);
            case SELECT -> select(player);
            case AIM -> aimAndClick(player, level);
            case VERIFY -> verify(player, level);
            case FIXUP -> fixup(player, level);
            case MINE -> mine(player, level);
            case CHEST_OPEN -> chestOpen(player, level);
            case CHEST_WAIT -> chestWait(player, level);
            case CHEST_LOOT -> chestLoot(player, level);
            case INV_OPEN -> invOpen(player);
            case INV_SWAP -> invSwap(player);
            case INV_CLOSE -> invClose(player);
            case DIG_DOWN -> digDown(player, level);
            case LOOK_AROUND -> lookAround(player);
            case FLUID_AIM -> fluidAim(player, level);
            case FLUID_VERIFY -> fluidVerify(player, level);
            case REFILL -> refill(player, level);
            case EAT -> eat(player);
            case CENTER -> centerStep(player, level);
            case CRAFTER_OPEN -> crafterOpen(player, level);
            case CRAFTER_WAIT -> crafterWait(level);
            case CRAFTER_SLOTS -> crafterSlots(player, level);
        }
    }

    // ================================================================ Bau-Reihenfolge (Waechter)

    /** Freie Zeit (Warten, Laufen): Standplatz-Listen fuer die naechsten Bloecke vorrechnen. */
    private void think(LocalPlayer player, ClientLevel level) {
        if (guardActive()) {
            guard.prefetch(level, player, feetNode(player), 3);
        }
    }

    /**
     * Wird die Bau-Reihenfolge gerade ueberwacht? In den letzten Runden nicht mehr (ab Runde 3): Was sich bis dahin
     * gegenseitig blockiert, wird dann trotzdem gesetzt - notfalls macht der Bot sich mit "Platz schaffen" Raum.
     */
    private boolean guardActive() {
        return (phase == Phase.BUILD || phase == Phase.BUILD2 && build2Rounds <= 2) && tick >= guardSuspendedUntil;
    }

    /** Ergebnis der Reihenfolge-Pruefung. */
    private enum Hold {
        /** Darf gesetzt werden. */
        GO,
        /** Wartet auf einen anderen Block (dem es sonst den letzten Standplatz naehme). */
        WAIT,
        /** Noch nicht fertig gerechnet - gleich noch mal fragen (zaehlt nicht als Fehlversuch). */
        LATER
    }

    /** Darf t jetzt gesetzt werden, ohne einem anderen offenen Block den letzten Standplatz zu nehmen? */
    private Hold hold(ClientLevel level, LocalPlayer player, BuildPlan.Target t) {
        if (!guardActive() || plan.stateAt(t.pos()) == null) {
            return Hold.GO;
        }
        if (waiting(t.pos())) {
            return Hold.WAIT;
        }
        BuildPlan.Target blocker = guard.cutsOff(level, player, t.pos(), t.state());
        if (blocker == AccessGuard.UNDECIDED) {
            // Der Waechter rechnet noch (Zeitbudget je Tick). Nie endlos: nach kurzer Zeit wird ohne ihn entschieden,
            // und plant der Bot insgesamt zu lange am Stueck, pausiert der Waechter eine Weile ganz.
            int since = guardLaterSince.computeIfAbsent(t.pos().immutable(), k -> tick);
            if (planningSince < 0) {
                planningSince = tick;
            } else if (tick - planningSince > GUARD_STALL_TICKS) {
                guardSuspendedUntil = tick + GUARD_SUSPEND_TICKS;
                planningSince = -1;
                guardLaterSince.clear();
                FarmLog.line("GUARD stalled " + (GUARD_STALL_TICKS / 20) + "s - building without it for "
                        + (GUARD_SUSPEND_TICKS / 20) + "s");
                return Hold.GO;
            }
            if (tick - since > GUARD_PATIENCE_TICKS) {
                guardLaterSince.remove(t.pos());
                FarmLog.line("GUARD timeout " + t.pos().toShortString() + " " + t.state().getBlock().getName().getString()
                        + " - undecided after " + (tick - since) + " ticks, placing anyway");
                return Hold.GO;
            }
            return Hold.LATER;
        }
        guardLaterSince.remove(t.pos());
        planningSince = -1;
        if (blocker == null) {
            return Hold.GO;
        }
        if (blocker.need().kind() == Materials.Kind.PLACE && pendingAt.containsKey(blocker.pos())
                && (coversPending(level, blocker) || orderWaits(level, blocker))) {
            return Hold.GO; // der andere muss selbst noch warten (z.B. Werfer auf den Crafter daneben) - nicht im Kreis
        }
        if (orderBlocker(blocker.pos()) != null) {
            // Der andere wartet nur wegen der Redstone-Reihenfolge - lieber jetzt setzen als nie mehr hinkommen
            order.allowEarly(blocker.pos());
            freedLayer = Math.min(freedLayer, blocker.pos().getY());
            FarmLog.line("ORDER early " + blocker.pos().toShortString() + " "
                    + blocker.state().getBlock().getName().getString() + " (would lose its last stand)");
        }
        int holds = guardHolds.merge(t.pos().immutable(), 1, Integer::sum);
        if (holds > MAX_GUARD_HOLDS) {
            FarmLog.line("GUARD gives up holding " + t.pos().toShortString() + " for " + blocker.pos().toShortString());
            return Hold.GO; // wartet schon zu oft - gegenseitiges Warten aufloesen
        }
        waitFor.put(t.pos().immutable(), blocker.pos().immutable());
        FarmLog.line("WAIT " + t.pos().toShortString() + " " + t.state().getBlock().getName().getString() + " until "
                + blocker.pos().toShortString() + " " + blocker.state().getBlock().getName().getString()
                + " is placed (would take its last stand)");
        return Hold.WAIT;
    }

    /**
     * Wartet der Block an pos noch auf einen anderen? Ist der andere fertig (oder wird nicht mehr gebaut), endet das
     * Warten - und die Runde geht bis zu dieser Schicht zurueck, damit er jetzt drankommt.
     */
    private boolean waiting(BlockPos pos) {
        BlockPos blocker = waitFor.get(pos);
        if (blocker == null) {
            return false;
        }
        BuildPlan.Target t = pendingAt.get(blocker);
        if (t != null && !missing.contains(t.need().item()) && !givenUp.contains(blocker) && guardActive()) {
            return true;
        }
        BuildPlan.Target f = t == null && mc.level != null ? inlineAt.get(blocker) : null;
        if (f != null && guardActive() && fluidTask(mc.level, f) != null) {
            return true; // wartet, bis Wasser/Lava dort drin ist
        }
        waitFor.remove(pos);
        brokenPlanLayer = Math.min(brokenPlanLayer, pos.getY());
        return false;
    }

    /** Ein Block ist fertig: was auf ihn gewartet hat, kommt jetzt dran. */
    private void released(BlockPos done) {
        guard.done(done);
        guard.touch(done);
        leaveCache.clear();
        waitFor.entrySet().removeIf(e -> {
            if (e.getValue().equals(done)) {
                brokenPlanLayer = Math.min(brokenPlanLayer, e.getKey().getY());
                FarmLog.line("RELEASE " + e.getKey().toShortString() + " (" + done.toShortString() + " is placed)");
                return true;
            }
            return false;
        });
        if (order != null) {
            // Redstone-Reihenfolge: wer nur noch auf diesen Block gewartet hat, kommt dran, sobald die Schicht fertig ist
            for (BlockPos d : order.after(done)) {
                if (pendingAt.containsKey(d) && orderBlocker(d) == null) {
                    freedLayer = Math.min(freedLayer, d.getY());
                }
            }
        }
    }

    // ================================================================ Redstone-Reihenfolge

    /** Gilt die Redstone-Reihenfolge gerade? In den letzten Runden nicht mehr (dann soll einfach alles stehen). */
    private boolean orderActive() {
        return order != null && (phase.ordinal() <= Phase.BUILD.ordinal() || phase == Phase.BUILD2 && build2Rounds <= 2);
    }

    /** Erster offene Block, der laut Redstone-Reihenfolge vor diesem stehen muss (null = darf). */
    private BlockPos orderBlocker(BlockPos pos) {
        if (!orderActive() || order.early(pos)) {
            return null;
        }
        for (BlockPos p : order.before(pos)) {
            BuildPlan.Target u = pendingAt.get(p);
            // Was fehlt, aufgegeben ist oder immer wieder scheitert, haelt niemanden auf
            if (u != null && !missing.contains(u.need().item()) && !givenUp.contains(p)
                    && attempts.getOrDefault(p, 0) < 3 && !needsChange.contains(p)) {
                return p;
            }
        }
        return null;
    }

    /** Darf t noch nicht? (Redstone-Reihenfolge; TNT ausserdem nie, solange die Stelle Strom hat - es zuendet sofort) */
    private boolean orderWaits(ClientLevel level, BuildPlan.Target t) {
        if (order == null) {
            return false;
        }
        if (RedstoneOrder.isTnt(t.state()) && level.hasNeighborSignal(t.pos())) {
            return true;
        }
        return orderBlocker(t.pos()) != null;
    }

    /** Tiefste Schicht unter layer mit Bloecken, die nach der Redstone-Reihenfolge jetzt duerfen (MAX = keine). */
    private int lowestFreed(ClientLevel level, int layer) {
        int low = Integer.MAX_VALUE;
        if (order == null) {
            return low;
        }
        for (BuildPlan.Target t : pending) {
            int y = t.pos().getY();
            if (y < layer && y < low && order.before(t.pos()).length > 0 && !missing.contains(t.need().item())
                    && attempts.getOrDefault(t.pos(), 0) < STRICT_TRIES && !waiting(t.pos())
                    && !needsChange.contains(t.pos()) && !orderWaits(level, t)) {
                low = y;
            }
        }
        return low;
    }

    /** Je wichtiger, desto frueher: Bloecke, auf die andere warten, und Bloecke mit wenigen Standplaetzen. */
    private double priority(BuildPlan.Target t) {
        double p = 0.0D;
        for (BlockPos blocker : waitFor.values()) {
            if (blocker.equals(t.pos())) {
                p += 80.0D; // andere warten auf diesen Block
                break;
            }
            if (t.pos().equals(guard.neededNeighbor(blocker))) {
                p += 60.0D; // ... und der braucht diesen hier zum Anklicken
                break;
            }
        }
        int stands = guard.knownStands(t);
        if (stands >= 0) {
            p += Math.max(0, 8 - stands) * 5.0D;
        }
        return p;
    }

    /**
     * Server meldet eine Blockaenderung (vor dem Anwenden - im Client steht noch der alte Zustand): Standplatz-Listen in
     * der Naehe neu rechnen. Nur wenn sich Block oder Form aendert: Redstone-Staerke, Beobachter-Puls, Verstaerker an/aus
     * ... aendern nichts am Laufen oder Zielen. Frueher warf jede solche Meldung die Rechnungen des Waechters weg - bei
     * einer laufenden Redstone-Farm im Takt, sodass er nie fertig wurde und der Bot ewig "plante".
     */
    public void onWorldChanged(BlockPos pos, BlockState now) {
        if (plan == null || !running() || mc.level == null) {
            return;
        }
        BlockState was = mc.level.getBlockState(pos);
        if (sameShape(mc.level, pos, was, now)) {
            return;
        }
        BlockPos min = plan.origin;
        BlockPos max = plan.max();
        if (pos.getX() >= min.getX() - 6 && pos.getX() <= max.getX() + 6 && pos.getZ() >= min.getZ() - 6
                && pos.getZ() <= max.getZ() + 6 && pos.getY() >= min.getY() - 6 && pos.getY() <= max.getY() + 6) {
            guard.touch(pos);
            leaveCache.clear();
            neighborChanged(pos);
        }
    }

    /** Gleicher Block mit gleicher Kollisions- und Umriss-Form (und Fluessigkeit)? Dann aendert sich fuer den Bot nichts. */
    private static boolean sameShape(ClientLevel level, BlockPos pos, BlockState a, BlockState b) {
        if (a == b) {
            return true;
        }
        if (a.getBlock() != b.getBlock() || a.getFluidState() != b.getFluidState()) {
            return false;
        }
        return !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(a.getCollisionShape(level, pos),
                b.getCollisionShape(level, pos), net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME)
                && !net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(a.getShape(level, pos), b.getShape(level, pos),
                net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME);
    }

    // ================================================================ Fallen-Gedaechtnis

    /** War er hier (oder direkt daneben) schon eingesperrt? Dann nicht als Standplatz nehmen. */
    private boolean avoidStand(BlockPos s) {
        if (trapSpots.isEmpty()) {
            return false;
        }
        for (Map.Entry<BlockPos, Integer> e : trapSpots.entrySet()) {
            BlockPos p = e.getKey();
            if (e.getValue() > tick && Math.abs(p.getX() - s.getX()) <= 1 && Math.abs(p.getZ() - s.getZ()) <= 1
                    && Math.abs(p.getY() - s.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    /** Eingesperrt: Stelle merken und die Aufgabe, fuer die er dorthin ging, deutlich zurueckstellen. */
    private void rememberTrap(BlockPos feet) {
        trapSpots.entrySet().removeIf(e -> e.getValue() <= tick);
        trapSpots.put(feet.immutable(), tick + TRAP_MEMORY_TICKS);
        guard.touch(feet);
        if (lastWalkTarget != null) {
            attempts.merge(lastWalkTarget, 2, Integer::sum);
            defer(lastWalkTarget, 600);
            badStands.computeIfAbsent(lastWalkTarget, k -> new HashSet<>()).add(feet.immutable());
            FarmLog.line("TRAP MEMORY " + feet.toShortString() + " - never standing here again for "
                    + lastWalkTarget.toShortString());
            lastWalkTarget = null;
        }
    }

    /**
     * Angekommen, aber mit einer Fussspitze noch im Zielfeld (der Server wuerde das Setzen ablehnen): ein Stueck in
     * die Mitte des Standplatzes ruecken, dann normal weiter.
     */
    private void centerStep(LocalPlayer player, ClientLevel level) {
        if (arrivedNode == null) {
            go(Step.CHOOSE);
            return;
        }
        boolean spot = arriveSpot != null && spotCentered;
        Vec3 c = spot ? arriveSpot : center(arrivedNode);
        if (horizontal(player.position(), c) < (spot ? 0.04D : 0.08D) || stepTicks > (spot ? 50 : 25)) {
            input = spot && arriveCrouch ? new Input(false, false, false, false, false, true, false) : idleInput();
            if (spot && player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4D && stepTicks <= 50) {
                return; // erst ausrollen lassen - sonst steht er nicht dort, wo geplant
            }
            arrive(player, level);
            return;
        }
        // Zur genauen Stelle geschlichen (langsam = genau), sonst normal
        input = spot ? strafeToward(player, c, true, 0.015D) : strafeToward(player, c, false);
    }

    // ================================================================ Essen (lange Bauten)

    /** Isst der Bot gerade? Dann muss die Benutzen-Taste gedrueckt bleiben (siehe SchematicBuilderMod.startTick). */
    public boolean eating() {
        return running() && !paused && step == Step.EAT;
    }

    /**
     * Hungrig? Dann zwischen zwei Aufgaben etwas essen - sonst kann er bei grossen Bauten irgendwann nicht mehr
     * sprinten, heilt nicht mehr und nimmt am Ende Hungerschaden. true = isst jetzt bzw. holt Essen.
     */
    private boolean maybeEat(LocalPlayer player, ClientLevel level) {
        if (tick < nextEatCheck || player.getFoodData().getFoodLevel() >= EAT_BELOW) {
            return false;
        }
        nextEatCheck = tick + 100;
        Item food = bestFood(player);
        if (food == null) {
            Item inChest = bestFoodInChests();
            if (inChest != null && player.getFoodData().getFoodLevel() <= 10) {
                FarmLog.line("FOOD fetch " + nameOf(inChest));
                beginRestock(player, level, inChest);
                return step != Step.CHOOSE;
            }
            if (inChest == null && player.getFoodData().getFoodLevel() <= 8 && !noFoodWarned) {
                noFoodWarned = true;
                NovaNotify.chat("Schematic Builder", "§eGetting hungry and there is no food - put some in a selected chest.");
            }
            return false;
        }
        FarmLog.line("EAT " + nameOf(food) + " food=" + player.getFoodData().getFoodLevel());
        status = "eating";
        eatStarted = false;
        if (prepareItem(player, food, Step.EAT)) {
            go(Step.EAT);
        }
        return true;
    }

    /** Essen in der Hand: Benutzen gedrueckt halten, bis fertig gegessen ist. */
    private void eat(LocalPlayer player) {
        input = idleInput();
        lastActivity = tick;
        if (player.isUsingItem()) {
            if (stepTicks > 140) {
                stopEating(player);
                go(Step.CHOOSE);
            }
            return;
        }
        if (eatStarted) {
            // Fertig gegessen (oder abgebrochen)
            stopEating(player);
            status = "";
            go(Step.CHOOSE);
            wait = delay(4, 10);
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (!Food.edible(held) || stepTicks > 60) {
            stopEating(player);
            nextEatCheck = tick + 200;
            go(Step.CHOOSE);
            return;
        }
        mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        eatStarted = true;
        if (!player.isUsingItem()) {
            // Ging nicht (z.B. doch satt) - spaeter wieder
            stopEating(player);
            nextEatCheck = tick + 400;
            go(Step.CHOOSE);
        }
    }

    private void stopEating(LocalPlayer player) {
        mc.options.keyUse.setDown(false);
        if (player != null && player.isUsingItem() && mc.gameMode != null) {
            mc.gameMode.releaseUsingItem(player);
        }
        eatStarted = false;
    }

    /** Bestes Essen im Inventar (nichts, was fuer die Farm gebraucht wird, z.B. Karotten zum Pflanzen). */
    private Item bestFood(LocalPlayer player) {
        Item best = null;
        int bestValue = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int value = Food.nutrition(stack);
            if (value > bestValue && !plan.materials.containsKey(stack.getItem())) {
                bestValue = value;
                best = stack.getItem();
            }
        }
        return best;
    }

    private int foodCount(LocalPlayer player) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (Food.edible(stack) && !plan.materials.containsKey(stack.getItem())) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private Item bestFoodInChests() {
        Item best = null;
        int bestValue = 0;
        for (Item item : store.allItems()) {
            int value = Food.nutrition(new ItemStack(item));
            if (value > bestValue && !plan.materials.containsKey(item)) {
                bestValue = value;
                best = item;
            }
        }
        return best;
    }

    /** Hilfsbloecke, die es nicht mehr gibt (abgebaut, ersetzt), vergessen. */
    private void pruneScaffolds() {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        Set<Item> scaffoldItems = Set.of(SCAFFOLD_ITEMS);
        // Erde als Hilfsblock wird neben Gras oft selbst zu Gras - bleibt trotzdem ein Hilfsblock
        scaffolds.removeIf(p -> level.isLoaded(p) && !scaffoldItems.contains(level.getBlockState(p).getBlock().asItem())
                && !Placer.sameFamily(level.getBlockState(p), Blocks.DIRT.defaultBlockState()));
        if (plan != null) {
            // Hilfsbloecke von einem ganz anderen Bau nicht quer durch die Welt abbauen wollen
            BlockPos center = plan.origin.offset(plan.sizeX / 2, 0, plan.sizeZ / 2);
            scaffolds.removeIf(p -> p.distSqr(center) > 128.0D * 128.0D);
            restore.keySet().removeIf(p -> p.distSqr(center) > 128.0D * 128.0D);
        }
        brokenPlanLayer = Integer.MAX_VALUE;
    }

    /** "x,y,z;x,y,z" fuer die Config - damit Hilfsbloecke auch nach einem Neustart wieder abgebaut werden. */
    public String serializeScaffolds() {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : scaffolds) {
            if (!sb.isEmpty()) {
                sb.append(';');
            }
            sb.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ());
        }
        return sb.toString();
    }

    public void deserializeScaffolds(String text) {
        if (text == null || text.isBlank() || running()) {
            return;
        }
        scaffolds.clear();
        for (String part : text.split(";")) {
            String[] xyz = part.split(",");
            if (xyz.length == 3) {
                try {
                    scaffolds.add(new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()),
                            Integer.parseInt(xyz[2].trim())));
                } catch (NumberFormatException ignored) {
                    // kaputter Eintrag
                }
            }
        }
    }

    /**
     * Einmal pro Start: fehlende Werkzeuge (Spitzhacke, Schaufel, ggf. Hacke) aus den Kisten holen, damit Abbauen
     * schnell geht. true = ist unterwegs zur Kiste.
     */
    private boolean fetchTools(LocalPlayer player, ClientLevel level) {
        if (toolsChecked) {
            return false;
        }
        toolsChecked = true;
        for (Tools.Kind kind : Tools.needed(plan)) {
            if (!Tools.inInventory(player, kind) && Tools.bestInChests(store, kind) != null) {
                status = "getting tools";
                beginRestock(player, level, null);
                return step != Step.CHOOSE;
            }
        }
        return false;
    }

    /** Fortschritt merken (fuer den Wachhund). */
    private void progress() {
        lastProgress = tick;
        watchdogStrikes = 0;
        failStreak = 0;
        stuckHere = 0;
        forcedEscapes = 0;
        // Auch die Rausgrab-Versuche: sonst sammelten sie sich ueber Stunden an, und ein einzelnes Haengen spaeter
        // hielt ihn gleich als "eingemauert" an, obwohl er nur kurz feststeckte
        escapeTries = 0;
        noProgressStrikes = 0;
    }

    /**
     * Notbremse gegen Haengenbleiben: geht 10 Sekunden lang nichts voran (kein Block, und er ist auch nicht sichtbar
     * unterwegs - Laufen und Abbauen zaehlen mit), wird die aktuelle Schicht bzw. Phase uebersprungen. Die
     * uebersprungenen Bloecke kommen in den letzten Runden noch einmal dran.
     */
    private void watchdog(LocalPlayer player) {
        if (!paused && phase != Phase.SCAN && tick - lastProgress > NO_PROGRESS_LIMIT
                && tick - lastNoProgressLog > NO_PROGRESS_LIMIT) {
            // Zweite Notbremse: lange gar kein echter Fortschritt, obwohl er "beschaeftigt" wirkt (z.B. Eimer fuellen im
            // Becken klappte nie, jeder Versuch begann neu - kein Log, 35 Minuten). Zustand festhalten, alles verwerfen.
            lastNoProgressLog = tick;
            if (++noProgressStrikes >= 3) {
                // Dreimal hintereinander nichts geschafft: anhalten und Bescheid geben, statt ewig herumzuprobieren
                FarmLog.line("NO PROGRESS 3 times in a row at " + feetNode(player).toShortString() + " - pausing");
                noProgressStrikes = 0;
                setPaused(true, "no progress");
                NovaNotify.chat("Schematic Builder", "§cI'm not getting anywhere here (no block placed for a long time). "
                        + "Have a look, then resume.");
                NovaNotify.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 0.7F);
                return;
            }
            FarmLog.line("NO PROGRESS for " + (tick - lastProgress) + " ticks: phase=" + phase + " step=" + step + " goal="
                    + goal + " status=" + status + " target=" + (target == null ? "-" : target.pos().toShortString())
                    + " mine=" + (minePos == null ? "-" : minePos.toShortString()) + " feet="
                    + feetNode(player).toShortString() + (player.isInWater() ? " water" : ""));
            releaseMining();
            path = null;
            target = null;
            resumeTarget = null;
            minePos = null;
            refillBlockedUntil = tick + 1200;
            input = idleInput();
            go(Step.CHOOSE);
            if (mc.level != null && !outside(feetNode(player))) {
                leaveFarm(player, mc.level);
            }
            return;
        }
        if (tick - Math.max(lastProgress, lastActivity) < STUCK_LIMIT || phase == Phase.SCAN) {
            return;
        }
        FarmLog.line("WATCHDOG phase=" + phase + " step=" + step + " goal=" + goal + " status=" + status
                + " target=" + (target == null ? "-" : target.pos().toShortString())
                + " mine=" + (minePos == null ? "-" : minePos.toShortString())
                + " feet=" + feetNode(player).toShortString() + " strikes=" + watchdogStrikes);
        lastProgress = tick;
        lastActivity = tick;
        BuildPlan.Target stuckOn = target != null && !targetIsScaffold ? target : resumeTarget;
        BlockPos stuckMine = minePos;
        releaseMining();
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            player.closeContainer();
        }
        path = null;
        target = null;
        resumeTarget = null;
        minePos = null;
        input = idleInput();
        if (escapeCells == null && !canGetOut(mc.level, feetNode(player), escapeOptions(player))) {
            // Nicht die Schicht ist das Problem, sondern der Bot steckt fest: erst rausgraben
            startEscape(player, mc.level);
            return;
        }
        // Hing er beim Holen, Abbauen oder Laufen fest und nicht an einem Bauziel, liegt es nicht an der Schicht: nur
        // den Weg verwerfen (frueher uebersprang er dabei Schicht um Schicht, y=124 bis 138, ohne dort zu bauen)
        if (stuckOn == null && goal != Goal.PLACE && (phase == Phase.BUILD || phase == Phase.BUILD2)) {
            FarmLog.line("WATCHDOG not a layer problem (" + goal + ") - new plan");
            noteStuck(player, mc.level);
            go(Step.CHOOSE);
            return;
        }
        if (++watchdogStrikes >= 3 && phase == Phase.BUILD) {
            // Auch Schicht-Ueberspringen hilft nicht: diese Runde beenden (BUILD -> Wasser/letzte Runden). In den
            // letzten Runden wird dagegen nie abgebrochen - dort geht es nur Schicht fuer Schicht weiter.
            watchdogStrikes = 0;
            NovaNotify.chat("Schematic Builder", "§7Stuck - finishing this round" + (phase == Phase.BUILD ? " and retrying the rest." : "."));
            finishBuildPhase();
            if (phase != Phase.DONE) {
                go(Step.CHOOSE);
            }
            return;
        }
        switch (phase) {
            case BUILD, BUILD2 -> {
                if (settings.layerByLayer && stuckOn != null && watchdogStrikes < 2) {
                    // Schicht-Modus: nur diesen einen Block zurueckstellen, die Schicht bleibt
                    attempts.merge(stuckOn.pos(), 2, Integer::sum);
                    defer(stuckOn.pos(), 200);
                    NovaNotify.chat("Schematic Builder", "§7Stuck for 10s on " + stuckOn.state().getBlock().getName().getString()
                            + " at " + stuckOn.pos().toShortString() + " - trying the others first.");
                } else {
                    int skipped = skipLayer(currentLayer);
                    NovaNotify.chat("Schematic Builder", "§7Stuck for 10s - skipping " + skipped
                            + " block(s) for now, will retry them at the end.");
                }
            }
            case CLEAR -> {
                if (stuckMine != null && clearList.contains(stuckMine) && watchdogStrikes < 6) {
                    // Nur dieser Block geht gerade nicht weg: zurueckstellen, die anderen trotzdem freiraeumen
                    FarmLog.line("CLEAR later " + stuckMine.toShortString() + " (" + clearList.size() + " left)");
                    defer(stuckMine, 600);
                    go(Step.CHOOSE);
                    return;
                }
                NovaNotify.chat("Schematic Builder", "§7Could not clear " + clearList.size() + " block(s) - building anyway.");
                enter(Phase.BUILD);
            }
            case FLUIDS -> {
                NovaNotify.chat("Schematic Builder", "§7Could not place " + fluidPending.size() + " water/lava source(s).");
                enter(Phase.BUILD2);
            }
            case CLEANUP -> {
                if (!scaffolds.isEmpty() && stuckMine != null && scaffolds.contains(stuckMine) && watchdogStrikes < 6) {
                    // Nur dieser Hilfsblock geht gerade nicht: zurueckstellen, die anderen trotzdem abbauen (frueher
                    // blieben wegen eines einzigen alle uebrigen stehen)
                    FarmLog.line("CLEANUP later " + stuckMine.toShortString() + " (" + scaffolds.size() + " left)");
                    defer(stuckMine, 400);
                    go(Step.CHOOSE);
                    return;
                } else if (!scaffolds.isEmpty()) {
                    NovaNotify.chat("Schematic Builder", "§7Could not remove " + scaffolds.size() + " helper block(s).");
                    FarmLog.line("CLEANUP gave up " + scaffolds.size() + " helper block(s), e.g. "
                            + scaffolds.iterator().next().toShortString());
                    scaffolds.clear();
                } else {
                    NovaNotify.chat("Schematic Builder", "§7Could not put back " + restore.size() + " block(s) I dug through.");
                    restore.clear();
                }
                if (restore.isEmpty()) {
                    enter(Phase.DONE);
                    finish();
                    return;
                }
            }
            default -> {
            }
        }
        go(Step.CHOOSE);
    }

    /** Alle offenen Bloecke dieser Schicht als "gescheitert" markieren, damit es darueber weitergeht. */
    private int skipLayer(int layer) {
        FarmLog.line("SKIP LAYER y=" + layer + " phase=" + phase);
        int skipped = 0;
        int mark = settings.layerByLayer ? LAYER_TRIES : 2; // so viele Versuche, dass die Schicht als erledigt gilt
        for (BuildPlan.Target t : pending) {
            if (t.pos().getY() == layer && !missing.contains(t.need().item())) {
                attempts.merge(t.pos(), mark, Math::max);
                defer(t.pos(), 400);
                skipped++;
            }
        }
        waitingLayer = Integer.MIN_VALUE;
        return skipped;
    }

    /**
     * Im Schicht-Modus geht auf der Schicht gerade nichts: an eine andere erreichbare Stelle in der Naehe der offenen
     * Bloecke gehen (von dort klappt es oft - anderer Blickwinkel, der eigene Koerper steht nicht mehr im Weg ...).
     */
    private boolean reposition(LocalPlayer player, ClientLevel level, int layer) {
        lastReposition = tick;
        BlockPos feet = feetNode(player);
        List<BlockPos> open = new ArrayList<>();
        for (BuildPlan.Target t : pending) {
            if (t.pos().getY() == layer && !missing.contains(t.need().item())) {
                open.add(t.pos());
            }
        }
        if (open.isEmpty()) {
            return false;
        }
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos s : reachableNow(level, player)) {
            if (s.distManhattan(feet) < 2 || !Pathfinder.standable(level, s) || avoidStand(s)) {
                continue;
            }
            Vec3 eye = Placer.eyeAt(level, s);
            for (BlockPos o : open) {
                if (eye.distanceToSqr(Vec3.atCenterOf(o)) <= 5.0D * 5.0D && !Placer.intersectsPlayer(level, s, o)) {
                    spots.add(s);
                    break;
                }
            }
        }
        java.util.Collections.shuffle(spots, random);
        Pathfinder.Options options = pathOptions(player);
        int tried = 0;
        for (BlockPos s : spots) {
            if (++tried > 8) {
                break;
            }
            if (!canLeave(level, player, s)) {
                continue; // nicht in eine Nische ohne Ausgang stellen
            }
            Pathfinder.Path p = Pathfinder.find(level, feet, s::equals, s, PATH_NODES, options);
            if (p != null && p.buildMoves() <= scaffoldCount(player)) {
                FarmLog.line("REPOSITION " + feet.toShortString() + " -> " + s.toShortString() + " (layer " + layer + ")");
                status = "trying another spot";
                beginWalk(p, Goal.MOVE);
                return true;
            }
        }
        return false;
    }

    /** Ziel zurueckstellen. Jedes Zurueckstellen zaehlt als Versuch - so kann nichts ewig blockieren. */
    private void postpone(BlockPos pos, int baseTicks) {
        lastTry.put(pos.immutable(), tick);
        failStreak++;
        int n = attempts.merge(pos, 1, Integer::sum);
        defer(pos, baseTicks + random.nextInt(60) + n * 80);
    }

    private void enter(Phase next) {
        phase = next;
        inlineReadyCache.clear(); // "bereit?" haengt auch von der Phase ab
        step = Step.CHOOSE;
        status = "";
        target = null;
        path = null;
        deferredUntil.clear();
        if (next == Phase.CLEAR) {
            buildClearList();
        }
        if (next == Phase.FLUIDS) {
            buildFluidList();
        }
        if (next == Phase.FLUIDS || next == Phase.BUILD2) {
            attempts.clear(); // Versuche aus der vorigen Phase zaehlen hier nicht
        }
        if (next == Phase.BUILD2) {
            build2Rounds++;
            // Neue Runde, neues Glueck: auch Bloecke, die auf eine Aenderung warteten, und alle Nachbarn wieder probieren
            needsChange.clear();
            triedAnchors.clear();
            if (build2Rounds >= 4 && !supportTemps.isEmpty()) {
                // Ueber-Kopf-Bloecke, die bis jetzt nicht gingen: ihre Hilfsbloecke auf Feldern der Farm wieder weg -
                // sonst blieben dort am Ende Erde statt Trichter stehen
                for (List<BlockPos> cells : supportTemps.values()) {
                    for (int i = cells.size() - 1; i >= 0; i--) {
                        tempRemovals.addLast(cells.get(i));
                    }
                }
                FarmLog.line("UNSTICK release helpers of " + supportTemps.size() + " overhead block(s)");
                supportTemps.clear();
            }
        }
        roundPlaced = 0;
        FarmLog.line("PHASE " + next + (next == Phase.BUILD2 ? " round " + build2Rounds : ""));
        currentLayer = Integer.MIN_VALUE;
        waitingLayer = Integer.MIN_VALUE;
        lastProgress = tick;
        watchdogStrikes = 0; // Fehlversuche der vorigen Phase zaehlen hier nicht
    }

    private void go(Step next) {
        step = next;
        stepTicks = 0;
        settledTicks = 0;
        sneakReplanned = false;
        reaction = reactionTicks();
    }

    // ================================================================ Pausen

    private void checkPauses(LocalPlayer player, ClientLevel level) {
        if (player.isDeadOrDying()) {
            setPaused(true, "you died");
            NovaNotify.chat("Schematic Builder", "§cPaused - you died.");
            return;
        }
        // Gerade gefallen? (Die Lebenspunkte kommen vom Server - etwas nach der Landung, deshalb ein kurzes Fenster)
        if ((double) player.fallDistance > 2.0D) {
            recentFallUntil = tick + 20;
        }
        // Schaden, den er nicht selbst gewollt hat (Mob, Hunger, Ersticken, Feuer ...): sofort anhalten statt weiterbauen
        float health = player.getHealth();
        if (lastHealth >= 0.0F && health < lastHealth - 0.01F && tick > ignoreDamageUntil && fallDamage(player)) {
            // Fallschaden (von einem Turm/Absatz gerutscht): kein Grund anzuhalten - weiterbauen
            FarmLog.line("FALL DAMAGE " + lastHealth + " -> " + health + " feet=" + feetNode(player).toShortString()
                    + " (continuing)");
            lastHealth = health;
        } else if (lastHealth >= 0.0F && health < lastHealth - 0.01F && tick > ignoreDamageUntil
                && health > 8.0F && fireDamage(player)) {
            // Feuer (Lava zuendet Luft neben Laub an, solange oben noch offen ist): nicht stehen bleiben, sondern aus
            // dem Feuer gehen - anhalten hiesse weiterbrennen
            FarmLog.line("HAZARD fire " + lastHealth + " -> " + health + " feet=" + feetNode(player).toShortString());
            lastHealth = health;
            escapeHazard(player, level);
            return;
        } else if (lastHealth >= 0.0F && health < lastHealth - 0.01F && tick > ignoreDamageUntil && health > 8.0F
                && player.getLastDamageSource() != null && "inWall".equals(player.getLastDamageSource().getMsgId())) {
            // Steckt mit dem Kopf in einem Block (z.B. gebueckt unter einem Hilfsblock): nicht anhalten - er pausierte
            // sonst alle paar Sekunden an derselben Stelle -, sondern dort heraus
            if (tick - lastWallLog > 40) {
                lastWallLog = tick;
                BlockPos eyeCell = BlockPos.containing(player.getEyePosition());
                StringBuilder around = new StringBuilder();
                for (BlockPos q : BlockPos.betweenClosed(eyeCell.offset(-1, 0, -1), eyeCell.offset(1, 0, 1))) {
                    BlockState qs = level.getBlockState(q);
                    if (!qs.isAir()) {
                        around.append(' ').append(q.getX() - eyeCell.getX()).append(',').append(q.getZ() - eyeCell.getZ())
                                .append('=').append(qs.getBlock().getName().getString());
                    }
                }
                FarmLog.line("HAZARD in wall " + lastHealth + " -> " + health + " feet=" + feetNode(player).toShortString()
                        + String.format(java.util.Locale.ROOT, " at=%.2f,%.2f,%.2f", player.getX(), player.getY(), player.getZ())
                        + (player.isCrouching() ? " crouch" : "") + " eye=" + eyeCell.toShortString() + around);
            }
            lastHealth = health;
            BlockPos feetNow = feetNode(player);
            if (wallHitPos != null && feetNow.closerThan(wallHitPos, 1.5D) && tick - wallHitTick < 200) {
                wallHits++;
            } else {
                wallHits = 1;
                wallHitPos = feetNow;
            }
            wallHitTick = tick;
            // Immer wieder an derselben Stelle (Herausruecken klappt nicht, z.B. zwischen alten Saeulen eingeklemmt -
            // 8 Minuten lang): den Block am Kopf abbauen, wie ein Spieler es auch taete
            if (wallHits >= 4 && mineHeadBlock(player, level)) {
                wallHits = 0;
                return;
            }
            escapeWall(player, level);
            return;
        } else if (lastHealth >= 0.0F && health < lastHealth - 0.01F && tick > ignoreDamageUntil) {
            net.minecraft.world.damagesource.DamageSource src = player.getLastDamageSource();
            FarmLog.line("DAMAGE " + lastHealth + " -> " + health + " food=" + player.getFoodData().getFoodLevel()
                    + " feet=" + feetNode(player).toShortString() + " source=" + (src == null ? "-" : src.getMsgId()));
            lastHealth = health;
            setPaused(true, "took damage");
            NovaNotify.chat("Schematic Builder", String.format(java.util.Locale.ROOT,
                    "§cPaused - took damage (%.1f / %.0f HP).§7 Check for mobs, lava or hunger, then resume.",
                    health, player.getMaxHealth()));
            NovaNotify.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 0.5F);
            return;
        }
        lastHealth = health;
        // Tasten, Maus, Kolben, Schubsen ... aendern nichts: gestoppt wird nur ueber die Taste des Moduls.
        aim.consumeUserMoved();
        if (settings.pauseNearPlayers) {
            Player other = nearbyPlayer(player, level);
            if (other != null) {
                playersAway = 0;
                setPaused(true, "Player " + other.getPlainTextName() + " nearby");
                NovaNotify.chat("Schematic Builder", "§ePaused - " + other.getPlainTextName() + " is nearby.");
            }
        }
    }

    /** Feuer oder Lava direkt neben/ueber/unter diesem Platz (Fuesse oder Kopf)? */
    private static boolean hotNeighbor(ClientLevel level, BlockPos feet) {
        for (BlockPos c : new BlockPos[]{feet, feet.above()}) {
            for (Direction d : Direction.values()) {
                BlockState s = level.getBlockState(c.relative(d));
                if (s.is(BlockTags.FIRE) || s.getFluidState().is(FluidTags.LAVA)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Schaden durch Feuer (im Feuer stehen, brennen)? Lava selbst hat ihren eigenen Reflex. */
    private static boolean fireDamage(LocalPlayer player) {
        net.minecraft.world.damagesource.DamageSource source = player.getLastDamageSource();
        return source != null ? source.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) && !player.isInLava()
                : player.isOnFire() && !player.isInLava();
    }

    /** Aus Feuer heraus: wie bei Lava zum naechsten Feld ohne Feuer/Lava ringsum. */
    private void escapeHazard(LocalPlayer player, ClientLevel level) {
        escapeLava(player, level);
    }

    /**
     * Kopf steckt in einem Block (meist: am Feldrand ragt das Auge ins Nachbarfeld unter einem Hilfsblock): ohne zu
     * springen in die Mitte des eigenen Feldes, steht er dort schon, in ein freies Nachbarfeld. Springen half in der
     * Enge nicht - er nahm im Viertelsekundentakt Schaden.
     */
    private void escapeWall(LocalPlayer player, ClientLevel level) {
        if (tick >= wallEscapeUntil) {
            wallEscapeUntil = tick + 40;
        }
        releaseMining();
        path = null;
        target = null;
        go(Step.CHOOSE);
        BlockPos feet = feetNode(player);
        Vec3 c = center(feet);
        // Herausruecken ist Bewegung, kein Fortschritt (sonst sah die zweite Notbremse nie, dass er festsass)
        if (horizontal(player.position(), c) > 0.12D) {
            input = strafeToward(player, c, false, 0.05D);
            lastActivity = tick;
            return;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d);
            if (Pathfinder.standable(level, n) && Pathfinder.passable(level, n.above())) {
                input = strafeToward(player, center(n), false, 0.05D);
                lastActivity = tick;
                return;
            }
        }
        input = idleInput();
    }

    /** Liegt neben p (6 Seiten) Wasser oder Lava? */
    private static boolean nextToFluid(ClientLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            if (!level.getFluidState(p.relative(d)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Den Block abbauen, in dem der Kopf steckt (Augen-Kasten wie bei Minecrafts "im Block"-Pruefung). Farm-Bloecke
     * kommen danach neu; was dabei verloren ginge (Glas), nur mit Ersatz in den Kisten. true = baut ab.
     */
    private boolean mineHeadBlock(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        double half = player.getBbWidth() * 0.8D / 2.0D;
        // Auch auf stehender Augenhoehe: geduckt ist der Kopf hier oft frei, der Server rechnet aber stehend
        double top = Math.max(eye.y, player.getY() + 1.62D);
        BlockPos from = BlockPos.containing(eye.x - half, eye.y, eye.z - half);
        BlockPos to = BlockPos.containing(eye.x + half, top, eye.z + half);
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !s.isSuffocating(level, p) || s.getDestroySpeed(level, p) < 0.0F
                    || wouldLose(player, level, p, s) && !spareInChests(level, p) || nextToFluid(level, p)) {
                continue; // neben Lava/Wasser nie: das liefe in die Luecke (Generator!)
            }
            BlockPos cell = p.immutable();
            FarmLog.line("HAZARD head stuck in " + s.getBlock().getName().getString() + " at " + cell.toShortString()
                    + " again and again - mining it");
            rememberBroken(level, cell);
            startMining(player, level, cell, null);
            return true;
        }
        return false;
    }

    /** In Lava: zum naechsten sicheren Feld (ohne Lava) springen und laufen. Danach wird normal weitergemacht. */
    private void escapeLava(LocalPlayer player, ClientLevel level) {
        releaseMining();
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) {
            player.closeContainer();
        }
        BlockPos feet = player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-3, -1, -3), feet.offset(3, 2, 3))) {
            if (!Pathfinder.standable(level, p) || !level.getFluidState(p).isEmpty()
                    || level.getFluidState(p.above()).is(FluidTags.LAVA)) {
                continue;
            }
            // Lieber ein Feld, neben dem kein Feuer und keine Lava ist (sonst brennt er gleich wieder)
            double d = p.distToCenterSqr(player.getX(), player.getY(), player.getZ()) + (hotNeighbor(level, p) ? 30.0D : 0.0D);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        if (tick % 20 == 0) {
            FarmLog.line("HAZARD in lava at " + feet.toShortString() + " -> " + (best == null ? "no safe spot" : best.toShortString()));
        }
        path = null;
        target = null;
        go(Step.CHOOSE);
        if (best == null) {
            input = new Input(true, false, false, false, true, false, false); // irgendwie nach vorn heraus
            return;
        }
        Vec3 c = center(best);
        aim.lookAt(player, Placer.yawTo(player.position(), c), 10.0F);
        Input dir = strafeToward(player, c, false, 0.05D);
        input = new Input(dir.forward(), dir.backward(), dir.left(), dir.right(), true, false, false);
        lastProgress = tick;
    }

    /** War der letzte Schaden Fallschaden? (Schadensart vom Server, sonst: eben gefallen und jetzt am Boden) */
    private boolean fallDamage(LocalPlayer player) {
        net.minecraft.world.damagesource.DamageSource source = player.getLastDamageSource();
        if (source != null) {
            return source.is(net.minecraft.tags.DamageTypeTags.IS_FALL);
        }
        return tick <= recentFallUntil && player.onGround();
    }

    private Player nearbyPlayer(LocalPlayer player, ClientLevel level) {
        double r = settings.playerRadius;
        for (Player other : level.players()) {
            if (other != player && !other.isSpectator() && other.distanceToSqr(player) < r * r) {
                return other;
            }
        }
        return null;
    }

    // ================================================================ Fortschritt

    private void refreshProgress() {
        ClientLevel level = mc.level;
        if (level == null || plan == null) {
            return;
        }
        List<BuildPlan.Target> open = new ArrayList<>();
        List<BuildPlan.Target> later = new ArrayList<>();
        int done = 0;
        int total = 0;
        int items = 0;
        for (BuildPlan.Target t : plan.targets) {
            if (t.need().kind() != Materials.Kind.PLACE) {
                continue;
            }
            total++;
            BlockState have = level.getBlockState(t.pos());
            if (Placer.done(have, t.state())) {
                done++;
            } else if (!givenUp.contains(t.pos())) {
                if (switchLater(have, t.state())) {
                    later.add(t); // Hebel steht schon, wird erst umgelegt, wenn alles andere fertig ist
                    continue;
                }
                open.add(t);
                items += t.need().count();
            }
        }
        switchesWaiting = later.size();
        if (open.isEmpty() && !beforeFluids()) {
            open.addAll(later);
        }
        pending = open;
        pendingAt.clear();
        pendingGrid.clear();
        for (BuildPlan.Target t : open) {
            pendingAt.put(t.pos(), t);
            BlockPos p = t.pos();
            pendingGrid.computeIfAbsent(gridKey(p.getX() >> 3, p.getY() >> 2, p.getZ() >> 3), k -> new ArrayList<>()).add(t);
        }
        doneCount = done;
        totalCount = total;
        remainingItems = items;
    }

    // ================================================================ Auswahl der naechsten Aufgabe

    private void choose(LocalPlayer player, ClientLevel level) {
        input = idleInput();
        currentAim = null;
        if (escapeCells != null) {
            continueEscape(player, level);
            return;
        }
        // Zwischen zwei Aufgaben regelmaessig pruefen, ob er sich eingebaut hat (oefter, wenn gerade nichts klappt) -
        // dann zuerst rausgraben bzw. Bescheid geben
        if (tick >= nextTrapCheck && player.onGround() && !outside(feetNode(player))) {
            nextTrapCheck = tick + (failStreak >= 3 ? 40 : 100);
            if (!canGetOut(level, feetNode(player), escapeOptions(player))) {
                FarmLog.line("TRAP CHECK: boxed in at " + feetNode(player).toShortString());
                startEscape(player, level);
                return;
            }
            escapeTries = 0;
        }
        if (phase != Phase.SCAN && maybeEat(player, level)) {
            return;
        }
        // Daneben gelandetes Wasser/Lava zuerst wieder aufnehmen - es flutet sonst die Farm
        if (phase != Phase.SCAN) {
            scanStray(player, level);
        }
        if (!strayFluids.isEmpty() && phase != Phase.SCAN && cleanStray(player, level)) {
            return;
        }
        switch (phase) {
            case SCAN -> {
                if (!nextChest()) {
                    scanOnly = false;
                    enter(settings.breakWrong ? Phase.CLEAR : Phase.BUILD);
                }
            }
            case CLEAR -> {
                if (!fetchTools(player, level)) {
                    chooseClear(player, level);
                }
            }
            case BUILD, BUILD2 -> {
                if (!fetchTools(player, level)) {
                    chooseBuild(player, level);
                }
            }
            case FLUIDS -> chooseFluid(player, level);
            case CLEANUP -> chooseCleanup(player, level);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- Bauen

    private void chooseBuild(LocalPlayer player, ClientLevel level) {
        // Eben unterbrochene Aufgabe (z.B. nach Abbauen eines falschen Blocks) zuerst fortsetzen
        if (resumeTarget != null) {
            target = resumeTarget;
            resumeTarget = null;
            if (!Placer.done(level.getBlockState(target.pos()), target.state())) {
                startPlacing(player, level, target, false);
                return;
            }
        }
        // Hilfsbloecke, die nur fuer einen (jetzt fertigen) Block in der Farm standen, gleich wieder abbauen - bevor der
        // weitere Bau sie einmauert. Fehlen daneben noch Ueber-Kopf-Bloecke (Reihe Klebekolben/Crafter), bleiben sie
        // erst stehen: die naechste Hilfskette nutzt sie mit (vorher je Kolben 4 Bloecke hin und wieder weg, je ~11 s)
        if (!keptTemps.isEmpty() && tick - keptCheckTick >= 20) {
            keptCheckTick = tick;
            keptTemps.removeIf(r -> {
                if (overheadNear(r)) {
                    return false;
                }
                tempRemovals.addLast(r);
                return true;
            });
        }
        while (!tempRemovals.isEmpty()) {
            BlockPos r = tempRemovals.pollFirst();
            if (!scaffolds.contains(r) || level.getBlockState(r).isAir()) {
                continue;
            }
            if (overheadNear(r) && !pendingAt.containsKey(r) && keptTemps.size() < 16) {
                keptTemps.add(r);
                continue;
            }
            if (!safeToRemoveTemp(level, r)) {
                FarmLog.line("UNSTICK keep helper " + r.toShortString() + " (something depends on it) - removed at the end");
                continue;
            }
            FarmLog.line("UNSTICK remove helper " + r.toShortString());
            status = "removing helper block";
            startMining(player, level, r, null);
            return;
        }
        // Wasser/Lava, die sonst eingemauert wuerden: jetzt, solange man noch herankommt
        if (!inlineFluids.isEmpty() && inlineFluid(player, level)) {
            return;
        }
        // Crafter in Reichweite, dessen Felder noch fehlen: gleich einstellen
        if (!crafterTodo.isEmpty() && crafterHere(player, level)) {
            return;
        }
        // Menschliche Pausen und Umschauen
        if (settings.humanPauses && placedSession > 5 && tick - lastProgress < 60) {
            float r = random.nextFloat();
            if (r < 0.004F) {
                startLookAround(player);
                return;
            }
            if (r < 0.018F) {
                status = "short break";
                wait = delay(25, 70);
                return;
            }
        }

        if (brokenPlanLayer != Integer.MAX_VALUE) {
            // Fuer einen Weg wurden Farm-Bloecke abgebaut: die Runde geht bis dorthin zurueck, damit sie neu kommen
            currentLayer = Math.min(currentLayer, brokenPlanLayer);
            brokenPlanLayer = Integer.MAX_VALUE;
            waitingLayer = Integer.MIN_VALUE;
            nextRefresh = 0;
        }
        if (tick >= nextRefresh - 30) {
            refreshProgress();
            nextRefresh = tick + 40;
        }
        if (pending.isEmpty()) {
            finishBuildPhase();
            return;
        }
        if (allBlockedByMissing()) {
            finishBuildPhase();
            return;
        }
        // Schicht fuer Schicht von unten nach oben. Die Schicht geht in einer Runde nie zurueck nach unten: was unten
        // gerade nicht klappt, kommt in der zweiten Runde dran - sonst rennt der Bot bei hohen Bauten staendig hoch und runter.
        int layer = pickLayer();
        if (layer == Integer.MAX_VALUE) {
            finishBuildPhase();
            return;
        }
        status = "layer " + (layer - plan.origin.getY() + 1) + "/" + plan.sizeY;

        boolean strict = settings.layerByLayer;
        Vec3 eye = player.getEyePosition();
        List<BuildPlan.Target> layerTargets = new ArrayList<>();
        for (BuildPlan.Target t : pending) {
            int y = t.pos().getY();
            // Andere Schichten nur, wenn sie direkt in Reichweite sind (Menschen bauen auch nicht streng Schicht fuer
            // Schicht) - im Schicht-Modus nie. Die Hoehe zuerst: die Pruefungen danach sind bei 16000 Bloecken teuer.
            if (y != layer && (strict || y > layer + 1 || eye.distanceToSqr(Vec3.atCenterOf(t.pos())) >= 12.0D)) {
                continue;
            }
            if (deferred(t.pos()) || missing.contains(t.need().item()) || coversPending(level, t)
                    || unsupportedFalling(level, t) || waiting(t.pos()) || needsChange.contains(t.pos())
                    || orderWaits(level, t)) {
                continue;
            }
            layerTargets.add(t);
        }
        if (strict && layerTargets.isEmpty()) {
            // Auf dieser Schicht sind nur noch zurueckgestellte Bloecke: nichts darueber vorziehen, sondern sie erneut
            // versuchen - aber jeden erst wieder nach ein paar Sekunden (fuenf Fehlversuche in einer Sekunde von
            // derselben Stelle aus sagen nichts). Dazwischen woanders hinstellen: anderer Blickwinkel, anderer Weg.
            boolean waiting = false;
            for (BuildPlan.Target t : pending) {
                if (t.pos().getY() == layer && !missing.contains(t.need().item()) && !coversPending(level, t)
                        && !waiting(t.pos()) && !needsChange.contains(t.pos()) && !orderWaits(level, t)
                        && attempts.getOrDefault(t.pos(), 0) < LAYER_TRIES) {
                    if (tick - lastTry.getOrDefault(t.pos(), -100000) < STRICT_RETRY_TICKS) {
                        waiting = true;
                        continue;
                    }
                    if (unsupportedFalling(level, t)) {
                        postpone(t.pos(), 20); // Sand/Kies ohne Halt: faellt sonst herunter
                        continue;
                    }
                    layerTargets.add(t);
                }
            }
            if (layerTargets.isEmpty() && waiting) {
                lastActivity = tick; // bewusstes Abwarten, kein Haengen
                if (tick - lastReposition > 60 && reposition(player, level, layer)) {
                    return;
                }
                status += " (retrying soon)";
                wait = delay(8, 16);
                return;
            }
        }
        if (!layerTargets.isEmpty()) {
            waitingLayer = Integer.MIN_VALUE;
            if (tryBuild(player, level, layerTargets)) {
                return;
            }
        }
        if (strict) {
            status += " (retrying)";
            wait = delay(6, 12); // Wegsuche durch Bloecke ist nur alle paar Ticks erlaubt - nicht zu schnell nochmal
            return;
        }
        // Auf dieser Schicht geht gerade nichts (alles zurueckgestellt oder eben gescheitert). Statt herumzustehen:
        // schon mal an den Schichten darueber weiterbauen, soweit dort etwas Halt hat.
        List<BuildPlan.Target> ahead = new ArrayList<>();
        for (BuildPlan.Target t : pending) {
            int y = t.pos().getY();
            if (y > layer && y <= layer + 3 && !deferred(t.pos()) && !missing.contains(t.need().item())
                    && countInventory(player, t.need().item()) > 0 && hasClickableNeighbor(level, t.pos())
                    && !coversPending(level, t) && !unsupportedFalling(level, t) && !waiting(t.pos())
                    && !needsChange.contains(t.pos()) && !orderWaits(level, t)) {
                ahead.add(t);
            }
        }
        if (!ahead.isEmpty() && tryBuild(player, level, ahead)) {
            return;
        }
        if (!layerTargets.isEmpty()) {
            wait = 2; // eben erst zurueckgestellt - gleich neu waehlen
            return;
        }
        // Wirklich nichts zu tun: kurz warten, dann die Schicht fuer diese Runde abhaken
        if (waitingLayer != layer) {
            waitingLayer = layer;
            waitingSince = tick;
        }
        int blocked = 0;
        for (BuildPlan.Target t : pending) {
            if (t.pos().getY() == layer && !missing.contains(t.need().item())) {
                blocked++;
            }
        }
        if (tick - waitingSince > 100) {
            int skipped = skipLayer(layer);
            NovaNotify.chat("Schematic Builder", "§7Layer " + (layer - plan.origin.getY() + 1) + ": " + skipped
                    + " block(s) don't work yet - moving on" + (phase == Phase.BUILD ? ", retrying them at the end." : "."));
            return;
        }
        status += " (waiting for " + blocked + " blocked)";
        failStreak++;
        wait = delay(5, 10);
    }

    private record Candidate(BuildPlan.Target target, Placer.Aim aim, double score) {
    }

    /** Eins dieser Ziele anfangen: von hier aus setzen oder hinlaufen. false = gerade keins moeglich. */
    private boolean tryBuild(LocalPlayer player, ClientLevel level, List<BuildPlan.Target> targets) {
        Vec3 eye = player.getEyePosition();
        BlockPos feet = feetNode(player);
        // Naechste zuerst - aber Bloecke, auf die andere warten oder die nur wenige Standplaetze haben, vorziehen
        Map<BuildPlan.Target, Double> order = new HashMap<>();
        for (BuildPlan.Target t : targets) {
            // Bloecke ohne Nachbarn zum Anklicken (schwebende Teile) brauchen einen Hilfsturm: erst die, die an Gebautem
            // haengen - so waechst die Struktur zusammenhaengend weiter statt Insel fuer Insel mit 7 Bloecke hohen Tuermen
            order.put(t, t.pos().distToCenterSqr(eye.x, eye.y, eye.z) - priority(t) * 4.0D
                    + (hasClickableNeighbor(level, t.pos()) ? 0.0D : 60.0D));
        }
        targets.sort(Comparator.comparingDouble(order::get));

        // 1) Etwas, das man von hier aus setzen kann: das mit der kleinsten Kopfbewegung
        List<Candidate> here = new ArrayList<>();
        int tested = 0;
        double reach = Placer.reach(player) + 0.6D;
        for (BuildPlan.Target t : targets) {
            if (tested >= 28) {
                break;
            }
            if (eye.distanceToSqr(Vec3.atCenterOf(t.pos())) > reach * reach) {
                break;
            }
            if (countInventory(player, t.need().item()) <= 0) {
                continue;
            }
            tested++;
            BlockState have = level.getBlockState(t.pos());
            if (!have.isAir() && !have.canBeReplaced()) {
                continue; // muss erst weg / ist schon (teilweise) da - wird beim Setzen behandelt
            }
            if (blocksMe(player, level, t)) {
                continue; // man steht selbst im Weg - von woanders setzen
            }
            Placer.Aim a = placer.planPlacement(level, player, eye, t.pos(), t.state(), new ItemStack(t.need().item()),
                    strict(t.pos()));
            if (a == null) {
                continue;
            }
            double score = Math.abs(Mth.wrapDegrees(a.yaw() - player.getYRot())) + Math.abs(a.pitch() - player.getXRot())
                    + Math.sqrt(eye.distanceToSqr(a.hit())) * 4.0D + random.nextDouble() * 12.0D - priority(t);
            here.add(new Candidate(t, a, score));
        }
        // Die besten zuerst; teure Pruefung (sperrt ein / schneidet Wege ab?) nur fuer die vordersten
        here.sort(Comparator.comparingDouble(Candidate::score));
        int checks = 0;
        boolean later = false;
        for (Candidate c : here) {
            if (++checks > 8) {
                break;
            }
            if (wouldTrap(level, player, feet, c.target().pos(), c.target().state()) || cutsOffWork(level, player, c.target())) {
                continue;
            }
            Hold h = hold(level, player, c.target());
            if (h != Hold.GO) {
                later |= h == Hold.LATER;
                continue;
            }
            target = c.target();
            targetIsScaffold = false;
            currentAim = c.aim();
            go(Step.SELECT);
            return true;
        }

        if (later) {
            // Der Waechter rechnet noch (Zeitbudget je Tick) - einen Tick spaeter entscheiden statt woanders hinzulaufen
            status = "planning";
            wait = 1;
            lastActivity = tick;
            return true;
        }
        // 2) Naechster Block (mit etwas Zufall), zu dem man hinlaufen muss. Material fehlt im Inventar -> erst holen
        BuildPlan.Target first = targets.get(0);
        if (countInventory(player, first.need().item()) <= 0) {
            beginRestock(player, level, first.need().item());
            return true;
        }
        int tries = 0;
        for (BuildPlan.Target t : pickSome(targets, 6)) {
            if (countInventory(player, t.need().item()) <= 0) {
                continue;
            }
            if (++tries > 4) {
                break;
            }
            if (startPlacing(player, level, t, true)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aktuelle Schicht: bleibt, solange es dort noch etwas gibt, das nicht schon zweimal gescheitert ist. Sonst geht
     * es zur naechsten Schicht darueber. MAX_VALUE = nichts mehr zu tun in dieser Runde.
     */
    private int pickLayer() {
        int layer = currentLayer;
        boolean rewound = false;
        while (true) {
            boolean workable = false;
            int next = Integer.MAX_VALUE;
            for (BuildPlan.Target t : pending) {
                int y = t.pos().getY();
                if (y < layer || y > layer && y >= next) {
                    continue; // aendert nichts am Ergebnis - die teuren Pruefungen sparen
                }
                if (missing.contains(t.need().item()) || mc.level != null && coversPending(mc.level, t)
                        || waiting(t.pos()) || needsChange.contains(t.pos())
                        || mc.level != null && orderWaits(mc.level, t)) {
                    continue; // wartet auf einen anderen Block / eine Aenderung daneben - haelt die Schicht nicht auf
                }
                if (y == layer) {
                    int tries = attempts.getOrDefault(t.pos(), 0);
                    // Schicht-Modus: erst weiter, wenn jeder offene Block hier mehrmals gescheitert ist
                    if (settings.layerByLayer ? tries < LAYER_TRIES : !deferred(t.pos()) || tries < 2) {
                        workable = true;
                        break;
                    }
                } else if (y > layer) {
                    next = Math.min(next, y);
                }
            }
            if (workable) {
                currentLayer = layer;
                return layer;
            }
            if (freedLayer < layer) {
                // Weiter unten durfte eben etwas (Redstone-Reihenfolge): erst dort weiter, dann wieder hoch
                layer = freedLayer;
                freedLayer = Integer.MAX_VALUE;
                continue;
            }
            if (next == Integer.MAX_VALUE) {
                // Oben angekommen: was unten inzwischen darf, kommt noch vor dem Wasser dran (einmal je Aufruf)
                int low = rewound || mc.level == null ? Integer.MAX_VALUE : lowestFreed(mc.level, layer + 1);
                if (low < Integer.MAX_VALUE) {
                    rewound = true;
                    layer = low;
                    continue;
                }
                return Integer.MAX_VALUE;
            }
            layer = next;
        }
    }

    /** Ein paar Kandidaten: die naechsten, aber leicht durchgemischt. */
    private List<BuildPlan.Target> pickSome(List<BuildPlan.Target> sorted, int n) {
        List<BuildPlan.Target> out = new ArrayList<>(sorted.subList(0, Math.min(sorted.size(), n + 2)));
        for (int i = 0; i < out.size() - 1; i++) {
            if (random.nextFloat() < 0.35F) {
                BuildPlan.Target tmp = out.get(i);
                out.set(i, out.get(i + 1));
                out.set(i + 1, tmp);
            }
        }
        return out.subList(0, Math.min(n, out.size()));
    }

    /** Ziel ansteuern: von hier setzen oder einen Standplatz suchen und hinlaufen. false = geht gerade nicht. */
    private boolean startPlacing(LocalPlayer player, ClientLevel level, BuildPlan.Target t, boolean deferOnFail) {
        target = t;
        targetIsScaffold = false;
        BlockState have = level.getBlockState(t.pos());
        if (!have.isAir() && !have.canBeReplaced()) {
            if (Placer.orientationMatches(have, t.state(), false) || Placer.toolConvertible(have, t.state())) {
                // Block ist da, braucht nur noch Korrektur/zweites Setzen/Hacke bzw. Schaufel
                go(Step.VERIFY);
                return true;
            }
            if (!have.getFluidState().isEmpty() && have.getCollisionShape(level, t.pos()).isEmpty()) {
                // Wasser im Weg: einfach hineinsetzen
            } else if (have.getDestroySpeed(level, t.pos()) < 0.0F) {
                // Bedrock o.Ae. - geht nicht weg
                givenUp.add(t.pos());
                target = null;
                return false;
            } else {
                startMining(player, level, t.pos(), t);
                return true;
            }
        }
        if (unsupportedFalling(level, t)) {
            // Sand, Kies, Betonpulver, Amboss ... ohne Halt darunter wuerde herunterfallen
            FarmLog.line("NO SUPPORT " + t.pos().toShortString() + " " + t.state().getBlock().getName().getString());
            if (deferOnFail) {
                postpone(t.pos(), 100);
            }
            target = null;
            return false;
        }
        if (countInventory(player, t.need().item()) <= 0) {
            beginRestock(player, level, t.need().item());
            return true;
        }
        if (cutsOffWork(level, player, t)) {
            // Wuerde den Weg zu Stellen versperren, an denen noch gebaut werden muss - spaeter setzen
            if (deferOnFail) {
                postpone(t.pos(), 80);
            }
            target = null;
            return false;
        }
        // Nimmt der Block einem anderen offenen Block den letzten Standplatz? Dann kommt er erst danach.
        Hold h = targetIsScaffold ? Hold.GO : hold(level, player, t);
        if (h == Hold.LATER) {
            target = null;
            status = "planning";
            wait = 1;
            lastActivity = tick;
            return true;
        }
        if (h == Hold.WAIT) {
            target = null;
            return false;
        }
        BlockPos feetHere = feetNode(player);
        Placer.Aim here = blocksMe(player, level, t) || tooLowInside(feetHere, t) ? null : placer.planPlacement(level, player,
                player.getEyePosition(), t.pos(), t.state(), new ItemStack(t.need().item()), strict(t.pos()));
        if (here != null && !wouldTrap(level, player, feetHere, t.pos(), t.state())) {
            currentAim = here;
            go(Step.SELECT);
            return true;
        }
        // Gar kein Nachbar zum Dagegenbauen: Hilfsblock darunter/daneben
        if (!hasClickableNeighbor(level, t.pos())) {
            if (startSupport(player, level, t)) {
                return true;
            }
        }
        if (findStandAndWalk(player, level, t.pos(), Goal.PLACE, t)) {
            return true;
        }
        // Von keinem Platz aus moeglich: erst einen fehlenden Nachbarn schaffen (Plan-Block vorziehen oder Hilfsblock),
        // erst danach Bloecke im Weg abbauen
        boolean noStand = lastValidStands == 0;
        if (noStand && unstick(player, level, t)) {
            return true;
        }
        // Schwebt frei, weit weg von allem Festen: ueber einen eigenen Steg hin und von dessen Kante aus setzen
        if (noStand && floatingStart(player, level, t)) {
            return true;
        }
        if (clearView(player, level, t)) {
            return true;
        }
        FarmLog.line("NO STAND " + t.pos().toShortString() + " " + t.state().getBlock().getName().getString()
                + " attempts=" + attempts.getOrDefault(t.pos(), 0) + " phase=" + phase + " " + lastStandInfo
                + " neighbor=" + hasClickableNeighbor(level, t.pos()));
        if (deferOnFail) {
            postpone(t.pos(), 60);
        }
        target = null;
        return false;
    }

    /**
     * Ein Hebel, der richtig steht, aber noch umgelegt werden muss. Das passiert erst ganz am Ende - sonst bekommen
     * Werfer, Kolben, Trichter ... mitten im Bau Strom und schiessen/schieben los.
     */
    private static boolean switchLater(BlockState have, BlockState want) {
        return want.getBlock() instanceof LeverBlock && have.is(want.getBlock())
                && Placer.orientationMatches(have, want, false)
                && Placer.fixup(have, want) == BlockStateProperties.POWERED;
    }

    /** Kommen Wasser und Lava noch? Dann bleiben Hebel aus - sonst laeuft die Farm schon beim Befuellen. */
    private boolean beforeFluids() {
        return phase.ordinal() <= Phase.BUILD.ordinal() && plan.fluidCount > 0;
    }

    /** Gibt es ausser pos noch etwas zu bauen (Hebel, die auf das Ende warten, zaehlen nicht)? */
    private boolean hasOtherWork(ClientLevel level, BlockPos pos) {
        for (BuildPlan.Target t : pending) {
            if (!t.pos().equals(pos) && !switchLater(level.getBlockState(t.pos()), t.state())) {
                return true;
            }
        }
        return false;
    }

    /** Faellt der Block (Sand, Kies, Betonpulver, Amboss ...) herunter, weil darunter noch nichts ist? */
    private static boolean unsupportedFalling(ClientLevel level, BuildPlan.Target t) {
        return t.state().getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(t.pos().below()));
    }

    /**
     * Wuerde t einen noch fehlenden Block direkt darunter zudecken (z.B. Stufe auf einen Trichter, der noch fehlt)?
     * Dann erst den unteren setzen - sonst kommt man an ihn spaeter nicht mehr heran. In den letzten Runden nur so
     * lange, wie der untere noch eine Chance hat (sonst bliebe der obere ewig liegen).
     */
    private boolean coversPending(ClientLevel level, BuildPlan.Target t) {
        if (closesInline(level, t)) {
            return true; // nimmt einer Quelle/nassem Laub daneben die letzte offene Seite
        }
        if (isTempCell(t.pos())) {
            return true; // dort steht gerade ein Hilfsblock fuer einen anderen Block - erst danach
        }
        if (overheadNeighborWaits(level, t) || blocksFluidStand(level, t)) {
            return true;
        }
        BlockPos below = t.pos().below();
        BuildPlan.Target under = pendingAt.get(below);
        if (under == null || missing.contains(under.need().item()) || givenUp.contains(below)) {
            return false;
        }
        if (orderBlocker(below) != null) {
            // Der untere wartet auf die Redstone-Reihenfolge (oft auf genau diesen): zudecken erlaubt - nimmt das ihm den
            // letzten Standplatz, haelt der Waechter an und der untere darf vorher
            return false;
        }
        if (phase == Phase.BUILD2 && (build2Rounds >= 3 || attempts.getOrDefault(below, 0) >= 2
                || clearTries.getOrDefault(below, 0) >= 3)) {
            return false;
        }
        return !Placer.done(level.getBlockState(below), under.state()) && hasClickableNeighbor(level, below);
    }

    /**
     * Letzte Runden: Der Block laesst sich von keinem Platz aus sehen, weil schon andere Bloecke davor oder darueber
     * sind (z.B. Trichter unter einer Stufe). Dann den stoerenden Block abbauen, diesen setzen - der abgebaute kommt
     * danach ganz normal wieder hin (bei Gelaende: am Ende).
     */
    private boolean clearView(LocalPlayer player, ClientLevel level, BuildPlan.Target t) {
        BlockPos pos = t.pos();
        // Letzte Runden immer; beim normalen Bauen, sobald der Block zweimal nicht ging
        boolean allowed = phase == Phase.BUILD2 || phase == Phase.BUILD && attempts.getOrDefault(pos, 0) >= 1;
        if (!settings.digThrough || !allowed || clearTries.getOrDefault(pos, 0) >= 3 || lastValidStands > 0) {
            return false; // lastValidStands > 0: man sieht ihn von irgendwo - nur der Weg fehlt gerade (Durchgraben)
        }
        BlockPos feet = feetNode(player);
        double reach = Placer.reach(player);
        ItemStack stack = new ItemStack(t.need().item());
        List<BlockPos> stands = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos s = pos.offset(dx, dy, dz);
                    if (!Placer.intersectsPlayer(level, s, pos) && Pathfinder.standable(level, s) && !avoidStand(s)
                            && Placer.eyeAt(level, s).distanceToSqr(Vec3.atCenterOf(pos)) <= (reach + 0.4D) * (reach + 0.4D)) {
                        stands.add(s.immutable());
                    }
                }
            }
        }
        stands.sort(Comparator.comparingDouble(s -> s.distSqr(feet)));
        Map<BlockPos, List<BlockPos>> blockers = new HashMap<>();
        int fewest = Integer.MAX_VALUE;
        int checked = 0;
        for (BlockPos s : stands) {
            if (++checked > 120) {
                break;
            }
            Placer.Through th = placer.planPlacementThrough(level, player, Placer.eyeAt(level, s), pos, t.state(), stack,
                    strict(pos), c -> !c.equals(s.below()) && canBreakThrough(c), 2);
            if (th == null || wouldTrap(level, player, s, pos, t.state()) || !canLeave(level, player, s)) {
                continue; // nie von einem Platz ohne Ausgang aus
            }
            blockers.put(s, th.obstacles());
            fewest = Math.min(fewest, th.obstacles().size());
        }
        if (blockers.isEmpty()) {
            FarmLog.line("CLEARVIEW none " + pos.toShortString() + " (" + stands.size() + " stands)");
            return makeRoom(player, level, t);
        }
        Set<BlockPos> best = new HashSet<>();
        for (Map.Entry<BlockPos, List<BlockPos>> e : blockers.entrySet()) {
            if (e.getValue().size() == fewest) {
                best.add(e.getKey());
            }
        }
        // Kommt man an einen dieser Plaetze hin (notfalls durch Bloecke hindurch)?
        Pathfinder.Options options = pathOptions(player);
        Pathfinder.Path way = Pathfinder.find(level, feet, best::contains, pos, PATH_NODES, options);
        if (way == null) {
            way = Pathfinder.find(level, feet, best::contains, pos, PATH_NODES, breakOptions(options));
        }
        if (way == null) {
            FarmLog.line("CLEARVIEW unreachable " + pos.toShortString() + " stands=" + best);
            return makeRoom(player, level, t);
        }
        BlockPos block = blockers.get(way.end()).get(0);
        clearTries.merge(pos, 1, Integer::sum);
        FarmLog.line("CLEARVIEW " + pos.toShortString() + " " + t.state().getBlock().getName().getString()
                + ": break " + block.toShortString() + " " + level.getBlockState(block).getBlock().getName().getString()
                + " (stand " + way.end().toShortString() + ", try " + clearTries.get(pos) + ")");
        NovaNotify.chat("Schematic Builder", "§7Can't reach " + t.state().getBlock().getName().getString() + " at "
                + pos.toShortString() + " - removing a block in the way, will put it back.");
        rememberBroken(level, block);
        startMining(player, level, block, t);
        return step != Step.CHOOSE || minePos != null;
    }

    /** Ein Standplatz-Vorschlag fuer "Platz schaffen": diese Bloecke weg, dann geht t von stand aus. */
    private record RoomOption(BlockPos stand, List<BlockPos> cells, double cost) {
    }

    /**
     * Platz schaffen (letzte Ausweg-Stufe nach clearView): t geht nur von einem Platz aus, auf dem gerade ein anderer
     * Block steht - Fuesse oder Kopf (z.B. eine Stufe, die den Boden 0.5 zu hoch macht). In der gedachten Welt wird
     * durchgerechnet: diese 1-2 Bloecke weg -> t ist von dort richtig ausgerichtet setzbar, der Platz ist erreichbar,
     * man kommt mit t an seinem Platz wieder heraus, UND jeder abgebaute Farm-Block laesst sich danach von einem Platz
     * mit Ausgang aus wieder setzen. Nur wenn alles stimmt, wird abgebaut - so entsteht keine Schleife.
     */
    private boolean makeRoom(LocalPlayer player, ClientLevel level, BuildPlan.Target t) {
        BlockPos pos = t.pos();
        BlockPos feet = feetNode(player);
        double reach = Placer.reach(player) + 0.4D;
        ItemStack stack = new ItemStack(t.need().item());
        List<RoomOption> options = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos s = pos.offset(dx, dy, dz);
                    if (s.equals(pos) || s.above().equals(pos) || avoidStand(s) || !Pathfinder.supports(level, s.below())) {
                        continue;
                    }
                    List<BlockPos> cells = new ArrayList<>();
                    boolean ok = true;
                    for (BlockPos c : new BlockPos[]{s.above(), s}) {
                        if (Pathfinder.passable(level, c)) {
                            continue;
                        }
                        if (!scaffolds.contains(c) && !canBreakThrough(c)) {
                            ok = false;
                            break;
                        }
                        cells.add(c.immutable());
                    }
                    if (ok && !cells.isEmpty()) {
                        options.add(new RoomOption(s.immutable(), cells, cells.size() * 10.0D + Math.sqrt(s.distSqr(feet))));
                    }
                }
            }
        }
        options.sort(Comparator.comparingDouble(RoomOption::cost));
        int evaluated = 0;
        for (RoomOption o : options) {
            if (++evaluated > 24) {
                break;
            }
            Map<BlockPos, BlockState> air = new HashMap<>();
            for (BlockPos c : o.cells()) {
                air.put(c, Blocks.AIR.defaultBlockState());
            }
            boolean works = WorldOverlay.with(air, () -> {
                BlockPos s = o.stand();
                if (!Pathfinder.standable(level, s) || Placer.intersectsPlayer(level, s, pos)) {
                    return false;
                }
                Vec3 eye = Placer.eyeAt(level, s);
                if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > reach * reach
                        || placer.planPlacement(level, player, eye, pos, t.state(), stack, false) == null) {
                    return false;
                }
                // Mit t an seinem Platz wieder herauskommen?
                if (!outside(s) && !WorldOverlay.with(Map.of(pos, t.state()), () -> canGetOut(level, s, escapeOptions(player)))) {
                    return false;
                }
                return Pathfinder.find(level, feet, s::equals, s, PATH_NODES, pathOptions(player)) != null;
            });
            if (!works) {
                continue;
            }
            // Lassen sich die abgebauten Farm-Bloecke danach wieder setzen (mit t an seinem Platz)?
            Map<BlockPos, BlockState> after = new HashMap<>(air);
            after.put(pos, t.state());
            boolean rebuildable = true;
            for (BlockPos c : o.cells()) {
                BuildPlan.Target ct = planTarget(c);
                if (ct == null || scaffolds.contains(c)) {
                    continue;
                }
                if (!WorldOverlay.with(after, () -> guard.placeable(level, player, ct))) {
                    rebuildable = false;
                    break;
                }
            }
            if (!rebuildable) {
                continue;
            }
            clearTries.merge(pos, 1, Integer::sum);
            FarmLog.line("MAKEROOM " + pos.toShortString() + " " + t.state().getBlock().getName().getString() + ": break "
                    + o.cells() + " to stand at " + o.stand().toShortString() + " (rebuilt afterwards)");
            NovaNotify.chat("Schematic Builder", "§7" + t.state().getBlock().getName().getString() + " at " + pos.toShortString()
                    + " only fits from one spot - clearing it, placing, then putting the block back.");
            for (BlockPos c : o.cells()) {
                rememberBroken(level, c);
            }
            startMining(player, level, o.cells().get(0), t);
            return step != Step.CHOOSE || minePos != null;
        }
        FarmLog.line("MAKEROOM none for " + pos.toShortString() + " (" + options.size() + " options)");
        return false;
    }

    /** Der Bau-Auftrag an pos (auch wenn er gerade fertig ist), sonst null. */
    private BuildPlan.Target planTarget(BlockPos pos) {
        BuildPlan.Target t = pendingAt.get(pos);
        if (t != null) {
            return t;
        }
        for (BuildPlan.Target other : plan.targets) {
            if (other.pos().equals(pos) && other.need().kind() == Materials.Kind.PLACE) {
                return other;
            }
        }
        return null;
    }

    private boolean strict(BlockPos pos) {
        return attempts.getOrDefault(pos, 0) < 2;
    }

    private void finishBuildPhase() {
        // Offene Crafter zuerst: sobald Wasser und Lava liegen, laeuft die Farm und Steine landen in falschen Feldern
        if (mc.player != null && mc.level != null && nextCrafter(mc.player, mc.level)) {
            return;
        }
        if (phase == Phase.BUILD) {
            enter(plan.fluidCount > 0 ? Phase.FLUIDS : Phase.BUILD2);
        } else {
            // Fehlt noch etwas und ging in dieser Runde etwas voran? Dann noch eine Runde - am Ende soll alles stehen
            refreshProgress();
            int left = 0;
            for (BuildPlan.Target t : pending) {
                if (!missing.contains(t.need().item())) {
                    left++;
                }
            }
            FarmLog.line("ROUND END phase=" + phase + " round=" + build2Rounds + " placed=" + roundPlaced + " left=" + left);
            // Mindestens 3 letzte Runden (ab Runde 3 darf auch ueber fehlende Bloecke gebaut und mehr freigeraeumt
            // werden), danach weiter, solange es vorangeht
            if (phase == Phase.BUILD2 && left > 0 && (roundPlaced > 0 || build2Rounds < 3)
                    && build2Rounds < MAX_BUILD2_ROUNDS) {
                NovaNotify.chat("Schematic Builder", "§7" + left + " block(s) still missing - another round.");
                enter(Phase.BUILD2);
                return;
            }
            if (!fluidRetry.isEmpty() && !finalFluids) {
                // Wasser/Lava, die vorhin nur wegen eines fehlenden Blocks nicht sicher waren: jetzt noch einmal
                finalFluids = true;
                FarmLog.line("FLUIDS again for " + fluidRetry.size() + " source(s) that waited for blocks");
                fluidRetry.clear();
                enter(Phase.FLUIDS);
                return;
            }
            enter(scaffolds.isEmpty() && restore.isEmpty() ? Phase.DONE : Phase.CLEANUP);
            if (phase == Phase.DONE) {
                finish();
            }
        }
    }

    private boolean allBlockedByMissing() {
        for (BuildPlan.Target t : pending) {
            if (!missing.contains(t.need().item())) {
                return false;
            }
        }
        return true;
    }

    private boolean deferred(BlockPos pos) {
        Integer until = deferredUntil.get(pos);
        if (until == null) {
            return false;
        }
        if (until <= tick) {
            deferredUntil.remove(pos);
            return false;
        }
        return true;
    }

    private void defer(BlockPos pos, int ticks) {
        deferredUntil.put(pos, tick + ticks);
    }

    // ---------------------------------------------------------------- Hilfsblock unter schwebenden Bloecken

    private static boolean hasClickableNeighbor(ClientLevel level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockState s = level.getBlockState(pos.relative(d));
            if (!s.isAir() && !s.canBeReplaced() && !s.getShape(level, pos.relative(d)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean startSupport(LocalPlayer player, ClientLevel level, BuildPlan.Target t) {
        Item scaffold = scaffoldItem(player);
        if (scaffold == null) {
            // Der Block schwebt (kein Nachbar zum Dagegenbauen): ohne Hilfsblock geht es nicht. Aus der Kiste holen
            // oder Bescheid sagen, statt ihn stumm zu ueberspringen.
            Item spare = bestScaffoldInChests();
            if (spare != null && tick - lastScaffoldRestock > 200) {
                lastScaffoldRestock = tick;
                scaffoldWanted = 16;
                FarmLog.line("SUPPORT needs helper blocks for " + t.pos().toShortString() + " - fetching " + nameOf(spare));
                beginRestock(player, level, spare);
                return step != Step.CHOOSE;
            }
            if (spare == null && !noScaffoldWarned) {
                noScaffoldWarned = true;
                FarmLog.line("SUPPORT impossible - no helper blocks for floating " + t.pos().toShortString());
                NovaNotify.chat("Schematic Builder", "§eNeed dirt or cobblestone as temporary helper blocks - "
                        + t.state().getBlock().getName().getString() + " at " + t.pos().toShortString()
                        + " floats in the air. Put some in a selected chest (it gets removed again afterwards).");
                NovaNotify.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 0.7F);
            }
            return false;
        }
        List<BlockPos> options = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (canScaffold(t.pos().relative(d))) {
                options.add(t.pos().relative(d));
            }
        }
        if (phase == Phase.BUILD2 || options.isEmpty() && attempts.getOrDefault(t.pos(), 0) >= 1) {
            options.add(t.pos().below()); // notfalls direkt darunter (kann spaeter schwerer abzubauen sein)
        }
        for (BlockPos n : options) {
            if (plan.stateAt(n) != null || !level.getBlockState(n).canBeReplaced() || !hasClickableNeighbor(level, n)) {
                continue;
            }
            BlockState state = ((BlockItem) scaffold).getBlock().defaultBlockState();
            BuildPlan.Target support = new BuildPlan.Target(n.immutable(), state,
                    new Materials.Need(scaffold, 1, Materials.Kind.PLACE), false);
            if (cutsOffWork(level, player, support)) {
                continue;
            }
            resumeTarget = t;
            target = support;
            targetIsScaffold = true;
            Placer.Aim here = blocksMe(player, level, target) ? null
                    : placer.planPlacement(level, player, player.getEyePosition(), n, state, new ItemStack(scaffold), false);
            if (here != null && !wouldTrap(level, player, feetNode(player), n, state)) {
                currentAim = here;
                go(Step.SELECT);
                return true;
            }
            if (findStandAndWalk(player, level, n, Goal.PLACE, target)) {
                return true;
            }
        }
        resumeTarget = null;
        target = t;
        targetIsScaffold = false;
        return false;
    }

    /**
     * t schwebt ganz frei, und keine Kette aus Hilfsbloecken reicht bis zu etwas Festem (z.B. ein Generator 5 Bloecke
     * ueber einem Wasserbecken). Wie ein Mensch: ueber einen eigenen Steg (Turm/Bruecke) bis neben t, dort geduckt an
     * der Kante einen Hilfsblock seitlich an den eigenen Bodenblock setzen (wie beim Brueckenbau) und t daran. Der Steg
     * bleibt bis zum Aufraeumen stehen - die naechsten Bloecke daneben nutzen ihn mit. true = ist dran.
     */
    private boolean floatingStart(LocalPlayer player, ClientLevel level, BuildPlan.Target t) {
        BlockPos pos = t.pos();
        if (phase != Phase.BUILD && phase != Phase.BUILD2 || !settings.useTowers || tick < floatNoWayUntil
                || hasClickableNeighbor(level, pos)) {
            return false;
        }
        if (!(scaffoldItem(player) instanceof BlockItem item)) {
            Item spare = bestScaffoldInChests();
            if (spare != null && tick - lastScaffoldRestock > 200) {
                lastScaffoldRestock = tick;
                scaffoldWanted = 128;
                FarmLog.line("FLOAT needs helper blocks for " + pos.toShortString() + " - fetching " + nameOf(spare));
                beginRestock(player, level, spare);
                return step != Step.CHOOSE;
            }
            return false;
        }
        BlockState helper = item.getBlock().defaultBlockState();
        ItemStack stack = new ItemStack(t.need().item());
        double limit = Placer.reach(player) - 0.35D;
        long deadline = System.nanoTime() + UNSTICK_NANOS;
        // Standplatz -> Hilfsblock, der von dort an der Kante an den Bodenblock kommt (darunter zuerst: t kommt obendrauf)
        Map<BlockPos, BlockPos> leanCell = new LinkedHashMap<>();
        List<BlockPos> cells = new ArrayList<>(List.of(pos.below()));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            cells.add(pos.relative(d));
        }
        for (BlockPos n : cells) {
            // Beim Hinauslehnen ragt er ueber n hinaus: dort muss in Fuss- und Kopfhoehe Platz sein
            if (!canSupportTemp(level, n) || !Pathfinder.passable(level, n.above())
                    || !Pathfinder.passable(level, n.above(2))) {
                continue;
            }
            for (Direction e : Direction.Plane.HORIZONTAL) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                BlockPos floor = n.relative(e);
                BlockPos s = floor.above();
                if (floor.equals(pos) || leanCell.containsKey(s) || Placer.intersectsPlayer(level, s, pos) || avoidStand(s)
                        || !Pathfinder.feetFree(level, s) || !Pathfinder.passable(level, s.above())) {
                    continue;
                }
                boolean solid = Pathfinder.supports(level, floor);
                if (!solid && !(Pathfinder.replaceable(level, floor) && canScaffold(floor))) {
                    continue;
                }
                Map<BlockPos, BlockState> overlay = new HashMap<>();
                overlay.put(n, helper);
                if (!solid) {
                    overlay.put(floor, helper);
                }
                boolean ok = WorldOverlay.with(overlay, () -> {
                    Vec3 eye = Placer.eyeAt(level, s);
                    Placer.Aim a = placer.planPlacement(level, player, eye, pos, t.state(), stack, strict(pos));
                    return a != null && a.hit().distanceTo(eye) <= limit;
                });
                if (ok) {
                    leanCell.put(s.immutable(), n.immutable());
                }
            }
        }
        if (leanCell.isEmpty()) {
            return false;
        }
        BlockPos feet = feetNode(player);
        long noPathKey = feet.asLong() * 31 + BlockPos.asLong(pos.getX() >> 3, pos.getY() >> 2, pos.getZ() >> 3) + 7;
        Integer noPathUntil = noPathCache.get(noPathKey);
        if (noPathUntil != null && noPathUntil > tick) {
            return false;
        }
        Pathfinder.Options options = pathOptions(player);
        Set<BlockPos> goals = leanCell.keySet();
        Pathfinder.Path found = Pathfinder.find(level, feet, goals::contains, pos, PATH_NODES, options);
        if (found == null) {
            found = straightBridge(level, feet, goals);
        }
        if (found == null) {
            found = Pathfinder.find(level, feet, goals::contains, pos, PATH_NODES * 3, options.anywhere());
        }
        if (found == null && pos.getY() - feet.getY() > 3) {
            found = ascentPath(level, player, feet, goals, pos, options);
        }
        if (found == null) {
            FarmLog.line("FLOAT no way to a stand next to " + pos.toShortString() + " (" + leanCell.size() + " stands)");
            if (noPathCache.size() > 256) {
                noPathCache.clear();
            }
            noPathCache.put(noPathKey, tick + 200);
            // Die Suchen sind teuer (bis ~0,3 s): nicht fuer jeden der vielen schwebenden Bloecke gleich die naechste
            floatNoWayUntil = tick + 100;
            return false;
        }
        BlockPos stand = found.end();
        BlockPos lean = leanCell.get(stand);
        List<BlockPos> nodes = new ArrayList<>(found.nodes());
        List<Pathfinder.Move> mv = new ArrayList<>(found.moves());
        nodes.add(lean.above()); // Brueckenschritt: setzt lean, ohne hinauszulaufen (leanOnly)
        mv.add(Pathfinder.Move.BRIDGE);
        Pathfinder.Path p = new Pathfinder.Path(nodes, mv);
        int needed = p.buildMoves();
        if (needed > scaffoldCount(player)) {
            Item spare = bestScaffoldInChests();
            if (spare != null && tick - lastScaffoldRestock > 600) {
                lastScaffoldRestock = tick;
                scaffoldWanted = Math.max(needed + 32, 128);
                beginRestock(player, level, spare);
                return true;
            }
            if (scaffoldCount(player) < needed) {
                return false;
            }
        }
        FarmLog.line("FLOAT " + pos.toShortString() + " " + t.state().getBlock().getName().getString() + ": stand "
                + stand.toShortString() + ", helper " + lean.toShortString() + " at the edge (" + needed + " helper blocks)");
        needsChange.remove(pos);
        target = t;
        targetIsScaffold = false;
        resumeTarget = null;
        beginWalk(p, Goal.PLACE);
        leanOnly = true;
        return true;
    }

    /**
     * Gerader Steg auf gleicher Hoehe von from zu einem der Ziele (erst in x, dann in z oder umgekehrt): laufen, wo Boden
     * ist, sonst Bruecke. Die normale Suche scheitert bei langen Bruecken im Inneren der Farm am Knoten-Limit (jeder
     * Hilfsblock dort ist teuer) - ein Mensch baut einfach geradeaus hinueber. null = so nicht.
     */
    private Pathfinder.Path straightBridge(ClientLevel level, BlockPos from, Set<BlockPos> goals) {
        List<BlockPos> sorted = new ArrayList<>(goals);
        sorted.sort(Comparator.comparingInt(g -> Math.abs(g.getX() - from.getX()) + Math.abs(g.getZ() - from.getZ())));
        for (BlockPos g : sorted) {
            if (g.getY() != from.getY() || Math.abs(g.getX() - from.getX()) + Math.abs(g.getZ() - from.getZ()) > 48) {
                continue;
            }
            for (int order = 0; order < 2; order++) {
                Pathfinder.Path p = lineBridge(level, from, g, order == 0);
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    private Pathfinder.Path lineBridge(ClientLevel level, BlockPos from, BlockPos to, boolean xFirst) {
        List<BlockPos> nodes = new ArrayList<>(List.of(from));
        List<Pathfinder.Move> moves = new ArrayList<>(List.of(Pathfinder.Move.START));
        BlockPos cur = from;
        while (!cur.equals(to)) {
            int dx = Integer.signum(to.getX() - cur.getX());
            int dz = Integer.signum(to.getZ() - cur.getZ());
            boolean stepX = xFirst ? dx != 0 : dz == 0;
            BlockPos next = stepX ? cur.offset(dx, 0, 0) : cur.offset(0, 0, dz);
            if (Pathfinder.standable(level, next)) {
                moves.add(Pathfinder.Move.WALK);
            } else if (Pathfinder.feetFree(level, next) && Pathfinder.passable(level, next.above())
                    && Pathfinder.replaceable(level, next.below()) && canScaffold(next.below())) {
                moves.add(Pathfinder.Move.BRIDGE);
            } else {
                return null;
            }
            nodes.add(next.immutable());
            cur = next;
        }
        return new Pathfinder.Path(nodes, moves);
    }

    // ---------------------------------------------------------------- Festgefahrene Bloecke: Nachbar schaffen

    /**
     * Ausweg, wenn t von keinem Platz aus geht, weil der Block fehlt, gegen den man klicken muesste: schwebende Bloecke,
     * aber auch Trichter, Kisten, Werfer ... die genau EINE bestimmte Seite brauchen. In einer gedachten Welt
     * ({@link WorldOverlay}) wird jeder der 6 Nachbarn durchgerechnet: Stuende dort ein Block - ginge t dann, richtig
     * ausgerichtet, von einem Platz in Reichweite? Ein passender Nachbar, der laut Vorlage sowieso kommt, wird zuerst
     * gebaut. Sonst eine kurze Kette aus Hilfsbloecken vom naechsten festen Block bis dorthin; Hilfsbloecke in der Farm
     * werden direkt nach t wieder abgebaut (sonst mauert der weitere Bau sie ein). Jeder Versuch nimmt einen anderen
     * Nachbarn. true = ist dran.
     */
    private boolean unstick(LocalPlayer player, ClientLevel level, BuildPlan.Target t) {
        if (unsticking || phase != Phase.BUILD && phase != Phase.BUILD2) {
            return false;
        }
        BlockPos pos = t.pos();
        // Ueber Kopf wird mit versetzten/geduckten Augen und feinerem Raster gerechnet - etwas mehr Zeit
        long deadline = System.nanoTime() + (Placer.needsLookUp(t.state()) ? UNSTICK_NANOS * 2 : UNSTICK_NANOS);
        Item scaffold = scaffoldItem(player);
        BlockState scaffoldState = scaffold instanceof BlockItem item ? item.getBlock().defaultBlockState() : null;
        Set<BlockPos> tried = triedAnchors.computeIfAbsent(pos.immutable(), k -> new HashSet<>());
        ItemStack stack = new ItemStack(t.need().item());
        BuildPlan.Target bestPlan = null;
        List<BlockPos> bestChain = null;
        BlockPos bestAnchor = null;
        double bestCost = Double.MAX_VALUE;
        int candidates = 0;
        boolean helpersWanted = false;
        // Erst Nachbarn laut Vorlage (die kommen sowieso), dann unten, Seiten, oben
        List<Direction> order = new ArrayList<>(List.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST,
                Direction.EAST, Direction.UP));
        order.sort(Comparator.comparingInt(d -> pendingAt.containsKey(pos.relative(d)) ? 0 : 1));
        // Ueber Kopf (Crafter/Kolben nach unten ...): steiler Blick nach oben gegen die Unterseite des Blocks darueber -
        // dort voruebergehend ein Hilfsblock, auch wenn spaeter ein Farm-Block hinkommt (der braucht oft erst diesen)
        boolean overhead = Placer.needsLookUp(t.state());
        if (overhead) {
            order.remove(Direction.UP);
            order.add(0, Direction.UP);
        }
        for (Direction d : order) {
            if (System.nanoTime() > deadline) {
                break;
            }
            BlockPos n = pos.relative(d).immutable();
            BlockState here = level.getBlockState(n);
            if (tried.contains(n) || !here.canBeReplaced() || !here.getFluidState().isEmpty()) {
                continue;
            }
            BuildPlan.Target planned = pendingAt.get(n);
            if (overhead && d == Direction.UP) {
                planned = null; // Hilfsblock statt Farm-Block (der entstuende hier selten richtig herum)
            }
            Map<BlockPos, BlockState> overlay = new HashMap<>();
            List<BlockPos> chain = null;
            if (planned != null) {
                if (missing.contains(planned.need().item()) || givenUp.contains(n) || needsChange.contains(n)
                        || deferred(n) || !hasClickableNeighbor(level, n) || coversPending(level, planned)
                        || orderWaits(level, planned)) {
                    // Der Plan-Nachbar geht gerade nicht oder MUSS warten (z.B. Werfer neben einem Crafter, der nur
                    // von dessen Kopfplatz aus geht) - frueher wurde er hier vorgezogen und nahm den Platz weg
                    continue;
                }
                overlay.put(n, planned.state());
            } else {
                if (!canSupportTemp(level, n) && !(overhead && d == Direction.UP && canOverheadTemp(level, n))) {
                    continue;
                }
                if (scaffoldState == null) {
                    helpersWanted = true;
                    continue;
                }
                chain = supportChain(level, n, overhead && d == Direction.UP, pos); // nie durch das Zielfeld selbst
                if (chain == null || chain.size() > scaffoldCount(player)) {
                    continue;
                }
                // Gerechnet wird mit der GANZEN Kette - sie darf weder Standplatz noch Blick versperren
                for (BlockPos c : chain) {
                    overlay.put(c, scaffoldState);
                }
            }
            candidates++;
            Direction toTarget = d.getOpposite();
            boolean works = WorldOverlay.with(overlay, () -> anyStandWorks(level, player, t, stack, n, toTarget, deadline));
            if (!works) {
                if (System.nanoTime() > deadline) {
                    break; // Rechenzeit vorbei, bevor es fertig war: kein Urteil - naechstes Mal weiterrechnen
                }
                tried.add(n); // mit Block dort geht es auch nicht - naechstes Mal nicht wieder rechnen
                continue;
            }
            if (planned != null) {
                double cost = Math.sqrt(n.distSqr(feetNode(player))) * 0.1D;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestPlan = planned;
                    bestChain = null;
                    bestAnchor = n;
                }
                continue;
            }
            int inside = 0;
            for (BlockPos c : chain) {
                if (plan.contains(c)) {
                    inside++;
                }
            }
            double cost = 2.0D + chain.size() * 3.0D + inside;
            if (cost < bestCost) {
                bestCost = cost;
                bestPlan = null;
                bestChain = chain;
                bestAnchor = n;
            }
        }
        if (bestAnchor == null) {
            if (helpersWanted && candidates == 0) {
                // Ein Hilfsblock koennte helfen, aber es sind keine im Inventar: aus der Kiste holen statt aufgeben
                Item spare = bestScaffoldInChests();
                if (spare != null && tick - lastScaffoldRestock > 200) {
                    lastScaffoldRestock = tick;
                    scaffoldWanted = 16;
                    FarmLog.line("UNSTICK needs helper blocks for " + pos.toShortString() + " - fetching " + nameOf(spare));
                    beginRestock(player, level, spare);
                    return step != Step.CHOOSE;
                }
            }
            if (candidates == 0 || tried.size() >= 6) {
                // Kein Nachbar hilft (oder alle schon probiert): warten, bis sich daneben etwas aendert
                tried.clear();
                needsChange.add(pos.immutable());
                FarmLog.line("UNSTICK none " + pos.toShortString() + " " + t.state().getBlock().getName().getString()
                        + " (candidates=" + candidates + ") - waiting for a change nearby");
            }
            return false;
        }
        tried.add(bestAnchor);
        if (bestPlan != null) {
            FarmLog.line("UNSTICK " + pos.toShortString() + " " + t.state().getBlock().getName().getString() + ": build "
                    + bestAnchor.toShortString() + " " + bestPlan.state().getBlock().getName().getString() + " first");
            resumeTarget = t;
            boolean started;
            unsticking = true; // der Plan-Nachbar darf nicht selbst wieder unstick starten (sonst verschachtelt)
            try {
                started = startPlacing(player, level, bestPlan, true);
            } finally {
                unsticking = false;
            }
            if (started) {
                return true;
            }
            resumeTarget = null;
            target = t;
            targetIsScaffold = false;
            return false;
        }
        FarmLog.line("UNSTICK " + pos.toShortString() + " " + t.state().getBlock().getName().getString() + ": "
                + bestChain.size() + " helper block(s) " + bestChain.get(0).toShortString() + " -> "
                + bestAnchor.toShortString());
        supportQueue.clear();
        supportQueue.addAll(bestChain);
        List<BlockPos> temps = new ArrayList<>();
        for (BlockPos c : bestChain) {
            if (plan.contains(c)) {
                temps.add(c);
            }
        }
        if (!temps.isEmpty()) {
            supportTemps.computeIfAbsent(pos.immutable(), k -> new ArrayList<>()).addAll(temps);
        }
        if (nextSupport(player, level, t)) {
            return true;
        }
        supportQueue.clear();
        return false;
    }

    /**
     * Gibt es (in der aktuellen, evtl. gedachten Welt) einen Platz in Reichweite, von dem aus t richtig gesetzt werden
     * kann? Nur Plaetze, von denen man die Seite des neuen Nachbarn ueberhaupt sieht - der Rest kann nicht helfen.
     */
    private boolean anyStandWorks(ClientLevel level, LocalPlayer player, BuildPlan.Target t, ItemStack stack,
                                  BlockPos anchor, Direction toTarget, long deadline) {
        BlockPos pos = t.pos();
        BlockPos feet = feetNode(player);
        double reach = Placer.reach(player);
        Pathfinder.Options options = pathOptions(player);
        boolean canBuild = options.pillar() || options.bridge();
        // Mitte der Seite des Nachbarn, die zu t zeigt, und ihre Richtung
        Vec3 faceCenter = Vec3.atCenterOf(anchor).add(toTarget.getStepX() * 0.5D, toTarget.getStepY() * 0.5D,
                toTarget.getStepZ() * 0.5D);
        List<BlockPos> stands = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -4; dy <= 2; dy++) {
                    BlockPos s = pos.offset(dx, dy, dz);
                    if (Placer.intersectsPlayer(level, s, pos) || avoidStand(s)) {
                        continue;
                    }
                    Vec3 eye = Placer.eyeAt(level, s);
                    if (eye.distanceToSqr(Vec3.atCenterOf(pos)) > (reach + 0.4D) * (reach + 0.4D)) {
                        continue;
                    }
                    Vec3 rel = eye.subtract(faceCenter);
                    if (rel.x * toTarget.getStepX() + rel.y * toTarget.getStepY() + rel.z * toTarget.getStepZ() <= 0.05D) {
                        continue; // sieht die Klickseite nicht
                    }
                    if (!Pathfinder.standable(level, s)) {
                        if (!canBuild || !Pathfinder.feetFree(level, s) || !Pathfinder.passable(level, s.above())) {
                            continue;
                        }
                        BlockPos below = s.below();
                        if (!Pathfinder.supports(level, below)
                                && !(Pathfinder.replaceable(level, below) && options.canScaffold().test(below))) {
                            continue;
                        }
                    }
                    stands.add(s.immutable());
                }
            }
        }
        boolean overhead = Placer.needsLookUp(t.state()) || Placer.needsSpot(t.state());
        if (overhead) {
            // Ueber Kopf ZUERST versetzt/geduckt rechnen (der wahrscheinliche Weg) - zuerst die Plaetze direkt daneben
            // eine Ebene tiefer. Frueher kam das erst nach 90 normalen Plaetzen, da war die Rechenzeit schon um.
            List<BlockPos> near = new ArrayList<>();
            for (BlockPos s : stands) {
                if (Placer.spotRange(s, pos)) {
                    near.add(s);
                }
            }
            near.sort(Comparator.comparingInt(s -> Math.abs(s.getY() - pos.getY() + 1) * 4
                    + Math.abs(s.getX() - pos.getX()) + Math.abs(s.getZ() - pos.getZ())));
            boolean crouch = Placer.needsLookUp(t.state());
            for (BlockPos s : near) {
                for (Vec3 eye : Placer.eyeSpots(level, s, crouch)) {
                    if (System.nanoTime() > deadline) {
                        return false;
                    }
                    Placer.Aim a = placer.planPlacement(level, player, eye, pos, t.state(), stack, strict(pos));
                    if (a != null && a.hit().distanceTo(eye) <= reach - 0.35D) {
                        return true;
                    }
                }
            }
        }
        stands.sort(Comparator.comparingDouble(s -> s.distSqr(feet)));
        int checked = 0;
        for (BlockPos s : stands) {
            if (++checked > 90 || System.nanoTime() > deadline) {
                break;
            }
            Vec3 eye = Placer.eyeAt(level, s);
            Placer.Aim a = placer.planPlacement(level, player, eye, pos, t.state(), stack, strict(pos), false);
            if (a != null && a.hit().distanceTo(eye) <= reach - 0.35D) {
                return true;
            }
        }
        return false;
    }

    /**
     * Kuerzeste Kette aus Hilfsblock-Zellen von einer Zelle neben einem festen Block bis anchor (inklusive), in
     * Setz-Reihenfolge. Bevorzugt nach unten (Saeule vom Boden), dann seitlich. null = keine (zu weit).
     */
    /**
     * planned = die Kette darf auch durch Felder laufen, wo spaeter ein Farm-Block hinkommt (nur fuer Ueber-Kopf-Bloecke:
     * z.B. Hilfsblock ueber einem Crafter, angelehnt an einen Hilfsblock an der Stelle des spaeteren Glases - alle
     * werden gleich nach dem Crafter wieder abgebaut).
     */
    private List<BlockPos> supportChain(ClientLevel level, BlockPos anchor, boolean planned, BlockPos exclude) {
        if (hasClickableNeighbor(level, anchor)) {
            return List.of(anchor);
        }
        Direction[] dirs = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        open.add(anchor);
        parent.put(anchor, null);
        depth.put(anchor, 1);
        while (!open.isEmpty()) {
            BlockPos cur = open.poll();
            int dist = depth.get(cur);
            if (dist >= MAX_CHAIN) {
                continue;
            }
            for (Direction d : dirs) {
                BlockPos next = cur.relative(d).immutable();
                if (parent.containsKey(next) || next.equals(exclude)
                        || !canSupportTemp(level, next) && !(planned && canOverheadTemp(level, next))) {
                    continue;
                }
                parent.put(next, cur);
                depth.put(next, dist + 1);
                if (hasClickableNeighbor(level, next)) {
                    // Gefunden: von next (liegt am festen Block) zurueck bis anchor
                    List<BlockPos> chain = new ArrayList<>();
                    for (BlockPos p = next; p != null; p = parent.get(p)) {
                        chain.add(p);
                    }
                    return chain;
                }
                open.add(next);
            }
        }
        return null;
    }

    /**
     * Darf hier fuer unstick ein Hilfsblock hin? Nur wo die Farm Luft hat (oder ausserhalb), nichts Festes und keine
     * Fluessigkeit ist und keine Kiste darunter (ginge nicht mehr auf). Anders als bei Tuermen auch IN der Farm unter
     * spaeteren Teilen - diese Hilfsbloecke werden ja direkt nach dem Zielblock wieder abgebaut.
     */
    private boolean canSupportTemp(ClientLevel level, BlockPos c) {
        if (plan.stateAt(c) != null || !inArea(c)) {
            return false;
        }
        BlockState state = level.getBlockState(c);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty() || ChestStore.isStorage(level, c.below())) {
            return false;
        }
        BlockState below = plan.stateAt(c.below());
        return below == null || !(below.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || below.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock);
    }

    /**
     * Hilfsblock direkt ueber einem Block, der nur ueber Kopf geht - auch auf einem Feld, wo spaeter ein Farm-Block
     * hinkommt (der Hilfsblock ist nach dem Setzen sofort wieder weg, erst dann kommt der Farm-Block).
     */
    private boolean canOverheadTemp(ClientLevel level, BlockPos c) {
        if (!inArea(c)) {
            return false;
        }
        BlockState state = level.getBlockState(c);
        return state.canBeReplaced() && state.getFluidState().isEmpty() && !ChestStore.isStorage(level, c.below());
    }

    /** Steht auf pos gerade ein Hilfsblock, den ein anderer Block noch braucht (Farm-Block dort erst danach)? */
    private boolean isTempCell(BlockPos pos) {
        if (supportTemps.isEmpty()) {
            return false;
        }
        for (List<BlockPos> cells : supportTemps.values()) {
            if (cells.contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** Naechsten Hilfsblock der Kette setzen (von hier aus oder hinlaufen). false = geht nicht, Kette abgebrochen. */
    private boolean nextSupport(LocalPlayer player, ClientLevel level, BuildPlan.Target forTarget) {
        Item scaffold = scaffoldItem(player);
        while (!supportQueue.isEmpty() && !level.getBlockState(supportQueue.peekFirst()).canBeReplaced()) {
            supportQueue.pollFirst(); // steht schon etwas (z.B. ein frueherer Versuch)
        }
        BlockPos n = supportQueue.peekFirst();
        if (!(scaffold instanceof BlockItem item) || n == null) {
            supportQueue.clear();
            return false;
        }
        BlockState state = item.getBlock().defaultBlockState();
        BuildPlan.Target support = new BuildPlan.Target(n, state, new Materials.Need(scaffold, 1, Materials.Kind.PLACE), false);
        resumeTarget = forTarget;
        target = support;
        targetIsScaffold = true;
        Placer.Aim here = blocksMe(player, level, support) ? null
                : placer.planPlacement(level, player, player.getEyePosition(), n, state, new ItemStack(scaffold), false);
        if (here != null && !wouldTrap(level, player, feetNode(player), n, state)) {
            currentAim = here;
            go(Step.SELECT);
            return true;
        }
        if (findStandAndWalk(player, level, n, Goal.PLACE, support)) {
            return true;
        }
        FarmLog.line("UNSTICK helper " + n.toShortString() + " not reachable - " + lastStandInfo);
        supportQueue.clear();
        resumeTarget = null;
        target = forTarget;
        targetIsScaffold = false;
        return false;
    }

    /**
     * Kann ein unstick-Hilfsblock weg, ohne etwas kaputt zu machen? Nichts daneben darf ohne ihn abfallen (Fackel,
     * Redstone, Knopf ...), nichts Fallendes darauf liegen.
     */
    private boolean safeToRemoveTemp(ClientLevel level, BlockPos r) {
        Map<BlockPos, BlockState> air = Map.of(r, Blocks.AIR.defaultBlockState());
        for (Direction d : Direction.values()) {
            BlockPos nb = r.relative(d);
            BlockState ns = level.getBlockState(nb);
            if (ns.isAir()) {
                continue;
            }
            if (d == Direction.UP && ns.getBlock() instanceof FallingBlock) {
                return false;
            }
            if (!WorldOverlay.with(air, () -> ns.canSurvive(level, nb))) {
                return false;
            }
        }
        return true;
    }

    /** Ein Block hat sich geaendert: Bloecke daneben, die auf eine Aenderung warten, wieder versuchen. */
    private void neighborChanged(BlockPos pos) {
        if (!needsChange.isEmpty()) {
            needsChange.removeIf(p -> Math.abs(p.getX() - pos.getX()) <= 2 && Math.abs(p.getY() - pos.getY()) <= 2
                    && Math.abs(p.getZ() - pos.getZ()) <= 2);
        }
    }

    // ---------------------------------------------------------------- Standplatz + Weg

    /**
     * Sucht einen Platz in Reichweite, von dem aus die Aufgabe klappt, und einen Weg dorthin. Wenn es keinen Boden
     * gibt (hohe oder schwebende Teile), darf der Weg Tuerme und Bruecken aus Hilfsbloecken bauen.
     */
    private boolean findStandAndWalk(LocalPlayer player, ClientLevel level, BlockPos pos, Goal what, BuildPlan.Target t) {
        BlockPos feet = feetNode(player);
        double reach = Placer.reach(player);
        Pathfinder.Options options = pathOptions(player);
        if (what == Goal.CHEST && scaffoldCount(player) == 0) {
            // Ohne Hilfsbloecke zur Kiste: nur Wege ohne Bauen (die Erde dafuer liegt ja erst in der Kiste) - sonst galten
            // alle Kisten als unerreichbar, weil jeder gefundene Weg einen Turm brauchte
            options = options.withoutBuilding();
        }
        boolean canBuild = options.pillar() || options.bridge();
        // Plaetze, an denen es fuer genau diesen Block vor Ort schon einmal nicht ging, bzw. alte Fallen: nie wieder
        Set<BlockPos> bad = what == Goal.PLACE ? badStands.get(pos) : null;
        List<BlockPos> stands = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                // Auch etwas hoeher/tiefer als frueher: manche Bloecke gehen nur mit Blick steil von oben oder unten
                for (int dy = -4; dy <= 2; dy++) {
                    BlockPos s = pos.offset(dx, dy, dz);
                    if (Placer.intersectsPlayer(level, s, pos) || avoidStand(s) || bad != null && bad.contains(s)) {
                        continue;
                    }
                    if (what == Goal.PLACE && t != null && tooLowInside(s, t)) {
                        continue;
                    }
                    if ((what == Goal.MINE || what == Goal.CLICK) && s.below().equals(pos) && unsafeDrop(level, pos)) {
                        continue; // wuerde sich selbst den Boden wegnehmen
                    }
                    if (Placer.eyeAt(level, s).distanceToSqr(Vec3.atCenterOf(pos)) > (reach + 0.4D) * (reach + 0.4D)) {
                        continue;
                    }
                    if (!Pathfinder.standable(level, s)) {
                        // Noch kein Boden: nur wenn der Bot ihn sich bauen darf
                        if (!canBuild || !Pathfinder.feetFree(level, s) || !Pathfinder.passable(level, s.above())) {
                            continue;
                        }
                        BlockPos below = s.below();
                        if (!Pathfinder.supports(level, below)
                                && !(Pathfinder.replaceable(level, below) && options.canScaffold().test(below))) {
                            continue;
                        }
                    }
                    stands.add(s.immutable());
                }
            }
        }
        lastValidStands = 0;
        lastStandInfo = "feet=" + feet.toShortString() + " stands=0";
        if (stands.isEmpty()) {
            return false;
        }
        // Plaetze, die er von hier aus wirklich erreicht, zuerst (sonst fuellen Plaetze oben auf der Farm, von denen
        // man den Block zwar saehe, auf die man aber nicht hinaufkommt, die Auswahl). Dann Naehe, echter Boden, Zufall.
        Set<BlockPos> reachable = reachableNow(level, player);
        Map<BlockPos, Double> score = new HashMap<>();
        for (BlockPos s : stands) {
            double d = Math.sqrt(s.distSqr(feet)) + Math.abs(s.getY() - feet.getY()) * 0.8D
                    + Math.abs(Math.sqrt(Placer.eyeAt(level, s).distanceToSqr(Vec3.atCenterOf(pos))) - 3.0D) * 0.6D
                    + (Pathfinder.standable(level, s) ? 0.0D : 4.0D) + (reachable.contains(s) ? 0.0D : 10.0D)
                    // Tief unten im Inneren (z.B. Wasserrinne) schliesst ihn die naechste Schicht leicht ein ("BOXED
                    // IN") - lieber oben auf der Farm stehen (normale Bloecke von dort: siehe tooLowInside)
                    + (s.getY() < pos.getY() - 1 && s.getY() <= plan.topY(s.getX(), s.getZ()) ? 6.0D : 0.0D)
                    + random.nextDouble() * 1.6D;
            score.put(s, d);
        }
        stands.sort(Comparator.comparingDouble(score::get));
        Set<BlockPos> valid = new LinkedHashSet<>(); // in der Reihenfolge der Vorliebe
        standSpots.clear();
        standCrouch.clear();
        int checked = 0;
        for (BlockPos s : stands) {
            if (valid.size() >= 40 || checked >= 300) {
                break;
            }
            checked++;
            Vec3 eye = Placer.eyeAt(level, s);
            boolean ok = switch (what) {
                case PLACE -> {
                    // Mit etwas Reserve: Beim Ankommen steht man nie exakt in der Blockmitte - sonst "no angle". Grobes
                    // Raster: das feine (Ueber-Kopf) kommt unten nur fuer die nahen Plaetze - bei 300 Plaetzen ruckelte es
                    Placer.Aim a = placer.planPlacement(level, player, eye, pos, t.state(), new ItemStack(t.need().item()),
                            strict(pos), false);
                    yield a != null && a.hit().distanceTo(eye) <= Placer.reach(player) - 0.35D;
                }
                case MINE -> placer.planMining(level, player, eye, pos) != null;
                // Klick braucht evtl. eine bestimmte Seite (Hacke: oben) - genau die muss von dort sichtbar sein
                case CLICK -> placer.planClickOn(level, player, eye, pos, clickFace, false) != null;
                case CHEST -> {
                    // Mit Reserve wie beim Setzen: beim Ankommen steht er nie genau in der Feldmitte - sonst "chest
                    // angle" und weiter zur naechsten Kiste (42-mal in 45 Minuten)
                    Placer.Aim a = planChest(level, player, eye, pos);
                    yield a != null && a.hit().distanceTo(eye) <= Placer.reach(player) - 0.35D;
                }
                case FLUID -> placer.planFluid(level, player, eye, pos, t != null && t.waterlogged(),
                        t == null || !t.state().is(Blocks.LAVA)) != null;
                case CRAFTER -> placer.planClickOn(level, player, eye, pos, null, false) != null;
                default -> true;
            };
            if (ok) {
                valid.add(s);
            }
        }
        boolean overhead = what == Goal.PLACE && (Placer.needsSpot(t.state()) || Placer.needsLookUp(t.state()));
        boolean spotsTried = false;
        if (valid.isEmpty() && overhead) {
            List<BlockPos> spots = overheadSpots(level, player, t, stands, valid);
            valid.clear();
            valid.addAll(spots); // Stellen mit Spielraum zuerst
            spotsTried = true;
        }
        lastValidStands = valid.size();
        int validReachable = 0;
        for (BlockPos s : valid) {
            if (reachable.contains(s)) {
                validReachable++;
            }
        }
        lastStandInfo = "feet=" + feet.toShortString() + " stands=" + stands.size() + " checked=" + checked
                + " valid=" + valid.size() + " validReachable=" + validReachable + " scaffolds=" + scaffoldCount(player);
        if (valid.isEmpty()) {
            return false;
        }
        // Plaetze, von denen er danach nicht mehr wegkommt (Grube, Nische unter einem Dach ...): NIE. Frueher ging er
        // "notfalls" doch hin, sperrte sich ein, grub sich frei, baute die Bloecke wieder hin - und lief wieder hinein.
        // Geht es nur von so einem Platz aus, kommt der Block spaeter bzw. per "Platz schaffen" (clearView) dran.
        Set<BlockPos> leavable = leavableStands(level, player, valid, what, t, pos);
        if (leavable.isEmpty() && overhead && !spotsTried) {
            // Aus der Feldmitte ging es nur von Plaetzen ohne Ausgang (Grube unter dem Crafter): versetzt und geduckt
            // gibt es oft noch einen Platz daneben, von dem man wieder wegkommt (z.B. im trockenen Kanal)
            leavable = leavableStands(level, player, overheadSpots(level, player, t, stands, valid), what, t, pos);
            lastStandInfo += " spots=" + leavable.size();
        }
        lastValidStands = leavable.size();
        if (leavable.isEmpty()) {
            lastStandInfo += " leavable=0 (only stands without exit)";
            return false;
        }
        List<Set<BlockPos>> tries = List.of(leavable);
        Pathfinder.Path found = null;
        // Eben erst von genau hier aus keinen Weg in diese Gegend gefunden: nicht fuer jeden Block daneben wieder alle
        // teuren Suchen (je ~0,5 s - bei 30 Trichtern ruckelte das Spiel sekundenlang)
        long noPathKey = feet.asLong() * 31 + BlockPos.asLong(pos.getX() >> 3, pos.getY() >> 2, pos.getZ() >> 3);
        Integer noPathUntil = noPathCache.get(noPathKey);
        if (noPathUntil != null && noPathUntil > tick) {
            lastStandInfo += " leavable=" + leavable.size() + " path=NONE (same area just now)";
            return false;
        }
        for (Set<BlockPos> goals : tries) {
            found = Pathfinder.find(level, feet, goals::contains, pos, PATH_NODES, options);
            if (found == null && canBuild) {
                // Sehr hoch/weit: einmal gruendlicher suchen
                found = Pathfinder.find(level, feet, goals::contains, pos, PATH_NODES * 3, options.anywhere());
            }
            if (found == null && player.getHealth() > 12.0F) {
                // Notfalls (wie ein Mensch) auch mal etwas tiefer herunterspringen
                found = Pathfinder.find(level, feet, goals::contains, pos, PATH_NODES, options.withMaxDrop(5));
            }
            if (found == null && player.getHealth() >= 18.0F) {
                // Letzter Ausweg, um von oben wieder runterzukommen (kostet etwas Herz)
                found = Pathfinder.find(level, feet, goals::contains, pos, PATH_NODES, options.withMaxDrop(8));
            }
            if (found == null && canBuild && options.pillar() && pos.getY() - feet.getY() > 3) {
                // Weit unter dem Ziel (z.B. nach dem Kistengang): gezielt an der naechsten Kante hochbauen
                found = ascentPath(level, player, feet, goals, pos, options);
            }
            if (found != null) {
                break;
            }
        }
        if (found == null && settings.digThrough && tick - lastBreakSearch >= 5) {
            // Kommt so nicht hin: Bloecke im Weg abbauen (Farm-Bloecke werden neu gebaut, Gelaende am Ende wieder
            // hingesetzt). Teure Suche - hoechstens alle paar Ticks.
            lastBreakSearch = tick;
            for (Set<BlockPos> goals : tries) {
                found = Pathfinder.find(level, feet, goals::contains, pos, PATH_NODES, breakOptions(options));
                if (found != null) {
                    break;
                }
            }
        }
        if (found == null) {
            lastStandInfo += " leavable=" + leavable.size() + " path=NONE";
            if (noPathCache.size() > 256) {
                noPathCache.clear();
            }
            noPathCache.put(noPathKey, tick + 200);
            return false;
        }
        if (!leavable.contains(found.end())) {
            FarmLog.line("STAND WITHOUT EXIT " + found.end().toShortString() + " for " + what + " " + pos.toShortString()
                    + " (no other stand reachable)");
        }
        int needed = found.buildMoves();
        if (needed > scaffoldCount(player)) {
            Item spare = bestScaffoldInChests();
            if (spare != null && tick - lastScaffoldRestock > 600) {
                lastScaffoldRestock = tick;
                scaffoldWanted = needed + 32;
                beginRestock(player, level, spare);
                return true;
            }
            if (scaffoldCount(player) < needed) {
                return false; // nicht genug Hilfsbloecke - anderes Ziel versuchen
            }
        }
        Vec3 spot = what == Goal.PLACE ? standSpots.get(found.end()) : null;
        boolean crouch = spot != null && standCrouch.contains(found.end());
        beginWalk(found, what);
        arriveSpot = spot;
        arriveCrouch = crouch;
        if (spot != null) {
            FarmLog.line("SPOT " + pos.toShortString() + " " + t.state().getBlock().getName().getString() + " from "
                    + String.format(java.util.Locale.ROOT, "%.2f, %d, %.2f", spot.x, found.end().getY(), spot.z)
                    + (crouch ? " crouched" : ""));
        }
        return true;
    }

    /**
     * Ueber-Kopf-Bloecke: auch etwas versetzt im Feld und geduckt probieren (Crafter/Kolben nach unten: nur mit steilem
     * Blick nach oben, Crafter zusaetzlich in eine Himmelsrichtung). Begrenzt - teure Rechnung. Neue Plaetze kommen in
     * valid (Punkt in standSpots); zurueck nur die neuen.
     */
    private List<BlockPos> overheadSpots(ClientLevel level, LocalPlayer player, BuildPlan.Target t, List<BlockPos> stands,
                                         Set<BlockPos> valid) {
        BlockPos pos = t.pos();
        List<BlockPos> found = new ArrayList<>();
        Map<BlockPos, Integer> margins = new HashMap<>();
        double limit = Placer.reach(player) - 0.35D;
        int spotChecks = 0;
        long until = System.nanoTime() + 10_000_000L;
        List<BlockPos> near = new ArrayList<>(stands);
        near.sort(Comparator.comparingInt(s -> Math.abs(s.getY() - pos.getY() + 1) * 4
                + Math.abs(s.getX() - pos.getX()) + Math.abs(s.getZ() - pos.getZ())));
        for (BlockPos s : near) {
            if (valid.contains(s) || !Placer.spotRange(s, pos) || !Pathfinder.standable(level, s)) {
                continue; // aus der Mitte schon gueltig: versetzt aendert am Ausgang nichts
            }
            if (++spotChecks > 30 || System.nanoTime() > until) {
                break;
            }
            ItemStack stack = new ItemStack(t.need().item());
            Vec3[] eyes = Placer.eyeSpots(level, s, Placer.needsLookUp(t.state()));
            Vec3 best = null;
            int bestScore = -1;
            for (int i = 1; i < eyes.length && bestScore < 4; i++) {
                Placer.Aim a = placer.planPlacement(level, player, eyes[i], pos, t.state(), stack, strict(pos));
                if (a != null && a.hit().distanceTo(eyes[i]) <= limit) {
                    int score = spotMargin(level, player, eyes[i], t, stack, limit);
                    if (score > bestScore) {
                        bestScore = score;
                        best = eyes[i];
                    }
                }
            }
            if (best != null) {
                standSpots.put(s, new Vec3(best.x, s.getY(), best.z));
                if (Placer.crouched(level, s, best)) {
                    standCrouch.add(s);
                }
                valid.add(s);
                found.add(s);
                margins.put(s, bestScore);
            }
        }
        found.sort(Comparator.comparingInt(s -> -margins.get(s))); // Stellen mit Spielraum zuerst
        return found;
    }

    /**
     * Wie viele der 4 Nachbar-Augen (je 5 cm daneben) gehen auch? Beim Ankommen steht er nie ganz genau auf der Stelle -
     * Stellen, an denen nur ein einziger Klickpunkt passt, gingen dann oft nicht ("no angle").
     */
    private int spotMargin(ClientLevel level, LocalPlayer player, Vec3 eye, BuildPlan.Target t, ItemStack stack,
                           double limit) {
        int ok = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Vec3 e = eye.add(d.getStepX() * 0.05D, 0.0D, d.getStepZ() * 0.05D);
            Placer.Aim a = placer.planPlacement(level, player, e, t.pos(), t.state(), stack, strict(t.pos()));
            if (a != null && a.hit().distanceTo(e) <= limit) {
                ok++;
            }
        }
        return ok;
    }

    /** Plaetze, von denen er wieder wegkommt - in der Reihenfolge der Vorliebe, nach ein paar Treffern Schluss (teuer). */
    private Set<BlockPos> leavableStands(ClientLevel level, LocalPlayer player, java.util.Collection<BlockPos> candidates,
                                         Goal what, BuildPlan.Target t, BlockPos pos) {
        Set<BlockPos> leavable = new HashSet<>();
        for (BlockPos s : candidates) {
            if (leavable.size() >= 6) {
                break;
            }
            // "Sperrt mich der Block dort ein?" erst hier - nur fuer die Plaetze, die wirklich in Frage kommen
            if (canLeave(level, player, s) && !(what == Goal.PLACE && wouldTrap(level, player, s, pos, t.state()))) {
                leavable.add(s);
            }
        }
        return leavable;
    }

    /**
     * Weg auf die Farm hinauf: zur Kante laufen, die dem Ziel am naechsten ist, dort direkt ausserhalb einen Turm bis auf
     * die Hoehe der gebauten Kante bauen und oben hinueber zum Standplatz. Die normale Suche probiert Tuerme von JEDEM
     * Bodenfeld aus - bei grossen Farmen reicht dafuer das Knoten-Limit nicht (path=NONE). Ein Spieler baut auch einfach
     * an der naechsten Kante hoch. null = so nicht.
     */
    private Pathfinder.Path ascentPath(ClientLevel level, LocalPlayer player, BlockPos feet, Set<BlockPos> goals,
                                       BlockPos pos, Pathfinder.Options options) {
        BlockPos min = plan.origin;
        BlockPos max = plan.max();
        int cx = Mth.clamp(pos.getX(), min.getX(), max.getX());
        int cz = Mth.clamp(pos.getZ(), min.getZ(), max.getZ());
        // Je Seite: Feld direkt vor der Kante (Turm) und das Kantenfeld der Farm daneben
        BlockPos[][] sides = {
                {new BlockPos(min.getX() - 1, 0, cz), new BlockPos(min.getX(), 0, cz)},
                {new BlockPos(max.getX() + 1, 0, cz), new BlockPos(max.getX(), 0, cz)},
                {new BlockPos(cx, 0, min.getZ() - 1), new BlockPos(cx, 0, min.getZ())},
                {new BlockPos(cx, 0, max.getZ() + 1), new BlockPos(cx, 0, max.getZ())}};
        java.util.Arrays.sort(sides, Comparator.comparingInt(s -> Math.abs(s[0].getX() - pos.getX())
                + Math.abs(s[0].getZ() - pos.getZ())));
        int budget = Math.max(scaffoldCount(player), 64); // fehlt etwas, holt der Aufrufer Hilfsbloecke nach
        int goalY = Integer.MAX_VALUE;
        for (BlockPos g : goals) {
            goalY = Math.min(goalY, g.getY());
        }
        for (BlockPos[] side : sides) {
            BlockPos tower = side[0];
            BlockPos edge = side[1];
            // Oberkante der Farm an dieser Stelle (was schon gebaut ist)
            int topSolid = Integer.MIN_VALUE;
            for (int y = max.getY(); y >= min.getY() - 1; y--) {
                if (Pathfinder.supports(level, new BlockPos(edge.getX(), y, edge.getZ()))) {
                    topSolid = y;
                    break;
                }
            }
            if (topSolid == Integer.MIN_VALUE || topSolid + 1 - feet.getY() <= 2) {
                continue; // dort ist noch nichts hoch genug gebaut
            }
            BlockPos ground = groundBelow(level, new BlockPos(tower.getX(), topSolid + 1, tower.getZ()), 64);
            if (ground == null) {
                continue;
            }
            Pathfinder.Path toBase = null;
            boolean baseSearched = false;
            // Schwebt das Ziel hoch ueber der Kante: zuerst den Turm gleich bis auf die Hoehe der Standplaetze, oben dann
            // ein gerader Steg hinueber; sonst knapp ueber die Kante (die Suchen von dort sind teuer)
            boolean high = goalY > topSolid + 3;
            for (int extra = 0; extra <= 3; extra++) {
                int topY = high ? (extra == 0 ? goalY : topSolid + extra) : topSolid + 1 + extra;
                if (!high && extra == 3) {
                    break;
                }
                if (topY - ground.getY() > budget) {
                    if (high && extra == 0) {
                        continue; // so hoch reicht es nicht - dann knapp ueber die Kante versuchen
                    }
                    break;
                }
                boolean free = true;
                for (int y = ground.getY(); y < topY && free; y++) {
                    BlockPos c = new BlockPos(tower.getX(), y, tower.getZ());
                    free = Pathfinder.replaceable(level, c) && canScaffold(c); // hier kommt je ein Turmblock hin
                }
                for (int y = topY; y <= topY + 1 && free; y++) {
                    free = Pathfinder.passable(level, new BlockPos(tower.getX(), y, tower.getZ()));
                }
                if (!free) {
                    if (high && extra == 0) {
                        continue; // hoher Turm geht hier nicht - die niedrigen evtl. schon
                    }
                    break;
                }
                if (!baseSearched) {
                    baseSearched = true; // Weg zum Turmfuss haengt nicht von der Turmhoehe ab: nur einmal suchen
                    toBase = ground.equals(feet) ? null
                            : Pathfinder.find(level, feet, ground::equals, ground, PATH_NODES, options);
                }
                if (toBase == null && !ground.equals(feet)) {
                    break;
                }
                BlockPos top = new BlockPos(tower.getX(), topY, tower.getZ());
                Pathfinder.Path onTop = topY == goalY ? straightBridge(level, top, goals) : null;
                if (onTop == null) {
                    onTop = Pathfinder.assuming(top.below(), null,
                            () -> Pathfinder.find(level, top, goals::contains, pos, PATH_NODES, options));
                }
                if (onTop == null) {
                    continue; // vielleicht einen Block hoeher (dann geht es oben herunter statt hinauf)
                }
                List<BlockPos> nodes = new ArrayList<>(toBase == null ? List.of(feet) : toBase.nodes());
                List<Pathfinder.Move> mv = new ArrayList<>(toBase == null ? List.of(Pathfinder.Move.START) : toBase.moves());
                for (int y = ground.getY() + 1; y <= topY; y++) {
                    nodes.add(new BlockPos(tower.getX(), y, tower.getZ()));
                    mv.add(Pathfinder.Move.PILLAR);
                }
                for (int i = 1; i < onTop.nodes().size(); i++) {
                    nodes.add(onTop.nodes().get(i));
                    mv.add(onTop.moves().get(i));
                }
                Pathfinder.Path p = new Pathfinder.Path(nodes, mv);
                if (p.buildMoves() <= budget) {
                    FarmLog.line("ASCENT tower at " + tower.getX() + ", " + tower.getZ() + " from y=" + ground.getY()
                            + " to " + topY + " for " + pos.toShortString());
                    return p;
                }
            }
        }
        return null;
    }

    /** Was die Wegsuche gerade darf: Tuerme/Bruecken nur mit Hilfsbloecken und wenn eingeschaltet. */
    private Pathfinder.Options pathOptions(LocalPlayer player) {
        boolean build = settings.useTowers && (scaffoldCount(player) > 0 || bestScaffoldInChests() != null);
        boolean bridge = build && phase != Phase.CLEANUP;
        return new Pathfinder.Options(build, bridge, true, 3, this::canScaffold, this::scaffoldPenalty, scaffolds);
    }

    /**
     * Darf hier ein Hilfsblock hin? Nur wo die Farm Luft hat - und nie unter einem Teil der Farm (Boden, Dach,
     * Plattform darueber). Sonst wird der Hilfsblock spaeter eingebaut, kann nicht mehr abgebaut werden und der Bot
     * kommt nicht mehr herunter. Ausserdem nie auf Kisten (die gingen sonst nicht mehr auf).
     */
    private boolean canScaffold(BlockPos pos) {
        if (plan.contains(pos) && plan.stateAt(pos) != null) {
            return false;
        }
        // Unter spaeteren Farm-Bloecken nur als kurze Kletterhilfe (z.B. auf die Kisten-Ebene): direkt darueber muss Luft
        // bleiben (dort steht er), und nach dem Weg wird sie gleich wieder abgebaut (releaseClimbHelpers)
        if (pos.getY() <= plan.topY(pos.getX(), pos.getZ()) && plan.stateAt(pos.above()) != null) {
            return false;
        }
        ClientLevel level = mc.level;
        if (level == null || ChestStore.isStorage(level, pos.below())) {
            return false;
        }
        BlockState below = plan.stateAt(pos.below());
        return below == null || !(below.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                || below.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock);
    }

    /** Hilfsbloecke viel lieber ausserhalb der Farm (innen koennen sie spaeter eingemauert werden). */
    private double scaffoldPenalty(BlockPos pos) {
        return plan.topY(pos.getX(), pos.getZ()) != Integer.MIN_VALUE || plan.contains(pos) ? 9.0D : 0.0D;
    }

    private void beginWalk(Pathfinder.Path found, Goal what) {
        if ((what == Goal.PLACE || what == Goal.CLICK) && target != null && !targetIsScaffold) {
            lastWalkTarget = target.pos().immutable();
        }
        walkAnchor = null;
        centered = false;
        arriveSpot = null;
        spotCentered = false;
        arriveCrouch = false;
        leanOnly = false;
        arrivedNode = found.end();
        path = new ArrayList<>(found.nodes());
        moves = new ArrayList<>(found.moves());
        pathIndex = Math.min(1, path.size() - 1);
        moveStage = 0;
        moveTicks = 0;
        moveTries = 0;
        walkStartTick = tick;
        walkLimit = 600 + path.size() * 80;
        goal = what;
        repaths = 0;
        bestPathDistance = Double.MAX_VALUE;
        noProgressTicks = 0;
        walkPitch = 8.0F + random.nextFloat() * 18.0F;
        go(Step.WALK);
    }

    // ================================================================ Laufen

    private void walk(LocalPlayer player, ClientLevel level) {
        if (path == null || path.isEmpty()) {
            arrive(player, level);
            return;
        }
        // Wirklichkeits-Check (unabhaengig von der Wegsuche): kommt er beim Laufen 7 Sekunden lang nicht vom Fleck
        // (springt z.B. immer gegen dieselbe Wand), steckt er fest - dann nicht weiter probieren, sondern raus
        Vec3 here = player.position();
        boolean gap = tick - lastWalkTick > 2; // dazwischen etwas anderes (Inventar, Pause) - neu messen
        lastWalkTick = tick;
        if (walkAnchor == null || gap || here.distanceToSqr(walkAnchor) > 1.5D * 1.5D || mining) {
            walkAnchor = here;
            walkAnchorTick = tick;
        } else if (tick - walkAnchorTick > 140) {
            walkAnchor = null;
            stuckInPlace(player, level);
            return;
        }
        if (tick - walkStartTick > walkLimit) {
            // Harte Grenze je Weg: im Wasser schob ihn die Stroemung immer wieder ein Stueck weiter - so galt er weder als
            // festgefahren noch kam er an und schwamm 35 Minuten lang im Becken herum
            FarmLog.line("WALK timeout after " + (tick - walkStartTick) + " ticks, node " + pathIndex + "/" + path.size()
                    + " goal=" + goal + " feet=" + feetNode(player).toShortString()
                    + (player.isInWater() ? " water" : ""));
            input = idleInput();
            path = null;
            failTask(level, "walk timeout");
            noteStuck(player, level);
            return;
        }
        Pathfinder.Move move = moves == null || pathIndex >= moves.size() ? Pathfinder.Move.WALK : moves.get(pathIndex);
        boolean special = move == Pathfinder.Move.PILLAR || move == Pathfinder.Move.BRIDGE || move == Pathfinder.Move.DIG
                || move == Pathfinder.Move.BREAK;
        // Unterwegs schon moeglich? Dann stehen bleiben (Menschen laufen nicht exakt bis zum Ziel)
        if (stepTicks % 4 == 0 && goal == Goal.PLACE && target != null && pathIndex > 0 && player.onGround()
                && (!special || moveStage == 0)) {
            Placer.Aim a = blocksMe(player, level, target) || tooLowInside(feetNode(player), target) ? null
                    : placer.planPlacement(level, player, player.getEyePosition(), target.pos(), target.state(),
                    new ItemStack(target.need().item()), strict(target.pos()));
            if (a != null && player.getDeltaMovement().horizontalDistanceSqr() < 0.03D
                    && !wouldTrap(level, player, feetNode(player), target.pos(), target.state())) {
                currentAim = a;
                input = idleInput();
                go(Step.SELECT);
                return;
            }
        }
        if (special) {
            if (specialMove(player, level, move, path.get(pathIndex - 1), path.get(pathIndex))) {
                progress();
                moveStage = 0;
                moveTicks = 0;
                moveTries = 0;
                bestPathDistance = Double.MAX_VALUE;
                noProgressTicks = 0;
                if (pathIndex >= path.size() - 1) {
                    input = idleInput();
                    arrivedNode = path.get(pathIndex);
                    releaseClimbHelpers(player);
                    path = null;
                    wait = delay(2, 6);
                    arrive(player, level);
                } else {
                    pathIndex++;
                }
            }
            return;
        }
        Vec3 pos = player.position();
        BlockPos node = path.get(pathIndex);
        // Wegpunkte abhaken
        while (pathIndex < path.size() - 1 && reached(pos, node, false)) {
            pathIndex++;
            lastActivity = tick; // kommt voran (Springen auf der Stelle erreicht keinen Wegpunkt)
            node = path.get(pathIndex);
            bestPathDistance = Double.MAX_VALUE;
            Pathfinder.Move nextMove = moves.get(pathIndex);
            if (nextMove == Pathfinder.Move.PILLAR || nextMove == Pathfinder.Move.BRIDGE || nextMove == Pathfinder.Move.DIG
                    || nextMove == Pathfinder.Move.BREAK) {
                moveStage = 0;
                moveTicks = 0;
                moveTries = 0;
                input = idleInput();
                return; // naechsten Tick ausfuehren
            }
            if (nextMove == Pathfinder.Move.DROP && path.get(pathIndex - 1).getY() - node.getY() > 3) {
                ignoreDamageUntil = tick + 80; // gewollter Sprung
            }
        }
        boolean last = pathIndex == path.size() - 1;
        if (last && reached(pos, node, true)) {
            // Ankommen allein ist kein Fortschritt (sonst haelt "hinlaufen -> geht nicht -> hinlaufen" den Wachhund ewig ruhig)
            input = idleInput();
            arrivedNode = node;
            releaseClimbHelpers(player);
            path = null;
            wait = delay(2, 6);
            arrive(player, level);
            return;
        }
        // Ziel leicht vorausschauend (Kurven statt Ecken), nie exakt auf die Blockmitte. Nicht vor einem Sprung nach unten:
        // in ein schmales Loch muss er genau hinein (vorausschauend lief er daneben vorbei und drehte sich hin und her)
        Vec3 aimPoint = center(node);
        if (!last && move != Pathfinder.Move.DROP && cornerFree(level, node, path.get(pathIndex + 1))) {
            Vec3 next = center(path.get(pathIndex + 1));
            double d = horizontal(pos, aimPoint);
            double blend = Mth.clamp(1.0D - d / 1.6D, 0.0D, 0.6D);
            aimPoint = aimPoint.lerp(next, blend);
        }
        float yaw = Placer.yawTo(pos, aimPoint);
        int floorHere = Mth.floor(player.getY() + 0.01D);
        if (pathIndex > 0 && node.getY() < floorHere && horizontal(pos, center(node)) < 0.8D) {
            BlockPos prev = path.get(pathIndex - 1);
            Vec3 c = center(node);
            double ahead = (node.getX() - prev.getX()) * (c.x - pos.x) + (node.getZ() - prev.getZ()) * (c.z - pos.z);
            if ((prev.getX() != node.getX() || prev.getZ() != node.getZ()) && ahead > 0.0D) {
                // An der Kante vor dem Herunterfallen: Richtung der Strecke halten. Die Richtung zum nahen Mittelpunkt
                // sprang hin und her, die Kamera drehte sich tausende Grad im Kreis und er fiel nie herunter. Aber nur,
                // bis er ueber das Loch hinaus ist - ueber ein schmales lief er hinweg und drueckte dann gegen die Wand.
                yaw = Placer.yawTo(center(prev), c);
            }
        }
        float wobble = (float) Math.sin(tick * 0.09D + walkPitch) * 4.0F;
        aim.follow(yaw, walkPitch + wobble);
        lastWalkYaw = yaw;

        float yawError = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
        boolean forward = yawError < 55.0F;
        int floorY = Mth.floor(player.getY() + 0.01D);
        boolean stepUp = node.getY() > floorY && horizontal(pos, center(node)) < 1.5D;
        // Im Wasser hilft ein einzelner Sprung nicht (man schwimmt nur langsam hoch): Leertaste halten wie ein Spieler,
        // bis er ueber die Kante kommt - sonst haengt er im Kanal unter den Kisten fest
        boolean swimUp = player.isInWater() && forward && stepUp;
        if (stepUp && pathIndex > 0 && (tick < centerJumpUntil
                || (player.horizontalCollision || player.isInWater()) && noProgressTicks > 12)) {
            // Sprung stoesst an: am Feldrand ragt er ins Nachbarfeld und stoesst dort oben oder seitlich an - erst in die
            // Mitte des eigenen Feldes (kurz dabei bleiben, sonst pendelt er), dann noch einmal. Im Wasser genauso: die
            // Stroemung schiebt ihn vom Rand weg, dann reicht der Sprung aus dem Wasser nicht. Nur QUER zur
            // Sprungrichtung: zum Ziel hin muss er an der Kante bleiben (aus dem Wasser kommt er nur, wenn er die Wand
            // beruehrt) - frueher zog ihn das Einmitten von der Wand weg und er pendelte in der Rinne, bis er aufgab
            BlockPos prev = path.get(pathIndex - 1);
            Vec3 own = center(prev);
            Vec3 lateral = node.getX() != prev.getX() ? new Vec3(pos.x, own.y, own.z) : new Vec3(own.x, own.y, pos.z);
            double off = horizontal(pos, lateral);
            if (off > 0.15D && off < 0.9D) {
                if (tick >= centerJumpUntil) {
                    centerJumpUntil = tick + 20;
                }
                input = strafeToward(player, lateral, false);
                return;
            }
            centerJumpUntil = 0;
        }
        if (!stepUp && player.onGround() && player.horizontalCollision && noProgressTicks > 15) {
            // Stoesst beim Laufen auf gleicher Hoehe an (am Feldrand ragt er ins Nachbarfeld, z.B. an eine Trichter- oder
            // Glasecke): direkt zur Mitte des Wegpunkts ruecken, egal wohin er gerade schaut - er lief sonst stur geradeaus
            input = strafeToward(player, center(node), false);
            return;
        }
        // Wegen Anstossens nur springen, wenn ueber dem Kopf Platz ist: unter einer niedrigen Decke (Steg unter dem
        // Generator) stiess er sonst immer wieder mit dem Kopf hinein - der Server rechnet stehend und gab Erstickungsschaden
        boolean roomAbove = Pathfinder.passable(level, BlockPos.containing(player.getX(), player.getY() + 2.2D, player.getZ()));
        boolean jump = swimUp || player.onGround() && forward
                && (stepUp || player.horizontalCollision && yawError < 30.0F && roomAbove);
        // Auch eine halbe Stufe tiefer zaehlt (von einer Stufe/Honig herunter): geduckt stiege er dort nie hinab
        boolean nextLower = !last && path.get(pathIndex + 1).getY() < node.getY() || node.getY() < floorY
                || node.getY() + Pathfinder.floorHeight(level, node) < player.getY() - 0.25D;
        // Herunterfallen ist gewollt (auch von Honig/Kiste, wo Fuss- und Zielfeld dieselbe Zahl haben): nie schleichen,
        // sonst bleibt er an der Kante stehen
        boolean sneak = !nextLower && !stepUp && move != Pathfinder.Move.DROP
                && Pathfinder.nearDrop(level, player.blockPosition())
                && Pathfinder.nearDrop(level, node) && player.getY() - plan.origin.getY() > 1.5D;
        double remaining = remainingDistance(pos);
        boolean sprint = !sneak && remaining > 7.0D && yawError < 20.0F && player.getFoodData().getFoodLevel() > 6
                && !upcomingStepUp(3);
        input = new Input(forward, false, false, false, jump, sneak, sprint);

        if (player.onGround() && node.getY() - floorY >= 2) {
            // Vom Weg gefallen (z.B. an einer Kante in eine offene Rinne): gleich neu planen, statt 7 s gegen die Wand
            // zu laufen - danach galt er als eingemauert und hielt an
            noProgressTicks = Math.max(noProgressTicks, 45);
        }
        // Haengt fest? Neu planen
        double dist = horizontal(pos, center(node)) + Math.abs(pos.y - node.getY());
        if (dist < bestPathDistance - 0.05D) {
            bestPathDistance = dist;
            noProgressTicks = 0;
        } else if (++noProgressTicks > 45) {
            noProgressTicks = 0;
            BlockPos end = path.get(path.size() - 1);
            // Weg mit Kanten-Hilfsblock am Ende: neu suchen hiesse, ins Feld des schwebenden Blocks zu laufen
            Pathfinder.Path again = ++repaths <= 3 && !leanOnly
                    ? Pathfinder.find(level, feetNode(player), p -> p.equals(end), end, PATH_NODES,
                    moves.contains(Pathfinder.Move.BREAK) ? breakOptions(pathOptions(player)) : pathOptions(player)) : null;
            if (again == null) {
                input = idleInput();
                path = null;
                failTask(level, "stuck");
                noteStuck(player, level);
                return;
            }
            path = new ArrayList<>(again.nodes());
            moves = new ArrayList<>(again.moves());
            pathIndex = Math.min(1, path.size() - 1);
            moveStage = 0;
            moveTicks = 0;
            walkLimit = tick - walkStartTick + 600 + path.size() * 80; // neuer Weg, neue Frist (hoechstens 3 Mal)
            bestPathDistance = Double.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- Turm / Bruecke / Runtergraben

    /** Fuehrt eine Bau-Bewegung aus. true = Wegpunkt "to" ist erreicht. */
    private boolean specialMove(LocalPlayer player, ClientLevel level, Pathfinder.Move move, BlockPos from, BlockPos to) {
        if (++moveTicks > (move == Pathfinder.Move.BREAK ? 600 : 260)) {
            failWalk(level, "move timeout");
            noteStuck(player, level);
            return false;
        }
        return switch (move) {
            case PILLAR -> pillarMove(player, level, from, to);
            case BRIDGE -> bridgeMove(player, level, from, to);
            case DIG -> digMove(player, level, from, to);
            case BREAK -> breakMove(player, level, from, to);
            default -> true;
        };
    }

    private void failWalk(ClientLevel level, String why) {
        releaseMining();
        input = idleInput();
        path = null;
        failTask(level, why);
    }

    /** Hochbauen: in die Mitte, nach unten schauen, springen und im Sprung einen Block unter sich setzen. */
    private boolean pillarMove(LocalPlayer player, ClientLevel level, BlockPos from, BlockPos to) {
        if (moveStage == 0 && player.onGround() && !Pathfinder.replaceable(level, from)
                && Math.abs(player.getY() - to.getY()) < 0.25D) {
            return true; // Block steht schon (z.B. von vorhin)
        }
        Item scaffold = scaffoldItem(player);
        if (scaffold == null) {
            failWalk(level, "no scaffold blocks");
            return false;
        }
        if (moveStage == 0 && player.onGround() && !Pathfinder.supports(level, from.below())) {
            // Unter dem Turmplatz ist nichts (er steht nur auf einer Kante daneben): geschlichen kommt er nie in die
            // Mitte - frueher stand er dann 3x 7 Sekunden da und die ganze Bauphase wurde abgebrochen
            failWalk(level, "pillar without floor");
            return false;
        }
        switch (moveStage) {
            case 0 -> {
                if (moveTicks == 1) {
                    lookDownTarget(player);
                    status = "building up";
                }
                aim.lookAt(player, downYaw, downPitch);
                if (!prepareItem(player, scaffold, Step.WALK)) {
                    input = idleInput();
                    return false;
                }
                if (horizontal(player.position(), center(from)) > 0.2D) {
                    input = strafeToward(player, center(from), true);
                    return false;
                }
                input = Input.EMPTY;
                if (player.onGround() && aim.error(player) < 9.0F && player.getDeltaMovement().horizontalDistanceSqr() < 0.002D) {
                    moveStage = 1;
                }
            }
            case 1 -> {
                aim.lookAt(player, downYaw, downPitch);
                input = new Input(false, false, false, false, true, false, false);
                if (!player.onGround()) {
                    moveStage = 2;
                }
            }
            case 2 -> {
                aim.lookAt(player, downYaw, downPitch);
                input = Input.EMPTY;
                if (player.getY() > from.getY() + 1.02D) {
                    BlockPos support = from.below();
                    BlockHitResult hit = crosshair(player, level);
                    if (hit != null && hit.getBlockPos().equals(support) && hit.getDirection() == Direction.UP) {
                        click(player, hit);
                    } else {
                        Vec3 top = new Vec3(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D);
                        click(player, new BlockHitResult(top, Direction.UP, support, false));
                    }
                    moveStage = 3;
                } else if (player.onGround() && moveTicks > 6) {
                    // Wieder gelandet ohne zu setzen: noch mal
                    moveStage = 1;
                    if (++moveTries > 4) {
                        failWalk(level, "pillar failed");
                    }
                }
            }
            default -> {
                aim.lookAt(player, downYaw, downPitch);
                input = Input.EMPTY;
                if (player.onGround()) {
                    if (!Pathfinder.replaceable(level, from)) {
                        scaffolds.add(from.immutable());
                        return true;
                    }
                    moveStage = 1; // Block kam nicht an
                    if (++moveTries > 4) {
                        failWalk(level, "pillar failed");
                    }
                }
            }
        }
        return false;
    }

    /** Bruecke: schleichend an die Kante, nach hinten-unten schauen und einen Block vor die Kante setzen. */
    private boolean bridgeMove(LocalPlayer player, ClientLevel level, BlockPos from, BlockPos to) {
        BlockPos support = to.below();
        if (!Pathfinder.replaceable(level, support)) {
            // Boden ist da: ab hier normal (schleichend) weiterlaufen
            moves.set(pathIndex, Pathfinder.Move.WALK);
            if (moveStage > 0) {
                scaffolds.add(support.immutable());
            }
            moveStage = 0;
            if (leanOnly && pathIndex == path.size() - 1) {
                // Nur der Hilfsblock an der Kante war gewollt (Halt fuer einen schwebenden Block): nicht hinauslaufen
                leanOnly = false;
                input = idleInput();
                arrivedNode = path.get(pathIndex - 1);
                releaseClimbHelpers(player);
                path = null;
                wait = delay(2, 6);
                arrive(player, level);
            }
            return false;
        }
        Item scaffold = scaffoldItem(player);
        if (scaffold == null) {
            failWalk(level, "no scaffold blocks");
            return false;
        }
        if (!prepareItem(player, scaffold, Step.WALK)) {
            input = idleInput();
            return false;
        }
        BlockState state = ((BlockItem) scaffold).getBlock().defaultBlockState();
        Input sneakOnly = new Input(false, false, false, false, false, true, false);
        switch (moveStage) {
            case 0 -> {
                status = "bridging";
                Placer.Aim a = player.isCrouching()
                        ? placer.planPlacement(level, player, player.getEyePosition(), support, state, new ItemStack(scaffold), false)
                        : null;
                if (a != null) {
                    currentAim = a;
                    settledTicks = 0;
                    moveStage = 1;
                    input = sneakOnly;
                    return false;
                }
                // Schleichend Richtung Kante (Schleichen verhindert das Herunterfallen)
                float yaw = Placer.yawTo(player.position(), center(to));
                aim.follow(yaw, 50.0F + walkPitch);
                boolean forward = moveTicks > 3 && Math.abs(Mth.wrapDegrees(yaw - player.getYRot())) < 40.0F;
                input = new Input(forward, false, false, false, false, true, false);
                if (moveTicks > 70) {
                    failWalk(level, "bridge angle");
                }
            }
            case 1 -> {
                input = sneakOnly;
                aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
                if (!aim.settled(player) || ++settledTicks < 3 + reaction) {
                    return false;
                }
                BlockHitResult hit = crosshair(player, level);
                if (hit == null || !hit.getBlockPos().equals(currentAim.clicked()) || hit.getDirection() != currentAim.face()) {
                    moveStage = 0;
                    return false;
                }
                click(player, hit);
                moveWait = 0;
                moveStage = 2;
            }
            default -> {
                input = sneakOnly;
                if (++moveWait > 15) {
                    moveStage = 0;
                    if (++moveTries > 3) {
                        failWalk(level, "bridge failed");
                    }
                }
            }
        }
        return false;
    }

    /** Eigenen Hilfsblock unter den Fuessen abbauen und einen Block tiefer landen. */
    private boolean digMove(LocalPlayer player, ClientLevel level, BlockPos from, BlockPos to) {
        BlockPos block = to; // der Block, auf dem man gerade steht
        if (Pathfinder.replaceable(level, block)) {
            releaseMining();
            scaffolds.remove(block);
            input = Input.EMPTY;
            return player.onGround() && Math.abs(player.getY() - to.getY()) < 0.25D;
        }
        if (!scaffolds.contains(block)) {
            failWalk(level, "not our block");
            return false;
        }
        if (moveTicks == 1) {
            lookDownTarget(player);
            status = "going down";
        }
        aim.lookAt(player, downYaw, downPitch);
        if (moveStage == 0) {
            Item tool = bestToolFor(player, level.getBlockState(block));
            if (tool != null && !prepareItem(player, tool, Step.WALK)) {
                input = idleInput();
                return false;
            }
            if (horizontal(player.position(), center(from)) > 0.25D) {
                if (moveTicks > 60) {
                    // Kommt nicht in die Mitte des Turms (stoesst an): anderen Weg suchen statt ewig zu schieben
                    failWalk(level, "dig: cannot center");
                    return false;
                }
                input = strafeToward(player, center(from), true);
                return false;
            }
            input = Input.EMPTY;
            if (aim.error(player) < 9.0F) {
                moveStage = 1;
            }
            return false;
        }
        input = Input.EMPTY;
        lastActivity = tick; // Abbauen ist kein Haengen (der Wachhund uebersprang sonst Schichten)
        if (!mining) {
            minePos = block;
            mineFace = Direction.UP;
            mc.gameMode.startDestroyBlock(block, Direction.UP);
            mining = true;
        } else {
            mc.gameMode.continueDestroyBlock(block, Direction.UP);
        }
        Compat.swing(player, InteractionHand.MAIN_HAND);
        return false;
    }

    /**
     * Durchbruch: die Bloecke zwischen hier und dem naechsten Wegpunkt mit dem passenden Werkzeug abbauen (oben
     * zuerst), danach ganz normal weiterlaufen. Farm-Bloecke werden spaeter neu gebaut, Gelaende am Ende wieder gesetzt.
     */
    private boolean breakMove(LocalPlayer player, ClientLevel level, BlockPos from, BlockPos to) {
        if (mining && minePos != null && Pathfinder.passable(level, minePos)) {
            releaseMining(); // Block ist weg
            progress();
        }
        List<BlockPos> cells = Pathfinder.breakCells(level, from, to);
        if (cells.isEmpty()) {
            releaseMining();
            moves.set(pathIndex, Pathfinder.Move.WALK); // frei - ab hier normal laufen (bzw. hochspringen)
            moveStage = 0;
            status = "";
            return false;
        }
        status = "digging through";
        BlockPos cell = cells.get(0);
        BlockState state = level.getBlockState(cell);
        boolean sneak = Pathfinder.nearDrop(level, player.blockPosition());
        if (mining && minePos != null && cells.contains(minePos)) {
            // Laeuft schon (auch wenn es der zweite Block der Liste ist) - weiter abbauen
            lastActivity = tick;
            input = new Input(false, false, false, false, false, sneak, false);
            mc.gameMode.continueDestroyBlock(minePos, mineFace);
            Compat.swing(player, InteractionHand.MAIN_HAND);
            return false;
        }
        releaseMining();
        if (!canBreakThrough(cell)) {
            failWalk(level, "cannot dig through"); // hat sich geaendert (z.B. Wasser daneben)
            return false;
        }
        Item tool = bestToolFor(player, state);
        if (tool != null && !prepareItem(player, tool, Step.WALK)) {
            input = idleInput();
            return false;
        }
        // Nah genug an den Startpunkt, damit alles in Reichweite und sichtbar ist
        if (horizontal(player.position(), center(from)) > 0.3D) {
            input = strafeToward(player, center(from), sneak);
            return false;
        }
        input = new Input(false, false, false, false, false, sneak, false);
        if (currentAim == null || !currentAim.clicked().equals(cell) || moveStage == 0) {
            currentAim = placer.planMining(level, player, player.getEyePosition(), cell);
            if (currentAim == null) {
                failWalk(level, "cannot see block");
                return false;
            }
            moveStage = 1;
            settledTicks = 0;
        }
        aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
        if (!aim.settled(player) || ++settledTicks < 2 + reaction) {
            return false;
        }
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !cells.contains(hit.getBlockPos())) {
            moveStage = 0; // neu zielen
            return false;
        }
        // Was das Fadenkreuz trifft, kommt dran (auch wenn es der zweite Block der Liste ist)
        BlockPos hitPos = hit.getBlockPos().immutable();
        rememberBroken(level, hitPos);
        minePos = hitPos;
        mineFace = hit.getDirection();
        mc.gameMode.startDestroyBlock(minePos, mineFace);
        mining = true;
        Compat.swing(player, InteractionHand.MAIN_HAND);
        return false;
    }

    /** Ein Block wird fuer einen Weg abgebaut: merken, damit er spaeter wieder hinkommt. */
    private void rememberBroken(ClientLevel level, BlockPos pos) {
        if (scaffolds.remove(pos)) {
            return; // eigener Hilfsblock - muss nicht zurueck
        }
        BlockState want = plan.stateAt(pos);
        if (want != null) {
            // Farm-Block: wird ganz normal neu gebaut (die Runde geht dafuer bis zu dieser Schicht zurueck)
            brokenPlanLayer = Math.min(brokenPlanLayer, pos.getY());
            rebuilds.merge(pos.immutable(), 1, Integer::sum);
            guard.touch(pos);
            return;
        }
        if (!plan.contains(pos) && !restore.containsKey(pos)) {
            restore.put(pos.immutable(), level.getBlockState(pos)); // Gelaende: am Ende wieder hinsetzen
        }
    }

    /** Wegsuche, die sich (als letzter Ausweg) auch durch Bloecke graben darf. */
    private Pathfinder.Options breakOptions(Pathfinder.Options options) {
        return options.withBreaking(this::canBreakThrough, this::breakCost);
    }

    /**
     * Darf dieser Block fuer einen Weg abgebaut werden? Nur, was sicher wieder hingesetzt werden kann: nichts mit
     * Inhalt (Kisten, Trichter ...), keine Schalter/Tueren, nichts Unzerstoerbares, nichts, wodurch Wasser oder Lava
     * hereinlaeuft - und nur, wenn der Block mit dem vorhandenen Werkzeug auch droppt.
     */
    private boolean canBreakThrough(BlockPos pos) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || plan == null) {
            return false;
        }
        BlockState s = level.getBlockState(pos);
        float hardness = s.getDestroySpeed(level, pos);
        if (s.isAir() || hardness < 0.0F || hardness > 10.0F || s.hasBlockEntity() || !s.getFluidState().isEmpty()
                || Pathfinder.danger(s) || Placer.interactive(s) || s.is(BlockTags.ICE)) {
            return false;
        }
        for (Direction d : Direction.values()) {
            if (d != Direction.DOWN && !level.getFluidState(pos.relative(d)).isEmpty()) {
                return false;
            }
        }
        if (s.requiresCorrectToolForDrops() && !hasCorrectTool(player, s)) {
            return false;
        }
        if (Drops.lost(s)) {
            return false; // Glas, Eis, Glowstone ... gehen beim Abbauen kaputt
        }
        if (plan.stateAt(pos) != null && !Drops.dropsItself(s)) {
            return false; // Farm-Block, der beim Abbauen etwas anderes droppt - koennte nicht genauso wieder hin
        }
        if (phase == Phase.CLEANUP && plan.stateAt(pos) != null) {
            return false; // beim Aufraeumen wird nichts mehr gebaut - Farm-Bloecke nicht mehr anfassen
        }
        if (plan.stateAt(pos) != null && rebuilds.getOrDefault(pos, 0) >= MAX_REBUILDS) {
            return false; // schon zweimal abgebaut und neu gesetzt - nicht noch mal (sonst Endlosschleife)
        }
        if (plan.stateAt(pos) == null && !plan.contains(pos)) {
            // Gelaende muss wieder hin: Glas/Laub droppen nichts, unbekannte Bloecke lieber nicht anfassen
            return !s.is(BlockTags.IMPERMEABLE) && !s.is(BlockTags.LEAVES) && restoreItem(s) != null;
        }
        return true;
    }

    /** Kosten fuer die Wegsuche: Abbauzeit, dazu ein Aufschlag fuer Bloecke, die danach wieder hin muessen. */
    private double breakCost(BlockPos pos) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        BlockState s = level.getBlockState(pos);
        float speed = 1.0F;
        for (int i = 0; i < 36; i++) {
            speed = Math.max(speed, player.getInventory().getItem(i).getDestroySpeed(s));
        }
        double seconds = s.getDestroySpeed(level, pos) * 1.5D / speed;
        double refill = plan.stateAt(pos) != null ? 8.0D : plan.contains(pos) || scaffolds.contains(pos) ? 0.0D : 3.0D;
        return 2.0D + seconds * 3.0D + refill;
    }

    private static boolean hasCorrectTool(LocalPlayer player, BlockState state) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isCorrectToolForDrops(state) && Tools.usable(stack)) {
                return true;
            }
        }
        return false;
    }

    /** Womit sich ein abgebauter Gelaende-Block wieder hinsetzen laesst (das, was er beim Abbauen droppt). */
    private static Item restoreItem(BlockState state) {
        if (Placer.sameFamily(state, Blocks.DIRT.defaultBlockState())) {
            return Items.DIRT; // Gras, Myzel, Podsol, Ackerboden, Pfad droppen Erde
        }
        if (state.is(Blocks.STONE)) {
            return Items.COBBLESTONE;
        }
        if (state.is(Blocks.DEEPSLATE)) {
            return Items.COBBLED_DEEPSLATE;
        }
        Item item = state.getBlock().asItem();
        return item instanceof BlockItem ? item : null;
    }

    /**
     * Fussfeld fuer die Wegsuche. blockPosition() waere auf etwas niedrigeren Bloecken (Ackerboden 15/16, Truhe,
     * Seelensand, Trichter ...) der Block, auf dem man steht - dann findet die Suche oft gar keinen Weg.
     */
    private static BlockPos feetNode(LocalPlayer player) {
        // Am Boden etwas mehr Spielraum: in der Mulde eines Trichters steht man nur auf 11/16
        BlockPos p = BlockPos.containing(player.getX(), player.getY() + (player.onGround() ? 0.45D : 0.25D), player.getZ());
        ClientLevel level = Minecraft.getInstance().level;
        if (!player.onGround() || level == null || Pathfinder.standable(level, p)) {
            return p;
        }
        // Steht nur auf einer Kante (Mitte ueber Luft): das Feld nehmen, auf dessen Block er wirklich steht - sonst
        // beginnt jeder Weg auf einem Feld ohne Boden (Turm "ohne Boden", Feststecken)
        BlockPos best = p;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                boolean overX = dx == 0 || (dx < 0 ? player.getX() - 0.3D < p.getX() : player.getX() + 0.3D > p.getX() + 1);
                boolean overZ = dz == 0 || (dz < 0 ? player.getZ() - 0.3D < p.getZ() : player.getZ() + 0.3D > p.getZ() + 1);
                BlockPos q = p.offset(dx, 0, dz);
                if (!overX || !overZ || !Pathfinder.standable(level, q)) {
                    continue;
                }
                double d = q.distToCenterSqr(player.getX(), q.getY() + 0.5D, player.getZ());
                if (d < bestD) {
                    bestD = d;
                    best = q;
                }
            }
        }
        return best;
    }

    /** Tasten (vor/zurueck/seitlich) relativ zur Blickrichtung, um zu einem Punkt zu rutschen. */
    private static Input strafeToward(LocalPlayer player, Vec3 point, boolean sneak) {
        return strafeToward(player, point, sneak, 0.06D);
    }

    private static Input strafeToward(LocalPlayer player, Vec3 point, boolean sneak, double dead) {
        double dx = point.x - player.getX();
        double dz = point.z - player.getZ();
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        double forward = -Mth.sin(yaw) * dx + Mth.cos(yaw) * dz;
        double left = Mth.cos(yaw) * dx + Mth.sin(yaw) * dz;
        return new Input(forward > dead, forward < -dead, left > dead, left < -dead, false, sneak, false);
    }

    /** Offene Bloecke im Wuerfel (Abstand je Achse <= range) um center - ueber das Gitter, ohne alle durchzugehen. */
    private List<BuildPlan.Target> pendingNearList(BlockPos center, int range) {
        List<BuildPlan.Target> out = new ArrayList<>();
        for (int gx = (center.getX() - range) >> 3; gx <= (center.getX() + range) >> 3; gx++) {
            for (int gz = (center.getZ() - range) >> 3; gz <= (center.getZ() + range) >> 3; gz++) {
                for (int gy = (center.getY() - range) >> 2; gy <= (center.getY() + range) >> 2; gy++) {
                    List<BuildPlan.Target> cell = pendingGrid.get(gridKey(gx, gy, gz));
                    if (cell == null) {
                        continue;
                    }
                    for (BuildPlan.Target t : cell) {
                        BlockPos p = t.pos();
                        if (Math.abs(p.getX() - center.getX()) <= range && Math.abs(p.getY() - center.getY()) <= range
                                && Math.abs(p.getZ() - center.getZ()) <= range) {
                            out.add(t);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Fehlt im Umkreis von 4 noch ein Ueber-Kopf-Block (Kolben/Werfer/Crafter nach unten), der Hilfe brauchen koennte? */
    private boolean overheadNear(BlockPos r) {
        if (phase != Phase.BUILD && (phase != Phase.BUILD2 || build2Rounds > 3)) {
            return false;
        }
        for (BuildPlan.Target t : pendingNearList(r, 4)) {
            // Nur fuer Bloecke, die noch Chancen haben - sonst sammelte sich um dauerhaft unmoegliche Crafter ein
            // Erde-Labyrinth, in dem er unter niedriger Decke stecken blieb
            if (Placer.needsLookUp(t.state()) && !givenUp.contains(t.pos()) && !missing.contains(t.need().item())
                    && attempts.getOrDefault(t.pos(), 0) < LAYER_TRIES && Math.abs(t.pos().getY() - r.getY()) <= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Steht er (feet) zwei und mehr unter t im Inneren der Farm (unter spaeteren Ebenen, z.B. in einer Wasserrinne)?
     * Von dort setzt er normale Bloecke nicht: er mauerte sich so mit genau diesem Block ein - die Fallen-Pruefung hielt
     * das grosse Rinnennetz fuer offen. Ueber-Kopf-Bloecke brauchen solche Plaetze; in den letzten Runden gilt es nicht.
     */
    private boolean tooLowInside(BlockPos feet, BuildPlan.Target t) {
        return feet.getY() <= t.pos().getY() - 2 && feet.getY() <= plan.topY(feet.getX(), feet.getZ())
                && !Placer.needsLookUp(t.state()) && !Placer.needsSpot(t.state())
                && (phase == Phase.BUILD || phase == Phase.BUILD2 && build2Rounds <= 2);
    }

    /**
     * Am Ziel eines Weges: Turm-/Brueckenbloecke dieses Weges, die unter spaeteren Farm-Bloecken stehen, gleich wieder
     * abbauen (ausser dem, auf dem er steht) - sonst mauert die naechste Schicht sie ein.
     */
    private void releaseClimbHelpers(LocalPlayer player) {
        if (path == null || moves == null) {
            return;
        }
        BlockPos under = feetNode(player).below();
        List<BlockPos> helpers = new ArrayList<>();
        for (int i = 1; i < path.size() && i < moves.size(); i++) {
            Pathfinder.Move m = moves.get(i);
            BlockPos b = m == Pathfinder.Move.PILLAR ? path.get(i - 1) : m == Pathfinder.Move.BRIDGE ? path.get(i).below() : null;
            if (b != null && !b.equals(under) && scaffolds.contains(b) && b.getY() <= plan.topY(b.getX(), b.getZ())
                    && boxedIn(b)) {
                helpers.add(b.immutable());
            }
        }
        helpers.sort(Comparator.comparingInt(b -> -b.getY())); // oben zuerst
        for (BlockPos b : helpers) {
            if (!tempRemovals.contains(b)) {
                tempRemovals.addLast(b);
            }
        }
    }

    /**
     * Steht ein Hilfsblock mitten zwischen Farm-Bloecken (5x5 auf seiner Hoehe oder eins darueber dicht bebaut, z.B.
     * Kletterhilfe auf die Kisten-Ebene)? Dort wird er spaeter eingemauert - also gleich nach dem Weg wieder weg. Stege
     * im Freien (z.B. unter einem schwebenden Generator) bleiben bis zum Aufraeumen: sonst baute er sie je Block neu.
     */
    private boolean boxedIn(BlockPos b) {
        for (int dy = 0; dy <= 1; dy++) {
            int n = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (plan.stateAt(b.offset(dx, dy, dz)) != null && ++n >= 6) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Darf er die Kurve bei node Richtung next schneiden? Nur wenn die Eckfelder frei sind (Fuss- und Kopfhoehe) -
     * sonst lief er mit der Schulter gegen eine Glasecke (Kanal unter der Kisten-Ebene) und hing dort fest.
     */
    private static boolean cornerFree(ClientLevel level, BlockPos node, BlockPos next) {
        if (node.getX() == next.getX() && node.getZ() == next.getZ()) {
            return true;
        }
        BlockPos a = new BlockPos(next.getX(), node.getY(), node.getZ());
        BlockPos b = new BlockPos(node.getX(), node.getY(), next.getZ());
        for (BlockPos c : new BlockPos[]{a, b}) {
            // Auch kein Loch im Eckfeld (offene Rinne neben dem Weg): beim Schneiden rutschte er sonst hinein
            if (!c.equals(node) && !c.equals(new BlockPos(next.getX(), node.getY(), next.getZ()))
                    && (!Pathfinder.passable(level, c) || !Pathfinder.passable(level, c.above())
                    || next.getY() >= node.getY() && !Pathfinder.supports(level, c.below()))) {
                return false;
            }
        }
        // Gerade weiter: die Seitenfelder neben node spielen keine Rolle, nur ob next selbst frei ist
        return true;
    }

    private boolean reached(Vec3 pos, BlockPos node, boolean finalNode) {
        double h = horizontal(pos, center(node));
        double tolerance = finalNode ? 0.38D : 0.55D;
        // Am Ziel wirklich auf dessen Hoehe: sonst "kam er an", als er noch oben an der Kante stand (Kanal darunter),
        // und blieb dann geduckt dort haengen
        return h < tolerance && pos.y > node.getY() - 0.4D && pos.y < node.getY() + (finalNode ? 0.6D : 1.2D);
    }

    private boolean upcomingStepUp(int n) {
        for (int i = pathIndex; i < Math.min(path.size() - 1, pathIndex + n); i++) {
            if (path.get(i + 1).getY() > path.get(i).getY()) {
                return true;
            }
        }
        return false;
    }

    private double remainingDistance(Vec3 pos) {
        double sum = horizontal(pos, center(path.get(pathIndex)));
        for (int i = pathIndex; i < path.size() - 1; i++) {
            sum += Math.sqrt(path.get(i).distSqr(path.get(i + 1)));
        }
        return sum;
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void arrive(LocalPlayer player, ClientLevel level) {
        input = idleInput();
        switch (goal == null ? Goal.PLACE : goal) {
            case PLACE -> {
                if (target == null) {
                    go(Step.CHOOSE);
                    return;
                }
                if (blocksMe(player, level, target) && !centered && arrivedNode != null) {
                    centered = true; // steht noch mit einer Fussspitze im Zielfeld: erst in die Mitte ruecken
                    go(Step.CENTER);
                    return;
                }
                if (arriveSpot != null && !spotCentered) {
                    spotCentered = true; // nur von einer bestimmten Stelle im Feld aus richtig ausgerichtet
                    crouchWait = 0;
                    if (horizontal(player.position(), arriveSpot) > 0.05D) {
                        go(Step.CENTER);
                        return;
                    }
                }
                if (arriveCrouch && !player.isCrouching() && crouchWait++ < 15) {
                    input = new Input(false, false, false, false, false, true, false); // erst ducken (Auge tiefer)
                    return;
                }
                Placer.Aim a = blocksMe(player, level, target) ? null : placer.planPlacement(level, player,
                        player.getEyePosition(), target.pos(), target.state(), new ItemStack(target.need().item()),
                        strict(target.pos()));
                if (a != null && arriveCrouch) {
                    a = new Placer.Aim(a.clicked(), a.face(), a.hit(), a.yaw(), a.pitch(), true); // geduckt bleiben
                }
                if (a == null || wouldTrap(level, player, feetNode(player), target.pos(), target.state())
                        || !targetIsScaffold && cutsOffWork(level, player, target)) {
                    failTask(level, "no angle");
                    return;
                }
                currentAim = a;
                go(Step.SELECT);
            }
            case ESCAPE -> {
                status = "";
                escapeTries = 0;
                go(Step.CHOOSE);
            }
            case MOVE -> {
                status = "";
                go(Step.CHOOSE); // neuer Platz - jetzt die offenen Bloecke von hier aus versuchen
            }
            case MINE -> go(Step.MINE);
            case CLICK -> go(Step.VERIFY);
            case CHEST -> go(Step.CHEST_OPEN);
            case FLUID -> go(Step.FLUID_AIM);
            case REFILL -> go(Step.REFILL);
            case CRAFTER -> go(Step.CRAFTER_OPEN);
        }
    }

    /** Aufgabe klappt gerade nicht: zurueckstellen und etwas anderes machen. */
    private void failTask(ClientLevel level, String why) {
        FarmLog.line("FAIL " + why + " step=" + step + " goal=" + goal
                + " target=" + (target == null ? "-" : target.pos().toShortString() + " " + target.state().getBlock().getName().getString())
                + " mine=" + (minePos == null ? "-" : minePos.toShortString())
                + " attempts=" + (target == null ? 0 : attempts.getOrDefault(target.pos(), 0))
                + (mc.player == null ? "" : " feet=" + feetNode(mc.player).toShortString()));
        releaseMining();
        if (step == Step.CRAFTER_OPEN || step == Step.CRAFTER_WAIT || step == Step.CRAFTER_SLOTS
                || step == Step.WALK && goal == Goal.CRAFTER) {
            crafterFailed(why);
            return;
        }
        if (target != null && !targetIsScaffold && mc.player != null
                && (why.equals("no angle") || why.equals("lost angle") || why.equals("aim timeout"))) {
            // Von hier aus ging es vor Ort nicht (obwohl die Planung es dachte): fuer diesen Block nie wieder hierher.
            // Versetzte Stellen (Crafter nach unten) erst beim zweiten Mal - oft gibt es nur zwei, und es lag nur an
            // ein paar Zentimetern beim Ankommen
            BlockPos feet = feetNode(mc.player);
            boolean secondMiss = arriveSpot == null || spotMisses.merge(target.pos().asLong() * 31 + feet.asLong(), 1,
                    Integer::sum) >= 2;
            if (secondMiss) {
                badStands.computeIfAbsent(target.pos().immutable(), k -> new HashSet<>()).add(feet);
            }
        }
        if (target != null) {
            postpone(target.pos(), 100);
            if (targetIsScaffold && resumeTarget != null) {
                postpone(resumeTarget.pos(), 120);
                resumeTarget = null;
            }
            if (targetIsScaffold) {
                supportQueue.clear(); // Kette abgebrochen - beim naechsten Versuch nimmt unstick einen anderen Nachbarn
            }
        }
        boolean walking = step == Step.WALK;
        if (minePos != null && (step == Step.MINE || walking && goal == Goal.MINE)) {
            defer(minePos, 200);
            if (resumeTarget != null) {
                // Falscher Block auf einem Zielplatz geht nicht weg: das Ziel zaehlt als gescheitert
                postpone(resumeTarget.pos(), 120);
                resumeTarget = null;
            }
        }
        if (step == Step.CHEST_OPEN || step == Step.CHEST_WAIT || walking && goal == Goal.CHEST) {
            // Kiste nicht erreichbar: naechste versuchen
            chestPos = null;
            if (!nextChest()) {
                finishRestock();
            }
            return;
        }
        target = null;
        minePos = null;
        path = null;
        go(Step.CHOOSE);
        wait = delay(3, 8);
    }

    // ================================================================ Item in die Hand

    private void select(LocalPlayer player) {
        Item item = target == null ? null : target.need().item();
        if (item == null) {
            go(Step.CHOOSE);
            return;
        }
        if (prepareItem(player, item, Step.AIM)) {
            go(Step.AIM);
        }
    }

    /**
     * Sorgt dafuer, dass item in der Hand ist. true = schon erledigt. Sonst wird gewechselt (mit kurzer Pause) oder
     * das Inventar geoeffnet; danach geht es mit "then" weiter.
     */
    private boolean prepareItem(LocalPlayer player, Item item, Step then) {
        var inv = player.getInventory();
        // Fast kaputte Werkzeuge zaehlen nicht (die wuerden beim Benutzen zerbrechen)
        if (inv.getSelectedItem().is(item) && Tools.usable(inv.getSelectedItem())) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item) && Tools.usable(inv.getItem(i))) {
                inv.setSelectedSlot(i);
                wait = delay(2, 6);
                go(then);
                return false;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i).is(item) && Tools.usable(inv.getItem(i))) {
                swapFrom = i;
                swapTo = hotbarSlotToReplace(player);
                wantedHotbarItem = item;
                afterInventory = then;
                go(Step.INV_OPEN);
                return false;
            }
        }
        // Nicht da -> holen
        if (mc.level != null) {
            beginRestock(player, mc.level, item);
        }
        return false;
    }

    /** Welcher Hotbar-Platz darf ueberschrieben werden? Der, dessen Item am wenigsten gebraucht wird. */
    private int hotbarSlotToReplace(LocalPlayer player) {
        Map<Item, Integer> upcoming = upcomingNeeds(400, true);
        int best = 8;
        int bestNeed = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            int need = stack.isEmpty() ? -1 : upcoming.getOrDefault(stack.getItem(), 0);
            if (isTool(stack)) {
                need += 50; // Werkzeuge lieber behalten
            }
            if (need < bestNeed) {
                bestNeed = need;
                best = i;
            }
        }
        return best;
    }

    private void invOpen(LocalPlayer player) {
        input = idleInput();
        if (!(mc.gui.screen() instanceof InventoryScreen)) {
            mc.gui.setScreen(new InventoryScreen(player));
            wait = delay(5, 10);
            return;
        }
        go(Step.INV_SWAP);
    }

    private void invSwap(LocalPlayer player) {
        if (!(mc.gui.screen() instanceof InventoryScreen)) {
            go(afterInventory == null ? Step.CHOOSE : afterInventory);
            return;
        }
        int slot = menuSlot(player.inventoryMenu, player, swapFrom);
        if (slot >= 0 && mc.gameMode != null) {
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, swapTo, ContainerInput.SWAP, player);
        }
        wait = delay(4, 9);
        go(Step.INV_CLOSE);
    }

    private void invClose(LocalPlayer player) {
        if (mc.gui.screen() instanceof InventoryScreen) {
            player.closeContainer();
        }
        player.getInventory().setSelectedSlot(swapTo);
        wait = delay(3, 6);
        go(afterInventory == null ? Step.CHOOSE : afterInventory);
    }

    // ================================================================ Zielen + Klicken

    private void aimAndClick(LocalPlayer player, ClientLevel level) {
        if (target == null || currentAim == null) {
            go(Step.CHOOSE);
            return;
        }
        if (!player.getMainHandItem().is(target.need().item())) {
            go(Step.SELECT);
            return;
        }
        BlockState have = level.getBlockState(target.pos());
        if (!have.isAir() && !have.canBeReplaced() && Placer.countMissing(have, target.state()) == 0) {
            go(Step.VERIFY); // schon da (eine halbe Stufe o.Ae. wird dagegen ganz normal vervollstaendigt)
            return;
        }
        boolean sneak = currentAim.sneak() || Pathfinder.nearDrop(level, player.blockPosition());
        input = new Input(false, false, false, false, false, sneak, false);
        if (stepTicks > 80) {
            failTask(level, "aim timeout");
            return;
        }
        // Unter niedriger Decke bleibt er gebueckt, auch ohne Taste - darauf nicht ewig warten ("aim timeout")
        boolean forcedCrouch = !sneak && player.isCrouching() && !level.noCollision(player,
                player.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(player.position()).deflate(1.0E-7D));
        if (sneak != player.isCrouching() && !forcedCrouch) {
            return; // Haltung aendert sich noch (Augenhoehe!)
        }
        if (!sneakReplanned) {
            sneakReplanned = true;
            Placer.Aim fresh = placer.planPlacement(level, player, player.getEyePosition(), target.pos(), target.state(),
                    new ItemStack(target.need().item()), strict(target.pos()));
            if (fresh != null) {
                currentAim = new Placer.Aim(fresh.clicked(), fresh.face(), fresh.hit(), fresh.yaw(), fresh.pitch(),
                        fresh.sneak() || sneak);
            }
        }
        aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
        if (!aim.settled(player)) {
            settledTicks = 0;
            return;
        }
        // Schleichen muss beim Server angekommen sein, ausserdem eine kleine Reaktionszeit
        if (++settledTicks < (sneak ? 3 : 2) + reaction) {
            return;
        }
        // Was trifft das Fadenkreuz wirklich? Genau diesen Treffer senden (wie ein echter Klick).
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !hit.getBlockPos().equals(currentAim.clicked()) || hit.getDirection() != currentAim.face()) {
            replanAim(player, level);
            return;
        }
        BlockState result = Placer.simulate(level, player, player.getMainHandItem(), hit, player.getYRot(),
                player.getXRot(), sneak, target.pos());
        if (result == null || !Placer.acceptable(level, target.pos(), result, target.state(), strict(target.pos()))) {
            replanAim(player, level);
            return;
        }
        click(player, hit);
        go(Step.VERIFY);
    }

    private void replanAim(LocalPlayer player, ClientLevel level) {
        Placer.Aim a = placer.planPlacement(level, player, player.getEyePosition(), target.pos(), target.state(),
                new ItemStack(target.need().item()), strict(target.pos()));
        if (a == null || stepTicks > 60) {
            failTask(level, "lost angle");
            return;
        }
        currentAim = a;
        settledTicks = 0;
    }

    private void click(LocalPlayer player, BlockHitResult hit) {
        if (mc.gameMode == null) {
            return;
        }
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            Compat.swingUse(player, InteractionHand.MAIN_HAND);
        }
    }

    /** Strahl aus dem Auge in Blickrichtung (wie das Fadenkreuz), ohne Fluessigkeiten. */
    private BlockHitResult crosshair(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(Placer.reach(player) + 0.3D));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private int reactionTicks() {
        // Menschen reagieren unterschiedlich schnell; selten deutlich langsamer
        double g = Math.abs(random.nextGaussian()) * 1.3D;
        int extra = random.nextFloat() < 0.06F ? 3 + random.nextInt(6) : 0;
        return (int) Math.round((g + extra) / settings.speed);
    }

    // ================================================================ Pruefen nach dem Setzen

    private void verify(LocalPlayer player, ClientLevel level) {
        if (target == null) {
            go(Step.CHOOSE);
            return;
        }
        BlockState have = level.getBlockState(target.pos());
        BlockState want = target.state();
        if (targetIsScaffold) {
            if (have.is(want.getBlock())) {
                progress();
                scaffolds.add(target.pos());
                guard.touch(target.pos()); // eigene Bloecke kommen vom Server nicht als Aenderung (Client hat sie vorhergesagt)
                leaveCache.clear();
                neighborChanged(target.pos());
                supportQueue.remove(target.pos());
                BuildPlan.Target resume = resumeTarget;
                if (!supportQueue.isEmpty() && resume != null) {
                    // Kette noch nicht fertig: naechster Hilfsblock (liegt jetzt an diesem an)
                    wait = delay(2, 5);
                    if (nextSupport(player, level, resume)) {
                        return;
                    }
                    resumeTarget = resume; // Kette ging nicht weiter - den Zielblock trotzdem jetzt versuchen
                }
                target = resumeTarget;
                resumeTarget = null;
                targetIsScaffold = false;
                wait = delay(3, 7);
                if (target != null) {
                    startPlacing(player, level, target, true);
                } else {
                    go(Step.CHOOSE);
                }
            } else if (stepTicks > 14) {
                failTask(level, "scaffold");
            }
            return;
        }
        if (Placer.done(have, want) && phase == Phase.CLEANUP) {
            // Gelaende wieder hingesetzt (zaehlt nicht zur Farm)
            progress();
            restore.remove(target.pos());
            target = null;
            go(Step.CHOOSE);
            wait = betweenBlocks();
            return;
        }
        if (Placer.done(have, want)) {
            progress();
            placedSession++;
            attempts.remove(target.pos());
            fixupTries.remove(target.pos());
            clickWalks.remove(target.pos());
            doneCount++;
            remainingItems = Math.max(0, remainingItems - target.need().count());
            pending.remove(target);
            pendingAt.remove(target.pos());
            roundPlaced++;
            FarmLog.line("PLACED " + target.pos().toShortString() + " " + want.getBlock().getName().getString());
            released(target.pos());
            badStands.remove(target.pos());
            triedAnchors.remove(target.pos());
            needsChange.remove(target.pos());
            neighborChanged(target.pos());
            // Hilfsbloecke, die nur fuer diesen Block in der Farm standen: jetzt wieder weg (letzter zuerst)
            List<BlockPos> temps = supportTemps.remove(target.pos());
            if (temps != null) {
                for (int i = temps.size() - 1; i >= 0; i--) {
                    tempRemovals.addLast(temps.get(i));
                }
            }
            if (plan.crafterSlots.containsKey(target.pos())) {
                // Crafter (auch neu gesetzt, z.B. nach dem Abbauen fuer einen Weg): gesperrte Felder sofort einstellen,
                // solange man davor steht
                crafterOk.remove(target.pos());
                crafterTodo.add(target.pos().immutable());
                Placer.Aim a = placer.planClickOn(level, player, player.getEyePosition(), target.pos(), null, false);
                if (a != null) {
                    BlockPos crafter = target.pos().immutable();
                    target = null;
                    startCrafter(crafter, a);
                    wait = betweenBlocks();
                    return;
                }
            }
            target = null;
            go(Step.CHOOSE);
            wait = betweenBlocks();
            return;
        }
        if (switchLater(have, want) && (hasOtherWork(level, target.pos()) || beforeFluids())) {
            // Hebel steht richtig - umgelegt wird erst, wenn alles andere fertig ist
            progress();
            placedSession++;
            roundPlaced++;
            attempts.remove(target.pos());
            pending.remove(target);
            pendingAt.remove(target.pos());
            FarmLog.line("PLACED (switch later) " + target.pos().toShortString());
            released(target.pos());
            target = null;
            go(Step.CHOOSE);
            wait = betweenBlocks();
            return;
        }
        // Ackerboden / Trampelpfad: Erde (oder Gras) liegt, jetzt mit Hacke/Schaufel bearbeiten
        if (Placer.toolConvertible(have, want)) {
            Item tool = findTool(player, want.is(Blocks.FARMLAND));
            if (tool == null) {
                missing.add(want.is(Blocks.FARMLAND) ? Items.WOODEN_HOE : Items.WOODEN_SHOVEL);
                givenUp.add(target.pos());
                target = null;
                go(Step.CHOOSE);
                return;
            }
            if (!prepareItem(player, tool, Step.VERIFY)) {
                return;
            }
            Placer.Aim a = placer.planClickOn(level, player, player.getEyePosition(), target.pos(), Direction.UP, false);
            if (a == null) {
                // Standplatz suchen, von dem aus genau die OBERSEITE sichtbar ist (sonst laeuft er ewig hin und her)
                clickFace = Direction.UP;
                if (!tooManyWalks(level) && !findStandAndWalk(player, level, target.pos(), Goal.CLICK, target)) {
                    failTask(level, "tool angle");
                }
                return;
            }
            startFixup(a, 1);
            return;
        }
        if (Placer.orientationMatches(have, want, false)) {
            int more = Placer.countMissing(have, want);
            if (more > 0) {
                // Zweite Stufe, weitere Kerze ...: wie ein normales Setzen planen - auf den vorhandenen Teil oder
                // einen Nachbarn klicken, von wo aus es eben geht (notfalls woanders hinlaufen oder etwas freiraeumen)
                if (stepTicks < 6) {
                    return; // eben geklickt? Erst die Antwort des Servers abwarten (sonst doppelt)
                }
                if (!prepareItem(player, target.need().item(), Step.VERIFY)) {
                    return;
                }
                if (clickWalks.merge(target.pos(), 1, Integer::sum) > 6) {
                    clickWalks.remove(target.pos());
                    failTask(level, "count retries");
                    return;
                }
                ItemStack stack = new ItemStack(target.need().item());
                Placer.Aim a = blocksMe(player, level, target) ? null : placer.planPlacement(level, player,
                        player.getEyePosition(), target.pos(), want, stack, strict(target.pos()));
                if (a != null && !wouldTrap(level, player, feetNode(player), target.pos(), want)) {
                    currentAim = a;
                    go(Step.SELECT);
                    return;
                }
                if (findStandAndWalk(player, level, target.pos(), Goal.PLACE, target) || clearView(player, level, target)) {
                    return;
                }
                if (lastValidStands == 0 && Drops.dropsItself(have) && !have.hasBlockEntity()
                        && have.getDestroySpeed(level, target.pos()) >= 0.0F) {
                    // Die vorhandene Haelfte laesst sich von nirgends vervollstaendigen (z.B. obere Stufe ueber einem
                    // Trichter): abbauen und richtig herum neu setzen
                    FarmLog.line("REDO partial " + target.pos().toShortString() + " " + have);
                    startMining(player, level, target.pos(), target);
                    return;
                }
                failTask(level, "count angle");
                return;
            }
            Property<?> property = Placer.fixup(have, want);
            if (property != null) {
                Placer.Aim a = placer.planClickOn(level, player, player.getEyePosition(), target.pos(), null, false);
                if (a == null) {
                    clickFace = null;
                    if (!tooManyWalks(level) && !findStandAndWalk(player, level, target.pos(), Goal.CLICK, target)) {
                        failTask(level, "fixup angle");
                    }
                    return;
                }
                startFixup(a, Placer.fixupClicks(have, want, property));
                return;
            }
        }
        if (have.isAir() || have.canBeReplaced()) {
            // Server hat (noch) nichts gesetzt
            if (stepTicks > 14) {
                attempts.merge(target.pos(), 1, Integer::sum);
                if (attempts.get(target.pos()) > 4) {
                    givenUp.add(target.pos());
                    target = null;
                    go(Step.CHOOSE);
                } else {
                    failTask(level, "not placed");
                }
            }
            return;
        }
        // Falscher Block oder falsch herum: abbauen und neu setzen (begrenzt oft)
        if (stepTicks < 6) {
            return;
        }
        int n = attempts.merge(target.pos(), 1, Integer::sum);
        if (n > 3) {
            givenUp.add(target.pos());
            NovaNotify.chat("Schematic Builder", "§7Could not place " + have.getBlock().getName().getString() + " at "
                    + target.pos().toShortString() + " correctly - skipped.");
            target = null;
            go(Step.CHOOSE);
            return;
        }
        startMining(player, level, target.pos(), target);
    }

    /**
     * Schon mehrmals fuer denselben Klick hingelaufen (bzw. vervollstaendigt), ohne dass es dann ging? Dann diesen
     * Block zurueckstellen, statt endlos zwischen zwei Plaetzen hin- und herzulaufen. true = zurueckgestellt.
     */
    private boolean tooManyWalks(ClientLevel level) {
        if (target != null && clickWalks.merge(target.pos(), 1, Integer::sum) > 4) {
            clickWalks.remove(target.pos());
            failTask(level, "keeps walking for a click");
            return true;
        }
        return false;
    }

    /**
     * Rechtsklicks auf den gesetzten Block starten. Aendert sich trotz vieler Runden nichts (z.B. Ackerboden mit einem
     * Block darueber), wird der Block uebersprungen - sonst pendelt der Bot ewig zwischen Pruefen und Klicken.
     */
    private void startFixup(Placer.Aim a, int clicks) {
        if (fixupTries.merge(target.pos(), 1, Integer::sum) > 12) {
            givenUp.add(target.pos());
            NovaNotify.chat("Schematic Builder", "§7Could not finish " + target.state().getBlock().getName().getString()
                    + " at " + target.pos().toShortString() + " - skipped.");
            target = null;
            go(Step.CHOOSE);
            return;
        }
        currentAim = a;
        fixupClicksLeft = clicks;
        go(Step.FIXUP);
    }

    /** Rechtsklicks auf den gesetzten Block (Verzoegerung, Modus, zweite Stufe, Hacke ...). */
    private void fixup(LocalPlayer player, ClientLevel level) {
        if (target == null || currentAim == null) {
            go(Step.CHOOSE);
            return;
        }
        input = new Input(false, false, false, false, false, false, false);
        aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
        if (stepTicks > 100) {
            failTask(level, "fixup timeout");
            return;
        }
        if (!aim.settled(player) || ++settledTicks < 2 + reaction) {
            return;
        }
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !hit.getBlockPos().equals(currentAim.clicked())) {
            Placer.Aim a = placer.planClickOn(level, player, player.getEyePosition(), currentAim.clicked(),
                    currentAim.face() == Direction.UP || currentAim.face() == Direction.DOWN ? currentAim.face() : null, false);
            if (a == null) {
                failTask(level, "fixup lost");
                return;
            }
            currentAim = a;
            settledTicks = 0;
            return;
        }
        click(player, hit);
        fixupClicksLeft--;
        settledTicks = 0;
        if (fixupClicksLeft <= 0) {
            go(Step.VERIFY);
        }
        wait = delay(3, 7);
    }

    private int betweenBlocks() {
        double base = 3.0D + Math.abs(random.nextGaussian()) * 2.5D;
        if (random.nextFloat() < 0.05F) {
            base += 6 + random.nextInt(14);
        }
        return Math.max(1, (int) Math.round(base / settings.speed));
    }

    // ================================================================ Crafter einstellen

    /** Offener Crafter, der von hier aus anklickbar ist? Dann gleich einstellen. true = angefangen. */
    private boolean crafterHere(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        double reach = Placer.reach(player);
        for (BlockPos p : crafterTodo) {
            if (crafterTries.getOrDefault(p, 0) >= CRAFTER_TRIES || eye.distanceToSqr(Vec3.atCenterOf(p)) > reach * reach) {
                continue;
            }
            Placer.Aim a = placer.planClickOn(level, player, eye, p, null, false);
            if (a != null) {
                startCrafter(p, a);
                return true;
            }
        }
        return false;
    }

    /** Naechsten offenen Crafter ansteuern (vor dem Wasser bzw. am Ende). false = keiner mehr (oder keiner erreichbar). */
    private boolean nextCrafter(LocalPlayer player, ClientLevel level) {
        crafterTodo.removeIf(p -> !(level.getBlockState(p).getBlock() instanceof CrafterBlock));
        // Hoechstens ein paar teure Standplatz-Suchen je Aufruf und jeden Crafter nur einmal: frueher probierte die
        // Schleife eingemauerte Crafter immer wieder im selben Tick (6000 Suchen in 10 Minuten, nichts ging voran)
        Set<BlockPos> triedNow = new HashSet<>();
        for (int round = 0; round < 3; round++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (BlockPos p : crafterTodo) {
                double d = player.distanceToSqr(Vec3.atCenterOf(p));
                if (!triedNow.contains(p) && crafterTries.getOrDefault(p, 0) < CRAFTER_TRIES * 2 && d < bestDistance) {
                    best = p;
                    bestDistance = d;
                }
            }
            if (best == null) {
                return false;
            }
            triedNow.add(best);
            Placer.Aim a = placer.planClickOn(level, player, player.getEyePosition(), best, null, false);
            if (a != null) {
                startCrafter(best, a);
                return true;
            }
            crafterPos = best;
            if (findStandAndWalk(player, level, best, Goal.CRAFTER, null)) {
                status = "walking to a crafter";
                return true;
            }
            crafterPos = null;
            crafterTries.merge(best, 1, Integer::sum);
            FarmLog.line("CRAFTER unreachable " + best.toShortString());
        }
        return false;
    }

    private void startCrafter(BlockPos pos, Placer.Aim a) {
        crafterPos = pos;
        currentAim = a;
        status = "setting up a crafter";
        go(Step.CRAFTER_OPEN);
    }

    private void crafterOpen(LocalPlayer player, ClientLevel level) {
        if (crafterPos == null || !(level.getBlockState(crafterPos).getBlock() instanceof CrafterBlock)) {
            if (crafterPos != null) {
                crafterTodo.remove(crafterPos); // Crafter ist weg - wird neu gesetzt und dann eingestellt
            }
            crafterPos = null;
            go(Step.CHOOSE);
            return;
        }
        if (currentAim == null || !currentAim.clicked().equals(crafterPos)) {
            currentAim = placer.planClickOn(level, player, player.getEyePosition(), crafterPos, null, false);
            if (currentAim == null) {
                crafterFailed("crafter angle");
                return;
            }
        }
        input = openInput(player);
        aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
        if (stepTicks > 80) {
            BlockHitResult seen = crosshair(player, level);
            crafterFailed("crafter timeout (shift=" + player.isShiftKeyDown() + " settled=" + aim.settled(player)
                    + " sees=" + (seen == null ? "-" : seen.getBlockPos().toShortString()) + ")");
            return;
        }
        // Nicht schleichen: sonst oeffnet der Klick nichts, sondern setzt den Block aus der Hand daneben. Zaehlt nur die
        // Taste - unter niedriger Decke bleibt er nach dem geduckten Setzen gebueckt (Haltung), das stoert nicht
        if (player.isShiftKeyDown() || !aim.settled(player) || ++settledTicks < 2 + reaction) {
            return;
        }
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !hit.getBlockPos().equals(crafterPos)) {
            currentAim = null;
            settledTicks = 0;
            return;
        }
        click(player, hit);
        go(Step.CRAFTER_WAIT);
    }

    private void crafterWait(ClientLevel level) {
        if (mc.gui.screen() instanceof CrafterScreen) {
            wait = delay(6, 12); // Menue kommt vom Server, ausserdem "schaut" man kurz
            go(Step.CRAFTER_SLOTS);
            return;
        }
        if (stepTicks > 50) {
            crafterFailed("crafter did not open");
        }
    }

    /** Je Schritt ein Feld umschalten - wie ein Klick auf ein leeres Feld im Crafter-Menue. */
    private void crafterSlots(LocalPlayer player, ClientLevel level) {
        if (!(mc.gui.screen() instanceof CrafterScreen screen) || mc.gameMode == null || crafterPos == null) {
            crafterFailed("crafter closed");
            return;
        }
        CrafterMenu menu = screen.getMenu();
        boolean[] off = new boolean[9];
        for (int slot : plan.crafterSlots.getOrDefault(crafterPos, new int[0])) {
            if (slot >= 0 && slot < 9) {
                off[slot] = true;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (menu.isSlotDisabled(slot) == off[slot]) {
                continue;
            }
            if (menu.getSlot(slot).hasItem()) {
                // Belegte Felder lassen sich nicht umschalten - bleibt fuer den Bericht offen
                FarmLog.line("CRAFTER slot " + slot + " at " + crafterPos.toShortString() + " holds items - cannot switch it");
                crafterTries.put(crafterPos, CRAFTER_TRIES * 2); // laesst sich nicht aendern - nicht wieder hinlaufen
                crafterPos = null;
                player.closeContainer();
                wait = delay(3, 8);
                go(Step.CHOOSE);
                return;
            }
            menu.setSlotState(slot, !off[slot]);
            mc.gameMode.handleSlotStateChanged(slot, menu.containerId, !off[slot]);
            wait = delay(4, 9); // Maus zum naechsten Feld
            return;
        }
        crafterOk.add(crafterPos);
        crafterTodo.remove(crafterPos);
        FarmLog.line("CRAFTER ok " + crafterPos.toShortString());
        progress();
        crafterPos = null;
        player.closeContainer();
        wait = delay(3, 8);
        go(Step.CHOOSE);
    }

    private void crafterFailed(String why) {
        FarmLog.line("CRAFTER " + why + " at " + (crafterPos == null ? "-" : crafterPos.toShortString()));
        if (crafterPos != null) {
            crafterTries.merge(crafterPos, 1, Integer::sum);
        }
        crafterPos = null;
        path = null;
        if (mc.player != null && mc.gui.screen() instanceof CrafterScreen) {
            mc.player.closeContainer();
        }
        go(Step.CHOOSE);
        wait = delay(3, 8);
    }

    // ================================================================ Abbauen

    private void startMining(LocalPlayer player, ClientLevel level, BlockPos pos, BuildPlan.Target resume) {
        if (tooSlowToMine(player, level, pos)) {
            // z.B. Obsidian (aus Lava + Wasser entstanden) ohne Diamant-Spitzhacke: nicht minutenlang daneben stehen
            defer(pos, 6000);
            if (resume != null) {
                postpone(resume.pos(), 6000);
            }
            if (slowWarned.add(pos.immutable())) {
                String name = level.getBlockState(pos).getBlock().getName().getString();
                FarmLog.line("MINE too slow " + pos.toShortString() + " " + name + " - skipped");
                NovaNotify.chat("Schematic Builder", "§e" + name + " at " + pos.toShortString()
                        + " is in the way and would take too long to break with my tools - please remove it.");
            }
            minePos = null;
            go(Step.CHOOSE);
            return;
        }
        minePos = pos.immutable();
        resumeTarget = resume;
        target = null;
        // Nie den Block unter den eigenen Fuessen abbauen, wenn es darunter tief runtergeht
        boolean underMe = player.blockPosition().below().equals(pos) && unsafeDrop(level, pos);
        Placer.Aim a = underMe ? null : placer.planMining(level, player, player.getEyePosition(), pos);
        if (a != null) {
            currentAim = a;
            go(Step.MINE);
            return;
        }
        if (!findStandAndWalk(player, level, pos, Goal.MINE, null)) {
            defer(pos, 200);
            if (resume != null) {
                postpone(resume.pos(), 120);
                resumeTarget = null;
            }
            minePos = null;
            go(Step.CHOOSE);
        }
    }

    private void mine(LocalPlayer player, ClientLevel level) {
        if (minePos == null) {
            go(Step.CHOOSE);
            return;
        }
        BlockState state = level.getBlockState(minePos);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, minePos).isEmpty()) {
            // Weg!
            progress();
            releaseMining();
            scaffolds.remove(minePos);
            guard.touch(minePos);
            leaveCache.clear();
            neighborChanged(minePos);
            minePos = null;
            wait = delay(3, 8);
            if (phase != Phase.BUILD && phase != Phase.BUILD2) {
                resumeTarget = null;
            }
            go(Step.CHOOSE); // chooseBuild macht mit resumeTarget weiter
            return;
        }
        if (stepTicks > 400) {
            failTask(level, "mining timeout");
            return;
        }
        if (!mining && player.blockPosition().below().equals(minePos) && unsafeDrop(level, minePos)) {
            // Steht auf dem Block, darunter geht es tief runter: woanders hinstellen
            if (!findStandAndWalk(player, level, minePos, Goal.MINE, null)) {
                failTask(level, "unsafe to mine");
            }
            return;
        }
        // Bestes Werkzeug in die Hand
        if (!mining) {
            Item tool = bestToolFor(player, state);
            if (tool != null && !prepareItem(player, tool, Step.MINE)) {
                return;
            }
        }
        if (currentAim == null || stepTicks % 20 == 0 && !mining) {
            currentAim = placer.planMining(level, player, player.getEyePosition(), minePos);
            if (currentAim == null) {
                if (!findStandAndWalk(player, level, minePos, Goal.MINE, null)) {
                    failTask(level, "cannot reach block");
                }
                return;
            }
        }
        input = new Input(false, false, false, false, false, Pathfinder.nearDrop(level, player.blockPosition()), false);
        aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
        if (!mining) {
            if (!aim.settled(player) || ++settledTicks < 2 + reaction) {
                return;
            }
            BlockHitResult hit = crosshair(player, level);
            if (hit == null || !hit.getBlockPos().equals(minePos)) {
                currentAim = placer.planMining(level, player, player.getEyePosition(), minePos);
                settledTicks = 0;
                if (currentAim == null) {
                    failTask(level, "mine lost");
                }
                return;
            }
            mineFace = hit.getDirection();
            mc.gameMode.startDestroyBlock(minePos, mineFace);
            mining = true;
        } else {
            mc.gameMode.continueDestroyBlock(minePos, mineFace);
            lastActivity = tick; // harte Bloecke brauchen - das ist kein Haengen
        }
        Compat.swing(player, InteractionHand.MAIN_HAND);
    }

    /** Braucht der Block mit dem besten Werkzeug im Inventar laenger als ~30 s (z.B. Obsidian mit Eisen-Spitzhacke)? */
    private boolean tooSlowToMine(LocalPlayer player, ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0F || state.getDestroySpeed(level, pos) < 3.0F) {
            return false; // weiche Bloecke nie (schnell genug), Grundgestein regeln andere
        }
        float hardness = state.getDestroySpeed(level, pos);
        float best = 1.0F;
        boolean canHarvest = !state.requiresCorrectToolForDrops();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!Tools.usable(stack)) {
                continue;
            }
            best = Math.max(best, stack.getDestroySpeed(state));
            canHarvest |= stack.isCorrectToolForDrops(state);
        }
        // Vanilla: Fortschritt je Tick = Tempo / Haerte / (30 mit passendem Werkzeug, sonst 100)
        float perTick = best / hardness / (canHarvest ? 30.0F : 100.0F);
        return perTick <= 0.0F || 1.0F / perTick > 600.0F;
    }

    private void releaseMining() {
        if (mining && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        mining = false;
    }

    private Item bestToolFor(LocalPlayer player, BlockState state) {
        float best = 1.0F;
        Item item = null;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!Tools.usable(stack)) {
                continue; // fast kaputt - nicht benutzen
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > best + 0.01F) {
                best = speed;
                item = stack.getItem();
            }
        }
        return item;
    }

    private static boolean isTool(ItemStack stack) {
        return !stack.isEmpty() && (stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) > 1.0F
                || stack.getDestroySpeed(Blocks.DIRT.defaultBlockState()) > 1.0F
                || stack.getDestroySpeed(Blocks.OAK_LOG.defaultBlockState()) > 1.0F
                || stack.is(net.minecraft.tags.ItemTags.HOES));
    }

    private Item findTool(LocalPlayer player, boolean hoe) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if ((hoe && stack.is(net.minecraft.tags.ItemTags.HOES)
                    || !hoe && stack.is(net.minecraft.tags.ItemTags.SHOVELS)) && Tools.usable(stack)) {
                return stack.getItem();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Bereich freimachen

    private void buildClearList() {
        ClientLevel level = mc.level;
        clearList = new ArrayList<>();
        if (level == null) {
            return;
        }
        BlockPos min = plan.origin;
        BlockPos max = plan.max();
        for (int y = max.getY(); y >= min.getY(); y--) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (needsClearing(level, pos)) {
                        clearList.add(pos);
                    }
                }
            }
        }
    }

    private boolean needsClearing(ClientLevel level, BlockPos pos) {
        BlockState have = level.getBlockState(pos);
        if (have.isAir() || !have.getFluidState().isEmpty() && have.getCollisionShape(level, pos).isEmpty()
                || have.getDestroySpeed(level, pos) < 0.0F || scaffolds.contains(pos)) {
            return false;
        }
        BlockState want = plan.stateAt(pos);
        if (want == null) {
            return true;
        }
        if (have.canBeReplaced()) {
            return false; // Gras usw. wird beim Setzen ersetzt
        }
        return !Placer.sameFamily(have, want) && Materials.of(want).kind() != Materials.Kind.SKIP;
    }

    private void chooseClear(LocalPlayer player, ClientLevel level) {
        clearList.removeIf(pos -> !needsClearing(level, pos));
        if (clearList.isEmpty()) {
            enter(Phase.BUILD);
            return;
        }
        int top = Integer.MIN_VALUE;
        for (BlockPos pos : clearList) {
            if (!deferred(pos)) {
                top = Math.max(top, pos.getY());
            }
        }
        if (top == Integer.MIN_VALUE) {
            // Alles Uebrige ist gerade zurueckgestellt: nicht lange warten - falsche Bloecke auf Bauplaetzen
            // werden beim Bauen sowieso noch abgebaut
            if (stepTicks > 100) {
                enter(Phase.BUILD);
            }
            wait = 10;
            return;
        }
        status = clearList.size() + " blocks in the way";
        Vec3 eye = player.getEyePosition();
        final int topY = top;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : clearList) {
            if (pos.getY() < topY - 1 || deferred(pos)) {
                continue;
            }
            double d = pos.distToCenterSqr(eye.x, eye.y, eye.z) + random.nextDouble() * 3.0D;
            if (d < bestD) {
                bestD = d;
                best = pos;
            }
        }
        if (best != null) {
            startMining(player, level, best, null);
        }
    }

    // ---------------------------------------------------------------- Aufraeumen

    private void chooseCleanup(LocalPlayer player, ClientLevel level) {
        scaffolds.removeIf(pos -> level.getBlockState(pos).isAir());
        if (scaffolds.isEmpty()) {
            // Hilfsbloecke sind weg - jetzt noch das Gelaende zurueck, durch das er sich gegraben hat
            if (chooseRestore(player, level)) {
                return;
            }
            enter(Phase.DONE);
            finish();
            return;
        }
        if (onTowerTop(player)) {
            startDigDown();
            return;
        }
        BlockPos best = null;
        int soonest = Integer.MAX_VALUE;
        for (BlockPos pos : scaffolds) {
            if (deferred(pos)) {
                soonest = Math.min(soonest, deferredUntil.get(pos));
                continue;
            }
            // Gleich hoch: innen zuerst, dann nach aussen - so baut er einen Steg (z.B. unter dem Generator) rueckwaerts
            // ab wie ein Mensch, statt sich selbst den Weg zum Rest abzuschneiden
            if (best == null || pos.getY() > best.getY()
                    || pos.getY() == best.getY() && edgeDistance(pos) > edgeDistance(best)) {
                best = pos;
            }
        }
        if (best == null && soonest - tick <= 400) {
            // Nur noch zurueckgestellte (ging eben nicht): kurz warten, dann noch einmal - der Wachhund zaehlt mit
            lastActivity = tick;
            status = scaffolds.size() + " helper blocks left (retrying soon)";
            wait = delay(10, 20);
            return;
        }
        if (best == null) {
            NovaNotify.chat("Schematic Builder", "§7Could not remove " + scaffolds.size() + " helper blocks (e.g. at "
                    + scaffolds.iterator().next().toShortString() + ").");
            scaffolds.clear();
            return;
        }
        status = scaffolds.size() + " helper blocks left";
        startMining(player, level, best, null);
    }

    /** Wie weit liegt pos innen in der Farm (0 = am Rand, negativ = draussen)? */
    private int edgeDistance(BlockPos pos) {
        BlockPos max = plan.max();
        return Math.min(Math.min(pos.getX() - plan.origin.getX(), max.getX() - pos.getX()),
                Math.min(pos.getZ() - plan.origin.getZ(), max.getZ() - pos.getZ()));
    }

    /**
     * Gelaende, durch das er sich fuer einen Weg gegraben hat, wieder hinsetzen (unterste zuerst). Nimmt den Block
     * selbst, sonst das, was er gedroppt hat (Stein -> Bruchstein, Gras -> Erde). true = ist dran.
     */
    private boolean chooseRestore(LocalPlayer player, ClientLevel level) {
        restore.entrySet().removeIf(e -> {
            BlockState have = level.getBlockState(e.getKey());
            return !level.isLoaded(e.getKey()) || givenUp.contains(e.getKey()) || !have.isAir() && !have.canBeReplaced();
        });
        if (restore.isEmpty()) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        boolean anyDeferred = false;
        for (BlockPos p : restore.keySet()) {
            if (deferred(p)) {
                anyDeferred = true;
                continue;
            }
            double score = p.getY() * 100.0D + p.distToCenterSqr(eye.x, eye.y, eye.z);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best == null) {
            if (anyDeferred && stepTicks < 200) {
                wait = delay(6, 12);
                return true;
            }
            NovaNotify.chat("Schematic Builder", "§7Could not put back " + restore.size() + " block(s) I dug through.");
            restore.clear();
            return false;
        }
        BlockState original = restore.get(best);
        Item item = null;
        BlockState place = null;
        Item own = original.getBlock().asItem();
        if (own instanceof BlockItem && countInventory(player, own) > 0) {
            item = own;
            place = original;
        } else {
            Item drop = restoreItem(original);
            if (drop instanceof BlockItem blockItem && countInventory(player, drop) > 0) {
                item = drop;
                place = blockItem.getBlock().defaultBlockState();
            }
        }
        if (item == null) {
            restore.remove(best); // nichts mehr davon im Inventar - auslassen
            return true;
        }
        status = restore.size() + " dug-out block(s) to put back";
        startPlacing(player, level, new BuildPlan.Target(best, place, new Materials.Need(item, 1, Materials.Kind.PLACE), false),
                true);
        return true;
    }

    private void finish() {
        givenUp.removeIf(p -> !plan.contains(p)); // zurueckgesetztes Gelaende zaehlt nicht zur Farm
        input = null;
        releaseMining();
        refreshProgress();
        StringBuilder sb = new StringBuilder("§aDone! §7" + doneCount + "/" + totalCount + " blocks placed");
        int notPlaced = 0;
        BlockPos example = null;
        for (BuildPlan.Target t : pending) {
            if (!missing.contains(t.need().item())) {
                notPlaced++;
                FarmLog.line("NOT PLACED " + t.pos().toShortString() + " " + t.state()
                        + " now=" + (mc.level == null ? "?" : mc.level.getBlockState(t.pos()).toString())
                        + " clearTries=" + clearTries.getOrDefault(t.pos(), 0));
                if (example == null) {
                    example = t.pos();
                }
            }
        }
        for (BlockPos p : givenUp) {
            FarmLog.line("GIVEN UP " + p.toShortString() + " want=" + plan.stateAt(p));
        }
        if (!givenUp.isEmpty() || notPlaced > 0) {
            sb.append(", ").append(givenUp.size() + notPlaced).append(" could not be placed");
            BlockPos at = example != null ? example : givenUp.iterator().next();
            sb.append(" (e.g. at ").append(at.toShortString()).append(")");
        }
        boolean leversInPending = false;
        for (BuildPlan.Target t : pending) {
            leversInPending |= t.state().getBlock() instanceof LeverBlock;
        }
        if (switchesWaiting > 0 && !leversInPending) {
            sb.append(". §e").append(switchesWaiting).append(" lever(s) left OFF on purpose§7 - flip them once the rest is built");
        }
        if (!missing.isEmpty()) {
            sb.append(". Missing: ");
            int i = 0;
            for (Item item : missing) {
                if (i++ > 0) {
                    sb.append(", ");
                }
                sb.append(nameOf(item));
            }
        }
        NovaNotify.chat("Schematic Builder", sb.toString());
        if (mc.level != null) {
            for (String line : checkReport(mc.level)) {
                NovaNotify.chat("Schematic Builder", line);
            }
        }
        NovaNotify.sound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.0F);
        FarmLog.line("FINISH " + sb.toString().replaceAll("§.", ""));
    }

    // ================================================================ Material holen

    /** Items fuer die naechsten Bloecke (in Bau-Reihenfolge), so viel wie ins Inventar passt. */
    /**
     * Pruefung am Ende: alles mit der Vorlage vergleichen und melden, was falsch ist oder was ein Bot nicht nachbauen
     * kann (Inhalte, Kreativ-Items, Kupferbirnen-Zustand). Jede Abweichung steht im Log.
     */
    private List<String> checkReport(ClientLevel level) {
        Map<String, Integer> wrong = new TreeMap<>();
        int fluidsMissing = 0;
        int bulbs = 0;
        int hotTnt = 0;
        for (BuildPlan.Target t : plan.targets) {
            BlockState have = level.getBlockState(t.pos());
            if (t.need().kind() == Materials.Kind.FLUID) {
                FluidState fluid = have.getFluidState();
                if (!fluid.isSource() || !fluid.getType().isSame(t.state().getFluidState().getType())) {
                    fluidsMissing++;
                    FarmLog.line("CHECK fluid " + t.pos().toShortString() + " want=" + t.state() + " have=" + have);
                }
                continue;
            }
            if (t.need().kind() != Materials.Kind.PLACE) {
                continue;
            }
            if (!Placer.done(have, t.state())) {
                wrong.merge(t.state().getBlock().getName().getString(), 1, Integer::sum);
                if (RedstoneOrder.isTnt(t.state()) && level.hasNeighborSignal(t.pos())) {
                    hotTnt++;
                }
                FarmLog.line("CHECK wrong " + t.pos().toShortString() + " want=" + t.state() + " have=" + have);
                continue;
            }
            if (t.waterlogged() && have.hasProperty(BlockStateProperties.WATERLOGGED)
                    && !have.getValue(BlockStateProperties.WATERLOGGED)) {
                fluidsMissing++;
            }
            if (t.state().getBlock() instanceof CopperBulbBlock && have.getBlock() instanceof CopperBulbBlock
                    && have.getValue(BlockStateProperties.LIT) != t.state().getValue(BlockStateProperties.LIT)) {
                bulbs++;
                FarmLog.line("CHECK bulb " + t.pos().toShortString() + " should be lit="
                        + t.state().getValue(BlockStateProperties.LIT));
            }
        }
        List<String> parts = new ArrayList<>();
        int wrongTotal = 0;
        for (int n : wrong.values()) {
            wrongTotal += n;
        }
        if (wrongTotal == 0 && fluidsMissing == 0) {
            parts.add("§aCheck: every block matches the schematic.");
        } else {
            StringBuilder w = new StringBuilder("§eCheck: ");
            if (wrongTotal > 0) {
                w.append(wrongTotal).append(" block(s) differ (");
                int i = 0;
                for (Map.Entry<String, Integer> e : wrong.entrySet()) {
                    if (i++ == 4) {
                        w.append(", ...");
                        break;
                    }
                    w.append(i > 1 ? ", " : "").append(e.getValue()).append("x ").append(e.getKey());
                }
                w.append(")");
            }
            if (fluidsMissing > 0) {
                w.append(wrongTotal > 0 ? ", " : "").append(fluidsMissing).append(" water/lava source(s) missing");
            }
            parts.add(w.append("§7 - details in schematic_builder.log").toString());
        }
        if (hotTnt > 0) {
            parts.add("§e" + hotTnt + " TNT left out§7 - the spot has redstone power and it would explode.");
        }
        int crafters = plan.crafterSlots.size();
        if (crafters > 0) {
            int ok = 0;
            for (BlockPos p : plan.crafterSlots.keySet()) {
                ok += crafterOk.contains(p) ? 1 : 0;
            }
            parts.add((ok == crafters ? "§7" : "§e") + "Crafters: " + ok + "/" + crafters + " slots set up"
                    + (ok == crafters ? "." : " - lock the rest by hand (see log)."));
        }
        if (bulbs > 0) {
            parts.add("§e" + bulbs + " copper bulb(s) are in the wrong on/off state§7 - give each one short redstone pulse.");
        }
        Schematic.Extras extras = plan.schematic.extras;
        if (extras.filledContainers() > 0) {
            parts.add("§7" + extras.filledContainers() + " container(s) had items in the schematic (it was saved while"
                    + " running) - not filled in.");
        }
        if (!extras.creativeItems().isEmpty()) {
            StringBuilder c = new StringBuilder("§eCreative-only items in containers: ");
            int i = 0;
            for (Map.Entry<String, Integer> e : extras.creativeItems().entrySet()) {
                c.append(i++ > 0 ? ", " : "").append(e.getValue()).append("x ").append(e.getKey().replace("minecraft:", ""));
            }
            parts.add(c.append("§7 - use a survival item instead.").toString());
        }
        FarmLog.line("CHECK " + String.join(" | ", parts).replaceAll("§.", ""));
        return parts;
    }

    private Map<Item, Integer> upcomingNeeds(int maxBlocks) {
        return upcomingNeeds(maxBlocks, false);
    }

    /**
     * Was die naechsten Bloecke brauchen. placeableNow: ohne Bloecke, die laut Redstone-Reihenfolge noch warten - die
     * spaeter holen, sonst ist das Inventar voll mit Beobachtern, die noch nicht duerfen, und fuer den Rest ist kein Platz.
     */
    private Map<Item, Integer> upcomingNeeds(int maxBlocks, boolean placeableNow) {
        Map<Item, Integer> need = new LinkedHashMap<>();
        int n = 0;
        List<BuildPlan.Target> list = phase == Phase.FLUIDS ? fluidPending : pending;
        boolean skipHeld = placeableNow && phase != Phase.FLUIDS && mc.level != null;
        for (BuildPlan.Target t : list) {
            // Zurueckgestellte bzw. schon mehrfach gescheiterte Bloecke zaehlen nicht: sonst schleppte er deren Material
            // (Trichter, Komparatoren ... fuer zaehe Reste weiter unten) stundenlang mit, und fuer das, was jetzt dran ist
            // (Generator), passte pro Kisten-Gang nur ein Stapel ins Inventar
            // Ebenso Reste unter der aktuellen Schicht (kommen erst in den Nacharbeits-Runden wieder dran) und alles mehr als
            // 2 Schichten darueber: vorher trug er 163 Trichter, 57 Werfer, 29 Crafter, Fliesen fuer y=132 ... mit und
            // legte fuer den Generator ausgerechnet Glas zurueck bzw. hatte keinen Platz mehr fuer Erde
            if (missing.contains(t.need().item()) || givenUp.contains(t.pos())
                    || phase != Phase.FLUIDS && (deferred(t.pos()) || attempts.getOrDefault(t.pos(), 0) >= LAYER_TRIES)
                    || phase == Phase.BUILD && currentLayer != Integer.MIN_VALUE
                    && (t.pos().getY() < currentLayer || t.pos().getY() > currentLayer + 2)
                    || skipHeld && orderWaits(mc.level, t)) {
                continue;
            }
            Item item = phase == Phase.FLUIDS ? Items.WATER_BUCKET : t.need().item();
            if (phase == Phase.FLUIDS && t.need().item() == Items.LAVA_BUCKET) {
                item = Items.LAVA_BUCKET;
            }
            need.merge(item, phase == Phase.FLUIDS ? 1 : t.need().count(), Integer::sum);
            if (++n >= maxBlocks) {
                break;
            }
        }
        return need;
    }

    private void beginRestock(LocalPlayer player, ClientLevel level, Item reason) {
        if (reason != null && tick < inventoryFullUntil && freeSlots(player) == 0) {
            // Eben erst war das Inventar zu voll dafuer: nicht wieder zu den Kisten laufen, erst Vorhandenes verbauen
            laterFor(reason, 200);
            wait = delay(5, 10);
            go(Step.CHOOSE);
            return;
        }
        Integer blockedUntil = reason == null ? null : unreachableUntil.get(reason);
        if (blockedUntil != null && blockedUntil > tick && feetNode(player).equals(unreachableFrom)) {
            // Eben kam er von genau hier aus nicht an die Kisten: nicht im Sekundentakt wieder alle Wege suchen
            wait = delay(5, 10);
            go(Step.CHOOSE);
            return;
        }
        restockReason = reason;
        scanOnly = false;
        chestsOpened = 0;
        inventoryStuck = false;
        shopping = planShopping(player, reason);
        chestQueue.clear();
        List<BlockPos> withItems = new ArrayList<>();
        List<BlockPos> unknown = new ArrayList<>();
        for (BlockPos chest : store.chests()) {
            boolean useful = false;
            for (Item item : shopping.keySet()) {
                if (store.count(chest, item) > 0) {
                    useful = true;
                    break;
                }
            }
            if (useful) {
                withItems.add(chest);
            } else if (!store.indexed(chest)) {
                unknown.add(chest);
            }
        }
        BlockPos feet = player.blockPosition();
        withItems.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
        unknown.sort(Comparator.comparingDouble(p -> p.distSqr(feet)));
        chestQueue.addAll(withItems);
        chestQueue.addAll(unknown);
        FarmLog.line("RESTOCK " + (reason == null ? "tools" : nameOf(reason)) + " chests=" + withItems.size()
                + " unchecked=" + unknown.size() + " list=" + shopping.size());
        if (chestQueue.isEmpty()) {
            markMissing(reason);
            return;
        }
        status = reason == null ? "getting tools" : "getting " + nameOf(reason);
        if (!nextChest()) {
            finishRestock();
        }
    }

    /** So viele Inventarplaetze bleiben beim Einkaufen frei (fuer Unerwartetes). */
    private static final int SHOP_RESERVE = 5;

    private Map<Item, Integer> planShopping(LocalPlayer player, Item reason) {
        Map<Item, Integer> have = inventoryCounts(player);
        int freeSlots = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        Map<Item, Integer> upcoming = upcomingNeeds(4000, true);
        Map<Item, Integer> buckets = inlineBucketNeeds();
        if (!buckets.isEmpty()) {
            // Eimer fuer die Fluessigkeiten der naechsten Schichten zuerst (sonst ein Gang je Eimer)
            Map<Item, Integer> merged = new LinkedHashMap<>(buckets);
            upcoming.forEach(merged::putIfAbsent);
            upcoming = merged;
        }
        Map<Item, Integer> list = new LinkedHashMap<>();
        if (reason != null) {
            upcoming.putIfAbsent(reason, 1);
        }
        int slotsUsed = 0;
        // Werkzeuge zuerst (je eins, das schnellste)
        for (Tools.Kind kind : Tools.needed(plan)) {
            if (!Tools.inInventory(player, kind)) {
                Item tool = Tools.bestInChests(store, kind);
                if (tool != null && slotsUsed < freeSlots) {
                    list.put(tool, 1);
                    slotsUsed++;
                }
            }
        }
        // Dann das, wofuer er gekommen ist - frueher fuellten Hilfsbloecke und Material fuer spaeter die freien Plaetze,
        // das Gesuchte blieb in der Kiste und galt danach faelschlich als "fehlt"
        if (reason != null && !list.containsKey(reason)) {
            int wantReason = upcoming.getOrDefault(reason, 1);
            if (java.util.Arrays.asList(SCAFFOLD_ITEMS).contains(reason)) {
                // Hilfsbloecke: so viele wie der Turm braucht - sonst stand bei vollem Inventar nichts auf der Liste ("1
                // gebraucht, 2 da"), er machte keinen Platz und lief jede Minute alle Kisten ab (RESTOCK Dirt ... have=2)
                wantReason = Math.max(wantReason, Math.max(scaffoldWanted, 32));
            }
            int stillNeeded = wantReason - have.getOrDefault(reason, 0);
            if (stillNeeded > 0) {
                int max = Math.max(1, reason.getDefaultMaxStackSize());
                int fit = Math.max(1, freeSlots - slotsUsed);
                if (max == 1) {
                    // Eimer stapeln nicht: an der Kiste wird fuer bis zu 8 Platz gemacht (MAKE ROOM) - sonst lief er bei
                    // vollem Inventar fuer jeden einzelnen Wassereimer zur Kiste. Bei viel Fluessigkeit (Generator: je
                    // Schicht 96 Lava und 96 Wasser) bis 16 - die Gaenge zur Kiste kosteten sonst die halbe Zeit
                    // Wasser und Lava teilen sich die 16 (sonst war das Inventar voller Eimer und nichts anderes passte)
                    int carried = countInventory(player, Items.WATER_BUCKET) + countInventory(player, Items.LAVA_BUCKET);
                    int allowance = Math.max(4, (stillNeeded >= 12 ? 16 : 8) - carried);
                    fit = Math.min(stillNeeded, allowance); // so viele (notfalls Platz machen), aber nie mehr
                }
                int stacks = Math.max(1, Math.min((stillNeeded + max - 1) / max, fit));
                list.put(reason, Math.min(stillNeeded, stacks * max));
                slotsUsed += stacks;
            }
        }
        // Etwas Essen mitnehmen (grosse Bauten dauern - sonst wird er hungrig und muss extra laufen)
        Item food = bestFoodInChests();
        if (food != null && foodCount(player) < 8 && slotsUsed < freeSlots) {
            list.merge(food, 16, Math::max);
            slotsUsed++;
        }
        // Hilfsbloecke fuer Tuerme/Stuetzen bekommen eigene Plaetze - vor dem Baumaterial, sonst fuellt das alles und der
        // Bot kommt an hohe Stellen nicht mehr hinauf (immer ein paar dabei: schwebende Teile brauchen eine Stuetze,
        // eingesperrt kommt man mit einem Turm raus)
        int wantScaffold = Math.max(Math.max(scaffoldWanted, 16), plan.sizeY > 3 ? Math.min(192, 32 + plan.sizeY * 4) : 0);
        int haveScaffold = scaffoldCount(player);
        if (settings.useTowers && wantScaffold > haveScaffold && slotsUsed < freeSlots) {
            Item scaffold = bestScaffoldInChests();
            if (scaffold != null) {
                int stacks = Math.min(freeSlots - slotsUsed, (wantScaffold - haveScaffold + 63) / 64);
                list.merge(scaffold, Math.min(wantScaffold - haveScaffold, stacks * 64), Integer::sum);
                slotsUsed += stacks;
            }
        }
        // Ein paar Plaetze frei lassen: sonst ist das Inventar nach jedem Einkauf randvoll mit Material fuer spaeter, und
        // fehlt dann etwas Besonderes (Fackeln, Hilfsbloecke), passt es nicht mehr hinein - der Bot lief so 629 Mal
        // vergeblich zur Kiste
        int room = Math.max(Math.min(freeSlots, slotsUsed + 1), freeSlots - SHOP_RESERVE);
        for (Map.Entry<Item, Integer> e : upcoming.entrySet()) {
            int stillNeeded = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (stillNeeded <= 0 || e.getKey() == reason && list.containsKey(reason)) {
                continue;
            }
            int max = Math.max(1, e.getKey().getDefaultMaxStackSize());
            int stacks = (stillNeeded + max - 1) / max;
            if (slotsUsed + stacks > room) {
                stacks = Math.max(0, room - slotsUsed);
                if (stacks == 0 && !list.isEmpty()) {
                    break;
                }
                stillNeeded = Math.max(1, Math.min(stillNeeded, stacks * max));
            }
            list.merge(e.getKey(), stillNeeded, Integer::sum);
            slotsUsed += Math.max(1, stacks);
        }
        scaffoldWanted = 0;
        return list;
    }

    private boolean nextChest() {
        while (!chestQueue.isEmpty()) {
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            if (level == null || player == null) {
                return false;
            }
            // Jeweils die naechste Kiste von hier aus (bekannte zuerst, unbekannte danach) - nach dem Abstand vom Start
            // sortiert lief er die 47 Bloecke lange Kistenreihe im Zickzack ab (45, 6, 46, 5, 47 ...), je Kiste 7 s
            BlockPos here = player.blockPosition();
            int pick = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < chestQueue.size(); i++) {
                BlockPos c = chestQueue.get(i);
                double d = c.distSqr(here) + (store.indexed(c) ? 0.0D : 1.0E6D);
                if (d < best) {
                    best = d;
                    pick = i;
                }
            }
            BlockPos chest = chestQueue.remove(pick);
            if (!level.isLoaded(chest) || !ChestStore.isStorage(level, chest)) {
                FarmLog.line("CHEST skip " + chest.toShortString() + " (not loaded or no container)");
                continue;
            }
            if (!scanOnly && !chestHasWanted(chest)) {
                continue; // laut Index nichts mehr drin, was noch fehlt (z.B. Eimer-Kisten, wenn genug Eimer dabei sind)
            }
            chestPos = chest;
            goal = Goal.CHEST;
            Placer.Aim a = planChest(level, player, player.getEyePosition(), chest);
            if (a != null) {
                currentAim = a;
                go(Step.CHEST_OPEN);
                return true;
            }
            if (findStandAndWalk(player, level, chest, Goal.CHEST, null)) {
                return true;
            }
            failStreak++; // Kiste nicht erreichbar - vielleicht eingemauert
            FarmLog.line("CHEST unreachable " + chest.toShortString() + " " + lastStandInfo);
        }
        return false;
    }

    /**
     * Liegt in der Kiste (laut Index) noch etwas, das auf der Einkaufsliste offen ist? Unbekannte Kisten: ja (ansehen).
     * Frueher wurde jede Kiste mit irgendeinem Listen-Item geoeffnet, bis ALLES erledigt war - bei 100 Kisten liefen
     * so 20 Kisten nacheinander auf, und der Wachhund hielt das fuer Haengen.
     */
    private boolean chestHasWanted(BlockPos chest) {
        if (!store.indexed(chest)) {
            return true;
        }
        for (Map.Entry<Item, Integer> e : shopping.entrySet()) {
            if (e.getValue() > 0 && store.count(chest, e.getKey()) > 0) {
                return true;
            }
        }
        return false;
    }

    private void chestOpen(LocalPlayer player, ClientLevel level) {
        if (chestPos == null) {
            finishRestock();
            return;
        }
        if (currentAim == null || !ChestStore.canonical(level, currentAim.clicked()).equals(chestPos)) {
            currentAim = planChest(level, player, player.getEyePosition(), chestPos);
            if (currentAim == null) {
                if (!centered && arrivedNode != null && goal == Goal.CHEST
                        && feetNode(player).closerThan(arrivedNode, 1.5D)) {
                    centered = true; // erst in die Feldmitte ruecken (dafuer war der Platz geplant), dann noch einmal
                    go(Step.CENTER);
                    return;
                }
                failTask(level, "chest angle");
                return;
            }
        }
        input = openInput(player);
        aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
        if (stepTicks > 80) {
            failTask(level, "chest timeout");
            return;
        }
        if (player.isShiftKeyDown() || !aim.settled(player) || ++settledTicks < 2 + reaction) {
            return;
        }
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !ChestStore.canonical(level, hit.getBlockPos()).equals(ChestStore.canonical(level, chestPos))) {
            currentAim = null;
            settledTicks = 0;
            return;
        }
        click(player, hit);
        go(Step.CHEST_WAIT);
    }

    /** Klick auf eine Kiste; bei Doppelkisten zaehlt auch die andere Haelfte. */
    private Placer.Aim planChest(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos chest) {
        Placer.Aim a = placer.planClickOn(level, player, eye, chest, null, false);
        if (a != null) {
            return a;
        }
        BlockState state = level.getBlockState(chest);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                && state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            BlockPos other = chest.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
            return placer.planClickOn(level, player, eye, other, null, false);
        }
        return null;
    }

    private void chestWait(LocalPlayer player, ClientLevel level) {
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> && !(mc.gui.screen() instanceof InventoryScreen)) {
            lootActions = 0;
            hotbarFixes = 0;
            swapStage = 0;
            wait = delay(6, 12); // Inhalt kommt vom Server, ausserdem "schaut" man kurz
            go(Step.CHEST_LOOT);
            return;
        }
        if (stepTicks > 50) {
            failTask(level, "chest did not open");
        }
    }

    private void chestLoot(LocalPlayer player, ClientLevel level) {
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || mc.gameMode == null) {
            afterChest();
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        store.snapshot(level, chestPos, menu, player);
        if (lootActions == 0 && hotbarFixes == 0) {
            int filled = 0;
            String first = "-";
            for (Slot slot : menu.slots) {
                if (slot.container != player.getInventory() && !slot.getItem().isEmpty()) {
                    if (filled++ == 0) {
                        first = nameOf(slot.getItem().getItem());
                    }
                }
            }
            lastActivity = tick; // Kisten durchsehen ist kein Haengen (Wachhund)
            chestsOpened++;
            FarmLog.line("CHEST open " + chestPos.toShortString() + " filled=" + filled + " first=" + first
                    + (scanOnly ? " (scan)" : ""));
        }
        if (scanOnly) {
            closeChest(player);
            return;
        }
        // Inventar voll? Erst unnoetiges Zeug in die Kiste legen
        if (settings.storeJunk && swapStage == 0 && freeSlots(player) == 0 && lootActions < 60) {
            int junk = findJunkSlot(menu, player);
            if (junk >= 0 && hasFreeSlot(menu, player)) {
                mc.gameMode.handleContainerInput(menu.containerId, junk, 0, ContainerInput.QUICK_MOVE, player);
                lootActions++;
                wait = lootDelay();
                return;
            }
        }
        // Inventar voll, das Gesuchte liegt hier und die Kiste hat Platz: etwas zuruecklegen, das erst spaeter drankommt
        if (swapStage == 0 && freeSlots(player) == 0 && restockReason != null && lootActions < 60
                && shopping.getOrDefault(restockReason, 0) > 0 && hasFreeSlot(menu, player)
                && chestHas(menu, player, restockReason)) {
            int later = swapPartner(menu, player, restockReason);
            if (later < 0) {
                later = surplusSlot(menu, player, restockReason);
            }
            if (later >= 0) {
                FarmLog.line("MAKE ROOM " + nameOf(menu.getSlot(later).getItem().getItem()) + " back into the chest for "
                        + nameOf(restockReason));
                mc.gameMode.handleContainerInput(menu.containerId, later, 0, ContainerInput.QUICK_MOVE, player);
                lootActions++;
                wait = lootDelay();
                return;
            }
        }
        // Inventar und Kiste voll: tauschen wie mit der Maus (Gesuchtes aufnehmen, auf Kram legen, Kram ins freie Feld)
        if ((swapStage > 0 || freeSlots(player) == 0 && !hasFreeSlot(menu, player)) && lootActions < 60
                && swapStep(menu, player)) {
            lootActions++;
            wait = lootDelay();
            return;
        }
        // Gebrauchte Items herausnehmen (Shift-Klick)
        if (freeSlots(player) > 0 || canStackInto(player)) {
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory()) {
                    continue;
                }
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                Integer want = shopping.get(stack.getItem());
                if (want == null || want <= 0) {
                    continue;
                }
                // Vor dem Klick merken: danach ist der Stapel im Kistenfeld leer (Item = Luft, Anzahl 0) - frueher wurde
                // deshalb nie etwas abgezogen und er nahm alles Passende, bis das Inventar voll war
                Item item = stack.getItem();
                int count = stack.getCount();
                mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
                shopping.put(item, want - count);
                progress();
                lootActions++;
                wait = lootDelay();
                return;
            }
        }
        // Hotbar fuer die naechsten Bloecke einraeumen (spart spaeter das Inventar-Oeffnen)
        if (hotbarFixes < 3) {
            Map<Item, Integer> upcoming = upcomingNeeds(200, true);
            for (Item item : upcoming.keySet()) {
                if (findHotbar(player, item) >= 0) {
                    continue;
                }
                int from = findMain(player, item);
                if (from < 0) {
                    continue;
                }
                int to = hotbarSlotToReplace(player);
                int slot = menuSlot(menu, player, from);
                if (slot >= 0) {
                    mc.gameMode.handleContainerInput(menu.containerId, slot, to, ContainerInput.SWAP, player);
                    hotbarFixes++;
                    wait = lootDelay() + 2;
                    return;
                }
            }
            hotbarFixes = 3;
        }
        if (restockReason != null && freeSlots(player) == 0 && !canStackInto(player)
                && shopping.getOrDefault(restockReason, 0) > 0 && chestHas(menu, player, restockReason)) {
            // Das Gesuchte liegt hier, passt aber nicht mehr hinein (und nichts liess sich tauschen): die anderen Kisten
            // helfen dann auch nicht - frueher oeffnete er so alle 32 Lava-Kisten nacheinander, ohne etwas zu nehmen
            FarmLog.line("RESTOCK stop - inventory full at " + chestPos.toShortString() + " [" + inventorySummary(player) + "]");
            inventoryStuck = true;
        }
        closeChest(player);
    }

    /**
     * Ein Klick eines Tauschs: 1) gesuchten Stapel aus der Kiste aufnehmen, 2) auf einen Inventarplatz mit Kram (oder mit
     * etwas, das erst spaeter gebraucht wird) legen - der liegt dann am Mauszeiger, 3) ins frei gewordene Kistenfeld.
     * true = ein Klick gemacht.
     */
    private boolean swapStep(AbstractContainerMenu menu, LocalPlayer player) {
        if (swapStage == 0) {
            List<Slot> slots = new ArrayList<>(menu.slots);
            // Zuerst das, wofuer er eigentlich gekommen ist (z.B. Hilfsbloecke), dann der Rest der Liste
            slots.sort(Comparator.comparingInt(sl -> restockReason != null && sl.getItem().is(restockReason) ? 0 : 1));
            for (Slot slot : slots) {
                ItemStack stack = slot.getItem();
                Integer want = stack.isEmpty() || slot.container == player.getInventory() ? null
                        : shopping.get(stack.getItem());
                if (want == null || want <= 0) {
                    continue;
                }
                int partner = swapPartner(menu, player, stack.getItem());
                if (partner < 0) {
                    return false;
                }
                FarmLog.line("SWAP " + nameOf(stack.getItem()) + " for " + nameOf(menu.getSlot(partner).getItem().getItem())
                        + " (inventory and chest full)");
                swapChest = slot.index;
                swapInv = partner;
                shopping.put(stack.getItem(), want - stack.getCount());
                mc.gameMode.handleContainerInput(menu.containerId, swapChest, 0, ContainerInput.PICKUP, player);
                swapStage = 1;
                return true;
            }
            return false;
        }
        int slot = swapStage == 1 ? swapInv : swapChest;
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player);
        swapStage = swapStage == 1 ? 2 : 0;
        progress();
        return true;
    }

    /** Inventarplatz (nicht Hotbar) zum Tauschen gegen item: Kram, sonst was laut Plan erst nach item drankommt. */
    private int swapPartner(AbstractContainerMenu menu, LocalPlayer player, Item item) {
        int junk = findJunkSlot(menu, player);
        if (junk >= 0) {
            return junk;
        }
        List<Item> order = new ArrayList<>(upcomingNeeds(4000, true).keySet());
        int wanted = order.indexOf(item);
        if (wanted < 0) {
            return -1;
        }
        Set<Item> scaffold = Set.of(SCAFFOLD_ITEMS);
        int best = -1;
        int bestRank = wanted;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (slot.container != player.getInventory() || slot.getContainerSlot() < 9 || stack.isEmpty()
                    || stack.is(item) || isTool(stack) || Food.edible(stack) || justFetched(stack.getItem())
                    || scaffold.contains(stack.getItem()) && scaffoldCount(player) <= 128
                    || stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)) {
                continue;
            }
            int rank = order.indexOf(stack.getItem());
            if (rank < 0) {
                rank = Integer.MAX_VALUE; // vorerst gar nicht gebraucht
            }
            if (rank > bestRank) {
                bestRank = rank;
                best = slot.index;
            }
        }
        return best;
    }

    private void closeChest(LocalPlayer player) {
        wait = delay(3, 8);
        player.closeContainer();
        afterChest();
    }

    private void afterChest() {
        chestPos = null;
        if (inventoryStuck) {
            inventoryStuck = false;
            finishRestock(); // voll - mit dem bauen, was er hat
            return;
        }
        boolean stillWanted = false;
        for (int v : shopping.values()) {
            if (v > 0) {
                stillWanted = true;
                break;
            }
        }
        if (scanOnly || stillWanted) {
            if (nextChest()) {
                return;
            }
        }
        if (scanOnly) {
            go(Step.CHOOSE);
            return;
        }
        finishRestock();
    }

    private void finishRestock() {
        chestQueue.clear();
        chestPos = null;
        LocalPlayer player = mc.player;
        Item reason = restockReason;
        restockReason = null;
        status = "";
        go(Step.CHOOSE);
        if (player != null && reason != null) {
            FarmLog.line("RESTOCK done " + nameOf(reason) + " have=" + countInventory(player, reason));
            if (countInventory(player, reason) > 0) {
                fetchedAt.put(reason, tick);
            }
        }
        if (player != null && reason != null && countInventory(player, reason) <= 0) {
            if (mc.level != null && !canGetOut(mc.level, feetNode(player), escapeOptions(player))) {
                // Kisten waren nur nicht erreichbar, weil der Bot eingemauert ist - nicht "leer" melden
                startEscape(player, mc.level);
                return;
            }
            if (freeSlots(player) == 0 && store.available(reason) > 0) {
                // Liegt in den Kisten, nur ist das Inventar voll (und nichts zum Tauschen): erst das andere verbauen
                FarmLog.line("INVENTORY full - " + nameOf(reason) + " stays in the chest for now [" + inventorySummary(player)
                        + "]");
                inventoryFullUntil = tick + 200;
                laterFor(reason, 400);
                return;
            }
            if (chestsOpened == 0 && store.available(reason) > 0) {
                // Liegt in den Kisten, er kam nur von hier aus nicht hin (z.B. oben auf dem Steg unter dem Generator):
                // nicht "fehlt" melden (das sperrte alles damit Gebaute), sondern spaeter von anderswo noch einmal
                FarmLog.line("RESTOCK chests not reachable from " + feetNode(player).toShortString() + " - " + nameOf(reason)
                        + " later");
                laterFor(reason, 600);
                unreachableUntil.put(reason, tick + 600);
                unreachableFrom = feetNode(player);
                if (mc.level != null && !outside(unreachableFrom)) {
                    leaveFarm(player, mc.level); // von draussen (z.B. ueber den Randturm) findet er den Weg hinunter
                }
                return;
            }
            markMissing(reason);
        }
    }

    /**
     * Kommt er von hier (mitten in der Farm, z.B. hoch oben auf dem Steg) nicht an die Kisten: erst einmal hinaus aus der
     * Farm - ins Becken springen, zum Rand, auf den eigenen Turm. Diese Suche ist leichter (irgendein Platz draussen) als
     * der ganze Weg zu einer bestimmten Kiste. true = laeuft los.
     */
    private boolean leaveFarm(LocalPlayer player, ClientLevel level) {
        BlockPos feet = feetNode(player);
        Pathfinder.Options options = scaffoldCount(player) > 0 ? pathOptions(player) : pathOptions(player).withoutBuilding();
        Pathfinder.Path out = Pathfinder.find(level, feet, this::outside, nearestOutside(feet), PATH_NODES * 3,
                options.anywhere());
        if (out == null) {
            FarmLog.line("LEAVE no way out of the farm from " + feet.toShortString());
            return false;
        }
        FarmLog.line("LEAVE farm via " + out.end().toShortString() + " (chests not reachable from inside)");
        status = "going to the chests";
        beginWalk(out, Goal.MOVE);
        return true;
    }

    /** Alle offenen Bloecke aus item eine Weile zurueckstellen (auch im Schicht-Modus nicht gleich wieder versuchen). */
    private void laterFor(Item item, int ticks) {
        for (BuildPlan.Target t : pending) {
            if (t.need().item() == item) {
                defer(t.pos(), ticks);
                lastTry.put(t.pos().immutable(), tick + ticks - STRICT_RETRY_TICKS);
            }
        }
    }

    private void markMissing(Item item) {
        if (item != null && Food.edible(item) && !plan.materials.containsKey(item)) {
            go(Step.CHOOSE); // Essen ist kein Baumaterial - nicht als "fehlt" melden
            return;
        }
        if (item != null && missing.add(item)) {
            NovaNotify.chat("Schematic Builder", "§cOut of " + nameOf(item)
                    + "§7 - put more in a selected chest. Building the rest.");
            NovaNotify.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 0.7F);
        }
        go(Step.CHOOSE);
    }

    /** Fehlendes Material wurde nachgelegt (z.B. vom Menue aus): wieder versuchen. */
    public void clearMissing() {
        missing.clear();
        for (BlockPos pos : store.chests()) {
            if (!store.indexed(pos)) {
                return;
            }
        }
    }

    private int lootDelay() {
        return Math.max(1, (int) Math.round((2.0D + Math.abs(random.nextGaussian()) * 2.0D) / settings.speed));
    }

    private int findJunkSlot(AbstractContainerMenu menu, LocalPlayer player) {
        Map<Item, Integer> upcoming = upcomingNeeds(100000);
        Set<Item> scaffold = Set.of(SCAFFOLD_ITEMS);
        int selected = player.getInventory().getSelectedSlot();
        for (Slot slot : menu.slots) {
            // Auch die Schnellleiste (ausser dem gewaehlten Platz): dort blieb alter Kram fuer erledigte Schichten fuer
            // immer liegen (60 Komparatoren, 35 Trichter) - und fuer Erde war kein Platz mehr, er kam nicht mehr hinauf
            if (slot.container != player.getInventory() || slot.getContainerSlot() == selected) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || isTool(stack) || upcoming.containsKey(stack.getItem()) || Food.edible(stack)
                    || stack.is(restockReason) || justFetched(stack.getItem())
                    || scaffold.contains(stack.getItem()) && scaffoldCount(player) < 64
                    || stack.is(Items.BUCKET) && countInventory(player, Items.BUCKET) <= 16
                    || stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET)) {
                continue; // leere Eimer: einen Stapel behalten (zum Nachfuellen), der Rest darf in die Kiste
            }
            return slot.index;
        }
        return -1;
    }

    /**
     * Notfalls zuruecklegen: der Stapel (auch Schnellleiste, ausser dem gewaehlten Platz), von dem am meisten ueber den
     * Bedarf der naechsten ~300 Bloecke hinaus da ist - auch zu viele Eimer. -1 = nichts davon ist uebrig.
     */
    private int surplusSlot(AbstractContainerMenu menu, LocalPlayer player, Item wanted) {
        Map<Item, Integer> soon = upcomingNeeds(300, true);
        Map<Item, Integer> buckets = inlineBucketNeeds();
        Map<Item, Integer> have = inventoryCounts(player);
        Set<Item> scaffold = Set.of(SCAFFOLD_ITEMS);
        int selected = player.getInventory().getSelectedSlot();
        int best = -1;
        int bestExcess = 0;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (slot.container != player.getInventory() || slot.getContainerSlot() == selected || stack.isEmpty()
                    || stack.is(wanted) || isTool(stack) || Food.edible(stack) || justFetched(stack.getItem())) {
                continue;
            }
            Item item = stack.getItem();
            int need = soon.getOrDefault(item, 0) + buckets.getOrDefault(item, 0);
            if (item == Items.BUCKET) {
                need = Math.max(need, 16);
            }
            if (scaffold.contains(item)) {
                need = Math.max(need, 128);
            }
            int excess = have.getOrDefault(item, 0) - need;
            if (excess >= stack.getCount() && excess > bestExcess) {
                bestExcess = excess;
                best = slot.index;
            }
        }
        return best;
    }

    /**
     * Eben erst (letzte Minute) dafuer an der Kiste gewesen? Dann nicht wieder zuruecklegen: sonst tauschte er zwei Items
     * im Sekundentakt gegeneinander (Kolben fuer Fliesen, Fliesen fuer Kolben ...), ohne etwas zu bauen.
     */
    private boolean justFetched(Item item) {
        Integer at = fetchedAt.get(item);
        return at != null && tick - at < 1200;
    }

    /** Inventar als kurze Liste (fuers Log, wenn es voll ist). */
    private static String inventorySummary(LocalPlayer player) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Item, Integer> e : inventoryCounts(player).entrySet()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(e.getValue()).append(' ').append(nameOf(e.getKey()));
        }
        return sb.toString();
    }

    /** Liegt item in der offenen Kiste (nicht im eigenen Inventar)? */
    private static boolean chestHas(AbstractContainerMenu menu, LocalPlayer player, Item item) {
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory() && slot.getItem().is(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFreeSlot(AbstractContainerMenu menu, LocalPlayer player) {
        for (Slot slot : menu.slots) {
            if (slot.container != player.getInventory() && slot.getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean canStackInto(LocalPlayer player) {
        for (Map.Entry<Item, Integer> e : shopping.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            for (int i = 0; i < 36; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(e.getKey()) && stack.getCount() < stack.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }

    // ================================================================ Tuerme (hoch bauen und wieder runter)

    /** Erster Platz zum Stehen unterhalb (oder auf Hoehe) dieser Position. */
    private static BlockPos groundBelow(ClientLevel level, BlockPos pos, int maxDepth) {
        BlockPos cursor = pos;
        for (int i = 0; i < maxDepth; i++) {
            if (Pathfinder.standable(level, cursor)) {
                return cursor.immutable();
            }
            if (!Pathfinder.passable(level, cursor)) {
                return null;
            }
            cursor = cursor.below();
        }
        return null;
    }

    /** Steht man auf einer Saeule aus eigenen Hilfsbloecken (darunter wieder Boden oder Hilfsblock)? */
    private boolean onTowerTop(LocalPlayer player) {
        BlockPos below = player.blockPosition().below();
        return scaffolds.contains(below) && mc.level != null && !unsafeDrop(mc.level, below);
    }

    /** Wuerde man tiefer als 3 Bloecke fallen, wenn dieser Block weg ist? */
    private boolean unsafeDrop(ClientLevel level, BlockPos removed) {
        for (int k = 1; k <= 3; k++) {
            BlockPos p = removed.below(k);
            if (Pathfinder.supports(level, p) || scaffolds.contains(p) && !Pathfinder.replaceable(level, p)) {
                return false;
            }
            if (!Pathfinder.passable(level, p)) {
                return false; // irgendetwas Festes faengt auf
            }
        }
        return true;
    }

    private void startDigDown() {
        status = "going down";
        LocalPlayer player = mc.player;
        if (player != null) {
            lookDownTarget(player);
        }
        go(Step.DIG_DOWN);
    }

    /** Einmal festgelegter Blick nach unten (fast senkrecht, wie beim Hochbauen ueblich). */
    private void lookDownTarget(LocalPlayer player) {
        downYaw = player.getYRot() + (random.nextFloat() - 0.5F) * 6.0F;
        downPitch = 87.4F + random.nextFloat() * 2.3F;
    }

    private void digDown(LocalPlayer player, ClientLevel level) {
        BlockPos below = player.blockPosition().below();
        if (!scaffolds.contains(below) || !mining && unsafeDrop(level, below)) {
            releaseMining();
            input = idleInput();
            if (player.onGround()) {
                status = "";
                wait = delay(4, 10);
                go(Step.CHOOSE);
            }
            return;
        }
        if (level.getBlockState(below).isAir()) {
            scaffolds.remove(below);
            releaseMining();
            return;
        }
        if (stepTicks > 300) {
            failTask(level, "dig down timeout");
            return;
        }
        input = new Input(false, false, false, false, false, false, false);
        aim.lookAt(player, downYaw, downPitch);
        if (!mining) {
            Item tool = bestToolFor(player, level.getBlockState(below));
            if (tool != null && !prepareItem(player, tool, Step.DIG_DOWN)) {
                return;
            }
            if (!aim.settled(player)) {
                return;
            }
            minePos = below;
            mineFace = Direction.UP;
            mc.gameMode.startDestroyBlock(below, Direction.UP);
            mining = true;
        } else {
            if (!below.equals(minePos)) {
                releaseMining();
                return;
            }
            mc.gameMode.continueDestroyBlock(below, Direction.UP);
        }
        Compat.swing(player, InteractionHand.MAIN_HAND);
    }

    // ================================================================ Eingemauert: vorbeugen und rausgraben

    /** Liegt die Fussposition ausserhalb der Farm (seitlich daneben oder ganz oben drauf)? */
    private boolean outside(BlockPos p) {
        BlockPos max = plan.max();
        return p.getX() < plan.origin.getX() || p.getX() > max.getX() || p.getZ() < plan.origin.getZ()
                || p.getZ() > max.getZ() || p.getY() > max.getY();
    }

    /** Naechster Punkt direkt neben der Farm (nur als Richtung fuer die Wegsuche). */
    private BlockPos nearestOutside(BlockPos p) {
        BlockPos min = plan.origin;
        BlockPos max = plan.max();
        int west = p.getX() - min.getX() + 1;
        int east = max.getX() - p.getX() + 1;
        int north = p.getZ() - min.getZ() + 1;
        int south = max.getZ() - p.getZ() + 1;
        int best = Math.min(Math.min(west, east), Math.min(north, south));
        if (best == west) {
            return new BlockPos(min.getX() - 1, p.getY(), p.getZ());
        }
        if (best == east) {
            return new BlockPos(max.getX() + 1, p.getY(), p.getZ());
        }
        if (best == north) {
            return new BlockPos(p.getX(), p.getY(), min.getZ() - 1);
        }
        return new BlockPos(p.getX(), p.getY(), max.getZ() + 1);
    }

    /**
     * Fuer "komme ich hier raus?": alles, was die Wegsuche darf - auch ein tiefer Sprung zaehlt als Ausweg. Tuerme und
     * Bruecken aber nur, wenn er die Hilfsbloecke WIRKLICH dabei hat (die Kiste erreicht er eingesperrt ja nicht).
     */
    private Pathfinder.Options escapeOptions(LocalPlayer player) {
        Pathfinder.Options options = pathOptions(player).withMaxDrop(24);
        return scaffoldCount(player) > 0 ? options : options.withoutBuilding();
    }

    private static final int ESCAPE_NODES = 4000;

    /**
     * Kommt man von feet aus noch aus der Farm heraus (oder oben drauf)? false = eingesperrt. Erst eine schnelle
     * gerichtete Suche; findet die nichts, wird ALLES abgesucht, was von hier erreichbar ist - erst wenn dort
     * nirgends ein Ausgang ist, gilt er als eingesperrt (ein grosser Innenraum allein zaehlt nicht als "draussen").
     */
    private boolean canGetOut(ClientLevel level, BlockPos feet, Pathfinder.Options options) {
        if (level == null || plan == null || outside(feet)) {
            return true;
        }
        if (Pathfinder.find(level, feet, this::outside, nearestOutside(feet), 800, options) != null) {
            return true;
        }
        Set<BlockPos> reachable = Pathfinder.explore(level, feet, options, this::inArea, ESCAPE_NODES);
        for (BlockPos p : reachable) {
            if (outside(p)) {
                return true;
            }
        }
        // Suche nicht fertig geworden (riesiger Bereich): sicherheitshalber nicht als eingesperrt werten
        return reachable.size() >= ESCAPE_NODES;
    }

    /** Kommt man von diesem Platz aus wieder aus der Farm heraus (pro Tick zwischengespeichert)? */
    private boolean canLeave(ClientLevel level, LocalPlayer player, BlockPos stand) {
        if (outside(stand)) {
            return true;
        }
        // Teure Suche - Ergebnis bleibt gueltig, bis sich in der Welt etwas aendert (Server-Update / eigener Block)
        boolean withScaffolds = scaffoldCount(player) > 0;
        if (withScaffolds != leaveCacheScaffolds) {
            leaveCache.clear();
            leaveCacheScaffolds = withScaffolds;
        }
        Boolean free = leaveCache.get(stand);
        if (free == null) {
            free = canGetOut(level, stand, escapeOptions(player));
            leaveCache.put(stand.immutable(), free);
        }
        return free;
    }

    /**
     * Wuerde ein Block an place den Spieler (Fuesse bei feet) einsperren? Geprueft werden nur Bloecke direkt um ihn
     * herum; true nur, wenn er vorher noch rauskam und danach nicht mehr. Solche Bloecke setzt er dann von draussen.
     */
    private boolean wouldTrap(ClientLevel level, LocalPlayer player, BlockPos feet, BlockPos place, BlockState state) {
        if (outside(feet) || state.getCollisionShape(level, place).isEmpty()) {
            return false;
        }
        int dy = place.getY() - feet.getY();
        if (Math.abs(place.getX() - feet.getX()) > 3 || Math.abs(place.getZ() - feet.getZ()) > 3 || dy < -1 || dy > 3) {
            return false;
        }
        if (trapCacheTick != tick) {
            trapCache.clear();
            trapCacheTick = tick;
        }
        List<BlockPos> key = List.of(feet.immutable(), place.immutable());
        Boolean cached = trapCache.get(key);
        if (cached != null) {
            return cached;
        }
        Pathfinder.Options options = escapeOptions(player);
        boolean trap;
        if (Pathfinder.assuming(place, null, () -> canGetOut(level, feet, options))) {
            trap = false;
        } else {
            // Kam er vorher raus und danach nicht mehr? (Vorher-Ergebnis haengt nur vom Standplatz ab)
            List<BlockPos> freeKey = List.of(feet.immutable());
            Boolean before = trapCache.get(freeKey);
            if (before == null) {
                before = canGetOut(level, feet, options);
                trapCache.put(freeKey, before);
            }
            trap = before;
        }
        trapCache.put(key, trap);
        return trap;
    }

    /** Bereich, in dem die Erreichbarkeit geprueft wird: die Farm plus ein Rand zum Drumherumlaufen. */
    private boolean inArea(BlockPos p) {
        BlockPos max = plan.max();
        return p.getX() >= plan.origin.getX() - 4 && p.getX() <= max.getX() + 4
                && p.getZ() >= plan.origin.getZ() - 4 && p.getZ() <= max.getZ() + 4
                && p.getY() >= plan.origin.getY() - 4 && p.getY() <= max.getY() + 4;
    }

    /** Alle Stehplaetze, die man von hier aus erreicht (kurz zwischengespeichert). */
    private Set<BlockPos> reachableNow(ClientLevel level, LocalPlayer player) {
        BlockPos feet = feetNode(player);
        if (reachCache == null || !feet.equals(reachCacheFeet) || tick - reachCacheTick > 40) {
            reachCache = Pathfinder.explore(level, feet, pathOptions(player), this::inArea, REACH_NODES);
            reachCacheFeet = feet;
            reachCacheTick = tick;
        }
        return reachCache;
    }

    /**
     * Wuerde der Block t den Weg zu Stellen abschneiden, an denen noch gebaut werden muss (z.B. die letzte Luecke einer
     * Wand, hinter der noch Bloecke fehlen)? Dann kommt er spaeter, wenn dort alles fertig ist. In der zweiten Runde
     * nach ein paar Versuchen trotzdem setzen, damit nichts ewig liegen bleibt.
     */
    private boolean cutsOffWork(ClientLevel level, LocalPlayer player, BuildPlan.Target t) {
        BlockPos place = t.pos();
        if (t.state().getCollisionShape(level, place).isEmpty()
                || phase == Phase.BUILD2 && attempts.getOrDefault(place, 0) >= 3) {
            return false;
        }
        // Betrifft der Block ueberhaupt Stehplaetze? (darin stehen, mit dem Kopf darunter, darunter hochspringen)
        BlockPos below = place.below();
        BlockPos jumpFrom = place.below(2);
        if (!Pathfinder.standable(level, place) && !Pathfinder.standable(level, below)
                && !Pathfinder.standable(level, jumpFrom)) {
            return false;
        }
        // Stehplaetze rundherum (nur +-1 Block hoch, da kommt man in beide Richtungen). Haengen die mit dem neuen
        // Block in der Naehe noch zusammen, ist nichts abgeschnitten.
        Set<BlockPos> border = new HashSet<>();
        for (BlockPos r : new BlockPos[]{place, below}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos n = r.offset(dx, dy, dz);
                        if (!n.equals(place) && !n.equals(below) && Pathfinder.standable(level, n)) {
                            border.add(n.immutable());
                        }
                    }
                }
            }
        }
        if (border.size() <= 1) {
            return false;
        }
        Pathfinder.Options options = pathOptions(player);
        BlockPos from = border.iterator().next();
        Set<BlockPos> found = new HashSet<>();
        boolean connected = Pathfinder.assuming(place, null, () -> Pathfinder.find(level, from, p -> {
            if (border.contains(p)) {
                found.add(p.immutable());
            }
            return found.size() >= border.size();
        }, place, 900, options) != null);
        if (connected) {
            return false;
        }
        // Der Block trennt etwas ab: was erreicht man danach nicht mehr? (Grosse Suche - hoechstens ein paar pro Tick)
        if (reachBudgetTick != tick) {
            reachBudgetTick = tick;
            reachBudget = 3;
        }
        if (--reachBudget < 0) {
            return true; // diesmal nicht pruefbar - lieber spaeter setzen als sich den Weg zuzubauen
        }
        Set<BlockPos> before = reachableNow(level, player);
        if (!before.contains(place) && !before.contains(below) && !before.contains(jumpFrom)) {
            return false; // betrifft eine Stelle, an die man jetzt schon nicht kommt
        }
        BlockPos feet = feetNode(player);
        Set<BlockPos> after = Pathfinder.assuming(place, null,
                () -> Pathfinder.explore(level, feet, options, this::inArea, REACH_NODES));
        boolean truncated = before.size() >= REACH_NODES;
        Set<BlockPos> lost = new HashSet<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : before) {
            if (after.contains(p) || p.equals(place) || p.equals(below) || !inArea(p)) {
                continue;
            }
            if (truncated && Math.max(Math.abs(p.getX() - place.getX()), Math.abs(p.getZ() - place.getZ())) > 12) {
                continue; // Suche war abgeschnitten - weit weg ist nicht verlaesslich
            }
            lost.add(p);
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (lost.isEmpty()) {
            return false;
        }
        // Muss im abgeschnittenen Teil noch etwas gebaut werden, das man von der anderen Seite aus nicht erreicht?
        double reach = Placer.reach(player) + 0.4D;
        List<BuildPlan.Target> work = new ArrayList<>(pending);
        if (phase.ordinal() <= Phase.FLUIDS.ordinal()) {
            for (BuildPlan.Target f : plan.targets) {
                if ((f.need().kind() == Materials.Kind.FLUID || f.waterlogged()) && !fluidDone(level, f)) {
                    work.add(f); // Wasser kommt erst spaeter - muss dann auch noch erreichbar sein
                }
            }
        }
        for (BuildPlan.Target other : work) {
            BlockPos o = other.pos();
            if (o.equals(place) || missing.contains(other.need().item()) || givenUp.contains(o)
                    || o.getX() < minX - 5 || o.getX() > maxX + 5 || o.getZ() < minZ - 5 || o.getZ() > maxZ + 5
                    || o.getY() < minY - 2 || o.getY() > maxY + 4) {
                continue;
            }
            if (reachableFrom(level, lost, o, reach) && !reachableFrom(level, after, o, reach)) {
                return true;
            }
        }
        return false;
    }

    /** Gibt es in stands einen Platz, von dem aus pos in Reichweite ist? */
    private static boolean reachableFrom(ClientLevel level, Set<BlockPos> stands, BlockPos pos, double reach) {
        Vec3 center = Vec3.atCenterOf(pos);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos s = pos.offset(dx, dy, dz);
                    if (stands.contains(s) && !Placer.intersectsPlayer(level, s, pos)
                            && Placer.eyeAt(level, s).distanceToSqr(center) <= reach * reach) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Steht der Spieler selbst dort, wo der Block hin soll? (Der Server wuerde das Setzen ablehnen.) */
    private static boolean blocksMe(LocalPlayer player, ClientLevel level, BuildPlan.Target t) {
        return t != null && !t.state().getCollisionShape(level, t.pos()).isEmpty()
                && player.getBoundingBox().intersects(new AABB(t.pos()));
    }

    /**
     * Der Bot hat sich eingebaut: kuerzesten Durchbruch nach draussen suchen (Kopf- und Fusshoehe, bis 3 Bloecke
     * tief, am liebsten durch eigene Hilfsbloecke statt durch die Farm). Abgebaute Farm-Bloecke werden spaeter neu gesetzt.
     */
    private void startEscape(LocalPlayer player, ClientLevel level) {
        startEscape(player, level, false);
    }

    /**
     * force: die Wegsuche glaubt zwar, es gaebe einen Ausweg, aber der Bot bleibt immer wieder an derselben Stelle
     * haengen (z.B. ein Sprung, den ein Spieler nicht schafft). Dann zaehlt nur ein Ausweg, der ohne Springen und
     * Bauen klappt - notfalls wird er freigegraben.
     */
    private void startEscape(LocalPlayer player, ClientLevel level, boolean force) {
        releaseMining();
        path = null;
        target = null;
        resumeTarget = null;
        minePos = null;
        escapeCells = null;
        input = idleInput();
        go(Step.CHOOSE);
        if (++escapeTries > 3 || force && ++forcedEscapes > 3) {
            if (canGetOut(level, feetNode(player), escapeOptions(player))) {
                // Oft gehangen, aber laut Wegsuche gibt es einen Ausweg: weitermachen statt anhalten (die Stellen sind
                // gemerkt) - der Wachhund faengt echtes Feststecken ab
                FarmLog.line("ESCAPE tries used up at " + feetNode(player).toShortString() + ", but a way out exists - moving on");
                escapeTries = 0;
                forcedEscapes = 0;
                return;
            }
            trapped();
            return;
        }
        BlockPos feet = feetNode(player);
        rememberTrap(feet); // hier nie wieder hinstellen - sonst dieselbe Falle immer wieder
        Pathfinder.Options options = escapeOptions(player);
        if (force) {
            Pathfinder.Path out = walkOut(level, feet);
            if (out != null) {
                status = "getting out";
                beginWalk(out, Goal.ESCAPE);
                return;
            }
        }
        List<BlockPos> best = escapeRoute(player, level, feet, options, force, false);
        if (best == null) {
            if (force && canGetOut(level, feet, options)) {
                // Kein Weg ganz ohne Springen, aber ein normaler: nicht anhalten - die Stelle ist gemerkt (rememberTrap),
                // dort stellt er sich nicht wieder hin
                FarmLog.line("ESCAPE no jump-free way at " + feet.toShortString() + ", but a normal one - moving on");
                return;
            }
            // Ginge es nur, wenn er Glas o.Ae. zerstoert? Dann nichts kaputtmachen, sondern Bescheid geben
            List<BlockPos> destructive = escapeRoute(player, level, feet, options, force, true);
            if (destructive != null) {
                boxedIn(player, level, destructive);
            } else {
                trapped();
            }
            return;
        }
        escapeCells = best;
        escapeForced = force;
        escapeMines = 0;
        FarmLog.line("ESCAPE dig " + best + " force=" + force + " feet=" + feet.toShortString());
        for (BlockPos c : best) {
            rememberBroken(level, c); // Farm-Bloecke kommen neu, Gelaende am Ende zurueck
        }
        status = "digging out";
        NovaNotify.chat("Schematic Builder", "§7Built myself in - digging a way out (" + best.size() + " block(s)).");
        go(Step.CHOOSE);
    }

    /**
     * Kuerzester Durchbruch nach draussen (Kopf- und Fusshoehe, bis 3 Bloecke tief, am liebsten durch eigene
     * Hilfsbloecke). destructive = auch Bloecke, die beim Abbauen verloren gehen (nur zum Pruefen, nie zum Abbauen).
     */
    private List<BlockPos> escapeRoute(LocalPlayer player, ClientLevel level, BlockPos feet, Pathfinder.Options options,
                                       boolean force, boolean destructive) {
        List<BlockPos> best = null;
        int bestCost = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            List<BlockPos> cells = new ArrayList<>();
            int cost = 0;
            for (int k = 1; k <= 3; k++) {
                BlockPos foot = feet.relative(d, k);
                boolean ok = true;
                for (BlockPos c : new BlockPos[]{foot.above(), foot}) {
                    if (Pathfinder.passable(level, c)) {
                        continue;
                    }
                    BlockState s = level.getBlockState(c);
                    if (s.getDestroySpeed(level, c) < 0.0F || !s.getFluidState().isEmpty() || Pathfinder.danger(s)
                            || ChestStore.isStorage(level, c)) {
                        ok = false; // Bedrock, Lava, Kiste ... - nicht hier lang
                        break;
                    }
                    boolean lost = wouldLose(player, level, c, s);
                    if (!destructive && lost && !spareInChests(level, c)) {
                        ok = false; // Glas & Co. bzw. ohne passendes Werkzeug - ginge dabei kaputt
                        break;
                    }
                    BlockState wanted = plan.stateAt(c);
                    if (wanted != null && !scaffolds.contains(c) && rebuilds.getOrDefault(c, 0) > MAX_REBUILDS) {
                        ok = false; // diesen Farm-Block hat er schon zu oft abgebaut und neu gesetzt
                        break;
                    }
                    cost += scaffolds.contains(c) ? 1 : lost ? 14 : wanted != null && !wanted.isAir() ? 8 : 3;
                    cells.add(c.immutable());
                }
                if (!ok) {
                    break;
                }
                Set<BlockPos> air = new HashSet<>(cells);
                if (Pathfinder.assuming(null, air, () -> force ? walkOut(level, feet) != null : canGetOut(level, feet, options))) {
                    cost += k;
                    if (!cells.isEmpty() && cost < bestCost) {
                        bestCost = cost;
                        best = new ArrayList<>(cells);
                    }
                    break;
                }
            }
        }
        return best;
    }

    /** Ginge der Block beim Abbauen verloren (Glas, falsches Werkzeug, Farm-Block mit anderem Drop, Inhalt ...)? */
    private boolean wouldLose(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState s) {
        if (scaffolds.contains(pos)) {
            return false;
        }
        return Drops.lost(s) || s.hasBlockEntity() || s.requiresCorrectToolForDrops() && !hasCorrectTool(player, s)
                || plan.stateAt(pos) != null && !Drops.dropsItself(s);
    }

    /**
     * Ginge der Farm-Block an c beim Abbauen verloren (z.B. Glas), liegt aber Ersatz in den Kisten? Dann darf er sich im
     * Notfall hindurchgraben - der Block kommt danach neu (wie ein Spieler, der eine Scheibe einschlaegt und ersetzt).
     */
    private boolean spareInChests(ClientLevel level, BlockPos c) {
        BlockState wanted = plan.stateAt(c);
        if (wanted == null || !level.getBlockState(c).is(wanted.getBlock())) {
            return false;
        }
        Item item = wanted.getBlock().asItem();
        return item != Items.AIR && store.available(item) > 0;
    }

    /** Eingesperrt - raus kaeme er nur, indem er etwas zerstoert (z.B. Glas). Nichts kaputtmachen, Bescheid geben. */
    private void boxedIn(LocalPlayer player, ClientLevel level, List<BlockPos> through) {
        BlockPos example = through.get(0);
        for (BlockPos c : through) {
            if (wouldLose(player, level, c, level.getBlockState(c))) {
                example = c;
                break;
            }
        }
        BlockState s = level.getBlockState(example);
        boolean tool = s.requiresCorrectToolForDrops() && !hasCorrectTool(player, s) && !Drops.lost(s);
        String name = s.getBlock().getName().getString();
        FarmLog.line("BOXED IN by " + name + " at " + example.toShortString() + " route=" + through);
        escapeCells = null;
        setPaused(true, "boxed in");
        NovaNotify.chat("Schematic Builder", "§cI'm boxed in. The only way out is through " + name + " at "
                + example.toShortString() + (tool ? " - I have no tool that keeps it." : " - it would be destroyed.")
                + " §7Free me (or bring a tool), then resume.");
        NovaNotify.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 0.7F);
    }

    private void continueEscape(LocalPlayer player, ClientLevel level) {
        escapeCells.removeIf(c -> Pathfinder.passable(level, c));
        if (!escapeCells.isEmpty()) {
            if (++escapeMines > 12) {
                startEscape(player, level, escapeForced); // geht so nicht (z.B. nachrutschender Sand) - anderen Weg suchen
                return;
            }
            status = "digging out";
            startMining(player, level, escapeCells.get(0), null);
            if (step == Step.CHOOSE) {
                startEscape(player, level, escapeForced); // kommt an den Block nicht heran
            }
            return;
        }
        escapeCells = null;
        // Die Runde faengt danach unten wieder an, damit abgebaute Farm-Bloecke neu gesetzt werden
        currentLayer = Integer.MIN_VALUE;
        waitingLayer = Integer.MIN_VALUE;
        failStreak = 0;
        nextTrapCheck = tick + 100;
        BlockPos feet = feetNode(player);
        if (outside(feet)) {
            status = "";
            escapeTries = 0;
            go(Step.CHOOSE);
            return;
        }
        // Erzwungen: der normale Weg hat hier schon nicht geklappt - den freigegrabenen Gang nehmen
        Pathfinder.Path out = escapeForced ? walkOut(level, feet) : null;
        if (out != null) {
            status = "getting out";
            beginWalk(out, Goal.ESCAPE);
            return;
        }
        Pathfinder.Options options = pathOptions(player);
        BlockPos toward = nearestOutside(feet);
        out = Pathfinder.find(level, feet, this::outside, toward, PATH_NODES, options);
        if (out == null && player.getHealth() >= 14.0F) {
            out = Pathfinder.find(level, feet, this::outside, toward, PATH_NODES, options.withMaxDrop(8));
        }
        if (out != null && out.buildMoves() <= scaffoldCount(player)) {
            status = "getting out";
            beginWalk(out, Goal.ESCAPE);
            return;
        }
        status = "";
        go(Step.CHOOSE);
    }

    /**
     * Beim Laufen haengen geblieben. Passiert das immer wieder an derselben Stelle, stimmt die Wegsuche dort nicht mit
     * der Wirklichkeit ueberein (sie haelt einen Weg fuer gangbar, den es nicht gibt) - dann rausgraben, statt endlos
     * gegen die Wand zu springen.
     */
    private void noteStuck(LocalPlayer player, ClientLevel level) {
        BlockPos feet = feetNode(player);
        if (stuckSpot != null && feet.closerThan(stuckSpot, 2.0D)) {
            stuckHere++;
        } else {
            stuckSpot = feet;
            stuckHere = 1;
        }
        FarmLog.line("STUCK at " + feet.toShortString() + " count=" + stuckHere);
        if (stuckHere >= 3 && escapeCells == null && step == Step.CHOOSE && !outside(feet)) {
            stuckHere = 0;
            NovaNotify.chat("Schematic Builder", "§7Keep getting stuck here - trying to get out another way.");
            startEscape(player, level, true);
        }
    }

    /**
     * Beim Laufen 7 Sekunden auf der Stelle: Die Wegsuche haelt hier etwas fuer machbar, das in Wirklichkeit nicht
     * geht. Aufgabe zurueckstellen; ist er in der Farm, gilt er als eingesperrt und sucht einen sicheren Ausweg
     * (ohne Springen) bzw. graebt sich frei - geht beides nicht, haelt er an und sagt Bescheid.
     */
    private void stuckInPlace(LocalPlayer player, ClientLevel level) {
        BlockPos feet = feetNode(player);
        Vec3 p = player.position();
        FarmLog.line("STUCK IN PLACE feet=" + feet.toShortString() + " goal=" + goal + " move="
                + (moves == null || pathIndex >= moves.size() ? "-" : moves.get(pathIndex))
                + " next=" + (path == null || pathIndex >= path.size() ? "-" : path.get(pathIndex).toShortString())
                + String.format(java.util.Locale.ROOT, " at=%.2f,%.2f,%.2f yaw=%.0f want=%.0f aimErr=%.0f", p.x, p.y, p.z,
                player.getYRot(), lastWalkYaw, aim.error(player))
                + (player.isInWater() ? " water" : "") + (player.isCrouching() ? " crouch" : "")
                + (player.horizontalCollision ? " hcol" : "") + (input == null ? "" : " fwd=" + input.forward()));
        input = idleInput();
        path = null;
        failTask(level, "stuck in place");
        if (!outside(feet)) {
            if (canGetOut(level, feet, escapeOptions(player))) {
                // Laut Wegsuche kommt er hier normal heraus (z.B. mit einem Sprung aus dem leeren Becken): erst nur
                // mitzaehlen - wiederholt an derselben Stelle gilt es als Falle (noteStuck). Frueher hielt jedes
                // einzelne Haengen gleich an ("eingemauert"), weil der sichere Ausweg keinen Sprung enthalten durfte.
                noteStuck(player, level);
                return;
            }
            NovaNotify.chat("Schematic Builder", "§7Can't move on here - looking for a safe way out.");
            startEscape(player, level, true);
        }
    }

    /** Weg nach draussen nur mit Laufen und Runterfallen (kein Springen, kein Bauen) - der klappt sicher. */
    private Pathfinder.Path walkOut(ClientLevel level, BlockPos feet) {
        Pathfinder.Path out = Pathfinder.find(level, feet, this::outside, nearestOutside(feet), 600,
                Pathfinder.Options.WALK_ONLY.withMaxDrop(3));
        return out != null && !out.moves().contains(Pathfinder.Move.JUMP) ? out : null;
    }

    /** Rausgraben klappt nicht: anhalten und Bescheid geben, statt ewig auf der Stelle zu springen. */
    private void trapped() {
        FarmLog.line("TRAPPED" + (mc.player == null ? "" : " feet=" + feetNode(mc.player).toShortString()));
        escapeCells = null;
        setPaused(true, "stuck inside the build");
        NovaNotify.chat("Schematic Builder", "§cStuck inside the build and can't dig out. Break a block to free me, then resume.");
        NovaNotify.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 0.7F);
    }

    private Item scaffoldItem(LocalPlayer player) {
        Item best = null;
        int bestCount = 0;
        for (Item item : SCAFFOLD_ITEMS) {
            int have = countInventory(player, item);
            int reserved = soonNeeded(item); // Material der Farm nicht verbrauchen - aber nur, was bald drankommt
            if (have - reserved > bestCount) {
                bestCount = have - reserved;
                best = item;
            }
        }
        return best;
    }

    /** Wie viele Hilfsbloecke gibt es im Inventar (ohne das, was die Farm selbst noch braucht)? */
    private int scaffoldCount(LocalPlayer player) {
        int n = 0;
        for (Item item : SCAFFOLD_ITEMS) {
            n += Math.max(0, countInventory(player, item) - soonNeeded(item));
        }
        return n;
    }

    private Item bestScaffoldInChests() {
        Item best = null;
        int bestCount = 0;
        for (Item item : SCAFFOLD_ITEMS) {
            if (missing.contains(item)) {
                continue; // Holen ist schon gescheitert (Kiste leer/unerreichbar) - nicht endlos wieder hinlaufen
            }
            // Was in den Kisten liegt und von der Farm (zusammen mit dem Inventar) nicht mehr gebraucht wird
            int inChests = store.available(item);
            int inv = mc.player == null ? 0 : countInventory(mc.player, item);
            int spare = Math.min(inChests, inChests + inv - stillNeeded(item));
            if (spare > bestCount) {
                bestCount = spare;
                best = item;
            }
        }
        return bestCount >= 4 ? best : null;
    }

    /** Wie viele Stueck von item die Farm noch braucht (offene Bloecke), pro Tick zwischengespeichert. */
    private int stillNeeded(Item item) {
        if (neededTick != tick || neededCache == null) {
            neededTick = tick;
            neededCache = new HashMap<>();
            for (BuildPlan.Target t : pending) {
                neededCache.merge(t.need().item(), t.need().count(), Integer::sum);
            }
        }
        return neededCache.getOrDefault(item, 0);
    }

    private Map<Item, Integer> neededCache;
    private int neededTick = -1;

    /**
     * Wie viele Stueck von item die naechsten Bloecke brauchen (pro Tick zwischengespeichert). Hilfsbloecke aus dem
     * Inventar duerfen alles darueber hinaus nehmen - sie kommen beim Abbauen ja zurueck. Frueher zaehlte der ganze
     * Rest der Farm: bei einer Bruchstein-Farm galt dann kein Bruchstein als Hilfsblock, und der Bot holte bei jedem
     * Gang 192 Stueck dazu, bis das Inventar voll war.
     */
    private int soonNeeded(Item item) {
        if (soonTick != tick || soonCache == null) {
            soonTick = tick;
            soonCache = upcomingNeeds(400);
        }
        return soonCache.getOrDefault(item, 0);
    }

    private Map<Item, Integer> soonCache;
    private int soonTick = -1;
    private boolean noScaffoldWarned;

    /** Die Bloecke, die der Bot als Hilfsbloecke (Tuerme, Stuetzen) benutzt - fuer die Materialliste. */
    public static List<Item> helperItems() {
        return List.of(SCAFFOLD_ITEMS);
    }

    // ================================================================ Wasser / Lava

    private void buildFluidList() {
        fluidPending = new ArrayList<>();
        for (BuildPlan.Target t : plan.targets) {
            if (t.need().kind() == Materials.Kind.FLUID || t.waterlogged()) {
                fluidPending.add(t);
            }
        }
    }

    // ---------------------------------------------------------------- Fluessigkeiten schon beim Bauen

    /** Waere die Stelle am Ende ringsum zugebaut (kein Nachbar laut Vorlage leer)? Dann geht es nur waehrend des Baus. */
    private boolean enclosedLater(BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (plan.stateAt(pos.relative(d)) == null) {
                return false;
            }
        }
        return true;
    }

    /** Nasser Block: erst fertig, wenn er steht UND Wasser hat. Quelle: wenn sie da ist. */
    private boolean inlineDone(ClientLevel level, BuildPlan.Target t) {
        if (t.waterlogged()) {
            BlockState have = level.getBlockState(t.pos());
            return have.is(t.state().getBlock()) && Materials.waterlogged(have);
        }
        return fluidDone(level, t);
    }

    /**
     * Liegt ueber der Stelle nur ein flacher Spalt (hoechstens 3 Felder Luft, dann ein Farm-Block)? Dann kommt am Ende
     * auch niemand mehr hin (in einem 1-3 Bloecke hohen Spalt unter einer Kisten-/Trichter-Ebene kann man nicht stehen
     * und nicht hineinsehen) - die Fluessigkeit muss beim Bauen hinein. Bei der Jouan-Farm z.B. alle Wasserkanaele auf
     * Schicht 1 unter den Kisten.
     */
    private boolean coveredLater(BlockPos pos) {
        for (int k = 1; k <= 4; k++) {
            BlockState above = plan.stateAt(pos.above(k));
            if (above != null && !above.getFluidState().isEmpty() && above.getBlock() instanceof LiquidBlock) {
                return false; // darueber wieder Fluessigkeit (Saeule) - zaehlt nicht als Deckel
            }
            if (above != null) {
                return k >= 2; // k == 1: direkt darueber ein Block = schon "eingemauert" nach oben
            }
        }
        return false;
    }

    /** Naechste eingemauerte Fluessigkeit, die jetzt sicher geht, setzen bzw. hinlaufen. true = angefangen. */
    private boolean inlineFluid(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        List<BuildPlan.Target> near = new ArrayList<>();
        java.util.Iterator<BuildPlan.Target> it = inlineFluids.iterator();
        while (it.hasNext()) {
            BuildPlan.Target t = it.next();
            if (inlineDone(level, t)) {
                it.remove();
                inlineAt.remove(t.pos());
                continue;
            }
            if (!deferred(t.pos()) && !givenUp.contains(t.pos())
                    && (currentLayer == Integer.MIN_VALUE || t.pos().getY() <= currentLayer + 1)) {
                near.add(t);
            }
        }
        near.sort(Comparator.comparingDouble(t -> eye.distanceToSqr(Vec3.atCenterOf(t.pos()))));
        int checks = 0;
        for (BuildPlan.Target t : near) {
            if (++checks > 30) {
                break;
            }
            if (!inlineReadyCached(level, t)) {
                continue;
            }
            Item bucket = t.state().is(Blocks.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
            if (missing.contains(bucket)) {
                continue;
            }
            if (countInventory(player, bucket) <= 0) {
                if (bucket == Items.WATER_BUCKET && countInventory(player, Items.BUCKET) > 0 && findRefill(player, level)) {
                    return true;
                }
                if (tick < inventoryFullUntil && freeSlots(player) == 0) {
                    continue; // eben war kein Platz dafuer - nicht im Sekundentakt zu den Kisten laufen
                }
                beginRestock(player, level, bucket);
                return step != Step.CHOOSE;
            }
            target = t;
            targetIsScaffold = false;
            status = t.waterlogged() ? "watering " + t.state().getBlock().getName().getString() : "filling "
                    + nameOf(bucket);
            Placer.Aim a = placer.planFluid(level, player, eye, t.pos(), t.waterlogged(), bucket == Items.WATER_BUCKET);
            if (a != null) {
                currentAim = a;
                go(Step.FLUID_AIM);
                return true;
            }
            if (findStandAndWalk(player, level, t.pos(), Goal.FLUID, t)) {
                return true;
            }
            postpone(t.pos(), 120);
            target = null;
        }
        return false;
    }

    /** Wie inlineReady, aber je Tick gemerkt (die Filter beim Bauen fragen oft nach denselben Stellen). */
    private boolean inlineReadyCached(ClientLevel level, BuildPlan.Target t) {
        if (inlineReadyTick != tick) {
            inlineReadyCache.clear();
            inlineReadyTick = tick;
        }
        Boolean ready = inlineReadyCache.get(t.pos());
        if (ready == null) {
            ready = inlineReady(level, t);
            inlineReadyCache.put(t.pos(), ready);
        }
        return ready;
    }

    /**
     * Darf die Fluessigkeit schon jetzt hin? Nur wenn sie nirgends hinlaeuft, wo sie nicht hingehoert: ringsum (und
     * darunter) dicht oder Felder, in denen sie laut Vorlage ohnehin fliesst - so weit, wie sie wirklich laeuft (7 Felder,
     * nach unten neu). Wassergeloggbare Bloecke (Flechten, Stufen ...) halten fliessendes Wasser auf. Aus einem vollen
     * Block (nasses Laub) laeuft nichts heraus - der wird gleich nach dem Setzen gewaessert, bevor ihn etwas zudeckt.
     * Lava erst, wenn Brennbares in der Naehe, das schon steht, fertig ist (nasses Laub brennt nicht) - Lava zuendet
     * ueber offene Luft an. Was noch fehlt, ist egal: wuerde man darauf warten, waere die Lava oft schon eingemauert.
     */
    /** Warum die letzte Pruefung "nicht sicher" ergab (fuers Log). */
    private String unsafeWhy = "";

    private boolean inlineReady(ClientLevel level, BuildPlan.Target t) {
        unsafeWhy = "";
        unsafeBlockMissing = false;
        BlockPos p = t.pos();
        boolean lava = t.state().is(Blocks.LAVA);
        if (t.waterlogged()) {
            BlockState have = level.getBlockState(p);
            if (!Placer.done(have, t.state())) {
                return false; // erst der Block selbst
            }
            if (have.isCollisionShapeFullBlock(level, p)) {
                return true;
            }
        } else if (!level.getBlockState(p).canBeReplaced()) {
            unsafeWhy = "cell taken by " + level.getBlockState(p).getBlock().getName().getString();
            return false;
        }
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, Integer> dist = new HashMap<>();
        open.add(p);
        dist.put(p, 0);
        while (!open.isEmpty()) {
            BlockPos c = open.poll();
            int cd = dist.get(c);
            for (Direction d : FLOW_DIRS) {
                int nd = d == Direction.DOWN ? 0 : cd + 1;
                if (nd > FLOW_REACH) {
                    continue; // so weit laeuft sie nicht
                }
                BlockPos n = c.relative(d);
                Integer known = dist.get(n);
                if (known != null && known <= nd) {
                    continue;
                }
                BlockState have = level.getBlockState(n);
                FluidState fluid = have.getFluidState();
                if (fluid.isSource() && fluid.is(lava ? FluidTags.LAVA : FluidTags.WATER)) {
                    continue; // dort ist sie schon
                }
                if (!have.isAir() && !have.canBeReplaced() && !(have.getBlock() instanceof LiquidBlock)
                        || have.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer) {
                    continue; // dicht (fliessendes Wasser fuellt keine wassergeloggbaren Bloecke)
                }
                BlockState want = plan.stateAt(n);
                if (want != null && want.is(lava ? Blocks.LAVA : Blocks.WATER)) {
                    // Laut Vorlage ist sie dort ohnehin - dann muessen auch deren Raender dicht sein
                    dist.put(n, nd);
                    open.add(n);
                    if (dist.size() > 400) {
                        unsafeWhy = "flows too far";
                        return false;
                    }
                    continue;
                }
                unsafeWhy = "would flow into " + n.toShortString() + " (" + have.getBlock().getName().getString()
                        + ", plan: " + (want == null ? "air" : want.getBlock().getName().getString()) + ")";
                unsafeBlockMissing = want != null && !want.isAir();
                return false; // liefe ins Leere oder dorthin, wo noch ein Block hinkommt
            }
        }
        if (!lava && !t.waterlogged() && phase != Phase.FLUIDS && floodsStands(dist.keySet())) {
            unsafeWhy = "waiting for blocks above that need a dry stand";
            return false;
        }
        if (lava) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos c = p.offset(dx, dy, dz);
                        BlockState have = level.getBlockState(c);
                        if (!have.ignitedByLava()) {
                            continue;
                        }
                        BlockState want = plan.stateAt(c);
                        if (want == null || !Placer.done(have, want)
                                || Materials.waterlogged(want) && !Materials.waterlogged(have)) {
                            unsafeWhy = "flammable " + have.getBlock().getName().getString() + " at " + c.toShortString();
                            return false; // brennt noch (fremder Block, Hilfsblock aus Holz, Laub ohne Wasser)
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Wuerde t den Kopfplatz ueber einem Standplatz belegen, der direkt neben einer jetzt fuellbaren, noch leeren
     * eingemauerten Quelle liegt (z.B. Werfer ueber dem Wasserkanal - danach kaeme der Eimer nicht mehr hinein)? Dann
     * kommt t erst nach dem Wasser. Billig und ohne Waechter (der pausiert manchmal).
     */
    private boolean blocksFluidStand(ClientLevel level, BuildPlan.Target t) {
        if (inlineAt.isEmpty() || phase != Phase.BUILD && phase != Phase.BUILD2) {
            return false;
        }
        BlockPos stand = t.pos().below();
        BuildPlan.Target near = null;
        for (Direction d : Direction.values()) {
            BuildPlan.Target f = d == Direction.UP ? null : inlineAt.get(stand.relative(d));
            if (f != null && !f.waterlogged()) {
                near = f; // erst die billige Suche, Weltabfragen nur, wenn ueberhaupt eine Quelle daneben liegt
                break;
            }
        }
        if (near == null || !level.getBlockState(stand).canBeReplaced() || !Pathfinder.supports(level, stand.below())) {
            return false;
        }
        // Auch wenn das Wasser noch wartet (Crafter ueber dem Kanal, Kanalboden): bis Runde 2 den Platz freihalten -
        // sonst stehen die Werfer schon darueber, und der Eimer kommt nie mehr an die Quelle. Nicht bei Lava (wartet auf
        // brennbare Bloecke - evtl. genau auf t) und nicht fuer Ueber-Kopf-Bloecke (auf die wartet das Wasser selbst).
        boolean early = (phase == Phase.BUILD || build2Rounds <= 2) && !Placer.needsLookUp(t.state());
        for (Direction d : Direction.values()) {
            if (d == Direction.UP) {
                continue;
            }
            BuildPlan.Target f = inlineAt.get(stand.relative(d));
            if (f == null || f.waterlogged()) {
                continue;
            }
            if (fluidTask(level, f) != null || early && !f.state().is(Blocks.LAVA) && fluidOpen(level, f)) {
                return true;
            }
        }
        return false;
    }

    /** Quelle noch offen (nicht drin, nicht aufgegeben) - egal, ob sie gerade schon gefahrlos gesetzt werden kann. */
    private boolean fluidOpen(ClientLevel level, BuildPlan.Target f) {
        boolean lava = f.state().is(Blocks.LAVA);
        return !givenUp.contains(f.pos()) && attempts.getOrDefault(f.pos(), 0) < INLINE_TRIES
                && !missing.contains(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET) && !inlineDone(level, f)
                && level.getBlockState(f.pos()).canBeReplaced();
    }

    /**
     * Wuerde t den Kopfplatz neben einem Ueber-Kopf-Block (Crafter/Kolben nach unten ...) belegen, von dem aus man
     * ihn geduckt mit steilem Blick nach oben setzt (darunter frei, darunter Boden)? Dann kommt t erst danach - z.B.
     * die Werfer neben den Craftern ueber dem Wasserkanal. Nur solange der andere nicht zu oft gescheitert ist.
     */
    private boolean overheadNeighborWaits(ClientLevel level, BuildPlan.Target t) {
        // Nicht an die Redstone-Reihenfolge gebunden: ab Runde 3 setzte er sonst die Werfer zuerst und nahm den Craftern
        // den einzigen Platz. Runde 3 bekommt der Crafter noch seine normalen Versuche, danach wartet keiner mehr.
        if (phase != Phase.BUILD && (phase != Phase.BUILD2 || build2Rounds > 3)) {
            return false;
        }
        int limit = phase == Phase.BUILD2 && build2Rounds == 3 ? STRICT_TRIES : STRICT_TRIES * 3;
        boolean neighbor = false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BuildPlan.Target o = pendingAt.get(t.pos().relative(d));
            if (o != null && Placer.needsLookUp(o.state()) && !missing.contains(o.need().item())
                    && !givenUp.contains(o.pos()) && attempts.getOrDefault(o.pos(), 0) < limit) {
                neighbor = true; // laenger als sonst: frueh scheitert er oft nur, weil Glas/Tiefenschiefer daneben fehlen
                break;
            }
        }
        // Zwei Ueber-Kopf-Bloecke nebeneinander warten nicht aufeinander; Weltabfragen nur, wenn es einen gibt
        if (!neighbor || Placer.needsLookUp(t.state())) {
            return false;
        }
        BlockPos below = t.pos().below();
        return level.getBlockState(below).canBeReplaced() && Pathfinder.supports(level, below.below());
    }

    /**
     * Steht direkt ueber dem Feld, das das Wasser fluten wuerde, noch ein offener Block, den man nur mit Blick nach
     * oben setzen kann (Crafter/Werfer/Kolben nach unten, Beobachter nach oben)? Den setzt man aus dem trockenen
     * Kanal heraus - das Wasser kommt erst danach (scheitert er zu oft, nicht mehr).
     */
    private boolean floodsStands(Set<BlockPos> region) {
        for (BlockPos c : region) {
            BuildPlan.Target above = pendingAt.get(c.above());
            if (above != null && Placer.needsLookUp(above.state()) && !missing.contains(above.need().item())
                    && !givenUp.contains(above.pos()) && attempts.getOrDefault(above.pos(), 0) < STRICT_TRIES) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wuerde t einer eingemauerten Fluessigkeit daneben die letzte offene Seite nehmen, solange sie gerade gesetzt
     * werden kann (nasses Laub ohne Wasser, Lava-Saeule ohne oberste Quelle)? Dann wartet t, bis sie drin ist -
     * danach kaeme der Eimer nie mehr heran. Nur solange sie nicht zu oft gescheitert ist.
     */
    private boolean closesInline(ClientLevel level, BuildPlan.Target t) {
        if (inlineAt.isEmpty() || phase != Phase.BUILD && phase != Phase.BUILD2) {
            return false;
        }
        for (Direction d : Direction.values()) {
            BlockPos c = t.pos().relative(d);
            BuildPlan.Target f = inlineAt.get(c);
            if (f == null || givenUp.contains(c) || attempts.getOrDefault(c, 0) >= INLINE_TRIES
                    || missing.contains(f.state().is(Blocks.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET)
                    || inlineDone(level, f) || !inlineReadyCached(level, f)) {
                continue;
            }
            boolean otherOpen = false;
            for (Direction e : Direction.values()) {
                BlockPos n = c.relative(e);
                if (n.equals(t.pos())) {
                    continue;
                }
                BlockState s = level.getBlockState(n);
                // Offen = man sieht hindurch (Luft, fliessend); Quellen derselben Saeule zaehlen nicht
                if (s.getCollisionShape(level, n).isEmpty() && !s.getFluidState().isSource()) {
                    otherOpen = true;
                    break;
                }
            }
            if (!otherOpen) {
                return true;
            }
        }
        return false;
    }

    /** Eimer fuer die eingemauerten Fluessigkeiten der naechsten Schichten (je Sorte hoechstens 12). */
    private Map<Item, Integer> inlineBucketNeeds() {
        Map<Item, Integer> need = new LinkedHashMap<>();
        if (inlineFluids.isEmpty() || phase != Phase.BUILD && phase != Phase.BUILD2) {
            return need;
        }
        int lowest = Integer.MAX_VALUE;
        for (BuildPlan.Target t : inlineFluids) {
            lowest = Math.min(lowest, t.pos().getY());
        }
        // Nur fuer die aktuelle und die naechsten beiden Schichten - frueher schon ab Schicht 1 Eimer fuer Schicht 7,
        // die dann wochenlang Inventarplaetze belegten
        int limit = (currentLayer == Integer.MIN_VALUE ? lowest : currentLayer) + 2;
        int lavaChecked = 0;
        for (BuildPlan.Target t : inlineFluids) {
            if (t.pos().getY() <= limit && !givenUp.contains(t.pos())) {
                boolean lava = t.state().is(Blocks.LAVA);
                // Lava nur, wenn sie schon hin darf (Ring drumherum fertig): sonst trug er 15 Lava-Eimer stundenlang mit,
                // und fuer Wasser (jedes Blatt sofort) blieb kaum Platz - 17 Gaenge zur Kiste in 45 Minuten
                if (lava && (mc.level == null || ++lavaChecked > 24 || !inlineReadyCached(mc.level, t))) {
                    continue;
                }
                need.merge(lava ? Items.LAVA_BUCKET : Items.WATER_BUCKET, 1, (a, b) -> Math.min(16, a + b));
            }
        }
        need.computeIfPresent(Items.LAVA_BUCKET, (k, v) -> Math.min(16, v + 2)); // ein paar Reserve
        return need;
    }

    private boolean fluidDone(ClientLevel level, BuildPlan.Target t) {
        BlockState have = level.getBlockState(t.pos());
        if (t.waterlogged()) {
            return Materials.waterlogged(have) || !have.is(t.state().getBlock());
        }
        FluidState fluid = have.getFluidState();
        boolean lava = t.state().is(Blocks.LAVA);
        return fluid.isSource() && (lava ? fluid.is(Fluids.LAVA) : fluid.is(Fluids.WATER));
    }

    private void chooseFluid(LocalPlayer player, ClientLevel level) {
        fluidPending.removeIf(t -> fluidDone(level, t) || givenUp.contains(t.pos()));
        if (fluidPending.isEmpty()) {
            enter(Phase.BUILD2);
            return;
        }
        status = fluidPending.size() + " left";
        Vec3 eye = player.getEyePosition();
        BuildPlan.Target best = null;
        double bestD = Double.MAX_VALUE;
        int lowest = Integer.MAX_VALUE;
        for (BuildPlan.Target t : fluidPending) {
            if (!deferred(t.pos())) {
                lowest = Math.min(lowest, t.pos().getY());
            }
        }
        for (BuildPlan.Target t : fluidPending) {
            if (deferred(t.pos()) || t.pos().getY() > lowest) {
                continue;
            }
            double d = t.pos().distToCenterSqr(eye.x, eye.y, eye.z) + random.nextDouble() * 2.0D;
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        if (best == null) {
            // Alles zurueckgestellt: statt herumzustehen die naechste Quelle gleich noch mal versuchen (begrenzt oft)
            BuildPlan.Target retry = null;
            double retryD = Double.MAX_VALUE;
            for (BuildPlan.Target t : fluidPending) {
                double d = t.pos().distToCenterSqr(eye.x, eye.y, eye.z);
                if (attempts.getOrDefault(t.pos(), 0) < 3 && d < retryD) {
                    retryD = d;
                    retry = t;
                }
            }
            if (retry == null) {
                FarmLog.line("FLUIDS left " + fluidPending.size() + " source(s) without a stand, e.g. "
                        + fluidPending.get(0).pos().toShortString() + " (" + lastStandInfo + ")");
                enter(Phase.BUILD2);
                return;
            }
            deferredUntil.remove(retry.pos());
            wait = delay(6, 14);
            return;
        }
        // Nie Lava/Wasser setzen, die dorthin laeuft, wo sie nicht hingehoert (Lava auf dem Boden = Feuer, Tod). Frisch
        // gerechnet: ein Ergebnis aus der Bauphase (dort wartet Wasser z.B. noch auf Crafter darueber) gilt hier nicht.
        inlineReadyCache.remove(best.pos());
        if (!inlineReadyCached(level, best)) {
            fluidPending.remove(best);
            if (unsafeBlockMissing && !finalFluids) {
                // Ein Block der Vorlage fehlt noch (kommt in den letzten Runden) - danach noch einmal
                FarmLog.line("FLUID later " + best.pos().toShortString() + " " + best.state().getBlock().getName().getString()
                        + " - " + unsafeWhy);
                fluidRetry.add(best);
                return;
            }
            FarmLog.line("FLUID unsafe " + best.pos().toShortString() + " " + best.state().getBlock().getName().getString()
                    + " - skipped: " + unsafeWhy);
            givenUp.add(best.pos());
            return;
        }
        Item bucket = best.state().is(Blocks.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        if (countInventory(player, bucket) <= 0) {
            if (bucket == Items.WATER_BUCKET && countInventory(player, Items.BUCKET) > 0 && findRefill(player, level)) {
                return;
            }
            if (missing.contains(bucket)) {
                FarmLog.line("FLUID no " + bucket + " for " + best.pos().toShortString() + " - skipped");
                givenUp.add(best.pos());
                return;
            }
            beginRestock(player, level, bucket);
            return;
        }
        target = best;
        targetIsScaffold = false;
        Placer.Aim a = placer.planFluid(level, player, eye, best.pos(), best.waterlogged(), bucket == Items.WATER_BUCKET);
        if (a != null) {
            currentAim = a;
            go(Step.FLUID_AIM);
            return;
        }
        if (!findStandAndWalk(player, level, best.pos(), Goal.FLUID, best)) {
            postpone(best.pos(), 120); // zaehlt als Versuch
            target = null;
        }
    }

    private void fluidAim(LocalPlayer player, ClientLevel level) {
        if (target == null) {
            go(Step.CHOOSE);
            return;
        }
        Item bucket = target.state().is(Blocks.LAVA) ? Items.LAVA_BUCKET : Items.WATER_BUCKET;
        if (!prepareItem(player, bucket, Step.FLUID_AIM)) {
            return;
        }
        boolean into = target.waterlogged();
        boolean water = bucket == Items.WATER_BUCKET;
        if (currentAim == null) {
            currentAim = placer.planFluid(level, player, player.getEyePosition(), target.pos(), into, water);
            if (currentAim == null) {
                failTask(level, "fluid angle");
                return;
            }
        }
        // Schleichen entscheidet beim Eimer, wohin das Wasser geht (geschlichen VOR einen wassergeloggbaren Block,
        // sonst IN ihn) - also genau so stehen, wie geplant, auch an einer Kante. Er steht still, rutscht also nicht.
        boolean sneak = currentAim.sneak();
        input = new Input(false, false, false, false, false, sneak, false);
        if (stepTicks > 80) {
            failTask(level, "fluid timeout");
            return;
        }
        if (player.isShiftKeyDown() != sneak) {
            settledTicks = 0;
            return; // Haltung aendert sich noch (Augenhoehe, beim Server angekommen?)
        }
        if (!sneakReplanned) {
            sneakReplanned = true;
            Placer.Aim fresh = placer.planFluid(level, player, player.getEyePosition(), target.pos(), into, water);
            if (fresh != null && fresh.sneak() == sneak) {
                currentAim = fresh; // mit der echten Augenhoehe
            }
        }
        aim.lookAt(player, currentAim.yaw(), currentAim.pitch());
        if (!aim.settled(player) || ++settledTicks < 3 + reaction) {
            return;
        }
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !hit.getBlockPos().equals(currentAim.clicked())
                || !into && hit.getDirection() != currentAim.face()) {
            currentAim = null;
            settledTicks = 0;
            return;
        }
        // Wo koennte die Fluessigkeit faelschlich landen? Alles rund um Ziel und angeklickten Block: den Eimer setzt der
        // Server mit seinem eigenen Blick - frueher wurden nur die beiden erwarteten Felder geprueft, und eine Quelle auf
        // dem Nachbarblock blieb unbemerkt (Generator geflutet, Lava wurde Obsidian)
        fluidWatch.clear();
        for (BlockPos center : new BlockPos[]{target.pos(), hit.getBlockPos()}) {
            for (BlockPos c : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
                if (!c.equals(target.pos()) && !fluidWatch.containsKey(c)) {
                    fluidWatch.put(c.immutable(), level.getBlockState(c));
                }
            }
        }
        // Wie Vanilla: erst auf den Block, dann (wenn nichts passiert) das Item selbst benutzen
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (!result.consumesAction()) {
            result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            Compat.swingUse(player, InteractionHand.MAIN_HAND);
        }
        go(Step.FLUID_VERIFY);
    }

    private void fluidVerify(LocalPlayer player, ClientLevel level) {
        if (target == null) {
            go(Step.CHOOSE);
            return;
        }
        BlockPos stray = strayFluid(level);
        if (stray != null) {
            // Daneben gelandet: sofort wieder aufnehmen (der leere Eimer ist schon in der Hand), dann neu versuchen
            FarmLog.line("FLUID STRAY " + stray.toShortString() + " instead of " + target.pos().toShortString() + " "
                    + target.state().getBlock().getName().getString() + " feet=" + feetNode(player).toShortString()
                    + " sneak=" + player.isShiftKeyDown());
            strayFluids.add(stray);
            fluidWatch.clear();
            postpone(target.pos(), 40);
            target = null;
            go(Step.CHOOSE);
            return;
        }
        if (fluidDone(level, target)) {
            progress();
            placedSession++;
            fluidPending.remove(target);
            fluidWatch.clear();
            FarmLog.line("FLUID " + target.pos().toShortString() + " " + (target.waterlogged() ? "water in "
                    + target.state().getBlock().getName().getString() : target.state().getBlock().getName().getString())
                    + (phase == Phase.FLUIDS ? "" : " (while building)"));
            released(target.pos());
            target = null;
            wait = betweenBlocks() + 2;
            go(Step.CHOOSE);
            return;
        }
        if (stepTicks > 16) {
            fluidWatch.clear();
            int n = attempts.merge(target.pos(), 1, Integer::sum);
            if (n > 3 && !inlineAt.containsKey(target.pos())) {
                givenUp.add(target.pos());
                target = null;
                go(Step.CHOOSE);
            } else if (n > INLINE_TRIES) {
                FarmLog.line("FLUID gave up " + target.pos().toShortString() + " after " + n + " tries");
                givenUp.add(target.pos());
                target = null;
                go(Step.CHOOSE);
            } else {
                failTask(level, "fluid not placed");
            }
        }
    }

    /** Ist beim letzten Eimer-Klick Fluessigkeit in einem Nachbarfeld gelandet, wo die Vorlage keine will? */
    private BlockPos strayFluid(ClientLevel level) {
        for (Map.Entry<BlockPos, BlockState> e : fluidWatch.entrySet()) {
            BlockPos c = e.getKey();
            FluidState now = level.getBlockState(c).getFluidState();
            if (!now.isSource() || e.getValue().getFluidState().isSource()) {
                continue;
            }
            BlockState want = plan.stateAt(c);
            if (want != null && want.getFluidState().isSource() && want.getFluidState().getType().isSame(now.getType())) {
                continue; // dort soll sie ohnehin hin
            }
            return c;
        }
        return null;
    }

    /**
     * Alle paar Sekunden rund um den Bot: Wasser- oder Lava-Quelle in der Farm, wo die Vorlage keine will? Dann
     * aufnehmen. Den Eimer setzt der Server mit seiner eigenen Augenhoehe - einmal landete das Wasser so auf dem
     * Nachbarblock statt im Blatt, lief ueber den Generator und machte aus Lava Obsidian (die Pruefung direkt nach dem
     * Klick sah nur die beiden erwarteten Felder).
     */
    private void scanStray(LocalPlayer player, ClientLevel level) {
        if (tick < nextStrayScan) {
            return;
        }
        nextStrayScan = tick + 100;
        BlockPos feet = feetNode(player);
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-8, -6, -8), feet.offset(8, 4, 8))) {
            if (!plan.contains(p) || strayFluids.contains(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (!s.is(Blocks.WATER) && !s.is(Blocks.LAVA) || !s.getFluidState().isSource()) {
                continue; // nur echte Quellen (wassergeloggte Bloecke zaehlen nicht)
            }
            BlockState want = plan.stateAt(p);
            if (want != null && want.getFluidState().isSource() && want.getFluidState().getType().isSame(s.getFluidState().getType())) {
                continue;
            }
            BlockPos stray = p.immutable();
            FarmLog.line("FLUID STRAY found " + stray.toShortString() + " " + s.getBlock().getName().getString()
                    + " (not in the plan) - picking it up");
            strayFluids.add(stray);
        }
    }

    /**
     * Daneben gelandete Fluessigkeit mit dem leeren Eimer wieder aufnehmen (wie ein Spieler nach einem Fehlklick).
     * true = angefangen (aufnehmen oder hinlaufen).
     */
    private boolean cleanStray(LocalPlayer player, ClientLevel level) {
        java.util.Iterator<BlockPos> it = strayFluids.iterator();
        while (it.hasNext()) {
            BlockPos c = it.next();
            FluidState f = level.getBlockState(c).getFluidState();
            BlockState want = plan.stateAt(c);
            boolean wanted = want != null && want.getFluidState().isSource() && want.getFluidState().getType().isSame(f.getType());
            if (!f.isSource() || wanted || attempts.getOrDefault(c, 0) >= 4) {
                if (f.isSource() && !wanted) {
                    FarmLog.line("FLUID STRAY left at " + c.toShortString() + " (could not pick it up)");
                }
                it.remove();
                continue;
            }
            if (countInventory(player, Items.BUCKET) <= 0) {
                FarmLog.line("FLUID STRAY " + c.toShortString() + " - no empty bucket");
                it.remove();
                continue;
            }
            attempts.merge(c, 1, Integer::sum);
            minePos = c;
            status = "picking up spilled " + (f.is(FluidTags.LAVA) ? "lava" : "water");
            Vec3 surface = new Vec3(c.getX() + 0.5D, c.getY() + 0.85D, c.getZ() + 0.5D);
            if (player.getEyePosition().distanceToSqr(surface) < Math.pow(Placer.reach(player), 2)) {
                go(Step.REFILL);
                return true;
            }
            if (findStandAndWalk(player, level, c, Goal.REFILL, null)) {
                minePos = c;
                return true;
            }
            minePos = null;
        }
        return false;
    }

    /**
     * Darf er hier einen Eimer fuellen? Wasserquelle, und gehoert sie zur Farm, nur eine unendliche (mind. 2 Quellen
     * daneben - sie fuellt sich gleich wieder auf).
     */
    private boolean refillable(ClientLevel level, BlockPos p) {
        FluidState fluid = level.getFluidState(p);
        if (!fluid.isSource() || !fluid.is(Fluids.WATER) || !level.getBlockState(p).is(Blocks.WATER)) {
            return false;
        }
        if (!plan.contains(p) || plan.stateAt(p) == null) {
            return true;
        }
        int sources = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            FluidState n = level.getFluidState(p.relative(d));
            if (n.isSource() && n.is(Fluids.WATER)) {
                sources++;
            }
        }
        return sources >= 2;
    }

    /** Leeren Eimer an einer Wasserquelle fuellen (am besten unendliche Quelle). */
    private boolean findRefill(LocalPlayer player, ClientLevel level) {
        if (tick < refillBlockedUntil) {
            return false; // eben dreimal nicht geklappt - erst einmal aus der Kiste
        }
        BlockPos feet = feetNode(player);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        // Auch weiter unten (z.B. das Becken unter dem schwebenden Generator: hinunterspringen ist kuerzer als zu den Kisten)
        for (BlockPos p : BlockPos.betweenClosed(feet.offset(-12, -10, -12), feet.offset(12, 4, 12))) {
            FluidState fluid = level.getFluidState(p);
            if (!fluid.isSource() || !fluid.is(Fluids.WATER) || !level.getBlockState(p).is(Blocks.WATER)) {
                continue;
            }
            boolean ours = plan.contains(p) && plan.stateAt(p) != null;
            int sources = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                FluidState n = level.getFluidState(p.relative(d));
                if (n.isSource() && n.is(Fluids.WATER)) {
                    sources++;
                }
            }
            if (ours && sources < 2) {
                continue; // nur unendliche Quellen der Farm anzapfen
            }
            double d = p.distSqr(feet);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        if (best == null) {
            return false;
        }
        minePos = best;
        Vec3 surface = new Vec3(best.getX() + 0.5D, best.getY() + 0.85D, best.getZ() + 0.5D);
        if (player.getEyePosition().distanceToSqr(surface) < Math.pow(Placer.reach(player), 2)) {
            go(Step.REFILL);
            return true;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos stand = groundBelow(level, best.relative(d, 2).above(), 4);
            if (stand == null) {
                continue;
            }
            Pathfinder.Path found = Pathfinder.find(level, feet, p -> p.equals(stand), stand, PATH_NODES, pathOptions(player));
            if (found != null && found.buildMoves() <= scaffoldCount(player)) {
                beginWalk(found, Goal.REFILL);
                return true;
            }
        }
        return false;
    }

    private void refill(LocalPlayer player, ClientLevel level) {
        if (minePos == null) {
            go(Step.CHOOSE);
            return;
        }
        if (!prepareItem(player, Items.BUCKET, Step.REFILL)) {
            return;
        }
        Vec3 surface = new Vec3(minePos.getX() + 0.5D, minePos.getY() + 0.85D, minePos.getZ() + 0.5D);
        Vec3 eye = player.getEyePosition();
        input = idleInput();
        aim.lookAt(player, Placer.yawTo(eye, surface), Placer.pitchTo(eye, surface));
        if (stepTicks > 80) {
            minePos = null;
            if (++refillMisses >= 3) {
                refillMisses = 0;
                refillBlockedUntil = tick + 600;
                FarmLog.line("REFILL timed out 3 times at " + feetNode(player).toShortString() + " - buckets from the chests for now");
            }
            go(Step.CHOOSE);
            return;
        }
        if (!aim.settled(player) || ++settledTicks < 3 + reaction) {
            return;
        }
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(Placer.reach(player)));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        // Jede Wasserquelle im Blick taugt (im Becken schob ihn die Stroemung, der Blick traf die Nachbarquelle - und er
        // versuchte es endlos mit derselben, ohne je zu fuellen). Gefahr fuer die Farm nur bei einer einzelnen Quelle.
        boolean source = hit.getType() == HitResult.Type.BLOCK && (hit.getBlockPos().equals(minePos)
                || refillable(level, hit.getBlockPos()));
        if (!source) {
            if (++refillMisses >= 3) {
                refillMisses = 0;
                refillBlockedUntil = tick + 600;
                FarmLog.line("REFILL failed 3 times at " + feetNode(player).toShortString() + " want=" + minePos.toShortString()
                        + " hit=" + hit.getType() + (hit.getType() == HitResult.Type.BLOCK ? " " + hit.getBlockPos().toShortString()
                        + " " + level.getBlockState(hit.getBlockPos()).getBlock().getName().getString() : "")
                        + String.format(java.util.Locale.ROOT, " eye=%.2f", eye.y) + (player.isInWater() ? " water" : "")
                        + (player.isUnderWater() ? " under" : "") + " - buckets from the chests for now");
            }
            minePos = null;
            go(Step.CHOOSE);
            return;
        }
        refillMisses = 0;
        InteractionResult result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            Compat.swingUse(player, InteractionHand.MAIN_HAND);
        }
        minePos = null;
        wait = delay(4, 9);
        go(Step.CHOOSE);
    }

    // ================================================================ Umschauen

    private void startLookAround(LocalPlayer player) {
        float yaw = player.getYRot() + (random.nextBoolean() ? 1 : -1) * (35.0F + random.nextFloat() * 90.0F);
        float pitch = -12.0F + random.nextFloat() * 30.0F;
        aim.lookAt(player, yaw, pitch);
        status = "looking around";
        afterLook = Step.CHOOSE;
        go(Step.LOOK_AROUND);
        wait = delay(18, 45);
    }

    private void lookAround(LocalPlayer player) {
        if (aim.settled(player)) {
            status = "";
            go(afterLook == null ? Step.CHOOSE : afterLook);
        }
    }

    // ================================================================ Inventar-Hilfen

    /**
     * Rechtsklick, der ein Menue oeffnen soll (Crafter, Kiste): nie schleichend - sonst oeffnet sich nichts (hoch oben an
     * Kanten schlich idleInput, und jeder Crafter lief in "crafter timeout"). Rollt er noch aus, lieber noch geduckt.
     */
    private Input openInput(LocalPlayer player) {
        if (player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4D) {
            return idleInput();
        }
        return new Input(false, false, false, false, false, false, false);
    }

    private Input idleInput() {
        LocalPlayer player = mc.player;
        boolean sneak = player != null && mc.level != null && player.onGround()
                && player.getY() - plan.origin.getY() > 1.5D && Pathfinder.nearDrop(mc.level, player.blockPosition());
        return new Input(false, false, false, false, false, sneak, false);
    }

    private int delay(int min, int max) {
        int base = min + random.nextInt(Math.max(1, max - min + 1));
        return Math.max(1, Math.round(base / settings.speed));
    }

    public static int countInventory(LocalPlayer player, Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    public static Map<Item, Integer> inventoryCounts(LocalPlayer player) {
        Map<Item, Integer> map = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                map.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return map;
    }

    private static int freeSlots(LocalPlayer player) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static int findHotbar(LocalPlayer player, Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int findMain(LocalPlayer player, Item item) {
        for (int i = 9; i < 36; i++) {
            if (player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /** Menue-Slot-Nummer fuer einen Inventarplatz des Spielers. */
    private static int menuSlot(AbstractContainerMenu menu, LocalPlayer player, int inventoryIndex) {
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return -1;
    }

    public static String nameOf(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    /** Alle Items, die bisher fehlten, als Text. */
    public List<String> missingNames() {
        List<String> names = new ArrayList<>();
        for (Item item : missing) {
            names.add(nameOf(item));
        }
        return names;
    }

    public Set<BlockPos> givenUp() {
        return new HashSet<>(givenUp);
    }
}
