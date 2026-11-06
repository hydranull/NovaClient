package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import nova.nova_client.client.LungeMacroMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.mixin.client.MinecraftAccessor;

import java.util.Properties;

/**
 * LungeMacro: Auf Tastendruck zum Speer mit Lunge in der Hotbar wechseln, zustossen (Lunge schleudert einen nach
 * vorne) und danach zurueck auf den vorherigen Slot. Nach dem Wechsel wird gewartet, bis der Speer genug
 * aufgeladen ist - sonst laesst Minecraft den Stoss nicht zu. Die Taste stellt man im Menue ein.
 */
public class LungeMacroMod extends GameTickMod {
    // Einstellungen
    /** Taste fuer den Lunge (GLFW-Code), NO_KEY = noch keine gesetzt. */
    public static int lungeKey = NO_KEY;

    private static final int IDLE = 0;
    private static final int LUNGE = 1;
    private static final int SWITCH_BACK = 2;
    /** So lange (Ticks) auf genug Aufladung warten, danach abbrechen. */
    private static final int MAX_WAIT = 40;

    private static boolean wasDown;
    private static int step = IDLE;
    private static int previousSlot = -1;
    private static int waited;

    public LungeMacroMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        wasDown = false;
        if (step != IDLE && previousSlot >= 0 && client.player != null) {
            client.player.getInventory().setSelectedSlot(previousSlot);
        }
        step = IDLE;
        previousSlot = -1;
    }

    public static String lungeKeyName() {
        return lungeKey == NO_KEY ? "None" : InputConstants.Type.KEYBOARD.getOrCreate(lungeKey).getDisplayName().getString();
    }

    @Override
    public void execute(Minecraft client) {
        boolean down = lungeKey != NO_KEY && InputConstants.isKeyDown(lungeKey);
        boolean pressed = down && !wasDown;
        wasDown = down;

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            step = IDLE;
            return;
        }
        Inventory inventory = player.getInventory();
        switch (step) {
            case IDLE -> {
                if (pressed && client.gui.screen() == null) {
                    // 1. Tick: in der Hotbar sichtbar auf den Speer wechseln
                    int spear = findLungeSpear(inventory);
                    if (spear >= 0) {
                        previousSlot = inventory.getSelectedSlot();
                        inventory.setSelectedSlot(spear);
                        waited = 0;
                        step = LUNGE;
                    }
                }
            }
            case LUNGE -> {
                ItemStack stack = player.getMainHandItem();
                if (!isLungeSpear(stack)) {
                    step = SWITCH_BACK;
                    return;
                }
                // Warten, bis der Speer nach dem Wechsel genug aufgeladen ist
                float needed = stack.getOrDefault(DataComponents.MINIMUM_ATTACK_CHARGE, 0.0F);
                if (player.getAttackStrengthScale(0.0F) < needed && ++waited < MAX_WAIT) {
                    return;
                }
                // Stoss wie ein normaler Linksklick (Paket, Lunge, Schwung-Animation)
                ((MinecraftAccessor) client).nova_client$startAttack();
                step = SWITCH_BACK;
            }
            default -> {
                // Danach zurueck auf den vorherigen Slot
                if (previousSlot >= 0) {
                    inventory.setSelectedSlot(previousSlot);
                }
                previousSlot = -1;
                step = IDLE;
            }
        }
    }

    private static int findLungeSpear(Inventory inventory) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (isLungeSpear(inventory.getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isLungeSpear(ItemStack stack) {
        if (!stack.is(ItemTags.SPEARS)) {
            return false;
        }
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            if (enchantment.is(Enchantments.LUNGE)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new LungeMacroMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "lungeKey", String.valueOf(lungeKey));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            lungeKey = Integer.parseInt(properties.getProperty(prefix + "lungeKey", String.valueOf(lungeKey)).trim());
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
