package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.mods.KillEffectsMod;

import java.util.List;

/**
 * Einstellungen fuer Kill Effects: Effekt waehlen, Vorschau vor dir abspielen, nur Spieler, Sound.
 */
public class KillEffectsMenu extends ModOptionsMenu {
    private static final KillEffectsMod.Effect[] EFFECTS = KillEffectsMod.Effect.values();

    public KillEffectsMenu(Screen parent) {
        super(parent, "Kill Effects", List.of(
                new Section("Effect"),
                new Segmented("", labels(), () -> KillEffectsMod.effect.ordinal(), i -> KillEffectsMod.effect = EFFECTS[i]),
                new Action(() -> "Preview in front of you", KillEffectsMenu::preview),
                new Section("Options"),
                new Toggle("Players only", () -> KillEffectsMod.playersOnly, v -> KillEffectsMod.playersOnly = v),
                new Toggle("Sound", () -> KillEffectsMod.sound, v -> KillEffectsMod.sound = v),
                new Info(() -> "Plays when something you hit dies. Only you see it.")));
    }

    private static String[] labels() {
        String[] labels = new String[EFFECTS.length];
        for (int i = 0; i < EFFECTS.length; i++) {
            labels[i] = EFFECTS[i].label;
        }
        return labels;
    }

    /** Effekt 3 Bloecke vor dir abspielen, alle Menues schliessen, damit man ihn sieht. */
    private static void preview() {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        flat = flat.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : flat.normalize();
        Vec3 pos = player.position().add(flat.scale(3.0D));
        ModConfig.save();
        client.gui.setScreen(null);
        KillEffectsMod.play(pos, 1.8D, KillEffectsMod.effect, player);
    }
}
