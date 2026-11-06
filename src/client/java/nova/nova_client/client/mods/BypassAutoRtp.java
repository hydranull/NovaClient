package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.NovaNotify;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto RTP fuer Player Bypass: schickt /rtp, wartet auf die Landung, laesst Player Bypass ein paar Sekunden scannen und
 * teleportiert dann weiter - bis eine Base gefunden ist (dann sofort Stopp), das Limit erreicht ist oder mehrere RTPs
 * hintereinander nicht ankommen. Antworten wie "Du musst noch 12 Sekunden warten" verschieben den naechsten Versuch.
 * Oeffnet der Befehl ein Menue, wird der eingestellte Eintrag angeklickt.
 */
final class BypassAutoRtp {
    private static final String TAG = "Auto RTP";
    /** So weit (Bloecke) muss man springen, damit es als Landung zaehlt. */
    private static final double LANDING_DISTANCE = 96.0D;
    private static final int MAX_FAILS = 5;
    private static final Pattern WAIT = Pattern.compile(
            "(\\d+)\\s*(s|sec|secs|second|seconds|sek|sekunde|sekunden|m|min|mins|minute|minutes|minuten)\\b",
            Pattern.CASE_INSENSITIVE);

    private enum Phase {
        OFF, WAITING, TELEPORTING, SCANNING
    }

    // Einstellungen (gespeichert in PlayerBypassMod)
    static String command = "rtp";
    static int scanSeconds = 5;
    static int delaySeconds = 6;
    static int timeoutSeconds = 10;
    /** 0 = unbegrenzt. */
    static int maxRtps = 100;
    static boolean stopOnSuspect = false;
    static String menuItem = "";

    private static Phase phase = Phase.OFF;
    private static int sent;
    private static int fails;
    private static long nextSendAt;
    private static long deadline;
    private static long scanUntil;
    private static Vec3 sentFrom;
    private static ClientLevel sentLevel;
    private static boolean menuClicked;

    private BypassAutoRtp() {
    }

    static boolean running() {
        return phase != Phase.OFF;
    }

    static void start() {
        if (command.isBlank()) {
            NovaNotify.chat(TAG, "§cSet an RTP command first.");
            return;
        }
        phase = Phase.WAITING;
        sent = 0;
        fails = 0;
        nextSendAt = System.currentTimeMillis();
        NovaNotify.chat(TAG, "§aStarted§7 - scanning " + scanSeconds + "s after each /" + command.trim() + ".");
    }

    static void stop(String why) {
        if (phase == Phase.OFF) {
            return;
        }
        phase = Phase.OFF;
        if (why != null) {
            NovaNotify.chat(TAG, why);
        }
    }

    /** Player Bypass hat eine Base gefunden: sofort anhalten. */
    static void baseSpotted() {
        if (running()) {
            stop("§cBase found - Auto RTP stopped§7 (" + sent + " RTPs).");
            NovaNotify.sound(SoundEvents.NOTE_BLOCK_BELL, 1.0F);
        }
    }

    /** Verdaechtiger Chunk (gelb): nur anhalten, wenn so eingestellt. */
    static void suspectSpotted() {
        if (running() && stopOnSuspect) {
            stop("§eSuspect chunk found - Auto RTP stopped§7 (" + sent + " RTPs).");
            NovaNotify.sound(SoundEvents.NOTE_BLOCK_BELL, 0.8F);
        }
    }

    /** Server-Nachricht: Wartezeiten ("in 12 seconds", "1 minute") verschieben den naechsten Versuch. */
    static void onChat(Component message) {
        if (phase != Phase.WAITING && phase != Phase.TELEPORTING) {
            return;
        }
        String text = message.getString().toLowerCase(Locale.ROOT);
        if (!(text.contains("wait") || text.contains("cooldown") || text.contains("warte") || text.contains("again"))) {
            return;
        }
        Matcher matcher = WAIT.matcher(text);
        if (!matcher.find()) {
            return;
        }
        long amount = Long.parseLong(matcher.group(1));
        long millis = matcher.group(2).startsWith("m") ? amount * 60_000L : amount * 1000L;
        nextSendAt = Math.max(nextSendAt, System.currentTimeMillis() + Math.min(millis, 15 * 60_000L) + 1000L);
        if (phase == Phase.TELEPORTING) {
            phase = Phase.WAITING; // abgelehnt: zaehlt nicht als Fehlversuch
            sent = Math.max(0, sent - 1);
        }
    }

    static void tick(Minecraft client) {
        if (phase == Phase.OFF || client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        switch (phase) {
            case WAITING -> {
                if (now < nextSendAt) {
                    return;
                }
                if (maxRtps > 0 && sent >= maxRtps) {
                    stop("§7Limit reached (" + maxRtps + " RTPs) - stopped.");
                    return;
                }
                ClientPacketListener connection = client.getConnection();
                if (connection == null) {
                    return;
                }
                String cmd = command.trim();
                connection.sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
                sent++;
                sentFrom = client.player.position();
                sentLevel = client.level;
                menuClicked = false;
                deadline = now + timeoutSeconds * 1000L;
                phase = Phase.TELEPORTING;
            }
            case TELEPORTING -> {
                clickMenuItem(client);
                boolean jumped = client.level != sentLevel
                        || client.player.position().distanceToSqr(sentFrom) > LANDING_DISTANCE * LANDING_DISTANCE;
                if (jumped) {
                    fails = 0;
                    scanUntil = now + scanSeconds * 1000L;
                    phase = Phase.SCANNING;
                } else if (now >= deadline) {
                    if (++fails >= MAX_FAILS) {
                        stop("§c" + MAX_FAILS + " RTPs in a row did not arrive - stopped.");
                        return;
                    }
                    nextSendAt = now + delaySeconds * 1000L;
                    phase = Phase.WAITING;
                }
            }
            case SCANNING -> {
                if (now >= scanUntil) {
                    nextSendAt = now + Math.max(0, delaySeconds - scanSeconds) * 1000L;
                    phase = Phase.WAITING;
                }
            }
            default -> {
            }
        }
    }

    /** Oeffnet /rtp ein Menue: den Eintrag anklicken, dessen Name den eingestellten Text enthaelt. */
    private static void clickMenuItem(Minecraft client) {
        if (menuClicked || menuItem.isBlank() || client.gameMode == null
                || !(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        String wanted = menuItem.trim().toLowerCase(Locale.ROOT);
        for (Slot slot : menu.slots) {
            if (slot.container != client.player.getInventory() && slot.hasItem()
                    && slot.getItem().getHoverName().getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                client.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.PICKUP, client.player);
                menuClicked = true;
                return;
            }
        }
    }

    static String status() {
        long now = System.currentTimeMillis();
        return switch (phase) {
            case OFF -> "Auto RTP is off";
            case WAITING -> "Next RTP in " + Math.max(0L, (nextSendAt - now + 999L) / 1000L) + "s · " + sent + " sent";
            case TELEPORTING -> "Teleporting ... · " + sent + " sent";
            case SCANNING -> "Scanning " + Math.max(0L, (scanUntil - now + 999L) / 1000L) + "s · " + sent + " sent";
        };
    }
}
