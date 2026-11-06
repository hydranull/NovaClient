package nova.nova_client.client;

import nova.nova_client.client.mods.SpotifyMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Steuert die Spotify-Desktop-App (nur Windows, ohne Login) ueber die Windows-Mediensteuerung (SMTC):
 * Titel, Kuenstler, Album, Cover, Fortschritt, Shuffle und Repeat lesen sowie Play/Pause, Skip, Spulen.
 * Dafuer laeuft ein verstecktes PowerShell im Hintergrund, das Befehle ueber stdin bekommt.
 * Ohne SMTC faellt es auf Fenstertitel + Medientasten zurueck.
 */
public final class Spotify {
    private static final long POLL_MS = 1000L;
    /** So lange (ms) ohne Bedarf, dann wird der PowerShell-Prozess beendet (startet bei Bedarf neu). */
    private static final long IDLE_STOP_MS = 30_000L;
    private static final char SEP = (char) 31;

    private static final String SCRIPT = """
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            $sep = [string][char]31
            Add-Type -Name K -Namespace NovaSpotify -MemberDefinition '[DllImport("user32.dll")] public static extern void keybd_event(byte v, byte s, uint f, UIntPtr e);'
            function Press($k) { [NovaSpotify.K]::keybd_event($k, 0, 1, [UIntPtr]::Zero); [NovaSpotify.K]::keybd_event($k, 0, 3, [UIntPtr]::Zero) }
            function Say($text) { [Console]::Out.WriteLine($text); [Console]::Out.Flush() }
            function Clean($text) { if ($text -eq $null) { return '' }; return ([string]$text).Replace([string][char]31, ' ').Replace("`n", ' ').Replace("`r", '') }

            $smtc = $false
            try {
              Add-Type -AssemblyName System.Runtime.WindowsRuntime
              Add-Type -AssemblyName System.Drawing
              $asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
              [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime] | Out-Null
              [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
              [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
              $smtc = $true
            } catch { Say ('E' + $sep + 'SMTC unavailable: ' + $_.Exception.Message) }

            function Await($op, [Type]$type) {
              $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
              if (-not $t.Wait(3000)) { throw 'timeout' }
              return $t.Result
            }

            $manager = $null
            if ($smtc) {
              try { $manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]) }
              catch { $smtc = $false; Say ('E' + $sep + 'SMTC unavailable: ' + $_.Exception.Message) }
            }

            function Session {
              foreach ($s in $manager.GetSessions()) { if ($s.SourceAppUserModelId -like '*Spotify*') { return $s } }
              return $null
            }

            $lastKey = ''
            $coverPath = ''
            $coverIndex = 0
            $recheck = 0

            function SaveCover($ref) {
              $stream = Await ($ref.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
              # PowerShell kann das WinRT-Objekt nicht selbst umwandeln -> ueber Reflection lesen
              $size = [uint32][Windows.Storage.Streams.IRandomAccessStream].GetProperty('Size').GetValue($stream)
              $reader = [Windows.Storage.Streams.DataReader].GetConstructor(@([Windows.Storage.Streams.IInputStream])).Invoke(@($stream))
              Await ($reader.LoadAsync($size)) ([uint32]) | Out-Null
              $bytes = New-Object byte[] $size
              $reader.ReadBytes($bytes)
              $reader.Dispose()
              $ms = New-Object System.IO.MemoryStream (,$bytes)
              $img = [System.Drawing.Image]::FromStream($ms)
              $bmp = New-Object System.Drawing.Bitmap 128, 128
              $gfx = [System.Drawing.Graphics]::FromImage($bmp)
              $gfx.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
              $gfx.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
              $side = [Math]::Min($img.Width, $img.Height)
              $src = New-Object System.Drawing.Rectangle ([int](($img.Width - $side) / 2)), ([int](($img.Height - $side) / 2)), $side, $side
              $dst = New-Object System.Drawing.Rectangle 0, 0, 128, 128
              $gfx.DrawImage($img, $dst, $src, [System.Drawing.GraphicsUnit]::Pixel)
              $gfx.Dispose(); $img.Dispose(); $ms.Dispose()
              $script:coverIndex = ($script:coverIndex + 1) % 4
              $path = Join-Path $env:TEMP ('nova_spotify_cover_' + $PID + '_' + $script:coverIndex + '.png')
              $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
              $bmp.Dispose()
              return $path
            }

            function Poll {
              $s = Session
              if ($s -eq $null) { Say 'N'; return }
              $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
              $info = $s.GetPlaybackInfo()
              $tl = $s.GetTimelineProperties()
              $playing = [int]$info.PlaybackStatus -eq 4
              $pos = $tl.Position.TotalMilliseconds
              if ($playing -and $tl.LastUpdatedTime.Year -gt 2000) { $pos += ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalMilliseconds }
              $end = $tl.EndTime.TotalMilliseconds
              if ($end -gt 0 -and $pos -gt $end) { $pos = $end }
              if ($pos -lt 0) { $pos = 0 }
              $key = (Clean $p.Artist) + '|' + (Clean $p.Title) + '|' + (Clean $p.AlbumTitle)
              if ($key -ne $script:lastKey) {
                $script:coverPath = ''
                if ($p.Thumbnail -ne $null) {
                  try { $script:coverPath = SaveCover $p.Thumbnail; $script:lastKey = $key; $script:recheck = 2 } catch { }
                }
              } elseif ($script:recheck -gt 0) {
                # Spotify liefert das neue Cover manchmal etwas spaeter als den Titel -> kurz danach erneut holen
                $script:recheck--
                if ($script:recheck -eq 0 -and $p.Thumbnail -ne $null) { try { $script:coverPath = SaveCover $p.Thumbnail } catch { } }
              }
              $shuffle = 0; if ($info.IsShuffleActive -eq $true) { $shuffle = 1 }
              $repeat = 0; if ($info.AutoRepeatMode -ne $null) { $repeat = [int]$info.AutoRepeatMode }
              Say ('S' + $sep + [int]$playing + $sep + [long]$pos + $sep + [long]$end + $sep + $shuffle + $sep + $repeat + $sep + (Clean $p.Artist) + $sep + (Clean $p.Title) + $sep + (Clean $p.AlbumTitle) + $sep + $script:coverPath)
            }

            function PollTitle {
              $p = Get-Process Spotify -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle } | Select-Object -First 1
              if ($p -eq $null) { Say 'N'; return }
              $t = $p.MainWindowTitle
              $i = $t.IndexOf(' - ')
              if ($i -lt 0 -or $t.StartsWith('Spotify')) { Say ('S' + $sep + '0' + $sep + '0' + $sep + '0' + $sep + '0' + $sep + '0' + $sep + '' + $sep + '' + $sep + '' + $sep + ''); return }
              Say ('S' + $sep + '1' + $sep + '0' + $sep + '0' + $sep + '0' + $sep + '0' + $sep + (Clean $t.Substring(0, $i)) + $sep + (Clean $t.Substring($i + 3)) + $sep + '' + $sep + '')
            }

            function Control($cmd, $arg) {
              $s = $null
              if ($smtc) { $s = Session }
              if ($s -eq $null) {
                switch ($cmd) { 'play' { Press 0xB3 } 'next' { Press 0xB0 } 'prev' { Press 0xB1 } }
                return
              }
              switch ($cmd) {
                'play' { Await ($s.TryTogglePlayPauseAsync()) ([bool]) | Out-Null }
                'next' { Await ($s.TrySkipNextAsync()) ([bool]) | Out-Null }
                'prev' { Await ($s.TrySkipPreviousAsync()) ([bool]) | Out-Null }
                'seek' { Await ($s.TryChangePlaybackPositionAsync([long]$arg * 10000)) ([bool]) | Out-Null }
                'shuffle' { $on = $s.GetPlaybackInfo().IsShuffleActive -eq $true; Await ($s.TryChangeShuffleActiveAsync(-not $on)) ([bool]) | Out-Null }
                'repeat' {
                  $m = 0; $mode = $s.GetPlaybackInfo().AutoRepeatMode; if ($mode -ne $null) { $m = [int]$mode }
                  $next = 0; if ($m -eq 0) { $next = 2 } elseif ($m -eq 2) { $next = 1 }
                  Await ($s.TryChangeAutoRepeatModeAsync($next)) ([bool]) | Out-Null
                }
              }
            }

            while (($line = [Console]::In.ReadLine()) -ne $null) {
              $parts = $line.Split(' ')
              try {
                if ($parts[0] -eq 'poll') { if ($smtc) { Poll } else { PollTitle } }
                else { Control $parts[0] $parts[1] }
              } catch { Say ('E' + $sep + $_.Exception.Message) }
            }
            """;

    public static final boolean SUPPORTED = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Spotify-App laeuft (Sitzung gefunden). */
    public static volatile boolean running;
    public static volatile boolean playing;
    public static volatile boolean shuffle;
    /** 0 = aus, 1 = Song wiederholen, 2 = Liste wiederholen (wie Windows MediaPlaybackAutoRepeatMode). */
    public static volatile int repeat;
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String album = "";
    /** Pfad zum aktuellen Cover (PNG, 128x128) oder "". */
    public static volatile String coverPath = "";
    /** Hat schon mindestens eine Antwort geliefert (vorher: "Verbinde..."). */
    public static volatile boolean connected;

    // Fortschritt: zuletzt gemeldete Position + Zeitpunkt, dazwischen wird lokal weitergezaehlt
    private static volatile long positionMs;
    private static volatile long durationMs;
    private static volatile long positionAt;

    /** Wird vom Player-Fenster gesetzt, damit auch ohne aktiven Mod abgefragt wird. */
    public static volatile boolean screenOpen;

    private static final BlockingQueue<String> COMMANDS = new LinkedBlockingQueue<>();
    private static Process process;
    private static Writer input;
    private static long lastPoll;
    private static String lastError = "";

    private Spotify() {
    }

    public static void init() {
        if (!SUPPORTED) {
            return;
        }
        Thread worker = new Thread(Spotify::workLoop, "NovaClient-spotify");
        worker.setDaemon(true);
        worker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(Spotify::stop));
    }

    private static boolean wanted() {
        return SpotifyMod.isEnabled() || screenOpen;
    }

    /**
     * Arbeitet Befehle ab und fragt regelmaessig den Status ab (nur wenn gebraucht). Wird der Player eine Weile nicht
     * mehr gebraucht, wird auch der PowerShell-Prozess beendet (spart RAM/CPU auf schwachen PCs).
     */
    private static void workLoop() {
        long lastWanted = System.currentTimeMillis();
        while (true) {
            try {
                String command = COMMANDS.poll(100L, TimeUnit.MILLISECONDS);
                if (command != null) {
                    lastWanted = System.currentTimeMillis();
                    write(command);
                    continue;
                }
                long now = System.currentTimeMillis();
                if (wanted()) {
                    lastWanted = now;
                    if (now - lastPoll >= POLL_MS) {
                        lastPoll = now;
                        write("poll");
                    }
                } else if (process != null && now - lastWanted > IDLE_STOP_MS) {
                    stop();
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Steuerung

    private static void command(String command) {
        if (!SUPPORTED) {
            return;
        }
        COMMANDS.offer(command);
        // kurz danach neuen Status holen, damit die Anzeige schnell nachzieht
        lastPoll = System.currentTimeMillis() - POLL_MS + 350L;
    }

    public static void playPause() {
        playing = !playing;
        rebase();
        command("play");
    }

    public static void next() {
        command("next");
    }

    public static void previous() {
        command("prev");
    }

    public static void toggleShuffle() {
        shuffle = !shuffle;
        command("shuffle");
    }

    /** Aus -> Liste -> Song -> Aus. */
    public static void cycleRepeat() {
        repeat = repeat == 0 ? 2 : repeat == 2 ? 1 : 0;
        command("repeat");
    }

    public static void seek(long ms) {
        long target = Math.max(0L, durationMs > 0 ? Math.min(durationMs, ms) : ms);
        positionMs = target;
        positionAt = System.currentTimeMillis();
        command("seek " + target);
    }

    // ---------------------------------------------------------------- Fortschritt

    /** Aktuelle Position in ms (zwischen den Abfragen lokal weitergezaehlt). */
    public static long position() {
        long pos = positionMs;
        if (playing) {
            pos += System.currentTimeMillis() - positionAt;
        }
        return durationMs > 0 ? Math.min(durationMs, pos) : pos;
    }

    public static long duration() {
        return durationMs;
    }

    public static boolean hasTimeline() {
        return durationMs > 0;
    }

    public static float progress() {
        return durationMs > 0 ? Math.max(0.0F, Math.min(1.0F, position() / (float) durationMs)) : 0.0F;
    }

    private static void rebase() {
        positionMs = position();
        positionAt = System.currentTimeMillis();
    }

    public static String time(long ms) {
        long seconds = Math.max(0L, ms / 1000L);
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    // ---------------------------------------------------------------- PowerShell-Prozess

    private static void write(String command) {
        try {
            if (process == null || !process.isAlive()) {
                start();
            }
            input.write(command + "\n");
            input.flush();
        } catch (IOException e) {
            logError("Spotify helper failed: " + e.getMessage());
            process = null;
        }
    }

    private static void start() throws IOException {
        Path script = Path.of(System.getProperty("java.io.tmpdir"), "nova_client_spotify.ps1");
        Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
        process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", script.toString())
                .redirectErrorStream(true)
                .start();
        input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Process started = process;
        Thread reader = new Thread(() -> readLoop(started), "NovaClient-spotify-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static void readLoop(Process source) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handle(line);
            }
        } catch (IOException ignored) {
            // Prozess beendet
        }
    }

    private static void handle(String line) {
        // BOM am Anfang der ersten Zeile entfernen
        if (!line.isEmpty() && line.charAt(0) == '﻿') {
            line = line.substring(1);
        }
        if (line.equals("N")) {
            connected = true;
            running = false;
            playing = false;
            return;
        }
        String[] parts = line.split(String.valueOf(SEP), -1);
        if (parts[0].equals("E")) {
            logError("Spotify: " + (parts.length > 1 ? parts[1] : line));
            return;
        }
        if (!parts[0].equals("S") || parts.length < 10) {
            return;
        }
        try {
            boolean nowPlaying = parts[1].equals("1");
            long pos = Long.parseLong(parts[2]);
            long end = Long.parseLong(parts[3]);
            shuffle = parts[4].equals("1");
            repeat = Integer.parseInt(parts[5]);
            artist = parts[6];
            title = parts[7];
            album = parts[8];
            coverPath = parts[9];
            durationMs = end;
            positionMs = pos;
            positionAt = System.currentTimeMillis();
            playing = nowPlaying;
            running = true;
            connected = true;
        } catch (NumberFormatException ignored) {
            // kaputte Zeile ueberspringen
        }
    }

    /** Fehler nur einmal loggen (nicht jede Sekunde). */
    private static void logError(String message) {
        if (!message.equals(lastError)) {
            lastError = message;
            System.err.println("[Nova] " + message);
        }
    }

    private static synchronized void stop() {
        if (process != null) {
            process.destroy();
            process = null;
        }
    }
}
