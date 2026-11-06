package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.KillEffectsMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaFx;
import nova.nova_client.client.SquashEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kill Effects: stirbt etwas, das du in den letzten Sekunden getroffen hast, gibt es an der Stelle einen Effekt -
 * Seelen-Explosion, Grabstein oder Squash (Amboss drueckt den Skin platt). Alles nur auf deinem Bildschirm.
 */
public class KillEffectsMod extends GameTickMod {
    public enum Effect {
        SOUL("Soul Burst"), GRAVESTONE("Gravestone"), SQUASH("Squash");

        public final String label;

        Effect(String label) {
            this.label = label;
        }
    }

    // Einstellungen
    public static Effect effect = Effect.SOUL;
    /** Nur bei Spielern, nicht bei Mobs. */
    public static boolean playersOnly = false;
    public static boolean sound = true;

    private static volatile boolean enabled;

    // ---------------------------------------------------------------- eigene Effekte (gezeichnet von NovaFx)

    /** Leuchtender Funke mit Schwerkraft. */
    private static final class Piece {
        Vec3 pos;
        Vec3 vel;
        final int color;
        final float size;
        final long born;
        final long life;

        Piece(Vec3 pos, Vec3 vel, int color, float size, long life) {
            this.pos = pos;
            this.vel = vel;
            this.color = color;
            this.size = size;
            this.life = life;
            this.born = System.currentTimeMillis();
        }
    }

    private record Grave(Vec3 pos, Vec3 facing, long born) {
    }

    private record Shockwave(Vec3 pos, long born, int color) {
    }

    private static final List<Piece> PIECES = new ArrayList<>();
    private static final List<Grave> GRAVES = new ArrayList<>();
    private static final List<Shockwave> WAVES = new ArrayList<>();
    private static long lastFrame;

    private static final long GRAVE_LIFE = 4500L;
    private static final long WAVE_LIFE = 700L;

    public KillEffectsMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        KillEffectsMod.enabled = enabled;
        PIECES.clear();
        GRAVES.clear();
        WAVES.clear();
        SquashEffect.clear();
    }

    public static boolean hasEffects() {
        return !PIECES.isEmpty() || !GRAVES.isEmpty() || !WAVES.isEmpty() || !SquashEffect.isEmpty();
    }

    /** Aus CombatEvents: ein Ziel, das du getroffen hast, ist gestorben. */
    public static void onKill(LivingEntity target) {
        if (!enabled || playersOnly && !(target instanceof Player)) {
            return;
        }
        play(target.position(), target.getBbHeight(), effect, target);
    }

    /** Effekt abspielen (auch fuer die Vorschau im Menue); target liefert z.B. den Skin fuer Squash. */
    public static void play(Vec3 pos, double height, Effect which, LivingEntity target) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) {
            return;
        }
        switch (which) {
            case SOUL -> soul(level, pos, height);
            case GRAVESTONE -> gravestone(level, pos);
            case SQUASH -> SquashEffect.start(pos, target);
        }
    }

    private static void sound(ClientLevel level, Vec3 pos, SoundEvent event, float volume, float pitch) {
        if (sound) {
            level.playLocalSound(pos.x, pos.y, pos.z, event, SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    // ---------------------------------------------------------------- Seelen-Explosion

    private static void soul(ClientLevel level, Vec3 pos, double height) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vec3 center = pos.add(0.0D, height * 0.5D, 0.0D);
        // Spirale aus Seelenfeuer, die nach oben steigt
        for (int i = 0; i < 48; i++) {
            double angle = i * 0.55D;
            double radius = 0.25D + i * 0.012D;
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, center.x + Math.cos(angle) * radius, pos.y + i * 0.045D,
                    center.z + Math.sin(angle) * radius, Math.cos(angle) * 0.03D, 0.06D + r.nextDouble() * 0.04D,
                    Math.sin(angle) * 0.03D);
        }
        for (int i = 0; i < 14; i++) {
            level.addParticle(ParticleTypes.SOUL, center.x + (r.nextDouble() - 0.5D) * 0.8D, center.y + r.nextDouble() * 0.6D,
                    center.z + (r.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.05D + r.nextDouble() * 0.05D, 0.0D);
        }
        // Mint-Funken, die nach aussen fliegen und aufsteigen
        for (int i = 0; i < 30; i++) {
            double angle = r.nextDouble() * Math.PI * 2.0D;
            double speed = 1.2D + r.nextDouble() * 1.8D;
            PIECES.add(new Piece(center, new Vec3(Math.cos(angle) * speed, 1.0D + r.nextDouble() * 2.5D, Math.sin(angle) * speed),
                    r.nextInt(3) == 0 ? NovaFx.LAVENDER : NovaFx.MINT, 0.045F, 900L + r.nextInt(600)));
        }
        WAVES.add(new Shockwave(pos, System.currentTimeMillis(), NovaFx.MINT));
        sound(level, pos, SoundEvents.SOUL_ESCAPE.value(), 2.0F, 0.8F);
        sound(level, pos, SoundEvents.SCULK_SHRIEKER_SHRIEK, 0.35F, 1.6F);
    }

    // ---------------------------------------------------------------- Grabstein

    private static void gravestone(ClientLevel level, Vec3 pos) {
        Minecraft client = Minecraft.getInstance();
        // Vorderseite zeigt zu dir
        Vec3 toPlayer = client.player != null ? client.player.position().subtract(pos) : new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 facing = new Vec3(toPlayer.x, 0.0D, toPlayer.z);
        facing = facing.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : facing.normalize();
        GRAVES.add(new Grave(pos, facing, System.currentTimeMillis()));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 16; i++) {
            level.addParticle(ParticleTypes.CLOUD, pos.x + (r.nextDouble() - 0.5D) * 0.8D, pos.y + 0.1D,
                    pos.z + (r.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.02D, 0.0D);
        }
        sound(level, pos, SoundEvents.GRAVEL_PLACE, 1.5F, 0.6F);
        sound(level, pos, SoundEvents.BELL_BLOCK, 0.8F, 0.5F);
    }

    // ---------------------------------------------------------------- Zeichnen (jeden Frame, aus NovaFx)

    public static void render(NovaFx.Painter painter) {
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0L ? 0.0F : Math.min(0.05F, (now - lastFrame) / 1000.0F);
        lastFrame = now;

        // Funken: Schwerkraft und Luftwiderstand
        PIECES.removeIf(p -> now - p.born > p.life);
        for (Piece p : PIECES) {
            p.vel = p.vel.scale(Math.pow(0.55D, dt * 6.0D)).add(0.0D, -4.0D * dt, 0.0D);
            p.pos = p.pos.add(p.vel.scale(dt));
            float t = (now - p.born) / (float) p.life;
            float alpha = t > 0.7F ? 1.0F - (t - 0.7F) / 0.3F : 1.0F;
            painter.spark(p.pos, p.size * (1.0F - t * 0.5F), p.color, alpha);
        }

        // Schockwelle am Boden
        WAVES.removeIf(w -> now - w.born() > WAVE_LIFE);
        for (Shockwave w : WAVES) {
            float t = (now - w.born()) / (float) WAVE_LIFE;
            float ease = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
            ring(painter, w.pos().add(0.0D, 0.04D, 0.0D), 0.2D + 2.6D * ease, 0.25D * (1.0F - t) + 0.03D,
                    w.color(), (1.0F - t) * 0.9F);
        }

        GRAVES.removeIf(g -> now - g.born() > GRAVE_LIFE);
        for (Grave g : GRAVES) {
            drawGrave(painter, g, now - g.born());
        }
        SquashEffect.render(painter);
    }

    /** Leuchtender Ring am Boden (innen und aussen weich auslaufend). */
    static void ring(NovaFx.Painter painter, Vec3 center, double radius, double width, int rgb, float alpha) {
        int segments = 48;
        double inner = Math.max(0.0D, radius - width);
        double mid = radius - width * 0.5D;
        int clear = NovaFx.argb(rgb, 0.0F);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0D * i / segments;
            double a1 = Math.PI * 2.0D * (i + 1) / segments;
            int c0 = NovaFx.argb(NovaFx.mix(rgb, NovaFx.nova(i / (float) segments), 0.35F), alpha);
            int c1 = NovaFx.argb(NovaFx.mix(rgb, NovaFx.nova((i + 1) / (float) segments), 0.35F), alpha);
            Vec3 i0 = at(center, a0, inner);
            Vec3 i1 = at(center, a1, inner);
            Vec3 m0 = at(center, a0, mid);
            Vec3 m1 = at(center, a1, mid);
            Vec3 o0 = at(center, a0, radius);
            Vec3 o1 = at(center, a1, radius);
            painter.quad(i0, i1, m1, m0, clear, clear, c1, c0);
            painter.quad(m0, m1, o1, o0, c0, c1, clear, clear);
        }
    }

    private static Vec3 at(Vec3 center, double angle, double radius) {
        return center.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
    }

    /** Grabstein: steigt aus dem Boden, bleibt stehen, sinkt wieder ein. Violettes Kreuz vorne, Leuchten am Fuss. */
    private static void drawGrave(NovaFx.Painter painter, Grave g, long age) {
        float rise = Math.min(1.0F, age / 400.0F);
        rise = 1.0F - (1.0F - rise) * (1.0F - rise);
        float sink = age > GRAVE_LIFE - 700L ? (age - (GRAVE_LIFE - 700L)) / 700.0F : 0.0F;
        double height = 0.95D * rise * (1.0D - sink);
        if (height <= 0.01D) {
            return;
        }
        float alpha = 1.0F - sink * 0.6F;
        Vec3 forward = g.facing();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 up = new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 base = g.pos().add(0.0D, height - 0.95D, 0.0D);

        int stone = 0x8C93A3;
        // Sockel, Stein, Abrundung oben
        box(painter, base.add(0.0D, 0.06D, 0.0D), right.scale(0.36D), up.scale(0.06D), forward.scale(0.2D), 0x6B7180, alpha);
        box(painter, base.add(0.0D, 0.47D, 0.0D), right.scale(0.25D), up.scale(0.35D), forward.scale(0.08D), stone, alpha);
        box(painter, base.add(0.0D, 0.86D, 0.0D), right.scale(0.18D), up.scale(0.04D), forward.scale(0.08D), stone, alpha);
        box(painter, base.add(0.0D, 0.92D, 0.0D), right.scale(0.1D), up.scale(0.02D), forward.scale(0.08D), stone, alpha);
        // Leuchtendes Kreuz auf der Vorderseite
        Vec3 front = base.add(forward.scale(0.085D));
        float pulse = 0.75F + 0.25F * (float) Math.sin(age / 180.0D);
        box(painter, front.add(0.0D, 0.55D, 0.0D), right.scale(0.025D), up.scale(0.17D), forward.scale(0.01D),
                NovaFx.LAVENDER, alpha * pulse);
        box(painter, front.add(0.0D, 0.6D, 0.0D), right.scale(0.1D), up.scale(0.025D), forward.scale(0.01D),
                NovaFx.LAVENDER, alpha * pulse);
        // Schein um das Kreuz und am Boden
        painter.spark(front.add(forward.scale(0.02D)).add(0.0D, 0.58D, 0.0D), 0.09D, NovaFx.VIOLET, alpha * pulse * 0.6F);
        ring(painter, g.pos().add(0.0D, 0.03D, 0.0D), 0.55D, 0.3D, NovaFx.VIOLET, alpha * 0.5F * rise);
    }

    /** Gedrehter Quader: Mitte und drei halbe Kantenvektoren. */
    private static void box(NovaFx.Painter painter, Vec3 c, Vec3 x, Vec3 y, Vec3 z, int rgb, float alpha) {
        Vec3[] p = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = c.add(x.scale((i & 1) == 0 ? -1 : 1)).add(y.scale((i & 2) == 0 ? -1 : 1)).add(z.scale((i & 4) == 0 ? -1 : 1));
        }
        int top = NovaFx.argb(rgb, alpha);
        int side = NovaFx.argb(NovaFx.scale(rgb, 0.78F), alpha);
        int face = NovaFx.argb(NovaFx.scale(rgb, 0.9F), alpha);
        int bottom = NovaFx.argb(NovaFx.scale(rgb, 0.5F), alpha);
        painter.quad(p[2], p[3], p[7], p[6], top, top, top, top);       // oben
        painter.quad(p[0], p[1], p[5], p[4], bottom, bottom, bottom, bottom);
        painter.quad(p[4], p[5], p[7], p[6], face, face, face, face);   // vorne (+z = forward)
        painter.quad(p[0], p[1], p[3], p[2], side, side, side, side);   // hinten
        painter.quad(p[0], p[4], p[6], p[2], side, side, side, side);   // links
        painter.quad(p[1], p[5], p[7], p[3], side, side, side, side);   // rechts
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new KillEffectsMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "effect", effect.name());
        properties.setProperty(prefix + "playersOnly", String.valueOf(playersOnly));
        properties.setProperty(prefix + "sound", String.valueOf(sound));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        String saved = properties.getProperty(prefix + "effect");
        if (saved != null) {
            try {
                effect = Effect.valueOf(saved.trim());
            } catch (IllegalArgumentException ignored) {
                // Standard behalten
            }
        }
        playersOnly = Boolean.parseBoolean(properties.getProperty(prefix + "playersOnly", String.valueOf(playersOnly)));
        sound = Boolean.parseBoolean(properties.getProperty(prefix + "sound", String.valueOf(sound)));
    }
}
