package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.SpotifyMod;

import java.util.List;

/**
 * Einstellungen fuer Spotify: Groesse der HUD-Karte (winzig bis mittel), Lyrics (an/aus, Zeilen, Karaoke) und Knopf
 * zum grossen Player.
 */
public class SpotifyMenu extends ModOptionsMenu {
    private static final String[] LINES = {"1", "2", "3"};

    public SpotifyMenu(Screen parent) {
        super(parent, "Spotify", List.of(
                new Segmented("HUD size", SpotifyMod.SCALE_NAMES, () -> SpotifyMod.scaleIndex, i -> SpotifyMod.scaleIndex = i),
                new Section("Lyrics"),
                new Toggle("Synced lyrics under the song", () -> SpotifyMod.lyrics, v -> SpotifyMod.lyrics = v),
                new Segmented("Lines", LINES, () -> SpotifyMod.lyricLines - 1, i -> SpotifyMod.lyricLines = i + 1),
                new Toggle("Karaoke", () -> SpotifyMod.karaoke, v -> SpotifyMod.karaoke = v),
                new Section("Player"),
                new Actions(List.of(
                        open("Open full player", SpotifyScreen::new),
                        open("Move on screen", HudEditorScreen::new))),
                new Info(() -> "In game: O opens the player, F7 / F8 / F9 = back / play / next")));
    }
}
