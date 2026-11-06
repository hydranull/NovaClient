package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaType;
import nova.nova_client.client.RadarMenu;
import nova.nova_client.client.Rect;

import java.util.Locale;
import java.util.Properties;
import nova.nova_client.client.NovaShapes;

/**
 * Radar: runder Kompass im Nova-Stil. Himmelsrichtungen drehen sich mit der Blickrichtung, ein Blickkegel zeigt
 * nach vorne, Spieler und Mobs in der Naehe erscheinen als Punkte (Spieler ausserhalb der Reichweite am Rand).
 * Darunter optional die Koordinaten. Verschiebbar ueber den HUD-Editor.
 */
public class RadarMod extends RenderMod {
    public static final float[] SCALES = {0.6F, 0.8F, 1.0F, 1.25F, 1.5F, 2.0F};
    public static final int[] RANGES = {16, 32, 64, 128, 256};
    private static final int RADIUS = 46;
    private static final String[] CARDINALS = {"N", "E", "S", "W"};
    /** Richtung der Himmelsrichtungen als (x, z) in der Welt. */
    private static final int[][] CARDINAL_DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    // Einstellungen
    public static int scaleIndex = 2;
    public static int rangeIndex = 2;
    /** Karte dreht sich mit der Blickrichtung (vorne = oben); sonst Norden oben. */
    public static boolean rotate = true;
    public static boolean players = true;
    public static boolean hostiles = true;
    public static boolean animals = false;
    public static boolean names = true;
    public static boolean coordinates = true;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = Standard. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    /** Zuletzt gezeichneter Bereich (fuer den Verschiebe-Modus). */
    public static Rect bounds;

    private long enabledAt = Util.getMillis();

    public RadarMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        enabledAt = Util.getMillis();
        if (!enabled) {
            bounds = null;
        }
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.getDebugOverlay().showDebugScreen()) {
            bounds = null;
            return;
        }
        Font font = client.font;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        float scale = SCALES[Math.max(0, Math.min(SCALES.length - 1, scaleIndex))];
        int range = RANGES[Math.max(0, Math.min(RANGES.length - 1, rangeIndex))];

        String coords = coordinates ? coordinateText(player) : null;
        int boxW = boxWidth(font, coords);
        int boxH = boxHeight(coords);

        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        float w = boxW * scale;
        float h = boxH * scale;
        float left;
        float top;
        if (posX >= 0.0F) {
            left = Math.max(0.0F, Math.min(screenW - w, posX * screenW));
            top = Math.max(0.0F, Math.min(screenH - h, posY * screenH));
        } else {
            // Standard: oben rechts (erstes Element der rechten Spalte)
            left = screenW - 6 - w;
            top = nova.nova_client.client.HudLayout.top(nova.nova_client.client.HudLayout.Slot.RADAR, Math.round(h));
        }
        bounds = new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h));

        float intro = NovaGui.easeOutCubic((Util.getMillis() - enabledAt) / 350.0F);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        draw(g, font, client, player, boxW, coords, range, partialTick, intro);
        g.pose().popMatrix();
    }

    /** Vorschau im Nova-Menue: das Radar wie im Spiel (in Originalgroesse), linke obere Ecke bei (x, y). */
    public static Rect drawPreview(GuiGraphicsExtractor g, Font font, int x, int y, float a) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return null;
        }
        String coords = coordinates ? coordinateText(player) : null;
        int boxW = boxWidth(font, coords);
        int range = RANGES[Math.max(0, Math.min(RANGES.length - 1, rangeIndex))];
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        draw(g, font, client, player, boxW, coords, range, 1.0F, a);
        g.pose().popMatrix();
        return new Rect(x, y, x + boxW, y + boxHeight(coords));
    }

    private static int boxWidth(Font font, String coords) {
        return coords != null ? Math.max(RADIUS * 2, NovaType.width(font, NovaType.Face.MONO, coords) + 12) : RADIUS * 2;
    }

    private static int boxHeight(String coords) {
        return RADIUS * 2 + (coords != null ? 14 : 0);
    }

    /**
     * Radar ab (0, 0): runde Glasscheibe, Himmelsrichtungen, Punkte, du als Pfeil in der Mitte, darunter die
     * Koordinaten. Alles aus geglaetteten Formen in echter Pixelgroesse - keine Treppenkanten.
     */
    private static void draw(GuiGraphicsExtractor g, Font font, Minecraft client, LocalPlayer player, int boxW, String coords,
                             int range, float partialTick, float intro) {
        float yaw = Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        float yawRad = (float) Math.toRadians(yaw);
        int cx = boxW / 2;
        int cy = RADIUS;
        // Blickrichtung: nach oben, wenn die Karte mitdreht; sonst wie der Blick (Norden oben)
        float facing = rotate ? 0.0F : yawRad + (float) Math.PI;

        drawDisc(g, cx, cy, intro);
        drawCardinals(g, font, cx, cy, yawRad, intro);
        drawEntities(g, font, client, player, cx, cy, yawRad, range, partialTick, intro);
        drawSelf(g, cx, cy, facing, intro);

        if (coords != null) {
            NovaType.Face face = NovaType.Face.MONO;
            int ty = RADIUS * 2 + 2;
            int tw = NovaType.width(font, face, coords) + 12;
            int x0 = cx - tw / 2;
            NovaShapes.rect(g, x0, ty, x0 + tw, ty + 12, 6, NovaGui.alpha(NovaGui.HUD_BACKGROUND, intro));
            NovaShapes.outline(g, x0, ty, x0 + tw, ty + 12, 6, NovaGui.alpha(NovaGui.HUD_BORDER, intro));
            NovaType.draw(g, font, face, coords, x0 + 6, NovaType.centerY(face, ty, 12), NovaGui.alpha(NovaGui.TEXT, intro));
        }
    }

    private static String coordinateText(LocalPlayer player) {
        return String.format(Locale.ROOT, "%d  %d  %d", Mth.floor(player.getX()), Mth.floor(player.getY()),
                Mth.floor(player.getZ()));
    }

    /** Scheibe: dunkles Glas mit feiner Kante, ein zarter innerer Ring und leise Achsen. */
    private static void drawDisc(GuiGraphicsExtractor g, int cx, int cy, float intro) {
        NovaShapes.disc(g, cx, cy, RADIUS, NovaGui.alpha(NovaGui.HUD_BACKGROUND, intro));
        NovaShapes.ring(g, cx, cy, RADIUS, 1.0F, NovaGui.alpha(0x2AFFFFFF, intro));
        NovaShapes.ring(g, cx, cy, RADIUS * 0.5F, 1.0F, NovaGui.alpha(0x10FFFFFF, intro));
        int axis = NovaGui.alpha(0x0CFFFFFF, intro);
        NovaShapes.beam(g, cx - RADIUS + 5, cy, cx + RADIUS - 5, cy, 1.0F, axis);
        NovaShapes.beam(g, cx, cy - RADIUS + 5, cx, cy + RADIUS - 5, 1.0F, axis);
    }

    /** Du in der Mitte: kleiner Pfeil in Blickrichtung. */
    private static void drawSelf(GuiGraphicsExtractor g, int cx, int cy, float angle, float intro) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate(angle);
        NovaShapes.triangle(g, -3.0F, -4.5F, 3.0F, 3.5F, NovaGui.alpha(NovaGui.TEXT, intro));
        g.pose().popMatrix();
    }

    /** Himmelsrichtungen innen am Rand in der Menue-Schrift: Norden hell, die anderen gedaempft. */
    private static void drawCardinals(GuiGraphicsExtractor g, Font font, int cx, int cy, float yawRad, float intro) {
        NovaType.Face face = NovaType.Face.CAPTION;
        float labelRadius = RADIUS - 8;
        for (int i = 0; i < CARDINALS.length; i++) {
            float[] p = toScreen(CARDINAL_DIRS[i][0], CARDINAL_DIRS[i][1], yawRad);
            int x = Math.round(cx + p[0] * labelRadius);
            int y = Math.round(cy + p[1] * labelRadius);
            NovaType.centered(g, font, face, CARDINALS[i], x, NovaType.centerY(face, y - 5, 10),
                    NovaGui.alpha(i == 0 ? NovaGui.TEXT : NovaGui.TEXT_MUTED, intro));
        }
    }

    private static void drawEntities(GuiGraphicsExtractor g, Font font, Minecraft client, LocalPlayer self,
                                     int cx, int cy, float yawRad, int range, float partialTick, float intro) {
        Vec3 origin = self.getPosition(partialTick);
        float edge = RADIUS - 3;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == self || !entity.isAlive()) {
                continue;
            }
            int color;
            boolean isPlayer = entity instanceof Player;
            if (isPlayer) {
                if (!players || entity.isSpectator()) {
                    continue;
                }
                color = NovaGui.TEXT;
            } else if (entity instanceof Enemy) {
                if (!hostiles) {
                    continue;
                }
                color = 0xFFFF6B6B;
            } else if (entity instanceof Mob) {
                if (!animals) {
                    continue;
                }
                color = 0xFF6BE39A;
            } else {
                continue;
            }

            Vec3 pos = entity.getPosition(partialTick);
            double dx = pos.x - origin.x;
            double dz = pos.z - origin.z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            boolean outside = distance > range;
            if (!isPlayer && outside) {
                continue; // Mobs nur in Reichweite, Spieler auch ausserhalb (am Rand)
            }
            float[] p = toScreen(dx, dz, yawRad);
            float r = (float) Math.min(1.0D, distance / range) * edge;
            float length = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1]);
            float px = length > 0.0001F ? cx + p[0] / length * r : cx;
            float py = length > 0.0001F ? cy + p[1] / length * r : cy;
            if (isPlayer) {
                NovaShapes.ring(g, px, py, 4.0F, 1.0F, NovaGui.alpha(NovaGui.alpha(NovaGui.BRAND, 0.6F), intro));
                NovaShapes.disc(g, px, py, 2.2F, NovaGui.alpha(NovaGui.alpha(color, outside ? 0.55F : 1.0F), intro));
                if (names) {
                    NovaType.Face face = NovaType.Face.CAPTION;
                    NovaType.centered(g, font, face, entity.getName().getString(), Math.round(px), Math.round(py) + 5,
                            NovaGui.alpha(NovaGui.TEXT_DIM, intro));
                }
            } else {
                NovaShapes.disc(g, px, py, 1.5F, NovaGui.alpha(NovaGui.alpha(color, 0.9F), intro));
            }
        }
    }

    /**
     * Weltrichtung (dx, dz) auf Radar-Richtung (x rechts, y unten). Dreht die Karte mit, ist vorne oben,
     * sonst ist Norden oben.
     */
    private static float[] toScreen(double dx, double dz, float yawRad) {
        if (!rotate) {
            return new float[]{(float) dx, (float) dz};
        }
        // Blickrichtung und rechte Seite des Spielers in der Welt (Minecraft: Yaw 0 = Sueden)
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        return new float[]{(float) (dx * rightX + dz * rightZ), (float) -(dx * forwardX + dz * forwardZ)};
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new RadarMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "size", String.valueOf(SCALES[scaleIndex]));
        properties.setProperty(prefix + "range", String.valueOf(RANGES[rangeIndex]));
        properties.setProperty(prefix + "rotate", String.valueOf(rotate));
        properties.setProperty(prefix + "players", String.valueOf(players));
        properties.setProperty(prefix + "hostiles", String.valueOf(hostiles));
        properties.setProperty(prefix + "animals", String.valueOf(animals));
        properties.setProperty(prefix + "names", String.valueOf(names));
        properties.setProperty(prefix + "coordinates", String.valueOf(coordinates));
        properties.setProperty(prefix + "x", String.valueOf(posX));
        properties.setProperty(prefix + "y", String.valueOf(posY));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            String size = properties.getProperty(prefix + "size");
            if (size != null) {
                scaleIndex = nearest(SCALES, Float.parseFloat(size.trim()));
            }
            String range = properties.getProperty(prefix + "range");
            if (range != null) {
                int value = Integer.parseInt(range.trim());
                for (int i = 0; i < RANGES.length; i++) {
                    if (RANGES[i] == value) {
                        rangeIndex = i;
                    }
                }
            }
            posX = Float.parseFloat(properties.getProperty(prefix + "x", String.valueOf(posX)));
            posY = Float.parseFloat(properties.getProperty(prefix + "y", String.valueOf(posY)));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        rotate = Boolean.parseBoolean(properties.getProperty(prefix + "rotate", String.valueOf(rotate)));
        players = Boolean.parseBoolean(properties.getProperty(prefix + "players", String.valueOf(players)));
        hostiles = Boolean.parseBoolean(properties.getProperty(prefix + "hostiles", String.valueOf(hostiles)));
        animals = Boolean.parseBoolean(properties.getProperty(prefix + "animals", String.valueOf(animals)));
        names = Boolean.parseBoolean(properties.getProperty(prefix + "names", String.valueOf(names)));
        coordinates = Boolean.parseBoolean(properties.getProperty(prefix + "coordinates", String.valueOf(coordinates)));
    }

    private static int nearest(float[] values, float value) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) {
                best = i;
            }
        }
        return best;
    }
}
