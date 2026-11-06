package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.PotionHudMod;

import java.util.List;

/**
 * Einstellungen fuer PotionHUD: Groesse, Anzeige-Optionen und Position (HUD-Editor). Das Aussehen sind feste,
 * schlichte Karten (HudCards).
 */
public class PotionHudMenu extends ModOptionsMenu {
    public PotionHudMenu(Screen parent) {
        super(parent, "PotionHUD", List.of(
                new Segmented("Size", percent(PotionHudMod.SCALES), () -> PotionHudMod.scaleIndex, i -> PotionHudMod.scaleIndex = i),
                new Toggle("Show level (II, III, ...)", () -> PotionHudMod.showLevel, v -> PotionHudMod.showLevel = v),
                new Toggle("Names in potion color", () -> PotionHudMod.effectColors, v -> PotionHudMod.effectColors = v),
                new Toggle("Sort by time left", () -> PotionHudMod.sortByTime, v -> PotionHudMod.sortByTime = v),
                new Actions(List.of(open("Move on screen", HudEditorScreen::new)))));
    }
}
