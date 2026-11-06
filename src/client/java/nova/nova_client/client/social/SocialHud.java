package nova.nova_client.client.social;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.NovaType;
import nova.nova_client.client.WorldToScreen;

import java.util.List;

/**
 * Nova Social im HUD: Karten rechts oben (Nachricht, Anfrage, Freund online, Party-Einladung), Pings der Party als
 * Markierung in der Welt und Party-Mitglieder, die weiter weg sind, mit Name und Entfernung.
 */
public final class SocialHud {
    private static final NovaToasts.Feed FEED = new NovaToasts.Feed(4);
    /** So lange steht eine Karte. */
    private static final int TOAST_SECONDS = 5;
    private static final long PING_MS = 12_000L;
    /** Naeher als das sieht man den Spieler selbst (mit Namensschild) - kein Marker. */
    private static final double MEMBER_MIN = 24.0D;

    private SocialHud() {
    }

    /** Karte rechts oben; gleicher key ersetzt die alte (z.B. mehrere Nachrichten desselben Freundes). */
    public static void toast(String key, String title, String badge, String text) {
        FEED.push(key, title, badge, null, text, List.of());
    }

    /** Jeden HUD-Frame, nach WorldToScreen.begin() und NovaToasts.beginFrame(). */
    public static void render(GuiGraphicsExtractor g) {
        FEED.render(g, TOAST_SECONDS);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        long now = Util.getMillis();
        for (SocialClient.Mark mark : SocialClient.positions()) {
            double distance = mark.pos().distanceTo(eye);
            if (distance >= MEMBER_MIN && SocialClient.sameWorld(mark)) {
                member(g, mc.font, mark, distance);
            }
        }
        for (SocialClient.Mark ping : SocialClient.pings()) {
            if (SocialClient.sameWorld(ping)) {
                ping(g, mc.font, ping, ping.pos().distanceTo(eye), now - ping.at());
            }
        }
    }

    /** Ping: aufgehender Ring, Punkt in der Akzentfarbe und darueber Name und Entfernung. */
    private static void ping(GuiGraphicsExtractor g, Font font, SocialClient.Mark ping, double distance, long age) {
        float[] at = WorldToScreen.project(ping.pos());
        if (at == null) {
            return;
        }
        float a = Math.min(1.0F, Math.min(age / 150.0F, (PING_MS - age) / 1500.0F));
        if (a <= 0.01F) {
            return;
        }
        float x = at[0];
        float y = at[1];
        float wave = (age % 1200L) / 1200.0F;
        NovaShapes.ring(g, x, y, 4.0F + 10.0F * wave, 1.2F, NovaGui.alpha(NovaGui.BRAND, a * 0.7F * (1.0F - wave)));
        NovaShapes.disc(g, x, y, 4.5F, NovaGui.alpha(0xFF0E0F12, a * 0.6F));
        NovaShapes.disc(g, x, y, 3.0F, NovaGui.alpha(NovaGui.BRAND, a));
        NovaShapes.disc(g, x, y, 1.2F, NovaGui.alpha(0xFFFFFFFF, a));
        label(g, font, ping.name() + "  ·  " + Math.round(distance) + "m", x, y - 18, a, true);
    }

    /** Party-Mitglied weiter weg: kleiner Punkt am Kopf, Name und Entfernung darueber. */
    private static void member(GuiGraphicsExtractor g, Font font, SocialClient.Mark mark, double distance) {
        float[] at = WorldToScreen.project(mark.pos().add(0.0D, 2.1D, 0.0D));
        if (at == null) {
            return;
        }
        NovaShapes.disc(g, at[0], at[1], 3.0F, 0x990E0F12);
        NovaShapes.disc(g, at[0], at[1], 2.0F, 0xFFFFFFFF);
        label(g, font, mark.name() + "  ·  " + Math.round(distance) + "m", at[0], at[1] - 15, 0.85F, false);
    }

    /** Kleine dunkle Pille mit Text, mittig ueber (cx, y). */
    private static void label(GuiGraphicsExtractor g, Font font, String text, float cx, float y, float a, boolean accent) {
        NovaType.Face face = NovaType.Face.CAPTION;
        int w = NovaType.width(font, face, text) + 10;
        int x0 = Math.round(cx) - w / 2;
        int y0 = Math.round(y);
        NovaShapes.rect(g, x0, y0, x0 + w, y0 + 12, 6, NovaGui.alpha(NovaGui.HUD_BACKGROUND, a));
        if (accent) {
            NovaShapes.outline(g, x0, y0, x0 + w, y0 + 12, 6, NovaGui.alpha(NovaGui.BRAND, 0.6F * a));
        }
        NovaType.draw(g, font, face, text, x0 + 5, NovaType.centerY(face, y0, 12), NovaGui.alpha(NovaGui.TEXT, a));
    }
}
