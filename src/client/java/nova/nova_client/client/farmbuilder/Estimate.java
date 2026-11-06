package nova.nova_client.client.farmbuilder;

import net.minecraft.world.item.Item;

import java.util.Map;

/**
 * Grobe Zeitschaetzung fuer einen Bau: Setzen (inkl. Zielen und Pausen), Laufen, Material holen, Tuerme fuer hohe
 * Teile und Wasser. Waehrend des Baus wird die Schaetzung mit der echten Geschwindigkeit nachgefuehrt.
 */
public final class Estimate {
    /** Laufgeschwindigkeit (Bloecke pro Sekunde, gemischt Gehen/Sprinten). */
    private static final double WALK_SPEED = 4.6D;

    private Estimate() {
    }

    /** Sekunden pro gesetztem Block bei Geschwindigkeit 1.0 (Zielen, Klicken, kleine Pausen). */
    public static double secondsPerBlock(float speed) {
        return 0.62D / speed;
    }

    public static long seconds(BuildPlan plan, int remainingPlacements, double chestDistance, float speed) {
        if (plan == null) {
            return 0L;
        }
        int total = Math.max(1, plan.totalPlacements());
        double share = Math.min(1.0D, remainingPlacements / (double) total);
        double place = remainingPlacements * secondsPerBlock(speed);
        // Zwischen den Bloecken ein paar Schritte
        double walk = remainingPlacements * 0.33D / WALK_SPEED;
        // Material holen: Inventar fasst ~30 Stapel pro Runde
        int stacks = 0;
        for (Map.Entry<Item, Integer> e : plan.materials.entrySet()) {
            int max = Math.max(1, e.getKey().getDefaultMaxStackSize());
            stacks += (e.getValue() + max - 1) / max;
        }
        stacks = (int) Math.ceil(stacks * share);
        int trips = Math.max(remainingPlacements > 0 ? 1 : 0, (int) Math.ceil(stacks / 30.0D));
        double perTrip = 2.0D * chestDistance / WALK_SPEED + 3.0D * 2.5D;
        double restock = trips * perTrip + stacks * 0.35D / speed;
        // Hotbar umraeumen, wenn es mehr als 9 Sorten gibt
        double hotbar = Math.max(0, plan.materials.size() - 8) * stacks / Math.max(1.0D, plan.materials.size()) * 1.6D;
        // Hohe Bauten: zwischendurch hochbauen und wieder abbauen
        double towers = plan.sizeY > 4 ? (plan.sizeY - 4) * (plan.sizeX * plan.sizeZ / 45.0D) * 1.8D * share : 0.0D;
        double fluids = plan.fluidCount * 2.4D * (remainingPlacements > 0 ? 1.0D : 0.0D);
        double sum = (place + walk + restock + hotbar + towers + fluids) * 1.08D;
        return Math.round(sum);
    }

    /** "≈ 1 h 05 min", "≈ 12 min", "< 1 min". */
    public static String format(long seconds) {
        if (seconds < 60) {
            return "< 1 min";
        }
        long minutes = Math.round(seconds / 60.0D);
        if (minutes < 60) {
            return "≈ " + minutes + " min";
        }
        return "≈ " + minutes / 60 + " h " + String.format("%02d", minutes % 60) + " min";
    }
}
