package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaCape;

/**
 * Nova Cape: ein eigenes, animiertes Cape (und Elytra-Muster) nur fuer dich sichtbar - fliessende Lichtadern in
 * Mint und Violett wie beim Nova-Tide-Himmel, leuchtender Rand und Nova-Stern. Der eigene Skin bekommt die Textur
 * in SkinProtectPlayerMixin. Das Cape muss in den Skin-Einstellungen eingeschaltet sein.
 */
public class NovaCapeMod extends GameTickMod {
    private static volatile boolean enabled;
    private int ticks;

    public NovaCapeMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        NovaCapeMod.enabled = enabled;
        if (enabled) {
            NovaCape.paint();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Alle 2 Ticks ein neues Bild (10 Bilder pro Sekunde reichen fuer die langsame Bewegung) - aber nur, wenn man das
     * eigene Modell sehen kann (dritte Person, Freecam, Inventar). In der Ego-Sicht, im Chat oder im Pausemenue ist
     * das Cape nicht zu sehen, dort wird gar nicht gemalt.
     */
    @Override
    public void execute(Minecraft client) {
        boolean visible = !client.options.getCameraType().isFirstPerson() || FreecamMod.isEnabled()
                || client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen;
        if (++ticks % 2 == 0 && visible) {
            NovaCape.paint();
        }
    }
}
