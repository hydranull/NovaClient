package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.mixin.client.MinecraftAccessor;

import java.util.List;
import java.util.Properties;

/**
 * Fast XP: Rechtsklick gedrueckt halten mit XP-Flaschen in der Hand (Haupt- oder Nebenhand) wirft sie extrem schnell.
 * Vanilla wirft beim Halten nur alle 4 Ticks eine Flasche; hier faellt die Wartezeit weg (eine pro Tick wie Fast Place)
 * und der Mod wirft im selben Tick noch weitere hinterher.
 */
public class FastXpMod extends GameTickMod {
    public static final int MAX_PER_TICK = 6;

    /** Flaschen pro Tick (20 Ticks = 1 Sekunde). */
    public static int perTick = 3;

    public FastXpMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || client.gui.screen() != null || !client.options.keyUse.isDown()
                || player.isUsingItem() || client.gameMode.isDestroying()) {
            return;
        }
        InteractionHand hand = player.getMainHandItem().is(Items.EXPERIENCE_BOTTLE) ? InteractionHand.MAIN_HAND
                : player.getOffhandItem().is(Items.EXPERIENCE_BOTTLE) ? InteractionHand.OFF_HAND : null;
        if (hand == null) {
            return;
        }
        // Eine Flasche pro Tick wirft Vanilla selbst (ohne die 4 Ticks Wartezeit) ...
        ((MinecraftAccessor) client).nova_client$setRightClickDelay(0);
        // ... die restlichen dieses Ticks direkt hinterher
        boolean thrown = false;
        for (int i = 1; i < perTick && player.getItemInHand(hand).is(Items.EXPERIENCE_BOTTLE); i++) {
            InteractionResult result = client.gameMode.useItem(player, hand);
            if (!result.consumesAction()) {
                break;
            }
            thrown = true;
        }
        if (thrown) {
            Compat.swingUse(player, hand);
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Hold right click with XP bottles", List.of(
                new ModOptionsMenu.Slider("Bottles per tick", 1.0D, MAX_PER_TICK, 1.0D, () -> perTick,
                        v -> perTick = (int) Math.round(v), v -> Math.round(v) + " (" + Math.round(v) * 20 + " / s)")));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "perTick", String.valueOf(perTick));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            perTick = Math.max(1, Math.min(MAX_PER_TICK,
                    Integer.parseInt(properties.getProperty(prefix + "perTick", String.valueOf(perTick)).trim())));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
