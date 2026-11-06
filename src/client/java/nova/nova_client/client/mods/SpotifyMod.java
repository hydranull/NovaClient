package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.Rect;
import nova.nova_client.client.Spotify;
import nova.nova_client.client.SpotifyMenu;
import nova.nova_client.client.SpotifyWidget;

import java.util.Properties;

/**
 * Spotify: "Now Playing"-Karte oben rechts im HUD, Mini-Player in den Nova-Menues und ein grosser Player
 * (Taste "Open Spotify Player", Standard O, oder Rechtsklick auf den Mod).
 * Im Spiel: F7 = zurueck, F8 = Play/Pause, F9 = weiter.
 */
public class SpotifyMod extends RenderMod {
    private static final int[] KEYS = {com.mojang.blaze3d.platform.InputConstants.KEY_F7, com.mojang.blaze3d.platform.InputConstants.KEY_F8, com.mojang.blaze3d.platform.InputConstants.KEY_F9};
    private static final boolean[] down = new boolean[KEYS.length];

    /** HUD-Groessen von winzig bis mittel. */
    public static final float[] SCALES = {0.45F, 0.6F, 0.75F, 0.9F, 1.0F};
    public static final String[] SCALE_NAMES = {"Tiny", "Small", "Compact", "Normal", "Medium"};
    public static int scaleIndex = 3;
    /** Aktuelle Lyrics-Zeile unter dem Song (synchronisierte Lyrics von lrclib.net). */
    public static boolean lyrics = true;
    /** So viele Lyrics-Zeilen (aktuelle + naechste). */
    public static int lyricLines = 2;
    /** Aktuelle Zeile fuellt sich beim Singen von links hell (wie Karaoke). */
    public static boolean karaoke = true;
    /** Karte auch zeigen, wenn nichts laeuft ("Open the Spotify app"). */
    public static boolean showWhenIdle = false;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = Standard oben rechts. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    /** Zuletzt gezeichneter Bereich (fuer den Verschiebe-Modus). */
    public static Rect bounds;

    // volatile Flag statt Suche in der Mod-Liste: wird vom Spotify-Thread abgefragt
    private static volatile boolean enabled;

    public SpotifyMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        SpotifyMod.enabled = enabled;
        if (!enabled) {
            bounds = null;
        }
    }

    @Override
    public void execute(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        SpotifyWidget.drawHud(graphics);
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new SpotifyMenu(parent);
    }

    /** Groesse der HUD-Karte (Faktor). */
    public static float scale() {
        return SCALES[Math.max(0, Math.min(SCALES.length - 1, scaleIndex))];
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "scale", String.valueOf(scaleIndex));
        properties.setProperty(prefix + "x", String.valueOf(posX));
        properties.setProperty(prefix + "y", String.valueOf(posY));
        properties.setProperty(prefix + "lyrics", String.valueOf(lyrics));
        properties.setProperty(prefix + "lyricLines", String.valueOf(lyricLines));
        properties.setProperty(prefix + "karaoke", String.valueOf(karaoke));
        properties.setProperty(prefix + "showWhenIdle", String.valueOf(showWhenIdle));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            scaleIndex = Math.max(0, Math.min(SCALES.length - 1,
                    Integer.parseInt(properties.getProperty(prefix + "scale", String.valueOf(scaleIndex)))));
            posX = Float.parseFloat(properties.getProperty(prefix + "x", String.valueOf(posX)));
            posY = Float.parseFloat(properties.getProperty(prefix + "y", String.valueOf(posY)));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        lyrics = Boolean.parseBoolean(properties.getProperty(prefix + "lyrics", String.valueOf(lyrics)));
        karaoke = Boolean.parseBoolean(properties.getProperty(prefix + "karaoke", String.valueOf(karaoke)));
        showWhenIdle = Boolean.parseBoolean(properties.getProperty(prefix + "showWhenIdle", String.valueOf(showWhenIdle)));
        try {
            lyricLines = Math.max(1, Math.min(3, Integer.parseInt(properties.getProperty(prefix + "lyricLines", String.valueOf(lyricLines)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }

    /** Medientasten im Spiel (nur Flanke, nicht in Menues oder im Chat). */
    public static void handleKeys(Minecraft client) {
        boolean inGame = client.gui.screen() == null;
        for (int i = 0; i < KEYS.length; i++) {
            boolean pressed = enabled && InputConstants.isKeyDown(KEYS[i]);
            if (pressed && !down[i] && inGame) {
                switch (i) {
                    case 0 -> Spotify.previous();
                    case 1 -> Spotify.playPause();
                    default -> Spotify.next();
                }
            }
            down[i] = pressed;
        }
    }
}
