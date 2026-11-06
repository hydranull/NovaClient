package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import nova.nova_client.client.ModCategory;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Item Physics: gedroppte Items schweben und drehen sich nicht mehr, sondern liegen flach auf dem Boden. Im Fallen
 * ueberschlagen sie sich (schneller, je schneller sie fliegen) und kippen beim Landen in die naechste flache Lage.
 * Gezeichnet wird in ItemEntityRendererMixin; hier wird nur der Kippwinkel je Item weitergerechnet.
 */
public class ItemPhysicsMod extends GameTickMod {
    private static volatile boolean enabled;
    /** Je Item: {Kippwinkel, letztes Alter in Ticks, Drehung um die Hochachse}. */
    private static final Map<ItemEntity, float[]> STATE = new WeakHashMap<>();

    public ItemPhysicsMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        ItemPhysicsMod.enabled = enabled;
        STATE.clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Jeden Frame je sichtbarem Item: neuen Kippwinkel rechnen. Rueckgabe {Kippwinkel, Drehung}. */
    public static float[] update(ItemEntity entity, float age) {
        float[] state = STATE.computeIfAbsent(entity, e -> new float[]{90.0F, age, yawFor(e.getId())});
        float dt = Math.max(0.0F, Math.min(5.0F, age - state[1]));
        state[1] = age;
        if (!entity.onGround()) {
            // Ueberschlagen: Grundtempo + schneller bei hoher Geschwindigkeit, im Wasser langsam
            double speed = entity.getDeltaMovement().length();
            float spin = (float) (8.0D + speed * 60.0D);
            if (entity.isInWater()) {
                spin *= 0.2F;
            }
            state[0] = (state[0] + spin * dt) % 360.0F;
        } else {
            // Am Boden in die naechste flache Lage (90 oder 270 Grad) kippen
            float target = 90.0F + 180.0F * Math.round((state[0] - 90.0F) / 180.0F);
            state[0] += (target - state[0]) * Math.min(1.0F, dt * 0.45F);
        }
        return new float[]{state[0], state[2]};
    }

    private static float yawFor(int id) {
        int x = id * 0x27d4eb2d;
        x ^= x >>> 15;
        x *= 0x85ebca6b;
        x ^= x >>> 13;
        return (x & 0xFFFF) / 65536.0F * 360.0F;
    }
}
