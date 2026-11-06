package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.social.SocialClient;
import nova.nova_client.client.social.SocialScreen;
import nova.nova_client.client.social.SocialSettings;

import java.util.Properties;

/**
 * Nova Social: Freunde mit Online-Status und Server, Nachrichten und Party mit Pings - fuer alle mit Nova. An = mit dem
 * Nova-Server verbunden (Anmeldung ueber das Minecraft-Konto), aus = getrennt. Das Fenster ist SocialScreen.
 */
public class NovaSocialMod extends GameTickMod {
    public NovaSocialMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        // die Verbindung laeuft im Hintergrund (SocialClient), auch ohne Welt
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (enabled) {
            SocialClient.start();
        } else {
            SocialClient.stop();
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new SocialScreen(parent, this);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "showServer", String.valueOf(SocialSettings.showServer));
        properties.setProperty(prefix + "notifyOnline", String.valueOf(SocialSettings.notifyOnline));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        SocialSettings.showServer = Boolean.parseBoolean(properties.getProperty(prefix + "showServer",
                String.valueOf(SocialSettings.showServer)));
        SocialSettings.notifyOnline = Boolean.parseBoolean(properties.getProperty(prefix + "notifyOnline",
                String.valueOf(SocialSettings.notifyOnline)));
    }
}
