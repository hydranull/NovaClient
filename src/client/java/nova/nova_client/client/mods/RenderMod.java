package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModUpdateType;

public abstract class RenderMod extends Mod{
    public RenderMod(String name, ModCategory category) {
        super(name, ModUpdateType.RENDER, category);
    }

    public abstract void execute(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker);
}
