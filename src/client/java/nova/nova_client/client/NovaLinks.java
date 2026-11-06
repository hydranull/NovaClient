package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Util;

import java.net.URI;

/**
 * Community-Links von Nova (Discord) und die Begruessung beim ersten Betreten einer Welt pro Spielstart.
 */
public final class NovaLinks {
    /** Discord-Einladung. */
    public static final String DISCORD = "https://discord.gg/nova-client";
    /**
     * Application-ID fuer Discord Rich Presence (discord.com/developers/applications -> "Nova Client" -> Application ID;
     * unter Rich Presence -> Art Assets ein Bild mit dem Namen "nova" hochladen). Leer = Rich Presence aus.
     */
    public static final String DISCORD_APP_ID = "";

    private static boolean welcomed;

    private NovaLinks() {
    }

    /** "discord.gg/xyz" - der Link ohne https:// fuer die Anzeige. */
    public static String discordShort() {
        return DISCORD.replaceFirst("^https?://(www\\.)?", "");
    }

    public static void openDiscord() {
        Compat.openUri(DISCORD);
    }

    /** Einmal pro Spielstart, sobald der Spieler in einer Welt ist: Hinweis auf den Discord im Chat. */
    public static void welcome(Minecraft client) {
        if (welcomed || client.player == null) {
            return;
        }
        welcomed = true;
        int brand = NovaGui.BRAND & 0xFFFFFF;
        int brand2 = NovaGui.BRAND_2 & 0xFFFFFF;
        Style link = Style.EMPTY.withColor(TextColor.fromRgb(brand)).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(DISCORD)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Open the Nova Discord")));
        MutableComponent title = Component.empty()
                .append(Component.literal("✦ ").withStyle(s -> s.withColor(TextColor.fromRgb(brand2))))
                .append(Component.literal("Nova Client").withStyle(s -> s.withColor(TextColor.fromRgb(brand)).withBold(true)))
                .append(Component.literal("  ·  free for everyone").withStyle(s -> s.withColor(TextColor.fromRgb(0x9AA3B5))));
        MutableComponent body = Component.empty()
                .append(Component.literal("Join our Discord now for news, updates & support: ")
                        .withStyle(s -> s.withColor(TextColor.fromRgb(0xD7DCE6))))
                .append(Component.literal(discordShort()).withStyle(link));
        client.gui.chatListener().handleSystemMessage(Component.empty(), false);
        client.gui.chatListener().handleSystemMessage(title, false);
        client.gui.chatListener().handleSystemMessage(body, false);
        client.gui.chatListener().handleSystemMessage(Component.empty(), false);
    }
}
