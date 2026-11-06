package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.ShieldBreakerMod;

import java.util.List;
import java.util.Locale;

/**
 * Einstellungen fuer Shield Breaker: wie lange die Axt nach dem Schlag in der Hand bleibt (sofort - 1,5 Sekunden).
 */
public class ShieldBreakerMenu extends ModOptionsMenu {
    public ShieldBreakerMenu(Screen parent) {
        super(parent, "Shield Breaker", List.of(
                new Slider("Switch back after", 0.0D, ShieldBreakerMod.MAX_DELAY, 0.05D, () -> ShieldBreakerMod.delay,
                        v -> ShieldBreakerMod.setDelay((float) v),
                        v -> v <= 0.0D ? "Instant" : String.format(Locale.ROOT, "%.2fs", v)),
                new Info(() -> "How long the axe stays in your hand after the hit")));
    }
}
