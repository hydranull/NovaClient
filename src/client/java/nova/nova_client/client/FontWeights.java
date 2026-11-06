package nova.nova_client.client;

import org.lwjgl.CLongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.FT_Face;
import org.lwjgl.util.freetype.FreeType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

/**
 * Variable Schriften (eine Datei, Strichstaerke einstellbar) zeichnet FreeType ohne Einstellung in ihrer
 * Standard-Staerke - bei Montserrat, Nunito, Manrope oder Figtree ist das Thin/Light und in Minecraft viel zu duenn.
 * Hier wird die gewuenschte Staerke gesetzt (FontWeightMixin, bevor die Groesse gesetzt und Zeichen geladen werden):
 * fuer die Schnitte der Menue-Schrift ({@link NovaType}) Staerke und optische Groesse des Schnitts, fuer die
 * Custom-Font-Schriften die Staerke aus der Liste unten. Andere Achsen bleiben auf ihrem Standard.
 */
public final class FontWeights {
    /** Familienname (wie in der Schrift-Datei) -> Strichstaerke (400 = Regular, 500 = Medium). */
    private static final Map<String, Float> WEIGHTS = Map.of(
            "Montserrat", 500.0F,
            "Nunito", 500.0F,
            "Manrope", 500.0F,
            "Figtree", 500.0F,
            "Open Sans", 500.0F,
            "DM Sans", 500.0F,
            "Plus Jakarta Sans", 500.0F);

    private FontWeights() {
    }

    public static void apply(ByteBuffer font, FT_Face face) {
        try {
            if (!FreeType.FT_HAS_MULTIPLE_MASTERS(face)) {
                return;
            }
            // Variante einer Nova-Schrift mit eigenen Werten (wird gerade von FontManagerMixin erzeugt)?
            NovaFonts.Spec spec = NovaFonts.LOADING.get();
            float weight = spec != null && spec.weight() > 0.0F ? spec.weight()
                    : WEIGHTS.getOrDefault(face.family_nameString(), 0.0F);
            float optical = spec != null ? spec.opticalSize() : 0.0F;
            if (weight <= 0.0F && optical <= 0.0F) {
                return;
            }
            float[] defaults = new float[16];
            int[] tags = new int[16];
            int axes = readAxes(font, tags, defaults);
            if (axes <= 0) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                CLongBuffer coords = stack.mallocCLong(axes);
                for (int i = 0; i < axes; i++) {
                    float value = tags[i] == WGHT && weight > 0.0F ? weight
                            : tags[i] == OPSZ && optical > 0.0F ? optical : defaults[i];
                    coords.put(i, Math.round(value * 65536.0D));
                }
                FreeType.FT_Set_Var_Design_Coordinates(face, coords);
            }
        } catch (Throwable ignored) {
            // FreeType ohne Unterstuetzung fuer variable Schriften: dann eben die Standard-Staerke
        }
    }

    private static final int WGHT = ('w' << 24) | ('g' << 16) | ('h' << 8) | 't';
    private static final int OPSZ = ('o' << 24) | ('p' << 16) | ('s' << 8) | 'z';

    /** Achsen aus der fvar-Tabelle: Kennung und Standardwert je Achse (in Datei-Reihenfolge). */
    private static int readAxes(ByteBuffer source, int[] tags, float[] defaults) {
        ByteBuffer b = source.duplicate().order(ByteOrder.BIG_ENDIAN);
        int base = b.position();
        int tables = b.getShort(base + 4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = base + 12 + 16 * i;
            if (b.getInt(record) == (('f' << 24) | ('v' << 16) | ('a' << 8) | 'r')) {
                int offset = base + b.getInt(record + 8);
                int axesOffset = b.getShort(offset + 4) & 0xFFFF;
                int count = Math.min(tags.length, b.getShort(offset + 8) & 0xFFFF);
                int size = b.getShort(offset + 10) & 0xFFFF;
                for (int a = 0; a < count; a++) {
                    int axis = offset + axesOffset + a * size;
                    tags[a] = b.getInt(axis);
                    defaults[a] = b.getInt(axis + 8) / 65536.0F;
                }
                return count;
            }
        }
        return 0;
    }
}
