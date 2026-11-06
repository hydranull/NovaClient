package nova.nova_client.client.waypoints;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Alle Wegpunkte aller Welten. Eigene Datei config/nova_client_waypoints.properties (nicht in der Mod-Config: Profile
 * laden die Mod-Einstellungen neu und wuerden sonst alte Wegpunkte zurueckholen). Neueste Wegpunkte stehen vorne.
 */
public final class WaypointStore {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nova_client_waypoints.properties");
    private static final List<Waypoint> ALL = new ArrayList<>();
    private static boolean loaded;
    private static boolean dirty;

    // Welt und Dimension, einmal je ClientLevel berechnet (der Ablauf fragt sie jeden Tick ab)
    private static ClientLevel cachedLevel;
    private static String cachedWorld;
    private static String cachedDimension;

    private WaypointStore() {
    }

    /** Alle Wegpunkte (die echte Liste - nach Aenderungen {@link #changed()} aufrufen). */
    public static List<Waypoint> all() {
        load();
        return ALL;
    }

    /** Wegpunkte einer Welt (alle Dimensionen), neueste zuerst. */
    public static List<Waypoint> inWorld(String world) {
        List<Waypoint> result = new ArrayList<>();
        if (world != null) {
            for (Waypoint waypoint : all()) {
                if (waypoint.world.equals(world)) {
                    result.add(waypoint);
                }
            }
        }
        return result;
    }

    public static void add(Waypoint waypoint) {
        load();
        ALL.add(0, waypoint);
        save();
    }

    public static void remove(Waypoint waypoint) {
        load();
        if (ALL.remove(waypoint)) {
            save();
        }
    }

    /** Position oder Dimension geaendert: Chunk-Zustand neu erfassen (sonst gilt der alte Chunk als entladen). */
    public static void moved(Waypoint waypoint) {
        waypoint.chunkLoaded = chunkLoaded(waypoint);
        changed();
    }

    /** Etwas wurde geaendert; gespeichert wird gesammelt mit {@link #saveIfDirty()}. */
    public static void changed() {
        dirty = true;
    }

    public static void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    // ---------------------------------------------------------------- Welt

    /**
     * Kennung der Welt, in der du gerade bist (null = in keiner): Server-Adresse, im Einzelspieler der Ordnername des
     * Speicherstands (eindeutig - Weltnamen wie "New World" gibt es oft mehrfach).
     */
    public static String currentWorld() {
        return cache() ? cachedWorld : null;
    }

    /** Dimension, in der du gerade bist, z.B. "minecraft:overworld" (null = in keiner Welt). */
    public static String currentDimension() {
        return cache() ? cachedDimension : null;
    }

    private static boolean cache() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) {
            cachedLevel = null; // alte Welt nicht im Speicher festhalten
            return false;
        }
        if (level != cachedLevel) {
            cachedLevel = level;
            cachedWorld = worldId(client);
            cachedDimension = level.dimension().identifier().toString();
        }
        return true;
    }

    private static String worldId(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return "sp:" + (folder != null ? folder.toString() : server.getWorldData().getLevelName());
        }
        if (client.getCurrentServer() != null) {
            return client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        }
        return "unknown";
    }

    /** Liegt der Wegpunkt in deiner aktuellen Welt und Dimension? */
    public static boolean isHere(Waypoint waypoint) {
        String world = currentWorld();
        return world != null && waypoint.world.equals(world) && waypoint.dimension.equals(currentDimension());
    }

    /**
     * Ist der Chunk des Wegpunkts gerade auf dem Client geladen? (ClientLevel.hasChunk meldet immer true - deshalb
     * direkt im Chunk-Cache nachsehen.)
     */
    public static boolean chunkLoaded(Waypoint waypoint) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && isHere(waypoint)
                && level.getChunkSource().hasChunk(waypoint.chunkX(), waypoint.chunkZ());
    }

    /** "Overworld", "Nether", "End" bzw. der Name einer eigenen Dimension. */
    public static String dimensionName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> {
                String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
                yield path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1);
            }
        };
    }

    // ---------------------------------------------------------------- Datei

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(FILE)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        } catch (IOException e) {
            System.err.println("[Nova] Could not load waypoints: " + e.getMessage());
            return;
        }
        int count = parseInt(properties.getProperty("count"), 0);
        for (int i = 0; i < count; i++) {
            String prefix = "waypoint." + i + ".";
            String world = properties.getProperty(prefix + "world");
            String dimension = properties.getProperty(prefix + "dimension");
            String[] pos = properties.getProperty(prefix + "pos", "").split(",");
            if (world == null || dimension == null || pos.length != 3) {
                continue;
            }
            try {
                Waypoint waypoint = new Waypoint(properties.getProperty(prefix + "name", "Waypoint"), world, dimension,
                        Integer.parseInt(pos[0].trim()), Integer.parseInt(pos[1].trim()), Integer.parseInt(pos[2].trim()),
                        Integer.parseInt(properties.getProperty(prefix + "color", "FFFFFF").trim(), 16) & 0xFFFFFF);
                waypoint.visible = Boolean.parseBoolean(properties.getProperty(prefix + "visible", "true"));
                waypoint.temporary = Boolean.parseBoolean(properties.getProperty(prefix + "temporary", "false"));
                waypoint.created = parseLong(properties.getProperty(prefix + "created"), waypoint.created);
                waypoint.expiresAt = parseLong(properties.getProperty(prefix + "expires"), 0L);
                waypoint.chunkLoaded = Boolean.parseBoolean(properties.getProperty(prefix + "chunkLoaded", "false"));
                ALL.add(waypoint);
            } catch (NumberFormatException ignored) {
                // kaputter Eintrag: ueberspringen
            }
        }
    }

    private static void save() {
        dirty = false;
        Properties properties = new Properties();
        properties.setProperty("count", String.valueOf(ALL.size()));
        for (int i = 0; i < ALL.size(); i++) {
            Waypoint waypoint = ALL.get(i);
            String prefix = "waypoint." + i + ".";
            properties.setProperty(prefix + "name", waypoint.name);
            properties.setProperty(prefix + "world", waypoint.world);
            properties.setProperty(prefix + "dimension", waypoint.dimension);
            properties.setProperty(prefix + "pos", waypoint.x + "," + waypoint.y + "," + waypoint.z);
            properties.setProperty(prefix + "color", String.format(Locale.ROOT, "%06X", waypoint.color & 0xFFFFFF));
            properties.setProperty(prefix + "visible", String.valueOf(waypoint.visible));
            properties.setProperty(prefix + "temporary", String.valueOf(waypoint.temporary));
            properties.setProperty(prefix + "created", String.valueOf(waypoint.created));
            if (waypoint.temporary) {
                properties.setProperty(prefix + "expires", String.valueOf(waypoint.expiresAt));
                properties.setProperty(prefix + "chunkLoaded", String.valueOf(waypoint.chunkLoaded));
            }
        }
        // Erst in eine Nebendatei schreiben, dann ersetzen: ein Absturz beim Speichern zerstoert keine Wegpunkte
        Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "Nova Client - Waypoints");
            }
            try {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Nova] Could not save waypoints: " + e.getMessage());
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
