package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.UUID;

/**
 * Kleine Helfer fuer Module, die Funde melden: Chat-Zeile mit farbigem Praefix, Ton, Himmelsrichtung und Entfernung,
 * Spielername zu einer UUID und eine Kennung fuer den aktuellen Server (fuer gespeicherte Daten pro Server).
 */
public final class NovaNotify {
    private static final String[] DIRECTIONS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private NovaNotify() {
    }

    /** Chat-Zeile nur bei dir: "[Name] Text" mit Farbcodes (§). */
    public static void chat(String prefix, String text) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.gui.chatListener().handleSystemMessage(Component.literal("§d[" + prefix + "]§r " + text), false);
        }
    }

    public static void sound(SoundEvent sound, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch));
    }

    public static void sound(Holder<SoundEvent> sound, float pitch) {
        sound(sound.value(), pitch);
    }

    /** Himmelsrichtung von from nach to (N = -Z wie in Minecraft). */
    public static String direction(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        int index = Math.floorMod((int) Math.round(angle / 45.0D), 8);
        return DIRECTIONS[index];
    }

    /** "84m NE" (oder "1.2km NE"). */
    public static String where(Vec3 from, Vec3 to) {
        double distance = Math.sqrt(Math.pow(to.x - from.x, 2) + Math.pow(to.z - from.z, 2));
        String d = distance >= 1000.0D ? String.format(Locale.ROOT, "%.1fkm", distance / 1000.0D) : Math.round(distance) + "m";
        return d + " " + direction(from, to);
    }

    /** "Name" des Spielers aus der Tab-Liste, sonst eine gekuerzte UUID. */
    public static String playerName(UUID uuid) {
        Minecraft client = Minecraft.getInstance();
        if (uuid == null) {
            return "?";
        }
        if (client.getConnection() != null) {
            var info = client.getConnection().getPlayerInfo(uuid);
            if (info != null) {
                return info.getProfile().name();
            }
        }
        if (client.player != null && client.player.getUUID().equals(uuid)) {
            return client.player.getPlainTextName();
        }
        return uuid.toString().substring(0, 8) + "… (offline)";
    }

    /** Kennung des aktuellen Servers bzw. der Einzelspieler-Welt (fuer Dateien mit Daten pro Server). */
    public static String serverId() {
        Minecraft client = Minecraft.getInstance();
        if (client.getCurrentServer() != null) {
            return client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        }
        if (client.getSingleplayerServer() != null) {
            return "sp:" + client.getSingleplayerServer().getWorldData().getLevelName();
        }
        return "unknown";
    }

    /**
     * Textgroesse fuer Schilder in der Welt: Gizmo-Text hat eine feste Groesse in Bloecken und waere aus der Ferne
     * winzig - ab 10 Bloecken waechst er deshalb mit der Entfernung mit (bleibt am Bildschirm etwa gleich gross).
     */
    public static float textScale(Vec3 pos, float base) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        double distance = Math.sqrt(pos.distanceToSqr(camera));
        return base * (float) Math.max(1.0D, Math.min(30.0D, distance / 10.0D));
    }
}
