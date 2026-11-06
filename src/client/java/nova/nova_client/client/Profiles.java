package nova.nova_client.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import nova.nova_client.client.mods.BlockEspMod;
import nova.nova_client.client.mods.Mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Mod-Profile: speichern welche Mods an sind, ihre Tasten und die BlockESP-Auswahl.
 * Jedes Profil ist eine Datei in config/nova_client_profiles/NAME.properties.
 * (Design/Theme und Macros bleiben global und gelten fuer alle Profile.)
 */
public final class Profiles {
    public static final int MAX_NAME_LENGTH = 24;
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("nova_client_profiles");
    private static final String EXTENSION = ".properties";

    /** Name des zuletzt geladenen/gespeicherten Profils ("" = keins). */
    public static String current = "";

    /** Kurzinfo fuer die Liste. */
    public record Info(String name, long modified, List<String> activeMods, int keybinds, int espBlocks) {
    }

    private Profiles() {
    }

    /** Erlaubte Zeichen fuer Profilnamen (auch als Dateiname sicher). */
    public static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9 _\\-]", "").trim();
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH).trim() : cleaned;
    }

    private static Path file(String name) {
        return DIR.resolve(name + EXTENSION);
    }

    public static boolean exists(String name) {
        return Files.exists(file(name));
    }

    // ---------------------------------------------------------------- Liste

    public static List<Info> list() {
        List<Info> result = new ArrayList<>();
        if (!Files.isDirectory(DIR)) {
            return result;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR, "*" + EXTENSION)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - EXTENSION.length());
                Properties properties = read(path);
                if (properties == null) {
                    continue;
                }
                List<String> active = new ArrayList<>();
                int keybinds = 0;
                for (Mod mod : Mods.mods) {
                    String id = ModConfig.ids().get(mod);
                    if (Boolean.parseBoolean(properties.getProperty("mod." + id + ".active"))) {
                        active.add(mod.name);
                    }
                    if (properties.getProperty("mod." + id + ".key") != null) {
                        keybinds++;
                    }
                }
                String blocks = properties.getProperty("blockesp.blocks", "");
                int espBlocks = blocks.isBlank() ? 0 : blocks.split(",").length;
                result.add(new Info(name, Files.getLastModifiedTime(path).toMillis(), active, keybinds, espBlocks));
            }
        } catch (IOException e) {
            System.err.println("[Nova] Could not list profiles: " + e.getMessage());
        }
        result.sort(Comparator.comparing(Info::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static Properties read(Path path) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
            ModConfig.migrateRenamed(properties);
            return properties;
        } catch (IOException e) {
            System.err.println("[Nova] Could not read profile: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- Speichern / Laden

    /** Speichert den aktuellen Stand aller Mods unter diesem Namen (ueberschreibt ein vorhandenes Profil). */
    public static boolean save(String name) {
        String clean = sanitize(name);
        if (clean.isEmpty()) {
            return false;
        }
        Properties properties = new Properties();
        for (Map.Entry<Mod, String> entry : ModConfig.ids().entrySet()) {
            Mod mod = entry.getKey();
            properties.setProperty("mod." + entry.getValue() + ".active", String.valueOf(mod.active));
            if (mod.hasKey()) {
                properties.setProperty("mod." + entry.getValue() + ".key", String.valueOf(mod.keyCode));
            }
            mod.saveSettings(properties, "mod." + entry.getValue() + ".");
        }
        properties.setProperty("blockesp.blocks", String.join(",", BlockEspMod.selectedIds()));
        try {
            Files.createDirectories(DIR);
            try (OutputStream out = Files.newOutputStream(file(clean))) {
                properties.store(out, "Nova Client profile: " + clean);
            }
        } catch (IOException e) {
            System.err.println("[Nova] Could not save profile: " + e.getMessage());
            return false;
        }
        current = clean;
        ModConfig.save();
        return true;
    }

    /** Laedt ein Profil: schaltet Mods an/aus, setzt Tasten und BlockESP-Auswahl. */
    public static boolean load(String name) {
        Path path = file(name);
        if (!Files.exists(path)) {
            return false;
        }
        Properties properties = read(path);
        if (properties == null) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        for (Map.Entry<Mod, String> entry : ModConfig.ids().entrySet()) {
            Mod mod = entry.getKey();
            String id = entry.getValue();
            String key = properties.getProperty("mod." + id + ".key");
            try {
                mod.keyCode = key == null ? Mod.NO_KEY : Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                mod.keyCode = Mod.NO_KEY;
            }
            mod.loadSettings(properties, "mod." + id + ".");
            boolean active = Boolean.parseBoolean(properties.getProperty("mod." + id + ".active"));
            if (mod.active != active) {
                mod.toggle(client);
            }
        }
        String blocks = properties.getProperty("blockesp.blocks");
        if (blocks != null) {
            BlockEspMod.loadSelectedIds(blocks.isBlank() ? List.of() : Arrays.asList(blocks.split(",")));
        }
        current = name;
        ModConfig.save();
        return true;
    }

    public static boolean delete(String name) {
        try {
            boolean deleted = Files.deleteIfExists(file(name));
            if (deleted && name.equals(current)) {
                current = "";
                ModConfig.save();
            }
            return deleted;
        } catch (IOException e) {
            System.err.println("[Nova] Could not delete profile: " + e.getMessage());
            return false;
        }
    }

    public static boolean rename(String oldName, String newName) {
        String clean = sanitize(newName);
        if (clean.isEmpty() || clean.equals(oldName) || exists(clean)) {
            return false;
        }
        try {
            Files.move(file(oldName), file(clean));
        } catch (IOException e) {
            System.err.println("[Nova] Could not rename profile: " + e.getMessage());
            return false;
        }
        if (oldName.equals(current)) {
            current = clean;
            ModConfig.save();
        }
        return true;
    }
}
