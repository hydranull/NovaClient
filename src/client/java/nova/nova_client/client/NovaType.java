package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Die Schrift der Nova-Menues: Geist (Vercel, OFL) fuer die Oberflaeche und Geist Mono fuer Werte, Tasten und Zahlen -
 * in wenigen festen Schnitten (Groesse + Strichstaerke je Schnitt). Text wird nie skaliert: jede Groesse ist ein
 * eigener Schnitt, den NovaFonts genau in der echten Pixeldichte rastern laesst und der auf ganzen Bildschirm-Pixeln
 * steht. So ist jede Zeile gestochen scharf - ohne Treppen, ohne Unschaerfe, ohne verschmierten Schatten.
 */
public final class NovaType {
    /** Minecraft setzt die Grundlinie 7 GUI-Pixel unter den Text-Anfang (GlyphBitmap.getTop). */
    public static final int BASELINE = 7;
    /** Hoehe der Grossbuchstaben von Geist (und Geist Mono) je Em. */
    private static final float CAP_HEIGHT = 0.71F;
    private static final String SANS = "geist.ttf";
    private static final String MONO_FILE = "geist_mono.ttf";

    public enum Face {
        /** Klein: Zaehler, Hinweise, Sektionen. */
        CAPTION("ui_caption", SANS, 7.0F, 500.0F),
        /** Text: Modulnamen, Beschriftungen, Knoepfe. */
        BODY("ui_body", SANS, 8.5F, 450.0F),
        /** Hervorgehoben in Textgroesse: Kategorien, gewaehlte Eintraege. */
        STRONG("ui_strong", SANS, 8.5F, 600.0F),
        /** Ueberschriften von Fenstern. */
        HEADING("ui_heading", SANS, 11.0F, 600.0F),
        /** Gross: Eintraege des Titelbildschirms. */
        TITLE("ui_title", SANS, 14.0F, 600.0F),
        /** Wortmarke "nova". */
        DISPLAY("ui_display", SANS, 30.0F, 700.0F),
        /** Werte, Tasten und Zahlen (Ziffern gleich breit, nichts springt). */
        MONO("ui_mono", MONO_FILE, 7.5F, 500.0F);

        public final NovaFonts.Spec spec;
        public final Style style;
        /** Hoehe der Grossbuchstaben in GUI-Pixeln. */
        public final float cap;

        Face(String id, String file, float size, float weight) {
            this.spec = new NovaFonts.Spec(id, file, size, 0.0F, 0.0F, weight, 0.0F);
            this.style = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("nova_client", id)));
            this.cap = size * CAP_HEIGHT;
        }
    }

    private static final int FACES = Face.values().length;
    /** Gemessene Breiten je Schnitt (Menues messen jedes Bild dieselben Texte). Haengen leicht vom GUI-Scale ab. */
    private static final Map<String, Integer>[] WIDTHS = maps();
    /** Fertige Text-Komponenten je Schnitt: Minecraft merkt sich darin die Zeichenfolge, nichts wird neu gebaut. */
    private static final Map<String, Component>[] COMPONENTS = maps();
    /** Schon gekuerzte Texte je Schnitt (letzte zwei Breiten je Text). */
    private static final Map<String, Cut>[] CUTS = maps();
    private static final int MAX_ENTRIES = 4096;
    /** GUI-Scale und NameProtect-Zustand, fuer die die Caches gelten. */
    private static int widthsScale;

    /** Gekuerzte Fassungen eines Textes fuer bis zu zwei Breiten. */
    private static final class Cut {
        int width = -1;
        String text;
        int width2 = -1;
        String text2;
    }

    private NovaType() {
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V>[] maps() {
        Map<String, V>[] maps = new Map[FACES];
        for (int i = 0; i < maps.length; i++) {
            maps[i] = new HashMap<>();
        }
        return maps;
    }

    /** Ist spec ein Schnitt der Menue-Schrift? Die werden sofort in der passenden Dichte erzeugt (FontManagerMixin). */
    public static boolean isUi(NovaFonts.Spec spec) {
        for (Face face : Face.values()) {
            if (face.spec == spec) {
                return true;
            }
        }
        return false;
    }

    /** Neue Text-Komponente im Schnitt (z.B. fuer Textfelder). */
    public static Component styled(Face face, String text) {
        return Component.literal(text).setStyle(face.style);
    }

    /** Gemerkte Text-Komponente im Schnitt (fuers Zeichnen: wird nicht jedes Bild neu angelegt). */
    public static Component component(Face face, String text) {
        Map<String, Component> map = COMPONENTS[face.ordinal()];
        Component cached = map.get(text);
        if (cached == null) {
            if (map.size() >= MAX_ENTRIES) {
                map.clear();
            }
            cached = styled(face, text);
            map.put(text, cached);
        }
        return cached;
    }

    public static int width(Font font, Face face, String text) {
        if (text.isEmpty()) {
            return 0;
        }
        validate();
        Map<String, Integer> map = WIDTHS[face.ordinal()];
        Integer cached = map.get(text);
        if (cached == null) {
            if (map.size() >= MAX_ENTRIES) {
                map.clear();
            }
            cached = font.width(component(face, text));
            map.put(text, cached);
        }
        return cached;
    }

    /** Caches verwerfen, wenn sich GUI-Scale oder NameProtect/Fake Media geaendert haben (andere Breiten). */
    private static void validate() {
        int scale = Minecraft.getInstance().getWindow().getGuiScale()
                | (nova.nova_client.client.mods.NameProtectMod.isEnabled() ? 1 << 8 : 0)
                | (nova.nova_client.client.mods.FakeMediaMod.isEnabled() ? 1 << 9 : 0);
        if (scale != widthsScale) {
            clearCaches();
            widthsScale = scale;
        }
    }

    /** Text ohne Schatten; fast unsichtbarer Text wird uebersprungen (kein Aufblitzen beim Einblenden). */
    public static void draw(GuiGraphicsExtractor g, Font font, Face face, String text, int x, int y, int color) {
        draw(g, font, face, text, x, y, color, false);
    }

    public static void draw(GuiGraphicsExtractor g, Font font, Face face, String text, int x, int y, int color,
                            boolean shadow) {
        if (((color >>> 24) & 0xFF) >= 8 && !text.isEmpty()) {
            g.text(font, component(face, text), x, y, color, shadow);
        }
    }

    /** Text rechtsbuendig an x1. */
    public static void right(GuiGraphicsExtractor g, Font font, Face face, String text, int x1, int y, int color) {
        draw(g, font, face, text, x1 - width(font, face, text), y, color);
    }

    /** Text mittig ueber cx. */
    public static void centered(GuiGraphicsExtractor g, Font font, Face face, String text, int cx, int y, int color) {
        draw(g, font, face, text, cx - width(font, face, text) / 2, y, color);
    }

    /** Text-Anfang (y), damit die Grossbuchstaben mittig in der Zeile top .. top + height stehen. */
    public static int centerY(Face face, int top, int height) {
        return Math.round(top + height / 2.0F + face.cap / 2.0F) - BASELINE;
    }

    /** Auf maxWidth gekuerzt (mit "…"). */
    public static String ellipsize(Font font, Face face, String text, int maxWidth) {
        if (width(font, face, text) <= maxWidth) {
            return text;
        }
        Map<String, Cut> map = CUTS[face.ordinal()];
        Cut cut = map.get(text);
        if (cut != null) {
            if (cut.width == maxWidth) {
                return cut.text;
            }
            if (cut.width2 == maxWidth) {
                return cut.text2;
            }
        } else {
            if (map.size() >= MAX_ENTRIES) {
                map.clear();
            }
            cut = new Cut();
            map.put(text, cut);
        }
        String dots = "…";
        int limit = Math.max(0, maxWidth - width(font, face, dots));
        // Laengsten Anfang suchen, der passt (Breite waechst mit der Laenge)
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (font.width(styled(face, text.substring(0, mid))) <= limit) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        String result = text.substring(0, low).stripTrailing() + dots;
        cut.width2 = cut.width;
        cut.text2 = cut.text;
        cut.width = maxWidth;
        cut.text = result;
        return result;
    }

    /**
     * Text an Leerzeichen in hoechstens lines.length Zeilen der Breite maxWidth umbrechen (die letzte notfalls mit "…").
     * Misst ohne Cache - nur fuer Texte, die sich selten aendern. Gibt die Anzahl der Zeilen zurueck.
     */
    public static int wrap(Font font, Face face, String text, int maxWidth, String[] lines) {
        int count = 0;
        int start = 0;
        while (start < text.length() && count < lines.length) {
            int end = text.length();
            if (font.width(styled(face, text.substring(start))) > maxWidth) {
                // so viele ganze Woerter wie passen
                end = -1;
                int space = text.indexOf(' ', start);
                while (space >= 0 && font.width(styled(face, text.substring(start, space))) <= maxWidth) {
                    end = space;
                    space = text.indexOf(' ', space + 1);
                }
                if (end < 0 || count == lines.length - 1) {
                    lines[count++] = ellipsize(font, face, text.substring(start), maxWidth);
                    return count;
                }
            }
            lines[count++] = text.substring(start, end);
            start = end + 1;
        }
        return count;
    }

    /** Ressourcen neu geladen oder anderer GUI-Scale: Breiten neu messen. */
    public static void clearCaches() {
        for (int i = 0; i < FACES; i++) {
            WIDTHS[i].clear();
            CUTS[i].clear();
            COMPONENTS[i].clear();
        }
    }
}
