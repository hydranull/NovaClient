package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import nova.nova_client.client.ModCategory;

/** Sprintet automatisch, sobald man vorwaerts laeuft. */
public class SprintMod extends GameTickMod {
    public SprintMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        boolean enoughFood = player.getFoodData().getFoodLevel() > 6 || player.getAbilities().mayfly;
        if (player.input.hasForwardImpulse()
                && enoughFood
                && !player.isShiftKeyDown()
                && !player.horizontalCollision
                && !player.isUsingItem()) {
            player.setSprinting(true);
        }
    }
}
