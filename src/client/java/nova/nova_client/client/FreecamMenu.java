package nova.nova_client.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.FreecamMod;

import java.util.List;

/**
 * Einstellungen fuer Freecam: Tempo (normal, mit Sprint, hoch/runter), Mausrad, was vom eigenen Spieler zu sehen ist
 * und ob er weiterlaeuft. Abbauen/Bauen ist wie normal (dort, wo der Spieler hinschaut) und braucht nichts.
 */
public class FreecamMenu extends ModOptionsMenu {
    public FreecamMenu(Screen parent) {
        super(parent, "Freecam", List.of(
                new Section("Flying"),
                new Slider("Speed", FreecamMod.MIN_SPEED, FreecamMod.MAX_SPEED, 1.0D,
                        () -> FreecamMod.speed, v -> FreecamMod.speed = (float) v,
                        v -> Math.round(v) + " blocks/s"),
                new Slider("Speed with Sprint", FreecamMod.MIN_BOOST, FreecamMod.MAX_BOOST, 10.0D,
                        () -> FreecamMod.boostSpeed, v -> FreecamMod.boostSpeed = (float) v,
                        v -> Math.round(v) + " blocks/s"),
                new Slider("Up / down", 20.0D, 300.0D, 10.0D,
                        () -> FreecamMod.verticalPercent, v -> FreecamMod.verticalPercent = (int) Math.round(v),
                        v -> Math.round(v) + "%"),
                new Toggle("Scroll wheel sets speed", () -> FreecamMod.scrollSpeed, v -> FreecamMod.scrollSpeed = v),
                new Info(FreecamMenu::controls),
                new Section("Your player"),
                new Info(() -> "Mining and building stay normal: where your player looks"),
                new Toggle("Show body", () -> FreecamMod.showBody, v -> FreecamMod.showBody = v),
                new Toggle("Show hand", () -> FreecamMod.showHand, v -> FreecamMod.showHand = v),
                new Toggle("Keep walking", () -> FreecamMod.keepWalking, v -> FreecamMod.keepWalking = v),
                new Info(() -> "Walking when you turn it on? Your player keeps going (sneaking too)"),
                new Toggle("Keys move body too", () -> FreecamMod.keysMoveBody, v -> FreecamMod.keysMoveBody = v)));
    }

    /** Steuerung mit den Tasten, die der Spieler wirklich eingestellt hat. */
    private static String controls() {
        Options options = Minecraft.getInstance().options;
        return "WASD fly  ·  " + key(options.keyJump) + " up  ·  " + key(options.keyShift) + " down  ·  hold "
                + key(options.keySprint) + " = fast";
    }

    private static String key(KeyMapping mapping) {
        return mapping.getTranslatedKeyMessage().getString();
    }
}
