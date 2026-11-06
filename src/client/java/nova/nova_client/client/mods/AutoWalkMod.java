package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import nova.nova_client.client.ModCategory;

/**
 * Auto Walk: laeuft von selbst geradeaus, ohne dass man W halten muss. Andere Tasten unterbrechen das nicht;
 * nur solange das Inventar (oder eine Kiste o.Ae.) offen ist, bleibt man stehen. Die Logik steckt in KeyboardInputMixin.
 */
public class AutoWalkMod extends GameTickMod {
    private static volatile boolean enabled;

    public AutoWalkMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        AutoWalkMod.enabled = enabled;
    }

    /** Soll gerade automatisch gelaufen werden? */
    public static boolean shouldWalk() {
        return enabled && !(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?>);
    }
}
