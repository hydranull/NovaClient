package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import nova.nova_client.client.HotkeyListMenu;
import nova.nova_client.client.HudCards;
import nova.nova_client.client.Macro;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.Mods;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Hotkeys: verschiebbare Liste aller belegten Tasten (Mods und Macros), jede Taste als eigene Karte
 * ({@link HudCards}). Links ein kleiner Punkt (an = Akzent, aus = grau, Macro = zweite Akzentfarbe) und der Name,
 * rechts die Taste als Tastenkappe; ausgeschaltete Mods sind gedimmt.
 */
public class HotkeyListMod extends RenderMod {
    public static final float[] SCALES = {0.45F, 0.6F, 0.75F, 1.0F, 1.25F, 1.5F};
    /** Text beginnt nach dem Zustands-Punkt. */
    private static final int TEXT_X = HudCards.DOT_TEXT;
    private static final int GAP = 10;
    private static final int OFF_DOT = 0xFF5A5A64;

    // Einstellungen
    public static int scaleIndex = 3;
    public static boolean showMacros = true;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = Standard. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    /** Zuletzt gezeichneter Bereich (fuer den Verschiebe-Modus). */
    public static Rect bounds;

    private long enabledAt = Util.getMillis();

    /** Eine Zeile: Taste, Beschreibung, Status (null = kein An/Aus, z.B. Macro). */
    private record Entry(String key, String label, Boolean on) {
    }

    public HotkeyListMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        enabledAt = Util.getMillis();
        if (!enabled) {
            bounds = null;
        }
    }

    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        for (Mod mod : Mods.mods) {
            if (mod.hasKey()) {
                entries.add(new Entry(keyText(mod.keyName()), mod.name, mod.active));
            }
        }
        if (showMacros) {
            for (Macro macro : MacroMod.MACROS) {
                if (macro.hasKey()) {
                    entries.add(new Entry(keyText(macro.keyName()), macro.name, null));
                }
            }
        }
        return entries;
    }

    private static String keyText(String key) {
        return key.toUpperCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- Zeichnen

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getDebugOverlay().showDebugScreen()) {
            bounds = null;
            return;
        }
        Font font = client.font;
        List<Entry> entries = entries();

        if (entries.isEmpty()) {
            bounds = null;
            return;
        }
        int boxW = boxWidth(font, entries);
        int boxH = HudCards.stackHeight(entries.size());

        float scale = SCALES[Math.max(0, Math.min(SCALES.length - 1, scaleIndex))];
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
            // Standard: rechte Spalte, ganz unten
            left = screenW - 6 - w;
            top = nova.nova_client.client.HudLayout.top(nova.nova_client.client.HudLayout.Slot.HOTKEYS, Math.round(h));
        }
        bounds = new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h));

        float intro = NovaGui.easeOutCubic((Util.getMillis() - enabledAt) / 350.0F);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        draw(g, font, entries, boxW, enabledAt, intro);
        g.pose().popMatrix();
    }

    /** Vorschau im Nova-Menue: die Liste wie im Spiel (ohne belegte Tasten zwei Beispiele), linke obere Ecke bei (x, y). */
    public static Rect drawPreview(GuiGraphicsExtractor g, Font font, int x, int y, float a) {
        List<Entry> entries = entries();
        if (entries.isEmpty()) {
            entries = List.of(new Entry("R", "KillAura", true), new Entry("G", "Key Pearl", false));
        }
        int boxW = boxWidth(font, entries);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        draw(g, font, entries, boxW, 0L, a);
        g.pose().popMatrix();
        return new Rect(x, y, x + boxW, y + HudCards.stackHeight(entries.size()));
    }

    /** Karte: Punkt | Name ... [Taste], alle so breit wie die laengste. */
    private static int boxWidth(Font font, List<Entry> entries) {
        int labelW = 0;
        int keyW = 0;
        for (Entry entry : entries) {
            labelW = Math.max(labelW, NovaGui.width(font, entry.label()));
            keyW = Math.max(keyW, HudCards.keycapWidth(font, entry.key()));
        }
        return TEXT_X + labelW + GAP + keyW + 3;
    }

    /** Karten ab (0, 0); since = Beginn des Einblendens (die Karten kommen nacheinander). */
    private static void draw(GuiGraphicsExtractor g, Font font, List<Entry> entries, int boxW, long since, float intro) {
        // Tasten genau in der Akzentfarbe (wie das Radar)
        int keyColor = NovaGui.BRAND;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int y = HudCards.top(i);
            // nacheinander einblenden
            float fade = NovaGui.easeOutCubic((Util.getMillis() - since - i * 35L) / 300.0F) * intro;
            boolean macro = entry.on() == null;
            boolean off = !macro && !entry.on();
            // Punkt: an = Akzent, aus = grau, Macro = zweite Akzentfarbe
            int dot = macro ? NovaGui.BRAND_2 : off ? OFF_DOT : NovaGui.BRAND;
            HudCards.card(g, 0, y, boxW, dot, fade);
            NovaGui.text(g, font, entry.label(), TEXT_X, y + HudCards.TEXT_Y,
                    NovaGui.alpha(off ? NovaGui.TEXT_MUTED : NovaGui.TEXT, fade), false);
            HudCards.keycap(g, font, entry.key(), boxW - 3, y,
                    off ? NovaGui.mix(keyColor, 0xFF8A8A94, 0.6F) : keyColor, fade);
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    private static int nearestScale(float value) {
        int best = 0;
        for (int i = 1; i < SCALES.length; i++) {
            if (Math.abs(SCALES[i] - value) < Math.abs(SCALES[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new HotkeyListMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "size", String.valueOf(SCALES[scaleIndex]));
        properties.setProperty(prefix + "macros", String.valueOf(showMacros));
        properties.setProperty(prefix + "x", String.valueOf(posX));
        properties.setProperty(prefix + "y", String.valueOf(posY));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            String size = properties.getProperty(prefix + "size");
            if (size != null) {
                scaleIndex = nearestScale(Float.parseFloat(size));
            } else if (properties.getProperty(prefix + "scale") != null) {
                // Alte Config: Index in {0.75, 1.0, 1.25, 1.5}
                float[] old = {0.75F, 1.0F, 1.25F, 1.5F};
                int index = Math.max(0, Math.min(old.length - 1, Integer.parseInt(properties.getProperty(prefix + "scale"))));
                scaleIndex = nearestScale(old[index]);
            }
            posX = Float.parseFloat(properties.getProperty(prefix + "x", String.valueOf(posX)));
            posY = Float.parseFloat(properties.getProperty(prefix + "y", String.valueOf(posY)));
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        showMacros = Boolean.parseBoolean(properties.getProperty(prefix + "macros", String.valueOf(showMacros)));
    }
}
