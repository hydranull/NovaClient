package nova.nova_client.client.seedfinder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.mixin.client.BiomeManagerAccessor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Was der Seed Finder pro Server/Welt gesammelt hat: Strukturen, End-Saeulen, Seed-Hash vom Server und das Ergebnis.
 * Bleibt ueber Neustarts erhalten (config/nova_client_seedfinder.properties), damit man ueber mehrere Sitzungen
 * sammeln kann.
 */
public final class SeedData {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nova_client_seedfinder.properties");
    private static final Map<String, SeedData> ALL = new HashMap<>();
    private static boolean loaded;

    public record Found(StructureKind kind, int chunkX, int chunkZ, boolean manual) {
        public int blockX() {
            return chunkX * 16 + (kind == StructureKind.BURIED_TREASURE ? 9 : 8);
        }

        public int blockZ() {
            return chunkZ * 16 + (kind == StructureKind.BURIED_TREASURE ? 9 : 8);
        }
    }

    public final String server;
    public final List<Found> structures = new ArrayList<>();
    /** Groessen-Index (0..9) jeder End-Saeule, -1 = noch nicht gesehen. */
    public final int[] pillars = new int[10];
    /** SHA-256-Hash des Seeds, den der Server beim Betreten schickt (null = keiner). */
    public Long hashedSeed;
    /** Ergebnis (null = noch unbekannt). */
    public Long seed;
    /** Woher das Ergebnis stammt ("cracked", "/seed", "singleplayer"). */
    public String source = "";
    /** Gefundene untere 48 Bit, falls der volle Seed nicht bestimmbar war. */
    public final List<Long> structureSeeds = new ArrayList<>();
    /** Zaehlt jede Aenderung der Daten (der Cracker startet nur fuer neue Daten). */
    public int version;

    private SeedData(String server) {
        this.server = server;
        Arrays.fill(pillars, -1);
    }

    /**
     * Daten der aktuellen Welt: pro Server und Seed-Hash getrennt - so mischen sich auf Netzwerken (Lobby, Survival,
     * ...) die Funde verschiedener Welten nicht.
     */
    public static synchronized SeedData current() {
        load();
        Long hash = currentHash();
        String key = hash == null ? NovaNotify.serverId() : NovaNotify.serverId() + " #" + Long.toHexString(hash);
        SeedData data = ALL.computeIfAbsent(key, SeedData::new);
        data.hashedSeed = hash;
        return data;
    }

    /** Hash des Seeds aus dem Login-Paket, null wenn keiner (0 = Server verschweigt ihn). */
    private static Long currentHash() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        long hash = ((BiomeManagerAccessor) client.level.getBiomeManager()).nova_client$biomeZoomSeed();
        return hash == 0L ? null : hash;
    }

    /** Server-Adresse ohne den Hash-Teil (fuer die Anzeige). */
    public String serverName() {
        int index = server.indexOf(" #");
        return index < 0 ? server : server.substring(0, index);
    }

    // ---------------------------------------------------------------- Aendern

    /** Fuegt eine Struktur hinzu; false, wenn sie schon bekannt war. */
    public boolean add(StructureKind kind, int chunkX, int chunkZ, boolean manual) {
        for (Found found : structures) {
            if (found.kind() == kind && found.chunkX() == chunkX && found.chunkZ() == chunkZ) {
                return false;
            }
        }
        structures.add(new Found(kind, chunkX, chunkZ, manual));
        changed();
        return true;
    }

    public void remove(Found found) {
        if (structures.remove(found)) {
            changed();
        }
    }

    public int pillarsSeen() {
        int count = 0;
        for (int size : pillars) {
            if (size >= 0) {
                count++;
            }
        }
        return count;
    }

    public void clear() {
        structures.clear();
        Arrays.fill(pillars, -1);
        seed = null;
        source = "";
        structureSeeds.clear();
        changed();
    }

    public void changed() {
        version++;
        save();
    }

    /** Eingabe fuer den Cracker. */
    public SeedCracker.Input input() {
        List<SeedCracker.Structure> list = new ArrayList<>();
        List<long[]> treasures = new ArrayList<>();
        for (Found found : structures) {
            if (found.kind() == StructureKind.BURIED_TREASURE) {
                treasures.add(new long[]{found.chunkX(), found.chunkZ()});
            } else {
                list.add(new SeedCracker.Structure(found.chunkX(), found.chunkZ(), found.kind().salt,
                        found.kind().spacing, found.kind().separation));
            }
        }
        return new SeedCracker.Input(list, treasures, pillarsSeen() > 0 ? pillars.clone() : null, hashedSeed);
    }

    // ---------------------------------------------------------------- Datei

    private static String key(String server) {
        return server.replaceAll("[^A-Za-z0-9._:-]", "_");
    }

    private static synchronized void load() {
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
            System.err.println("[Nova] Could not load seed finder data: " + e.getMessage());
            return;
        }
        for (String name : properties.stringPropertyNames()) {
            if (!name.endsWith(".server")) {
                continue;
            }
            String prefix = name.substring(0, name.length() - "server".length());
            SeedData data = new SeedData(properties.getProperty(name));
            for (String entry : properties.getProperty(prefix + "structures", "").split(";")) {
                String[] parts = entry.split(",");
                if (parts.length < 3) {
                    continue;
                }
                StructureKind kind = StructureKind.byName(parts[0]);
                try {
                    if (kind != null) {
                        data.structures.add(new Found(kind, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                                parts.length > 3 && parts[3].equals("m")));
                    }
                } catch (NumberFormatException ignored) {
                    // Eintrag ueberspringen
                }
            }
            String[] pillars = properties.getProperty(prefix + "pillars", "").split(",");
            for (int i = 0; i < Math.min(10, pillars.length); i++) {
                try {
                    data.pillars[i] = Integer.parseInt(pillars[i].trim());
                } catch (NumberFormatException ignored) {
                    // bleibt -1
                }
            }
            data.hashedSeed = parseLong(properties.getProperty(prefix + "hash"));
            data.seed = parseLong(properties.getProperty(prefix + "seed"));
            data.source = properties.getProperty(prefix + "source", "");
            for (String value : properties.getProperty(prefix + "structureSeeds", "").split(",")) {
                Long parsed = parseLong(value);
                if (parsed != null) {
                    data.structureSeeds.add(parsed);
                }
            }
            ALL.put(data.server, data);
        }
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static synchronized void save() {
        Properties properties = new Properties();
        for (SeedData data : ALL.values()) {
            String prefix = "world." + key(data.server) + ".";
            properties.setProperty(prefix + "server", data.server);
            StringBuilder structures = new StringBuilder();
            for (Found found : data.structures) {
                if (!structures.isEmpty()) {
                    structures.append(';');
                }
                structures.append(found.kind().name()).append(',').append(found.chunkX()).append(',')
                        .append(found.chunkZ()).append(',').append(found.manual() ? "m" : "a");
            }
            properties.setProperty(prefix + "structures", structures.toString());
            StringBuilder pillars = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                pillars.append(i == 0 ? "" : ",").append(data.pillars[i]);
            }
            properties.setProperty(prefix + "pillars", pillars.toString());
            if (data.hashedSeed != null) {
                properties.setProperty(prefix + "hash", String.valueOf(data.hashedSeed));
            }
            if (data.seed != null) {
                properties.setProperty(prefix + "seed", String.valueOf(data.seed));
                properties.setProperty(prefix + "source", data.source);
            }
            StringBuilder seeds = new StringBuilder();
            for (int i = 0; i < Math.min(16, data.structureSeeds.size()); i++) {
                seeds.append(i == 0 ? "" : ",").append(data.structureSeeds.get(i));
            }
            properties.setProperty(prefix + "structureSeeds", seeds.toString());
        }
        try (OutputStream out = Files.newOutputStream(FILE)) {
            properties.store(out, "Nova Client - Seed Finder");
        } catch (IOException e) {
            System.err.println("[Nova] Could not save seed finder data: " + e.getMessage());
        }
    }
}
