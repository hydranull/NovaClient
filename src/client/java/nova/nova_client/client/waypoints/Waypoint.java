package nova.nova_client.client.waypoints;

import net.minecraft.world.phys.Vec3;

/**
 * Ein Wegpunkt: Block-Position in einer Dimension einer Welt (Server-Adresse bzw. Einzelspieler-Speicherstand, siehe
 * {@link WaypointStore#currentWorld()}). Dauerhaft oder temporaer - wann temporaere verschwinden, legt das Waypoints-Modul
 * fest (nach Zeit und/oder wenn ihr Chunk entladen wird).
 */
public final class Waypoint {
    public static final int MAX_NAME_LENGTH = 32;

    public String name;
    public final String world;
    /** z.B. "minecraft:overworld". */
    public String dimension;
    public int x;
    public int y;
    public int z;
    /** Farbe als RGB (ohne Alpha). */
    public int color;
    /** In der Welt anzeigen (ausgeblendete bleiben in der Liste). */
    public boolean visible = true;
    public boolean temporary;
    public long created;
    /** Ablaufzeit temporaerer Wegpunkte (System.currentTimeMillis). */
    public long expiresAt;
    /**
     * War der Chunk beim letzten Pruefen auf dem Client geladen? Der Wechsel geladen -> nicht geladen ist das
     * "Chunk entladen" (auch beim Verlassen der Welt oder der Dimension). Wird mitgespeichert: wer das Spiel mit
     * geladenem Chunk beendet, hat ihn beim naechsten Start entladen.
     */
    public boolean chunkLoaded;

    public Waypoint(String name, String world, String dimension, int x, int y, int z, int color) {
        this.name = name;
        this.world = world;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.color = color;
        this.created = System.currentTimeMillis();
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    /** Mitte des Blocks. */
    public Vec3 center() {
        return new Vec3(x + 0.5D, y + 0.5D, z + 0.5D);
    }

    /** Name fuer die Anzeige (ein geleertes Namensfeld zeigt nicht "nichts"). */
    public String displayName() {
        return name == null || name.isBlank() ? "Waypoint" : name;
    }

    public String coords() {
        return x + ", " + y + ", " + z;
    }
}
