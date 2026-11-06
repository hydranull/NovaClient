package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import nova.nova_client.client.mods.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein selbst erstelltes Macro: eine Ausloeser-Taste startet eine Folge von Schritten,
 * die nacheinander ausgefuehrt werden (Tastendruecke, Chat/Befehle, Wartezeiten).
 */
public class Macro {
    public static final int TICKS_PER_SECOND = 20;

    public enum StepType {
        KEY, TEXT, WAIT
    }

    public static class Step {
        public StepType type;
        /** KEY: Name der Taste, z.B. "key.keyboard.e" oder "key.mouse.left". */
        public String key = "";
        /** TEXT: Chatnachricht oder Befehl (mit "/"). */
        public String text = "";
        /** KEY: Haltedauer in Ticks, WAIT: Wartezeit in Ticks. */
        public int ticks;

        public static Step key(InputConstants.Key key) {
            Step step = new Step();
            step.type = StepType.KEY;
            step.key = key.getName();
            step.ticks = 1;
            return step;
        }

        public static Step text(String text) {
            Step step = new Step();
            step.type = StepType.TEXT;
            step.text = text;
            return step;
        }

        public static Step waitTicks(int ticks) {
            Step step = new Step();
            step.type = StepType.WAIT;
            step.ticks = ticks;
            return step;
        }

        public InputConstants.Key inputKey() {
            try {
                return InputConstants.getKey(key);
            } catch (RuntimeException e) {
                return InputConstants.UNKNOWN;
            }
        }

        public String keyDisplayName() {
            return inputKey().getDisplayName().getString();
        }
    }

    public String name;
    /** Ausloeser-Taste (GLFW-Code), Mod.NO_KEY = keine. */
    public int keyCode;
    public final List<Step> steps = new ArrayList<>();

    public Macro(String name, int keyCode) {
        this.name = name;
        this.keyCode = keyCode;
    }

    public boolean hasKey() {
        return keyCode != Mod.NO_KEY;
    }

    public String keyName() {
        return hasKey() ? InputConstants.Type.KEYBOARD.getOrCreate(keyCode).getDisplayName().getString() : "";
    }

    public static String formatTicks(int ticks) {
        return String.format(java.util.Locale.ROOT, "%.2fs", ticks / (float) TICKS_PER_SECOND);
    }
}
