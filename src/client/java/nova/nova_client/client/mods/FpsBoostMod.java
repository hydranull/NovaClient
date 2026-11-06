package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.util.Util;
import nova.nova_client.client.ModCategory;

import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * FPS Boost: senkt Sichtweite, Partikel, Wolken, Schatten und alles, was auf schwachen PCs viel kostet (Biome Blend,
 * Ambient Occlusion, transparente Blaetter, ...), und vereinfacht die Nova-Menues. Beim Ausschalten werden die
 * vorherigen Einstellungen wiederhergestellt. Der Zustand bleibt ueber Neustarts erhalten.
 * <p>
 * Unabhaengig davon schalten die Nova-Menues von selbst auf die einfachen Hintergruende, wenn das Spiel dort dauerhaft
 * langsam laeuft ({@link #liteMenus()}).
 */
public class FpsBoostMod extends GameTickMod {
    private static final int BOOST_RENDER_DISTANCE = 6;

    /**
     * Eine Minecraft-Einstellung, die der Boost veraendert. Der alte Wert wird als Text gesichert (auch in der Config),
     * damit er beim Ausschalten - auch nach einem Neustart - zurueckkommt.
     */
    private static final class Tweak<T> {
        final String key;
        final Function<Options, OptionInstance<T>> option;
        final UnaryOperator<T> boost;
        final Function<String, T> parse;
        /** Gesicherter Originalwert, null = nichts gesichert. */
        String saved;

        Tweak(String key, Function<Options, OptionInstance<T>> option, UnaryOperator<T> boost, Function<String, T> parse) {
            this.key = key;
            this.option = option;
            this.boost = boost;
            this.parse = parse;
        }

        void apply(Options options) {
            OptionInstance<T> instance = option.apply(options);
            // Nur sichern, wenn nicht schon gesichert (sonst wuerden Boost-Werte als "Original" gespeichert)
            if (saved == null) {
                T value = instance.get();
                saved = value instanceof Enum<?> e ? e.name() : String.valueOf(value);
            }
            instance.set(boost.apply(instance.get()));
        }

        void restore(Options options) {
            if (saved == null) {
                return;
            }
            try {
                option.apply(options).set(parse.apply(saved));
            } catch (RuntimeException ignored) {
                // unbekannter Wert -> aktuelle Einstellung behalten
            }
            saved = null;
        }
    }

    /** Alle Einstellungen des Boosts. Die ersten vier Schluessel gab es schon frueher (alte Configs bleiben gueltig). */
    private static final List<Tweak<?>> TWEAKS = List.of(
            new Tweak<>("renderDistance", Options::renderDistance, v -> Math.min(v, BOOST_RENDER_DISTANCE), Integer::valueOf),
            new Tweak<>("particles", Options::particles, v -> ParticleStatus.MINIMAL, ParticleStatus::valueOf),
            new Tweak<>("clouds", Options::cloudStatus, v -> CloudStatus.OFF, CloudStatus::valueOf),
            new Tweak<>("shadows", Options::entityShadows, v -> false, Boolean::valueOf),
            // Chunk-Aufbau: kein Farbverlauf zwischen Biomen, keine weichen Schatten - spart viel CPU beim Laden
            new Tweak<>("biomeBlend", Options::biomeBlendRadius, v -> 0, Integer::valueOf),
            new Tweak<>("ambientOcclusion", Options::ambientOcclusion, v -> false, Boolean::valueOf),
            // Undurchsichtige Blaetter (wie "Fast"): in Waeldern der groesste Unterschied fuer schwache Grafik
            new Tweak<>("cutoutLeaves", Options::cutoutLeaves, v -> false, Boolean::valueOf),
            new Tweak<>("improvedTransparency", Options::improvedTransparency, v -> false, Boolean::valueOf),
            new Tweak<>("chunkFade", Options::chunkSectionFadeInTime, v -> 0.0D, Double::valueOf),
            new Tweak<>("weatherRadius", Options::weatherRadius, v -> Math.min(v, 5), Integer::valueOf),
            new Tweak<>("simulationDistance", Options::simulationDistance, v -> Math.min(v, 6), Integer::valueOf),
            new Tweak<>("entityDistance", Options::entityDistanceScaling, v -> Math.min(v, 0.75D), Double::valueOf),
            // RGSS tastet jede Block-Textur vierfach ab - auf integrierter Grafik spuerbar teuer (anisotrop bleibt)
            new Tweak<>("textureFiltering", Options::textureFiltering,
                    v -> v == TextureFilteringMethod.RGSS ? TextureFilteringMethod.NONE : v, TextureFilteringMethod::valueOf));

    // volatile Flag statt Suche in der Mod-Liste: wird jeden Frame vom Menue-Hintergrund abgefragt
    private static volatile boolean enabled;
    /** Beim Laden aus der Config aktiviert, Einstellungen aber noch nicht angewendet. */
    private static boolean pendingStartup;
    private static FpsBoostMod instance;

    // Automatisch einfache Menues, wenn das Spiel dort dauerhaft langsam ist (gilt bis zum Neustart)
    private static final int SLOW_FPS = 25;
    private static final long SLOW_FOR_MS = 5000L;
    /** Erst so lange nach dem Start messen (Laden, Shader, JIT - da ruckelt es auch auf schnellen PCs). */
    private static final long WARMUP_MS = 20_000L;
    private static final long STARTED_AT = Util.getMillis();
    private static boolean autoLite;
    private static long slowSince;

    public FpsBoostMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Einfache Menue-Hintergruende (keine Sterne, Nebel, Animationen)? Bei FPS Boost immer; sonst automatisch, sobald die
     * Menues mehrere Sekunden unter {@value #SLOW_FPS} FPS laufen, ohne dass Minecraft selbst drosselt (AFK, minimiert).
     */
    public static boolean liteMenus() {
        if (enabled || autoLite) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        long now = Util.getMillis();
        FramerateLimitTracker limiter = client.getFramerateLimitTracker();
        FramerateLimitTracker.FramerateThrottleReason reason = limiter.getThrottleReason();
        boolean measurable = now - STARTED_AT > WARMUP_MS && client.gui.overlay() == null && client.isWindowActive()
                && limiter.getFramerateLimit() >= 50
                && (reason == FramerateLimitTracker.FramerateThrottleReason.NONE
                || reason == FramerateLimitTracker.FramerateThrottleReason.OUT_OF_LEVEL_MENU);
        int fps = client.getFps();
        if (!measurable || fps <= 0 || fps >= SLOW_FPS) {
            slowSince = 0L;
            return false;
        }
        if (slowSince == 0L) {
            slowSince = now;
        } else if (now - slowSince > SLOW_FOR_MS) {
            autoLite = true;
        }
        return autoLite;
    }

    @Override
    public void execute(Minecraft client) {
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        FpsBoostMod.enabled = enabled;
        if (client.options == null) {
            pendingStartup = enabled;
            return;
        }
        if (enabled) {
            apply(client.options);
        } else {
            restore(client.options);
        }
        nova.nova_client.client.ModConfig.save();
    }

    /** Wird jeden Tick aufgerufen: wendet einen aus der Config geladenen Zustand einmalig an. */
    public static void checkStartup(Minecraft client) {
        if (!pendingStartup || client.options == null) {
            return;
        }
        pendingStartup = false;
        if (instance != null && instance.active) {
            apply(client.options);
        }
    }

    private static void apply(Options options) {
        for (Tweak<?> tweak : TWEAKS) {
            tweak.apply(options);
        }
        options.save();
    }

    private static void restore(Options options) {
        for (Tweak<?> tweak : TWEAKS) {
            tweak.restore(options);
        }
        options.save();
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "enabled", String.valueOf(active));
        for (Tweak<?> tweak : TWEAKS) {
            if (tweak.saved != null) {
                properties.setProperty(prefix + "saved." + tweak.key, tweak.saved);
            }
        }
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        for (Tweak<?> tweak : TWEAKS) {
            tweak.saved = properties.getProperty(prefix + "saved." + tweak.key);
        }
        // Alte Configs: "-1" hiess "nichts gesichert"
        Tweak<?> distance = TWEAKS.get(0);
        if ("-1".equals(distance.saved)) {
            for (Tweak<?> tweak : TWEAKS) {
                tweak.saved = null;
            }
        }

        active = Boolean.parseBoolean(properties.getProperty(prefix + "enabled", "false"));
        enabled = active;
        pendingStartup = active;
    }
}
