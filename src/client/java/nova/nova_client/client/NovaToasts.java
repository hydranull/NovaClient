package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Toasts rechts oben fuer alle Module (Block Notifier, Weather Notifier, Advanced Stash Finder, Activity Inspector,
 * SpawnerNotifier): sie stapeln sich untereinander, statt sich gegenseitig zu verdecken. Dazu die schlichte Fund-Karte
 * im Stil von Advanced Stash Finder.
 */
public final class NovaToasts {
    public static final int TOP = 8;
    public static final int GAP = 6;
    public static final int CARD_W = 176;
    public static final int CARD_H = 44;

    private static int nextY = TOP;

    private NovaToasts() {
    }

    /** Am Anfang der Toasts in jedem HUD-Frame: unter der rechten HUD-Spalte (hoechstens bis zur Bildschirmmitte). */
    public static void beginFrame() {
        int half = Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2;
        nextY = Math.max(TOP, Math.min(HudLayout.rightBottom(), half - CARD_H));
    }

    /** Oberkante fuer den naechsten Toast. */
    public static int top() {
        return nextY;
    }

    /** Toasts bis knapp ueber y sind gezeichnet (y = Oberkante fuer den naechsten). */
    public static void setTop(int y) {
        nextY = Math.max(nextY, y);
    }

    /** Linke Kante eines Toasts der Breite width, der mit show (0..1) von rechts hereingleitet. */
    public static int slideX(int width, float show) {
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        return screenW - 8 - width + Math.round((1.0F - show) * (width + 16));
    }

    /**
     * Fund-Karte: Fensterflaeche mit Haarlinie, schmaler Akzentstrich links; Titel und Badge (Mono) oben, darunter der
     * Ort (Entfernung + Koordinaten in Mono) und rechts kleine Icons.
     */
    public static void card(GuiGraphicsExtractor g, Font font, int x0, int y, float show, String title, String badge,
                            String where, List<ItemStack> icons) {
        int x1 = x0 + CARD_W;
        int y1 = y + CARD_H;
        NovaShapes.rect(g, x0, y, x1, y1, NovaGui.RADIUS_LARGE, NovaGui.alpha(NovaGui.PANEL, show));
        NovaShapes.outline(g, x0, y, x1, y1, NovaGui.RADIUS_LARGE, NovaGui.alpha(NovaGui.BORDER, show));
        NovaShapes.rect(g, x0 + 4, y + 9, x0 + 6, y1 - 9, 1, NovaGui.alpha(NovaGui.BRAND, show));

        // Titel + Badge
        int tx = x0 + 13;
        int bx1 = x1 - 9;
        int bw = NovaType.width(font, NovaType.Face.MONO, badge) + 8;
        NovaType.draw(g, font, NovaType.Face.STRONG, NovaType.ellipsize(font, NovaType.Face.STRONG, title, bx1 - bw - 6 - tx), tx,
                NovaType.centerY(NovaType.Face.STRONG, y + 6, 12), NovaGui.alpha(NovaGui.TEXT, show));
        NovaShapes.rect(g, bx1 - bw, y + 6, bx1, y + 18, NovaGui.RADIUS_SMALL, NovaGui.alpha(NovaGui.BRAND, 0.18F * show));
        NovaType.draw(g, font, NovaType.Face.MONO, badge, bx1 - bw + 4, NovaType.centerY(NovaType.Face.MONO, y + 6, 12),
                NovaGui.alpha(NovaGui.mix(NovaGui.BRAND, 0xFFFFFFFF, 0.35F), show));

        // Wo, rechts daneben die Icons
        NovaType.draw(g, font, NovaType.Face.MONO, NovaType.ellipsize(font, NovaType.Face.MONO, where, x1 - tx - 8 - icons.size() * 14),
                tx, NovaType.centerY(NovaType.Face.MONO, y + 23, 12), NovaGui.alpha(NovaGui.TEXT_DIM, show));
        int ix = x1 - 8 - icons.size() * 14;
        for (ItemStack icon : icons) {
            NovaGui.item(g, icon, ix + 6, y + 29, 0.75F);
            ix += 14;
        }
    }

    /**
     * Toast-Liste eines Moduls mit genau diesen Fund-Karten (wie Advanced Stash Finder): neue Karten oben, gleiten von
     * rechts herein und nach Ablauf wieder hinaus. Gleicher key ersetzt die alte Karte (z.B. derselbe Spieler).
     */
    public static final class Feed {
        private record Entry(String key, String title, String badge, BlockPos pos, String where, List<ItemStack> icons,
                             long created) {
        }

        private final int max;
        private final List<Entry> entries = new ArrayList<>();

        public Feed(int max) {
            this.max = max;
        }

        /**
         * pos != null: darunter stehen Entfernung + Koordinaten (jeden Frame neu), sonst der Text where. key == null =
         * nie ersetzen.
         */
        public void push(String key, String title, String badge, BlockPos pos, String where, List<ItemStack> icons) {
            if (key != null) {
                entries.removeIf(e -> key.equals(e.key()));
            }
            entries.add(0, new Entry(key, title, badge, pos, where == null ? "" : where, List.copyOf(icons), Util.getMillis()));
            while (entries.size() > max) {
                entries.remove(entries.size() - 1);
            }
        }

        public void clear() {
            entries.clear();
        }

        /** Jeden HUD-Frame (nach {@link #beginFrame()}); seconds = wie lange eine Karte stehen bleibt. */
        public void render(GuiGraphicsExtractor g, int seconds) {
            Minecraft client = Minecraft.getInstance();
            if (entries.isEmpty() || client.player == null) {
                return;
            }
            long now = Util.getMillis();
            long life = seconds * 1000L;
            entries.removeIf(e -> now - e.created() > life);
            int y = top();
            for (Entry entry : entries) {
                float in = NovaGui.easeOutCubic((now - entry.created()) / 280.0F);
                float out = Mth.clamp((life - (now - entry.created())) / 280.0F, 0.0F, 1.0F);
                float show = in * out;
                String where = entry.where();
                if (entry.pos() != null) {
                    int distance = (int) Math.round(Math.sqrt(entry.pos().distSqr(client.player.blockPosition())));
                    where = distance + "m  ·  " + entry.pos().getX() + " " + entry.pos().getY() + " " + entry.pos().getZ();
                }
                card(g, client.font, slideX(CARD_W, show), y, show, entry.title(), entry.badge(), where, entry.icons());
                y += CARD_H + GAP;
            }
            setTop(y);
        }
    }
}
