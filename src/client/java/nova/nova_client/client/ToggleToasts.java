package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Kleine Karten unten rechts, wenn ein Modul per Taste an- oder ausgeschaltet wird (statt Text ueber der Hotbar):
 * Name, farbiger Punkt und "On"/"Off". Sie gleiten herein, stapeln sich nach oben und blenden nach kurzer Zeit aus;
 * schaltet man dasselbe Modul nochmal, wird seine Karte ersetzt.
 */
public final class ToggleToasts {
    private static final long LIFE_MS = 1600L;
    private static final long FADE_MS = 220L;
    private static final int MAX = 4;
    private static final int H = 18;
    private static final int GAP = 4;
    private static final int MARGIN = 8;
    private static final int ON = 0xFF5BE38A;
    private static final int OFF = 0xFFFF5C6C;

    private record Entry(String name, boolean on, long created) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private ToggleToasts() {
    }

    public static void push(String name, boolean on) {
        ENTRIES.removeIf(e -> e.name().equals(name));
        ENTRIES.add(0, new Entry(name, on, Util.getMillis()));
        while (ENTRIES.size() > MAX) {
            ENTRIES.remove(ENTRIES.size() - 1);
        }
    }

    /** Jeden HUD-Frame. */
    public static void render(GuiGraphicsExtractor g) {
        if (ENTRIES.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        long now = Util.getMillis();
        ENTRIES.removeIf(e -> now - e.created() > LIFE_MS);
        int screenW = g.guiWidth();
        int y = g.guiHeight() - MARGIN - H;
        for (Entry entry : ENTRIES) {
            long age = now - entry.created();
            float in = NovaGui.easeOutCubic(Mth.clamp(age / (float) FADE_MS, 0.0F, 1.0F));
            float out = Mth.clamp((LIFE_MS - age) / (float) FADE_MS, 0.0F, 1.0F);
            float a = in * out;
            String state = entry.on() ? "On" : "Off";
            int color = entry.on() ? ON : OFF;
            int w = NovaGui.width(font, entry.name()) + NovaGui.width(font, state) + 30;
            int x0 = screenW - MARGIN - w + Math.round((1.0F - in) * 24.0F);
            NovaShapes.rect(g, x0, y, x0 + w, y + H, 6, NovaGui.alpha(0xE60E0E14, a));
            NovaShapes.outline(g, x0, y, x0 + w, y + H, 6, NovaGui.alpha(0x22FFFFFF, a));
            NovaShapes.disc(g, x0 + 9.5F, y + H / 2.0F, 2.5F, NovaGui.alpha(color, a));
            NovaGui.text(g, font, entry.name(), x0 + 17, y + 5, NovaGui.alpha(0xFFF2F3F6, a), false);
            NovaGui.text(g, font, state, x0 + w - 7 - NovaGui.width(font, state), y + 5, NovaGui.alpha(color, a), false);
            y -= H + GAP;
        }
    }
}
