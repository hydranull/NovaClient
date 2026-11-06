package nova.nova_client.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.StreamProtectMod;

/**
 * Hotbar im Nova-Stil (immer an, kein Modul): eine schmale, dunkle Glas-Leiste mit leicht runden Ecken und sehr
 * dezentem hellem Rand, ohne Trennlinien; die Auswahl ist ein abgerundetes Feld in der Akzentfarbe aus dem Nova-Menue
 * (eigene Farbe, sonst die Akzentfarbe) - klarer Rand, nur leicht getoent - und gleitet weich zum neuen Feld.
 * Die Neben-Hand bekommt ein eigenes kleines Feld im selben Stil, Stueckzahlen stehen klein und mittig unter dem Item
 * ("x64"). Ersetzt nur Bilder und Zahl der Vanilla-Hotbar ({@link nova.nova_client.mixin.client.HotbarMixin}) - Items,
 * Haltbarkeitsbalken und Abklingzeiten zeichnet weiter Minecraft.
 */
public final class NovaHotbar {
    public static final int RADIUS = 2;
    public static final int BODY = 0xC40A0B10;
    public static final int EDGE = NovaGui.BORDER_STRONG;
    /** Deckkraft der Toenung im gewaehlten Feld (der Rand ist voll deckend). */
    private static final float SELECT_FILL = 0.2F;

    private static float selX = Float.NaN;
    private static long lastFrame;

    private NovaHotbar() {
    }

    /** Statt eines Hotbar-Bildes: true = selbst gezeichnet, false = Vanilla zeichnen lassen. */
    public static boolean sprite(GuiGraphicsExtractor g, Identifier sprite, int x, int y, int width, int height) {
        if (!"minecraft".equals(sprite.getNamespace())) {
            return false;
        }
        switch (sprite.getPath()) {
            // Leiste 182 x 22: die Pille sitzt eng um die Items (y + 3 .. y + 19)
            case "hud/hotbar" -> panel(g, x, y + 2, x + width, y + 20);
            case "hud/hotbar_selection" -> selection(g, x, y);
            // Neben-Hand: Bild 29 x 24 (eine Zeile hoeher als die Leiste), das Item sitzt links 3 bzw. rechts 10 weit drin
            case "hud/hotbar_offhand_left" -> panel(g, x + 2, y + 3, x + 20, y + 21);
            case "hud/hotbar_offhand_right" -> panel(g, x + 9, y + 3, x + 27, y + 21);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Feld im Hotbar-Stil: dunkles Glas, runde Ecken, dezenter heller Rand. */
    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, BODY);
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, EDGE);
    }

    /**
     * Rahmen um das gewaehlte Feld (Vanilla-Bild: 24 x 23 bei x, y; das Item liegt bei x + 4, y + 4). Gleitet weich
     * hinterher und streckt sich dabei ein wenig.
     */
    private static void selection(GuiGraphicsExtractor g, int x, int y) {
        if (StreamProtectMod.hidesSelectedSlot()) {
            return;
        }
        long now = Util.getMillis();
        float dt = Math.min(0.1F, Math.max(0.0F, (now - lastFrame) / 1000.0F));
        lastFrame = now;
        if (Float.isNaN(selX) || Math.abs(selX - x) > 400.0F) {
            selX = x;
        }
        selX = NovaGui.approach(selX, x, 18.0F, dt);
        float stretch = Math.min(4.0F, Math.abs(x - selX) * 0.25F);
        float center = selX + 12.0F;
        // ganzes Feld (20 breit, so hoch wie die Leiste, gleiche Rundung): am ersten/letzten Platz deckt sich der
        // Rahmen genau mit dem Ende der Leiste - keine Luecke dazwischen
        int barX0 = g.guiWidth() / 2 - 91;
        int barX1 = barX0 + 182;
        int x0 = Math.max(barX0, Math.round(center - 10.0F - stretch / 2.0F));
        int x1 = Math.min(barX1, Math.round(center + 10.0F + stretch / 2.0F));
        int y0 = y + 3;
        int y1 = y + 21;
        int accent = 0xFF000000 | NovaGui.BRAND;
        g.nextStratum();
        NovaShapes.rect(g, x0, y0, x1, y1, RADIUS, NovaGui.alpha(accent, SELECT_FILL));
        NovaShapes.outline(g, x0, y0, x1, y1, RADIUS, accent);
    }

    /**
     * Dekoration eines Hotbar-Felds: Haltbarkeit und Abklingzeit wie Vanilla, die Stueckzahl aber klein und mittig unter
     * dem Item als "x64".
     */
    public static void decorations(GuiGraphicsExtractor graphics, Font font, ItemStack stack, int x, int y) {
        graphics.itemDecorations(font, stack, x, y, "");
        int count = stack.getCount();
        if (count <= 1 || stack.isEmpty()) {
            return;
        }
        String text = "x" + count;
        float scale = 0.62F;
        int width = NovaGui.width(font, text);
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + 8.0F - width * scale / 2.0F, y + 12.5F);
        graphics.pose().scale(scale, scale);
        NovaGui.text(graphics, font, text, 0, 0, 0xFFE2E2E6, true);
        graphics.pose().popMatrix();
    }
}
