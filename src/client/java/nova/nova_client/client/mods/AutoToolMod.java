package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import nova.nova_client.client.AutoToolMenu;
import nova.nova_client.client.ModCategory;

import java.util.Properties;

/**
 * AutoTool: wechselt beim Abbauen automatisch auf das schnellste Werkzeug in der Hotbar
 * (Stein -> Spitzhacke, Erde -> Schaufel, Holz -> Axt ...). Laesst man die Angriffstaste los,
 * wird wieder der vorherige Slot ausgewaehlt. Der Wechsel passiert in MultiPlayerGameModeMixin.
 * Mit einer Verzoegerung > 0 wird erst gewechselt, wenn man so lange am selben Block abbaut.
 */
public class AutoToolMod extends GameTickMod {
    public static final int MIN_DELAY = 0;
    public static final int MAX_DELAY = 2000;
    public static final int DELAY_STEP = 50;

    /** Verzoegerung vor dem Wechsel in ms, 0 = sofort. */
    public static int delay = 0;

    private static boolean enabled;
    /** Slot vor dem ersten automatischen Wechsel, -1 = nichts gewechselt. */
    private static int previousSlot = -1;
    /** Block, der gerade abgebaut wird, und seit wann (fuer die Verzoegerung). */
    private static BlockPos target;
    private static long targetSince;

    public AutoToolMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        if (client.options.keyAttack.isDown()) {
            return;
        }
        target = null;
        // Angriffstaste losgelassen: zurueck zum urspruenglichen Slot
        if (previousSlot != -1 && client.player != null) {
            client.player.getInventory().setSelectedSlot(previousSlot);
            previousSlot = -1;
        }
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        AutoToolMod.enabled = enabled;
        if (!enabled) {
            previousSlot = -1;
            target = null;
        }
    }

    /** Wird vor startDestroyBlock/continueDestroyBlock aufgerufen. */
    public static void onDestroyBlock(BlockPos pos) {
        if (!enabled) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || player.getAbilities().instabuild) {
            return;
        }

        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(client.level, pos) < 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!pos.equals(target)) {
            target = pos.immutable();
            targetSince = now;
        }
        if (delay > 0 && now - targetSince < delay) {
            return;
        }

        Inventory inventory = player.getInventory();
        int current = inventory.getSelectedSlot();
        int best = current;
        float bestScore = score(inventory.getItem(current), state);
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            float s = score(inventory.getItem(slot), state);
            if (s > bestScore) {
                bestScore = s;
                best = slot;
            }
        }

        if (best != current) {
            if (previousSlot == -1) {
                previousSlot = current;
            }
            inventory.setSelectedSlot(best);
        }
    }

    public static void setDelay(int value) {
        int clamped = Math.max(MIN_DELAY, Math.min(MAX_DELAY, value));
        delay = Math.round(clamped / (float) DELAY_STEP) * DELAY_STEP;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new AutoToolMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "delay", String.valueOf(delay));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String value = properties.getProperty(prefix + "delay");
        if (value != null) {
            try {
                setDelay(Integer.parseInt(value.trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }

    /** Abbaugeschwindigkeit des Items, Werkzeuge die fast kaputt sind werden uebersprungen. */
    private static float score(ItemStack stack, BlockState state) {
        if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= 1) {
            return -1.0F;
        }
        float speed = stack.getDestroySpeed(state);
        // Bloecke wie Erz/Obsidian droppen nur mit dem richtigen Werkzeug
        if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) {
            return speed * 0.01F;
        }
        return speed;
    }
}
