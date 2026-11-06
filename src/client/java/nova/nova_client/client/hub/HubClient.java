package nova.nova_client.client.hub;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import nova.nova_client.client.social.SocialClient;
import nova.nova_client.client.update.UpdateManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nova Hub: Liste der Add-ons fuer diese Minecraft-Version, Installieren, Aktualisieren, Entfernen und Bewerten.
 * <p>
 * Sicherheit wie beim Nova-Update: die Liste ist mit dem Schluessel des Nova-Servers signiert (nur das Nova-Team stellt
 * Add-ons ein), installiert wird nur eine Datei mit genau dem signierten SHA-256. Neue Add-ons laedt Minecraft beim
 * naechsten Start; geladene (gesperrte) .jars tauscht bzw. loescht ein Helfer nach dem Beenden ({@link ExitTasks}).
 */
public final class HubClient {
    public record Addon(String id, String name, String author, String description, String version, String file,
                        String sha256, long size, long updated) {
    }

    public record Rating(double avg, int count) {
    }

    public enum Status {
        NOT_INSTALLED,
        INSTALLED,
        UPDATE,
        /** Heruntergeladen, wird beim naechsten Start geladen. */
        PENDING_INSTALL,
        /** Neue Version wartet, wird nach dem Beenden getauscht. */
        PENDING_UPDATE,
        /** Wird nach dem Beenden entfernt. */
        PENDING_REMOVE
    }

    private static final long MAX_SIZE = 32L * 1024L * 1024L;
    private static final String ID_PATTERN = "[a-z0-9_]{2,40}";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private static volatile boolean loading;
    private static volatile boolean loaded;
    private static volatile String error = "";
    private static volatile String notice = "";
    private static volatile List<Addon> addons = List.of();
    private static volatile Map<String, Rating> ratings = Map.of();
    private static volatile Map<String, Long> downloads = Map.of();
    /** Laufende Downloads: id -> Fortschritt 0..1. */
    private static final Map<String, Float> progress = new ConcurrentHashMap<>();
    /** In dieser Sitzung geaendert (Neustart noetig). */
    private static final Map<String, Status> changed = new ConcurrentHashMap<>();
    /** Eigene Bewertungen dieser Sitzung. */
    private static final Map<String, Integer> mine = new ConcurrentHashMap<>();

    private HubClient() {
    }

    // ---------------------------------------------------------------- Liste

    public static void refresh() {
        if (loading) {
            return;
        }
        loading = true;
        background(() -> {
            try {
                load();
            } catch (Exception e) {
                error = "Nova server not reachable";
            } finally {
                loading = false;
                loaded = true;
            }
        });
    }

    private static void load() throws Exception {
        String mc = UpdateManager.minecraftVersion();
        if (mc == null) {
            error = "Unknown Minecraft version";
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("mc", mc);
        HttpResponse<String> response = HTTP.send(post("/hub/list", body, 15), HttpResponse.BodyHandlers.ofString());
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
            error = json.has("error") ? json.get("error").getAsString() : "Could not load add-ons";
            return;
        }
        JsonObject index = UpdateManager.verifySigned(json.get("index").getAsString());
        if (index == null || !"hub".equals(text(index, "u")) || !mc.equals(text(index, "m"))) {
            error = "The add-on list is not signed by Nova";
            return;
        }
        List<Addon> list = new ArrayList<>();
        for (JsonElement e : index.getAsJsonArray("a")) {
            JsonObject a = e.getAsJsonObject();
            Addon addon = new Addon(text(a, "id"), text(a, "name"), text(a, "author"), text(a, "description"), text(a, "version"),
                    text(a, "file"), text(a, "sha256").toLowerCase(Locale.ROOT), a.get("size").getAsLong(),
                    a.has("updated") ? a.get("updated").getAsLong() : 0L);
            if (addon.id().matches(ID_PATTERN) && addon.sha256().matches("[0-9a-f]{64}") && addon.size() > 0
                    && addon.size() <= MAX_SIZE) {
                list.add(addon);
            }
        }
        Map<String, Rating> stars = new HashMap<>();
        if (json.has("ratings")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("ratings").entrySet()) {
                JsonObject r = e.getValue().getAsJsonObject();
                stars.put(e.getKey(), new Rating(r.get("avg").getAsDouble(), r.get("count").getAsInt()));
            }
        }
        Map<String, Long> counts = new HashMap<>();
        if (json.has("downloads")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("downloads").entrySet()) {
                counts.put(e.getKey(), e.getValue().getAsLong());
            }
        }
        ratings = stars;
        downloads = counts;
        addons = list;
        error = "";
    }

    // ---------------------------------------------------------------- Installieren, Aktualisieren, Entfernen

    public static Status status(Addon addon) {
        Status pending = changed.get(addon.id());
        if (pending != null) {
            return pending;
        }
        String installed = installedVersion(addon.id());
        if (installed == null) {
            return Status.NOT_INSTALLED;
        }
        return UpdateManager.compareVersions(addon.version(), installed) > 0 ? Status.UPDATE : Status.INSTALLED;
    }

    /** Version des geladenen Add-ons, oder null. */
    public static String installedVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    public static void install(Addon addon) {
        if (progress.putIfAbsent(addon.id(), 0.0F) != null) {
            return;
        }
        background(() -> {
            try {
                Path staged = download(addon);
                Path target = modsDir().resolve("nova-addon-" + addon.id() + ".jar");
                Path loadedJar = loadedJar(addon.id());
                if (loadedJar == null) {
                    // noch nicht geladen: direkt in den mods-Ordner, Minecraft laedt es beim naechsten Start
                    ExitTasks.cancel(target);
                    Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                    changed.put(addon.id(), Status.PENDING_INSTALL);
                } else {
                    // geladen und gesperrt: nach dem Beenden alte .jar weg, neue an ihren Platz
                    ExitTasks.cancel(loadedJar);
                    if (!loadedJar.toAbsolutePath().equals(target.toAbsolutePath())) {
                        ExitTasks.delete(loadedJar);
                    }
                    ExitTasks.move(staged, target);
                    changed.put(addon.id(), Status.PENDING_UPDATE);
                }
                notice = addon.name() + " is ready - restart Minecraft to use it";
            } catch (Exception e) {
                notice = "Download of " + addon.name() + " failed";
            } finally {
                progress.remove(addon.id());
            }
        });
    }

    public static void remove(Addon addon) {
        Path target = modsDir().resolve("nova-addon-" + addon.id() + ".jar");
        Path loadedJar = loadedJar(addon.id());
        Path staged = hubDir().resolve(addon.id() + ".jar");
        ExitTasks.cancel(staged);
        if (loadedJar != null) {
            ExitTasks.delete(loadedJar);
            changed.put(addon.id(), Status.PENDING_REMOVE);
            notice = addon.name() + " will be removed when you close Minecraft";
        } else {
            try {
                Files.deleteIfExists(target);
            } catch (Exception ignored) {
                // gesperrt o.Ae.: dann nach dem Beenden
                ExitTasks.delete(target);
            }
            changed.remove(addon.id());
            notice = addon.name() + " removed";
        }
    }

    /** Herunterladen nach nova_client/hub/<id>.jar und pruefen (Groesse und signierter SHA-256). */
    private static Path download(Addon addon) throws Exception {
        Path dir = hubDir();
        Files.createDirectories(dir);
        Path part = dir.resolve(addon.id() + ".jar.part");
        Path done = dir.resolve(addon.id() + ".jar");
        JsonObject body = new JsonObject();
        body.addProperty("id", addon.id());
        body.addProperty("mc", UpdateManager.minecraftVersion());
        HttpResponse<InputStream> response = HTTP.send(post("/hub/download", body, 120), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long read = 0L;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                read += n;
                if (read > addon.size()) {
                    break;
                }
                digest.update(buffer, 0, n);
                out.write(buffer, 0, n);
                progress.put(addon.id(), Math.min(1.0F, read / (float) addon.size()));
            }
        }
        if (read != addon.size() || !HexFormat.of().formatHex(digest.digest()).equals(addon.sha256())) {
            Files.deleteIfExists(part);
            throw new IllegalStateException("Checksum mismatch");
        }
        Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
        return done;
    }

    /** .jar des geladenen Add-ons im mods-Ordner, oder null. */
    private static Path loadedJar(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(ModContainer::getOrigin)
                .filter(origin -> origin.getKind() == ModOrigin.Kind.PATH && origin.getPaths().size() == 1)
                .map(origin -> origin.getPaths().get(0))
                .filter(Files::isRegularFile)
                .orElse(null);
    }

    // ---------------------------------------------------------------- Bewerten

    public static void rate(Addon addon, int stars) {
        String token = SocialClient.token();
        if (token == null) {
            notice = "Turn on Nova Social to rate add-ons";
            return;
        }
        mine.put(addon.id(), stars);
        background(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("token", token);
                body.addProperty("id", addon.id());
                body.addProperty("stars", stars);
                JsonObject json = JsonParser.parseString(HTTP.send(post("/hub/rate", body, 12),
                        HttpResponse.BodyHandlers.ofString()).body()).getAsJsonObject();
                if (json.has("ok") && json.get("ok").getAsBoolean()) {
                    Map<String, Rating> copy = new HashMap<>(ratings);
                    copy.put(addon.id(), new Rating(json.get("avg").getAsDouble(), json.get("count").getAsInt()));
                    ratings = copy;
                    notice = "Thanks for rating " + addon.name();
                } else {
                    notice = json.has("error") ? json.get("error").getAsString() : "Rating failed";
                }
            } catch (Exception e) {
                notice = "Rating failed";
            }
        });
    }

    // ---------------------------------------------------------------- Lesen (Fenster)

    public static boolean loading() {
        return loading;
    }

    public static boolean loaded() {
        return loaded;
    }

    public static String error() {
        return error;
    }

    public static String notice() {
        return notice;
    }

    public static List<Addon> addons() {
        return addons;
    }

    public static Rating rating(String id) {
        return ratings.get(id);
    }

    public static long downloads(String id) {
        return downloads.getOrDefault(id, 0L);
    }

    public static int myRating(String id) {
        return mine.getOrDefault(id, 0);
    }

    /** Download-Fortschritt 0..1, oder -1 wenn gerade keiner laeuft. */
    public static float progress(String id) {
        return progress.getOrDefault(id, -1.0F);
    }

    /** Wurde in dieser Sitzung etwas installiert, aktualisiert oder entfernt (Neustart noetig)? */
    public static boolean restartNeeded() {
        return !changed.isEmpty();
    }

    // ---------------------------------------------------------------- Helfer

    private static Path modsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    private static Path hubDir() {
        return FabricLoader.getInstance().getGameDir().resolve("nova_client").resolve("hub");
    }

    private static HttpRequest post(String path, JsonObject body, int timeoutSeconds) {
        return HttpRequest.newBuilder(URI.create(UpdateManager.SERVER + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static void background(Runnable task) {
        Thread thread = new Thread(task, "Nova Hub");
        thread.setDaemon(true);
        thread.start();
    }
}
