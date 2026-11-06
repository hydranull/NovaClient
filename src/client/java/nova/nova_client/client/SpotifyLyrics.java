package nova.nova_client.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Songtexte fuer die Spotify-Karte: holt zum laufenden Song synchronisierte Lyrics (LRC) von lrclib.net und liefert
 * die Zeile zur aktuellen Position. Gibt es keine synchronisierten Lyrics (oder kein Internet), gibt es keine Zeile.
 */
public final class SpotifyLyrics {
    private static final String API = "https://lrclib.net/api/";
    /** Zeile etwas frueher zeigen - die Position aus Windows hinkt leicht hinterher. */
    private static final long LEAD_MS = 300L;
    /** Erst laden, wenn der Song so lange (ms) gleich bleibt - direkt nach dem Wechsel stimmt die Laenge oft noch nicht. */
    private static final long SETTLE_MS = 1200L;
    /** Nach einem Netzwerkfehler so lange (ms) nichts neu anfragen. */
    private static final long RETRY_MS = 30_000L;
    /** Suchtreffer, deren Laenge um mehr als so viele Sekunden abweicht, sind wohl ein anderer Song (Live, Remix). */
    private static final double MAX_DURATION_DIFF = 8.0D;
    private static final int MAX_CACHE = 64;
    private static final Pattern TIME = Pattern.compile("\\[(\\d+):(\\d+(?:[.:]\\d+)?)]");
    private static final Pattern FEATURE = Pattern.compile("\\s*[(\\[](?:feat\\.?|ft\\.?|with) [^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("\\s+-\\s+.*(?:remaster|version|edit|mix|live|mono|stereo).*$",
            Pattern.CASE_INSENSITIVE);

    private record Line(long timeMs, String text) {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "NovaClient-lyrics");
        thread.setDaemon(true);
        return thread;
    });
    /** Song -> Zeilen (leer = keine Lyrics), zuletzt benutzte bleiben. */
    private static final Map<String, List<Line>> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Line>> eldest) {
                    return size() > MAX_CACHE;
                }
            });

    private static String seenKey = "";
    private static long seenAt;
    private static volatile String requestedKey = "";
    private static volatile long retryAfter;

    private SpotifyLyrics() {
    }

    private static String key() {
        return Spotify.artist + "|" + Spotify.title + "|" + Spotify.album;
    }

    /** Jeden Frame aufrufen, solange Lyrics gebraucht werden: laedt sie fuer neue Songs im Hintergrund. */
    public static void update() {
        if (Spotify.title.isEmpty()) {
            return;
        }
        String key = key();
        long now = System.currentTimeMillis();
        if (!key.equals(seenKey)) {
            seenKey = key;
            seenAt = now;
            return;
        }
        if (key.equals(requestedKey) || now - seenAt < SETTLE_MS || now < retryAfter || CACHE.containsKey(key)) {
            return;
        }
        requestedKey = key;
        String artist = Spotify.artist;
        String title = Spotify.title;
        String album = Spotify.album;
        long duration = Spotify.duration();
        LOADER.submit(() -> {
            try {
                CACHE.put(key, fetch(artist, title, album, duration));
            } catch (Exception e) {
                // offline / Server nicht erreichbar: spaeter nochmal versuchen
                retryAfter = System.currentTimeMillis() + RETRY_MS;
                requestedKey = "";
            }
        });
    }

    /** Zeile zur aktuellen Position, "" zwischen zwei Zeilen (Instrumental), null = keine Lyrics fuer diesen Song. */
    public static String line() {
        List<Line> lines = CACHE.get(key());
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int index = index(lines);
        return index < 0 ? "" : lines.get(index).text();
    }

    /** Naechste Zeile mit Text nach der aktuellen, "" = keine mehr, null = keine Lyrics fuer diesen Song. */
    public static String nextLine() {
        List<Line> lines = CACHE.get(key());
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        for (int i = index(lines) + 1; i < lines.size(); i++) {
            if (!lines.get(i).text().isBlank()) {
                return lines.get(i).text();
            }
        }
        return "";
    }

    /**
     * Was die Lyrics gerade zeigen: lines = aktuelle Zeile zuerst ("" = Pause/Instrumental), danach die naechsten;
     * progress = wie weit die aktuelle Zeile schon gesungen ist (0..1, fuer Karaoke und die Pausen-Punkte); index =
     * Nummer der aktuellen Zeile (aendert sich = neue Zeile).
     */
    public record View(List<String> lines, float progress, int index) {
    }

    /** Aktuelle und die naechsten count-1 Zeilen, oder null (keine Lyrics fuer diesen Song / noch nicht geladen). */
    public static View view(int count) {
        List<Line> lines = CACHE.get(key());
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        int index = index(lines);
        long position = Spotify.position() + LEAD_MS;
        long start = index < 0 ? 0L : lines.get(index).timeMs();
        long end = index + 1 < lines.size() ? lines.get(index + 1).timeMs() : start + 4000L;
        float progress = end > start ? Math.max(0.0F, Math.min(1.0F, (position - start) / (float) (end - start))) : 1.0F;
        List<String> shown = new ArrayList<>(count);
        shown.add(index < 0 ? "" : lines.get(index).text());
        for (int i = index + 1; i < lines.size() && shown.size() < count; i++) {
            if (!lines.get(i).text().isBlank()) {
                shown.add(lines.get(i).text());
            }
        }
        return new View(shown, progress, index);
    }

    /** Hinweis statt Lyrics ("Loading lyrics", "No synced lyrics"), oder null. */
    public static String status() {
        if (Spotify.title.isEmpty()) {
            return null;
        }
        String key = key();
        List<Line> lines = CACHE.get(key);
        if (lines != null) {
            return lines.isEmpty() ? "No synced lyrics" : null;
        }
        return key.equals(seenKey) && System.currentTimeMillis() - seenAt > SETTLE_MS + 700L ? "Loading lyrics" : null;
    }

    /** Index der aktuellen Zeile (-1 = vor der ersten). */
    private static int index(List<Line> lines) {
        long position = Spotify.position() + LEAD_MS;
        int low = 0;
        int high = lines.size() - 1;
        int index = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs() <= position) {
                index = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return index;
    }

    // ---------------------------------------------------------------- Laden (Hintergrund-Thread)

    private static List<Line> fetch(String artist, String title, String album, long durationMs)
            throws IOException, InterruptedException {
        long seconds = Math.round(durationMs / 1000.0D);
        // 1. Exakter Treffer (braucht Album und Laenge)
        if (!album.isEmpty() && seconds > 0) {
            HttpResponse<String> response = get("get?artist_name=" + encode(artist) + "&track_name=" + encode(title)
                    + "&album_name=" + encode(album) + "&duration=" + seconds);
            if (response.statusCode() == 200) {
                List<Line> lines = parse(JsonParser.parseString(response.body()).getAsJsonObject());
                if (!lines.isEmpty()) {
                    return lines;
                }
            } else if (response.statusCode() != 404) {
                throw new IOException("HTTP " + response.statusCode());
            }
        }
        // 2. Suche mit bereinigtem Titel (ohne "feat.", "- Remastered" ...), Treffer mit passender Laenge
        String cleanTitle = VERSION.matcher(FEATURE.matcher(title).replaceAll("")).replaceAll("").trim();
        String mainArtist = artist.split(",| & | feat\\.? | x ")[0].trim();
        List<Line> lines = search("search?track_name=" + encode(cleanTitle) + "&artist_name=" + encode(mainArtist), seconds);
        if (lines.isEmpty()) {
            lines = search("search?q=" + encode(cleanTitle + " " + mainArtist), seconds);
        }
        return lines;
    }

    private static List<Line> search(String query, long seconds) throws IOException, InterruptedException {
        HttpResponse<String> response = get(query);
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        JsonObject best = null;
        double bestDiff = Double.MAX_VALUE;
        for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
            JsonObject result = element.getAsJsonObject();
            if (!hasSynced(result)) {
                continue;
            }
            double diff = seconds > 0 && result.has("duration") && !result.get("duration").isJsonNull()
                    ? Math.abs(result.get("duration").getAsDouble() - seconds) : 0.0D;
            if (diff < bestDiff) {
                best = result;
                bestDiff = diff;
            }
        }
        return best == null || bestDiff > MAX_DURATION_DIFF ? List.of() : parse(best);
    }

    private static boolean hasSynced(JsonObject result) {
        return result.has("syncedLyrics") && !result.get("syncedLyrics").isJsonNull()
                && !result.get("syncedLyrics").getAsString().isBlank();
    }

    /** LRC lesen: "[mm:ss.xx] Text", auch mehrere Zeitstempel vor einer Zeile. */
    private static List<Line> parse(JsonObject result) {
        if (!hasSynced(result)) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        for (String raw : result.get("syncedLyrics").getAsString().split("\\r?\\n")) {
            Matcher matcher = TIME.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (matcher.find() && matcher.start() == end) {
                long minutes = Long.parseLong(matcher.group(1));
                double secs = Double.parseDouble(matcher.group(2).replace(':', '.'));
                times.add(minutes * 60_000L + Math.round(secs * 1000.0D));
                end = matcher.end();
            }
            String text = raw.substring(end).trim();
            for (long time : times) {
                lines.add(new Line(time, text));
            }
        }
        lines.sort(Comparator.comparingLong(Line::timeMs));
        return List.copyOf(lines);
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "NovaClient (Minecraft mod)")
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
