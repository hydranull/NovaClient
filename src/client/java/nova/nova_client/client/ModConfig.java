package nova.nova_client.client;

import net.fabricmc.loader.api.FabricLoader;
import nova.nova_client.client.mods.BlockEspMod;
import nova.nova_client.client.mods.MacroMod;
import nova.nova_client.client.mods.Mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Speichert Keybinds, aktive Module ({@link ModStates}), BlockESP-Auswahl und Macros in config/nova_client.properties.
 */
public final class ModConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("nova_client.properties");

    private ModConfig() {
    }

    /** Eindeutige ID pro Mod (gleichnamige Mods bekommen _2, _3, ...). */
    static Map<Mod, String> ids() {
        Map<Mod, String> ids = new IdentityHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Mod mod : Mods.mods) {
            String base = mod.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
            int n = seen.merge(base, 1, Integer::sum);
            ids.put(mod, n == 1 ? base : base + "_" + n);
        }
        return ids;
    }

    /** Umbenannte Module: die alten Einstellungen (und Tasten) unter dem neuen Namen weiterverwenden. */
    static void migrateRenamed(Properties properties) {
        rename(properties, "auto_farm_builder", "schematic_builder");
    }

    private static void rename(Properties properties, String oldId, String newId) {
        String oldPrefix = "mod." + oldId + ".";
        for (String key : properties.stringPropertyNames()) {
            String moved = null;
            if (key.startsWith(oldPrefix)) {
                moved = "mod." + newId + "." + key.substring(oldPrefix.length());
            } else if (key.equals("keybind." + oldId)) {
                moved = "keybind." + newId;
            }
            if (moved != null && properties.getProperty(moved) == null) {
                properties.setProperty(moved, properties.getProperty(key));
            }
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void load() {
        if (!Files.exists(FILE)) {
            ModStates.loadDefaults();
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            properties.load(in);
        } catch (IOException e) {
            System.err.println("[Nova] Could not load config: " + e.getMessage());
            return;
        }
        migrateRenamed(properties);

        for (Map.Entry<Mod, String> entry : ids().entrySet()) {
            entry.getKey().keyCode = parseInt(properties.getProperty("keybind." + entry.getValue()), Mod.NO_KEY);
            entry.getKey().loadSettings(properties, "mod." + entry.getValue() + ".");
        }

        ModStates.load(properties);
        UiSettings.load(properties);
        Profiles.current = properties.getProperty("profile.current", "");

        String blocks = properties.getProperty("blockesp.blocks");
        if (blocks != null) {
            BlockEspMod.loadSelectedIds(blocks.isBlank() ? java.util.List.of() : Arrays.asList(blocks.split(",")));
        }

        MacroMod.MACROS.clear();
        int macroCount = parseInt(properties.getProperty("macro.count"), 0);
        for (int i = 0; i < macroCount; i++) {
            String prefix = "macro." + i + ".";
            Macro macro = new Macro(
                    properties.getProperty(prefix + "name", "Macro " + (i + 1)),
                    parseInt(properties.getProperty(prefix + "key"), Mod.NO_KEY));

            int stepCount = parseInt(properties.getProperty(prefix + "steps"), -1);
            if (stepCount >= 0) {
                for (int j = 0; j < stepCount; j++) {
                    String stepPrefix = prefix + "step." + j + ".";
                    Macro.Step step = new Macro.Step();
                    try {
                        step.type = Macro.StepType.valueOf(properties.getProperty(stepPrefix + "type", "WAIT"));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    step.key = properties.getProperty(stepPrefix + "key", "");
                    step.text = properties.getProperty(stepPrefix + "text", "");
                    step.ticks = parseInt(properties.getProperty(stepPrefix + "ticks"), 1);
                    macro.steps.add(step);
                }
            } else {
                // Altes Format: "Aktion; Aktion; ..." -> Text-Schritte
                for (String part : properties.getProperty(prefix + "actions", "").split(";")) {
                    if (!part.isBlank()) {
                        macro.steps.add(Macro.Step.text(part.trim()));
                    }
                }
            }
            MacroMod.MACROS.add(macro);
        }
    }

    public static void save() {
        Properties properties = new Properties();
        // Neuanfang erledigt (NovaReset): beim naechsten Start nichts loeschen
        properties.setProperty(NovaReset.KEY, String.valueOf(NovaReset.EPOCH));
        for (Map.Entry<Mod, String> entry : ids().entrySet()) {
            if (entry.getKey().hasKey()) {
                properties.setProperty("keybind." + entry.getValue(), String.valueOf(entry.getKey().keyCode));
            }
            entry.getKey().saveSettings(properties, "mod." + entry.getValue() + ".");
        }

        properties.setProperty("blockesp.blocks", String.join(",", BlockEspMod.selectedIds()));
        ModStates.save(properties, ids());

        UiSettings.save(properties);
        properties.setProperty("profile.current", Profiles.current);

        properties.setProperty("macro.count", String.valueOf(MacroMod.MACROS.size()));
        for (int i = 0; i < MacroMod.MACROS.size(); i++) {
            Macro macro = MacroMod.MACROS.get(i);
            String prefix = "macro." + i + ".";
            properties.setProperty(prefix + "name", macro.name);
            properties.setProperty(prefix + "key", String.valueOf(macro.keyCode));
            properties.setProperty(prefix + "steps", String.valueOf(macro.steps.size()));
            for (int j = 0; j < macro.steps.size(); j++) {
                Macro.Step step = macro.steps.get(j);
                String stepPrefix = prefix + "step." + j + ".";
                properties.setProperty(stepPrefix + "type", step.type.name());
                properties.setProperty(stepPrefix + "key", step.key);
                properties.setProperty(stepPrefix + "text", step.text);
                properties.setProperty(stepPrefix + "ticks", String.valueOf(step.ticks));
            }
        }

        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                properties.store(out, "Nova Client");
            }
        } catch (IOException e) {
            System.err.println("[Nova] Could not save config: " + e.getMessage());
        }
    }
}
