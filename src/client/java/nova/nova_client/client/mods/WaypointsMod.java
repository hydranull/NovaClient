package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.Compat;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.WaypointsMenu;
import nova.nova_client.client.WorldToScreen;
import nova.nova_client.client.waypoints.Waypoint;
import nova.nova_client.client.waypoints.WaypointStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import nova.nova_client.client.NovaShapes;

/**
 * Waypoints: Wegpunkte setzen und in der Welt anzeigen - Lichtsaeule durch alle Bloecke, Rahmen um den Block und ein
 * Schild mit Name und Entfernung. Dauerhafte Wegpunkte bleiben, bis man sie loescht. Temporaere verschwinden von
 * selbst: nach einer einstellbaren Zeit, sobald ihr Chunk entladen wird, oder bei dem, was zuerst passiert.
 * Hinzufuegen per Taste (an deiner Position) oder im Menue. Gespeichert pro Welt/Server und Dimension.
 */
public class WaypointsMod extends GameTickMod {
    /** Wann temporaere Wegpunkte verschwinden. */
    public enum Removal {
        TIME("After time"),
        CHUNK("Chunk unload"),
        BOTH("Time or unload");

        public final String label;

        Removal(String label) {
            this.label = label;
        }

        public boolean byTime() {
            return this != CHUNK;
        }

        public boolean byChunk() {
            return this != TIME;
        }
    }

    public static final int[] COLORS = {0x9B6BFF, 0x4DA6FF, 0x2EE6D6, 0x4ADE80, 0xFACC15, 0xFF9F43, 0xFF5C7A, 0xFF7AD9, 0xFFFFFF};
    public static final String[] COLOR_NAMES = {"Violet", "Blue", "Cyan", "Green", "Yellow", "Orange", "Red", "Pink", "White"};

    private static final double MIN_LIFETIME = 0.5D;
    private static final double MAX_LIFETIME = 240.0D;
    /** Bis zu dieser Entfernung bekommt der Wegpunkt-Block zusaetzlich einen Rahmen. */
    private static final double BOX_DISTANCE = 96.0D;

    // Einstellungen
    public static Removal removal = Removal.TIME;
    /** Lebensdauer neuer temporaerer Wegpunkte in Minuten. */
    public static double lifetime = 10.0D;
    public static int addKey = NO_KEY;
    public static int tempKey = NO_KEY;
    public static boolean beam = true;
    public static boolean labels = true;
    public static double labelScale = 1.0D;
    /** Chat-Zeile, wenn ein temporaerer Wegpunkt verschwindet. */
    public static boolean removalMessage = true;

    private static volatile boolean enabled;
    private static boolean addWasDown;
    private static boolean tempWasDown;
    private static int saveTicks;
    private static final Map<Integer, GizmoStyle> BOX_STYLES = new HashMap<>();

    public WaypointsMod(String name, ModCategory category) {
        super(name, category);
        // Ablauf laeuft immer - auch mit Modul aus und ausserhalb einer Welt (Verlassen der Welt entlaedt jeden Chunk)
        ClientTickEvents.END_CLIENT_TICK.register(WaypointsMod::expireTick);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        WaypointsMod.enabled = enabled;
        addWasDown = false;
        tempWasDown = false;
    }

    /** Tasten (nur mit Modul an, im Spiel ohne offenes Menue). */
    @Override
    public void execute(Minecraft client) {
        boolean inGame = client.gui.screen() == null;
        boolean addDown = addKey != NO_KEY && InputConstants.isKeyDown(addKey);
        boolean tempDown = tempKey != NO_KEY && InputConstants.isKeyDown(tempKey);
        if (addDown && !addWasDown && inGame) {
            announceAdded(client, addHere(client, false));
        }
        if (tempDown && !tempWasDown && inGame) {
            announceAdded(client, addHere(client, true));
        }
        addWasDown = addDown;
        tempWasDown = tempDown;
    }

    // ---------------------------------------------------------------- Anlegen

    /** Neuer Wegpunkt an deiner Position (null = gerade in keiner Welt). */
    public static Waypoint addHere(Minecraft client, boolean temporary) {
        String world = WaypointStore.currentWorld();
        if (client.player == null || world == null) {
            return null;
        }
        List<Waypoint> existing = WaypointStore.inWorld(world);
        BlockPos pos = client.player.blockPosition();
        Waypoint waypoint = new Waypoint(freeName(existing, temporary ? "Temp" : "Waypoint"), world,
                WaypointStore.currentDimension(), pos.getX(), pos.getY(), pos.getZ(),
                COLORS[existing.size() % COLORS.length]);
        if (temporary) {
            makeTemporary(waypoint);
        }
        WaypointStore.add(waypoint);
        return waypoint;
    }

    /** "Waypoint 3": die kleinste Nummer, die es in dieser Welt noch nicht gibt. */
    private static String freeName(List<Waypoint> existing, String base) {
        for (int n = 1; ; n++) {
            String name = base + " " + n;
            if (existing.stream().noneMatch(w -> w.name.equalsIgnoreCase(name))) {
                return name;
            }
        }
    }

    /** Macht einen Wegpunkt temporaer; die Zeit laeuft ab jetzt mit der eingestellten Lebensdauer. */
    public static void makeTemporary(Waypoint waypoint) {
        waypoint.temporary = true;
        restartTimer(waypoint);
        waypoint.chunkLoaded = WaypointStore.chunkLoaded(waypoint);
        WaypointStore.changed();
    }

    public static void restartTimer(Waypoint waypoint) {
        waypoint.expiresAt = System.currentTimeMillis() + Math.round(lifetime * 60_000.0D);
        WaypointStore.changed();
    }

    private static void announceAdded(Minecraft client, Waypoint waypoint) {
        if (waypoint == null || client.player == null) {
            return;
        }
        String kind = waypoint.temporary ? "Temporary waypoint" : "Waypoint";
        Compat.actionBar(client.player, Component.literal("§d[Waypoints]§r " + kind + " §f" + waypoint.name
                + "§r added at §f" + waypoint.coords()));
        NovaNotify.sound(SoundEvents.NOTE_BLOCK_PLING, 1.6F);
    }

    /** Alle temporaeren Wegpunkte der aktuellen Welt loeschen; gibt die Anzahl zurueck. */
    public static int clearTemporary() {
        String world = WaypointStore.currentWorld();
        int removed = 0;
        for (Iterator<Waypoint> it = WaypointStore.all().iterator(); it.hasNext(); ) {
            Waypoint waypoint = it.next();
            if (waypoint.temporary && waypoint.world.equals(world)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            WaypointStore.changed();
            WaypointStore.saveIfDirty();
        }
        return removed;
    }

    public static int countTemporary() {
        int count = 0;
        for (Waypoint waypoint : WaypointStore.inWorld(WaypointStore.currentWorld())) {
            if (waypoint.temporary) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- Ablauf temporaerer Wegpunkte

    /** Jeden Client-Tick: abgelaufene bzw. entladene temporaere Wegpunkte entfernen, Aenderungen speichern. */
    private static void expireTick(Minecraft client) {
        long now = System.currentTimeMillis();
        List<String> byTime = new ArrayList<>();
        List<String> byChunk = new ArrayList<>();
        for (Iterator<Waypoint> it = WaypointStore.all().iterator(); it.hasNext(); ) {
            Waypoint waypoint = it.next();
            if (!waypoint.temporary) {
                continue;
            }
            boolean loaded = WaypointStore.chunkLoaded(waypoint);
            boolean unloaded = waypoint.chunkLoaded && !loaded;
            if (waypoint.chunkLoaded != loaded) {
                waypoint.chunkLoaded = loaded;
                WaypointStore.changed();
            }
            if (removal.byTime() && now >= waypoint.expiresAt) {
                it.remove();
                byTime.add(waypoint.displayName());
            } else if (removal.byChunk() && unloaded) {
                it.remove();
                byChunk.add(waypoint.displayName());
            }
        }
        if (!byTime.isEmpty() || !byChunk.isEmpty()) {
            WaypointStore.changed();
            WaypointStore.saveIfDirty();
            if (removalMessage) {
                announceRemoved(byTime, "time is up");
                announceRemoved(byChunk, "chunk unloaded");
            }
        }
        // Sonstige Aenderungen (z.B. Umbenennen im Menue) hoechstens einmal pro Sekunde speichern
        if (++saveTicks >= 20) {
            saveTicks = 0;
            WaypointStore.saveIfDirty();
        }
    }

    private static void announceRemoved(List<String> names, String reason) {
        if (names.size() == 1) {
            NovaNotify.chat("Waypoints", "§7Temporary waypoint §f" + names.get(0) + "§7 removed (" + reason + ")");
        } else if (names.size() > 1) {
            NovaNotify.chat("Waypoints", "§f" + names.size() + "§7 temporary waypoints removed (" + reason + ")");
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Wegpunkte, die gerade in der Welt zu sehen sind (aktuelle Welt und Dimension, nicht ausgeblendet). */
    private static List<Waypoint> shown() {
        List<Waypoint> result = new ArrayList<>();
        String world = WaypointStore.currentWorld();
        String dimension = WaypointStore.currentDimension();
        if (world == null) {
            return result;
        }
        for (Waypoint waypoint : WaypointStore.all()) {
            if (waypoint.visible && waypoint.world.equals(world) && waypoint.dimension.equals(dimension)) {
                result.add(waypoint);
            }
        }
        return result;
    }

    /** Lichtsaeulen und Block-Rahmen. Wird jeden Frame aufgerufen, waehrend ein Gizmo-Collector aktiv ist. */
    public static void emitGizmos() {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || !beam || client.level == null || client.player == null) {
            return;
        }
        Vec3 camera = client.gameRenderer.mainCamera().position();
        // Weiter weg als die Sichtweite wuerde die Saeule abgeschnitten -> in Richtung des Wegpunkts naeher heranholen
        double maxDistance = Math.max(64.0D, client.options.getEffectiveRenderDistance() * 16.0D * 2.0D);
        double minY = client.level.getMinY();
        double maxY = client.level.getMaxY() + 1.0D;
        for (Waypoint waypoint : shown()) {
            double x = waypoint.x + 0.5D;
            double z = waypoint.z + 0.5D;
            double dx = x - camera.x;
            double dz = z - camera.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            boolean far = horizontal > maxDistance;
            if (far) {
                x = camera.x + dx * maxDistance / horizontal;
                z = camera.z + dz * maxDistance / horizontal;
            }
            // Direkt am Wegpunkt blasser, damit die Saeule nicht die Sicht verdeckt
            float fade = (float) Mth.clamp((horizontal - 1.0D) / 8.0D, 0.15D, 1.0D);
            int rgb = waypoint.color & 0xFFFFFF;
            Vec3 bottom = new Vec3(x, minY, z);
            Vec3 top = new Vec3(x, maxY, z);
            Gizmos.line(bottom, top, argb(rgb, 0.25F * fade), 9.0F).setAlwaysOnTop();
            Gizmos.line(bottom, top, argb(rgb, 0.9F * fade), 2.5F).setAlwaysOnTop();
            if (!far && waypoint.center().distanceTo(camera) <= BOX_DISTANCE) {
                GizmoStyle style = BOX_STYLES.computeIfAbsent(rgb,
                        c -> GizmoStyle.strokeAndFill(0xFF000000 | c, 2.0F, 0x30000000 | c));
                Gizmos.cuboid(new BlockPos(waypoint.x, waypoint.y, waypoint.z), style).setAlwaysOnTop();
            }
        }
    }

    /** Schilder mit Name, Entfernung und Restzeit; aus dem HUD-Callback, jeden Frame (nach WorldToScreen.begin()). */
    public static void drawHud(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        if (!enabled || !labels || client.level == null || client.player == null
                || client.getDebugOverlay().showDebugScreen()) {
            return;
        }
        List<Waypoint> waypoints = shown();
        if (waypoints.isEmpty()) {
            return;
        }
        Vec3 camera = WorldToScreen.camera();
        // Ferne zuerst, damit nahe Schilder obenauf liegen
        waypoints.sort(Comparator.comparingDouble((Waypoint w) -> w.center().distanceToSqr(camera)).reversed());
        Font font = client.font;
        long now = System.currentTimeMillis();
        float scale = (float) labelScale;
        for (Waypoint waypoint : waypoints) {
            Vec3 at = new Vec3(waypoint.x + 0.5D, waypoint.y + 1.25D, waypoint.z + 0.5D);
            float[] screen = WorldToScreen.project(at);
            if (screen == null) {
                continue;
            }
            String name = NovaGui.ellipsize(font, waypoint.displayName(), 160);
            String info = formatDistance(at.distanceTo(camera));
            if (waypoint.temporary && removal.byTime()) {
                info += "  " + formatRemaining(waypoint.expiresAt - now);
            }
            int nameWidth = NovaGui.width(font, name);
            int width = 18 + nameWidth + 6 + NovaGui.width(font, info) + 6;
            int color = 0xFF000000 | waypoint.color;

            g.pose().pushMatrix();
            g.pose().translate(screen[0], screen[1]);
            g.pose().scale(scale, scale);
            int x0 = -width / 2;
            int y0 = -7;
            NovaShapes.rect(g, x0, y0, x0 + width, y0 + 14, 4, NovaGui.HUD_BACKGROUND);
            NovaShapes.outline(g, x0, y0, x0 + width, y0 + 14, 4, NovaGui.alpha(color, 0.75F));
            NovaShapes.rect(g, x0 + 6, y0 + 4, x0 + 12, y0 + 10, 3, color);
            NovaGui.text(g, font, name, x0 + 18, y0 + 3, 0xFFFFFFFF);
            NovaGui.text(g, font, info, x0 + 18 + nameWidth + 6, y0 + 3, 0xFFB4B8C8);
            g.pose().popMatrix();
        }
    }

    private static int argb(int rgb, float alpha) {
        return (Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24) | (rgb & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- Texte

    /** "84m" bzw. "1.2km". */
    public static String formatDistance(double distance) {
        return distance >= 1000.0D ? String.format(Locale.ROOT, "%.1fkm", distance / 1000.0D) : Math.round(distance) + "m";
    }

    /** Restzeit "4:05" bzw. "1:04:05". */
    public static String formatRemaining(long millis) {
        long seconds = Math.max(0L, (millis + 999L) / 1000L);
        long hours = seconds / 3600L;
        return hours > 0L
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60L % 60L, seconds % 60L)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    /** Lebensdauer fuer den Regler: "30 s", "10 min", "1.5 min", "2 h 30 min". */
    private static String formatLifetime(double minutes) {
        if (minutes < 1.0D) {
            return Math.round(minutes * 60.0D) + " s";
        }
        if (minutes < 60.0D) {
            return (minutes == Math.rint(minutes) ? String.valueOf(Math.round(minutes))
                    : String.format(Locale.ROOT, "%.1f", minutes)) + " min";
        }
        long total = Math.round(minutes);
        return total % 60L == 0L ? total / 60L + " h" : total / 60L + " h " + total % 60L + " min";
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    /** Wegpunkt-Liste; die Einstellungen liegen dort hinter "Settings". */
    @Override
    public Screen createSettings(Screen parent) {
        return new WaypointsMenu(parent);
    }

    public static Screen settingsScreen(Screen parent) {
        String[] removals = new String[Removal.values().length];
        for (Removal value : Removal.values()) {
            removals[value.ordinal()] = value.label;
        }
        return new ModOptionsMenu(parent, "Waypoint Settings", "Keys work while the module is enabled", List.of(
                new ModOptionsMenu.Choice("Remove temporary", removals, () -> removal.ordinal(),
                        v -> removal = Removal.values()[v]),
                new ModOptionsMenu.Slider("Temporary lifetime", MIN_LIFETIME, MAX_LIFETIME, 0.5D, () -> lifetime,
                        v -> lifetime = v, WaypointsMod::formatLifetime),
                new ModOptionsMenu.KeyBind("Add waypoint key", () -> addKey, v -> addKey = v),
                new ModOptionsMenu.KeyBind("Add temporary key", () -> tempKey, v -> tempKey = v),
                new ModOptionsMenu.Toggle("Light beam", () -> beam, v -> beam = v),
                new ModOptionsMenu.Toggle("Labels", () -> labels, v -> labels = v),
                new ModOptionsMenu.Slider("Label size", 0.5D, 2.0D, 0.1D, () -> labelScale,
                        v -> labelScale = v, v -> String.format(Locale.ROOT, "%.1fx", v)),
                new ModOptionsMenu.Toggle("Message when removed", () -> removalMessage, v -> removalMessage = v),
                new ModOptionsMenu.Action(() -> "Delete temporary waypoints (" + countTemporary() + ")",
                        WaypointsMod::clearTemporary),
                new ModOptionsMenu.Info(() -> switch (removal) {
                    case TIME -> "Temporary waypoints vanish after the lifetime";
                    case CHUNK -> "Temporary waypoints vanish when their chunk unloads";
                    case BOTH -> "Temporary: lifetime or chunk unload, whichever is first";
                })));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "removal", removal.name());
        properties.setProperty(prefix + "lifetime", String.valueOf(lifetime));
        properties.setProperty(prefix + "addKey", String.valueOf(addKey));
        properties.setProperty(prefix + "tempKey", String.valueOf(tempKey));
        properties.setProperty(prefix + "beam", String.valueOf(beam));
        properties.setProperty(prefix + "labels", String.valueOf(labels));
        properties.setProperty(prefix + "labelScale", String.valueOf(labelScale));
        properties.setProperty(prefix + "removalMessage", String.valueOf(removalMessage));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            removal = Removal.valueOf(properties.getProperty(prefix + "removal", removal.name()).trim());
        } catch (IllegalArgumentException ignored) {
            // Standard behalten
        }
        lifetime = Mth.clamp(parseDouble(properties.getProperty(prefix + "lifetime"), lifetime), MIN_LIFETIME, MAX_LIFETIME);
        addKey = parseInt(properties.getProperty(prefix + "addKey"), addKey);
        tempKey = parseInt(properties.getProperty(prefix + "tempKey"), tempKey);
        beam = Boolean.parseBoolean(properties.getProperty(prefix + "beam", String.valueOf(beam)));
        labels = Boolean.parseBoolean(properties.getProperty(prefix + "labels", String.valueOf(labels)));
        labelScale = Mth.clamp(parseDouble(properties.getProperty(prefix + "labelScale"), labelScale), 0.5D, 2.0D);
        removalMessage = Boolean.parseBoolean(properties.getProperty(prefix + "removalMessage", String.valueOf(removalMessage)));
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
