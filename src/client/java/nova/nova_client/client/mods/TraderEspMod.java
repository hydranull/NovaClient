package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import nova.nova_client.client.EspOptionsMenu;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Markiert Wandering Trader (und ihre Lamas) und meldet einen neu entdeckten Trader im Chat mit Koordinaten + Ton.
 */
public class TraderEspMod extends TargetEspMod {
    public boolean llamas = true;
    public boolean alert = true;

    /** Schon gemeldete Trader (nicht bei jedem Tick neu melden). */
    private final Set<UUID> announced = new HashSet<>();

    public TraderEspMod(String name) {
        super(name, 0x4FA3FF);
    }

    @Override
    protected boolean matches(Entity entity) {
        return entity instanceof WanderingTrader || llamas && entity instanceof TraderLlama;
    }

    @Override
    protected String label(Entity entity) {
        return entity instanceof WanderingTrader ? "Wandering Trader" : "Trader Llama";
    }

    @Override
    public void execute(Minecraft client) {
        if (!alert || client.level == null || client.player == null) {
            return;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof WanderingTrader && entity.isAlive() && announced.add(entity.getUUID())) {
                BlockPos pos = entity.blockPosition();
                long distance = Math.round(Math.sqrt(client.player.distanceToSqr(entity)));
                Compat.chat(client.player, Component.literal("[Nova] ").withStyle(ChatFormatting.AQUA)
                        .append(Component.literal("Wandering Trader found! ").withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" (" + distance + "m)").withStyle(ChatFormatting.GRAY)));
                client.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 1.6F);
            }
        }
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        announced.clear();
    }

    @Override
    protected List<EspOptionsMenu.Toggle> toggles() {
        List<EspOptionsMenu.Toggle> list = super.toggles();
        list.add(new EspOptionsMenu.Toggle("Trader llamas", () -> llamas, () -> llamas = !llamas));
        list.add(new EspOptionsMenu.Toggle("Chat alert + sound", () -> alert, () -> alert = !alert));
        return list;
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        super.saveSettings(properties, prefix);
        properties.setProperty(prefix + "llamas", String.valueOf(llamas));
        properties.setProperty(prefix + "alert", String.valueOf(alert));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        super.loadSettings(properties, prefix);
        llamas = bool(properties, prefix + "llamas", llamas);
        alert = bool(properties, prefix + "alert", alert);
    }
}
