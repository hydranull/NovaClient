package nova.nova_client.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Properties;

/**
 * Einstellungen der Nova-Oberflaeche. Es gibt ein einziges Design (NovaGui); einstellbar sind nur Akzentfarbe,
 * Kategorie-Farben, Menue-Groesse, Animationen, Klick-Toene und ob Titel-, Pause- und Ladebildschirm im Nova-Look
 * kommen.
 */
public final class UiSettings {
    public static final String[] ACCENT_NAMES = {"Blue", "Indigo", "Violet", "Teal", "Green", "Amber", "Rose", "Mono"};
    public static final int[] ACCENTS = {
            0xFF5B8DEF, 0xFF7B7EF0, 0xFF9B7BEA, 0xFF3FBFAD, 0xFF54C27C, 0xFFE5A93B, 0xFFE8698A, 0xFFE4E7EC
    };
    public static final int DEFAULT_ACCENT = 0xFF5B8DEF;

    public static int accent = DEFAULT_ACCENT;
    /** Jede Kategorie in ihrer eigenen Linienfarbe (sonst alles in der Akzentfarbe). */
    public static boolean categoryColors = true;
    public static boolean animations = true;
    public static boolean sounds = true;
    /** Titel-, Pause- und Ladebildschirm im Nova-Look (sonst die von Minecraft). */
    public static boolean novaScreens = true;

    private UiSettings() {
    }

    /** Akzentfarbe an NovaGui uebergeben. */
    public static void apply() {
        NovaGui.applyAccent(accent);
    }

    public static Screen mainMenu() {
        return new NovaMenu();
    }

    /** Version zum Anzeigen, mit Stufe davor (z.B. "Beta 0.100"; Stufe aus fabric.mod.json, release_stage beim Bauen). */
    public static String version() {
        return FabricLoader.getInstance().getModContainer("nova_client").map(c -> {
            String version = c.getMetadata().getVersion().getFriendlyString();
            net.fabricmc.loader.api.metadata.CustomValue stage = c.getMetadata().getCustomValue("nova_client:stage");
            return stage != null && stage.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING
                    && !stage.getAsString().isBlank() ? stage.getAsString() + " " + version : version;
        }).orElse("");
    }

    private static int accentIndex() {
        for (int i = 0; i < ACCENTS.length; i++) {
            if (ACCENTS[i] == accent) {
                return i;
            }
        }
        return 0;
    }

    /** Einstellungs-Fenster (aus dem Nova-Menue). */
    public static Screen screen(Screen parent) {
        String[] sizes = new String[NovaScale.OPTIONS.length];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = NovaScale.label(NovaScale.OPTIONS[i]);
        }
        return new ModOptionsMenu(parent, "Settings", "Nova " + version(), List.of(
                new ModOptionsMenu.Section("Appearance"),
                new ModOptionsMenu.Colors("Accent", ACCENTS, UiSettings::accentIndex, i -> {
                    accent = ACCENTS[i];
                    apply();
                }),
                new ModOptionsMenu.Toggle("Colored categories", () -> categoryColors, v -> categoryColors = v),
                new ModOptionsMenu.Segmented("Menu size", sizes, () -> Math.max(0, java.util.Arrays.binarySearch(NovaScale.OPTIONS,
                        NovaScale.menuScale)), v -> {
                    NovaScale.menuScale = NovaScale.OPTIONS[v];
                    NovaScale.apply();
                }),
                new ModOptionsMenu.Section("Behavior"),
                new ModOptionsMenu.Toggle("Animations", () -> animations, v -> animations = v),
                new ModOptionsMenu.Toggle("Click sounds", () -> sounds, v -> sounds = v),
                new ModOptionsMenu.Toggle("Nova title, pause & loading screens", () -> novaScreens, v -> novaScreens = v),
                new ModOptionsMenu.Section("More"),
                new ModOptionsMenu.Actions(List.of(
                        new ModOptionsMenu.Action(() -> "Edit HUD layout", () -> Minecraft.getInstance().gui.setScreen(
                                new HudEditorScreen(Minecraft.getInstance().gui.screen()))),
                        new ModOptionsMenu.Action(() -> "Profiles", () -> Minecraft.getInstance().gui.setScreen(
                                new ProfileMenu(Minecraft.getInstance().gui.screen()))),
                        new ModOptionsMenu.Action(() -> "Reset to defaults", UiSettings::reset)))));
    }

    public static void reset() {
        accent = DEFAULT_ACCENT;
        categoryColors = true;
        animations = true;
        sounds = true;
        NovaScale.menuScale = 0;
        apply();
        NovaScale.apply();
    }

    // ---------------------------------------------------------------- Config

    public static void load(Properties properties) {
        accent = parseInt(properties.getProperty("ui.accent"), DEFAULT_ACCENT);
        categoryColors = flag(properties, "ui.categoryColors", "gui.categoryColors", true);
        animations = flag(properties, "ui.animations", "gui.animations", true);
        sounds = flag(properties, "ui.sounds", "gui.sounds", true);
        novaScreens = flag(properties, "ui.novaScreens", "gui.titleScreen", true);
        NovaScale.menuScale = Math.max(0, Math.min(4, parseInt(properties.getProperty("gui.menuScale"), 0)));
        apply();
    }

    public static void save(Properties properties) {
        properties.setProperty("ui.accent", String.valueOf(accent));
        properties.setProperty("ui.categoryColors", String.valueOf(categoryColors));
        properties.setProperty("ui.animations", String.valueOf(animations));
        properties.setProperty("ui.sounds", String.valueOf(sounds));
        properties.setProperty("ui.novaScreens", String.valueOf(novaScreens));
        properties.setProperty("gui.menuScale", String.valueOf(NovaScale.menuScale));
    }

    /** Neuer Schluessel, sonst der aus der alten GUI (Einstellungen gehen beim Wechsel nicht verloren). */
    private static boolean flag(Properties properties, String key, String oldKey, boolean fallback) {
        String value = properties.getProperty(key, properties.getProperty(oldKey));
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
