package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import nova.nova_client.client.ModCategory;

/** Fliegen wie im Kreativmodus (Leertaste/Shift fuer hoch/runter, Sprint = schneller). */
public class FlightMod extends GameTickMod {
    private static final float NORMAL_SPEED = 0.05F;
    private static final float FAST_SPEED = 0.1F;

    private int ticks;

    public FlightMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        Abilities abilities = player.getAbilities();
        abilities.mayfly = true;
        abilities.flying = true;
        abilities.setFlyingSpeed(client.options.keySprint.isDown() ? FAST_SPEED : NORMAL_SPEED);

        // Anti-Kick: Vanilla-Server werfen Spieler raus, die ~4 Sekunden in der Luft schweben.
        // Alle 40 Ticks minimal absinken, damit der Server eine Abwaertsbewegung sieht.
        ticks++;
        if (ticks % 40 == 0 && !player.onGround()) {
            player.setPos(player.getX(), player.getY() - 0.04D, player.getZ());
        }
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        ticks = 0;
        if (enabled || client == null || client.player == null) {
            return;
        }

        LocalPlayer player = client.player;
        Abilities abilities = player.getAbilities();
        abilities.setFlyingSpeed(NORMAL_SPEED);
        if (!player.isCreative() && !player.isSpectator()) {
            abilities.flying = false;
            abilities.mayfly = false;
        }
    }
}
