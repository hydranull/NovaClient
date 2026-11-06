package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import nova.nova_client.client.KeyPearlMenu;
import nova.nova_client.client.ModCategory;

import java.util.Properties;

/**
 * Key Pearl: Auf Tastendruck eine Enderperle werfen - in der Hotbar sichtbar auf die Perle wechseln, im naechsten
 * Tick werfen und danach zurueck auf den vorherigen Slot. Ohne Perle in der Hotbar passiert nichts. Die Taste stellt man im Menue ein.
 */
public class KeyPearlMod extends GameTickMod {
    // Einstellungen
    /** Taste zum Werfen (GLFW-Code), NO_KEY = noch keine gesetzt. */
    public static int throwKey = NO_KEY;

    private static final int IDLE = 0;
    private static final int THROW = 1;
    private static final int SWITCH_BACK = 2;

    private static boolean wasDown;
    private static int step = IDLE;
    private static int previousSlot = -1;

    public KeyPearlMod(String name, ModCategory category) {
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

    public static String throwKeyName() {
        return throwKey == NO_KEY ? "None" : InputConstants.Type.KEYBOARD.getOrCreate(throwKey).getDisplayName().getString();
    }

    @Override
    public void execute(Minecraft client) {
        boolean down = throwKey != NO_KEY && InputConstants.isKeyDown(throwKey);
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
                    // 1. Tick: in der Hotbar sichtbar auf die Perle wechseln
                    int pearl = findPearl(inventory);
                    if (pearl >= 0 && !player.getCooldowns().isOnCooldown(inventory.getItem(pearl))) {
                        previousSlot = inventory.getSelectedSlot();
                        inventory.setSelectedSlot(pearl);
                        step = THROW;
                    }
                }
            }
            case THROW -> {
                // 2. Tick: werfen (nur wenn wirklich noch eine Perle in der Hand ist)
                if (player.getMainHandItem().is(Items.ENDER_PEARL)) {
                    InteractionResult result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                    if (result.consumesAction()) {
                        Compat.swingUse(player, InteractionHand.MAIN_HAND);
                    }
                }
                step = SWITCH_BACK;
            }
            default -> {
                // 3. Tick: zurueck auf den vorherigen Slot
                if (previousSlot >= 0) {
                    inventory.setSelectedSlot(previousSlot);
                }
                previousSlot = -1;
                step = IDLE;
            }
        }
    }

    private static int findPearl(Inventory inventory) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inventory.getItem(i).is(Items.ENDER_PEARL)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new KeyPearlMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "throwKey", String.valueOf(throwKey));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            throwKey = Integer.parseInt(properties.getProperty(prefix + "throwKey", String.valueOf(throwKey)).trim());
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
