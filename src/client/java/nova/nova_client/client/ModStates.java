package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import nova.nova_client.client.mods.Mod;

import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Welche Module an sind, bleibt ueber Neustarts erhalten: beim Speichern merkt sich Nova die aktiven Module, beim
 * ersten Betreten einer Welt pro Spielstart werden sie wieder eingeschaltet (erst dann, weil viele Module eine Welt
 * brauchen). Bei einer frischen Installation sind es die {@link #DEFAULTS} - die wichtigsten Anzeigen, damit Nova
 * sofort nach etwas aussieht.
 * <p>
 * Module, die von selbst etwas tun (Bots, Bewegung, Kaeufe) oder nur fuer einen Moment gedacht sind, werden nie
 * automatisch eingeschaltet ({@link #NEVER_RESTORE}).
 */
public final class ModStates {
    /** Beim allerersten Start an (Namen wie in {@link Mods}). */
    private static final Set<String> DEFAULTS = Set.of(
            "HudInfo", "Hotkeys", "PotionHUD", "Radar", "Region Map", "Spotify", "Weather Notifier", "Fullbright",
            "Name tags", "Block Overlay", "Crosshair", "Jump Circles", "Motion Blur", "Low Health Warning",
            "Damage Numbers", "Sprint", "Target HUD", "Discord Presence");
    /** Nie automatisch an - auch wenn sie beim Beenden an waren. */
    private static final Set<String> NEVER_RESTORE = Set.of(
            "Freecam", "Freelook", "Flight", "Boat Fly", "Auto Walk", "Zoom", "AuctionSniper", "Auto Sell",
            "TpaHere Macro", "Spawner Protect", "Schematic Builder", "Tunnel Base Finder", "Seed Finder", "Macros",
            "Donut Base Finder");
    private static final String KEY = "states.active";
    /** Nach einer Aenderung so viele Ticks warten, dann speichern (mehrere Klicks = einmal speichern). */
    private static final int SAVE_DELAY_TICKS = 40;

    private static Set<String> pending;
    private static boolean applied;
    private static int saveIn = -1;

    private ModStates() {
    }

    static void load(Properties properties) {
        String saved = properties.getProperty(KEY);
        pending = saved == null ? new HashSet<>(DEFAULTS) : new HashSet<>(Set.of(saved.isBlank() ? new String[0] : saved.split(",")));
    }

    /** Ohne Config-Datei (erster Start): die Standard-Module. */
    static void loadDefaults() {
        pending = new HashSet<>(DEFAULTS);
    }

    static void save(Properties properties, Map<Mod, String> ids) {
        Set<String> active = new HashSet<>();
        for (Map.Entry<Mod, String> entry : ids.entrySet()) {
            Mod mod = entry.getKey();
            if (applied ? mod.active : wanted(mod, entry.getValue())) {
                active.add(entry.getValue());
            }
        }
        properties.setProperty(KEY, String.join(",", active));
    }

    /** Ein Modul wurde umgeschaltet: bald speichern. */
    public static void changed() {
        if (applied) {
            saveIn = SAVE_DELAY_TICKS;
        }
    }

    /** Jeden Tick (auch ohne Welt). */
    public static void tick(Minecraft client) {
        if (!applied && client.player != null && client.level != null) {
            apply(client);
        }
        if (saveIn >= 0 && --saveIn < 0) {
            ModConfig.save();
        }
    }

    private static void apply(Minecraft client) {
        applied = true;
        if (pending == null) {
            return;
        }
        Map<Mod, String> ids = ModConfig.ids();
        for (Mod mod : Mods.mods) {
            if (wanted(mod, ids.get(mod)) && !mod.active && !NEVER_RESTORE.contains(mod.name)) {
                mod.toggle(client);
            }
        }
        pending = null;
    }

    /** Gespeichert wird die ID, die Standard-Liste nennt Namen. */
    private static boolean wanted(Mod mod, String id) {
        return pending != null && (pending.contains(id) || pending.contains(mod.name));
    }
}
