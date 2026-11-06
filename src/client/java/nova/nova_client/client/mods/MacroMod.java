package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import nova.nova_client.client.Macro;
import nova.nova_client.client.MacroMenu;
import nova.nova_client.client.ModCategory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fuehrt selbst erstellte Macros per Taste aus.
 *
 * Laeuft am ANFANG jedes Ticks (vor Minecrafts Tastenverarbeitung), damit simulierte Tasten noch im
 * selben Tick wirken. Mehrere Schritte werden in einen Tick gepackt, wenn Minecraft sie dort in der
 * richtigen Reihenfolge verarbeitet: erst ein Hotbar-Slot, danach eine weitere Taste (z.B. Rechtsklick).
 * Beispiel: Slot 3 -> Rechtsklick -> Slot 1 -> Rechtsklick dauert so nur 2 Ticks (0,1 s).
 */
public class MacroMod extends GameTickMod {
    public static final List<Macro> MACROS = new ArrayList<>();

    private final ArrayDeque<Macro.Step> queue = new ArrayDeque<>();
    private final Set<Macro> keysDown = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Gehaltene Tasten -> Tick, in dem sie losgelassen werden. */
    private final Map<InputConstants.Key, Long> heldKeys = new java.util.HashMap<>();
    private long tick;
    private long nextBatchTick;

    public MacroMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new MacroMenu(parent);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        releaseAll();
        queue.clear();
        keysDown.clear();
        nextBatchTick = tick;
    }

    private void releaseAll() {
        for (InputConstants.Key key : heldKeys.keySet()) {
            KeyMapping.set(key, false);
        }
        heldKeys.clear();
    }

    /** Wird am Ende des Ticks aufgerufen; die eigentliche Arbeit passiert in {@link #startTick}. */
    @Override
    public void execute(Minecraft client) {
    }

    /** Am Anfang jedes Client-Ticks (vor der Tastenverarbeitung von Minecraft). */
    public void startTick(Minecraft client) {
        tick++;
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }

        // Ausloeser erkennen (nur im Spiel, nicht im Chat/Menue)
        boolean inGame = client.gui.screen() == null;
        for (Macro macro : MACROS) {
            boolean down = macro.hasKey() && InputConstants.isKeyDown(macro.keyCode);
            if (down && !keysDown.contains(macro) && inGame && queue.isEmpty() && heldKeys.isEmpty()) {
                queue.addAll(macro.steps);
                nextBatchTick = tick;
            }
            if (down) {
                keysDown.add(macro);
            } else {
                keysDown.remove(macro);
            }
        }

        // Abgelaufene Tasten loslassen
        Iterator<Map.Entry<InputConstants.Key, Long>> it = heldKeys.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InputConstants.Key, Long> entry = it.next();
            if (entry.getValue() <= tick) {
                KeyMapping.set(entry.getKey(), false);
                it.remove();
            }
        }

        // Naechste Schritte ausfuehren (Wartezeiten von 0 laufen direkt weiter)
        while (tick >= nextBatchTick && !queue.isEmpty()) {
            List<Macro.Step> batch = nextBatch(queue, client);
            int duration = run(batch, player);
            nextBatchTick = tick + duration;
        }
    }

    /** Fuehrt einen Block von Schritten aus und gibt zurueck, wie viele Ticks er dauert. */
    private int run(List<Macro.Step> batch, LocalPlayer player) {
        int duration = 0;
        for (Macro.Step step : batch) {
            switch (step.type) {
                case KEY -> {
                    InputConstants.Key key = step.inputKey();
                    if (key == InputConstants.UNKNOWN) {
                        continue;
                    }
                    int hold = Math.max(1, step.ticks);
                    KeyMapping.set(key, true);
                    KeyMapping.click(key);
                    heldKeys.put(key, tick + hold);
                    duration = Math.max(duration, hold);
                }
                case TEXT -> {
                    String text = step.text.trim();
                    if (text.startsWith("/")) {
                        player.connection.sendCommand(text.substring(1));
                    } else if (!text.isEmpty()) {
                        player.connection.sendChat(text);
                    }
                    duration = Math.max(duration, 1);
                }
                case WAIT -> duration = Math.max(duration, step.ticks);
            }
        }
        return duration;
    }

    /**
     * Holt die Schritte, die gemeinsam in einem Tick laufen duerfen:
     * hoechstens ein Hotbar-Slot, danach hoechstens eine weitere Taste. Text/Warten laufen einzeln.
     */
    private static List<Macro.Step> nextBatch(Deque<Macro.Step> queue, Minecraft client) {
        List<Macro.Step> batch = new ArrayList<>();
        boolean hadHotbar = false;
        boolean hadOther = false;
        Set<String> usedKeys = new HashSet<>();

        while (!queue.isEmpty()) {
            Macro.Step step = queue.peek();
            if (step.type != Macro.StepType.KEY) {
                if (batch.isEmpty()) {
                    batch.add(queue.poll());
                }
                break;
            }
            boolean hotbar = isHotbarKey(client, step.key);
            if (!batch.isEmpty()) {
                if (usedKeys.contains(step.key) || hadOther || (hotbar && hadHotbar)) {
                    break;
                }
            }
            batch.add(queue.poll());
            usedKeys.add(step.key);
            if (hotbar) {
                hadHotbar = true;
            } else {
                hadOther = true;
            }
            if (step.ticks > 1) {
                break; // gehaltene Taste: der Rest kommt danach
            }
        }
        return batch;
    }

    private static boolean isHotbarKey(Minecraft client, String keyName) {
        for (KeyMapping mapping : client.options.keyHotbarSlots) {
            if (!mapping.isUnbound() && mapping.saveString().equals(keyName)) {
                return true;
            }
        }
        return false;
    }

    /** Geschaetzte Dauer eines Macros in Ticks (gleiche Regeln wie beim Ausfuehren). */
    public static int estimateTicks(Macro macro) {
        Minecraft client = Minecraft.getInstance();
        Deque<Macro.Step> copy = new ArrayDeque<>(macro.steps);
        int total = 0;
        while (!copy.isEmpty()) {
            int duration = 0;
            for (Macro.Step step : nextBatch(copy, client)) {
                duration = Math.max(duration, switch (step.type) {
                    case KEY -> Math.max(1, step.ticks);
                    case TEXT -> 1;
                    case WAIT -> step.ticks;
                });
            }
            total += duration;
        }
        return total;
    }
}
