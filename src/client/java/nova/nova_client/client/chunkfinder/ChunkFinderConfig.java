package nova.nova_client.client.chunkfinder;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Alle Einstellungen des ChunkFinders. Was nicht im Menue steht (Schwellenwerte, Performance), laesst sich in
 * config/nova_client.properties unter "mod.chunkfinder.&lt;name&gt;" anpassen.
 */
public final class ChunkFinderConfig {
    public static final int WORLD_MIN_Y = -64;
    public static final int WORLD_MAX_Y = 320;
    /** Verdaechtige Bloecke (Deepslate, End Stone, ...) werden nur in diesem Bereich gezaehlt. */
    public static final int BLOCKS_MIN_Y = 0;
    public static final int BLOCKS_MAX_Y = 64;

    /** Staerke des Leuchtrands, fest wie beim Sus Chunk Finder (1 = 100 %). */
    public static final float GLOW_STRENGTH = 0.65F;

    // ---------------------------------------------------------------- Anzeige
    public static boolean overlayEnabled = true;
    /** Einzelne verdaechtige Bloecke zusaetzlich als Kasten markieren. */
    public static boolean blockBoxes = false;
    public static int overlayY = 64;
    /** Farbe der Markierung (RGB, volle Saettigung/Helligkeit - eingestellt wird nur der Farbton). Standard gruen. */
    public static int color = 0x3DFF5A;
    public static float opacity = 0.31F;

    // ---------------------------------------------------------------- Meldungen
    public static boolean chatAlerts = true;
    public static boolean soundAlerts = true;
    /** Hoechstens so viele Meldungen pro Minute. */
    public static int maxAlertsPerMinute = 6;

    // ---------------------------------------------------------------- 1) Verdaechtige Bloecke (Y 0..64)
    public static boolean detectDeepslate = true;
    public static boolean detectCobbledDeepslate = true;
    public static boolean detectRotatedDeepslate = true;
    public static boolean detectEndStone = true;
    /** Bloecke, die an Luft/Wasser/Lava grenzen, nicht zaehlen (Hoehlenwaende). */
    public static boolean ignoreExposed = true;
    public static int deepslateMin = 1;
    public static int cobbledDeepslateMin = 3;
    public static int rotatedDeepslateMin = 2;
    public static int endStoneMin = 2;
    /** Normales Deepslate (axis=y) kommt bis zu dieser Hoehe natuerlich vor (Uebergang 0..8). */
    public static int deepslateNaturalMaxY = 8;

    // ---------------------------------------------------------------- Trial Chambers
    public static boolean skipTrialChambers = true;
    /** Ab so vielen Wachs-Kupfer-/Tuff-Ziegel-Bloecken ist es eine Trial Chamber (natuerlich) - Chunk ignorieren. */
    public static int trialChamberMin = 40;

    // ---------------------------------------------------------------- 2) Base-Detektoren (Punkte)
    public static boolean baseFinder = true;
    public static int minBaseScore = 6;

    // ---------------------------------------------------------------- 3) Geoden
    public static boolean detectGeodes = true;
    /** Zusaetzlich die Geoden-Form pruefen (Calcit-Huelle, dicht, kaum offen). */
    public static boolean geodeShapeCheck = true;
    /** 1 = streng ... 10 = sehr locker. */
    public static int geodeSensitivity = 5;
    public static boolean magicGeodes = true;
    public static int magicMinSpots = 12;
    public static int magicRadius = 6;

    // ---------------------------------------------------------------- 4) Struktur-Score
    public static boolean detectStructures = true;
    public static int minBaseChance = 45;

    // ---------------------------------------------------------------- Performance
    /** Chunk-Abschnitte (16x16x16), die pro Tick hoechstens vollstaendig gelesen werden. */
    public static int sectionsPerTick = 16;
    public static int scanRadius = 16;
    public static int rescanDelayTicks = 40;
    public static int entityCheckTicks = 20;
    /** Hoechstens so viele verdaechtige Einzelbloecke je Chunk / insgesamt als Kasten. */
    public static int maxBoxesPerChunk = 24;
    public static int maxBoxes = 300;

    private ChunkFinderConfig() {
    }

    private record Field(String key, Supplier<Number> get, Consumer<Float> set) {
    }

    private record Flag(String key, Supplier<Boolean> get, Consumer<Boolean> set) {
    }

    private static final List<Field> FIELDS = List.of(
            new Field("overlayY", () -> overlayY, v -> overlayY = clamp(v, WORLD_MIN_Y, WORLD_MAX_Y)),
            new Field("opacity", () -> opacity, v -> opacity = Math.max(0.05F, Math.min(1.0F, v))),
            new Field("maxAlertsPerMinute", () -> maxAlertsPerMinute, v -> maxAlertsPerMinute = clamp(v, 1, 60)),
            new Field("deepslateMin", () -> deepslateMin, v -> deepslateMin = clamp(v, 1, 64)),
            new Field("cobbledDeepslateMin", () -> cobbledDeepslateMin, v -> cobbledDeepslateMin = clamp(v, 1, 64)),
            new Field("rotatedDeepslateMin", () -> rotatedDeepslateMin, v -> rotatedDeepslateMin = clamp(v, 1, 64)),
            new Field("endStoneMin", () -> endStoneMin, v -> endStoneMin = clamp(v, 1, 64)),
            new Field("deepslateNaturalMaxY", () -> deepslateNaturalMaxY, v -> deepslateNaturalMaxY = clamp(v, -64, 64)),
            new Field("trialChamberMin", () -> trialChamberMin, v -> trialChamberMin = clamp(v, 1, 500)),
            new Field("minBaseScore", () -> minBaseScore, v -> minBaseScore = clamp(v, 1, 60)),
            new Field("geodeSensitivity", () -> geodeSensitivity, v -> geodeSensitivity = clamp(v, 1, 10)),
            new Field("magicMinSpots", () -> magicMinSpots, v -> magicMinSpots = clamp(v, 1, 200)),
            new Field("magicRadius", () -> magicRadius, v -> magicRadius = clamp(v, 2, 12)),
            new Field("minBaseChance", () -> minBaseChance, v -> minBaseChance = clamp(v, 20, 99)),
            new Field("sectionsPerTick", () -> sectionsPerTick, v -> sectionsPerTick = clamp(v, 1, 128)),
            new Field("scanRadius", () -> scanRadius, v -> scanRadius = clamp(v, 1, 64)),
            new Field("rescanDelayTicks", () -> rescanDelayTicks, v -> rescanDelayTicks = clamp(v, 0, 600)),
            new Field("entityCheckTicks", () -> entityCheckTicks, v -> entityCheckTicks = clamp(v, 5, 200)),
            new Field("maxBoxesPerChunk", () -> maxBoxesPerChunk, v -> maxBoxesPerChunk = clamp(v, 1, 256)),
            new Field("maxBoxes", () -> maxBoxes, v -> maxBoxes = clamp(v, 10, 2000)));

    private static final List<Flag> FLAGS = List.of(
            new Flag("overlayEnabled", () -> overlayEnabled, v -> overlayEnabled = v),
            new Flag("blockBoxes", () -> blockBoxes, v -> blockBoxes = v),
            new Flag("chatAlerts", () -> chatAlerts, v -> chatAlerts = v),
            new Flag("soundAlerts", () -> soundAlerts, v -> soundAlerts = v),
            new Flag("detectDeepslate", () -> detectDeepslate, v -> detectDeepslate = v),
            new Flag("detectCobbledDeepslate", () -> detectCobbledDeepslate, v -> detectCobbledDeepslate = v),
            new Flag("detectRotatedDeepslate", () -> detectRotatedDeepslate, v -> detectRotatedDeepslate = v),
            new Flag("detectEndStone", () -> detectEndStone, v -> detectEndStone = v),
            new Flag("ignoreExposed", () -> ignoreExposed, v -> ignoreExposed = v),
            new Flag("skipTrialChambers", () -> skipTrialChambers, v -> skipTrialChambers = v),
            new Flag("baseFinder", () -> baseFinder, v -> baseFinder = v),
            new Flag("detectGeodes", () -> detectGeodes, v -> detectGeodes = v),
            new Flag("geodeShapeCheck", () -> geodeShapeCheck, v -> geodeShapeCheck = v),
            new Flag("magicGeodes", () -> magicGeodes, v -> magicGeodes = v),
            new Flag("detectStructures", () -> detectStructures, v -> detectStructures = v));

    private static int clamp(float value, int min, int max) {
        return Math.max(min, Math.min(max, Math.round(value)));
    }

    public static void save(Properties properties, String prefix) {
        for (Field field : FIELDS) {
            Number value = field.get().get();
            properties.setProperty(prefix + field.key(), value instanceof Float
                    ? String.format(Locale.ROOT, "%.3f", value.floatValue()) : String.valueOf(value));
        }
        for (Flag flag : FLAGS) {
            properties.setProperty(prefix + flag.key(), String.valueOf(flag.get().get()));
        }
        properties.setProperty(prefix + "overlayColor", String.format("%06X", color & 0xFFFFFF));
    }

    public static void load(Properties properties, String prefix) {
        for (Field field : FIELDS) {
            String value = properties.getProperty(prefix + field.key());
            if (value != null) {
                try {
                    field.set().accept(Float.parseFloat(value.trim()));
                } catch (NumberFormatException ignored) {
                    // Standard behalten
                }
            }
        }
        for (Flag flag : FLAGS) {
            String value = properties.getProperty(prefix + flag.key());
            if (value != null) {
                flag.set().accept(Boolean.parseBoolean(value.trim()));
            }
        }
        String value = properties.getProperty(prefix + "overlayColor");
        if (value != null) {
            try {
                color = Integer.parseInt(value.trim(), 16) & 0xFFFFFF;
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
