package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.AuctionSniperMod;

import java.util.List;

/**
 * Einstellungen fuer den Auction-Sniper: Item, Hoechstpreis, Suchintervall, "nach Kauf stoppen" und Bestaetigung.
 */
public class AuctionSniperMenu extends ModOptionsMenu {
    public AuctionSniperMenu(Screen parent) {
        super(parent, "Auction Sniper", List.of(
                new Section("Search"),
                new Text("Item", () -> AuctionSniperMod.item, v -> AuctionSniperMod.item = v.trim(), 64),
                new Text("Max price", () -> AuctionSniperMod.maxPrice > 0 ? AuctionSniperMod.formatPrice(AuctionSniperMod.maxPrice) : "",
                        v -> AuctionSniperMod.maxPrice = Math.max(0, AuctionSniperMod.parsePrice(v)), 24),
                new Info(() -> AuctionSniperMod.maxPrice > 0 ? "Buys below $" + AuctionSniperMod.formatPrice(AuctionSniperMod.maxPrice)
                        : "Enter a price like 1.5k or 2m"),
                new Segmented("Refresh", intervals(), AuctionSniperMenu::interval,
                        i -> AuctionSniperMod.interval = AuctionSniperMod.INTERVAL_PRESETS[i]),
                new Section("Buying"),
                new Toggle("Stop after first buy", () -> AuctionSniperMod.stopAfterBuy, v -> AuctionSniperMod.stopAfterBuy = v),
                new Toggle("Confirm purchases automatically", () -> AuctionSniperMod.autoConfirm, v -> AuctionSniperMod.autoConfirm = v),
                new Info(() -> "Status: " + AuctionSniperMod.status)));
    }

    private static String[] intervals() {
        String[] labels = new String[AuctionSniperMod.INTERVAL_PRESETS.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = AuctionSniperMod.INTERVAL_PRESETS[i] / 20 + "s";
        }
        return labels;
    }

    private static int interval() {
        for (int i = 0; i < AuctionSniperMod.INTERVAL_PRESETS.length; i++) {
            if (AuctionSniperMod.interval == AuctionSniperMod.INTERVAL_PRESETS[i]) {
                return i;
            }
        }
        return -1;
    }
}
