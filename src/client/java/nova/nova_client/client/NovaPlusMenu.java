package nova.nova_client.client;

import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.NovaPlusMod;

import java.util.List;

/** Einstellungen fuer Nova+: Wortmarke oben links, Info-Leiste oben in der Mitte und was darin steht. */
public class NovaPlusMenu extends ModOptionsMenu {
    public NovaPlusMenu(Screen parent) {
        super(parent, "Nova+", List.of(
                new Toggle("Watermark (top left)", () -> NovaPlusMod.watermark, v -> NovaPlusMod.watermark = v),
                new Section("Info bar"),
                new Toggle("Info bar (top middle)", () -> NovaPlusMod.bar, v -> NovaPlusMod.bar = v),
                new Toggle("Nova logo", () -> NovaPlusMod.showLogo, v -> NovaPlusMod.showLogo = v),
                new Toggle("Time", () -> NovaPlusMod.showTime, v -> NovaPlusMod.showTime = v),
                new Toggle("FPS", () -> NovaPlusMod.showFps, v -> NovaPlusMod.showFps = v),
                new Toggle("Ping", () -> NovaPlusMod.showPing, v -> NovaPlusMod.showPing = v),
                new Info(() -> "Boss bars and the player list move down below the bar")));
    }
}
