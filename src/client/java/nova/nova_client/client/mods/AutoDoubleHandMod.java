package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nova.nova_client.client.ModCategory;

import java.util.Arrays;

/**
 * Auto Double Hand: merkt sich jeden Tick, in welchen Hotbar-Slots ein Totem liegt. Wird so ein Slot leer (Totem
 * geplatzt), kommt sofort ein Totem aus dem Inventar genau in diesen Slot zurueck. Wie bei AutoTotem zaehlt nur
 * "Slot ist leer" - kein Pop-Paket noetig - aber der Slot, in dem das Totem war, wird sich gemerkt.
 */
public class AutoDoubleHandMod extends GameTickMod {
    /** So lange wird ein leerer Slot nachgefuellt, danach aufgeben (ms). */
    private static final long GIVE_UP_MS = 3000L;
    /** Nach einem Tausch so lange auf die Bestaetigung vom Server warten, bevor nachgebessert wird (ms). */
    private static final long CONFIRM_MS = 250L;
    private static final int MAX_ATTEMPTS = 4;

    private final boolean[] hadTotem = new boolean[Inventory.getSelectionSize()];
    /** Seit wann der Slot auf ein neues Totem wartet; 0 = nichts zu tun. */
    private final long[] pendingSince = new long[Inventory.getSelectionSize()];
    private final int[] attempts = new int[Inventory.getSelectionSize()];
    private long lastSwap;
    private boolean primed;

    public AutoDoubleHandMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        Arrays.fill(pendingSince, 0L);
        Arrays.fill(attempts, 0);
        primed = false;
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            primed = false;
            return;
        }
        Inventory inventory = player.getInventory();
        long now = System.currentTimeMillis();

        // Hotbar beobachten: Totem-Slot leer geworden -> diesen Slot merken
        for (int slot = 0; slot < hadTotem.length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            boolean totem = stack.is(Items.TOTEM_OF_UNDYING);
            if (primed && hadTotem[slot] && stack.isEmpty()) {
                pendingSince[slot] = now;
                attempts[slot] = 0;
            } else if (pendingSince[slot] != 0L && (!stack.isEmpty() || now - pendingSince[slot] > GIVE_UP_MS)) {
                // Totem angekommen, anderes Item hingelegt oder zu lange gewartet
                pendingSince[slot] = 0L;
            }
            hadTotem[slot] = totem;
        }
        primed = true;

        // Hoechstens ein Tausch pro Tick; nach einem Tausch kurz auf die Antwort vom Server warten
        if (now - lastSwap < CONFIRM_MS || !player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        for (int slot = 0; slot < pendingSince.length; slot++) {
            if (pendingSince[slot] == 0L) {
                continue;
            }
            if (attempts[slot] >= MAX_ATTEMPTS) {
                pendingSince[slot] = 0L;
                continue;
            }
            int menuSlot = findTotemMenuSlot(player);
            if (menuSlot < 0) {
                // Kein Totem mehr im Inventar
                Arrays.fill(pendingSince, 0L);
                return;
            }
            // Totem aus dem Inventar per Zahlentasten-Tausch genau in den leeren Hotbar-Slot
            client.gameMode.handleContainerInput(player.containerMenu.containerId, menuSlot, slot, ContainerInput.SWAP,
                    player);
            attempts[slot]++;
            lastSwap = now;
            return;
        }
    }

    /** Menue-Slot eines Totems im Hauptinventar (nicht Hotbar, damit kein anderer Hotbar-Slot leer wird), sonst -1. */
    private static int findTotemMenuSlot(LocalPlayer player) {
        for (int menuSlot = 0; menuSlot < player.containerMenu.slots.size(); menuSlot++) {
            Slot slot = player.containerMenu.slots.get(menuSlot);
            int inventorySlot = slot.getContainerSlot();
            if (slot.container == player.getInventory() && inventorySlot >= Inventory.getSelectionSize()
                    && inventorySlot < Inventory.INVENTORY_SIZE && slot.getItem().is(Items.TOTEM_OF_UNDYING)) {
                return menuSlot;
            }
        }
        return -1;
    }
}
