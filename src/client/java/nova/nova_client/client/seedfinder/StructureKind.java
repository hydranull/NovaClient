package nova.nova_client.client.seedfinder;

/**
 * Strukturen, die der Seed Finder nutzt - nur solche, deren Startchunk sich aus den Bloecken eindeutig ablesen laesst
 * (Werte aus data/minecraft/worldgen/structure_set, Minecraft 26.2).
 */
public enum StructureKind {
    DESERT_PYRAMID("Desert Pyramid", 14357617, 32, 8),
    JUNGLE_TEMPLE("Jungle Temple", 14357619, 32, 8),
    SWAMP_HUT("Swamp Hut", 14357620, 32, 8),
    /** Spacing 1: jeder Chunk koennte einen haben, 1 % Chance - eigene Pruefung im Cracker. */
    BURIED_TREASURE("Buried Treasure", 0, 1, 0);

    public final String title;
    public final int salt;
    public final int spacing;
    public final int separation;

    StructureKind(String title, int salt, int spacing, int separation) {
        this.title = title;
        this.salt = salt;
        this.spacing = spacing;
        this.separation = separation;
    }

    public static StructureKind byName(String name) {
        for (StructureKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return null;
    }
}
