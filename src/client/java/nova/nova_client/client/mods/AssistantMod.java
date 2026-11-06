package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModConfig;
import nova.nova_client.client.Mods;
import nova.nova_client.client.Profiles;
import nova.nova_client.client.UiSettings;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.assistant.NovaAi;
import nova.nova_client.client.assistant.NovaBrain;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nova Assistant: steuert den Client per Chat. Nachrichten mit "." werden abgefangen (nicht gesendet), wenn sie
 * ein Befehl sind - z.B. ".t esp", ".price diamond" oder frei formuliert ".nova wo ist mein letzter tod".
 * Fragen beantwortet die Nova-KI (assistant/NovaAi) auf Deutsch, Tuerkisch und Englisch - auch ohne Befehl
 * (".was macht anti vanish?"). Andere ".irgendwas"-Nachrichten gehen ganz normal in den Chat.
 */
public class AssistantMod extends GameTickMod {
    public static final String PREFIX = ".";

    /** Befehl: Name, Kurzformen, Argumente, Beschreibung. */
    public record Command(String name, String[] aliases, String args, String help) {
    }

    public static final List<Command> COMMANDS = List.of(
            new Command("help", new String[]{"hilfe", "?"}, "", "Show all commands"),
            new Command("toggle", new String[]{"t"}, "<module>", "Turn a module on/off"),
            new Command("on", new String[]{"an", "enable"}, "<module>", "Turn a module on"),
            new Command("off", new String[]{"aus", "disable"}, "<module>", "Turn a module off"),
            new Command("list", new String[]{"active", "aktiv"}, "", "Show active modules"),
            new Command("modules", new String[]{"mods"}, "", "Show all modules"),
            new Command("bind", new String[]{"b"}, "<module> <key>", "Bind a key, e.g. .bind esp r"),
            new Command("unbind", new String[]{"ub"}, "<module>", "Remove a module's key"),
            new Command("coords", new String[]{"pos", "koords"}, "", "Your coordinates (copied to clipboard)"),
            new Command("death", new String[]{"tod", "lastdeath"}, "", "Where you died last"),
            new Command("accent", new String[]{"theme", "design", "color"}, "<color>", "Change the menu accent color"),
            new Command("profile", new String[]{"profil"}, "<name>", "Load a profile (no name = list)"),
            new Command("panic", new String[]{"notaus"}, "", "Turn every module off"),
            new Command("clear", new String[]{"cls"}, "", "Clear the chat"),
            new Command("say", new String[]{}, "<text>", "Send text as it is (e.g. starting with a dot)"),
            new Command("nova", new String[]{"ai", "ki", "ask", "frag", "sor"}, "<question>",
                    "Ask the Nova AI (German, Turkish, English), e.g. .nova what does xray do"));

    private static AssistantMod instance;

    // Letzter Tod
    private static Vec3 lastDeath;
    private static String lastDeathDimension;
    private static long lastDeathTime;
    private static boolean wasDead;

    public AssistantMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
        // Standardmaessig an: der Assistent soll einfach da sein
        active = true;
    }

    public static boolean isOn() {
        return instance != null && instance.active;
    }

    /** Punkt-Nachrichten, die wie eine Frage aussehen, beantwortet die Nova-KI (statt sie zu senden). */
    public static boolean answerQuestions = true;

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, "Assistant", "Chat commands and the Nova AI", List.of(
                new ModOptionsMenu.Section("Nova AI"),
                new ModOptionsMenu.Choice("Answer Language", NovaAi.LANGUAGES, () -> NovaAi.language,
                        value -> NovaAi.language = value),
                new ModOptionsMenu.Toggle("Answer Dot Questions", () -> answerQuestions, value -> answerQuestions = value),
                new ModOptionsMenu.Info(() -> "Ask with .nova, .ki, .ai or .sor - e.g. .nova what does xray do")));
    }

    @Override
    public void saveSettings(java.util.Properties properties, String prefix) {
        properties.setProperty(prefix + "aiLanguage", String.valueOf(NovaAi.language));
        properties.setProperty(prefix + "aiQuestions", String.valueOf(answerQuestions));
    }

    @Override
    public void loadSettings(java.util.Properties properties, String prefix) {
        try {
            NovaAi.language = Math.max(0, Math.min(NovaAi.LANGUAGES.length - 1,
                    Integer.parseInt(properties.getProperty(prefix + "aiLanguage", String.valueOf(NovaAi.language)).trim())));
        } catch (NumberFormatException ignored) {
            // alter oder kaputter Wert: Standard behalten
        }
        answerQuestions = Boolean.parseBoolean(properties.getProperty(prefix + "aiQuestions", String.valueOf(answerQuestions)));
    }

    /** Todes-Position merken. */
    @Override
    public void execute(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        boolean dead = client.player.isDeadOrDying();
        if (dead && !wasDead) {
            lastDeath = client.player.position();
            lastDeathDimension = client.level.dimension().identifier().getPath();
            lastDeathTime = System.currentTimeMillis();
        }
        wasDead = dead;
    }

    // ---------------------------------------------------------------- Eingang

    /** true = Nachricht war ein Befehl und wird nicht gesendet. */
    public static boolean handle(String message) {
        if (!isOn() || !message.startsWith(PREFIX) || message.length() < 2) {
            return false;
        }
        String body = message.substring(PREFIX.length()).trim();
        String[] parts = body.split("\\s+", 2);
        Command command = command(parts[0]);
        if (command == null) {
            // Kein Befehl, aber eine Frage (".was macht xray?", ".xray ne ise yarar") -> Nova-KI statt Chat
            if (answerQuestions && isQuestion(body)) {
                NovaAi.ask(body);
                return true;
            }
            return false;
        }
        String args = parts.length > 1 ? parts[1].trim() : "";
        run(command.name(), args);
        return true;
    }

    private static final java.util.Set<String> QUESTION_WORDS = java.util.Set.of(
            "was", "wie", "wo", "welche", "welcher", "welches", "wer", "warum", "wofur", "wozu", "gibt", "erklar",
            "erklare", "mach", "schalte", "what", "whats", "how", "which", "where", "who", "why", "does", "is", "explain",
            "turn", "enable", "disable", "ne", "nasil", "hangi", "nerede", "neden", "kim", "neler");

    /** Sieht der Text nach einer Frage an Nova aus (Fragezeichen, Fragewort oder sicher erkannte Absicht)? */
    private static boolean isQuestion(String body) {
        List<String> words = NovaBrain.normalize(body);
        if (words.isEmpty()) {
            return false;
        }
        if (body.endsWith("?") || QUESTION_WORDS.contains(words.get(0))) {
            return true;
        }
        NovaBrain brain = NovaBrain.get();
        if (brain == null) {
            return false;
        }
        NovaBrain.Thought thought = brain.think(words);
        return !thought.intent().equals("unknown") && thought.confidence() >= 0.8F && words.size() >= 2;
    }

    public static Command command(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        for (Command command : COMMANDS) {
            if (command.name().equals(w)) {
                return command;
            }
            for (String alias : command.aliases()) {
                if (alias.equals(w)) {
                    return command;
                }
            }
        }
        return null;
    }

    private static void run(String name, String args) {
        switch (name) {
            case "help" -> help();
            case "toggle" -> withModule(args, mod -> setModule(mod, !mod.active));
            case "on" -> withModule(args, mod -> setModule(mod, true));
            case "off" -> withModule(args, mod -> setModule(mod, false));
            case "list" -> listActive();
            case "modules" -> listModules();
            case "bind" -> bind(args);
            case "unbind" -> withModule(args, mod -> {
                mod.keyCode = Mod.NO_KEY;
                ModConfig.save();
                reply(Component.literal("Removed the key of ").append(accent(mod.name)));
            });
            case "coords" -> coords();
            case "death" -> death();
            case "accent" -> accentColor(args);
            case "profile" -> profile(args);
            case "panic" -> panic();
            case "clear" -> Minecraft.getInstance().gui.hud.getChat().clearMessages(false);
            case "say" -> {
                if (!args.isEmpty() && Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.connection.sendChat(args);
                }
            }
            case "nova" -> NovaAi.ask(args);
            default -> help();
        }
    }

    // ---------------------------------------------------------------- Freie Sprache

    /** Einfache Absichts-Erkennung per Schluesselwoertern (Deutsch + Englisch). */
    /** Einfacher Schluesselwort-Modus (falls die KI nicht laedt). */
    public static void understand(String text) {
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        if (t.isBlank()) {
            reply(Component.literal("Ask me something, e.g. ").append(accent(".nova where did I die")));
            return;
        }
        if (has(t, "tod", "tot ", "death", "died", "gestorben", "starb", "sterben")) {
            death();
        } else if (has(t, "koord", "coord", "wo bin ich", "where am i", "position")) {
            coords();
        } else if (has(t, "hilfe", "help", "befehle", "commands", "was kannst")) {
            help();
        } else if (has(t, "panic", "notaus", "alles aus", "everything off", "all off")) {
            panic();
        } else if (has(t, " theme", "design", "accent", "farbe", "color")) {
            int index = findAccent(text);
            if (index >= 0) {
                accentColor(UiSettings.ACCENT_NAMES[index]);
            } else {
                reply(Component.literal("Which color? ").append(accent(accentNames())));
            }
        } else if (has(t, "aktiv", "active", "running", "laeuft", "läuft", "was ist an", "what is on")) {
            listActive();
        } else {
            Mod mod = findModuleIn(t);
            if (mod == null) {
                reply(Component.literal("Sorry, I didn't get that. Try ").append(accent(".help")));
            } else if (has(t, " aus", " off", "disable", "deaktiv", "ausschalten", "stop")) {
                setModule(mod, false);
            } else if (has(t, " an ", " on", "enable", "aktivier", "einschalten", "start")) {
                setModule(mod, true);
            } else {
                setModule(mod, !mod.active);
            }
        }
    }

    private static boolean has(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- Befehle

    private static void help() {
        reply(Component.literal("Commands (prefix ").append(accent(PREFIX)).append(", Tab completes):"));
        for (Command command : COMMANDS) {
            MutableComponent line = Component.literal("  " + PREFIX + command.name()).withStyle(ChatFormatting.AQUA);
            if (!command.args().isEmpty()) {
                line.append(Component.literal(" " + command.args()).withStyle(ChatFormatting.DARK_AQUA));
            }
            line.append(Component.literal("  " + command.help()).withStyle(ChatFormatting.GRAY));
            raw(line);
        }
    }

    private interface ModAction {
        void run(Mod mod);
    }

    private static void withModule(String query, ModAction action) {
        if (query.isBlank()) {
            reply(Component.literal("Which module? e.g. ").append(accent(".t esp")));
            return;
        }
        Mod mod = findModule(query);
        if (mod == null) {
            reply(Component.literal("No module called \"" + query + "\". ").append(accent(".modules")).append(" lists all."));
            return;
        }
        action.run(mod);
    }

    private static void setModule(Mod mod, boolean on) {
        Minecraft client = Minecraft.getInstance();
        if (mod.active != on) {
            mod.toggle(client);
        }
        reply(accent(mod.name).append(on
                ? Component.literal(" enabled").withStyle(ChatFormatting.GREEN)
                : Component.literal(" disabled").withStyle(ChatFormatting.RED)));
    }

    private static void listActive() {
        List<String> names = new ArrayList<>();
        for (Mod mod : Mods.mods) {
            if (mod.active) {
                names.add(mod.name);
            }
        }
        reply(names.isEmpty() ? Component.literal("No modules active.")
                : Component.literal(names.size() + " active: ").append(Component.literal(String.join(", ", names))
                .withStyle(ChatFormatting.GREEN)));
    }

    private static void listModules() {
        MutableComponent line = Component.literal("Modules: ");
        for (int i = 0; i < Mods.mods.size(); i++) {
            Mod mod = Mods.mods.get(i);
            if (i > 0) {
                line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(Component.literal(mod.name).withStyle(mod.active ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        reply(line);
    }

    private static void bind(String args) {
        String[] parts = args.trim().split("\\s+");
        if (parts.length < 2) {
            reply(Component.literal("Usage: ").append(accent(".bind <module> <key>")).append(", e.g. .bind esp r"));
            return;
        }
        String keyName = parts[parts.length - 1].toLowerCase(Locale.ROOT);
        String moduleName = args.trim().substring(0, args.trim().length() - parts[parts.length - 1].length()).trim();
        Mod mod = findModule(moduleName);
        if (mod == null) {
            reply(Component.literal("No module called \"" + moduleName + "\"."));
            return;
        }
        InputConstants.Key key = InputConstants.getKey("key.keyboard." + keyName);
        if (key == InputConstants.UNKNOWN) {
            reply(Component.literal("Unknown key \"" + keyName + "\". Examples: r, g, f6, left.shift, keypad.1"));
            return;
        }
        mod.keyCode = key.getValue();
        ModConfig.save();
        reply(accent(mod.name).append(Component.literal(" is now on ")).append(accent(mod.keyName())));
    }

    private static void coords() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        String text = client.player.getBlockX() + " " + client.player.getBlockY() + " " + client.player.getBlockZ();
        client.keyboardHandler.setClipboard(text);
        reply(Component.literal("You are at ").append(copyable(text))
                .append(Component.literal(" in " + client.level.dimension().identifier().getPath() + " (copied)")
                        .withStyle(ChatFormatting.GRAY)));
    }

    private static void death() {
        Minecraft client = Minecraft.getInstance();
        if (lastDeath == null) {
            reply(Component.literal("No death recorded this session."));
            return;
        }
        String text = (int) Math.floor(lastDeath.x) + " " + (int) Math.floor(lastDeath.y) + " " + (int) Math.floor(lastDeath.z);
        long minutes = (System.currentTimeMillis() - lastDeathTime) / 60_000L;
        MutableComponent line = Component.literal("You died at ").append(copyable(text))
                .append(Component.literal(" in " + lastDeathDimension + ", " + (minutes == 0 ? "just now" : minutes + " min ago"))
                        .withStyle(ChatFormatting.GRAY));
        if (client.player != null && client.level != null
                && client.level.dimension().identifier().getPath().equals(lastDeathDimension)) {
            line.append(Component.literal(" - " + Math.round(client.player.position().distanceTo(lastDeath)) + " m away")
                    .withStyle(ChatFormatting.YELLOW));
        }
        if (minutes < 5) {
            line.append(Component.literal(" (items despawn in ~" + (5 - minutes) + " min)").withStyle(ChatFormatting.RED));
        }
        reply(line);
    }

    private static void accentColor(String name) {
        int index = findAccent(name);
        if (index < 0) {
            reply(Component.literal("Accent colors: ").append(accent(accentNames())));
            return;
        }
        applyAccent(index);
        reply(Component.literal("Accent: ").append(accent(UiSettings.ACCENT_NAMES[index])));
    }

    private static void profile(String name) {
        if (name.isBlank()) {
            List<String> names = new ArrayList<>();
            for (Profiles.Info info : Profiles.list()) {
                names.add(info.name());
            }
            reply(names.isEmpty() ? Component.literal("No profiles saved yet.")
                    : Component.literal("Profiles: ").append(accent(String.join(", ", names))));
            return;
        }
        if (Profiles.load(name)) {
            reply(Component.literal("Loaded profile ").append(accent(name)));
        } else {
            reply(Component.literal("No profile called \"" + name + "\"."));
        }
    }

    private static void panic() {
        reply(Component.literal("Panic: turned off " + turnAllOff() + " modules.").withStyle(ChatFormatting.RED));
    }

    /** Alle Module ausser dem Assistenten aus. Rueckgabe: wie viele. */
    public static int turnAllOff() {
        Minecraft client = Minecraft.getInstance();
        int count = 0;
        for (Mod mod : Mods.mods) {
            if (mod.active && !(mod instanceof AssistantMod)) {
                mod.toggle(client);
                count++;
            }
        }
        return count;
    }

    /** Akzentfarbe setzen (Index in UiSettings.ACCENT_NAMES). */
    public static void applyAccent(int index) {
        UiSettings.accent = UiSettings.ACCENTS[index];
        UiSettings.apply();
        ModConfig.save();
    }

    public static Vec3 lastDeath() {
        return lastDeath;
    }

    public static String lastDeathDimension() {
        return lastDeathDimension;
    }

    public static long lastDeathTime() {
        return lastDeathTime;
    }

    // ---------------------------------------------------------------- Suche

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Modul nach Name: exakt, dann Anfang, dann enthalten. */
    public static Mod findModule(String query) {
        String q = normalize(query);
        if (q.isEmpty()) {
            return null;
        }
        for (Mod mod : Mods.mods) {
            if (normalize(mod.name).equals(q)) {
                return mod;
            }
        }
        for (Mod mod : Mods.mods) {
            if (normalize(mod.name).startsWith(q)) {
                return mod;
            }
        }
        for (Mod mod : Mods.mods) {
            if (normalize(mod.name).contains(q)) {
                return mod;
            }
        }
        return null;
    }

    /** Modul, dessen Name irgendwo im Satz vorkommt (laengster Treffer gewinnt, z.B. "BlockESP" vor "ESP"). */
    private static Mod findModuleIn(String text) {
        String t = normalize(text);
        Mod best = null;
        for (Mod mod : Mods.mods) {
            String name = normalize(mod.name);
            if (t.contains(name) && (best == null || name.length() > normalize(best.name).length())) {
                best = mod;
            }
        }
        return best;
    }

    /** Akzentfarbe nach Name (Index in UiSettings.ACCENT_NAMES, -1 = keine). */
    private static int findAccent(String text) {
        String t = normalize(text);
        for (int i = 0; i < UiSettings.ACCENT_NAMES.length; i++) {
            String name = normalize(UiSettings.ACCENT_NAMES[i]);
            if (!t.isEmpty() && (t.contains(name) || name.startsWith(t))) {
                return i;
            }
        }
        return -1;
    }

    private static String accentNames() {
        return String.join(", ", UiSettings.ACCENT_NAMES);
    }

    /** Vorschlaege fuer die Chat-Eingabe (Befehle, Module, Farben). */
    public static List<String> suggestions(String input) {
        List<String> result = new ArrayList<>();
        if (!isOn() || !input.startsWith(PREFIX)) {
            return result;
        }
        String body = input.substring(PREFIX.length());
        int space = body.indexOf(' ');
        if (space < 0) {
            String typed = body.toLowerCase(Locale.ROOT);
            for (Command command : COMMANDS) {
                if (command.name().startsWith(typed)) {
                    result.add(command.name());
                }
            }
            return result;
        }
        Command command = command(body.substring(0, space));
        String arg = normalize(body.substring(space + 1));
        if (command == null || body.substring(space + 1).contains(" ")) {
            return result;
        }
        switch (command.name()) {
            case "toggle", "on", "off", "bind", "unbind" -> {
                for (Mod mod : Mods.mods) {
                    if (normalize(mod.name).startsWith(arg)) {
                        result.add(mod.name.replace(" ", ""));
                    }
                }
            }
            case "accent" -> {
                for (String name : UiSettings.ACCENT_NAMES) {
                    if (normalize(name).startsWith(arg)) {
                        result.add(name);
                    }
                }
            }
            default -> {
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- Ausgabe

    private static MutableComponent accent(String text) {
        return Component.literal(text).withStyle(ChatFormatting.AQUA);
    }

    /** Text zum Anklicken: kopiert in die Zwischenablage. */
    public static MutableComponent copyable(String text) {
        return Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.YELLOW).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(text))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
    }

    public static void reply(Component message) {
        raw(Component.literal("[Nova] ").withStyle(ChatFormatting.LIGHT_PURPLE).append(message.copy().withStyle(style ->
                style.getColor() == null ? style.withColor(ChatFormatting.WHITE) : style)));
    }

    private static void raw(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            Compat.chat(client.player, message);
        }
    }
}
