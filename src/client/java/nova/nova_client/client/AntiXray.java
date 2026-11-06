package nova.nova_client.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.function.Function;

/**
 * Filter gegen Anti-Xray (z.B. Paper engine-mode 2): Server ersetzen komplett eingeschlossene Bloecke
 * durch zufaellige Bloecke - Fake-Erze UND Fake-Luftloecher. Nur Bloecke an echten Hohlraeumen
 * (Hoehlen, Tunnel, Wasser) werden vom Server echt gesendet.
 *
 * Deshalb zaehlt ein Block nur als echt, wenn er an Wasser/Lava grenzt oder an Luft, die mit
 * mindestens {@link #minCaveSize} Luftbloecken verbunden ist. Einzelne Fake-Loecher im Stein
 * erreichen diese Groesse praktisch nie.
 */
public final class AntiXray {
    /** Fake-Erze ausblenden (fuer Xray und BlockESP). */
    public static volatile boolean hideFakes = true;

    /** Wie viele verbundene Luftbloecke ein echter Hohlraum mindestens hat (einstellbar). */
    public static volatile int minCaveSize = 16;
    public static final int MIN_CAVE_SIZE_MIN = 2;
    public static final int MIN_CAVE_SIZE_MAX = 32;

    /**
     * Positionen, die der Server einzeln per Block-Update geschickt hat. Solche Updates sind immer echt
     * (z.B. wenn beim Abbauen die Bloecke im Umkreis aufgedeckt werden) und werden deshalb immer angezeigt.
     */
    private static final Set<Long> CONFIRMED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Obergrenze, damit die Menge bei langen Sessions (Farmen, Redstone) nicht endlos waechst. */
    private static final int MAX_CONFIRMED = 200_000;

    /**
     * Vom Server einzeln gesendeten Block als echt merken. Immer, auch bei ausgeschaltetem Xray:
     * wer erst graebt und danach Xray anschaltet, braucht die Bestaetigungen fuer die frischen Gaenge.
     */
    public static void confirm(BlockPos pos) {
        if (CONFIRMED.size() >= MAX_CONFIRMED) {
            CONFIRMED.clear();
        }
        CONFIRMED.add(pos.asLong());
    }

    /** Beim Welt-/Dimensionswechsel vergessen. */
    public static void clearConfirmed() {
        CONFIRMED.clear();
    }

    public static int confirmedCount() {
        return CONFIRMED.size();
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    private AntiXray() {
    }

    /** Filter umschalten und Xray/BlockESP sofort neu aufbauen. */
    public static void setHideFakes(boolean value) {
        hideFakes = value;
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (nova.nova_client.client.mods.XrayMod.isEnabled() && client.level != null) {
            client.levelExtractor.allChanged();
        }
        nova.nova_client.client.mods.BlockEspMod.rescan();
        ModConfig.save();
    }

    /** Soll ein gefundener Block angezeigt werden? */
    public static boolean isReal(BlockPos pos, Function<BlockPos, BlockState> getter) {
        return !hideFakes || CONFIRMED.contains(pos.asLong()) || touchesRealCavity(pos, getter);
    }

    /** Grenzt der Block an Fluessigkeit oder an einen echten (grossen) Hohlraum? */
    private static boolean touchesRealCavity(BlockPos pos, Function<BlockPos, BlockState> getter) {
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        LongOpenHashSet checked = null;
        for (Direction direction : DIRECTIONS) {
            neighbor.setWithOffset(pos, direction);
            BlockState state = getter.apply(neighbor);
            if (!state.getFluidState().isEmpty()) {
                return true; // Wasser/Lava werden nicht gefaelscht
            }
            if (state.isSolidRender()) {
                continue;
            }
            if (checked == null) {
                checked = new LongOpenHashSet();
            } else if (checked.contains(neighbor.asLong())) {
                continue; // gehoert zu einem schon geprueften (zu kleinen) Hohlraum
            }
            if (isLargeCavity(neighbor.immutable(), getter, checked)) {
                return true;
            }
        }
        return false;
    }

    /** Flood-Fill ueber nicht-massive Bloecke: erreicht der Hohlraum MIN_CAVE_SIZE Bloecke? */
    private static boolean isLargeCavity(BlockPos start, Function<BlockPos, BlockState> getter, LongOpenHashSet checked) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        // fastutil-Set ohne Long-Boxing (wird beim Chunk-Bau sehr oft aufgerufen)
        LongOpenHashSet visited = new LongOpenHashSet();
        queue.add(start);
        visited.add(start.asLong());
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (Direction direction : DIRECTIONS) {
                next.setWithOffset(current, direction);
                long key = next.asLong();
                if (visited.contains(key)) {
                    continue;
                }
                BlockState state = getter.apply(next);
                if (state.isSolidRender()) {
                    continue;
                }
                if (!state.getFluidState().isEmpty()) {
                    return true;
                }
                visited.add(key);
                if (visited.size() >= minCaveSize) {
                    return true;
                }
                queue.add(next.immutable());
            }
        }
        checked.addAll(visited);
        return false;
    }
}
