package nova.nova_client.client.assistant;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Die kleine Nova-KI: ein neuronales Netz (gehashte Wort-/Buchstaben-Merkmale -> 96 Neuronen -> Absicht + Sprache),
 * trainiert mit tools/nova-ai/train.py. Vorverarbeitung muss genau wie dort sein (normalize/features).
 */
public final class NovaBrain {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/NovaAI");
    private static final Identifier FILE = Identifier.fromNamespaceAndPath("nova_client", "assistant/brain.bin");

    /** Ergebnis: Absicht (z.B. "what"), Sicherheit 0..1, Sprache ("de", "tr", "en") mit ihrer Sicherheit. */
    public record Thought(String intent, float confidence, String language, float languageConfidence) {
    }

    private static NovaBrain instance;
    private static boolean failed;

    private final int dim;
    private final int hidden;
    private final String[] intents;
    private final String[] languages;
    private final float[] w1;
    private final float[] b1;
    private final float[] w2;
    private final float[] b2;
    private final float[] w3;
    private final float[] b3;

    private NovaBrain(int dim, int hidden, String[] intents, String[] languages, float[] w1, float[] b1, float[] w2,
                      float[] b2, float[] w3, float[] b3) {
        this.dim = dim;
        this.hidden = hidden;
        this.intents = intents;
        this.languages = languages;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
        this.w3 = w3;
        this.b3 = b3;
    }

    /** Geladenes Netz oder null (Datei fehlt/kaputt - dann antwortet der einfache Schluesselwort-Modus). */
    public static NovaBrain get() {
        if (instance == null && !failed) {
            try {
                instance = load();
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Could not load the Nova AI", e);
                failed = true;
            }
        }
        return instance;
    }

    public Thought think(List<String> words) {
        Map<Integer, Float> features = features(words, dim);
        float[] h = b1.clone();
        for (Map.Entry<Integer, Float> e : features.entrySet()) {
            int row = e.getKey() * hidden;
            float v = e.getValue();
            for (int j = 0; j < hidden; j++) {
                h[j] += v * w1[row + j];
            }
        }
        for (int j = 0; j < hidden; j++) {
            h[j] = Math.max(0.0F, h[j]);
        }
        float[] intent = softmax(layer(h, w2, b2, intents.length));
        float[] language = softmax(layer(h, w3, b3, languages.length));
        int bi = argmax(intent);
        int bl = argmax(language);
        return new Thought(intents[bi], intent[bi], languages[bl], language[bl]);
    }

    private float[] layer(float[] h, float[] w, float[] b, int out) {
        float[] z = b.clone();
        for (int j = 0; j < hidden; j++) {
            if (h[j] == 0.0F) {
                continue;
            }
            for (int k = 0; k < out; k++) {
                z[k] += h[j] * w[j * out + k];
            }
        }
        return z;
    }

    private static float[] softmax(float[] z) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : z) {
            max = Math.max(max, v);
        }
        float sum = 0.0F;
        float[] out = new float[z.length];
        for (int i = 0; i < z.length; i++) {
            out[i] = (float) Math.exp(z[i] - max);
            sum += out[i];
        }
        for (int i = 0; i < z.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    private static int argmax(float[] v) {
        int best = 0;
        for (int i = 1; i < v.length; i++) {
            if (v[i] > v[best]) {
                best = i;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- Vorverarbeitung (wie train.py)

    /** Kleinschreiben, tuerkische/deutsche Sonderzeichen merken (xtrch/xdech) und falten, nur a-z0-9 als Woerter. */
    public static List<String> normalize(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        boolean turkish = false;
        boolean german = false;
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            switch (c) {
                case 'ı', 'ş', 'ğ', 'ç' -> turkish = true;
                case 'ä', 'ß' -> german = true;
                default -> {
                }
            }
            String folded = fold(c);
            for (int k = 0; k < folded.length(); k++) {
                char f = folded.charAt(k);
                b.append((f >= 'a' && f <= 'z') || (f >= '0' && f <= '9') ? f : ' ');
            }
        }
        List<String> words = new ArrayList<>();
        for (String w : b.toString().trim().split("\\s+")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        if (turkish) {
            words.add("xtrch");
        }
        if (german) {
            words.add("xdech");
        }
        return words;
    }

    private static String fold(char c) {
        return switch (c) {
            case 'ä', 'â', 'à' -> "a";
            case 'ö' -> "o";
            case 'ü', 'û' -> "u";
            case 'ß' -> "ss";
            case 'ı', 'î' -> "i";
            case 'ş' -> "s";
            case 'ç' -> "c";
            case 'ğ' -> "g";
            case 'é', 'è', 'ê' -> "e";
            default -> String.valueOf(c);
        };
    }

    private static Map<Integer, Float> features(List<String> words, int dim) {
        Map<Integer, Float> feats = new LinkedHashMap<>();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            add(feats, "w:" + w, dim);
            if (i + 1 < words.size()) {
                add(feats, "b:" + w + "_" + words.get(i + 1), dim);
            }
            if (w.equals("xmod") || w.equals("xcat") || w.equals("xtrch") || w.equals("xdech") || w.equals("xcol")) {
                continue;
            }
            String g = "<" + w + ">";
            for (int n = 3; n <= 4; n++) {
                for (int k = 0; k + n <= g.length(); k++) {
                    add(feats, "c:" + g.substring(k, k + n), dim);
                }
            }
        }
        double norm = 0.0D;
        for (float v : feats.values()) {
            norm += v * v;
        }
        float scale = norm > 0.0D ? (float) (1.0D / Math.sqrt(norm)) : 1.0F;
        feats.replaceAll((k, v) -> v * scale);
        return feats;
    }

    private static void add(Map<Integer, Float> feats, String key, int dim) {
        int h = 0x811C9DC5;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i) & 0xFF;
            h *= 0x01000193;
        }
        int index = (int) (Integer.toUnsignedLong(h) % dim);
        feats.merge(index, 1.0F, Float::sum);
    }

    // ---------------------------------------------------------------- Laden

    private static NovaBrain load() throws IOException {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FILE);
        if (resource.isEmpty()) {
            throw new IOException("missing " + FILE);
        }
        try (InputStream raw = resource.get().open();
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {
            if (in.readInt() != 0x4E414931) { // "NAI1"
                throw new IOException("bad brain file");
            }
            int dim = in.readInt();
            int hidden = in.readInt();
            int nIntents = in.readInt();
            int nLanguages = in.readInt();
            String[] intents = new String[nIntents];
            for (int i = 0; i < nIntents; i++) {
                intents[i] = in.readUTF();
            }
            String[] languages = new String[nLanguages];
            for (int i = 0; i < nLanguages; i++) {
                languages[i] = in.readUTF();
            }
            float[] w1 = new float[dim * hidden];
            for (int i = 0; i < w1.length; i++) {
                w1[i] = Float.float16ToFloat(in.readShort());
            }
            float[] b1 = floats(in, hidden);
            float[] w2 = floats(in, hidden * nIntents);
            float[] b2 = floats(in, nIntents);
            float[] w3 = floats(in, hidden * nLanguages);
            float[] b3 = floats(in, nLanguages);
            LOGGER.info("Nova AI loaded ({} intents)", nIntents);
            return new NovaBrain(dim, hidden, intents, languages, w1, b1, w2, b2, w3, b3);
        }
    }

    private static float[] floats(DataInputStream in, int count) throws IOException {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = in.readFloat();
        }
        return out;
    }
}
