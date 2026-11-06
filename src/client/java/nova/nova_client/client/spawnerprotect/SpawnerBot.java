package nova.nova_client.client.spawnerprotect;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.farmbuilder.HumanAim;
import nova.nova_client.client.farmbuilder.Pathfinder;
import nova.nova_client.client.farmbuilder.Placer;
import nova.nova_client.client.farmbuilder.Tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Der Bot hinter Spawner Protect: laeuft zu jedem ausgewaehlten Spawner, baut ihn mit der besten Spitzhacke ab (Silk
 * Touch zuerst), sammelt herumliegende Spawner ein, oeffnet die Ender-Kiste und legt alle Spawner per Shift-Klick
 * hinein. Gelaufen wird mit echten "Tasten" (KeyboardInputMixin), die Kamera dreht sich wie mit der Maus (HumanAim,
 * schneller als beim Schematic Builder - es eilt).
 */
public final class SpawnerBot {
    public enum Phase {
        IDLE, MINING, COLLECTING, STORING, DONE, FAILED
    }

    /** Sicherheitsgrenze: so lange ohne jeden Fortschritt (Abbau, Einsammeln, Einlagern) = abbrechen. */
    private static final int MAX_IDLE_TICKS = 20 * 60;
    /** So lange darf ein einzelner Abbau dauern (auch mit schlechter Spitzhacke). */
    private static final int MAX_MINE_TICKS = 20 * 20;
    /** Drops kommen erst einen Moment nach dem Abbauen vom Server. */
    private static final int MIN_COLLECT_TICKS = 10;
    private static final int MAX_COLLECT_TICKS = 20 * 10;
    private static final int OPEN_TIMEOUT = 40;
    private static final int MAX_OPEN_TRIES = 3;
    private static final int PATH_NODES = 8000;
    private static final int MAX_REPATHS = 4;
    private static final int NO_PROGRESS_TICKS = 40;
    private static final int MAX_AIM_TICKS = 40;
    private static final float AIM_SPEED = 2.2F;

    private final Minecraft mc = Minecraft.getInstance();
    private final HumanAim aim = new HumanAim();
    private final Placer placer = new Placer();

    private Phase phase = Phase.IDLE;
    private String status = "";
    private String failure = "";
    private Input input = Input.EMPTY;
    private int ticks;
    private int phaseTicks;

    private final List<BlockPos> todo = new ArrayList<>();
    private final List<BlockPos> skipped = new ArrayList<>();
    private final Set<Integer> ignoredItems = new HashSet<>();
    private BlockPos enderChest;
    private int total;
    private int mined;

    // Laufen
    private List<BlockPos> path;
    private int pathIndex;
    private BlockPos pathFor;
    private double bestDistance;
    private int noProgress;
    private int repaths;

    // Zielen / Abbauen
    private BlockPos aimFor;
    private Placer.Aim currentAim;
    private int aimTicks;
    /** Wie oft schon ein anderer Punkt am Block versucht wurde (Fadenkreuz traf nie). */
    private int aimRetries;
    private BlockPos miningPos;
    private int miningTicks;
    private boolean mining;
    /**
     * Gerade abgebaut, aber noch nicht bestaetigt: Gestapelte Spawner (mehr als 64) setzt der Server nach dem Abbauen
     * wieder hin - dann muss man nochmal abbauen. Erst wenn der Block so lange weg bleibt, ist der Spawner fertig.
     */
    private BlockPos pendingPos;
    private int pendingSince;
    /** Abbau-Vorgaenge insgesamt (bei Stapeln mehrere je Spawner). */
    private int breaks;
    private int lastProgress;
    /** Inventar war voll: nach dem Einlagern weiter abbauen. */
    private boolean resumeMining;

    // Ender-Kiste
    private int clickedAt = -1;
    private int openTries;
    private boolean chestSeen;
    private int storeWait;
    private int countAtOpen = -1;
    private int lastCount = -1;
    private int noStoreProgress;
    private int stored;
    private int leftOver;

    // Ender-Kiste besorgen (keine ausgewaehlt oder weg): aus dem Inventar setzen oder per /shop kaufen
    private boolean autoChest;
    private boolean buyChest;
    private int chestTicks;
    private int shopStep;
    private int shopWait;
    private BlockPos placeTarget;
    private int placeTries;
    private int lastPlaceAt = -100;

    // ================================================================ Zustand

    public boolean running() {
        return phase == Phase.MINING || phase == Phase.COLLECTING || phase == Phase.STORING;
    }

    public Phase phase() {
        return phase;
    }

    public String status() {
        return status;
    }

    public String failure() {
        return failure;
    }

    public int total() {
        return total;
    }

    public int mined() {
        return mined;
    }

    public int breaks() {
        return breaks;
    }

    public int skipped() {
        return skipped.size();
    }

    public int stored() {
        return stored;
    }

    /** Spawner, die nicht mehr in die Kiste passten. */
    public int leftOver() {
        return leftOver;
    }

    /** Woran der Bot gerade arbeitet (fuer die Markierung in der Welt), sonst null. */
    public BlockPos currentTarget() {
        return phase == Phase.MINING ? aimFor : phase == Phase.STORING ? enderChest : null;
    }

    /** Eingabe fuer KeyboardInputMixin (null = Spieler steuert selbst). */
    public Input inputOverride() {
        return running() ? input : null;
    }

    // ================================================================ Start / Stopp

    /**
     * Startet mit den Spawnern, die gerade wirklich (geladen) als Spawner dastehen. autoChest: fehlt die Ender-Kiste,
     * eine aus dem Inventar setzen; buyChest: notfalls per /shop kaufen (DonutSMP).
     */
    public void start(Collection<BlockPos> spawners, BlockPos chest, boolean autoChest, boolean buyChest) {
        this.autoChest = autoChest;
        this.buyChest = buyChest;
        resetChestHunt();
        ClientLevel level = mc.level;
        todo.clear();
        skipped.clear();
        ignoredItems.clear();
        if (level != null) {
            for (BlockPos pos : spawners) {
                if (level.isLoaded(pos) && isSpawner(level, pos)) {
                    todo.add(pos.immutable());
                }
            }
        }
        enderChest = chest;
        total = todo.size();
        mined = 0;
        breaks = 0;
        stored = 0;
        leftOver = 0;
        ticks = 0;
        lastProgress = 0;
        pendingPos = null;
        resumeMining = false;
        failure = "";
        input = Input.EMPTY;
        aim.reset();
        aim.setSpeed(AIM_SPEED);
        go(todo.isEmpty() ? Phase.STORING : Phase.MINING);
    }

    public void stop() {
        releaseMining();
        input = Input.EMPTY;
        aim.reset();
        if (running()) {
            phase = Phase.IDLE;
        }
        status = "stopped";
    }

    /** Nach DONE/FAILED: wieder bereit. */
    public void reset() {
        stop();
        phase = Phase.IDLE;
    }

    /** Pro Frame aus dem HUD: Kamera fluessig bewegen. */
    public void frame() {
        if (running() && mc.gui.screen() == null) {
            aim.update(mc);
        }
    }

    private void go(Phase next) {
        phase = next;
        phaseTicks = 0;
        lastProgress = ticks;
        resetMove();
        resetAim();
        clickedAt = -1;
        openTries = 0;
        chestSeen = false;
        countAtOpen = -1;
        lastCount = -1;
        noStoreProgress = 0;
        storeWait = 0;
    }

    private void fail(String reason) {
        releaseMining();
        input = Input.EMPTY;
        failure = reason;
        status = "§c" + reason;
        phase = Phase.FAILED;
    }

    // ================================================================ Tick

    public void tick() {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!running() || player == null || level == null || mc.gameMode == null) {
            return;
        }
        ticks++;
        phaseTicks++;
        if (ticks - lastProgress > MAX_IDLE_TICKS) {
            fail("no progress for " + MAX_IDLE_TICKS / 20 + " seconds");
            return;
        }
        aim.update(mc);
        input = Input.EMPTY;
        switch (phase) {
            case MINING -> mine(player, level);
            case COLLECTING -> collect(player, level);
            case STORING -> store(player, level);
            default -> {
            }
        }
    }

    // ================================================================ Abbauen

    private void mine(LocalPlayer player, ClientLevel level) {
        // Gerade abgebaut: kommt der Spawner wieder (Stapel ueber 64), nochmal abbauen - sonst ist er fertig
        if (pendingPos != null) {
            if (level.isLoaded(pendingPos) && isSpawner(level, pendingPos)) {
                pendingPos = null; // Rest vom Stapel: gleich weiter abbauen
            } else if (ticks - pendingSince >= confirmTicks()) {
                todo.remove(pendingPos);
                mined++;
                lastProgress = ticks;
                if (pendingPos.equals(miningPos)) {
                    miningPos = null;
                }
                pendingPos = null;
                resetAim();
                resetMove();
            } else {
                status = "Mining spawner " + Math.min(total, mined + skipped.size() + 1) + " / " + total
                        + "  ·  checking for more";
                return; // Antwort vom Server abwarten
            }
        }
        // Weg ist weg (z.B. von jemand anderem abgebaut)
        todo.removeIf(pos -> !level.isLoaded(pos) || !isSpawner(level, pos));
        if (todo.isEmpty()) {
            releaseMining();
            go(Phase.COLLECTING);
            return;
        }
        // Inventar voll: erst einlagern, dann weiter abbauen (sonst fallen die Spawner auf den Boden)
        if (!hasRoomForSpawners(player)) {
            releaseMining();
            if (countSpawners(player) == 0) {
                fail("your inventory is full - keep a few slots free");
                return;
            }
            resumeMining = true;
            go(Phase.STORING);
            return;
        }
        BlockPos target = miningPos != null && todo.contains(miningPos) ? miningPos : nearest(todo, player.getEyePosition());
        status = "Mining spawner " + Math.min(total, mined + skipped.size() + 1) + " / " + total
                + (breaks > 0 ? "  ·  " + breaks + " breaks" : "");

        Placer.Aim a = aimAt(player, level, target);
        if (a == null) {
            // Von hier nicht zu treffen: hinlaufen
            releaseMining();
            if (!walk(player, level, target, spot -> canClickFrom(level, player, spot, target))) {
                skip(target);
            }
            return;
        }
        resetMove();
        aim.lookAt(player, a.yaw(), a.pitch());
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !hit.getBlockPos().equals(target)) {
            releaseMining();
            if (++aimTicks > MAX_AIM_TICKS) {
                currentAim = null; // anderen Punkt am Block versuchen
                aimTicks = 0;
                if (++aimRetries > 3) {
                    skip(target);
                }
            }
            return;
        }
        aimTicks = 0;
        int tool = selectPickaxe(player);
        if (tool < 0) {
            fail("no pickaxe in your inventory");
            return;
        }
        if (tool == 0) {
            return; // Spitzhacke kommt gerade in die Hand
        }
        if (!target.equals(miningPos)) {
            miningPos = target;
            miningTicks = 0;
        }
        mc.gameMode.continueDestroyBlock(target, hit.getDirection());
        mining = true;
        Compat.swing(player, InteractionHand.MAIN_HAND);
        // Fertig abgebaut? Minecraft setzt den Block sofort selbst auf Luft (Vorhersage), der Server bestaetigt spaeter
        if (!isSpawner(level, target)) {
            mining = false;
            breaks++;
            miningTicks = 0;
            pendingPos = target;
            pendingSince = ticks;
            lastProgress = ticks;
            return;
        }
        if (++miningTicks > MAX_MINE_TICKS) {
            skip(target);
        }
    }

    /** So lange warten, ob der Server den Spawner wieder hinsetzt: etwa zwei Ping-Laufzeiten plus Puffer. */
    private int confirmTicks() {
        int latency = 0;
        if (mc.getConnection() != null && mc.player != null) {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            latency = info == null ? 0 : info.getLatency();
        }
        return Mth.clamp(8 + latency * 2 / 50, 8, 40);
    }

    /** Passt noch ein Spawner ins Inventar (freier Platz oder ein nicht voller Spawner-Stapel)? */
    private static boolean hasRoomForSpawners(LocalPlayer player) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || stack.is(Items.SPAWNER) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private void skip(BlockPos target) {
        todo.remove(target);
        skipped.add(target);
        if (target.equals(pendingPos)) {
            pendingPos = null;
        }
        releaseMining();
        miningPos = null;
        resetAim();
        resetMove();
    }

    private void releaseMining() {
        if (mining && mc.gameMode != null && mc.gameMode.isDestroying()) {
            mc.gameMode.stopDestroyBlock();
        }
        mining = false;
    }

    /**
     * Beste Spitzhacke in die Hand (Silk Touch zuerst, dann die schnellste). 1 = bereit, 0 = wird gerade aus dem
     * Inventar in die Hotbar getauscht, -1 = keine da.
     */
    private int selectPickaxe(LocalPlayer player) {
        Inventory inv = player.getInventory();
        BlockState spawner = Blocks.SPAWNER.defaultBlockState();
        int best = -1;
        float bestScore = -1.0F;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.is(ItemTags.PICKAXES) || !Tools.usable(stack)) {
                continue;
            }
            float score = (silkTouch(stack) ? 1000.0F : 0.0F) + stack.getDestroySpeed(spawner);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) {
            return -1;
        }
        if (best < 9) {
            if (inv.getSelectedSlot() != best) {
                inv.setSelectedSlot(best);
            }
            return 1;
        }
        // Liegt nur im Inventar: in den aktuellen Hotbar-Platz tauschen (Inventar-Platz 9-35 = Menue-Platz 9-35)
        mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, best, inv.getSelectedSlot(), ContainerInput.SWAP, player);
        return 0;
    }

    private static boolean silkTouch(ItemStack stack) {
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            if (enchantment.is(Enchantments.SILK_TOUCH)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ Einsammeln

    private void collect(LocalPlayer player, ClientLevel level) {
        status = "Picking up the spawners";
        if (phaseTicks < MIN_COLLECT_TICKS) {
            return;
        }
        ItemEntity item = nearestSpawnerItem(player, level);
        if (item == null || phaseTicks > MAX_COLLECT_TICKS) {
            go(Phase.STORING);
            return;
        }
        BlockPos at = item.blockPosition();
        // Auf den Block des Items (oder direkt daneben) - dort sammelt Minecraft es von selbst ein
        if (!walk(player, level, at, spot -> spot.equals(at) || spot.closerThan(at, 1.1D))) {
            ignoredItems.add(item.getId());
            resetMove();
        }
    }

    private ItemEntity nearestSpawnerItem(LocalPlayer player, ClientLevel level) {
        ItemEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(16.0D),
                e -> e.isAlive() && e.getItem().is(Items.SPAWNER) && !ignoredItems.contains(e.getId()))) {
            double d = item.distanceToSqr(player);
            if (d < bestDistance) {
                bestDistance = d;
                best = item;
            }
        }
        return best;
    }

    // ================================================================ Ender-Kiste

    private void store(LocalPlayer player, ClientLevel level) {
        int count = countSpawners(player);
        Screen screen = mc.gui.screen();
        if (clickedAt >= 0 && screen instanceof AbstractContainerScreen<?> container && !(screen instanceof InventoryScreen)) {
            transfer(player, container, count);
            return;
        }
        if (count == 0) {
            storeDone();
            return;
        }
        status = "Putting " + count + " spawners into the ender chest";
        if (enderChest == null || !level.isLoaded(enderChest)
                || !(level.getBlockState(enderChest).getBlock() instanceof EnderChestBlock)) {
            if (!autoChest) {
                fail("the ender chest is gone");
                return;
            }
            getChest(player, level);
            return;
        }
        if (clickedAt >= 0) {
            if (phaseTicks - clickedAt < OPEN_TIMEOUT) {
                return; // wartet auf die Kiste
            }
            clickedAt = -1; // nicht aufgegangen: nochmal klicken
            if (++openTries >= MAX_OPEN_TRIES) {
                fail("the ender chest did not open");
                return;
            }
        }
        Placer.Aim a = aimAt(player, level, enderChest);
        if (a == null) {
            BlockPos chest = enderChest;
            if (!walk(player, level, chest, spot -> canClickFrom(level, player, spot, chest))) {
                fail("cannot reach the ender chest");
            }
            return;
        }
        resetMove();
        aim.lookAt(player, a.yaw(), a.pitch());
        if (!aim.settled(player)) {
            return;
        }
        BlockHitResult hit = crosshair(player, level);
        if (hit == null || !hit.getBlockPos().equals(enderChest)) {
            if (++aimTicks > MAX_AIM_TICKS) {
                currentAim = null;
                aimTicks = 0;
            }
            return;
        }
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            Compat.swingUse(player, InteractionHand.MAIN_HAND);
        }
        clickedAt = phaseTicks;
    }

    /** Kiste ist offen: Spawner einzeln per Shift-Klick hinein, bis keiner mehr da ist (oder die Kiste voll ist). */
    private void transfer(LocalPlayer player, AbstractContainerScreen<?> screen, int count) {
        status = "Putting spawners into the ender chest";
        if (!chestSeen) {
            chestSeen = true;
            countAtOpen = count;
            storeWait = 4; // Inhalt kommt erst vom Server
            return;
        }
        if (storeWait > 0) {
            storeWait--;
            return;
        }
        if (count > 0 && lastCount >= 0 && count >= lastCount) {
            if (++noStoreProgress > 6) {
                // Kiste voll: aufhoeren - restliche Spawner bleiben lieber stehen, als auf dem Boden zu landen
                leftOver = count;
                stored += Math.max(0, countAtOpen - count);
                player.closeContainer();
                finish();
                return;
            }
        } else {
            noStoreProgress = 0;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (count > 0) {
            for (Slot slot : menu.slots) {
                if (slot.container == player.getInventory() && slot.getItem().is(Items.SPAWNER)) {
                    lastCount = count;
                    mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, player);
                    storeWait = 1;
                    lastProgress = ticks;
                    return;
                }
            }
        }
        stored += Math.max(0, countAtOpen - count);
        player.closeContainer();
        storeDone();
    }

    /** Alles eingelagert: war das Inventar zwischendurch voll, weiter abbauen - sonst fertig. */
    private void storeDone() {
        if (resumeMining) {
            resumeMining = false;
            go(Phase.MINING);
        } else {
            finish();
        }
    }

    private void finish() {
        releaseMining();
        input = Input.EMPTY;
        status = "done";
        phase = Phase.DONE;
    }

    // ================================================================ Ender-Kiste besorgen

    /** So lange darf das Besorgen insgesamt dauern. */
    private static final int CHEST_TIMEOUT = 20 * 40;
    /** /shop von DonutSMP: Fenster-Titel und Platz, der darin angeklickt wird (End -> Ender Chest -> Kaufen). */
    private static final String[] SHOP_TITLES = {"SHOP", "END", "ENDER CHEST"};
    private static final int[] SHOP_SLOTS = {11, 9, 25};

    private void resetChestHunt() {
        chestTicks = 0;
        shopStep = 0;
        shopWait = 0;
        placeTarget = null;
        placeTries = 0;
        lastPlaceAt = -100;
    }

    /** Keine (brauchbare) Ender-Kiste: eine in der Naehe nehmen, eine aus dem Inventar setzen oder eine kaufen. */
    private void getChest(LocalPlayer player, ClientLevel level) {
        if (++chestTicks > CHEST_TIMEOUT) {
            closeMenu(player);
            fail("could not get an ender chest");
            return;
        }
        BlockPos near = nearbyEnderChest(level, player.blockPosition());
        if (near != null) {
            closeMenu(player);
            enderChest = near;
            resetChestHunt();
            resetAim();
            lastProgress = ticks;
            return;
        }
        int slot = -1;
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).is(Items.ENDER_CHEST)) {
                slot = i;
                break;
            }
        }
        if (slot >= 0) {
            closeMenu(player);
            placeChest(player, level, slot);
            return;
        }
        if (!buyChest) {
            fail("no ender chest (none placed, none in your inventory)");
            return;
        }
        buyChest(player);
    }

    private static BlockPos nearbyEnderChest(ClientLevel level, BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -2, -4), origin.offset(4, 3, 4))) {
            if (level.getBlockState(pos).getBlock() instanceof EnderChestBlock) {
                double d = pos.distSqr(origin);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    /** Ender-Kiste aus dem Inventar neben dich setzen (mit echtem Hinschauen wie beim Bauen). */
    private void placeChest(LocalPlayer player, ClientLevel level, int slot) {
        status = "Placing an ender chest";
        Inventory inv = player.getInventory();
        if (slot >= 9) {
            mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, inv.getSelectedSlot(), ContainerInput.SWAP, player);
            return;
        }
        if (inv.getSelectedSlot() != slot) {
            inv.setSelectedSlot(slot);
            return;
        }
        if (phaseTicks - lastPlaceAt < 10) {
            return; // Antwort vom Server abwarten
        }
        if (placeTarget == null || !level.getBlockState(placeTarget).canBeReplaced()) {
            placeTarget = placeSpot(player, level);
        }
        if (placeTarget == null) {
            fail("no free spot for an ender chest");
            return;
        }
        Placer.Aim a = placer.planPlacement(level, player, player.getEyePosition(), placeTarget,
                Blocks.ENDER_CHEST.defaultBlockState(), inv.getItem(slot), false);
        if (a == null || a.sneak()) {
            placeTarget = null;
            if (++placeTries > 8) {
                fail("could not place an ender chest");
            }
            return;
        }
        aim.lookAt(player, a.yaw(), a.pitch());
        if (!aim.settled(player)) {
            return;
        }
        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, a.hitResult());
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            Compat.swingUse(player, InteractionHand.MAIN_HAND);
        }
        lastPlaceAt = phaseTicks;
        lastProgress = ticks;
        if (++placeTries > 8) {
            fail("could not place an ender chest");
        }
    }

    /** Freier Platz in Reichweite mit festem Boden darunter, nicht da, wo du stehst. */
    private static BlockPos placeSpot(LocalPlayer player, ClientLevel level) {
        BlockPos feet = player.blockPosition();
        Vec3 eye = player.getEyePosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-2, -1, -2), feet.offset(2, 1, 2))) {
            if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()
                    || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    || Placer.intersectsPlayer(level, feet, pos)) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < bestDistance) {
                bestDistance = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** /shop -> End -> Ender Chest -> Kaufen (DonutSMP). Klappt ein Schritt nicht, von vorn. */
    private void buyChest(LocalPlayer player) {
        status = "Buying an ender chest (/shop)";
        Screen screen = mc.gui.screen();
        shopWait++;
        if (shopStep == 0) {
            if (shopWait >= 20) {
                closeMenu(player);
                player.connection.sendCommand("shop");
                shopStep = 1;
                shopWait = 0;
            }
            return;
        }
        if (shopStep > SHOP_TITLES.length) {
            // Gekauft? Dann taucht sie gleich im Inventar auf - sonst nochmal
            if (shopWait > 60) {
                shopStep = 0;
                shopWait = 0;
            }
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container) || screen instanceof InventoryScreen) {
            if (shopWait > 80) {
                shopStep = 0; // Menue nicht (mehr) offen: von vorn
                shopWait = 0;
            }
            return;
        }
        String title = screen.getTitle().getString().toUpperCase(Locale.ROOT);
        if (title.contains(SHOP_TITLES[shopStep - 1]) && shopWait >= 8) {
            mc.gameMode.handleContainerInput(container.getMenu().containerId, SHOP_SLOTS[shopStep - 1], 0, ContainerInput.PICKUP, player);
            shopStep++;
            shopWait = 0;
            lastProgress = ticks;
        }
    }

    private void closeMenu(LocalPlayer player) {
        Screen screen = mc.gui.screen();
        if (screen instanceof AbstractContainerScreen<?> && !(screen instanceof InventoryScreen)) {
            player.closeContainer();
        }
    }

    private static int countSpawners(LocalPlayer player) {
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(Items.SPAWNER)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    // ================================================================ Zielen

    /** Blickpunkt auf target, solange er von hier aus zu treffen ist (bleibt gleich, kein Zappeln), sonst null. */
    private Placer.Aim aimAt(LocalPlayer player, ClientLevel level, BlockPos target) {
        Vec3 eye = player.getEyePosition();
        if (!target.equals(aimFor)) {
            aimFor = target;
            currentAim = null;
            aimTicks = 0;
            aimRetries = 0;
        }
        double reach = Placer.reach(player);
        if (currentAim != null && (eye.distanceToSqr(currentAim.hit()) > reach * reach
                || !Placer.visible(level, player, eye, currentAim.hit(), target, currentAim.face()))) {
            currentAim = null;
        }
        if (currentAim == null) {
            currentAim = placer.planClickOn(level, player, eye, target, null, false);
        }
        return currentAim;
    }

    private void resetAim() {
        aimFor = null;
        currentAim = null;
        aimTicks = 0;
        aimRetries = 0;
    }

    /** Kann man von diesem Standplatz aus auf target klicken (in Reichweite und sichtbar)? */
    private boolean canClickFrom(ClientLevel level, LocalPlayer player, BlockPos spot, BlockPos target) {
        if (!Pathfinder.standable(level, spot) || Placer.intersectsPlayer(level, spot, target)) {
            return false;
        }
        Vec3 eye = Placer.eyeAt(level, spot);
        double reach = Placer.reach(player) - 0.25D;
        // Schneller Vorab-Test: Blockmitte deutlich ausser Reichweite (halbe Raumdiagonale ~0.87)
        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > (reach + 0.9D) * (reach + 0.9D)) {
            return false;
        }
        return placer.planClickOn(level, player, eye, target, null, false) != null;
    }

    private static BlockHitResult crosshair(LocalPlayer player, ClientLevel level) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(Placer.reach(player) + 0.3D));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    // ================================================================ Laufen

    /** Zu einem Platz laufen, der goal erfuellt. false = kein Weg (mehr) moeglich. */
    private boolean walk(LocalPlayer player, ClientLevel level, BlockPos target, Predicate<BlockPos> goal) {
        if (path == null || !target.equals(pathFor)) {
            if (!player.onGround() && !player.isInWater()) {
                return true; // erst landen, dann planen
            }
            if (!target.equals(pathFor)) {
                repaths = 0;
            }
            if (++repaths > MAX_REPATHS) {
                return false;
            }
            Pathfinder.Path found = Pathfinder.find(level, feetNode(player), goal, target, PATH_NODES,
                    Pathfinder.Options.WALK_ONLY);
            if (found == null) {
                return false;
            }
            path = new ArrayList<>(found.nodes());
            pathIndex = 0;
            pathFor = target;
            bestDistance = Double.MAX_VALUE;
            noProgress = 0;
        }
        follow(player);
        return true;
    }

    private void follow(LocalPlayer player) {
        Vec3 pos = player.position();
        BlockPos node = path.get(pathIndex);
        while (pathIndex < path.size() - 1 && reached(pos, node, false)) {
            pathIndex++;
            node = path.get(pathIndex);
            bestDistance = Double.MAX_VALUE;
        }
        boolean last = pathIndex == path.size() - 1;
        if (last && reached(pos, node, true)) {
            // Angekommen, aber das Ziel ist von hier doch nicht zu treffen: neu planen (zaehlt als Versuch)
            path = null;
            return;
        }
        // leicht vorausschauend zielen (Kurven statt Ecken)
        Vec3 aimPoint = center(node);
        if (!last) {
            double blend = Mth.clamp(1.0D - horizontal(pos, aimPoint) / 1.6D, 0.0D, 0.6D);
            aimPoint = aimPoint.lerp(center(path.get(pathIndex + 1)), blend);
        }
        float yaw = Placer.yawTo(pos, aimPoint);
        aim.follow(yaw, 15.0F);
        float yawError = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
        boolean forward = yawError < 55.0F;
        int floorY = Mth.floor(player.getY() + 0.01D);
        boolean stepUp = node.getY() > floorY && horizontal(pos, center(node)) < 1.5D;
        boolean jump = player.onGround() && forward && (stepUp || player.horizontalCollision && yawError < 30.0F);
        boolean sprint = forward && yawError < 20.0F && path.size() - pathIndex > 3 && player.getFoodData().getFoodLevel() > 6;
        input = new Input(forward, false, false, false, jump, false, sprint);

        // Haengt fest? Neu planen
        double dist = horizontal(pos, center(node)) + Math.abs(pos.y - node.getY());
        if (dist < bestDistance - 0.05D) {
            bestDistance = dist;
            noProgress = 0;
        } else if (++noProgress > NO_PROGRESS_TICKS) {
            path = null;
            noProgress = 0;
        }
    }

    private void resetMove() {
        path = null;
        pathFor = null;
        pathIndex = 0;
        noProgress = 0;
        repaths = 0;
        input = Input.EMPTY;
    }

    private static boolean reached(Vec3 pos, BlockPos node, boolean finalNode) {
        double tolerance = finalNode ? 0.38D : 0.55D;
        return horizontal(pos, center(node)) < tolerance && pos.y > node.getY() - 0.4D && pos.y < node.getY() + 1.2D;
    }

    private static BlockPos feetNode(LocalPlayer player) {
        return BlockPos.containing(player.getX(), player.getY() + (player.onGround() ? 0.45D : 0.25D), player.getZ());
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static BlockPos nearest(List<BlockPos> positions, Vec3 from) {
        BlockPos best = positions.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : positions) {
            double d = from.distanceToSqr(Vec3.atCenterOf(pos));
            if (d < bestDistance) {
                bestDistance = d;
                best = pos;
            }
        }
        return best;
    }

    public static boolean isSpawner(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.SPAWNER);
    }
}
