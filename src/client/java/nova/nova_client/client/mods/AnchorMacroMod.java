package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.AnchorMacroMenu;
import nova.nova_client.client.ModCategory;

import java.util.Properties;

/**
 * AnchorMacro: Rechtsklick halten mit Respawn Anchor in der Hand -> Kreislauf Anchor setzen, mit Glowstone laden,
 * mit einem anderen Item zuenden (explodiert ausserhalb des Nethers). Reagiert auf das, was man anvisiert:
 * leerer Anchor -> laden, geladener Anchor -> zuenden, sonst Block -> neuen Anchor setzen.
 * Hotbar-Slots werden automatisch gewechselt, beim Loslassen kommt der alte Slot zurueck.
 */
public class AnchorMacroMod extends GameTickMod {
    public static final int MAX_DELAY = 500;

    public record Preset(String name, int place, int charge, int explode, float random) {
    }

    public static final Preset[] PRESETS = {
            new Preset("Blatant", 0, 0, 0, 0.0F),
            new Preset("Fast", 50, 50, 50, 0.10F),
            new Preset("Legit", 120, 90, 110, 0.25F),
            new Preset("Slow", 250, 200, 250, 0.20F)
    };

    // Einstellungen
    /** Wartezeit nach dem Zuenden, bevor ein neuer Anchor gesetzt wird (ms). */
    public static int placeDelay = 120;
    /** Wartezeit nach dem Setzen, bevor geladen wird (ms). */
    public static int chargeDelay = 90;
    /** Wartezeit nach dem Laden, bevor gezuendet wird (ms). */
    public static int explodeDelay = 110;
    /** Zufaellige Abweichung der Wartezeiten (0 - 0.5 = +-50%). */
    public static float randomization = 0.25F;
    /** Beim Loslassen wieder den vorherigen Slot auswaehlen. */
    public static boolean switchBack = true;
    /** Hotbar-Slot (1-9), mit dem der Anchor gezuendet wird. */
    public static int explodeSlot = 1;

    private static boolean enabled;
    private static final RandomSource RANDOM = RandomSource.create();
    /** Makro laeuft (Rechtsklick wurde mit Anchor in der Hand begonnen). */
    private static boolean engaged;
    private static int originalSlot = -1;
    private static long lastAction;
    private static long wait;

    public AnchorMacroMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        AnchorMacroMod.enabled = enabled;
        stop(client);
    }

    /** Soll der normale Rechtsklick unterdrueckt werden? (Makro uebernimmt) */
    public static boolean blocksVanillaUse() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || client.player == null || client.gui.screen() != null) {
            return false;
        }
        ItemStack held = client.player.getMainHandItem();
        return engaged || held.is(Items.RESPAWN_ANCHOR);
    }

    private static void stop(Minecraft client) {
        if (engaged && switchBack && originalSlot >= 0 && client.player != null) {
            client.player.getInventory().setSelectedSlot(originalSlot);
        }
        engaged = false;
        originalSlot = -1;
    }

    private static long jitter(int base) {
        float offset = (RANDOM.nextFloat() * 2.0F - 1.0F) * randomization;
        return Math.max(0L, Math.round(base * (1.0F + offset)));
    }

    private static int findSlot(Inventory inventory, Item item) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inventory.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /** Slot zum Zuenden: der eingestellte Slot, sonst der Ausgangs-Slot, sonst irgendein Slot ohne Anchor/Glowstone. */
    private static int explodeSlot(Inventory inventory) {
        int chosen = explodeSlot - 1;
        if (!isMacroItem(inventory.getItem(chosen))) {
            return chosen;
        }
        if (originalSlot >= 0 && !isMacroItem(inventory.getItem(originalSlot))) {
            return originalSlot;
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (!isMacroItem(inventory.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isMacroItem(ItemStack stack) {
        return stack.is(Items.RESPAWN_ANCHOR) || stack.is(Items.GLOWSTONE);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null || client.gui.screen() != null
                || !client.options.keyUse.isDown()) {
            stop(client);
            return;
        }
        Inventory inventory = player.getInventory();
        if (!engaged) {
            if (!player.getMainHandItem().is(Items.RESPAWN_ANCHOR)) {
                return;
            }
            engaged = true;
            originalSlot = inventory.getSelectedSlot();
            lastAction = 0L;
            wait = 0L;
        }

        long now = System.currentTimeMillis();
        if (now - lastAction < wait || !(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = client.level.getBlockState(pos);

        if (state.is(Blocks.RESPAWN_ANCHOR)) {
            int charge = state.getValue(RespawnAnchorBlock.CHARGE);
            if (charge == 0) {
                // Laden
                int glowstone = findSlot(inventory, Items.GLOWSTONE);
                if (glowstone >= 0 && use(client, glowstone, anchorHit(pos))) {
                    wait = jitter(explodeDelay);
                    // Sofort auf den Zuend-Slot wechseln
                    int slot = explodeSlot(inventory);
                    if (slot >= 0) {
                        inventory.setSelectedSlot(slot);
                    }
                }
            } else {
                // Zuenden
                int slot = explodeSlot(inventory);
                if (slot >= 0 && use(client, slot, anchorHit(pos))) {
                    wait = jitter(placeDelay);
                    // Sofort zurueck zum Glowstone
                    int glowstone = findSlot(inventory, Items.GLOWSTONE);
                    if (glowstone >= 0) {
                        inventory.setSelectedSlot(glowstone);
                    }
                }
            }
        } else {
            // Neuen Anchor setzen
            int anchor = findSlot(inventory, Items.RESPAWN_ANCHOR);
            BlockPos target = pos.relative(hit.getDirection());
            if (anchor >= 0 && client.level.getBlockState(target).canBeReplaced() && use(client, anchor, hit)) {
                wait = jitter(chargeDelay);
            }
        }
    }

    private static BlockHitResult anchorHit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos).add(0.0D, 0.5D, 0.0D), Direction.UP, pos, false);
    }

    /** Slot waehlen und damit auf den Block rechtsklicken. */
    private static boolean use(Minecraft client, int slot, BlockHitResult hit) {
        LocalPlayer player = client.player;
        player.getInventory().setSelectedSlot(slot);
        InteractionResult result = client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        if (result.consumesAction()) {
            Compat.swingUse(player, InteractionHand.MAIN_HAND);
            lastAction = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- Einstellungen

    public static void apply(Preset preset) {
        placeDelay = preset.place();
        chargeDelay = preset.charge();
        explodeDelay = preset.explode();
        randomization = preset.random();
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new AnchorMacroMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "placeDelay", String.valueOf(placeDelay));
        properties.setProperty(prefix + "chargeDelay", String.valueOf(chargeDelay));
        properties.setProperty(prefix + "explodeDelay", String.valueOf(explodeDelay));
        properties.setProperty(prefix + "randomization", String.valueOf(randomization));
        properties.setProperty(prefix + "switchBack", String.valueOf(switchBack));
        properties.setProperty(prefix + "explodeSlot", String.valueOf(explodeSlot));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            placeDelay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "placeDelay", String.valueOf(placeDelay)).trim()), 0, MAX_DELAY);
            chargeDelay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "chargeDelay", String.valueOf(chargeDelay)).trim()), 0, MAX_DELAY);
            explodeDelay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "explodeDelay", String.valueOf(explodeDelay)).trim()), 0, MAX_DELAY);
            randomization = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "randomization", String.valueOf(randomization)).trim()), 0.0F, 0.5F);
            explodeSlot = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "explodeSlot", String.valueOf(explodeSlot)).trim()), 1, 9);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        switchBack = Boolean.parseBoolean(properties.getProperty(prefix + "switchBack", String.valueOf(switchBack)));
    }
}
