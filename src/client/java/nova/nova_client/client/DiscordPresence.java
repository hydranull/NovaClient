package nova.nova_client.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * Discord Rich Presence ("Spielt Nova Client") ueber Discords lokale Schnittstelle (IPC): Windows ueber die Named Pipe
 * discord-ipc-N, Linux/macOS ueber den gleichnamigen Unix-Socket. Laeuft in einem eigenen Thread; ist Discord nicht
 * offen, wird alle 20 Sekunden still neu versucht.
 * <p>
 * Ein Bild = Frame aus Opcode (int32, little endian), Laenge (int32) und JSON. 0 = Handshake, 1 = Befehl.
 */
public final class DiscordPresence {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Discord");
    private static final long RETRY_MS = 20_000L;
    private static final long REFRESH_MS = 15_000L;
    private static final long STARTED = System.currentTimeMillis() / 1000L;

    private static volatile boolean wanted;
    private static volatile String details = "In the menus";
    private static volatile String state = "";
    private static Thread worker;

    private DiscordPresence() {
    }

    /** An/aus (Modul Discord Presence). */
    public static synchronized void setEnabled(boolean enabled) {
        wanted = enabled && !NovaLinks.DISCORD_APP_ID.isBlank();
        if (wanted && worker == null) {
            worker = new Thread(DiscordPresence::run, "NovaClient-Discord");
            worker.setDaemon(true);
            worker.setPriority(Thread.MIN_PRIORITY);
            worker.start();
        }
    }

    /** Was angezeigt wird (vom Spiel-Thread, aendert sich selten). */
    public static void update(String details, String state) {
        DiscordPresence.details = details;
        DiscordPresence.state = state;
    }

    // ---------------------------------------------------------------- Hintergrund

    private static void run() {
        Connection connection = null;
        String shown = null;
        long lastSend = 0L;
        while (true) {
            try {
                if (!wanted) {
                    if (connection != null) {
                        connection.close();
                        connection = null;
                        shown = null;
                    }
                    Thread.sleep(1000L);
                    continue;
                }
                if (connection == null) {
                    connection = Connection.open();
                    if (connection == null) {
                        Thread.sleep(RETRY_MS);
                        continue;
                    }
                    JsonObject hello = new JsonObject();
                    hello.addProperty("v", 1);
                    hello.addProperty("client_id", NovaLinks.DISCORD_APP_ID);
                    connection.send(0, hello);
                    connection.receive();
                }
                String now = details + "\n" + state;
                long time = System.currentTimeMillis();
                if (!now.equals(shown) || time - lastSend > REFRESH_MS) {
                    connection.send(1, activity());
                    connection.receive();
                    shown = now;
                    lastSend = time;
                }
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            } catch (IOException | RuntimeException e) {
                // Discord geschlossen oder neu gestartet: spaeter neu verbinden
                LOGGER.debug("Discord presence connection lost", e);
                if (connection != null) {
                    connection.close();
                    connection = null;
                }
                shown = null;
                try {
                    Thread.sleep(RETRY_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private static JsonObject activity() {
        JsonObject activity = new JsonObject();
        activity.addProperty("details", details);
        if (!state.isBlank()) {
            activity.addProperty("state", state);
        }
        JsonObject timestamps = new JsonObject();
        timestamps.addProperty("start", STARTED);
        activity.add("timestamps", timestamps);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "nova");
        assets.addProperty("large_text", "Nova Client - free");
        activity.add("assets", assets);
        JsonArray buttons = new JsonArray();
        JsonObject discord = new JsonObject();
        discord.addProperty("label", "Join the Discord");
        discord.addProperty("url", NovaLinks.DISCORD);
        buttons.add(discord);
        activity.add("buttons", buttons);

        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", activity);
        JsonObject command = new JsonObject();
        command.addProperty("cmd", "SET_ACTIVITY");
        command.add("args", args);
        command.addProperty("nonce", UUID.randomUUID().toString());
        return command;
    }

    /** Verbindung zu Discord: Named Pipe (Windows) oder Unix-Socket. */
    private interface Connection {
        void write(ByteBuffer frame) throws IOException;

        ByteBuffer read(int length) throws IOException;

        void close();

        default void send(int opcode, JsonObject json) throws IOException {
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(opcode).putInt(body.length).put(body).flip();
            write(frame);
        }

        /** Antwort lesen und verwerfen (sonst laeuft Discords Puffer voll). */
        default void receive() throws IOException {
            ByteBuffer header = read(8).order(ByteOrder.LITTLE_ENDIAN);
            header.getInt();
            int length = header.getInt();
            if (length < 0 || length > 1 << 20) {
                throw new IOException("Bad Discord frame");
            }
            read(length);
        }

        static Connection open() {
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            for (int i = 0; i < 10; i++) {
                try {
                    if (windows) {
                        return new PipeConnection(new RandomAccessFile("\\\\?\\pipe\\discord-ipc-" + i, "rw"));
                    }
                    Path socket = unixDirectory().resolve("discord-ipc-" + i);
                    if (Files.exists(socket)) {
                        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                        channel.connect(UnixDomainSocketAddress.of(socket));
                        return new SocketConnection(channel);
                    }
                } catch (IOException ignored) {
                    // naechste Nummer
                }
            }
            return null;
        }

        private static Path unixDirectory() {
            for (String name : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
                String value = System.getenv(name);
                if (value != null && !value.isBlank()) {
                    return Path.of(value);
                }
            }
            return Path.of("/tmp");
        }
    }

    private record PipeConnection(RandomAccessFile pipe) implements Connection {
        public void write(ByteBuffer frame) throws IOException {
            byte[] bytes = new byte[frame.remaining()];
            frame.get(bytes);
            pipe.write(bytes);
        }

        public ByteBuffer read(int length) throws IOException {
            byte[] bytes = new byte[length];
            pipe.readFully(bytes);
            return ByteBuffer.wrap(bytes);
        }

        public void close() {
            try {
                pipe.close();
            } catch (IOException ignored) {
                // egal
            }
        }
    }

    private record SocketConnection(SocketChannel channel) implements Connection {
        public void write(ByteBuffer frame) throws IOException {
            while (frame.hasRemaining()) {
                channel.write(frame);
            }
        }

        public ByteBuffer read(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Discord closed the connection");
                }
            }
            return buffer.flip();
        }

        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // egal
            }
        }
    }
}
