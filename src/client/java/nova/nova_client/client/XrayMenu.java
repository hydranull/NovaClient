package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Xray-Einstellungen: Fake-Erze von Anti-Xray-Servern ausblenden und Empfindlichkeit des Filters.
 */
public class XrayMenu extends ModOptionsMenu {
    /** Hoehlengroesse, mit der zuletzt neu gebaut wurde. */
    private int applied = AntiXray.minCaveSize;

    public XrayMenu(Screen parent) {
        super(parent, "Xray", List.of(
                new Toggle("Hide fake ores (Anti-Xray)", () -> AntiXray.hideFakes, AntiXray::setHideFakes),
                new Slider("Min. cave size", AntiXray.MIN_CAVE_SIZE_MIN, AntiXray.MIN_CAVE_SIZE_MAX, 1.0D,
                        () -> AntiXray.minCaveSize, v -> AntiXray.minCaveSize = (int) Math.round(v),
                        v -> String.valueOf(Math.round(v))),
                new Info(() -> "Revealed blocks: " + AntiXray.confirmedCount())));
    }

    @Override
    protected void changed() {
        // Neue Hoehlengroesse erst nach dem Loslassen anwenden (baut die Chunks neu)
        if (AntiXray.minCaveSize != applied) {
            applied = AntiXray.minCaveSize;
            AntiXray.setHideFakes(AntiXray.hideFakes);
        }
    }
}
