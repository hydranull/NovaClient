package nova.nova_client.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Util;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Viele grosse Server (z.B. DonutSMP) zeigen Raenge nicht als Text, sondern als Bild-Zeichen aus ihrem
 * Resourcepack (ein Sonderzeichen, das als "ADMIN"-Plakette gezeichnet wird). Diese Klasse liest die Schriften aller
 * geladenen Resourcepacks und merkt sich, welches Zeichen welches Bild zeigt - der Dateiname des Bildes
 * ("ranks/sr_mod.png") verraet den Rang. {@link #describe} macht daraus lesbaren Text ("ranks sr mod").
 */
public final class RankGlyphs {
    private static final long CHECK_MS = 5000L;

    /** Schrift -> (Zeichen -> Bildname). */
    private static Map<Identifier, Map<Integer, String>> glyphs = Map.of();
    /** Schrift -> Schriften, auf die sie verweist ("reference"). */
    private static Map<Identifier, List<Identifier>> references = Map.of();
    private static int loadedPacks = -1;
    private static long lastCheck;

    private RankGlyphs() {
    }

    /** Lesbarer Text fuer alle Bild-Zeichen in der Komponente (leer, wenn keine vorkommen). */
    public static String describe(Component component) {
        if (component == null) {
            return "";
        }
        ensureLoaded();
        if (glyphs.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        component.visit((style, text) -> {
            Identifier font = fontOf(style);
            text.codePoints().forEach(codePoint -> {
                String name = lookup(font, codePoint, 0);
                if (name != null) {
                    out.append(' ').append(name).append(' ');
                }
            });
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static Identifier fontOf(Style style) {
        FontDescription font = style.getFont();
        return font instanceof FontDescription.Resource resource ? resource.id() : FontDescription.DEFAULT.id();
    }

    private static String lookup(Identifier font, int codePoint, int depth) {
        Map<Integer, String> map = glyphs.get(font);
        if (map != null) {
            String name = map.get(codePoint);
            if (name != null) {
                return name;
            }
        }
        if (depth < 4) {
            for (Identifier reference : references.getOrDefault(font, List.of())) {
                String name = lookup(reference, codePoint, depth + 1);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Laden

    /** Neu einlesen, sobald sich die Resourcepacks aendern (z.B. Server-Pack beim Betreten). */
    private static void ensureLoaded() {
        long now = Util.getMillis();
        if (now - lastCheck < CHECK_MS && loadedPacks != -1) {
            return;
        }
        lastCheck = now;
        Minecraft client = Minecraft.getInstance();
        int packs = client.getResourcePackRepository().getSelectedIds().hashCode();
        if (packs == loadedPacks) {
            return;
        }
        loadedPacks = packs;
        Map<Identifier, Map<Integer, String>> newGlyphs = new HashMap<>();
        Map<Identifier, List<Identifier>> newReferences = new HashMap<>();
        try {
            Map<Identifier, List<Resource>> fonts = client.getResourceManager()
                    .listResourceStacks("font", id -> id.getPath().endsWith(".json"));
            for (Map.Entry<Identifier, List<Resource>> entry : fonts.entrySet()) {
                String path = entry.getKey().getPath();
                Identifier font = Identifier.fromNamespaceAndPath(entry.getKey().getNamespace(),
                        path.substring("font/".length(), path.length() - ".json".length()));
                for (Resource resource : entry.getValue()) {
                    read(resource, font, newGlyphs, newReferences);
                }
            }
        } catch (RuntimeException ignored) {
            // kaputtes Resourcepack: dann eben ohne Bild-Raenge
        }
        glyphs = newGlyphs;
        references = newReferences;
    }

    private static void read(Resource resource, Identifier font, Map<Identifier, Map<Integer, String>> glyphs,
                             Map<Identifier, List<Identifier>> references) {
        try (Reader reader = resource.openAsReader()) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("providers")) {
                return;
            }
            for (JsonElement element : root.getAsJsonObject().getAsJsonArray("providers")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject provider = element.getAsJsonObject();
                String type = provider.has("type") ? provider.get("type").getAsString() : "";
                if (type.equals("reference") && provider.has("id")) {
                    Identifier target = Identifier.tryParse(provider.get("id").getAsString());
                    if (target != null) {
                        references.computeIfAbsent(font, key -> new ArrayList<>()).add(target);
                    }
                } else if (type.equals("bitmap") && provider.has("file") && provider.has("chars")) {
                    String name = imageName(provider.get("file").getAsString());
                    if (name == null) {
                        continue;
                    }
                    Map<Integer, String> map = glyphs.computeIfAbsent(font, key -> new HashMap<>());
                    JsonArray rows = provider.getAsJsonArray("chars");
                    for (JsonElement row : rows) {
                        row.getAsString().codePoints().filter(c -> c != 0 && c != ' ')
                                .forEach(c -> map.putIfAbsent(c, name));
                    }
                }
            }
        } catch (Exception ignored) {
            // einzelne kaputte Schrift ueberspringen
        }
    }

    /** "donut:ranks/sr_mod.png" -> "ranks sr mod"; Vanilla-Schriftbilder (ascii, ...) zaehlen nicht. */
    private static String imageName(String file) {
        Identifier id = Identifier.tryParse(file);
        if (id == null) {
            return null;
        }
        String path = id.getPath();
        if (id.getNamespace().equals("minecraft") && path.startsWith("font/")) {
            return null;
        }
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
