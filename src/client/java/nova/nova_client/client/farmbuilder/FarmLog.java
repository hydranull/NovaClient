package nova.nova_client.client.farmbuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Protokoll des Schematic Builders (nova_client/schematic_builder.log im Spielordner, bei jedem Start neu): warum ein Block
 * nicht klappt, was uebersprungen wird, wann er sich freigraebt ... - damit man Haenger hinterher nachvollziehen kann.
 */
public final class FarmLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final long MAX_BYTES = 20L * 1024L * 1024L;
    private static BufferedWriter writer;
    private static long bytes;

    private FarmLog() {
    }

    public static void start(String what) {
        stop();
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("nova_client");
            Files.createDirectories(dir);
            Path file = dir.resolve("schematic_builder.log");
            // Anhaengen (fruehere Laeufe bleiben lesbar); wird es zu gross, die alte Datei beiseitelegen
            if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                Files.move(file, dir.resolve("schematic_builder.old.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            bytes = Files.exists(file) ? Files.size(file) : 0L;
            writer.newLine();
            line("==================== START " + java.time.LocalDate.now() + " " + what);
        } catch (IOException e) {
            writer = null;
        }
    }

    public static void line(String text) {
        if (writer == null || bytes > MAX_BYTES) {
            return;
        }
        try {
            String s = LocalTime.now().format(TIME) + " " + text;
            writer.write(s);
            writer.newLine();
            writer.flush();
            bytes += s.length() + 1;
        } catch (IOException e) {
            writer = null;
        }
    }

    public static void stop() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // nichts zu tun
            }
        }
        writer = null;
    }
}
