package nova.nova_client.client.social;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Taste fuer Party-Pings (in den Minecraft-Steuerungen unter Nova Client einstellbar, Standard Z). */
public final class SocialKeys {
    private static KeyMapping ping;

    private SocialKeys() {
    }

    /** Beim Start aus Nova_clientClient (nach dem Registrieren). */
    public static void init(KeyMapping pingKey) {
        ping = pingKey;
    }

    /** Jeden Tick: Ping-Taste gedrueckt? (nur im Spiel, nicht in Menues) */
    public static void tick(Minecraft mc) {
        if (ping == null) {
            return;
        }
        while (ping.consumeClick()) {
            if (mc.gui.screen() == null) {
                SocialClient.ping(mc);
            }
        }
    }

    public static String pingKeyName() {
        return ping == null ? "Z" : ping.getTranslatedKeyMessage().getString();
    }
}
