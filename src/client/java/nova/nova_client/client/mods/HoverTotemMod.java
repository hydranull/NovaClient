package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import nova.nova_client.client.HoverTotemMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.mixin.client.ContainerScreenAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * HoverTotem: faehrt der Mauszeiger in einem Inventar ueber ein Totem, wird es sofort in einen zufaelligen
 * Hotbar-Slot getauscht - ohne Klick. Freie Slots werden bevorzugt, Slots mit Totem uebersprungen.
 * Getauscht wird wie mit einer Zahlentaste (SWAP).
 */
public class HoverTotemMod extends GameTickMod {
    public static final int MAX_DELAY = 500;
    /** Hauptinventar = Slots 9-35 im Spieler-Inventar. */
    private static final int MAIN_END = 36;

    // Einstellungen
    /** So lange muss die Maus auf dem Totem sein (ms), 0 = sofort. */
    public static int delay = 0;

    private static final RandomSource RANDOM = RandomSource.create();
    private static Slot hovered;
    private static long hoveredSince;

    public HoverTotemMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || player.isSpectator()
                || !(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            hovered = null;
            return;
        }

        Slot slot = ((ContainerScreenAccessor) screen).nova_client$hoveredSlot();
        long now = System.currentTimeMillis();
        if (slot != hovered) {
            hovered = slot;
            hoveredSince = now;
        }
        if (slot == null || !slot.getItem().is(Items.TOTEM_OF_UNDYING) || now - hoveredSince < delay) {
            return;
        }
        // Nur Totems aus dem Hauptinventar (nicht Hotbar, Ruestung, Zweithand), nichts mit der Maus in der Hand
        int index = slot.getContainerSlot();
        if (slot.container != player.getInventory() || index < Inventory.getSelectionSize() || index >= MAIN_END
                || !screen.getMenu().getCarried().isEmpty()) {
            return;
        }
        int hotbarSlot = randomHotbarSlot(player.getInventory());
        if (hotbarSlot < 0) {
            return;
        }
        if (screen instanceof CreativeModeInventoryScreen) {
            // Creative-Inventar-Tab: Slots sind nur Huellen -> direkt ueber das Spieler-Inventarmenue tauschen
            // (dort entsprechen die Menue-Slots 9-35 den Inventar-Slots 9-35)
            client.gameMode.handleContainerInput(player.inventoryMenu.containerId, index, hotbarSlot, ContainerInput.SWAP, player);
        } else {
            client.gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, hotbarSlot, ContainerInput.SWAP, player);
        }
        // Nicht sofort wieder dasselbe Feld nehmen (jetzt liegt dort das alte Item)
        hoveredSince = Long.MAX_VALUE / 2;
    }

    /** Zufaelliger Hotbar-Slot ohne Totem, freie Slots zuerst; -1 wenn ueberall schon ein Totem liegt. */
    private static int randomHotbarSlot(Inventory inventory) {
        List<Integer> empty = new ArrayList<>();
        List<Integer> other = new ArrayList<>();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                empty.add(i);
            } else if (!inventory.getItem(i).is(Items.TOTEM_OF_UNDYING)) {
                other.add(i);
            }
        }
        List<Integer> choices = empty.isEmpty() ? other : empty;
        return choices.isEmpty() ? -1 : choices.get(RANDOM.nextInt(choices.size()));
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new HoverTotemMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "delay", String.valueOf(delay));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            delay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "delay", String.valueOf(delay)).trim()), 0, MAX_DELAY);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
