package nova.nova_client.client.activityinspector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import nova.nova_client.client.NovaNotify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Activity Inspector: schickt Funde als Embed an einen Discord-Webhook. Laeuft asynchron, Fehler landen im Chat.
 */
public final class DiscordWebhook {
    private static final String TAG = "Activity Inspector";
    /** Rot wie die Markierung. */
    private static final int EMBED_COLOR = 0xFF2828;
    private static final String PIN = "📍";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private DiscordWebhook() {
    }

    /** Ein Fund: Mitte des Chunks, was angeschlagen hat, was sichtbar ist, wo es liegt. */
    public record Detection(int x, int z, int chunkX, int chunkZ, String signal, String blocks, String where) {
    }

    /** Nur echte Discord-Webhook-Links (https://discord.com/api/webhooks/...). */
    public static boolean isValid(String url) {
        String u = url.trim().toLowerCase(Locale.ROOT);
        return u.startsWith("https://discord.com/api/webhooks/")
                || u.startsWith("https://discordapp.com/api/webhooks/")
                || u.startsWith("https://ptb.discord.com/api/webhooks/")
                || u.startsWith("https://canary.discord.com/api/webhooks/");
    }

    /** Fund(e) melden - pro Base ein eigener Abschnitt mit den Details. */
    public static void sendBases(String url, List<Detection> detections, boolean rtpStopped) {
        Minecraft client = Minecraft.getInstance();
        String player = client.player != null ? client.player.getPlainTextName() : "?";
        String dimension = client.level != null ? client.level.dimension().identifier().getPath() : "?";

        JsonObject embed = new JsonObject();
        embed.addProperty("title", detections.size() == 1 ? "Base detected" : detections.size() + " bases detected");
        embed.addProperty("color", EMBED_COLOR);
        embed.addProperty("description", detections.size() == 1
                ? "Activity Inspector found a built-up area underground."
                : "Activity Inspector found " + detections.size() + " built-up chunks underground.");
        JsonArray fields = new JsonArray();
        for (Detection d : detections) {
            String value = "**Detected:** " + d.signal()
                    + "\n**Visible:** " + d.blocks()
                    + "\n**Chunk:** " + d.chunkX() + ", " + d.chunkZ()
                    + "\n**Distance:** " + d.where();
            fields.add(field(PIN + " " + d.x() + ", " + d.z(), limit(value, 1024), false));
        }
        fields.add(field("Server", NovaNotify.serverId(), true));
        fields.add(field("Dimension", dimension, true));
        fields.add(field("Player", player, true));
        if (rtpStopped) {
            fields.add(field("RTP", "stopped", true));
        }
        embed.add("fields", fields);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Nova Client · Activity Inspector");
        embed.add("footer", footer);
        embed.addProperty("timestamp", java.time.Instant.now().toString());
        send(url, embed, false);
    }

    /** Testnachricht aus den Einstellungen. */
    public static void sendTest(String url) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Webhook connected");
        embed.addProperty("description", "Activity Inspector will post here when it finds a base.");
        embed.addProperty("color", EMBED_COLOR);
        send(url, embed, true);
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value.isEmpty() ? "?" : value);
        field.addProperty("inline", inline);
        return field;
    }

    private static void send(String url, JsonObject embed, boolean confirm) {
        if (!isValid(url)) {
            NovaNotify.chat(TAG, "§cInvalid webhook URL (must start with https://discord.com/api/webhooks/)");
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("username", "Activity Inspector");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            String message;
            if (error != null) {
                message = "§cWebhook failed: could not reach Discord";
            } else if (response.statusCode() / 100 != 2) {
                message = "§cWebhook failed: HTTP " + response.statusCode()
                        + (response.statusCode() == 404 ? " (webhook deleted?)" : response.statusCode() == 429 ? " (rate limited)" : "");
            } else {
                message = confirm ? "§aWebhook test sent" : null;
            }
            if (message != null) {
                Minecraft.getInstance().execute(() -> NovaNotify.chat(TAG, message));
            }
        });
    }
}
