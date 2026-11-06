package nova.nova_client.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Eigene Bilder in den Sky-VFX-Himmeln: PNGs aus .minecraft/nova_client/sky lassen sich in jeden Himmel setzen
 * (z.B. ein Logo neben das Schwarze Loch). Jeder Himmel hat seine eigene Liste von Bildern mit Richtung, Hoehe und
 * Groesse; gezeichnet werden sie von {@link SkyVfx} nach dem Himmel, platziert im {@link SkyImageEditor}.
 * <p>
 * Richtung wie die Menue-Vorschau: Yaw 0 = Sueden (+Z), 90 = Osten (+X), 180 = Norden; Pitch nach oben positiv.
 */
public final class SkyImages {
    // absolut + normalisiert: manche Launcher geben den Spielordner relativ an (z.B. "."), dann schluege der
    // startsWith-Test in load() fehl und kein Bild wuerde geladen
    public static final Path FOLDER = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize()
            .resolve("nova_client").resolve("sky");
    /** Groesste Kantenlaenge auf der Grafikkarte (Bilder am Himmel brauchen nicht mehr). */
    private static final int MAX_SIZE = 2048;
    public static final float MIN_SIZE = 3.0F;
    public static final float MAX_SIZE_DEGREES = 160.0F;
    public static final int MAX_PER_SKY = 24;

    /** Ein Bild in einem Himmel. */
    public static final class Placement {
        public String file;
        public float yaw;
        public float pitch;
        /** Breite in Grad. */
        public float size;

        public Placement(String file, float yaw, float pitch, float size) {
            this.file = file;
            this.yaw = yaw;
            this.pitch = pitch;
            this.size = size;
        }
    }

    private record Loaded(DynamicTexture texture, int width, int height) {
    }

    private static final Map<SkyStyle, List<Placement>> PLACED = new EnumMap<>(SkyStyle.class);
    private static final Map<String, Loaded> TEXTURES = new HashMap<>();
    /** Dateien, die sich nicht laden liessen (nicht jeden Frame neu versuchen). */
    private static final Set<String> FAILED = new HashSet<>();
    private static List<String> files;
    private static String error = "";

    private SkyImages() {
    }

    /** Bilder dieses Himmels (veraenderbar). */
    public static List<Placement> placed(SkyStyle style) {
        return PLACED.computeIfAbsent(style, s -> new ArrayList<>());
    }

    // ---------------------------------------------------------------- Dateien

    /** PNGs im Ordner (sortiert); der Ordner wird bei Bedarf angelegt. */
    public static List<String> files() {
        if (files == null) {
            files = scan();
        }
        return files;
    }

    private static List<String> scan() {
        List<String> result = new ArrayList<>();
        try {
            Files.createDirectories(FOLDER);
            try (Stream<Path> stream = Files.list(FOLDER)) {
                stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".png"))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(result::add);
            }
            error = "";
        } catch (IOException e) {
            error = "Could not read the folder: " + e.getMessage();
        }
        return result;
    }

    /** Ordner neu einlesen und alle Bilder neu laden (z.B. nach dem Ersetzen einer Datei). */
    public static void reload() {
        files = scan();
        for (Loaded loaded : TEXTURES.values()) {
            loaded.texture().close();
        }
        TEXTURES.clear();
        FAILED.clear();
    }

    public static void openFolder() {
        files();
        Compat.openPath(FOLDER);
    }

    public static String error() {
        return error;
    }

    // ---------------------------------------------------------------- Texturen

    /** Textur einer Datei (laedt beim ersten Aufruf, nur im Render-Thread), oder null. */
    public static DynamicTexture texture(String file) {
        Loaded loaded = load(file);
        return loaded == null ? null : loaded.texture();
    }

    /** Breite / Hoehe des Bildes (1, solange es nicht geladen ist). */
    public static float aspect(String file) {
        Loaded loaded = load(file);
        return loaded == null || loaded.height() == 0 ? 1.0F : loaded.width() / (float) loaded.height();
    }

    private static Loaded load(String file) {
        Loaded loaded = TEXTURES.get(file);
        if (loaded != null || FAILED.contains(file)) {
            return loaded;
        }
        Path path = FOLDER.resolve(file).normalize();
        if (!path.startsWith(FOLDER) || !Files.isRegularFile(path)) {
            // z.B. Einstellungen von einem anderen PC: das Bild selbst liegt dort nicht im Ordner
            error = "Missing image " + file + " - copy it into .minecraft/nova_client/sky";
            FAILED.add(file);
            return null;
        }
        try (InputStream in = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(in);
            if (image.getWidth() > MAX_SIZE || image.getHeight() > MAX_SIZE) {
                NativeImage smaller = shrink(image);
                image.close();
                image = smaller;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            DynamicTexture texture = new DynamicTexture(() -> "nova sky image " + file, image);
            texture.upload();
            loaded = new Loaded(texture, width, height);
            TEXTURES.put(file, loaded);
            return loaded;
        } catch (Exception e) {
            error = "Could not load " + file + ": " + e.getMessage();
            FAILED.add(file);
            return null;
        }
    }

    /** Verkleinert auf hoechstens MAX_SIZE (Mittelwert ueber die zusammengefassten Pixel). */
    private static NativeImage shrink(NativeImage image) {
        float scale = MAX_SIZE / (float) Math.max(image.getWidth(), image.getHeight());
        int w = Math.max(1, Math.round(image.getWidth() * scale));
        int h = Math.max(1, Math.round(image.getHeight() * scale));
        NativeImage result = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            int y0 = y * image.getHeight() / h;
            int y1 = Math.max(y0 + 1, (y + 1) * image.getHeight() / h);
            for (int x = 0; x < w; x++) {
                int x0 = x * image.getWidth() / w;
                int x1 = Math.max(x0 + 1, (x + 1) * image.getWidth() / w);
                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int argb = image.getPixel(sx, sy);
                        a += argb >>> 24;
                        r += argb >> 16 & 0xFF;
                        g += argb >> 8 & 0xFF;
                        b += argb & 0xFF;
                        n++;
                    }
                }
                result.setPixel(x, y, (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n));
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- Speichern

    /** Bilder eines Himmels als Text: "datei|yaw|pitch|groesse;..." (Dateiname URL-kodiert). */
    public static String save(SkyStyle style) {
        StringBuilder text = new StringBuilder();
        for (Placement p : placed(style)) {
            if (!text.isEmpty()) {
                text.append(';');
            }
            text.append(URLEncoder.encode(p.file, StandardCharsets.UTF_8)).append('|')
                    .append(String.format(Locale.ROOT, "%.2f|%.2f|%.2f", p.yaw, p.pitch, p.size));
        }
        return text.toString();
    }

    public static void load(SkyStyle style, String text) {
        List<Placement> list = placed(style);
        list.clear();
        if (text == null || text.isBlank()) {
            return;
        }
        for (String entry : text.split(";")) {
            String[] parts = entry.split("\\|");
            if (parts.length != 4 || list.size() >= MAX_PER_SKY) {
                continue;
            }
            try {
                list.add(new Placement(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        Float.parseFloat(parts[1]), Math.max(-89.0F, Math.min(89.0F, Float.parseFloat(parts[2]))),
                        Math.max(MIN_SIZE, Math.min(MAX_SIZE_DEGREES, Float.parseFloat(parts[3])))));
            } catch (IllegalArgumentException ignored) {
                // Eintrag ueberspringen
            }
        }
    }
}
