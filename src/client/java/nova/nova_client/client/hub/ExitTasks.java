package nova.nova_client.client.hub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Datei-Arbeiten nach dem Beenden: geladene Add-on-.jars sind (unter Windows) gesperrt, solange das Spiel laeuft.
 * Austauschen und Loeschen macht deshalb ein kleiner versteckter Helfer, der wartet, bis das Spiel zu ist
 * (wie beim Nova-Update).
 */
final class ExitTasks {
    private static final List<String> OPS = new ArrayList<>();
    private static boolean hooked;

    private ExitTasks() {
    }

    static synchronized void delete(Path file) {
        OPS.removeIf(op -> op.endsWith("|" + file.toAbsolutePath()));
        OPS.add("DEL|" + file.toAbsolutePath());
        hook();
    }

    /** source nach target verschieben (target wird ersetzt). */
    static synchronized void move(Path source, Path target) {
        OPS.add("MOVE|" + source.toAbsolutePath() + "|" + target.toAbsolutePath());
        hook();
    }

    /** Fuer diese Datei nichts mehr vorhaben (z.B. Entfernen rueckgaengig gemacht). */
    static synchronized void cancel(Path file) {
        String path = file.toAbsolutePath().toString();
        OPS.removeIf(op -> op.contains("|" + path));
    }

    static synchronized boolean pendingDelete(Path file) {
        return OPS.contains("DEL|" + file.toAbsolutePath());
    }

    private static void hook() {
        if (!hooked) {
            hooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(ExitTasks::start, "Nova Hub Installer"));
        }
    }

    private static synchronized void start() {
        if (OPS.isEmpty()) {
            return;
        }
        String pid = String.valueOf(ProcessHandle.current().pid());
        try {
            Path list = Path.of(System.getProperty("java.io.tmpdir"), "nova_client_hub_" + pid + ".txt");
            Files.write(list, OPS, StandardCharsets.UTF_8);
            ProcessBuilder builder;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                Path script = Path.of(System.getProperty("java.io.tmpdir"), "nova_client_hub.ps1");
                Files.writeString(script, WINDOWS_SCRIPT, StandardCharsets.UTF_8);
                builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                        "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-ProcId", pid, "-List", list.toString());
            } else {
                Path script = Path.of(System.getProperty("java.io.tmpdir"), "nova_client_hub.sh");
                Files.writeString(script, UNIX_SCRIPT, StandardCharsets.UTF_8);
                builder = new ProcessBuilder("sh", script.toString(), pid, list.toString());
            }
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
        } catch (IOException e) {
            // naechster Start: das Hub-Fenster zeigt den Zustand wie er ist
        }
    }

    /** Wartet auf das Ende des Spiels, dann je Zeile "DEL|pfad" oder "MOVE|von|nach" (mit Wiederholungen). */
    private static final String WINDOWS_SCRIPT = """
            param([int]$ProcId, [string]$List)
            try { Wait-Process -Id $ProcId -Timeout 120 -ErrorAction SilentlyContinue } catch {}
            foreach ($line in Get-Content -LiteralPath $List) {
                $p = $line.Split('|')
                for ($i = 0; $i -lt 30; $i++) {
                    try {
                        if ($p[0] -eq 'DEL') {
                            if (Test-Path -LiteralPath $p[1]) { Remove-Item -LiteralPath $p[1] -Force -ErrorAction Stop }
                        } elseif ($p[0] -eq 'MOVE') {
                            Move-Item -LiteralPath $p[1] -Destination $p[2] -Force -ErrorAction Stop
                        }
                        break
                    } catch { Start-Sleep -Seconds 1 }
                }
            }
            Remove-Item -LiteralPath $List -Force -ErrorAction SilentlyContinue
            """;

    private static final String UNIX_SCRIPT = """
            #!/bin/sh
            i=0
            while kill -0 "$1" 2>/dev/null && [ $i -lt 120 ]; do sleep 1; i=$((i+1)); done
            while IFS='|' read -r op a b; do
                case "$op" in
                    DEL) rm -f "$a" ;;
                    MOVE) mv -f "$a" "$b" ;;
                esac
            done < "$2"
            rm -f "$2"
            """;
}
