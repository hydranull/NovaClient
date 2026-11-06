package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import nova.nova_client.client.CustomFonts;
import nova.nova_client.client.FontMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModConfig;
import nova.nova_client.client.NovaFonts;
import nova.nova_client.client.NovaGui;

import java.util.Properties;

/**
 * Custom Font: ersetzt Minecrafts Schrift ueberall (Chat, HUD, Menues, Schilder, Namen) durch eine der Nova-Schriften
 * aus {@link CustomFonts}. Auswahl mit Vorschau im eigenen Menue ({@link FontMenu}). Zeichen, die eine Schrift nicht hat
 * (z.B. Symbole), kommen weiter aus der Minecraft-Schrift. Die Nova-Menues behalten ihre eigene Schrift (NovaType).
 */
public class CustomFontMod extends GameTickMod {
    private static final Identifier DEFAULT_ID = FontDescription.DEFAULT.id();

    // Einstellungen
    public static int selected = 1;

    private static volatile boolean enabled;

    public CustomFontMod(String name, ModCategory category) {
        super(name, category);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        enabled = on;
        refresh();
    }

    @Override
    public void execute(Minecraft client) {
    }

    public static CustomFonts.Entry current() {
        return CustomFonts.ALL.get(Mth.clamp(selected, 0, CustomFonts.ALL.size() - 1));
    }

    /** Fuer FontManagerMixin: soll diese Schrift (minecraft:default) durch die gewaehlte ersetzt werden? */
    public static NovaFonts.Spec replacement(Identifier font) {
        return enabled && !NovaFonts.effectLookup && DEFAULT_ID.equals(font) ? current().spec() : null;
    }

    /** Fuer CachedFontProviderMixin: Minecraft-Schrift gerade ersetzt (dann nicht zwischenspeichern)? */
    public static boolean replaces(FontDescription description) {
        return enabled && description instanceof FontDescription.Resource resource && DEFAULT_ID.equals(resource.id());
    }

    public static void select(int index) {
        selected = Mth.clamp(index, 0, CustomFonts.ALL.size() - 1);
        refresh();
        ModConfig.save();
    }

    /** Neue Schrift = neue Breiten: Text-Caches leeren, Chat neu umbrechen. */
    private static void refresh() {
        NovaGui.clearTextCaches();
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.gui != null) {
            try {
                client.gui.hud.getChat().rescaleChat();
            } catch (RuntimeException ignored) {
                // noch kein HUD (Startbildschirm)
            }
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new FontMenu(parent, this);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "font", current().spec().name());
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String font = properties.getProperty(prefix + "font");
        if (font != null) {
            for (int i = 0; i < CustomFonts.ALL.size(); i++) {
                if (CustomFonts.ALL.get(i).spec().name().equals(font.trim())) {
                    selected = i;
                    break;
                }
            }
        }
    }
}
