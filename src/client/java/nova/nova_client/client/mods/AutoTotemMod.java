package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import nova.nova_client.client.AutoTotemMenu;
import nova.nova_client.client.ModCategory;

import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AutoTotem (frueher "TotemPop"): legt nach einem Totem-Pop mit einstellbarer, leicht zufaelliger Verzoegerung
 * ein neues Totem in die Zweithand. Den Pop meldet ClientPacketListenerMixin.
 */
public class AutoTotemMod extends GameTickMod {
    public static final int MAX_DELAY = 1000;

    // Einstellungen
    /** Wartezeit nach dem Pop, bevor das neue Totem in die Zweithand kommt (ms). */
    public static int delay = 425;
    /** Zufaellige Abweichung der Wartezeit (0 - 0.5 = +-50%). */
    public static float randomization = 0.4F;

    /** So lange wird nach einem Pop auf das "Totem verbraucht" vom Server gewartet (ms). */
    private static final long GIVE_UP_MS = 3000L;
    /** Nach einem Tausch so lange auf die Bestaetigung vom Server warten, bevor nachgebessert wird (ms). */
    private static final long CONFIRM_MS = 250L;
    private static final int MAX_ATTEMPTS = 4;

    // volatile: Entity-Event-Pakete koennen auch auf dem Netzwerk-Thread ankommen
    private static volatile boolean enabled;
    /** Zeitpunkt, ab dem das neue Totem in die Zweithand soll; 0 = nichts zu tun. */
    private static volatile long swapAt;
    private static volatile int attempts;
    private static long lastSwap;

    public AutoTotemMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        AutoTotemMod.enabled = enabled;
        swapAt = 0L;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Aus ClientPacketListenerMixin: das eigene Totem ist geplatzt. */
    public static void onTotemPop() {
        swapAt = System.currentTimeMillis() + nextDelay();
        attempts = 0;
    }

    /**
     * Jeden Tick. Ein Tausch startet nach einem Pop (nach der eingestellten Wartezeit) oder sofort, sobald die
     * Zweithand aus irgendeinem anderen Grund leer ist. Danach wird geprueft, ob wirklich ein Totem angekommen ist: im Fight
     * aendert sich das Inventar staendig, und ist der Client kurz nicht auf dem Stand des Servers, landet beim Tausch
     * ein falsches Item (z.B. die Spitzhacke) in der Zweithand - dann wird mit einem Totem nachgebessert.
     */
    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            swapAt = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        boolean offhandTotem = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);

        // Zweithand leer (Tausch fehlgeschlagen, Totem weggelegt, ...) -> sofort nachlegen, ohne Wartezeit
        if (swapAt == 0L) {
            if (!player.getOffhandItem().isEmpty() || findTotem(player) < 0) {
                return;
            }
            swapAt = now;
            attempts = 0;
        }
        if (now < swapAt) {
            return;
        }
        if (offhandTotem) {
            // Entweder hat der Tausch geklappt, oder das alte Totem ist noch da (Update vom Server kommt gleich)
            if (attempts > 0 || now - swapAt > GIVE_UP_MS) {
                swapAt = 0L;
            }
            return;
        }
        // Nach einem Tausch kurz auf die Antwort vom Server warten, bevor nachgebessert wird
        if (attempts > 0 && now - lastSwap < CONFIRM_MS) {
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            swapAt = 0L;
            return;
        }
        // Mauszeiger haelt gerade ein Item (z.B. im Inventar) -> Tausch wuerde nicht gehen, naechsten Tick nochmal
        if (!player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        int inventorySlot = findTotem(player);
        int menuSlot = inventorySlot < 0 ? -1 : findMenuSlot(player, inventorySlot);
        if (menuSlot < 0) {
            // Kein Totem mehr (oder offenes Menue ohne Spielerinventar)
            swapAt = 0L;
            return;
        }
        client.gameMode.handleContainerInput(player.containerMenu.containerId, menuSlot, 40, ContainerInput.SWAP, player);
        attempts++;
        lastSwap = now;
    }

    /** Erster Inventar-Platz (Hotbar + Hauptinventar) mit einem Totem, sonst -1. */
    private static int findTotem(LocalPlayer player) {
        for (int inventorySlot = 0; inventorySlot < 36; inventorySlot++) {
            if (player.getInventory().getItem(inventorySlot).is(Items.TOTEM_OF_UNDYING)) {
                return inventorySlot;
            }
        }
        return -1;
    }

    private static int findMenuSlot(LocalPlayer player, int inventorySlot) {
        for (int menuSlot = 0; menuSlot < player.containerMenu.slots.size(); menuSlot++) {
            Slot slot = player.containerMenu.slots.get(menuSlot);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                return menuSlot;
            }
        }
        return -1;
    }

    /** Wartezeit fuer den naechsten Tausch (ms), mit Zufall. */
    public static long nextDelay() {
        float offset = (ThreadLocalRandom.current().nextFloat() * 2.0F - 1.0F) * randomization;
        return Math.max(0L, Math.round(delay * (1.0F + offset)));
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new AutoTotemMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "delay", String.valueOf(delay));
        properties.setProperty(prefix + "randomization", String.valueOf(randomization));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            delay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "delay", String.valueOf(delay)).trim()), 0, MAX_DELAY);
            randomization = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "randomization", String.valueOf(randomization)).trim()), 0.0F, 0.5F);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
