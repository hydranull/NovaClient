package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import nova.nova_client.client.ModCategory;

public class FullbrightMod extends GameTickMod {
    // volatile Flag statt Suche in der Mod-Liste: wird jeden Frame vom Lightmap-Mixin abgefragt
    private static volatile boolean enabled;

    public FullbrightMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        FullbrightMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }
}
