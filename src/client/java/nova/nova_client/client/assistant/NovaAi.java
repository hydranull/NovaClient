package nova.nova_client.client.assistant;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.Mods;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.UiSettings;
import nova.nova_client.client.mods.AssistantMod;
import nova.nova_client.client.mods.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Nova-KI im Chat: versteht Fragen auf Deutsch, Tuerkisch und Englisch ("was macht anti vanish", "xray ne ise yarar",
 * "which module helps with base finding") und antwortet in derselben Sprache. NovaBrain erkennt Absicht und Sprache,
 * NovaKnowledge kennt die Module. Merkt sich das letzte Modul ("und wie stelle ich das ein?").
 */
public final class NovaAi {
    public static final String[] LANGUAGES = {"Auto", "Deutsch", "Türkçe", "English"};
    private static final String[] CODES = {"", "de", "tr", "en"};
    /** Unter dieser Sicherheit gilt die Absicht als nicht verstanden. */
    private static final float SURE = 0.4F;

    /** Einstellung: 0 = Sprache der Frage, sonst fest. */
    public static int language;

    private static NovaKnowledge.Entry lastModule;
    private static String lastLanguage;

    private NovaAi() {
    }

    /** Frage beantworten (aus dem Chat, ".nova ..." oder eine Punkt-Frage). */
    public static void ask(String text) {
        NovaBrain brain = NovaBrain.get();
        if (brain == null) {
            AssistantMod.understand(text);
            return;
        }
        List<String> words = NovaBrain.normalize(text);
        if (words.isEmpty()) {
            say(t(pickLanguage(null, words), "Frag mich etwas, z.B. ", "Bana bir şey sor, örneğin ", "Ask me something, e.g. ")
                    .append(code(".nova was macht xray")));
            return;
        }
        NovaKnowledge.Match module = NovaKnowledge.findModule(words);
        int[] category = findCategory(words);
        int[] color = findColor(words);
        NovaBrain.Thought thought = brain.think(brainInput(words, module, category, color));
        String lang = pickLanguage(thought, words);
        String intent = thought.confidence() < SURE ? (module != null ? "what" : "unknown") : thought.intent();
        if ((intent.equals("on") || intent.equals("off")) && !hasAction(words)) {
            // Nur ein Modulname ohne "an/aus/ac/kapat": erklaeren statt schalten
            intent = "what";
        }
        NovaKnowledge.Entry target = module != null ? module.entry() : null;
        if (target == null && lastModule != null && refersBack(words)) {
            target = lastModule;
        }
        switch (intent) {
            case "what", "how", "on", "off", "key" -> {
                if (target == null) {
                    say(t(lang, "Welches Modul meinst du? Zum Beispiel: ", "Hangi modülü kastediyorsun? Örneğin: ",
                            "Which module do you mean? For example: ").append(code(example(lang))));
                    break;
                }
                switch (intent) {
                    case "what" -> what(target, lang);
                    case "how" -> how(target, lang);
                    case "on" -> setModule(target, true, lang);
                    case "off" -> setModule(target, false, lang);
                    default -> key(target, lang);
                }
                lastModule = target;
            }
            case "which" -> which(words, lang);
            case "active" -> active(lang);
            case "list" -> list(category[0] >= 0 ? ModCategory.values()[category[0]] : null, lang);
            case "death" -> death(lang);
            case "coords" -> coords(lang);
            case "help" -> help(lang);
            case "greet" -> say(pick(lang,
                    new String[]{"Hey! Frag mich einfach etwas über die Module.", "Hallo! Womit kann ich helfen?"},
                    new String[]{"Selam! Modüller hakkında bana istediğini sor.", "Merhaba! Nasıl yardımcı olabilirim?"},
                    new String[]{"Hey! Ask me anything about the modules.", "Hi! How can I help?"}));
            case "thanks" -> say(pick(lang, new String[]{"Gern geschehen!", "Immer gern!"},
                    new String[]{"Rica ederim!", "Ne demek, her zaman!"}, new String[]{"You're welcome!", "Anytime!"}));
            case "who" -> say(t(lang,
                    "Ich bin die Nova-KI – ein kleines neuronales Netz direkt in deinem Client, ohne Internet. Ich kenne alle "
                            + Mods.mods.size() + " Module.",
                    "Ben Nova yapay zekasıyım – doğrudan istemcinde çalışan, internetsiz küçük bir sinir ağı. "
                            + Mods.mods.size() + " modülün hepsini biliyorum.",
                    "I'm the Nova AI – a small neural network right inside your client, no internet needed. I know all "
                            + Mods.mods.size() + " modules."));
            case "panic" -> {
                int count = AssistantMod.turnAllOff();
                say(t(lang, "Alles aus: " + count + " Module ausgeschaltet.", "Hepsi kapandı: " + count + " modül kapatıldı.",
                        "Everything off: turned off " + count + " modules.").withStyle(ChatFormatting.RED));
            }
            case "theme" -> theme(color, lang);
            default -> unknown(words, lang);
        }
        lastLanguage = lang;
    }

    // ---------------------------------------------------------------- Antworten

    private static void what(NovaKnowledge.Entry e, String lang) {
        MutableComponent line = name(e.mod()).append(Component.literal(" – " + e.about(lang)));
        line.append(state(e.mod(), lang));
        say(line);
        hint(t(lang, "Einstellungen? Frag: ", "Ayarlar mı? Sor: ", "Settings? Ask: ")
                .append(code(t(lang, ".nova wie benutze ich ", ".nova ", ".nova how do i use ").getString() + e.mod().name
                        + (lang.equals("tr") ? " nasıl kullanılır" : ""))));
    }

    private static void how(NovaKnowledge.Entry e, String lang) {
        Mod mod = e.mod();
        String category = NovaGui.label(mod.category);
        say(t(lang, "So nutzt du ", "", "How to use ").append(name(mod)).append(t(lang, ":", " nasıl kullanılır:", ":")));
        step(t(lang, "Nova-Menü öffnen (" + menuKey() + "), Kategorie „" + category + "“, auf ",
                "Nova menüsünü aç (" + menuKey() + "), „" + category + "“ kategorisinde ",
                "Open the Nova menu (" + menuKey() + "), category “" + category + "”, click ")
                .append(name(mod)).append(t(lang, " klicken = an/aus.", " üzerine tıkla = aç/kapat.", " to turn it on/off.")));
        List<String> settings = settings(mod);
        if (mod.hasSettings()) {
            MutableComponent s = t(lang, "Rechtsklick darauf öffnet die Einstellungen", "Sağ tık ayarları açar",
                    "Right-click it for the settings");
            if (!settings.isEmpty()) {
                s.append(Component.literal(": " + String.join(", ", settings.subList(0, Math.min(8, settings.size())))
                        + (settings.size() > 8 ? ", …" : "")).withStyle(ChatFormatting.GRAY));
            }
            step(s.append(Component.literal(".")));
        }
        step(t(lang, "Eigene Taste: ", "Kısayol tuşu: ", "Own key: ")
                .append(code(".bind " + mod.name.replace(" ", "") + " <" + t(lang, "taste", "tuş", "key").getString() + ">"))
                .append(mod.hasKey() ? t(lang, " (jetzt: ", " (şu an: ", " (now: ").append(keyText(mod.keyName()))
                        .append(Component.literal(")")) : Component.empty()));
    }

    private static void key(NovaKnowledge.Entry e, String lang) {
        Mod mod = e.mod();
        if (mod.hasKey()) {
            say(name(mod).append(t(lang, " liegt auf ", " şu tuşta: ", " is on ")).append(keyText(mod.keyName()))
                    .append(Component.literal(".")));
        } else {
            say(name(mod).append(t(lang, " hat noch keine Taste. Setzen mit ", " modülünün henüz tuşu yok. Atamak için ",
                    " has no key yet. Set one with ")).append(code(".bind " + mod.name.replace(" ", "") + " r")));
        }
    }

    private static void setModule(NovaKnowledge.Entry e, boolean on, String lang) {
        Mod mod = e.mod();
        if (mod.active != on) {
            mod.toggle(Minecraft.getInstance());
        }
        say(name(mod).append(on
                ? t(lang, " ist jetzt an.", " açıldı.", " is now on.").withStyle(ChatFormatting.GREEN)
                : t(lang, " ist jetzt aus.", " kapatıldı.", " is now off.").withStyle(ChatFormatting.RED)));
    }

    private static void which(List<String> words, String lang) {
        List<NovaKnowledge.Entry> found = NovaKnowledge.search(words);
        if (found.isEmpty()) {
            unknown(words, lang);
            return;
        }
        say(t(lang, found.size() == 1 ? "Dafür passt:" : "Dafür passen:", "Bunun için uygun:",
                found.size() == 1 ? "This fits:" : "These fit:"));
        for (NovaKnowledge.Entry e : found) {
            step(name(e.mod()).append(Component.literal(" – " + e.about(lang)).withStyle(ChatFormatting.GRAY)));
        }
        lastModule = found.get(0);
    }

    private static void active(String lang) {
        List<String> names = new ArrayList<>();
        for (Mod mod : Mods.mods) {
            if (mod.active) {
                names.add(mod.name);
            }
        }
        if (names.isEmpty()) {
            say(t(lang, "Gerade ist kein Modul an.", "Şu an açık modül yok.", "No modules are on right now."));
        } else {
            say(t(lang, names.size() + " an: ", names.size() + " açık: ", names.size() + " on: ")
                    .append(Component.literal(String.join(", ", names)).withStyle(ChatFormatting.GREEN)));
        }
    }

    private static void list(ModCategory category, String lang) {
        if (category == null) {
            MutableComponent line = t(lang, Mods.mods.size() + " Module: ", Mods.mods.size() + " modül: ",
                    Mods.mods.size() + " modules: ");
            ModCategory[] categories = ModCategory.values();
            for (int i = 0; i < categories.length; i++) {
                int count = 0;
                for (Mod mod : Mods.mods) {
                    if (mod.category == categories[i]) {
                        count++;
                    }
                }
                line.append(Component.literal((i > 0 ? ", " : "") + NovaGui.label(categories[i]) + " " + count));
            }
            say(line);
            hint(t(lang, "Eine Kategorie zeigen: ", "Bir kategori göster: ", "Show a category: ")
                    .append(code(t(lang, ".nova zeig die visuals module", ".nova visuals modülleri neler",
                            ".nova show me the visuals modules").getString())));
            return;
        }
        MutableComponent line = Component.literal(NovaGui.label(category) + ": ");
        boolean first = true;
        for (Mod mod : Mods.mods) {
            if (mod.category == category) {
                if (!first) {
                    line.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                }
                line.append(Component.literal(mod.name).withStyle(mod.active ? ChatFormatting.GREEN : ChatFormatting.GRAY));
                first = false;
            }
        }
        say(line);
    }

    private static void death(String lang) {
        Vec3 pos = AssistantMod.lastDeath();
        if (pos == null) {
            say(t(lang, "In dieser Sitzung bist du noch nicht gestorben.", "Bu oturumda henüz ölmedin.",
                    "You haven't died this session."));
            return;
        }
        long minutes = (System.currentTimeMillis() - AssistantMod.lastDeathTime()) / 60_000L;
        String at = (int) Math.floor(pos.x) + " " + (int) Math.floor(pos.y) + " " + (int) Math.floor(pos.z);
        String dimension = AssistantMod.lastDeathDimension();
        say(t(lang, "Du bist hier gestorben: ", "Öldüğün yer: ", "You died at: ").append(AssistantMod.copyable(at))
                .append(t(lang, " (" + dimension + ", vor " + minutes + " min)", " (" + dimension + ", " + minutes + " dk önce)",
                        " (" + dimension + ", " + minutes + " min ago)").withStyle(ChatFormatting.GRAY)));
        if (minutes < 5) {
            hint(t(lang, "Deine Items verschwinden in ~" + (5 - minutes) + " min!", "Eşyaların ~" + (5 - minutes)
                    + " dk içinde kaybolur!", "Your items despawn in ~" + (5 - minutes) + " min!").withStyle(ChatFormatting.RED));
        }
    }

    private static void coords(String lang) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        String at = client.player.getBlockX() + " " + client.player.getBlockY() + " " + client.player.getBlockZ();
        client.keyboardHandler.setClipboard(at);
        say(t(lang, "Du bist bei ", "Konumun: ", "You are at ").append(AssistantMod.copyable(at))
                .append(t(lang, " (kopiert)", " (kopyalandı)", " (copied)").withStyle(ChatFormatting.GRAY)));
    }

    private static void help(String lang) {
        say(t(lang, "Ich bin die Nova-KI. Frag mich zum Beispiel:", "Ben Nova yapay zekası. Örneğin şunları sorabilirsin:",
                "I'm the Nova AI. Ask me for example:"));
        String[][] examples = {
                {"was macht anti vanish", "xray ne işe yarar", "what does anti vanish do"},
                {"wie benutze ich freecam", "freecam nasıl kullanılır", "how do I use freecam"},
                {"welches modul hilft beim basen finden", "base bulmak için hangi modül", "which module helps find bases"},
                {"mach fullbright an", "fullbright aç", "turn on fullbright"},
                {"was ist an", "hangi modüller açık", "what is on"}};
        int column = lang.equals("de") ? 0 : lang.equals("tr") ? 1 : 2;
        for (String[] example : examples) {
            step(code(".nova " + example[column]));
        }
        hint(t(lang, "Befehle wie .t esp oder .bind gibt es weiterhin – .help zeigt alle.",
                ".t esp veya .bind gibi komutlar hâlâ çalışır – .help hepsini gösterir.",
                "Commands like .t esp or .bind still work – .help lists them all."));
    }

    private static void theme(int[] color, String lang) {
        if (color[0] < 0) {
            say(t(lang, "Welche Farbe? ", "Hangi renk? ", "Which color? ")
                    .append(Component.literal(String.join(", ", UiSettings.ACCENT_NAMES)).withStyle(ChatFormatting.AQUA)));
            return;
        }
        AssistantMod.applyAccent(color[0]);
        say(t(lang, "Menüfarbe: ", "Menü rengi: ", "Menu color: ")
                .append(Component.literal(UiSettings.ACCENT_NAMES[color[0]]).withStyle(ChatFormatting.AQUA)));
    }

    private static void unknown(List<String> words, String lang) {
        List<NovaKnowledge.Entry> guess = NovaKnowledge.search(words);
        if (!guess.isEmpty()) {
            say(t(lang, "Da bin ich mir nicht sicher. Meinst du vielleicht: ", "Emin değilim. Şunu mu demek istedin: ",
                    "I'm not sure. Did you mean: ").append(name(guess.get(0).mod())).append(Component.literal("?")));
            lastModule = guess.get(0);
            return;
        }
        say(pick(lang,
                new String[]{"Das weiß ich leider nicht – ich kenne mich mit den Nova-Modulen aus.",
                        "Hm, das verstehe ich nicht. Frag mich etwas über ein Modul."},
                new String[]{"Maalesef bunu bilmiyorum – ben Nova modüllerinde uzmanım.",
                        "Hmm, bunu anlamadım. Bana bir modül hakkında soru sor."},
                new String[]{"Sorry, I don't know that – I'm an expert on the Nova modules.",
                        "Hm, I didn't get that. Ask me about a module."}));
        hint(t(lang, "Beispiel: ", "Örnek: ", "Example: ").append(code(".nova " + example(lang))));
    }

    // ---------------------------------------------------------------- Erkennung

    /** Eingabe fuers Netz: erkanntes Modul -> "xmod", Kategorie -> "xcat", Farbe -> "xcol". */
    private static List<String> brainInput(List<String> words, NovaKnowledge.Match module, int[] category, int[] color) {
        List<String> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            if (module != null && i >= module.start() && i < module.start() + module.length()) {
                if (i == module.start()) {
                    out.add("xmod");
                }
            } else if (i == category[1]) {
                out.add("xcat");
            } else if (i == color[1]) {
                out.add("xcol");
            } else {
                out.add(words.get(i));
            }
        }
        return out;
    }

    private static final String[][] CATEGORY_WORDS = {
            {"combat", "kampf", "savas", "pvp"},
            {"misc", "sonstiges", "cesitli", "diverses"},
            {"donut"},
            {"basefinding", "basen", "base", "us"},
            {"render"},
            {"visuals", "visuell", "gorsel", "gorseller", "grafik"},
            {"nova"}};

    /** {Kategorie-Index, Wort-Index} oder {-1, -1}. "nova" zaehlt nur, wenn danach "module/modulleri" kommt. */
    private static int[] findCategory(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            for (int c = 0; c < CATEGORY_WORDS.length; c++) {
                for (String w : CATEGORY_WORDS[c]) {
                    if (words.get(i).equals(w)) {
                        boolean next = i + 1 < words.size() && words.get(i + 1).startsWith("modul");
                        if (c == ModCategory.NOVA.ordinal() && !next) {
                            continue;
                        }
                        return new int[]{c, i};
                    }
                }
            }
        }
        return new int[]{-1, -1};
    }

    private static final String[][] COLOR_WORDS = {
            {"blue", "blau", "mavi"}, {"indigo"}, {"violet", "lila", "violett", "mor"}, {"teal", "turkis", "turkuaz"},
            {"green", "grun", "gruen", "yesil"}, {"amber", "gelb", "orange", "sari", "turuncu"},
            {"rose", "rosa", "pink", "pembe", "rot", "red", "kirmizi"}, {"mono", "grau", "gray", "grey", "gri", "weiss",
            "white", "beyaz"}};

    private static int[] findColor(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            for (int c = 0; c < COLOR_WORDS.length; c++) {
                for (String w : COLOR_WORDS[c]) {
                    if (words.get(i).equals(w)) {
                        return new int[]{c, i};
                    }
                }
            }
        }
        return new int[]{-1, -1};
    }

    private static final java.util.Set<String> ACTIONS = java.util.Set.of(
            "an", "aus", "ein", "ab", "einschalten", "ausschalten", "anschalten", "abschalten", "anmachen", "ausmachen",
            "aktiviere", "aktivieren", "deaktiviere", "deaktivieren", "starte", "stoppe", "schalte", "schalt",
            "on", "off", "enable", "disable", "activate", "deactivate", "turn", "switch", "start", "stop",
            "ac", "acar", "kapat", "kapa", "kapatir", "etkinlestir", "durdur", "calistir", "aktif", "devre");

    /** Steht ein Schalt-Wort im Satz (an/aus, on/off, ac/kapat ...)? */
    private static boolean hasAction(List<String> words) {
        for (String w : words) {
            if (ACTIONS.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /** Kurzer Anschluss-Satz oder Fuerwort: bezieht sich aufs letzte Modul ("und wie stelle ich das ein?"). */
    private static boolean refersBack(List<String> words) {
        for (String w : words) {
            switch (w) {
                case "das", "es", "dieses", "ihn", "den", "dem", "it", "that", "this", "onu", "bunu", "sunu", "o", "bu" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return words.size() <= 4;
    }

    private static String pickLanguage(NovaBrain.Thought thought, List<String> words) {
        if (language > 0 && language < CODES.length) {
            return CODES[language];
        }
        boolean marked = words.contains("xtrch") || words.contains("xdech");
        if (thought != null && (words.size() >= 3 || marked || thought.languageConfidence() >= 0.9F)) {
            return thought.language();
        }
        if (lastLanguage != null) {
            return lastLanguage;
        }
        String game = Minecraft.getInstance().options.languageCode.toLowerCase(Locale.ROOT);
        if (game.startsWith("de")) {
            return "de";
        }
        if (game.startsWith("tr")) {
            return "tr";
        }
        return thought != null ? thought.language() : "en";
    }

    // ---------------------------------------------------------------- Bausteine

    private static MutableComponent t(String lang, String de, String tr, String en) {
        return Component.literal(lang.equals("de") ? de : lang.equals("tr") ? tr : en);
    }

    private static MutableComponent pick(String lang, String[] de, String[] tr, String[] en) {
        String[] options = lang.equals("de") ? de : lang.equals("tr") ? tr : en;
        return Component.literal(options[ThreadLocalRandom.current().nextInt(options.length)]);
    }

    private static String example(String lang) {
        return t(lang, "was macht anti vanish", "anti vanish ne işe yarar", "what does anti vanish do").getString();
    }

    private static MutableComponent name(Mod mod) {
        return Component.literal(mod.name).withStyle(ChatFormatting.AQUA);
    }

    private static MutableComponent code(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static MutableComponent keyText(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static MutableComponent state(Mod mod, String lang) {
        return Component.literal(" ").append(mod.active
                ? t(lang, "(an)", "(açık)", "(on)").withStyle(ChatFormatting.GREEN)
                : t(lang, "(aus)", "(kapalı)", "(off)").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static List<String> settings(Mod mod) {
        try {
            if (mod.createSettings(null) instanceof ModOptionsMenu menu) {
                return menu.settingNames();
            }
        } catch (RuntimeException ignored) {
            // Manche Einstellungsfenster brauchen ein offenes Menue - dann ohne Liste
        }
        return List.of();
    }

    private static String menuKey() {
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping.getName().equals("Open Nova Client Menu")) {
                return mapping.getTranslatedKeyMessage().getString();
            }
        }
        return "Right Shift";
    }

    private static void say(Component message) {
        AssistantMod.reply(message);
    }

    private static void step(Component message) {
        AssistantMod.reply(Component.literal("  • ").withStyle(ChatFormatting.DARK_GRAY).append(message));
    }

    private static void hint(Component message) {
        AssistantMod.reply(message.copy().withStyle(style -> style.getColor() == null
                ? style.withColor(ChatFormatting.GRAY) : style));
    }
}
