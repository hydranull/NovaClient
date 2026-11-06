package nova.nova_client.client.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.activityinspector.DiscordWebhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Coord Snapper: ein Tastendruck kopiert die Koordinaten des Blocks, auf den du schaust (bis 512 Bloecke weit, auch
 * aus dem Freecam), oder deine eigenen in die Zwischenablage - als "X Y Z", "X, Y, Z" oder fertigen /tp-Befehl. Auf
 * Wunsch gehen sie zusaetzlich an einen Discord-Webhook (z.B. fuer das Team).
 */
public class CoordSnapperMod extends GameTickMod {
    public static final String[] TARGETS = {"Looked at", "Own position"};
    public static final String[] FORMATS = {"X Y Z", "X, Y, Z", "/tp command"};
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public static int key = NO_KEY;
    public static int target = 0;
    public static int range = 256;
    public static int format = 0;
    public static boolean dimension = true;
    public static boolean webhook = false;
    public static String webhookUrl = "";

    private boolean wasDown;

    public CoordSnapperMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
        boolean down = key != NO_KEY && client.gui.screen() == null
                && InputConstants.isKeyDown(key);
        if (down && !wasDown && client.player != null && client.level != null) {
            snap(client, client.level);
        }
        wasDown = down;
    }

    private void snap(Minecraft client, ClientLevel level) {
        BlockPos pos;
        if (target == 1) {
            pos = client.player.blockPosition();
        } else {
            pos = lookedAt(client, level);
            if (pos == null) {
                NovaNotify.chat(name, "§cNothing copied§7 - no loaded block within " + range + " blocks of your aim.");
                return;
            }
            if (format == 2) {
                pos = pos.above(); // auf den Block stellen, nicht hinein
            }
        }
        String text = format(pos, level.dimension());
        client.keyboardHandler.setClipboard(text);
        NovaNotify.chat(name, "§aCopied §f" + text);
        if (webhook) {
            send(client, pos, level.dimension());
        }
    }

    /** Block unter dem Fadenkreuz - ab der Kamera, damit es auch im Freecam passt. */
    private static BlockPos lookedAt(Minecraft client, ClientLevel level) {
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 from = camera.position();
        Vec3 direction = Vec3.directionFromRotation(camera.xRot(), camera.yRot());
        Vec3 to = from.add(direction.scale(range));
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                client.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static String format(BlockPos pos, ResourceKey<Level> key) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        return switch (format) {
            case 2 -> dimension && !Level.OVERWORLD.equals(key)
                    ? String.format(Locale.ROOT, "/execute in %s run tp @s %d %d %d", key.identifier(), x, y, z)
                    : String.format(Locale.ROOT, "/tp %d %d %d", x, y, z);
            case 1 -> String.format(Locale.ROOT, "%d, %d, %d", x, y, z) + suffix(key);
            default -> String.format(Locale.ROOT, "%d %d %d", x, y, z) + suffix(key);
        };
    }

    private static String suffix(ResourceKey<Level> key) {
        return dimension ? " (" + dimensionName(key) + ")" : "";
    }

    private static String dimensionName(ResourceKey<Level> key) {
        if (Level.OVERWORLD.equals(key)) {
            return "Overworld";
        }
        if (Level.NETHER.equals(key)) {
            return "Nether";
        }
        return Level.END.equals(key) ? "End" : key.identifier().getPath();
    }

    private void send(Minecraft client, BlockPos pos, ResourceKey<Level> key) {
        String url = webhookUrl.trim();
        if (!DiscordWebhook.isValid(url)) {
            NovaNotify.chat(name, "§cWebhook URL is missing or invalid.");
            return;
        }
        ServerData server = client.getCurrentServer();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "📍 Coordinates");
        embed.addProperty("color", 0x5CE1FF);
        embed.addProperty("description", String.format(Locale.ROOT,
                "**Coordinates:** `%d %d %d`\n**Dimension:** %s\n**Server:** %s\n**Player:** %s\n**Time:** <t:%d:R>",
                pos.getX(), pos.getY(), pos.getZ(), dimensionName(key), server != null ? server.name : "Singleplayer",
                client.player.getName().getString(), System.currentTimeMillis() / 1000L));
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Nova Client");
        embed.add("footer", footer);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject body = new JsonObject();
        body.add("embeds", embeds);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            boolean ok = error == null && response.statusCode() / 100 == 2;
            client.execute(() -> NovaNotify.chat(name, ok ? "§aSent to webhook." : "§cWebhook failed."));
        });
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Copy coordinates with one key", List.of(
                new ModOptionsMenu.KeyBind("Copy key", () -> key, v -> key = v),
                new ModOptionsMenu.Choice("Target", TARGETS, () -> target, v -> target = v),
                new ModOptionsMenu.Slider("Range", 16.0D, 512.0D, 16.0D, () -> range, v -> range = (int) Math.round(v),
                        v -> Math.round(v) + " blocks"),
                new ModOptionsMenu.Choice("Format", FORMATS, () -> format, v -> format = v),
                new ModOptionsMenu.Toggle("Include dimension", () -> dimension, v -> dimension = v),
                new ModOptionsMenu.Toggle("Send to Discord webhook", () -> webhook, v -> webhook = v),
                new ModOptionsMenu.Text("Webhook URL", () -> webhookUrl, v -> webhookUrl = v.trim(), 256)));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "key", String.valueOf(key));
        properties.setProperty(prefix + "target", String.valueOf(target));
        properties.setProperty(prefix + "range", String.valueOf(range));
        properties.setProperty(prefix + "format", String.valueOf(format));
        properties.setProperty(prefix + "dimension", String.valueOf(dimension));
        properties.setProperty(prefix + "webhook", String.valueOf(webhook));
        properties.setProperty(prefix + "webhookUrl", webhookUrl);
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            key = Integer.parseInt(properties.getProperty(prefix + "key", String.valueOf(key)).trim());
            target = Math.floorMod(Integer.parseInt(properties.getProperty(prefix + "target", String.valueOf(target)).trim()), TARGETS.length);
            range = Math.max(16, Math.min(512, Integer.parseInt(properties.getProperty(prefix + "range", String.valueOf(range)).trim())));
            format = Math.floorMod(Integer.parseInt(properties.getProperty(prefix + "format", String.valueOf(format)).trim()), FORMATS.length);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        dimension = Boolean.parseBoolean(properties.getProperty(prefix + "dimension", String.valueOf(dimension)).trim());
        webhook = Boolean.parseBoolean(properties.getProperty(prefix + "webhook", String.valueOf(webhook)).trim());
        webhookUrl = properties.getProperty(prefix + "webhookUrl", webhookUrl);
    }
}
