package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.Vivid;

/**
 * Vivid: kraeftigere, lebendigere Farben und ein klareres Bild der Spielwelt - ohne Einstellungen, nur an oder aus.
 * Gezeichnet wird in {@link Vivid}.
 */
public class VividMod extends GameTickMod {
    // volatile Flag statt Suche in der Mod-Liste: wird jedes Bild vom GameRenderer-Mixin abgefragt
    private static volatile boolean enabled;

    public VividMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        VividMod.enabled = enabled;
        if (enabled) {
            Vivid.retry();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
