package nova.nova_client.client.mods;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import nova.nova_client.client.EspOptionsMenu;

import java.util.List;
import java.util.Properties;

/** Markiert Item Frames (auch Glow Item Frames), auf Wunsch nur solche mit Item drin. */
public class ItemFrameEspMod extends TargetEspMod {
    public boolean onlyWithItem = false;

    public ItemFrameEspMod(String name) {
        super(name, 0xFFC857);
    }

    @Override
    protected boolean matches(Entity entity) {
        return entity instanceof ItemFrame frame && (!onlyWithItem || !frame.getItem().isEmpty());
    }

    @Override
    protected String label(Entity entity) {
        ItemFrame frame = (ItemFrame) entity;
        if (frame.getItem().isEmpty()) {
            return frame instanceof GlowItemFrame ? "Glow Item Frame" : "Item Frame";
        }
        return frame.getItem().getHoverName().getString();
    }

    // Item Frames sind flach, der Glow-Umriss faellt kaum auf -> zusaetzlich eine Box
    @Override
    protected boolean drawBox() {
        return true;
    }

    @Override
    protected List<EspOptionsMenu.Toggle> toggles() {
        List<EspOptionsMenu.Toggle> list = super.toggles();
        list.add(new EspOptionsMenu.Toggle("Only frames with an item", () -> onlyWithItem, () -> onlyWithItem = !onlyWithItem));
        return list;
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        super.saveSettings(properties, prefix);
        properties.setProperty(prefix + "onlyWithItem", String.valueOf(onlyWithItem));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        super.loadSettings(properties, prefix);
        onlyWithItem = bool(properties, prefix + "onlyWithItem", onlyWithItem);
    }
}
