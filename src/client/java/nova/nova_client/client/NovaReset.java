package nova.nova_client.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Properties;

/**
 * Kompletter Neuanfang: startet zum ersten Mal eine Nova-Version mit hoeherer {@link #EPOCH} als die gespeicherte,
 * werden alle Nova-Dateien der alten Version geloescht - Einstellungen, Tasten, Wegpunkte, Profile, Skins, eigene
 * Himmel, Logs und Anmelde-Daten - und Nova startet frisch. Danach steht die Epoche in config/nova_client.properties
 * ({@link ModConfig} schreibt sie bei jedem Speichern mit), es passiert also nur einmal.
 * <p>
 * Geloescht werden nur Nova-eigene Dateien (feste Liste unten). Welten, Schematics, Screenshots, Add-ons aus dem Nova
 * Hub und andere Mods bleiben, wie sie sind.
 */
public final class NovaReset {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Reset");
    /** Schluessel in nova_client.properties. */
    public static final String KEY = "nova.epoch";
    /** 2 = Neustart mit Beta 0.100 (Open Source). Fuer einen weiteren kompletten Neuanfang hochzaehlen. */
    public static final int EPOCH = 2;

    private NovaReset() {
    }

    /** Vor dem Laden der Einstellungen aufrufen. */
    public static void run() {
        Path config = FabricLoader.getInstance().getConfigDir();
        Path game = FabricLoader.getInstance().getGameDir();
        Path settings = config.resolve("nova_client.properties");
        if (epochOf(settings) >= EPOCH) {
            return;
        }
        List<Path> nova = List.of(
                settings,
                config.resolve("nova_client_waypoints.properties"),
                config.resolve("nova_client_skin.png"),
                config.resolve("nova_client_seedfinder.properties"),
                config.resolve("nova_client_spotify.properties"),
                config.resolve("nova_license.dat"),
                config.resolve("nova_client_profiles"),
                config.resolve("nova_client_player_bypass"),
                game.resolve("nova_client"));
        int removed = 0;
        for (Path path : nova) {
            if (Files.exists(path)) {
                delete(path);
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("Fresh start (epoch {}): removed {} old Nova files and folders", EPOCH, removed);
        }
    }

    /** Gespeicherte Epoche, 0 = keine (alte Version oder noch nie gestartet). */
    private static int epochOf(Path settings) {
        if (!Files.isRegularFile(settings)) {
            return 0;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(settings)) {
            properties.load(in);
            return Integer.parseInt(properties.getProperty(KEY, "0").trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    private static void delete(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Files.walkFileTree(path, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not remove {}", path, e);
        }
    }
}
