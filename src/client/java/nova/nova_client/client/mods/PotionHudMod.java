package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
//? if >=26.1 {
import net.minecraft.client.gui.Hud;
//?} else {
/*import net.minecraft.client.gui.Gui;
*///?}
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import nova.nova_client.client.HudCards;
import nova.nova_client.client.HudStyle;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.PotionHudMenu;
import nova.nova_client.client.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;

/**
 * PotionHUD: alle aktiven Trank-Effekte untereinander, jeder als eigene Karte ({@link HudCards}): Effekt-Symbol,
 * Name mit Stufe, Restzeit rechts und unten eine duenne Leiste mit der verbleibenden Dauer. Position ueber den
 * HUD-Editor. (style bleibt nur fuer alte Configs/die Zeilen-Vorschau erhalten.)
 */
public class PotionHudMod extends RenderMod {
    public static final float[] SCALES = {0.75F, 1.0F, 1.25F, 1.5F};
    /** Unter so vielen Ticks Restzeit wird die Zeit rot (10 Sekunden). */
    private static final int EXPIRING_TICKS = 200;
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    // Einstellungen
    /** Aussehen (Farben, Hintergrund, Schrift, Abstaende), einstellbar im HudStyleMenu. */
    public static final HudStyle style = new HudStyle();
    public static int scaleIndex = 1;
    /** Stufe (II, III, ...) hinter dem Namen anzeigen. */
    public static boolean showLevel = true;
    /** Effektname in der Farbe des Tranks. */
    public static boolean effectColors = true;
    /** Nach Restzeit sortieren (kuerzeste oben), sonst alphabetisch. */
    public static boolean sortByTime = true;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = Standard. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    /** Zuletzt gezeichneter Bereich (fuer den Verschiebe-Modus). */
    public static Rect bounds;

    private static final int ICON = 11;
    private static final int TEXT_X = 6 + ICON + 4;
    private static final int TIME_GAP = 12;

    private long enabledAt = Util.getMillis();
    /** Laengste gesehene Dauer je Effekt (fuer die Leiste - die Startdauer kennt der Client nicht). */
    private static final Map<Holder<MobEffect>, Integer> LONGEST = new HashMap<>();
    /** Beispiel-Effekte fuer die Vorschau, wenn gerade keiner wirkt (beim ersten Gebrauch angelegt). */
    private static List<MobEffectInstance> samples;

    public PotionHudMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        enabledAt = Util.getMillis();
        if (!enabled) {
            bounds = null;
        }
    }

    // ---------------------------------------------------------------- Zeilen

    private static String level(int amplifier) {
        return amplifier < ROMAN.length ? ROMAN[amplifier] : String.valueOf(amplifier + 1);
    }

    private static String time(MobEffectInstance effect) {
        if (effect.isInfiniteDuration()) {
            return "∞";
        }
        int seconds = effect.getDuration() / 20;
        return seconds >= 3600
                ? String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static HudStyle.Row row(MobEffect effect, int amplifier, String time, boolean expiring) {
        String label = effect.getDisplayName().getString();
        if (showLevel && amplifier > 0) {
            label += " " + level(amplifier);
        }
        int labelColor = effectColors ? 0xFF000000 | effect.getColor() : 0;
        int valueColor = expiring ? 0xFFFF5470 : 0;
        return new HudStyle.Row(label, time, labelColor, valueColor);
    }

    /** Kopfzeile + eine Zeile je aktivem Effekt. */
    public static List<HudStyle.Row> rows(Minecraft client) {
        List<MobEffectInstance> sorted = sorted(client);

        List<HudStyle.Row> rows = new ArrayList<>();
        rows.add(new HudStyle.Row("Effects", String.valueOf(sorted.size())));
        if (sorted.isEmpty()) {
            rows.add(new HudStyle.Row("No active effects", ""));
        }
        for (MobEffectInstance effect : sorted) {
            boolean expiring = !effect.isInfiniteDuration() && effect.endsWithin(EXPIRING_TICKS);
            rows.add(row(effect.getEffect().value(), effect.getAmplifier(), time(effect), expiring));
        }
        return rows;
    }

    /** Echte Effekte, falls welche aktiv sind, sonst Beispielzeilen fuer die Vorschau im Stil-Menue. */
    public static List<HudStyle.Row> previewRows() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && !client.player.getActiveEffects().isEmpty()) {
            return rows(client);
        }
        List<HudStyle.Row> rows = new ArrayList<>();
        rows.add(new HudStyle.Row("Effects", "3"));
        rows.add(row(net.minecraft.world.effect.MobEffects.STRENGTH.value(), 1, "0:08", true));
        rows.add(row(net.minecraft.world.effect.MobEffects.SPEED.value(), 1, "1:30", false));
        rows.add(row(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE.value(), 0, "7:45", false));
        return rows;
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
        List<MobEffectInstance> effects = sorted(client);
        LONGEST.keySet().retainAll(activeKeys(effects));
        // ohne Effekte nichts zeigen - ausser im Chat/HUD-Editor, damit man es verschieben kann
        boolean placeholder = effects.isEmpty();
        if (placeholder && client.gui.screen() == null) {
            bounds = null;
            return;
        }

        int boxW = boxWidth(font, effects, placeholder);
        int boxH = HudCards.stackHeight(placeholder ? 1 : effects.size());

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
            // Standard: rechte Spalte
            left = screenW - w - 6;
            top = nova.nova_client.client.HudLayout.top(nova.nova_client.client.HudLayout.Slot.POTIONS, Math.round(h));
        }
        bounds = new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h));

        float intro = NovaGui.easeOutCubic((Util.getMillis() - enabledAt) / 350.0F);
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, scale);
        drawCards(g, font, effects, placeholder, boxW, enabledAt, intro, true);
        g.pose().popMatrix();
    }

    /** Vorschau im Nova-Menue: deine Effekte (ohne welche drei Beispiele) wie im Spiel, linke obere Ecke bei (x, y). */
    public static Rect drawPreview(GuiGraphicsExtractor g, Font font, int x, int y, float a) {
        List<MobEffectInstance> effects = sorted(Minecraft.getInstance());
        boolean real = !effects.isEmpty();
        if (!real) {
            if (samples == null) {
                samples = List.of(new MobEffectInstance(net.minecraft.world.effect.MobEffects.STRENGTH, 160, 1),
                        new MobEffectInstance(net.minecraft.world.effect.MobEffects.SPEED, 1800, 1),
                        new MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 9300, 0));
            }
            effects = samples;
        }
        int boxW = boxWidth(font, effects, false);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        drawCards(g, font, effects, false, boxW, 0L, a, real);
        g.pose().popMatrix();
        return new Rect(x, y, x + boxW, y + HudCards.stackHeight(effects.size()));
    }

    /** Gleich breite Karten: Symbol | Name ... Zeit. */
    private static int boxWidth(Font font, List<MobEffectInstance> effects, boolean placeholder) {
        int labelW = placeholder ? NovaGui.width(font, "No effects") : 0;
        int timeW = 0;
        for (MobEffectInstance effect : effects) {
            labelW = Math.max(labelW, NovaGui.width(font, label(effect)));
            timeW = Math.max(timeW, NovaGui.width(font, time(effect)));
        }
        return TEXT_X + labelW + TIME_GAP + timeW + HudCards.PAD;
    }

    /**
     * Karten ab (0, 0); since = Beginn des Einblendens (die Karten kommen nacheinander). remember = laengste Dauer je
     * Effekt fuer die Leisten merken (echte Effekte), sonst zeigt die Leiste zwei Drittel.
     */
    private static void drawCards(GuiGraphicsExtractor g, Font font, List<MobEffectInstance> effects, boolean placeholder, int boxW,
                                  long since, float intro, boolean remember) {
        if (placeholder) {
            HudCards.card(g, 0, 0, boxW, 0, intro);
            NovaGui.text(g, font, "No effects", TEXT_X, HudCards.TEXT_Y, NovaGui.alpha(NovaGui.TEXT_MUTED, intro), false);
        }
        for (int i = 0; i < effects.size(); i++) {
            MobEffectInstance effect = effects.get(i);
            int y = HudCards.top(i);
            float fade = NovaGui.easeOutCubic((Util.getMillis() - since - i * 35L) / 300.0F) * intro;
            int color = 0xFF000000 | effect.getEffect().value().getColor();
            boolean expiring = !effect.isInfiniteDuration() && effect.endsWithin(EXPIRING_TICKS);

            HudCards.card(g, 0, y, boxW, 0, fade);
            //? if >=26.1 {
            var sprite = Hud.getMobEffectSprite(effect.getEffect());
            //?} else {
            /*var sprite = Gui.getMobEffectSprite(effect.getEffect());
            *///?}
            g.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, 5,
                    y + (HudCards.HEIGHT - ICON) / 2, ICON, ICON, fade);
            // Name hell, Restzeit gedaempft; die Trankfarbe steckt nur in der Leiste (sonst Akzentfarbe)
            NovaGui.text(g, font, label(effect), TEXT_X, y + HudCards.TEXT_Y, NovaGui.alpha(NovaGui.TEXT, fade), false);
            String time = time(effect);
            // laeuft ab: rot und leicht pulsierend
            float pulse = expiring ? 0.65F + 0.35F * (float) Math.abs(Math.sin(Util.getMillis() / 180.0D)) : 1.0F;
            NovaGui.text(g, font, time, boxW - HudCards.PAD - NovaGui.width(font, time), y + HudCards.TEXT_Y,
                    NovaGui.alpha(expiring ? 0xFFFF5470 : NovaGui.TEXT_DIM, fade * pulse), false);
            if (!effect.isInfiniteDuration()) {
                int longest = remember ? LONGEST.merge(effect.getEffect(), effect.getDuration(), Math::max)
                        : effect.getDuration() * 3 / 2;
                HudCards.progress(g, 0, y, boxW, effect.getDuration() / (float) Math.max(1, longest),
                        expiring ? 0xFF5470 : effectColors ? color : NovaGui.BRAND, fade);
            }
        }
    }

    /** Aktive Effekte, sortiert nach Restzeit (kuerzeste oben) oder Name. */
    private static List<MobEffectInstance> sorted(Minecraft client) {
        List<MobEffectInstance> sorted = client.player == null ? new ArrayList<>() : new ArrayList<>(client.player.getActiveEffects());
        if (sortByTime) {
            sorted.sort(Comparator.comparingInt((MobEffectInstance e) -> e.isInfiniteDuration() ? Integer.MAX_VALUE : e.getDuration()));
        } else {
            sorted.sort(Comparator.comparing(e -> e.getEffect().value().getDisplayName().getString()));
        }
        return sorted;
    }

    private static Set<Holder<MobEffect>> activeKeys(List<MobEffectInstance> effects) {
        Set<Holder<MobEffect>> keys = new HashSet<>();
        for (MobEffectInstance effect : effects) {
            keys.add(effect.getEffect());
        }
        return keys;
    }

    /** "Speed II" (Stufe nur mit Einstellung und ab II). */
    private static String label(MobEffectInstance effect) {
        String label = effect.getEffect().value().getDisplayName().getString();
        return showLevel && effect.getAmplifier() > 0 ? label + " " + level(effect.getAmplifier()) : label;
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new PotionHudMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "scale", String.valueOf(scaleIndex));
        properties.setProperty(prefix + "level", String.valueOf(showLevel));
        properties.setProperty(prefix + "colors", String.valueOf(effectColors));
        properties.setProperty(prefix + "sortByTime", String.valueOf(sortByTime));
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
        showLevel = Boolean.parseBoolean(properties.getProperty(prefix + "level", String.valueOf(showLevel)));
        effectColors = Boolean.parseBoolean(properties.getProperty(prefix + "colors", String.valueOf(effectColors)));
        sortByTime = Boolean.parseBoolean(properties.getProperty(prefix + "sortByTime", String.valueOf(sortByTime)));
        style.load(properties, prefix);
    }
}
