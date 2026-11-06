package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.CombatEvents;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaFx;
import nova.nova_client.client.WorldToScreen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Damage Numbers: triffst du etwas, springt der Schaden als Zahl aus dem Ziel, steigt auf und verblasst.
 * Normale Treffer in Mint, Crits groesser und Violett mit Stern. Der Schaden ist der Unterschied der Lebenspunkte
 * (inkl. Absorption) vor und nach dem Treffer; gezaehlt werden nur Treffer, die der Server dir zuordnet.
 */
public class DamageNumbersMod extends GameTickMod {
    private static final double RANGE = 32.0D;
    private static final long LIFE_MS = 1100L;
    private static final int MAX_NUMBERS = 64;

    private record Number(Vec3 origin, double driftX, double driftZ, float amount, boolean crit, long born) {
    }

    private static volatile boolean enabled;
    private static final List<Number> NUMBERS = new ArrayList<>();
    private final Map<Integer, Float> lastHealth = new HashMap<>();

    public DamageNumbersMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        DamageNumbersMod.enabled = enabled;
        NUMBERS.clear();
        lastHealth.clear();
    }

    /** Jeden Tick: Leben aller Ziele in der Naehe vergleichen. */
    @Override
    public void execute(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living == client.player
                    || living.distanceToSqr(client.player) > RANGE * RANGE) {
                continue;
            }
            int id = living.getId();
            seen.add(id);
            float health = Math.max(0.0F, living.getHealth()) + living.getAbsorptionAmount();
            Float before = lastHealth.put(id, health);
            if (before != null && health < before - 0.05F && CombatEvents.hitByMeRecently(id, 1000L)) {
                spawn(living, before - health, CombatEvents.critRecently(id, 600L));
            }
        }
        lastHealth.keySet().retainAll(seen);
    }

    private static void spawn(LivingEntity target, float amount, boolean crit) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vec3 origin = target.position().add(0.0D, target.getBbHeight() * 0.75D, 0.0D);
        NUMBERS.add(new Number(origin, (random.nextDouble() - 0.5D) * 0.7D, (random.nextDouble() - 0.5D) * 0.7D,
                amount, crit, System.currentTimeMillis()));
        if (NUMBERS.size() > MAX_NUMBERS) {
            NUMBERS.remove(0);
        }
    }

    private static String format(float amount) {
        float rounded = Math.round(amount * 2.0F) / 2.0F;
        return rounded == Math.floor(rounded) ? String.valueOf((int) rounded)
                : String.format(Locale.ROOT, "%.1f", rounded);
    }

    /** Aus dem HUD-Callback, jeden Frame. */
    public static void drawHud(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || NUMBERS.isEmpty() || client.getDebugOverlay().showDebugScreen()) {
            return;
        }
        long now = System.currentTimeMillis();
        NUMBERS.removeIf(n -> now - n.born() > LIFE_MS);
        Font font = client.font;
        for (Number n : NUMBERS) {
            float t = (now - n.born()) / (float) LIFE_MS;
            float rise = 1.0F - (1.0F - t) * (1.0F - t);
            Vec3 pos = n.origin().add(n.driftX() * rise, 0.7D * rise, n.driftZ() * rise);
            float[] screen = WorldToScreen.project(pos);
            if (screen == null) {
                continue;
            }
            double distance = pos.distanceTo(WorldToScreen.camera());
            // Aufploppen: kurz gross, dann normal; zum Ende ausblenden
            float pop = t < 0.12F ? 1.0F + 0.8F * (1.0F - t / 0.12F) : 1.0F;
            float alpha = t > 0.6F ? 1.0F - (t - 0.6F) / 0.4F : 1.0F;
            float scale = (float) Mth.clamp(1.6D - distance / 20.0D, 0.6D, 1.6D) * pop * (n.crit() ? 1.35F : 1.0F);
            String text = n.crit() ? "✦" + format(n.amount()) : format(n.amount());
            int color = n.crit() ? NovaFx.mix(NovaFx.VIOLET, 0xFFFFFF, 0.15F) : NovaFx.MINT;
            int main = NovaFx.argb(color, alpha);
            int outline = NovaFx.argb(0x0A0E1C, alpha * 0.85F);

            g.pose().pushMatrix();
            g.pose().translate(screen[0], screen[1]);
            g.pose().scale(scale, scale);
            int x = -font.width(text) / 2;
            int y = -4;
            // Rand ringsum, damit die Zahl auf jedem Hintergrund lesbar ist
            g.text(font, text, x - 1, y, outline, false);
            g.text(font, text, x + 1, y, outline, false);
            g.text(font, text, x, y - 1, outline, false);
            g.text(font, text, x, y + 1, outline, false);
            g.text(font, text, x, y, main, false);
            g.pose().popMatrix();
        }
    }
}
