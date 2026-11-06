package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import nova.nova_client.client.mods.Mod;

/**
 * Das Nova-Design fuer alle Menues und HUD-Kaesten - ruhig, dunkel, praezise. Hier stehen alle Design-Tokens
 * (Flaechen in vier Stufen, Haarlinien, Text in drei Stufen, eine Akzentfarbe aus UiSettings, Status- und
 * Kategoriefarben, runde Radien 6/8/14 auf einem 4-px-Raster, Bewegung 120-200 ms ease-out); kein Screen hat eigene
 * Hex-Farben. Formen kommen aus NovaShapes, Text aus NovaType (Geist, nie skaliert), Bedienelemente aus NovaControls.
 * Kein Leuchten, keine Verlaeufe als Deko - nur eine dezente Schattenkante unter schwebenden Fenstern.
 */
public final class NovaGui {
    // Flaechen: Abdunklung -> Fenster -> Karte -> Hover/aktiv (dunkles Neutralgrau mit leicht kuehlem Stich)
    /** Abdunklung hinter Menues (ueber der unscharfen Spielwelt) - leicht, die Welt bleibt sichtbar. */
    public static final int SCRIM = 0x6B0A0B0E;
    /** Voller Hintergrund ausserhalb einer Welt (Titel, Laden). */
    public static final int BG = 0xFF0D0E11;
    /** Fenster. */
    public static final int PANEL = 0xFA131418;
    /** Glas-Karten des Nova-Menues: wie das Fenster, aber durchscheinend (der bewegte Hintergrund scheint durch). */
    public static final int GLASS = 0xC2131418;
    /** Unschaerfe der Spielwelt hinter Nova-Menues (leicht, unabhaengig von der Minecraft-Einstellung). */
    public static final int MENU_BLUR = 3;
    /** Karten, Knoepfe, Felder. */
    public static final int CARD = 0xFF1A1C21;
    /** Hover und aktiv (eine Stufe heller als die Karte). */
    public static final int RAISED = 0xFF22252B;
    /** Vertiefte Flaechen (Eingabefelder, Tasten-Chips, Segment-Leisten). */
    public static final int INSET = 0xFF0E0F12;
    /** Haarlinie (Raender, Trenner). */
    public static final int BORDER = 0x12FFFFFF;
    /** Kraeftigere Haarlinie (Hover, Fokus ohne Akzent). */
    public static final int BORDER_STRONG = 0x21FFFFFF;
    /** Leichte Aufhellung beim Drueberfahren - auf jeder dunklen Flaeche (Zeilen ohne eigene Flaeche). */
    public static final int HOVER = 0x0BFFFFFF;
    /** Spur von Reglern, Scroll- und Fortschrittsbalken. */
    public static final int TRACK = 0x1AFFFFFF;
    /** Bahn eines ausgeschalteten Schalters. */
    public static final int SWITCH_OFF = 0xFF2B2F37;

    // Text: primaer / sekundaer / gedaempft
    public static final int TEXT = 0xFFECEDF0;
    public static final int TEXT_DIM = 0xFFA0A5AF;
    public static final int TEXT_MUTED = 0xFF6A707B;

    // Status (sparsam)
    public static final int DANGER = 0xFFE5574F;
    public static final int SUCCESS = 0xFF3DB37A;

    // Radien und Raster
    /** Chips, Tasten, Checkboxen. */
    public static final int RADIUS_SMALL = 6;
    /** Knoepfe, Felder, Zeilen, Segmente. */
    public static final int RADIUS = 8;
    /** Fenster, Karten, Toasts. */
    public static final int RADIUS_LARGE = 14;
    /** Hoehe von Bedienelementen (Knopf, Feld, Segment). */
    public static final int CONTROL_H = 16;
    /** Hoehe einer Einstellungs-Zeile. */
    public static final int ROW_H = 20;

    // Bewegung: Geschwindigkeit fuer approach() (16 = ~160 ms bis ~92 %)
    public static final float FAST = 22.0F;
    public static final float SPEED = 16.0F;
    /** Dauer der Oeffnen-Animation in Sekunden. */
    public static final float OPEN_TIME = 0.18F;

    /** Akzentfarbe (UiSettings), eine hellere Stufe, eine Toenung fuer gewaehlte Flaechen und die Schrift darauf. */
    public static int BRAND = UiSettings.DEFAULT_ACCENT;
    public static int BRAND_2 = mix(BRAND, 0xFFFFFFFF, 0.3F);
    public static int BRAND_SOFT = alpha(BRAND, 0.14F);
    public static int ON_ACCENT = 0xFFFFFFFF;

    /** Farben der Kategorien (Reihenfolge von ModCategory) - gedaempft, alle etwa gleich hell. */
    private static final int[] CATEGORY_COLORS = {
            0xFFE0786F, 0xFF4FB0A3, 0xFFD6A24F, 0xFF9581DE, 0xFF5D95DE, 0xFFD688B0, 0xFFAEB5C1
    };

    /** Eckenradius der HUD-Kaesten. */
    public static final int HUD_RADIUS = 6;
    /** Einheitlicher Hintergrund aller HUD-Anzeigen: dunkel, ~75 % deckend. */
    public static final int HUD_BACKGROUND = 0xC00E0F12;
    /** Feine Kante um HUD-Kaesten (laesst sie vor jedem Hintergrund sauber abgegrenzt wirken). */
    public static final int HUD_BORDER = 0x16FFFFFF;

    private NovaGui() {
    }

    static void applyAccent(int accent) {
        BRAND = 0xFF000000 | accent;
        BRAND_2 = mix(BRAND, 0xFFFFFFFF, 0.3F);
        BRAND_SOFT = alpha(BRAND, 0.14F);
        ON_ACCENT = luminance(BRAND) > 0.62F ? 0xFF0B0D12 : 0xFFFFFFFF;
    }

    private static float luminance(int color) {
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        return 0.2126F * r + 0.7152F * g + 0.0722F * b;
    }

    // ---------------------------------------------------------------- Kategorien

    public static String label(ModCategory category) {
        return switch (category) {
            case COMBAT -> "Combat";
            case MISC -> "Misc";
            case DONUT -> "Donut";
            case BASEFINDING -> "Base Finding";
            case RENDER -> "Render";
            case VISUALS -> "Visuals";
            case NOVA -> "Nova";
        };
    }

    /** Farbe einer Kategorie, oder die Akzentfarbe, wenn Kategorie-Farben aus sind. */
    public static int accent(ModCategory category) {
        return UiSettings.categoryColors ? CATEGORY_COLORS[category.ordinal()] : BRAND;
    }

    /**
     * Sind die Item-Daten schon gebunden? Seit 26.x erst, wenn eine Welt geladen wurde - vorher (Hauptmenue nach dem
     * Spielstart) stuerzt new ItemStack(...) mit "Components not bound yet" ab.
     */
    public static boolean itemsReady() {
        //? if >=26.1 {
        return Items.STONE.builtInRegistryHolder().areComponentsBound();
        //?} else {
        /*return true;
        *///?}
    }

    /** ItemStack, falls schon moeglich - sonst leer (wird dann einfach nicht gezeichnet). */
    public static ItemStack stack(Item item) {
        return item == null || item == Items.AIR || !itemsReady() ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static int countActive() {
        int count = 0;
        for (Mod mod : Mods.mods) {
            if (mod.active) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- Farben & Animation

    /** Multipliziert den Alphakanal einer Farbe. */
    public static int alpha(int color, float factor) {
        float f = Math.max(0.0F, Math.min(1.0F, factor));
        int a = Math.round(((color >>> 24) & 0xFF) * f);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Lineare Farbinterpolation inkl. Alpha. */
    public static int mix(int from, int to, float t) {
        float f = Math.max(0.0F, Math.min(1.0F, t));
        int a = (int) (((from >>> 24) & 0xFF) + ((((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * f));
        int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * f));
        int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * f));
        int b = (int) ((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Framerate-unabhaengiges Annaehern an einen Zielwert (ohne Animationen: sofort). */
    public static float approach(float current, float target, float speed, float deltaSeconds) {
        if (!UiSettings.animations) {
            return target;
        }
        float next = current + (target - current) * (1.0F - (float) Math.exp(-speed * deltaSeconds));
        return Math.abs(target - next) < 0.002F ? target : next;
    }

    public static float easeOutCubic(float t) {
        if (!UiSettings.animations) {
            return 1.0F;
        }
        float f = 1.0F - Math.max(0.0F, Math.min(1.0F, t));
        return 1.0F - f * f * f;
    }

    /** Laufende Zeit fuer Effekte (ohne Animationen eingefroren). */
    public static float seconds() {
        if (!UiSettings.animations) {
            return 0.0F;
        }
        return (Util.getMillis() % 3_600_000L) / 1000.0F;
    }

    /** Blinken (z.B. Cursor) - ohne Animationen immer sichtbar. */
    public static boolean blink() {
        return !UiSettings.animations || (Util.getMillis() / 500L) % 2L == 0L;
    }

    public static void clickSound() {
        if (UiSettings.sounds) {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.35F));
        }
    }

    // ---------------------------------------------------------------- Flaechen

    /**
     * Dezente Schattenkante unter einer schwebenden Flaeche (eigene Ebene davor und danach, sonst laege sie ueber ihr).
     */
    public static void shadow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius) {
        shadow(g, x0, y0, x1, y1, radius, 1.0F);
    }

    /** Wie {@link #shadow(GuiGraphicsExtractor, int, int, int, int, int)}, beim Ein-/Ausblenden mit Deckkraft a. */
    public static void shadow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, float a) {
        g.nextStratum();
        NovaTextures.roundGlow(g, x0, y0 + 3, x1, y1 + 3, radius, 10.0F, alpha(0x52000000, a), true);
        g.nextStratum();
    }

    /** Fenster: Schattenkante, Flaeche, Haarlinie. */
    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        shadow(g, x0, y0, x1, y1, RADIUS_LARGE);
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS_LARGE, PANEL);
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS_LARGE, BORDER);
    }

    /** Hintergrund eines HUD-Kastens: abgerundet, in der einheitlichen HUD-Farbe. */
    public static void hudBackground(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        NovaShapes.rect(g, x0, y0, x1, y1, HUD_RADIUS, HUD_BACKGROUND);
        NovaShapes.outline(g, x0, y0, x1, y1, HUD_RADIUS, HUD_BORDER);
    }

    /**
     * Hintergrund hinter einem Menue: die unscharfe Spielwelt bleibt sichtbar, nur leicht abgedunkelt, zu den Raendern
     * hin etwas mehr.
     */
    public static void background(GuiGraphicsExtractor g, int width, int height) {
        // VR Menu: schwaecher, damit das Milchglas des schwebenden Fensters durchscheint
        float f = VrMenu.backgroundFactor();
        g.fill(0, 0, width, height, alpha(SCRIM, f));
        NovaShapes.vignette(g, width, height, alpha(0x8C000000, f));
    }

    // ---------------------------------------------------------------- Oeffnen-Animation

    /**
     * Sanftes Hereingleiten eines Menues - nur verschieben, nie skalieren (skalierter Text waere kurz unscharf).
     * Immer mit {@link #popIntro} beenden.
     */
    public static void pushIntro(GuiGraphicsExtractor g, float elapsed) {
        float t = easeOutCubic(elapsed / OPEN_TIME);
        g.pose().pushMatrix();
        g.pose().translate(0.0F, Math.round((1.0F - t) * 6.0F));
    }

    public static void popIntro(GuiGraphicsExtractor g) {
        g.pose().popMatrix();
    }

    /** Deckkraft beim Oeffnen (0..1). */
    public static float introAlpha(float elapsed) {
        return easeOutCubic(elapsed / OPEN_TIME);
    }

    // ---------------------------------------------------------------- Text

    /** Text in der Menue-Schrift; fast unsichtbarer Text wird uebersprungen. */
    public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        NovaType.draw(g, font, NovaType.Face.BODY, text, x, y, color, false);
    }

    /** Wie {@link #text}, wahlweise mit feinem Schatten (z.B. Text direkt auf der Spielwelt). */
    public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, boolean shadow) {
        NovaType.draw(g, font, NovaType.Face.BODY, text, x, y, color, shadow);
    }

    /** Textfeld zeichnet seinen Inhalt in der Menue-Schrift. */
    public static void themeFont(EditBox box) {
        box.addFormatter((text, offset) -> NovaType.styled(NovaType.Face.BODY, text).getVisualOrderText());
    }

    /** Name der Menue-Schrift (fuer Caches, die von der Schrift abhaengen). */
    public static String menuFont() {
        return NovaType.Face.BODY.spec.name();
    }

    /** Textbreite in der Menue-Schrift. */
    public static int width(Font font, String text) {
        return NovaType.width(font, NovaType.Face.BODY, text);
    }

    public static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
        text(g, font, text, cx - width(font, text) / 2, y, color);
    }

    public static void item(GuiGraphicsExtractor g, ItemStack stack, float cx, float cy, float scale) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(scale, scale);
        g.item(stack, -8, -8);
        g.pose().popMatrix();
    }

    /** Auf maxWidth gekuerzt (mit "…"), in der Menue-Schrift. */
    public static String ellipsize(Font font, String text, int maxWidth) {
        return NovaType.ellipsize(font, NovaType.Face.BODY, text, maxWidth);
    }

    /** Text-Caches verwerfen (Ressourcen neu geladen: Glyphen und damit Breiten koennen anders sein). */
    public static void clearTextCaches() {
        NovaType.clearCaches();
    }
}
