package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import nova.nova_client.client.ModCategory;

/**
 * Fake Media: zeigt links neben dem eigenen Namen das DonutSMP-Media-Symbol - ueberall, wo der Name gezeichnet wird
 * (Nametag, Tab-Liste, Chat). Das Symbol ist ein eigenes Zeichen in der Standard-Schrift (assets/minecraft/font/
 * default.json -> textures/font/media.png). Nur auf dem eigenen Bildschirm, der Server merkt davon nichts.
 */
public class FakeMediaMod extends GameTickMod {
    /** Zeichen aus der Private-Use-Area-B, auf das das Media-Symbol gelegt ist. */
    public static final int ICON = 0x10F5A0;

    private static volatile boolean enabled;
    private static String realName;

    public FakeMediaMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        FakeMediaMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static String realName() {
        if (realName == null) {
            realName = Minecraft.getInstance().getUser().getName();
        }
        return realName;
    }

    /**
     * Startpositionen des angezeigten eigenen Namens (mit NameProtect der Fake-Name) im bereits geschuetzten Text.
     * null wenn der Name nicht vorkommt.
     */
    public static int[] namePositions(String shown) {
        if (!enabled || shown == null) {
            return null;
        }
        String name = NameProtectMod.protect(realName());
        if (name.isEmpty() || !shown.contains(name)) {
            return null;
        }
        int count = 0;
        for (int i = shown.indexOf(name); i >= 0; i = shown.indexOf(name, i + name.length())) {
            count++;
        }
        int[] positions = new int[count];
        int k = 0;
        for (int i = shown.indexOf(name); i >= 0; i = shown.indexOf(name, i + name.length())) {
            positions[k++] = i;
        }
        return positions;
    }

    /** Sink, der vor jedem Namensanfang das Symbol einfuegt. */
    public static final class Sink implements FormattedCharSink {
        private final FormattedCharSink parent;
        private final int[] positions;
        private int next;

        public Sink(FormattedCharSink parent, int[] positions) {
            this.parent = parent;
            this.positions = positions;
        }

        @Override
        public boolean accept(int position, Style style, int codepoint) {
            while (next < positions.length && position >= positions[next]) {
                boolean atName = position == positions[next];
                next++;
                // Nur in der Standard-Schrift (eigene GUI-Schriften haben das Zeichen nicht)
                if (atName && FontDescription.DEFAULT.equals(style.getFont())) {
                    // Ohne Farbe/Fett, damit das Symbol nicht eingefaerbt oder verschoben wird.
                    // Kein Leerzeichen: der Glyph-Vorschub (Breite + 1) ergibt genau 1 Pixel Abstand wie auf DonutSMP
                    if (!parent.accept(position, Style.EMPTY, ICON)) {
                        return false;
                    }
                }
            }
            return parent.accept(position, style, codepoint);
        }
    }
}
