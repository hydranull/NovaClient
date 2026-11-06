package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModUpdateType;
import nova.nova_client.client.Rect;

public abstract class Mod {
    public static final int NO_KEY = InputConstants.UNKNOWN.getValue();

    public boolean active;
    public String name;
    public ModUpdateType updateType;
    public Rect textRect;
    public ModCategory category;
    /** Tastencode (InputConstants) zum Umschalten, NO_KEY = keine Taste. */
    public int keyCode = NO_KEY;

    public Mod(String name, ModUpdateType updateType, ModCategory category) {
        this.active = false;
        this.name = name;
        this.updateType = updateType;
        this.category = category;
    }

    public void onStateChanged(Minecraft client, boolean enabled) {
    }

    public void toggle(Minecraft client) {
        active = !active;
        onStateChanged(client, active);
        nova.nova_client.client.ModStates.changed();
    }

    /** Einstellungs-Menue dieses Mods, oder null wenn es keins gibt. */
    public Screen createSettings(Screen parent) {
        return null;
    }

    public boolean hasSettings() {
        return false;
    }

    /** Eigene Einstellungen speichern (Config und Profile). Schluessel mit prefix beginnen. */
    public void saveSettings(java.util.Properties properties, String prefix) {
    }

    /** Eigene Einstellungen laden. Fehlende Werte sollen den Standard behalten. */
    public void loadSettings(java.util.Properties properties, String prefix) {
    }

    public boolean hasKey() {
        return keyCode != NO_KEY;
    }

    public String keyName() {
        return hasKey() ? InputConstants.Type.KEYBOARD.getOrCreate(keyCode).getDisplayName().getString() : "";
    }
}
