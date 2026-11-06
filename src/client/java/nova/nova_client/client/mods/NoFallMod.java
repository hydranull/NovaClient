package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import nova.nova_client.client.ModCategory;

/** Verhindert Fallschaden, indem dem Server waehrend des Falls "auf dem Boden" gemeldet wird. */
public class NoFallMod extends GameTickMod {
    public NoFallMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || player.isCreative() || player.isSpectator() || player.isFallFlying()) {
            return;
        }

        if (player.fallDistance > 2.5D) {
            player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, player.horizontalCollision));
        }
    }
}
