package nova.nova_client.client.mods;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

/** Markiert Dorfbewohner. */
public class VillagerEspMod extends TargetEspMod {
    public VillagerEspMod(String name) {
        super(name, 0x57E389);
    }

    @Override
    protected boolean matches(Entity entity) {
        return entity instanceof Villager;
    }

    @Override
    protected String label(Entity entity) {
        return ((Villager) entity).isBaby() ? "Baby Villager" : "Villager";
    }
}
