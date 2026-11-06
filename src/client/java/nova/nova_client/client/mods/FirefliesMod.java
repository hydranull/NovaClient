package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaFx;
import nova.nova_client.client.NovaFxMenu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fireflies: Gluehwuermchen kreisen rundherum (360 Grad) um dich - vorne ein kleiner, kraeftig leuchtender Kreis,
 * dahinter ein Schweif wie bei einer Sternschnuppe. Jedes hat seine eigene Bahn (Abstand, Hoehe, Richtung), folgt
 * ihr in weichen Kurven und pulsiert leicht. Farbe und Anzahl (bis 50) sind einstellbar.
 */
public class FirefliesMod extends GameTickMod {
    public static final int MAX_AMOUNT = 50;
    /** Goldener Winkel: verteilt beliebig viele Gluehwuermchen gleichmaessig rundherum. */
    private static final double GOLDEN_ANGLE = 2.399963D;
    /** Laenge des Schweifs (ms). */
    private static final long TAIL_MS = 420L;

    // Einstellungen
    public static NovaFx.ColorMode colorMode = NovaFx.ColorMode.NOVA;
    public static float hue = 0.45F;
    public static int amount = 7;

    private record TailPoint(Vec3 pos, long time) {
    }

    /**
     * Ein Gluehwuermchen mit eigener Umlaufbahn um dich: Startwinkel, Abstand, Hoehe und Tempo (manche linksrum,
     * manche rechtsrum). Es folgt seinem Bahnpunkt weich, dadurch entstehen geschwungene Schweife.
     */
    private static final class Firefly {
        Vec3 pos;
        Vec3 vel = Vec3.ZERO;
        final float seed;
        final double phase;
        final double radius;
        final double height;
        final double speed;
        final Deque<TailPoint> tail = new ArrayDeque<>();

        Firefly(int index) {
            this.seed = index * 17.31F + ThreadLocalRandom.current().nextFloat() * 10.0F;
            this.phase = index * GOLDEN_ANGLE;
            this.radius = 1.8D + 3.2D * frac(index * 0.618034D + 0.13D);
            this.height = -0.8D + 2.6D * frac(index * 0.381966D + 0.37D);
            this.speed = (0.22D + 0.3D * frac(index * 0.754878D)) * (index % 2 == 0 ? 1.0D : -1.0D);
        }

        /** Punkt auf der Bahn zur Zeit time (rund um home). */
        Vec3 target(Vec3 home, float time) {
            double angle = phase + speed * time + 0.35D * Math.sin(time * 0.6D + seed);
            double r = radius + 0.5D * Math.sin(time * 0.45D + seed * 1.7D);
            double h = height + 0.45D * Math.sin(time * 0.8D + seed * 2.3D);
            return home.add(Math.cos(angle) * r, h, Math.sin(angle) * r);
        }
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    private static volatile boolean enabled;
    private static final List<Firefly> FLIES = new ArrayList<>();
    private static long lastFrame;

    public FirefliesMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        FirefliesMod.enabled = enabled;
        FLIES.clear();
        lastFrame = 0L;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Jeden Frame aus NovaFx: fliegen lassen und zeichnen. */
    public static void render(NovaFx.Painter painter, Minecraft client, float partialTick) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.05F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float time = (now % 1_000_000L) / 1000.0F;
        Vec3 home = player.getPosition(partialTick).add(0.0D, 1.3D, 0.0D);

        // Anzahl anpassen (jedes neue bekommt die naechste Bahn, so bleiben alle gleichmaessig rundherum verteilt)
        int wanted = Math.max(1, Math.min(MAX_AMOUNT, amount));
        while (FLIES.size() < wanted) {
            Firefly f = new Firefly(FLIES.size());
            f.pos = f.target(home, time);
            FLIES.add(f);
        }
        while (FLIES.size() > wanted) {
            FLIES.remove(FLIES.size() - 1);
        }

        for (Firefly f : FLIES) {
            Vec3 target = f.target(home, time);
            if (f.pos.distanceToSqr(target) > 16.0D * 16.0D) {
                // Teleport: direkt auf die Bahn setzen
                f.pos = target;
                f.vel = Vec3.ZERO;
                f.tail.clear();
            }
            move(f, target, dt);
            recordTail(f, now);
            draw(painter, f, now, time);
        }
    }

    /** Weich dem Bahnpunkt folgen (gedaempfte Feder), dadurch fliessende Kurven statt starrer Kreise. */
    private static void move(Firefly f, Vec3 target, float dt) {
        Vec3 pull = target.subtract(f.pos).scale(12.0D).subtract(f.vel.scale(6.0D));
        f.vel = f.vel.add(pull.scale(dt));
        f.pos = f.pos.add(f.vel.scale(dt));
    }

    /** Schweif-Punkte merken (jeden Frame), alte wegwerfen. */
    private static void recordTail(Firefly f, long now) {
        TailPoint last = f.tail.peekLast();
        if (last == null || last.pos().distanceToSqr(f.pos) > 0.0004D) {
            f.tail.addLast(new TailPoint(f.pos, now));
        }
        while (!f.tail.isEmpty() && now - f.tail.peekFirst().time() > TAIL_MS) {
            f.tail.pollFirst();
        }
    }

    private static void draw(NovaFx.Painter painter, Firefly f, long now, float time) {
        int color = NovaFx.color(colorMode, hue, f.seed * 0.137F + time * 0.05F);
        float pulse = 0.8F + 0.2F * (float) Math.sin(time * 4.0F + f.seed * 2.0F);

        // Schweif wie eine Sternschnuppe: vorne breit und hell, hinten spitz und durchsichtig
        TailPoint[] tail = f.tail.toArray(new TailPoint[0]);
        int n = tail.length;
        if (n >= 2) {
            Vec3[] side = new Vec3[n];
            float[] life = new float[n];
            for (int i = 0; i < n; i++) {
                Vec3 prev = tail[Math.max(0, i - 1)].pos();
                Vec3 next = tail[Math.min(n - 1, i + 1)].pos();
                Vec3 s = next.subtract(prev).cross(painter.view.camera().subtract(tail[i].pos()));
                side[i] = s.lengthSqr() < 1.0E-10D ? new Vec3(0.0D, 1.0D, 0.0D) : s.normalize();
                life[i] = 1.0F - Math.min(1.0F, (now - tail[i].time()) / (float) TAIL_MS);
            }
            for (int i = 0; i < n - 1; i++) {
                ribbon(painter, tail, side, life, i, 0.05D, color, 0.35F * pulse);
                ribbon(painter, tail, side, life, i, 0.018D, NovaFx.mix(color, 0xFFFFFF, 0.5F), 0.85F * pulse);
            }
        }

        // Kopf: weicher Schein, Leuchtring, kraeftiger heller Kern
        painter.disc(f.pos, 0.22D, NovaFx.argb(color, 0.22F * pulse), NovaFx.argb(color, 0.0F));
        painter.disc(f.pos, 0.085D, NovaFx.argb(color, 0.75F * pulse), NovaFx.argb(color, 0.0F));
        painter.disc(f.pos, 0.042D, NovaFx.argb(NovaFx.mix(color, 0xFFFFFF, 0.55F), pulse),
                NovaFx.argb(NovaFx.mix(color, 0xFFFFFF, 0.2F), 0.9F * pulse));
    }

    private static void ribbon(NovaFx.Painter painter, TailPoint[] tail, Vec3[] side, float[] life, int i, double width,
                               int color, float alpha) {
        Vec3 a = tail[i].pos();
        Vec3 b = tail[i + 1].pos();
        double wa = width * life[i];
        double wb = width * life[i + 1];
        int ca = NovaFx.argb(color, alpha * life[i] * life[i]);
        int cb = NovaFx.argb(color, alpha * life[i + 1] * life[i + 1]);
        painter.quad(a.subtract(side[i].scale(wa)), b.subtract(side[i + 1].scale(wb)), b.add(side[i + 1].scale(wb)),
                a.add(side[i].scale(wa)), ca, cb, cb, ca);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new NovaFxMenu(parent, "Fireflies", "",
                () -> colorMode, mode -> colorMode = mode, () -> hue, value -> hue = value,
                new NovaFxMenu.Slider("Amount", 2.0F, MAX_AMOUNT, 1.0F, () -> (float) amount,
                        value -> amount = Math.round(value), value -> String.valueOf(Math.round(value))));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "color", colorMode.name());
        properties.setProperty(prefix + "hue", String.format(Locale.ROOT, "%.3f", hue));
        properties.setProperty(prefix + "amount", String.valueOf(amount));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        colorMode = NovaFx.ColorMode.byName(properties.getProperty(prefix + "color", colorMode.name()), colorMode);
        hue = nova.nova_client.client.NovaFx.number(properties, prefix + "hue", hue, 0.0F, 1.0F);
        amount = Math.round(nova.nova_client.client.NovaFx.number(properties, prefix + "amount", amount, 2.0F, MAX_AMOUNT));
    }
}
