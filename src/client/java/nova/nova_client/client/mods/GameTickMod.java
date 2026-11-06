package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModUpdateType;

public abstract class GameTickMod extends Mod{
    public GameTickMod(String name, ModCategory category) {
        super(name, ModUpdateType.TICK, category);
    }

    public abstract void execute(Minecraft client);
}
