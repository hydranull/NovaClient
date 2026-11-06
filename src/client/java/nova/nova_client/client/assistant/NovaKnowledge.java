package nova.nova_client.client.assistant;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import nova.nova_client.client.ModInfo;
import nova.nova_client.client.Mods;
import nova.nova_client.client.mods.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wissen der Nova-KI: je Modul (nach Anzeigename) weitere Namen, Stichwoerter und Beschreibung auf
 * Deutsch/Tuerkisch/Englisch (assistant/knowledge.json aus tools/nova-ai). Findet Module im Satz - auch mit Tippfehlern - und sucht passende
 * Module zu einem Thema ("welches Modul hilft beim Basen finden").
 */
public final class NovaKnowledge {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/NovaAI");
    private static final Identifier FILE = Identifier.fromNamespaceAndPath("nova_client", "assistant/knowledge.json");

    /** Ein Modul mit allem, was die KI darueber weiss. */
    record Entry(Mod mod, List<List<String>> names, Set<String> keywords, Set<String> text, Map<String, String> about) {
        String about(String language) {
            String text = about.get(language);
            if (text == null || text.isEmpty()) {
                text = about.getOrDefault("en", ModInfo.description(mod));
            }
            return text == null ? "" : text;
        }
    }

    /** Fundstelle im Satz: Woerter [start, start + length). */
    record Match(Entry entry, int start, int length) {
    }

    private static List<Entry> entries;

    private NovaKnowledge() {
    }

    static List<Entry> entries() {
        if (entries == null) {
            entries = build();
        }
        return entries;
    }

    private static List<Entry> build() {
        JsonObject json = new JsonObject();
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isPresent()) {
            try (Reader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Could not load the Nova AI knowledge", e);
            }
        }
        List<Entry> list = new ArrayList<>();
        for (Mod mod : Mods.mods) {
            // nach Anzeigename: Klassennamen benennt ProGuard im Release um
            JsonObject info = json.has(mod.name) ? json.getAsJsonObject(mod.name) : new JsonObject();
            List<List<String>> names = new ArrayList<>();
            names.add(words(mod.name));
            if (info.has("aliases")) {
                for (JsonElement alias : info.getAsJsonArray("aliases")) {
                    List<String> w = words(alias.getAsString());
                    if (!w.isEmpty() && !names.contains(w)) {
                        names.add(w);
                    }
                }
            }
            Map<String, String> about = new HashMap<>();
            for (String language : new String[]{"de", "tr", "en"}) {
                if (info.has(language)) {
                    about.put(language, info.get(language).getAsString());
                }
            }
            Set<String> keywords = new HashSet<>();
            if (info.has("kw")) {
                for (String w : words(info.get("kw").getAsString())) {
                    keywords.add(stem(w));
                }
            }
            for (List<String> name : names) {
                for (String w : name) {
                    keywords.add(stem(w));
                }
            }
            Set<String> text = new HashSet<>();
            for (String value : about.values()) {
                for (String w : words(value)) {
                    if (w.length() > 3) {
                        text.add(stem(w));
                    }
                }
            }
            list.add(new Entry(mod, names, keywords, text, about));
        }
        return list;
    }

    /** Woerter wie NovaBrain.normalize, ohne die Sonderzeichen-Merker. */
    static List<String> words(String text) {
        List<String> words = NovaBrain.normalize(text);
        words.removeIf(w -> w.equals("xtrch") || w.equals("xdech"));
        return words;
    }

    static String stem(String word) {
        return word.length() > 5 ? word.substring(0, 5) : word;
    }

    // ---------------------------------------------------------------- Module im Satz finden

    /** Bestes Modul im Satz: exakter Name > Name ohne Leerzeichen > aehnlich geschrieben (Tippfehler). */
    static Match findModule(List<String> words) {
        Match best = null;
        float bestScore = 0.0F;
        for (Entry entry : entries()) {
            for (List<String> name : entry.names()) {
                String joined = String.join("", name);
                // exakte Wortfolge
                for (int i = 0; i + name.size() <= words.size(); i++) {
                    if (words.subList(i, i + name.size()).equals(name)) {
                        float score = 100.0F + joined.length();
                        if (score > bestScore) {
                            bestScore = score;
                            best = new Match(entry, i, name.size());
                        }
                    }
                }
                // 1-3 Woerter zusammengeschrieben bzw. aehnlich
                for (int n = 1; n <= 3; n++) {
                    for (int i = 0; i + n <= words.size(); i++) {
                        String window = String.join("", words.subList(i, i + n));
                        float score;
                        if (window.equals(joined)) {
                            score = 90.0F + joined.length();
                        } else if (joined.length() >= 5 && window.length() >= 4) {
                            float sim = similarity(window, joined);
                            score = sim >= 0.8F ? 40.0F + sim * joined.length() : 0.0F;
                        } else {
                            score = 0.0F;
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = new Match(entry, i, n);
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Dice-Aehnlichkeit ueber Buchstabenpaare (0..1). */
    static float similarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return a.equals(b) ? 1.0F : 0.0F;
        }
        Map<String, Integer> pairs = new HashMap<>();
        for (int i = 0; i + 2 <= a.length(); i++) {
            pairs.merge(a.substring(i, i + 2), 1, Integer::sum);
        }
        int common = 0;
        for (int i = 0; i + 2 <= b.length(); i++) {
            String p = b.substring(i, i + 2);
            Integer count = pairs.get(p);
            if (count != null && count > 0) {
                common++;
                pairs.put(p, count - 1);
            }
        }
        return 2.0F * common / (a.length() - 1 + b.length() - 1);
    }

    // ---------------------------------------------------------------- Suche nach Thema

    private static final Set<String> STOP = new HashSet<>(List.of(
            // Deutsch
            "welches", "welcher", "welche", "modul", "module", "hilft", "beim", "bei", "fur", "fuer", "gibt", "es",
            "ein", "eine", "einen", "was", "wie", "kann", "ich", "mit", "dem", "den", "der", "die", "das", "und",
            "zum", "zur", "gegen", "suche", "brauche", "nehme", "womit", "hast", "du", "zeigt", "will", "mir",
            "mal", "bitte", "nova", "hey", "von", "auf", "im", "in", "an", "aus",
            // Englisch
            "which", "what", "is", "there", "a", "an", "for", "with", "helps", "help", "module", "modules", "how",
            "can", "i", "need", "something", "should", "use", "best", "any", "shows", "want", "to", "the", "me",
            "please", "do", "my", "of", "on", "it",
            // Tuerkisch
            "hangi", "modul", "icin", "var", "mi", "mu", "ne", "kullanmaliyim", "yapmak", "bir", "lazim", "gosterir",
            "en", "iyi", "bana", "lutfen", "abi", "kanka", "ya", "ve", "bu", "nasil"));

    /** Bis zu drei Module, die zum Thema passen (Stichwoerter zaehlen mehr als die Beschreibung). */
    static List<Entry> search(List<String> words) {
        List<String> topic = new ArrayList<>();
        for (String w : words) {
            if (!STOP.contains(w) && w.length() > 1 && !w.startsWith("xtr") && !w.startsWith("xde")) {
                topic.add(stem(w));
            }
        }
        if (topic.isEmpty()) {
            return List.of();
        }
        Map<Entry, Float> scores = new HashMap<>();
        for (Entry entry : entries()) {
            float score = 0.0F;
            for (String t : topic) {
                if (entry.keywords().contains(t)) {
                    score += 3.0F;
                } else if (entry.text().contains(t)) {
                    score += 1.0F;
                }
            }
            if (score > 0.0F) {
                scores.put(entry, score);
            }
        }
        List<Entry> result = new ArrayList<>(scores.keySet());
        result.sort(Comparator.comparing((Entry e) -> -scores.get(e)).thenComparing(e -> e.mod().name));
        if (result.size() > 3) {
            float top = scores.get(result.get(0));
            result.removeIf(e -> scores.get(e) < top * 0.5F);
        }
        return result.subList(0, Math.min(3, result.size()));
    }
}
