package nova.nova_client.client.mods;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.ScoreboardMenu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Scoreboard-Designer: aendert das Scoreboard rechts rein visuell (nur bei dir, der Server merkt nichts).
 * Titel und jede Zeile koennen ersetzt werden, dazu Suchen/Ersetzen-Regeln (z.B. Geld) und Stil-Optionen.
 * Farben mit &-Codes (&a, &l, ...) und Hex (&#FF55AA). "{hide}" blendet eine Zeile aus.
 */
public class ScoreboardMod extends GameTickMod {
    public static final int MAX_LINES = 15;
    public static final int MAX_RULES = 8;
    public static final String HIDE = "{hide}";
    /** Hintergrund-Deckkraft: -1 = wie in Minecraft eingestellt. */
    public static final int[] OPACITY_PRESETS = {-1, 0, 25, 50, 75};

    // Gleiche Sortierung wie Vanilla (hoechster Wert oben)
    private static final Comparator<PlayerScoreEntry> ORDER = Comparator.comparingInt(PlayerScoreEntry::value).reversed()
            .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

    // Einstellungen
    public static String title = "";
    public static final String[] lines = new String[MAX_LINES];
    public static final String[] find = new String[MAX_RULES];
    public static final String[] replace = new String[MAX_RULES];
    public static boolean hideNumbers = false;
    public static int opacity = -1;

    private static ScoreboardMod instance;

    static {
        reset();
    }

    public ScoreboardMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    @Override
    public void execute(Minecraft client) {
    }

    public static boolean isOn() {
        return instance != null && instance.active;
    }

    public static void reset() {
        title = "";
        Arrays.fill(lines, "");
        Arrays.fill(find, "");
        Arrays.fill(replace, "");
        hideNumbers = false;
        opacity = -1;
    }

    // ---------------------------------------------------------------- Scoreboard lesen

    /** Das Objective, das Vanilla gerade rechts anzeigen wuerde (Team-Farbe zuerst), oder null. */
    public static Objective sidebarObjective(Minecraft client) {
        if (client.level == null || client.player == null) {
            return null;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(client.player.getScoreboardName());
        //? if >=26.1 {
        DisplaySlot teamSlot = team != null && team.getColor().isPresent() ? team.getColor().get().displaySlot() : null;
        //?} else {
        /*DisplaySlot teamSlot = team != null ? DisplaySlot.teamColorToSlot(team.getColor()) : null;
        *///?}
        if (teamSlot != null) {
            Objective objective = scoreboard.getDisplayObjective(teamSlot);
            if (objective != null) {
                return objective;
            }
        }
        return scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
    }

    /** Eine Zeile wie vom Server geschickt. */
    public record Line(Component name, Component score) {
    }

    public static List<Line> originalLines(Objective objective) {
        Scoreboard scoreboard = objective.getScoreboard();
        NumberFormat format = objective.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        List<Line> result = new ArrayList<>();
        scoreboard.listPlayerScores(objective).stream()
                .filter(entry -> !entry.isHidden())
                .sorted(ORDER)
                .limit(MAX_LINES)
                .forEach(entry -> result.add(new Line(
                        PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()),
                        entry.formatValue(format))));
        return result;
    }

    // ---------------------------------------------------------------- Zeichnen

    /**
     * Zeichnet das Scoreboard mit allen Aenderungen, Layout wie Vanilla.
     * right = rechter Bildschirmrand, centerY = vertikale Mitte.
     */
    public static void draw(GuiGraphicsExtractor graphics, Font font, Objective objective, int right, int centerY) {
        List<Line> original = originalLines(objective);
        List<Component> names = new ArrayList<>();
        List<Component> scores = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) {
            String override = lines[i];
            if (HIDE.equalsIgnoreCase(override.trim())) {
                continue;
            }
            names.add(transform(original.get(i).name(), override));
            scores.add(hideNumbers ? Component.empty() : applyRules(original.get(i).score()));
        }

        Component titleText = transform(objective.getDisplayName(), title);
        int lineHeight = font.lineHeight;
        int titleWidth = font.width(titleText);
        int spacer = font.width(": ");
        int maxWidth = titleWidth;
        int[] scoreWidths = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            scoreWidths[i] = font.width(scores.get(i));
            maxWidth = Math.max(maxWidth, font.width(names.get(i)) + (scoreWidths[i] > 0 ? spacer + scoreWidths[i] : 0));
        }

        int count = names.size();
        int bottom = centerY + count * lineHeight / 3;
        int left = right - maxWidth - 3;
        int rightEdge = right - 3 + 2;
        int top = bottom - count * lineHeight;

        Minecraft client = Minecraft.getInstance();
        int background = opacity < 0 ? client.options.getBackgroundColor(0.3F) : opacityColor(opacity);

        // Ein abgerundeter Kasten im einheitlichen HUD-Hintergrund (wie alle anderen Anzeigen)
        NovaGui.hudBackground(graphics, left - 2, top - lineHeight - 1, rightEdge, bottom);
        graphics.text(font, titleText, left + maxWidth / 2 - titleWidth / 2, top - lineHeight, -1, false);
        for (int i = 0; i < count; i++) {
            int y = bottom - (count - i) * lineHeight;
            graphics.text(font, names.get(i), left, y, -1, false);
            graphics.text(font, scores.get(i), rightEdge - scoreWidths[i], y, -1, false);
        }
    }

    private static int opacityColor(int percent) {
        return Math.round(percent * 2.55F) << 24;
    }

    // ---------------------------------------------------------------- Text umbauen

    /** Eigener Text hat Vorrang, sonst Original mit Ersetzen-Regeln. */
    public static Component transform(Component original, String override) {
        if (override != null && !override.isEmpty()) {
            return parseColors(override);
        }
        return applyRules(original);
    }

    /** Wendet alle Suchen/Ersetzen-Regeln an; die Farben des Originals bleiben erhalten. */
    public static Component applyRules(Component original) {
        boolean any = false;
        for (String f : find) {
            any |= !f.isEmpty();
        }
        if (!any) {
            return original;
        }

        // In einzelne Zeichen mit Style zerlegen, damit Treffer ueber mehrere Teile hinweg funktionieren
        StringBuilder text = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        flatten(original, text, styles);

        boolean changed = false;
        for (int r = 0; r < MAX_RULES; r++) {
            if (find[r].isEmpty()) {
                continue;
            }
            StringBuilder replText = new StringBuilder();
            List<Style> replStyles = new ArrayList<>();
            flatten(parseColors(replace[r]), replText, replStyles);

            int from = 0;
            int index;
            while ((index = text.indexOf(find[r], from)) >= 0) {
                Style base = styles.get(index);
                text.replace(index, index + find[r].length(), replText.toString());
                styles.subList(index, index + find[r].length()).clear();
                List<Style> inserted = new ArrayList<>();
                for (Style s : replStyles) {
                    inserted.add(s.applyTo(base));
                }
                styles.addAll(index, inserted);
                from = index + replText.length();
                changed = true;
            }
        }
        return changed ? build(text, styles) : original;
    }

    private static void flatten(Component component, StringBuilder text, List<Style> styles) {
        component.visit((style, part) -> {
            text.append(part);
            for (int i = 0; i < part.length(); i++) {
                styles.add(style);
            }
            return Optional.empty();
        }, Style.EMPTY);
    }

    private static MutableComponent build(CharSequence text, List<Style> styles) {
        MutableComponent out = Component.empty();
        int start = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i == text.length() || !styles.get(i).equals(styles.get(start))) {
                out.append(Component.literal(text.subSequence(start, i).toString()).setStyle(styles.get(start)));
                start = i;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- Farbcodes

    private static final String COLOR_CODES = "0123456789abcdef";
    private static final String FORMAT_CODES = "klmno";

    /** "&aHallo &#FF00FFWelt" -> farbige Komponente. Unterstuetzt &0-f, &k-o, &r und &#RRGGBB. */
    public static MutableComponent parseColors(String input) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(i + 1));
                if (code == '#' && i + 8 <= input.length()) {
                    try {
                        int rgb = Integer.parseInt(input.substring(i + 2, i + 8), 16);
                        flush(out, buffer, style);
                        style = Style.EMPTY.withColor(TextColor.fromRgb(rgb));
                        i += 7;
                        continue;
                    } catch (NumberFormatException ignored) {
                        // kein Hex-Code, normal als Text behandeln
                    }
                }
                ChatFormatting formatting = ChatFormatting.getByCode(code);
                if (formatting != null) {
                    flush(out, buffer, style);
                    if (COLOR_CODES.indexOf(code) >= 0) {
                        style = Style.EMPTY.withColor(formatting);
                    } else if (FORMAT_CODES.indexOf(code) >= 0) {
                        style = style.applyFormat(formatting);
                    } else {
                        style = Style.EMPTY;
                    }
                    i++;
                    continue;
                }
            }
            buffer.append(c);
        }
        flush(out, buffer, style);
        return out;
    }

    private static void flush(MutableComponent out, StringBuilder buffer, Style style) {
        if (!buffer.isEmpty()) {
            out.append(Component.literal(buffer.toString()).setStyle(style));
            buffer.setLength(0);
        }
    }

    /** Gegenstueck zu parseColors: Komponente -> Text mit &-Codes (zum Bearbeiten im Designer). */
    public static String toCodes(Component component) {
        StringBuilder out = new StringBuilder();
        Style[] last = {null};
        component.visit((style, part) -> {
            if (part.isEmpty()) {
                return Optional.empty();
            }
            if (!style.equals(last[0])) {
                TextColor color = style.getColor();
                if (color != null) {
                    out.append(String.format(Locale.ROOT, "&#%06X", color.getValue() & 0xFFFFFF));
                } else if (last[0] != null) {
                    out.append("&r");
                }
                if (style.isObfuscated()) out.append("&k");
                if (style.isBold()) out.append("&l");
                if (style.isStrikethrough()) out.append("&m");
                if (style.isUnderlined()) out.append("&n");
                if (style.isItalic()) out.append("&o");
                last[0] = style;
            }
            out.append(part);
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ScoreboardMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "title", title);
        for (int i = 0; i < MAX_LINES; i++) {
            properties.setProperty(prefix + "line." + i, lines[i]);
        }
        for (int i = 0; i < MAX_RULES; i++) {
            properties.setProperty(prefix + "find." + i, find[i]);
            properties.setProperty(prefix + "replace." + i, replace[i]);
        }
        properties.setProperty(prefix + "hideNumbers", String.valueOf(hideNumbers));
        properties.setProperty(prefix + "opacity", String.valueOf(opacity));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        // Frueher hiess das Modul "Scoreboard": alte Einstellungen einmalig uebernehmen
        if (properties.getProperty(prefix + "title") == null && properties.getProperty("mod.scoreboard.title") != null) {
            prefix = "mod.scoreboard.";
        }
        title = properties.getProperty(prefix + "title", title);
        for (int i = 0; i < MAX_LINES; i++) {
            lines[i] = properties.getProperty(prefix + "line." + i, lines[i]);
        }
        for (int i = 0; i < MAX_RULES; i++) {
            find[i] = properties.getProperty(prefix + "find." + i, find[i]);
            replace[i] = properties.getProperty(prefix + "replace." + i, replace[i]);
        }
        String numbers = properties.getProperty(prefix + "hideNumbers");
        if (numbers != null) {
            hideNumbers = Boolean.parseBoolean(numbers);
        }
        String value = properties.getProperty(prefix + "opacity");
        if (value != null) {
            try {
                opacity = Math.max(-1, Math.min(100, Integer.parseInt(value.trim())));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
    }
}
