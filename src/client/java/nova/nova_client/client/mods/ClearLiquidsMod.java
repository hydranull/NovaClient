package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.material.FogType;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Properties;

/**
 * Clear Liquids: in Wasser und Lava klare Sicht wie an der Luft - kein Nebel, keine Faerbung, kein Zoom, kein
 * Wasser-Schleier und (in Lava) kein Feuer vor den Augen. Die Kamera gilt fuer die Darstellung einfach als "nicht in
 * einer Fluessigkeit" (CameraMixin), die Overlays blendet ScreenEffectRendererMixin aus. Nur clientseitig.
 */
public class ClearLiquidsMod extends GameTickMod {
    public static boolean water = true;
    public static boolean lava = true;

    // volatile Flag: wird jeden Frame von Kamera und Bildschirm-Effekten abgefragt
    private static volatile boolean enabled;

    public ClearLiquidsMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        ClearLiquidsMod.enabled = enabled;
    }

    /** Soll die Kamera in dieser Fluessigkeit klar sehen? */
    public static boolean clears(FogType type) {
        return enabled && (type == FogType.WATER && water || type == FogType.LAVA && lava);
    }

    /** Wasser-Schleier (textures/misc/underwater.png) ausblenden? */
    public static boolean hideWaterOverlay() {
        return enabled && water;
    }

    /** Feuer vor den Augen ausblenden? Nur solange man in Lava steckt (sonst brennt man ja wirklich sichtbar). */
    public static boolean hideFireOverlay() {
        Minecraft client = Minecraft.getInstance();
        return enabled && lava && client.player != null && client.player.isInLava();
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Toggle("Clear water", () -> water, v -> water = v),
                new ModOptionsMenu.Toggle("Clear lava (also hides the fire on screen)", () -> lava, v -> lava = v)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "water", String.valueOf(water));
        properties.setProperty(prefix + "lava", String.valueOf(lava));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        water = Boolean.parseBoolean(properties.getProperty(prefix + "water", String.valueOf(water)));
        lava = Boolean.parseBoolean(properties.getProperty(prefix + "lava", String.valueOf(lava)));
    }
}
