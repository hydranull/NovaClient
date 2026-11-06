package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import nova.nova_client.client.LegitCrystalMacroMenu;
import nova.nova_client.client.ModCategory;

import java.util.Properties;

/**
 * LegitCrystalMacro: solange Rechtsklick gehalten wird und ein End Crystal in der Hand ist, wird abwechselnd
 * ein Crystal auf Obsidian/Bedrock gesetzt und der anvisierte Crystal geschlagen (place -> break -> place ...).
 * Place- und Break-Verzoegerung sind einstellbar, dazu eine zufaellige Abweichung fuer menschlicheres Timing.
 * Vanilla-Rechtsklick wird waehrenddessen unterdrueckt (MinecraftMixin), damit nicht doppelt gesetzt wird.
 */
public class LegitCrystalMacroMod extends GameTickMod {
    public static final int MAX_DELAY = 500;
    /** Wie lange nach dem Setzen auf den neuen Crystal gewartet wird, bevor erneut gesetzt werden darf. */
    private static final long SPAWN_TIMEOUT = 400L;

    public record Preset(String name, int place, int breakDelay, float random) {
    }

    public static final Preset[] PRESETS = {
            new Preset("Blatant", 0, 0, 0.0F),
            new Preset("Fast", 50, 50, 0.10F),
            new Preset("Legit", 120, 100, 0.25F),
            new Preset("Slow", 250, 250, 0.20F)
    };

    // Einstellungen
    /** Wartezeit nach einem Break, bevor gesetzt wird (ms). */
    public static int placeDelay = 120;
    /** Wartezeit nach einem Place, bevor geschlagen wird (ms). */
    public static int breakDelay = 100;
    /** Zufaellige Abweichung der Wartezeiten (0 - 0.5 = +-50%). */
    public static float randomization = 0.25F;

    private static boolean enabled;
    private static final RandomSource RANDOM = RandomSource.create();
    private static long lastPlace;
    private static long lastBreak;
    private static long placeWait;
    private static long breakWait;
    /** Crystal gesetzt, aber noch nicht als Entity angekommen. */
    private static boolean awaitingCrystal;

    public LegitCrystalMacroMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        LegitCrystalMacroMod.enabled = enabled;
        reset();
    }

    private static void reset() {
        lastPlace = 0L;
        lastBreak = 0L;
        placeWait = 0L;
        breakWait = 0L;
        awaitingCrystal = false;
    }

    /** Hand mit End Crystal (Haupthand bevorzugt), sonst null. */
    private static InteractionHand crystalHand(LocalPlayer player) {
        if (player.getMainHandItem().is(Items.END_CRYSTAL)) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().is(Items.END_CRYSTAL)) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    /** Soll der normale Rechtsklick gerade unterdrueckt werden? (Makro uebernimmt das Setzen) */
    public static boolean blocksVanillaUse() {
        Minecraft client = Minecraft.getInstance();
        return enabled && client.player != null && client.gui.screen() == null && crystalHand(client.player) != null;
    }

    /** Wartezeit mit zufaelliger Abweichung. */
    private static long jitter(int base) {
        float offset = (RANDOM.nextFloat() * 2.0F - 1.0F) * randomization;
        return Math.max(0L, Math.round(base * (1.0F + offset)));
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null || client.gui.screen() != null
                || !client.options.keyUse.isDown()) {
            reset();
            return;
        }
        InteractionHand hand = crystalHand(player);
        if (hand == null || player.isUsingItem()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (awaitingCrystal && now - lastPlace > SPAWN_TIMEOUT) {
            awaitingCrystal = false;
        }

        HitResult hit = client.hitResult;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof EndCrystal crystal) {
            // Break
            awaitingCrystal = false;
            if (crystal.isAlive() && now - lastPlace >= breakWait && now - lastBreak >= breakWait) {
                client.gameMode.attack(player, crystal);
                Compat.swing(player, InteractionHand.MAIN_HAND);
                lastBreak = now;
                breakWait = jitter(breakDelay);
                placeWait = jitter(placeDelay);
            }
        } else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            // Place
            if (awaitingCrystal || now - lastBreak < placeWait || !canPlaceOn(client, blockHit.getBlockPos())) {
                return;
            }
            InteractionResult result = client.gameMode.useItemOn(player, hand, blockHit);
            if (result.consumesAction()) {
                Compat.swingUse(player, hand);
                lastPlace = now;
                awaitingCrystal = true;
                breakWait = jitter(breakDelay);
            }
        }
    }

    /** Obsidian/Bedrock mit freiem Platz darueber (gleiche Regeln wie Vanilla). */
    private static boolean canPlaceOn(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        if (!state.is(Blocks.OBSIDIAN) && !state.is(Blocks.BEDROCK)) {
            return false;
        }
        BlockPos above = pos.above();
        if (!client.level.getBlockState(above).isAir()) {
            return false;
        }
        AABB space = new AABB(above.getX(), above.getY(), above.getZ(), above.getX() + 1.0D, above.getY() + 2.0D, above.getZ() + 1.0D);
        return client.level.getEntities(null, space).isEmpty();
    }

    // ---------------------------------------------------------------- Einstellungen

    public static void apply(Preset preset) {
        placeDelay = preset.place();
        breakDelay = preset.breakDelay();
        randomization = preset.random();
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new LegitCrystalMacroMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "placeDelay", String.valueOf(placeDelay));
        properties.setProperty(prefix + "breakDelay", String.valueOf(breakDelay));
        properties.setProperty(prefix + "randomization", String.valueOf(randomization));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            placeDelay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "placeDelay", String.valueOf(placeDelay)).trim()), 0, MAX_DELAY);
            breakDelay = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "breakDelay", String.valueOf(breakDelay)).trim()), 0, MAX_DELAY);
            randomization = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "randomization", String.valueOf(randomization)).trim()), 0.0F, 0.5F);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
