package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Items;
import nova.nova_client.client.ModCategory;

/**
 * Legt ein Totem in die Zweithand, aber nur solange das eigene Inventar offen ist (wirkt wie von Hand).
 */
public class AutoInvTotemMod extends GameTickMod {
    /** Ticks nach dem Oeffnen des Inventars, bevor getauscht wird (~100 ms). */
    private static final int OPEN_DELAY = 2;
    /** Mindestabstand zwischen zwei Tauschvorgaengen. */
    private static final int SWAP_COOLDOWN = 3;
    private static final int MAIN_INVENTORY_SIZE = 36;
    private static final int HOTBAR_SIZE = 9;
    /** Im Spieler-Inventarmenue beginnt die Hotbar bei Slot 36. */
    private static final int HOTBAR_MENU_OFFSET = 36;

    private int openTicks;
    private int cooldown;

    public AutoInvTotemMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        if (!(client.gui.screen() instanceof InventoryScreen)) {
            openTicks = 0;
            return;
        }

        openTicks++;
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (openTicks > OPEN_DELAY && equipTotem(client)) {
            cooldown = SWAP_COOLDOWN;
        }
    }

    /** Tauscht ein Totem aus dem Inventar in die Zweithand. @return true, wenn getauscht wurde */
    private static boolean equipTotem(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || player.isSpectator()) {
            return false;
        }
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) {
            return false;
        }
        if (player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) {
            return false;
        }

        Inventory inventory = player.getInventory();
        for (int i = 0; i < MAIN_INVENTORY_SIZE; i++) {
            if (inventory.getItem(i).getItem() == Items.TOTEM_OF_UNDYING) {
                int menuSlot = i < HOTBAR_SIZE ? HOTBAR_MENU_OFFSET + i : i;
                client.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot,
                        Inventory.SLOT_OFFHAND, ContainerInput.SWAP, player);
                return true;
            }
        }
        return false;
    }
}
