package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import org.joml.Matrix3x2fc;

import java.util.Map;

/**
 * Scharfe Nova-Schriften: TTF-Schriften werden in ein Pixel-Raster gezeichnet (oversample = Raster-Pixel je
 * GUI-Einheit). Passt das nicht genau zur echten Pixeldichte, muss die Grafikkarte das Raster ohne Glaettung
 * vergroessern/verkleinern - duenne Striche fallen dann zufaellig weg oder werden doppelt, die Schrift wirkt
 * abgehackt und verpixelt.
 * <p>
 * Pixeldichte = GUI-Scale x Skalierung, mit der der Text gezeichnet wird (z.B. GUI-Scale 2 und Titel mit 1.1 = 2.2).
 * Fuer ganze Dichten 1..8 gibt es fertige Varianten ("poppins_s3"), fuer alle anderen erzeugt FontManagerMixin bei
 * Bedarf eine (auf Hundertstel genau). So ist ein Raster-Pixel immer ein Bildschirm-Pixel. Alle Stellen, die
 * "nova_client:poppins" bzw. "nova_client:ui_body" benutzen, bleiben unveraendert.
 */
public final class NovaFonts {
    public static final int MAX_SCALE = 8;
    private static final float MIN_DENSITY = 0.5F;
    private static final float MAX_DENSITY = 12.0F;

    /**
     * Eine Nova-Schrift: Datei und Werte wie in ihrer JSON-Datei. weight/opticalSize stellen bei variablen Schriften
     * Strichstaerke und optische Groesse ein (0 = Standard der Datei, siehe {@link FontWeights}).
     */
    public record Spec(String name, String file, float size, float shiftX, float shiftY, float weight, float opticalSize) {
        public Spec(String name, String file, float size, float shiftX, float shiftY) {
            this(name, file, size, shiftX, shiftY, 0.0F, 0.0F);
        }

        public TrueTypeGlyphProviderDefinition definition(float oversample) {
            return new TrueTypeGlyphProviderDefinition(Identifier.fromNamespaceAndPath("nova_client", file), size,
                    oversample, new TrueTypeGlyphProviderDefinition.Shift(shiftX, shiftY), "");
        }
    }

    /** Alle Nova-Schriften nach Namen (Menue-Schriften aus {@link NovaType} und die von Custom Font). */
    private static final Map<String, Spec> SPECS = new java.util.HashMap<>();

    /** Schrift, deren Variante gerade erzeugt wird (FontManagerMixin) - FontWeights liest daraus die Strichstaerke. */
    public static final ThreadLocal<Spec> LOADING = new ThreadLocal<>();

    static {
        for (CustomFonts.Entry entry : CustomFonts.ALL) {
            SPECS.put(entry.spec().name(), entry.spec());
        }
        for (NovaType.Face face : NovaType.Face.values()) {
            SPECS.put(face.spec.name(), face.spec);
        }
    }

    /** Gerade wird der Effekt-Glyph geholt (CachedFontProviderMixin) - dann keine Custom-Font-Ersetzung. */
    public static volatile boolean effectLookup;

    /** Dichte des Textes, der gerade vorbereitet wird (gesetzt von GuiTextRenderStateMixin), sonst NaN. */
    private static float drawDensity = Float.NaN;

    private NovaFonts() {
    }

    /** Ist font eine Nova-Schrift, die je nach Dichte getauscht wird? */
    public static Spec spec(Identifier font) {
        return "nova_client".equals(font.getNamespace()) ? SPECS.get(font.getPath()) : null;
    }

    public static boolean isSharpened(FontDescription description) {
        return description instanceof FontDescription.Resource resource && spec(resource.id()) != null;
    }

    /** Waehrend ein Text mit dieser Pose vorbereitet wird: seine echte Pixeldichte merken. */
    public static void beginText(Matrix3x2fc pose) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) {
            return;
        }
        float scale = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01());
        drawDensity = mc.getWindow().getGuiScale() * scale;
    }

    public static void endText() {
        drawDensity = Float.NaN;
    }

    /**
     * Text-Anfang (x) so verschieben, dass er auf einem ganzen Bildschirm-Pixel landet. Sonst liegt das Raster der
     * Zeichen z.B. einen halben Pixel neben den Bildschirm-Pixeln (skalierte HUDs, mittig gesetzte Zahlen) und die
     * Grafikkarte verdoppelt bzw. verschluckt einzelne Pixelspalten - der Text wirkt abgehackt. Nur fuer ungedrehte
     * Posen; die Verschiebung ist kleiner als ein halber Bildschirm-Pixel.
     */
    public static float snapX(Matrix3x2fc pose, float x, float y) {
        if (!axisAligned(pose) || pose.m00() == 0.0F) {
            return x;
        }
        int gui = guiScale();
        float device = (pose.m00() * x + pose.m20()) * gui;
        return x + (Math.round(device) - device) / (pose.m00() * gui);
    }

    /** Wie {@link #snapX}, fuer die Hoehe. */
    public static float snapY(Matrix3x2fc pose, float x, float y) {
        if (!axisAligned(pose) || pose.m11() == 0.0F) {
            return y;
        }
        int gui = guiScale();
        float device = (pose.m11() * y + pose.m21()) * gui;
        return y + (Math.round(device) - device) / (pose.m11() * gui);
    }

    private static boolean axisAligned(Matrix3x2fc pose) {
        return pose.m01() == 0.0F && pose.m10() == 0.0F;
    }

    private static int guiScale() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 1 : Math.max(1, mc.getWindow().getGuiScale());
    }

    /** Raster-Dichte fuer die naechste Abfrage, auf Hundertstel gerundet (NaN = keine Nova-Schrift moeglich). */
    public static float density() {
        float density = drawDensity;
        if (Float.isNaN(density)) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null) {
                return Float.NaN;
            }
            density = mc.getWindow().getGuiScale();
        }
        // Genau treffen: schon 2 % daneben (z.B. 2.25 statt 2.2) laesst jede ~50. Pixelspalte doppelt oder wegfallen
        density = Math.round(density * 100.0F) / 100.0F;
        return Math.max(MIN_DENSITY, Math.min(MAX_DENSITY, density));
    }

    // Namen der Varianten einmal bauen: Nova-Schriften umgehen Minecrafts Schrift-Cache, die Abfrage laeuft pro Zeichen
    private static final Map<Spec, Identifier[]> FIXED_IDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Spec, Map<Integer, Identifier>> DYNAMIC_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fertige Variante aus den JSON-Dateien (ganze Dichte 1..8). */
    public static Identifier fixedVariant(Spec spec, int scale) {
        int s = Math.max(1, Math.min(MAX_SCALE, scale));
        Identifier[] ids = FIXED_IDS.computeIfAbsent(spec, k -> new Identifier[MAX_SCALE + 1]);
        Identifier id = ids[s];
        if (id == null) {
            id = Identifier.fromNamespaceAndPath("nova_client", spec.name() + "_s" + s);
            ids[s] = id;
        }
        return id;
    }

    private static final Map<Spec, Identifier> BASE_IDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Grund-Datei der Schrift (name.json) - Vorlage fuer erzeugte Varianten, wenn es keine fertige gibt. */
    public static Identifier baseVariant(Spec spec) {
        return BASE_IDS.computeIfAbsent(spec, k -> Identifier.fromNamespaceAndPath("nova_client", k.name()));
    }

    /** Erzeugte Variante fuer eine krumme Dichte (Name in Hundertsteln, z.B. "clean_d220" = 2.2). */
    public static Identifier dynamicVariant(Spec spec, float density) {
        return DYNAMIC_IDS.computeIfAbsent(spec, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(Math.round(density * 100.0F),
                        hundredths -> Identifier.fromNamespaceAndPath("nova_client", spec.name() + "_d" + hundredths));
    }

    /**
     * Textur einer Nova-Schrift ("nova_client:font_inter/0", "nova_client:clean_d225/0", ...)? Die wird geglaettet
     * gezeichnet (FontTextureMixin): liegt das Raster genau auf den Bildschirm-Pixeln, ist das Ergebnis dasselbe wie
     * ohne Glaettung (gestochen scharf); wo es nicht passt (Ersatz-Groesse, waehrend Animationen, Schrift in der Welt),
     * bleibt der Text glatt statt pixelig und abgehackt. Minecrafts Pixel-Schrift bleibt ungeglaettet.
     */
    public static boolean isNovaTexture(String textureName) {
        return textureName != null && textureName.startsWith("nova_client:");
    }

    public static boolean isWhole(float density) {
        return density == Math.rint(density) && density >= 1.0F && density <= MAX_SCALE;
    }
}
