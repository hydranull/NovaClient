package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Eigene GUI-Groesse fuer die Nova-Menues, unabhaengig von der GUI-Groesse im Spiel.
 * Solange ein Nova-Menue offen ist, wird der GUI-Scale des Fensters auf den gewaehlten Wert gesetzt,
 * beim Verlassen kommt automatisch wieder der Wert aus den Minecraft-Einstellungen.
 */
public final class NovaScale {
    /** Waehlbare Groessen: 0 = wie im Spiel, sonst fester GUI-Scale. */
    public static final int[] OPTIONS = {0, 1, 2, 3, 4};

    /** 0 = wie im Spiel. */
    public static int menuScale = 0;

    private NovaScale() {
    }

    /** Nova-Menue? Der HUD-Editor bleibt bei der Spiel-Groesse, damit das HUD dort genauso aussieht wie im Spiel. */
    public static boolean isNovaScreen(Screen screen) {
        return screen != null && isNovaClass(screen.getClass()) && !(screen instanceof HudEditorScreen);
    }

    private static final java.net.URL OWN_LOCATION = location(NovaScale.class);
    private static Class<?> lastChecked;
    private static boolean lastResult;

    /**
     * Stammt die Klasse aus Nova (derselben .jar bzw. demselben Klassen-Ordner)? Bewusst nicht ueber den Paketnamen:
     * in der Release-.jar sind Klassen- und Paketnamen verschleiert.
     */
    public static boolean isNovaClass(Class<?> type) {
        if (type == lastChecked) {
            return lastResult;
        }
        java.net.URL location = location(type);
        boolean result = OWN_LOCATION != null && OWN_LOCATION.equals(location);
        lastChecked = type;
        lastResult = result;
        return result;
    }

    private static java.net.URL location(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            return source == null ? null : source.getLocation();
        } catch (SecurityException e) {
            return null;
        }
    }

    /** GUI-Scale fuer diesen Bildschirm; {@code vanilla} = Wert aus den Minecraft-Einstellungen. */
    public static int scaleFor(Screen screen, int vanilla) {
        if (menuScale <= 0 || !isNovaScreen(screen)) {
            return vanilla;
        }
        Minecraft mc = Minecraft.getInstance();
        // 0 = "Auto" = groesster Wert, der ins Fenster passt
        int max = mc.getWindow().calculateScale(0, mc.isEnforceUnicode());
        return Math.max(1, Math.min(menuScale, max));
    }

    /** Vor dem Oeffnen eines Bildschirms: passenden Scale setzen, damit er gleich richtig aufgebaut wird. */
    public static void beforeSetScreen(Screen next) {
        Minecraft mc = Minecraft.getInstance();
        int vanilla = mc.getWindow().calculateScale(mc.options.guiScale().get(), mc.isEnforceUnicode());
        int target = scaleFor(next, vanilla);
        if (mc.getWindow().getGuiScale() != target) {
            mc.getWindow().setGuiScale(target);
            mc.mouseHandler.setIgnoreFirstMove();
        }
    }

    /** Nach einer Aenderung der Einstellung: offenen Bildschirm sofort neu skalieren. */
    public static void apply() {
        Minecraft.getInstance().resizeGui();
    }

    public static String label(int scale) {
        return scale <= 0 ? "Game" : scale + "x";
    }
}
