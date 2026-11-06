package nova.nova_client.client.farmbuilder;

import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * Eine geladene Bauvorlage (Litematica, Sponge oder Vanilla-Struktur): alle Bloecke in einem Quader,
 * lokale Koordinaten 0..size-1. Luft wird als null gespeichert.
 */
public final class Schematic {
    public final String name;
    public final String author;
    public final String fileName;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    /** Index (y * sizeZ + z) * sizeX + x. */
    private final BlockState[] blocks;
    /** Entities (Villager, Minecarts, Rahmen ...) - kann der Bot nicht bauen, nur zur Info. */
    public final int entityCount;
    /** Bloecke mit Inhalt (Kisten, Trichter ...) - der Inhalt wird nicht nachgebaut. */
    public final int tileEntityCount;
    /** Bloecke, die es in dieser Minecraft-Version nicht gibt. */
    public final int unknownBlocks;
    /** Was aus den Block-Daten gebraucht wird (Crafter-Felder) bzw. gemeldet wird (Inhalt). */
    public final Extras extras;

    /**
     * Aus den Block-Daten: gesperrte Crafter-Felder (Index wie {@link #get} -> Felder 0..8), Behaelter mit Inhalt und
     * Kreativ-Items darin (Barriere ...) - der Inhalt wird nicht nachgebaut, nur gemeldet.
     */
    public record Extras(Map<Integer, int[]> crafterSlots, int filledContainers, Map<String, Integer> creativeItems) {
        public static final Extras NONE = new Extras(Map.of(), 0, Map.of());
    }

    Schematic(String name, String author, String fileName, int sizeX, int sizeY, int sizeZ, BlockState[] blocks,
              int entityCount, int tileEntityCount, int unknownBlocks, Extras extras) {
        this.name = name;
        this.author = author;
        this.fileName = fileName;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = blocks;
        this.entityCount = entityCount;
        this.tileEntityCount = tileEntityCount;
        this.unknownBlocks = unknownBlocks;
        this.extras = extras;
    }

    /** Index in {@link #get}-Koordinaten. */
    public int index(int x, int y, int z) {
        return (y * sizeZ + z) * sizeX + x;
    }

    public BlockState get(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return null;
        }
        return blocks[index(x, y, z)];
    }

    public int nonAirCount() {
        int count = 0;
        for (BlockState state : blocks) {
            if (state != null) {
                count++;
            }
        }
        return count;
    }
}
