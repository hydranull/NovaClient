package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import nova.nova_client.client.HudInfoMenu;
import nova.nova_client.client.HudStyle;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.Rect;
import nova.nova_client.client.TpsTracker;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * HUD mit Werten wie FPS, Ping, Koordinaten und Uhrzeit, gezeichnet ueber {@link HudStyle}.
 * Groesse, Seite, sichtbare Werte und das komplette Aussehen sind einstellbar (Rechtsklick auf den Mod).
 */
public class HudInfoMod extends RenderMod {
    public static final float[] SCALES = {0.75F, 1.0F, 1.25F, 1.5F};
    private static final int WARN = 0xFFFFC857;

    // Einstellungen
    /** Aussehen (Farben, Hintergrund, Schrift, Abstaende), einstellbar im HudStyleMenu. */
    public static final HudStyle style = new HudStyle();
    public static int scaleIndex = 1;
    public static boolean right;
    public static boolean showFps = true;
    public static boolean showCoords = true;
    public static boolean showCompass = true;
    public static boolean showNetwork = true;
    public static boolean showClock = true;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = Standard. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    /** Zuletzt gezeichneter Bereich (fuer den Verschiebe-Modus). */
    public static Rect bounds;


    // Tempo
    private double lastX;
    private double lastZ;
    private long lastSpeedSample;
    private float speed;

    private long enabledAt = Util.getMillis();
    /** Zuletzt gezeichnete Zeilen (fuer die Vorschau im Stil-Menue). */
    private static List<HudStyle.Row> lastRows = List.of();

    public HudInfoMod(String name, ModCategory category) {
        super(name, category);
    }

    /** Echte Werte, falls gerade im Spiel, sonst Beispielzeilen. */
    public static List<HudStyle.Row> previewRows() {
        if (!lastRows.isEmpty()) {
            return lastRows;
        }
        return List.of(
                new HudStyle.Row("FPS", "144"),
                new HudStyle.Row("Ping", "182", 0, WARN),
                new HudStyle.Row("TPS", "20.0"),
                new HudStyle.Row("XYZ", "128 64 -256"),
                new HudStyle.Row("Dir", "North -Z"),
                new HudStyle.Row("Time", "18:42"));
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        enabledAt = Util.getMillis();
        if (!enabled) {
            bounds = null;
        }
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new HudInfoMenu(parent);
    }

    // ---------------------------------------------------------------- Werte

    private void sampleSpeed(LocalPlayer player) {
        long now = Util.getMillis();
        long elapsed = now - lastSpeedSample;
        if (elapsed < 100L) {
            return;
        }
        double dx = player.getX() - lastX;
        double dz = player.getZ() - lastZ;
        float instant = (float) (Math.sqrt(dx * dx + dz * dz) * 1000.0D / elapsed);
        // Teleports ignorieren
        if (lastSpeedSample != 0L && instant < 200.0F) {
            speed = speed * 0.55F + instant * 0.45F;
        }
        lastX = player.getX();
        lastZ = player.getZ();
        lastSpeedSample = now;
    }

    private List<HudStyle.Row> rows(Minecraft client, LocalPlayer player) {
        List<HudStyle.Row> rows = new ArrayList<>();
        if (showFps) {
            int fps = client.getFps();
            rows.add(new HudStyle.Row("FPS", String.valueOf(fps), 0, fps >= 60 ? 0 : fps >= 30 ? WARN : NovaGui.DANGER));
        }
        if (showNetwork) {
            int ping = -1;
            if (client.getConnection() != null) {
                PlayerInfo info = client.getConnection().getPlayerInfo(player.getUUID());
                if (info != null) {
                    ping = info.getLatency();
                }
            }
            rows.add(new HudStyle.Row("Ping", ping < 0 ? "--" : String.valueOf(ping),
                    0, ping < 120 ? 0 : ping < 250 ? WARN : NovaGui.DANGER));
            float tps = TpsTracker.tps();
            rows.add(new HudStyle.Row("TPS", tps < 0 ? "--" : String.format(Locale.ROOT, "%.1f", tps),
                    0, tps < 0 || tps >= 18.0F ? 0 : tps >= 12.0F ? WARN : NovaGui.DANGER));
        }
        if (showCoords && !StreamProtectMod.hidesCoords()) {
            int bx = (int) Math.floor(player.getX());
            int by = (int) Math.floor(player.getY());
            int bz = (int) Math.floor(player.getZ());
            rows.add(new HudStyle.Row("XYZ", bx + " " + by + " " + bz));
            if (client.level != null) {
                if (client.level.dimension() == Level.OVERWORLD) {
                    rows.add(new HudStyle.Row("Nether", Math.floorDiv(bx, 8) + " " + Math.floorDiv(bz, 8)));
                } else if (client.level.dimension() == Level.NETHER) {
                    rows.add(new HudStyle.Row("Overworld", bx * 8 + " " + bz * 8));
                }
            }
        }
        if (showCompass) {
            float yaw = ((player.getYRot() % 360.0F) + 540.0F) % 360.0F - 180.0F;
            String[] names = {"South +Z", "West -X", "North -Z", "East +X"};
            int index = Math.floorMod(Math.round((yaw + 360.0F) / 90.0F), 4);
            rows.add(new HudStyle.Row("Dir", names[index]));
            rows.add(new HudStyle.Row("Speed", String.format(Locale.ROOT, "%.1f", speed)));
        }
        if (showClock) {
            LocalTime now = LocalTime.now();
            rows.add(new HudStyle.Row("Time", String.format(Locale.ROOT, "%02d:%02d", now.getHour(), now.getMinute())));
        }
        return rows;
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.getDebugOverlay().showDebugScreen()) {
            bounds = null;
            return;
        }
        sampleSpeed(player);
        Font font = client.font;
        List<HudStyle.Row> rows = rows(client, player);
        lastRows = rows;
        if (rows.isEmpty()) {
            bounds = null;
            return;
        }

        float scale = SCALES[Math.max(0, Math.min(SCALES.length - 1, scaleIndex))];
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        float w = style.width(font, rows) * scale;
        float h = style.height(rows, 0) * scale;
        float left;
        float top;
        if (posX >= 0.0F) {
            // selbst verschoben (Anteil vom Bildschirm)
            left = Math.max(0.0F, Math.min(screenW - w, posX * screenW));
            top = Math.max(0.0F, Math.min(screenH - h, posY * screenH));
        } else {
            // Standard: oben links bzw. rechts, ohne andere HUD-Elemente zu ueberdecken
            top = nova.nova_client.client.HudLayout.top(right ? nova.nova_client.client.HudLayout.Slot.HUD_INFO_RIGHT
                    : nova.nova_client.client.HudLayout.Slot.HUD_INFO_LEFT, Math.round(h));
            left = right ? screenW - 6 - w : 6;
        }
        bounds = new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h));

        float elapsed = (Util.getMillis() - enabledAt) / 1000.0F;
        float intro = NovaGui.easeOutCubic(elapsed / 0.35F);
        if (intro <= 0.01F) {
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        int slide = Math.round((1.0F - intro) * 10.0F);
        g.pose().translate(right ? slide : -slide, 0);
        style.draw(g, font, rows, 0, intro);
        g.pose().popMatrix();
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "scale", String.valueOf(scaleIndex));
        properties.setProperty(prefix + "right", String.valueOf(right));
        properties.setProperty(prefix + "fps", String.valueOf(showFps));
        properties.setProperty(prefix + "coords", String.valueOf(showCoords));
        properties.setProperty(prefix + "compass", String.valueOf(showCompass));
        properties.setProperty(prefix + "network", String.valueOf(showNetwork));
        properties.setProperty(prefix + "clock", String.valueOf(showClock));
        properties.setProperty(prefix + "x", String.valueOf(posX));
        properties.setProperty(prefix + "y", String.valueOf(posY));
        style.save(properties, prefix);
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            scaleIndex = Math.max(0, Math.min(SCALES.length - 1,
                    Integer.parseInt(properties.getProperty(prefix + "scale", String.valueOf(scaleIndex)))));
            posX = Float.parseFloat(properties.getProperty(prefix + "x", String.valueOf(posX)));
            posY = Float.parseFloat(properties.getProperty(prefix + "y", String.valueOf(posY)));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        right = Boolean.parseBoolean(properties.getProperty(prefix + "right", String.valueOf(right)));
        showFps = Boolean.parseBoolean(properties.getProperty(prefix + "fps", String.valueOf(showFps)));
        showCoords = Boolean.parseBoolean(properties.getProperty(prefix + "coords", String.valueOf(showCoords)));
        showCompass = Boolean.parseBoolean(properties.getProperty(prefix + "compass", String.valueOf(showCompass)));
        showNetwork = Boolean.parseBoolean(properties.getProperty(prefix + "network", String.valueOf(showNetwork)));
        showClock = Boolean.parseBoolean(properties.getProperty(prefix + "clock", String.valueOf(showClock)));
        style.load(properties, prefix);
    }
}
