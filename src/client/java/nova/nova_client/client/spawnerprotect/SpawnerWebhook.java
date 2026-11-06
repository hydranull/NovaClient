package nova.nova_client.client.spawnerprotect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.activityinspector.DiscordWebhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Discord-Meldungen von Spawner Protect: ein Embed mit Titel, Text, Farbe und Feldern; Account, Dimension und deine
 * Position haengen immer mit dran. Gesendet wird im Hintergrund, Fehler landen im Chat.
 */
public final class SpawnerWebhook {
    private static final String TAG = "Spawner Protect";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public static final int RED = 0xED4245;
    public static final int BLUE = 0x5865F2;
    public static final int GREEN = 0x57F287;
    public static final int YELLOW = 0xFEE75C;

    private SpawnerWebhook() {
    }

    /** Ein Feld (Name, Wert, nebeneinander ja/nein). */
    public record Field(String name, String value, boolean inline) {
    }

    public static String coordinates(BlockPos pos) {
        return "`X: " + pos.getX() + "  Y: " + pos.getY() + "  Z: " + pos.getZ() + "`";
    }

    public static void send(String url, String title, String description, int color, Field... fields) {
        Minecraft client = Minecraft.getInstance();
        if (url == null || url.isBlank() || client.player == null || client.level == null) {
            return;
        }
        if (!DiscordWebhook.isValid(url)) {
            NovaNotify.chat(TAG, "§cInvalid webhook URL (must start with https://discord.com/api/webhooks/)");
            return;
        }
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        JsonArray list = new JsonArray();
        for (Field field : fields) {
            list.add(field(field.name(), field.value(), field.inline()));
        }
        list.add(field("Account", "`" + client.player.getPlainTextName() + "`", true));
        list.add(field("Dimension", "`" + client.level.dimension().identifier() + "`", true));
        list.add(field("Your location", coordinates(client.player.blockPosition()), false));
        embed.add("fields", list);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Nova Client · Spawner Protect");
        embed.add("footer", footer);
        embed.addProperty("timestamp", Instant.now().toString());

        JsonObject body = new JsonObject();
        body.addProperty("username", "Spawner Protect");
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            String message = null;
            if (error != null) {
                message = "§cWebhook failed: could not reach Discord";
            } else if (response.statusCode() / 100 != 2) {
                message = "§cWebhook failed: HTTP " + response.statusCode();
            }
            if (message != null) {
                String text = message;
                Minecraft.getInstance().execute(() -> NovaNotify.chat(TAG, text));
            }
        });
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", name);
        field.addProperty("value", value == null || value.isEmpty() ? "?" : value);
        field.addProperty("inline", inline);
        return field;
    }
}
