package nova.nova_client.client;

import java.util.List;

/**
 * Alle Nova-Schriften: die beiden der Nova-Menues (Roboto = "clean", Poppins Black = "poppins") und die waehlbaren
 * Schriften fuer Custom Font. Groesse und Versatz sind je Schrift so gewaehlt, dass Grossbuchstaben wie bei Roboto etwa
 * 6,75 Pixel hoch sind und auf derselben Grundlinie stehen - so passt jede Schrift in Minecrafts 9-Pixel-Zeilen.
 * Die TTF-Dateien und ihre Lizenzen (OFL/UFL) liegen in assets/nova_client/font, die JSON-Varianten je Pixeldichte
 * daneben (name.json; Menue-Schriften zusaetzlich name_s1..s8.json, Custom-Font-Varianten entstehen bei Bedarf).
 */
public final class CustomFonts {
    /** Eine waehlbare Schrift: Name im Menue, kurze Beschreibung, Schrift-Daten. */
    public record Entry(String label, String style, NovaFonts.Spec spec) {
    }

    public static final List<Entry> ALL = List.of(
            entry("Roboto", "Clean", "clean", "roboto-regular.ttf", 9.5F, 0.5F),
            entry("Inter", "Modern", "font_inter", "inter.ttf", 9.3F, 0.5F),
            // Saubere Alltags-Schriften (variable werden in FontWeights auf Medium gestellt)
            entry("Lato", "Classic", "font_lato", "lato-medium.ttf", 9.4F, 0.5F),
            entry("Open Sans", "Neutral", "font_opensans", "opensans.ttf", 9.5F, 0.5F),
            entry("Montserrat", "Elegant", "font_montserrat", "montserrat.ttf", 9.6F, 0.5F),
            entry("Nunito", "Soft", "font_nunito", "nunito.ttf", 9.4F, 0.5F),
            entry("Manrope", "Minimal", "font_manrope", "manrope.ttf", 9.4F, 0.5F),
            entry("DM Sans", "Crisp", "font_dmsans", "dmsans.ttf", 9.6F, 0.5F),
            entry("Figtree", "Fresh", "font_figtree", "figtree.ttf", 9.6F, 0.5F),
            entry("Plus Jakarta Sans", "Sleek", "font_jakarta", "plusjakartasans.ttf", 9.1F, 0.5F),
            entry("Be Vietnam Pro", "Smooth", "font_bevietnam", "bevietnampro-medium.ttf", 9.1F, 0.5F),
            entry("Barlow", "Compact", "font_barlow", "barlow-medium.ttf", 9.6F, 0.5F),
            entry("Poppins", "Geometric", "font_poppins", "poppins-medium.ttf", 9.7F, 0.5F),
            entry("Ubuntu", "Friendly", "font_ubuntu", "ubuntu-medium.ttf", 9.7F, 0.5F),
            entry("Fira Sans", "Readable", "font_firasans", "firasans-medium.ttf", 9.8F, 0.5F),
            entry("Varela Round", "Rounded", "font_varelaround", "varelaround-regular.ttf", 9.5F, 0.5F),
            entry("Titillium Web", "Sporty", "font_titillium", "titilliumweb-semibold.ttf", 9.9F, 0.5F),
            entry("Kanit", "Gamer", "font_kanit", "kanit-medium.ttf", 10.5F, 0.5F),
            entry("Rajdhani", "Tech", "font_rajdhani", "rajdhani-semibold.ttf", 10.5F, 0.5F),
            entry("Chakra Petch", "Cyber", "font_chakrapetch", "chakrapetch-medium.ttf", 9.6F, 0.5F),
            entry("Russo One", "Bold", "font_russoone", "russoone-regular.ttf", 9.6F, 0.5F),
            entry("Audiowide", "Sci-Fi", "font_audiowide", "audiowide-regular.ttf", 9.6F, 0.5F),
            entry("Comic Neue", "Fun", "font_comicneue", "comicneue-bold.ttf", 9.9F, 0.5F),
            entry("Fira Mono", "Monospace", "font_firamono", "firamono-medium.ttf", 9.8F, 0.5F),
            // Pixel-Schrift auf ihrem 8er-Raster: genau 8 Pixel je Em, sonst wird sie unscharf
            entry("Press Start 2P", "Retro pixel", "font_pressstart", "pressstart2p-regular.ttf", 8.0F, 0.0F),
            entry("Poppins Black", "Heavy", "poppins", "poppins-black.ttf", 9.0F, 1.0F));

    private CustomFonts() {
    }

    private static Entry entry(String label, String style, String name, String file, float size, float shiftY) {
        return new Entry(label, style, new NovaFonts.Spec(name, file, size, 0.0F, shiftY));
    }
}
