package nova.nova_client.client.mods;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.SeedFinderMenu;
import nova.nova_client.client.seedfinder.SeedCracker;
import nova.nova_client.client.seedfinder.SeedData;
import nova.nova_client.client.seedfinder.SeedScanner;
import nova.nova_client.client.seedfinder.StructureKind;

import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seed Finder: findet den Welt-Seed - im Einzelspieler direkt, auf Servern ueber /seed (falls erlaubt) oder durch
 * Zurueckrechnen aus Strukturen, End-Saeulen und dem Seed-Hash, den jeder Server mitschickt (siehe
 * {@link SeedCracker}). Sammelt beim Herumfliegen automatisch, rechnet los, sobald die Daten reichen, und merkt sich
 * alles pro Server.
 */
public class SeedFinderMod extends GameTickMod {
    private static final Pattern SEED_REPLY = Pattern.compile("\\[(-?\\d{1,20})]");

    // Einstellungen
    /** Automatisch losrechnen, sobald genug Daten da sind. */
    public static boolean autoCrack = true;
    /** Funde und Ergebnis im Chat melden. */
    public static boolean chatMessages = true;

    // Zustand des Crackers (liest das Menue)
    private static volatile boolean running;
    private static volatile boolean cancel;
    private static volatile double progress;
    private static volatile String stage = "";
    /** Letzte Meldung des Crackers oder einer Aktion. */
    public static String status = "";
    private static SeedData attemptedData;
    private static int attemptedVersion = -1;
    private static long awaitSeedUntil;

    public SeedFinderMod(String name, ModCategory category) {
        super(name, category);
        // Antwort auf /seed abfangen ("Seed: [123]")
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || Util.getMillis() > awaitSeedUntil) {
                return;
            }
            Matcher matcher = SEED_REPLY.matcher(message.getString());
            if (matcher.find()) {
                try {
                    found(SeedData.current(), Long.parseLong(matcher.group(1)), "/seed");
                    awaitSeedUntil = 0L;
                } catch (NumberFormatException ignored) {
                    // keine Zahl
                }
            }
        });
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (!enabled) {
            cancel = true;
        }
        SeedScanner.reset();
    }

    @Override
    public void execute(Minecraft client) {
        if (client.level == null || client.player == null) {
            return;
        }
        SeedData data = SeedData.current();

        // Einzelspieler: einfach nachschauen
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null) {
            try {
                long seed = server.overworld().getSeed();
                if (data.seed == null || data.seed != seed) {
                    found(data, seed, "singleplayer");
                }
            } catch (RuntimeException ignored) {
                // Welt laedt noch
            }
            return;
        }

        SeedScanner.tick(client, data, new SeedScanner.Listener() {
            @Override
            public void found(StructureKind kind, int chunkX, int chunkZ) {
                if (chatMessages && data.seed == null) {
                    NovaNotify.chat("Seed Finder", "§f" + kind.title + "§7 found at §f" + (chunkX * 16 + 8) + ", "
                            + (chunkZ * 16 + 8) + "§7 - " + progressText(data));
                }
            }

            @Override
            public void pillars(int seen) {
                if (chatMessages && data.seed == null) {
                    NovaNotify.chat("Seed Finder", "§7End pillars read: §f" + seen + "/10§7 - " + progressText(data));
                }
            }
        });

        if (autoCrack && data.seed == null && !running && ready(data)
                && (data != attemptedData || data.version != attemptedVersion)) {
            startCrack(data);
        }
    }

    // ---------------------------------------------------------------- Rechnen

    /** Wie viele Bit fuer einen Versuch noetig sind (mit Hash reichen weniger, den Rest prueft der Hash). */
    public static double neededBits(SeedData data) {
        return data.hashedSeed != null
                ? 48.0 - Math.log(SeedCracker.MAX_CANDIDATES) / Math.log(2.0)
                : 44.0;
    }

    public static boolean ready(SeedData data) {
        SeedCracker.Input input = data.input();
        if (input.structures().isEmpty() && input.treasures().isEmpty() && data.pillarsSeen() == 0) {
            return false;
        }
        return SeedCracker.informationBits(input) >= neededBits(data)
                && SeedCracker.estimateWork(input) <= SeedCracker.MAX_WORK;
    }

    public static String progressText(SeedData data) {
        double bits = SeedCracker.informationBits(data.input());
        return String.format(Locale.ROOT, "%.0f/%.0f bits of data", Math.min(bits, neededBits(data)), neededBits(data));
    }

    public static boolean running() {
        return running;
    }

    public static double progress() {
        return progress;
    }

    public static String stage() {
        return stage;
    }

    public static void cancel() {
        cancel = true;
    }

    /** Startet den Cracker im Hintergrund (laesst einen Kern fuer das Spiel frei). */
    public static void startCrack(SeedData data) {
        if (running) {
            return;
        }
        SeedCracker.Input input = data.input();
        int version = data.version;
        attemptedData = data;
        attemptedVersion = version;
        running = true;
        cancel = false;
        progress = 0.0;
        stage = "Starting";
        status = "Cracking...";
        Thread thread = new Thread(() -> {
            SeedCracker.Result result;
            ForkJoinPool pool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
            try {
                result = pool.submit(() -> SeedCracker.crack(input, new SeedCracker.Listener() {
                    @Override
                    public void progress(double fraction, String name) {
                        progress = fraction;
                        stage = name;
                    }

                    @Override
                    public boolean cancelled() {
                        return cancel;
                    }
                })).get();
            } catch (Exception e) {
                result = new SeedCracker.Result(List.of(), List.of(), false, "Error: " + e.getMessage());
            } finally {
                pool.shutdownNow();
            }
            SeedCracker.Result finalResult = result;
            Minecraft.getInstance().execute(() -> finish(data, finalResult));
        }, "Nova Seed Finder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void finish(SeedData data, SeedCracker.Result result) {
        running = false;
        progress = 1.0;
        status = result.message();
        if (!result.worldSeeds().isEmpty()) {
            found(data, result.worldSeeds().getFirst(), "cracked");
            return;
        }
        data.structureSeeds.clear();
        if (result.structureSeeds().size() <= 16) {
            data.structureSeeds.addAll(result.structureSeeds());
        }
        SeedData.save();
        if (chatMessages && !result.message().equals("Cancelled")) {
            NovaNotify.chat("Seed Finder", "§7" + result.message());
        }
    }

    private static void found(SeedData data, long seed, String source) {
        boolean isNew = data.seed == null || data.seed != seed;
        data.seed = seed;
        data.source = source;
        data.structureSeeds.clear();
        data.changed();
        status = "Seed found (" + source + ")";
        if (isNew && chatMessages && !source.equals("singleplayer")) {
            NovaNotify.chat("Seed Finder", "§aSeed found: §f" + seed + " §7(open the menu to copy it)");
        }
    }

    // ---------------------------------------------------------------- Aktionen aus dem Menue

    /** Schickt /seed an den Server (klappt nur mit Rechten) und wartet kurz auf die Antwort. */
    public static void trySeedCommand(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }
        awaitSeedUntil = Util.getMillis() + 5000L;
        client.getConnection().sendCommand("seed");
        status = "Sent /seed - waiting for an answer...";
    }

    /** Struktur am Fadenkreuz von Hand eintragen; gibt die Meldung fuer das Menue zurueck. */
    public static String addAtCrosshair(Minecraft client, StructureKind kind) {
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return "Look at a block of the structure first";
        }
        BlockPos pos = blockHit.getBlockPos();
        int localX = pos.getX() & 15;
        int localZ = pos.getZ() & 15;
        switch (kind) {
            case DESERT_PYRAMID -> {
                if (localX != 10 || localZ != 10) {
                    return "Look at the blue terracotta in the middle of the pyramid floor";
                }
            }
            case BURIED_TREASURE -> {
                if (localX != 9 || localZ != 9) {
                    return "That chest is not a buried treasure (wrong spot in the chunk)";
                }
            }
            default -> {
                // Dschungeltempel und Hexenhuette liegen ganz in ihrem Startchunk
            }
        }
        SeedData data = SeedData.current();
        if (!data.add(kind, pos.getX() >> 4, pos.getZ() >> 4, true)) {
            return kind.title + " is already in the list";
        }
        return kind.title + " added - " + progressText(data);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new SeedFinderMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "autoCrack", String.valueOf(autoCrack));
        properties.setProperty(prefix + "chat", String.valueOf(chatMessages));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        autoCrack = Boolean.parseBoolean(properties.getProperty(prefix + "autoCrack", String.valueOf(autoCrack)));
        chatMessages = Boolean.parseBoolean(properties.getProperty(prefix + "chat", String.valueOf(chatMessages)));
    }
}
