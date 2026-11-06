package nova.nova_client.client.mods;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.Pillager;

/** Markiert Pillager (Patrouillen, Aussenposten). */
public class PillagerEspMod extends TargetEspMod {
    public PillagerEspMod(String name) {
        super(name, 0xC04CFD);
    }

    @Override
    protected boolean matches(Entity entity) {
        return entity instanceof Pillager;
    }

    @Override
    protected String label(Entity entity) {
        return "Pillager";
    }
}
