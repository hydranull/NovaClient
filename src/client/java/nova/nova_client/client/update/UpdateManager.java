package nova.nova_client.client.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModOrigin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Auto-Update: beim Start fragt Nova im Hintergrund den Nova-Server nach einer neuen Version ("/v2/update/check"),
 * laedt sie ("/v2/update/download") und installiert sie beim Beenden des Spiels.
 * <p>
 * Nova ist kostenlos und quelloffen: jeder offizielle Build bekommt Updates, ohne Key; selbst gebaute Versionen nie
 * (siehe {@link #official()}). Sicherheit: Die Update-Info ist mit dem Ed25519-Schluessel
 * des Nova-Servers signiert ({@link #verifySigned}) und enthaelt den SHA-256 der .jar. Nur eine Datei mit genau diesem
 * Hash wird installiert - eine fremde oder beschaedigte .jar nie.
 * <p>
 * Installieren: Die laufende .jar ist (unter Windows) gesperrt. Deshalb liegt die neue erst in
 * {@code nova_client/update/} (nicht im mods-Ordner, sonst laedt Fabric beide). Beim Beenden startet ein Shutdown-Hook
 * einen kleinen versteckten Helfer, der wartet, bis das Spiel zu ist, die neue .jar in den mods-Ordner kopiert und
 * erst danach die alte loescht. Klappt das nicht, bleibt die alte Version (nie zwei Nova-.jars im Ordner).
 * <p>
 * Im Entwicklungs-Lauf (IntelliJ) passiert nichts: dort ist Nova keine .jar.
 * <p>
 * Jede Minecraft-Version hat ihren eigenen Update-Kanal: Nova schickt die Version mit, fuer die diese .jar gebaut
 * ist, und installiert nur ein Update, dessen signierte Info genau diese Version nennt.
 */
public final class UpdateManager {
    /** Adresse des Nova-Servers. Zum Testen: -Dnova.license.url=... */
    public static final String SERVER = System.getProperty("nova.license.url", "https://nova-license.novaclient.workers.dev");
    /**
     * Oeffentlicher Ed25519-Schluessel des Nova-Servers (prueft die Update-Infos). Zum Testen gegen einen lokalen
     * Server mit eigenem Testschluessel: -Dnova.signing.key=...
     */
    private static final String PUBLIC_KEY = System.getProperty("nova.signing.key",
            "MCowBQYDK2VwAyEAOIIhnXS2Q4dXDpqublrAuEdCB2MmcJC9vgwDIjMDtiQ=");

    public enum State {
        /** Noch nicht geprueft / kein Update moeglich (Entwicklung). */
        IDLE,
        CHECKING,
        DOWNLOADING,
        /** Neue Version geladen und geprueft - wird beim Beenden installiert. */
        READY,
        UP_TO_DATE,
        FAILED
    }

    /**
     * Update-Kanal ab Beta 0.100 (neue Versionsnummern). Die alten Wege /update/... bedient der Server weiter fuer
     * Clients bis 2.0.0 - die bekommen dort einmal die neue Version und landen danach hier.
     */
    private static final String CHECK = "/v2/update/check";
    private static final String DOWNLOAD = "/v2/update/download";

    /** Groesser darf eine Update-.jar nicht sein (Schutz vor Unsinn). */
    private static final long MAX_SIZE = 64L * 1024L * 1024L;

    private static volatile State state = State.IDLE;
    private static volatile String newVersion;
    private static volatile float progress;
    private static volatile Path staged;
    private static volatile String stagedName;
    private static Path currentJar;
    private static boolean started;
    private static boolean hookInstalled;

    private UpdateManager() {
    }

    public static State state() {
        return state;
    }

    /** Version des geladenen Updates (oder null). */
    public static String newVersion() {
        return newVersion;
    }

    /** Download-Fortschritt 0..1. */
    public static float progress() {
        return progress;
    }

    public static boolean ready() {
        return state == State.READY;
    }

    // ---------------------------------------------------------------- Start

    /** Beim Start: im Hintergrund pruefen und laden (nur offizielle Builds, siehe {@link #official()}). */
    public static synchronized void init() {
        if (started) {
            return;
        }
        started = true;
        if (!official()) {
            return; // aus dem Quellcode selbst gebaut: nie durch die offizielle Version ersetzen
        }
        currentJar = ownJar();
        if (currentJar == null) {
            return; // Entwicklung (IntelliJ) oder unbekannter Start: nie selbst ersetzen
        }
        // Aeltere Versionen hiessen NovaClient-<mc>-<version>.jar: beim Beenden in NovaClient-<mc>.jar umbenennen
        String minecraft = minecraftVersion();
        if (minecraft != null && !currentJar.getFileName().toString().equals(jarName(minecraft))) {
            installOnExit();
        }
        Thread thread = new Thread(UpdateManager::run, "Nova Update");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Pfad der eigenen .jar im mods-Ordner, oder null (Entwicklung, eingebettet, Ordner statt Datei). */
    private static Path ownJar() {
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isDevelopmentEnvironment()) {
            return null;
        }
        try {
            ModContainer container = loader.getModContainer("nova_client").orElse(null);
            if (container == null || container.getOrigin().getKind() != ModOrigin.Kind.PATH) {
                return null;
            }
            List<Path> paths = container.getOrigin().getPaths();
            if (paths.size() != 1) {
                return null;
            }
            Path jar = paths.get(0).toAbsolutePath();
            return Files.isRegularFile(jar) && jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    ? jar : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Offizieller Build (beim Bauen mit nova_official=true markiert)? Nur der bekommt Auto-Updates - eine selbst gebaute
     * oder veraenderte Version aus dem Quellcode wuerde sonst beim naechsten Beenden durch die offizielle ersetzt.
     */
    public static boolean official() {
        return FabricLoader.getInstance().getModContainer("nova_client")
                .map(c -> c.getMetadata().getCustomValue("nova_client:official"))
                .map(v -> v.getType() == CustomValue.CvType.BOOLEAN ? v.getAsBoolean()
                        : v.getType() == CustomValue.CvType.STRING && v.getAsString().equals("true"))
                .orElse(false);
    }

    private static String currentVersion() {
        return FabricLoader.getInstance().getModContainer("nova_client")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    /** Minecraft-Version, fuer die diese .jar gebaut ist (beim Bauen in fabric.mod.json eingetragen), oder null. */
    public static String minecraftVersion() {
        return FabricLoader.getInstance().getModContainer("nova_client")
                .map(c -> c.getMetadata().getCustomValue("nova_client:minecraft"))
                .filter(v -> v.getType() == CustomValue.CvType.STRING)
                .map(CustomValue::getAsString)
                .filter(v -> v.matches("\\d+(\\.\\d+){1,3}"))
                .orElse(null);
    }

    /** Dateiname der Mod im mods-Ordner, z.B. NovaClient-26.3.jar (ohne Mod-Version). */
    private static String jarName(String minecraft) {
        return "NovaClient-" + minecraft + ".jar";
    }

    private static Path updateDir() {
        return FabricLoader.getInstance().getGameDir().resolve("nova_client").resolve("update");
    }

    // ---------------------------------------------------------------- Pruefen und laden (Hintergrund)

    private static void run() {
        try {
            cleanUpdateDir();
            String minecraft = minecraftVersion();
            if (minecraft == null) {
                return; // ohne bekannte Minecraft-Version nie updaten (koennte die falsche .jar sein)
            }
            state = State.CHECKING;
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
            JsonObject body = new JsonObject();
            body.addProperty("version", currentVersion());
            body.addProperty("mc", minecraft);

            HttpResponse<String> response = client.send(post(CHECK, body, 12), HttpResponse.BodyHandlers.ofString());
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                state = State.FAILED;
                return;
            }
            if (!json.has("update") || json.get("update").isJsonNull()) {
                state = State.UP_TO_DATE;
                return;
            }
            JsonObject info = verifySigned(json.get("update").getAsString());
            if (info == null || !info.has("u") || !"update".equals(info.get("u").getAsString())) {
                state = State.FAILED; // nicht vom Nova-Server signiert
                return;
            }
            if (!info.has("m") || !minecraft.equals(info.get("m").getAsString())) {
                state = State.FAILED; // Update fuer eine andere Minecraft-Version (oder alter Server) - nie installieren
                return;
            }
            String version = info.get("v").getAsString();
            String sha256 = info.get("s").getAsString().toLowerCase(Locale.ROOT);
            long size = info.get("n").getAsLong();
            if (!version.matches("\\d+(\\.\\d+){0,3}") || !sha256.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_SIZE) {
                state = State.FAILED;
                return;
            }
            if (compareVersions(version, currentVersion()) <= 0) {
                state = State.UP_TO_DATE;
                return;
            }

            state = State.DOWNLOADING;
            progress = 0.0F;
            Path dir = updateDir();
            Files.createDirectories(dir);
            String name = jarName(minecraft);
            Path part = dir.resolve(name + ".part");
            Path done = dir.resolve(name);
            HttpResponse<InputStream> download = client.send(post(DOWNLOAD, body, 300),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (download.statusCode() != 200) {
                download.body().close();
                state = State.FAILED;
                return;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long read = 0L;
            try (InputStream in = download.body(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    read += n;
                    if (read > size) {
                        break; // laenger als angekuendigt
                    }
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    progress = Math.min(1.0F, read / (float) size);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (read != size || !actual.equals(sha256)) {
                Files.deleteIfExists(part);
                state = State.FAILED; // beschaedigt oder manipuliert - nie installieren
                return;
            }
            Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
            stagedName = name;
            staged = done;
            newVersion = version;
            progress = 1.0F;
            installOnExit();
            state = State.READY;
        } catch (Exception e) {
            // Kein Internet, Server nicht erreichbar, ...: einfach beim naechsten Start wieder versuchen
            state = State.FAILED;
        }
    }

    /**
     * Vom Server signierte Nachricht pruefen ("base64url(JSON).base64url(Signatur)").
     * Rueckgabe: der JSON-Inhalt, oder null wenn die Signatur nicht vom Nova-Server stammt.
     */
    public static JsonObject verifySigned(String raw) {
        try {
            int dot = raw.indexOf('.');
            if (dot <= 0) {
                return null;
            }
            String body = raw.substring(0, dot);
            byte[] signature = Base64.getUrlDecoder().decode(raw.substring(dot + 1));
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(body.getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(signature)) {
                return null;
            }
            return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpRequest post(String path, JsonObject body, int timeoutSeconds) {
        return HttpRequest.newBuilder(URI.create(SERVER + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    /** Reste frueherer Laeufe wegraeumen (abgebrochene Downloads, schon installierte Updates). */
    private static void cleanUpdateDir() {
        Path dir = updateDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            files.forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // gesperrt o.Ae. - naechstes Mal
                }
            });
        } catch (IOException ignored) {
            // egal
        }
    }

    /** "1.10.0" > "1.9.2"; Anhaenge wie "-beta" oder "+build" werden ignoriert. */
    public static int compareVersions(String a, String b) {
        String[] x = a.split("[-+]")[0].split("\\.");
        String[] y = b.split("[-+]")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0;
            int q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int parse(String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- Installieren beim Beenden

    private static synchronized void installOnExit() {
        if (hookInstalled) {
            return;
        }
        hookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(UpdateManager::startInstaller, "Nova Update Installer"));
    }

    /**
     * Startet den Helfer, der nach dem Beenden die .jar tauscht (laeuft weiter, wenn Java schon zu ist). Ohne Update
     * benennt er nur die eigene .jar auf den festen Namen um (NovaClient-<mc>.jar).
     */
    private static void startInstaller() {
        Path old = currentJar;
        String minecraft = minecraftVersion();
        if (old == null) {
            return;
        }
        Path update = staged;
        String name = stagedName;
        if (update == null || name == null || !Files.isRegularFile(update)) {
            if (minecraft == null || old.getFileName().toString().equals(jarName(minecraft))) {
                return;
            }
            update = old; // nur umbenennen
            name = jarName(minecraft);
        }
        Path target = old.getParent().resolve(name);
        String pid = String.valueOf(ProcessHandle.current().pid());
        try {
            ProcessBuilder builder;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                Path script = Path.of(System.getProperty("java.io.tmpdir"), "nova_client_update.ps1");
                Files.writeString(script, WINDOWS_SCRIPT, StandardCharsets.UTF_8);
                builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-ProcId", pid,
                        "-Old", old.toString(), "-New", update.toString(), "-Target", target.toString());
            } else {
                Path script = Path.of(System.getProperty("java.io.tmpdir"), "nova_client_update.sh");
                Files.writeString(script, UNIX_SCRIPT, StandardCharsets.UTF_8);
                builder = new ProcessBuilder("sh", script.toString(), pid, old.toString(), update.toString(),
                        target.toString());
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
        } catch (IOException e) {
            // Helfer liess sich nicht starten: alte Version bleibt, beim naechsten Start neuer Versuch
        }
    }

    /**
     * Wartet auf das Ende des Spiels, kopiert die neue .jar als .part in den mods-Ordner, loescht die alte und gibt
     * der neuen dann ihren Namen (bei gleichem Namen wird ersetzt). Laesst sich die alte nicht loeschen, wird die
     * .part wieder entfernt - nie zwei Nova-Versionen gleichzeitig. Bei New = Old wird nur umbenannt.
     */
    private static final String WINDOWS_SCRIPT = """
            param([int]$ProcId, [string]$Old, [string]$New, [string]$Target)
            try { Wait-Process -Id $ProcId -Timeout 120 -ErrorAction SilentlyContinue } catch {}
            # Erst als .part kopieren (laedt Fabric nicht), dann alte .jar weg, dann an den richtigen Namen schieben
            $tmp = "$Target.part"
            $ok = $false
            for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
                try {
                    if (Test-Path -LiteralPath $New) { Copy-Item -LiteralPath $New -Destination $tmp -Force -ErrorAction Stop }
                    if ($Target -ne $Old -and (Test-Path -LiteralPath $Old)) { Remove-Item -LiteralPath $Old -Force -ErrorAction Stop }
                    Move-Item -LiteralPath $tmp -Destination $Target -Force -ErrorAction Stop
                    $ok = $true
                } catch { Start-Sleep -Seconds 1 }
            }
            if ($ok) {
                if ($New -ne $Old) { Remove-Item -LiteralPath $New -Force -ErrorAction SilentlyContinue }
            } elseif (Test-Path -LiteralPath $Old) {
                Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
            }
            """;

    private static final String UNIX_SCRIPT = """
            #!/bin/sh
            i=0
            while kill -0 "$1" 2>/dev/null && [ $i -lt 120 ]; do sleep 1; i=$((i+1)); done
            tmp="$4.part"
            if cp -f "$3" "$tmp"; then
                if [ "$4" = "$2" ] || rm -f "$2"; then
                    mv -f "$tmp" "$4" && { [ "$3" = "$2" ] || rm -f "$3"; }
                else
                    rm -f "$tmp"
                fi
            fi
            """;
}
