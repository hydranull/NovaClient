package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.TriggerBotMenu;

import java.util.Properties;

/**
 * Trigger Bot: schlaegt automatisch zu, sobald das Fadenkreuz in Reichweite auf einem Spieler liegt (die Reichweite
 * prueft Minecraft beim Anvisieren selbst). Zwei Modi: mit Cooldown (wartet, bis der Schlag voll aufgeladen ist -
 * volle Staerke, Crits, Sweep) oder ohne Cooldown (schlaegt jeden Tick).
 */
public class TriggerBotMod extends GameTickMod {
    // Einstellungen
    /** true = auf den normalen Minecraft-Angriffs-Cooldown warten, false = sofort jeden Tick schlagen. */
    public static boolean useCooldown = true;
    /** Nur mit Schwert, Axt, Streitkolben oder Dreizack in der Hand. */
    public static boolean weaponOnly = true;
    /** Keine Spieler aus dem eigenen Team schlagen. */
    public static boolean ignoreTeam = true;

    public TriggerBotMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || client.gui.screen() != null || player.isUsingItem()
                || !(client.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player target)) {
            return;
        }
        if (target == player || !target.isAlive() || target.isDeadOrDying() || target.isSpectator()
                || target.isCreative() || ignoreTeam && player.isAlliedTo(target)) {
            return;
        }
        if (weaponOnly && !isWeapon(player.getMainHandItem())) {
            return;
        }
        if (useCooldown && player.getAttackStrengthScale(0.5F) < 1.0F) {
            return;
        }
        client.gameMode.attack(player, target);
        Compat.swing(player, InteractionHand.MAIN_HAND);
    }

    private static boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE) || stack.is(Items.TRIDENT);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new TriggerBotMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "useCooldown", String.valueOf(useCooldown));
        properties.setProperty(prefix + "weaponOnly", String.valueOf(weaponOnly));
        properties.setProperty(prefix + "ignoreTeam", String.valueOf(ignoreTeam));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        useCooldown = Boolean.parseBoolean(properties.getProperty(prefix + "useCooldown", String.valueOf(useCooldown)));
        weaponOnly = Boolean.parseBoolean(properties.getProperty(prefix + "weaponOnly", String.valueOf(weaponOnly)));
        ignoreTeam = Boolean.parseBoolean(properties.getProperty(prefix + "ignoreTeam", String.valueOf(ignoreTeam)));
    }
}
