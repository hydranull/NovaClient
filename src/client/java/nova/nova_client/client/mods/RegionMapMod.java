package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.Rect;
import nova.nova_client.client.RegionMapMenu;

import java.util.Locale;
import java.util.Properties;

/**
 * Region Map (DonutSMP): die Oberwelt von -225.000 bis +225.000 als 9 x 9 Regionen zu je 50.000 x 50.000 Bloecken, jede
 * mit ihrer Server-Nummer und der Farbe ihres Standorts. Darunter Koordinaten, die aktuelle Region und eine Legende.
 * <p>
 * Schnell: die farbigen Kacheln werden einmal (je Groesse) in eine Textur gemalt und pro Bild nur noch als ein Bild
 * gezeichnet - pro Frame kommen nur die 81 Nummern, die Markierung der eigenen Region und der Pfeil dazu.
 */
public class RegionMapMod extends RenderMod {
    public static final int GRID = 9;
    public static final int CELL_BLOCKS = 50_000;
    public static final int WORLD_MIN = -225_000;

    /** Server-Standorte (Name + Farbe wie auf der Region-Karte). */
    public enum Location {
        EU_CENTRAL("EU Central", 0x92D050),
        EU_WEST("EU West", 0x38761D),
        NA_EAST("NA East", 0x5B9BD5),
        NA_WEST("NA West", 0x4472C4),
        ASIA("Asia", 0xFFC000),
        OCEANIA("Oceania", 0xED7D31);

        public final String label;
        public final int color;

        Location(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /** Nummer der Region je Feld, Zeile fuer Zeile von Norden nach Sueden, jeweils von Westen nach Osten. */
    private static final int[] NUMBERS = {
            82, 100, 101, 102, 103, 104, 105, 106, 91,
            83, 44, 75, 42, 41, 40, 39, 38, 92,
            84, 45, 14, 13, 12, 11, 10, 37, 93,
            85, 46, 74, 3, 2, 1, 25, 36, 94,
            86, 47, 72, 71, 5, 4, 24, 35, 95,
            87, 51, 17, 9, 8, 7, 23, 34, 96,
            88, 54, 18, 61, 62, 21, 22, 33, 97,
            89, 26, 27, 28, 29, 30, 59, 32, 98,
            90, 107, 108, 109, 110, 111, 112, 113, 99};
    /** Standort je Feld: C EU Central, U EU West, E NA East, W NA West, A Asia, O Oceania. */
    private static final String LOCATIONS =
            "OWWWEEEEE" + "OWWWEEEEE" + "OWWWEEEEE" + "OOOWEEEEE" + "AAAAEEEEE" + "AUUCCCCCE" + "AUUCCCCCC"
                    + "CUCCCCCCC" + "CUUUUUUUC";

    /** Ziffern in Ubuntu Medium (unabhaengig von Custom Font): schmal, kraeftig, auch klein gut lesbar. */
    private static final Style NUMBER_STYLE = Style.EMPTY.withFont(
            new FontDescription.Resource(Identifier.fromNamespaceAndPath("nova_client", "font_ubuntu")));
    private static final Component[] NUMBER_TEXT = new Component[NUMBERS.length];

    static {
        for (int i = 0; i < NUMBERS.length; i++) {
            NUMBER_TEXT[i] = Component.literal(String.valueOf(NUMBERS[i])).setStyle(NUMBER_STYLE);
        }
    }

    public static final float MIN_SCALE = 0.5F;
    public static final float MAX_SCALE = 2.0F;
    private static final int CELL = 17;
    private static final int GAP = 2;
    private static final int PAD = 7;
    private static final int MAP = GRID * (CELL + GAP) - GAP;
    private static final int W = MAP + PAD * 2;
    private static final int INFO_LINE = 12;
    private static final int LEGEND_LINE = 10;
    private static final int BACKGROUND = 0xA00C0C12;
    private static final int LABEL = 0xFF8C93A3;
    private static final int VALUE = 0xFFF2F3F6;
    private static final Identifier TILES = Identifier.fromNamespaceAndPath("nova_client", "dynamic/region_tiles");

    // Einstellungen
    public static float scale = 1.0F;
    /** Koordinaten und Region unter der Karte. */
    public static boolean showCoords = true;
    /** Farbe -> Standort unter der Karte. */
    public static boolean showLegend = true;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = Standard. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    /** Zuletzt gezeichneter Bereich (fuer den Verschiebe-Modus). */
    public static Rect bounds;

    private static DynamicTexture tiles;
    private static int tilesPixels;

    public RegionMapMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (!enabled) {
            bounds = null;
        }
    }

    private static Location location(int index) {
        return switch (LOCATIONS.charAt(index)) {
            case 'C' -> Location.EU_CENTRAL;
            case 'U' -> Location.EU_WEST;
            case 'E' -> Location.NA_EAST;
            case 'W' -> Location.NA_WEST;
            case 'A' -> Location.ASIA;
            default -> Location.OCEANIA;
        };
    }

    /** Feld (0..80) an dieser Position, -1 = ausserhalb der Karte. */
    public static int cellAt(double x, double z) {
        int col = Mth.floor((x - WORLD_MIN) / CELL_BLOCKS);
        int row = Mth.floor((z - WORLD_MIN) / CELL_BLOCKS);
        return col < 0 || col >= GRID || row < 0 || row >= GRID ? -1 : row * GRID + col;
    }

    private static int height() {
        int h = W;
        if (showCoords) {
            h += 2 * INFO_LINE + 2;
        }
        if (showLegend) {
            h += 3 * LEGEND_LINE + 4;
        }
        return h + (showCoords || showLegend ? 3 : 0);
    }

    /** Hoehe in GUI-Pixeln mit der eingestellten Groesse (fuer die HUD-Anordnung). */
    public static int scaledHeight() {
        return Math.round(height() * scale);
    }

    public static int scaledWidth() {
        return Math.round(W * scale);
    }

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getDebugOverlay().showDebugScreen()) {
            bounds = null;
            return;
        }
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        float w = W * scale;
        float h = height() * scale;
        float left;
        float top;
        if (posX >= 0.0F) {
            left = Mth.clamp(posX * screenW, 0.0F, screenW - w);
            top = Mth.clamp(posY * screenH, 0.0F, screenH - h);
        } else {
            // Standard: linke Spalte, unter HudInfo
            left = 6;
            top = nova.nova_client.client.HudLayout.top(nova.nova_client.client.HudLayout.Slot.REGION_MAP, Math.round(h));
        }
        bounds = new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h));

        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        draw(g, client.font, client, deltaTracker.getGameTimeDeltaPartialTick(false));
        g.pose().popMatrix();
    }

    /** Vorschau im Nova-Menue: die Karte wie im Spiel (in Originalgroesse), linke obere Ecke bei (x, y). */
    public static Rect drawPreview(GuiGraphicsExtractor g, Font font, int x, int y) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        draw(g, font, client, 1.0F);
        g.pose().popMatrix();
        return new Rect(x, y, x + W, y + height());
    }

    private static void draw(GuiGraphicsExtractor g, Font font, Minecraft client, float partialTick) {
        double x = Mth.lerp(partialTick, client.player.xo, client.player.getX());
        double z = Mth.lerp(partialTick, client.player.zo, client.player.getZ());
        boolean overworld = client.level != null && client.level.dimension() == Level.OVERWORLD;
        int here = overworld ? cellAt(x, z) : -1;
        int h = height();

        // Karte: Hintergrund und alle Kacheln als ein Bild
        NovaShapes.rect(g, 0, 0, W, h, NovaGui.HUD_RADIUS, NovaGui.HUD_BACKGROUND);
        ensureTiles(g);
        if (tiles != null) {
            g.pose().pushMatrix();
            g.pose().translate(PAD, PAD);
            g.pose().scale(MAP / (float) tilesPixels, MAP / (float) tilesPixels);
            g.blit(RenderPipelines.GUI_TEXTURED, TILES, 0, 0, 0, 0, tilesPixels, tilesPixels, tilesPixels, tilesPixels,
                    tilesPixels, tilesPixels, 0xFFFFFFFF);
            g.pose().popMatrix();
        }

        // Eigene Region hervorheben
        if (here >= 0) {
            int cx = PAD + (here % GRID) * (CELL + GAP);
            int cy = PAD + (here / GRID) * (CELL + GAP);
            NovaShapes.outline(g, cx - 1, cy - 1, cx + CELL + 1, cy + CELL + 1, 4, 0xFFFFFFFF);
        }

        // Nummern
        g.nextStratum();
        for (int i = 0; i < NUMBERS.length; i++) {
            int cx = PAD + (i % GRID) * (CELL + GAP);
            int cy = PAD + (i / GRID) * (CELL + GAP);
            Component text = NUMBER_TEXT[i];
            float s = NUMBERS[i] >= 100 ? 0.72F : 0.85F;
            g.pose().pushMatrix();
            g.pose().translate(cx + CELL / 2.0F, cy + CELL / 2.0F);
            g.pose().scale(s, s);
            g.text(font, text, -font.width(text) / 2, -4, numberColor(location(i).color), false);
            g.pose().popMatrix();
        }

        // Eigene Position als Pfeil in Blickrichtung
        if (overworld) {
            float fx = (float) ((x - WORLD_MIN) / CELL_BLOCKS);
            float fz = (float) ((z - WORLD_MIN) / CELL_BLOCKS);
            if (fx >= 0.0F && fx < GRID && fz >= 0.0F && fz < GRID) {
                float px = PAD + (int) fx * (CELL + GAP) + (fx - (int) fx) * CELL;
                float pz = PAD + (int) fz * (CELL + GAP) + (fz - (int) fz) * CELL;
                g.pose().pushMatrix();
                g.pose().translate(px, pz);
                g.pose().rotate((client.player.getViewYRot(partialTick) + 180.0F) * Mth.DEG_TO_RAD);
                NovaShapes.triangle(g, -5.0F, -6.5F, 5.0F, 4.5F, 0xC8000000);
                NovaShapes.triangle(g, -3.6F, -4.9F, 3.6F, 3.1F, 0xFFFF3B47);
                g.pose().popMatrix();
            }
        }

        // Koordinaten + Region
        int y = W;
        if (showCoords) {
            String region;
            int regionColor = VALUE;
            if (!overworld) {
                region = "Overworld only";
                regionColor = LABEL;
            } else if (here < 0) {
                region = "Outside the map";
                regionColor = LABEL;
            } else {
                region = NUMBERS[here] + "  ·  " + location(here).label;
                regionColor = NovaGui.mix(0xFF000000 | location(here).color, 0xFFFFFFFF, 0.35F);
            }
            int valueX = PAD + NovaGui.width(font, "Coordinate") + 8;
            NovaGui.text(g, font, "Coordinate", PAD, y, LABEL, false);
            NovaGui.text(g, font, format(x) + ", " + format(z), valueX, y, VALUE, false);
            NovaGui.text(g, font, "Region", PAD, y + INFO_LINE, LABEL, false);
            NovaGui.text(g, font, region, valueX, y + INFO_LINE, regionColor, false);
            y += 2 * INFO_LINE + 2;
        }

        // Legende: Farbe -> Standort in zwei Spalten
        if (showLegend) {
            Location[] all = Location.values();
            int colW = (W - PAD * 2) / 2;
            for (int i = 0; i < all.length; i++) {
                int lx = PAD + (i % 2) * colW;
                int ly = y + (i / 2) * LEGEND_LINE;
                g.fill(lx, ly + 1, lx + 6, ly + 7, 0xFF000000 | all[i].color);
                NovaGui.text(g, font, all[i].label, lx + 10, ly, LABEL, false);
            }
        }
    }

    /** Kacheln in echter Pixelgroesse malen (neu, wenn sich GUI-Scale oder Groesse aendern). */
    private static void ensureTiles(GuiGraphicsExtractor g) {
        // echte Pixel je Karten-Einheit (GUI-Scale x eingestellte Groesse)
        int density = Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale() * g.pose().m00()));
        int pixels = Mth.clamp(MAP * density, MAP, 1024);
        if (tiles != null && pixels == tilesPixels) {
            return;
        }
        float unit = pixels / (float) MAP;
        DynamicTexture texture = new DynamicTexture(() -> "nova region tiles", pixels, pixels, false) {
            {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
        };
        NativeImage image = texture.getPixels();
        float radius = 3.0F * unit;
        float cell = CELL * unit;
        float step = (CELL + GAP) * unit;
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                int col = Math.min(GRID - 1, (int) (px / step));
                int row = Math.min(GRID - 1, (int) (py / step));
                float lx = px + 0.5F - col * step;
                float ly = py + 0.5F - row * step;
                // Abstand zum abgerundeten Rechteck der Kachel (negativ = innen), weiche Kante ueber 1 Pixel
                float dx = Math.max(Math.abs(lx - cell / 2.0F) - (cell / 2.0F - radius), 0.0F);
                float dy = Math.max(Math.abs(ly - cell / 2.0F) - (cell / 2.0F - radius), 0.0F);
                float distance = (float) Math.sqrt(dx * dx + dy * dy) - radius;
                float coverage = Mth.clamp(0.5F - distance, 0.0F, 1.0F);
                int alpha = Math.round(coverage * 225.0F);
                image.setPixel(px, py, alpha == 0 ? 0 : (alpha << 24) | location(row * GRID + col).color);
            }
        }
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(TILES, texture);
        tiles = texture;
        tilesPixels = pixels;
    }

    /** Dunkle Ziffern auf hellen Feldern (Gelb, Hellgruen), sonst weiss. */
    private static int numberColor(int background) {
        int r = (background >> 16) & 0xFF;
        int gr = (background >> 8) & 0xFF;
        int b = background & 0xFF;
        return 0.299F * r + 0.587F * gr + 0.114F * b > 165.0F ? 0xFF1A1A1A : 0xFFFFFFFF;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%d", Mth.floor(value));
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new RegionMapMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "scale", String.valueOf(scale));
        properties.setProperty(prefix + "showCoords", String.valueOf(showCoords));
        properties.setProperty(prefix + "showLegend", String.valueOf(showLegend));
        properties.setProperty(prefix + "x", String.valueOf(posX));
        properties.setProperty(prefix + "y", String.valueOf(posY));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            scale = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "scale", String.valueOf(scale)).trim()),
                    MIN_SCALE, MAX_SCALE);
            posX = Float.parseFloat(properties.getProperty(prefix + "x", String.valueOf(posX)).trim());
            posY = Float.parseFloat(properties.getProperty(prefix + "y", String.valueOf(posY)).trim());
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        showCoords = Boolean.parseBoolean(properties.getProperty(prefix + "showCoords", String.valueOf(showCoords)));
        showLegend = Boolean.parseBoolean(properties.getProperty(prefix + "showLegend", String.valueOf(showLegend)));
    }
}
