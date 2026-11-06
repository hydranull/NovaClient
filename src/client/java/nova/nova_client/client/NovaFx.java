package nova.nova_client.client;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.mods.FirefliesMod;
import nova.nova_client.client.mods.JumpCirclesMod;
import nova.nova_client.client.mods.KillEffectsMod;
import nova.nova_client.mixin.client.RenderPipelinesAccessor;
import org.joml.Vector3fc;

/**
 * Gemeinsame Welt-Effekte (Jump Circles, Kill Effects, Fireflies): leuchtende, durchsichtige Flaechen in der Welt.
 * Alles wird pro Frame ueber LevelRenderEvents.COLLECT_SUBMITS mit einem eigenen Render-Typ gezeichnet - wie die
 * Debug-Flaechen, mit Tiefentest (hinter Bloecken verdeckt), aber ohne Tiefe zu schreiben (Effekte verdecken sich
 * nicht gegenseitig). Positionen sind relativ zur Kamera.
 */
public final class NovaFx {
    public static final int MINT = 0x5EF2C2;
    public static final int VIOLET = 0xB388FF;
    public static final int LAVENDER = 0xE2D2FF;

    private static RenderType glow;

    private NovaFx() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(NovaFx::submit);
    }

    private static RenderType glow() {
        if (glow == null) {
            RenderPipeline pipeline = RenderCompat.glowDepth(RenderCompat.blend(RenderPipeline.builder(RenderPipelinesAccessor.nova_client$debugFilledSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath("nova_client", "pipeline/nova_fx")),
                    com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT), true)
                    // beidseitig: 1.21.11 hat im Snippet noch Culling an (Ringe/Grabstein waeren je nach Blickwinkel weg)
                    .withCull(false)
                    .build();
            glow = nova.nova_client.mixin.client.RenderTypeInvoker.nova_client$create("nova_client_fx",
                    RenderSetup.builder(pipeline).sortOnUpload().createRenderSetup());
        }
        return glow;
    }

    private static RenderType seeThrough;

    /** Wie glow(), aber ohne Tiefentest: durch Bloecke hindurch sichtbar (fuer Markierungen unter der Erde). */
    private static RenderType seeThrough() {
        if (seeThrough == null) {
            RenderPipeline pipeline = RenderCompat.glowDepth(RenderCompat.blend(RenderPipeline.builder(RenderPipelinesAccessor.nova_client$debugFilledSnippet())
                    .withLocation(Identifier.fromNamespaceAndPath("nova_client", "pipeline/nova_fx_see_through")),
                    com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT), false)
                    .withCull(false)
                    .build();
            seeThrough = nova.nova_client.mixin.client.RenderTypeInvoker.nova_client$create("nova_client_fx_see_through",
                    RenderSetup.builder(pipeline).sortOnUpload().createRenderSetup());
        }
        return seeThrough;
    }

    /** Leuchtende Flaechen zeichnen, die durch Bloecke hindurch sichtbar sind (aus einem COLLECT_SUBMITS-Listener). */
    public static void drawSeeThrough(LevelRenderContext context, java.util.function.Consumer<Painter> draw) {
        Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
        View view = new View(camera.position(), vec(camera.leftVector()), vec(camera.upVector()));
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), seeThrough(),
                (pose, buffer) -> draw.accept(new Painter(buffer, pose, view)));
    }

    private static void submit(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        boolean circles = JumpCirclesMod.hasRings();
        boolean kills = KillEffectsMod.hasEffects();
        boolean fireflies = FirefliesMod.isEnabled();
        if (!circles && !kills && !fireflies) {
            return;
        }
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 cameraPos = camera.position();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (kills && !SquashEffect.isEmpty()) {
            // Squash: echtes Spielermodell und Amboss-Block
            SquashEffect.submit(context, cameraPos);
        }
        View view = new View(cameraPos, vec(camera.leftVector()), vec(camera.upVector()));
        context.submitNodeCollector().submitCustomGeometry(context.poseStack(), glow(), (pose, buffer) -> {
            Painter painter = new Painter(buffer, pose, view);
            if (circles) {
                JumpCirclesMod.render(painter);
            }
            if (kills) {
                KillEffectsMod.render(painter);
            }
            if (fireflies) {
                FirefliesMod.render(painter, client, partialTick);
            }
        });
    }

    private static Vec3 vec(Vector3fc v) {
        return new Vec3(v.x(), v.y(), v.z());
    }

    /** Kamera fuer diesen Frame: Position und Bildschirm-Achsen (fuer Flaechen, die zur Kamera zeigen). */
    public record View(Vec3 camera, Vec3 left, Vec3 up) {
    }

    /** Zeichnet Flaechen in Welt-Koordinaten (wird intern kamera-relativ). */
    public static final class Painter {
        private final VertexConsumer buffer;
        private final PoseStack.Pose pose;
        public final View view;

        Painter(VertexConsumer buffer, PoseStack.Pose pose, View view) {
            this.buffer = buffer;
            this.pose = pose;
            this.view = view;
        }

        public void vertex(Vec3 world, int argb) {
            // Kamera-relativ ohne neues Vec3 pro Eckpunkt (gleiche Rechnung wie world.subtract(camera))
            Vec3 camera = view.camera();
            buffer.addVertex(pose, (float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z))
                    .setColor(argb);
        }

        /** Viereck a-b-c-d mit je einer Farbe pro Ecke. */
        public void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int ca, int cb, int cc, int cd) {
            vertex(a, ca);
            vertex(b, cb);
            vertex(c, cc);
            vertex(d, cd);
        }

        /** Quadrat, das zur Kamera zeigt (Funken, Konfetti), gedreht um angle. */
        public void billboard(Vec3 center, double size, float angle, int argb) {
            double cos = Math.cos(angle) * size;
            double sin = Math.sin(angle) * size;
            Vec3 a = view.left().scale(cos).add(view.up().scale(sin));
            Vec3 b = view.left().scale(-sin).add(view.up().scale(cos));
            quad(center.subtract(a).subtract(b), center.add(a).subtract(b), center.add(a).add(b),
                    center.subtract(a).add(b), argb, argb, argb, argb);
        }

        /** Runde Scheibe, die zur Kamera zeigt: Farbe innen -> aussen (fuer weichen Schein aussen durchsichtig). */
        public void disc(Vec3 center, double radius, int inner, int outer) {
            int segments = 16;
            Vec3 previous = center.add(view.left().scale(radius));
            for (int i = 1; i <= segments; i++) {
                double angle = Math.PI * 2.0D * i / segments;
                Vec3 next = center.add(view.left().scale(Math.cos(angle) * radius)).add(view.up().scale(Math.sin(angle) * radius));
                quad(center, previous, next, center, inner, outer, outer, inner);
                previous = next;
            }
        }

        /** Leuchtender Punkt: heller Kern und weicher Schein. */
        public void spark(Vec3 center, double size, int rgb, float alpha) {
            billboard(center, size * 2.6D, 0.0F, argb(rgb, alpha * 0.18F));
            billboard(center, size, 0.785F, argb(mix(rgb, 0xFFFFFF, 0.5F), alpha));
        }

        /** Achsenparalleler Quader (fuer den Grabstein), Flaechen leicht schattiert. */
        public void box(Vec3 min, Vec3 max, int rgb, float alpha) {
            double x0 = min.x, y0 = min.y, z0 = min.z, x1 = max.x, y1 = max.y, z1 = max.z;
            int top = argb(rgb, alpha);
            int side = argb(scale(rgb, 0.8F), alpha);
            int front = argb(scale(rgb, 0.65F), alpha);
            quad(new Vec3(x0, y1, z0), new Vec3(x1, y1, z0), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1), top, top, top, top);
            quad(new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0), front, front, front, front);
            quad(new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1), front, front, front, front);
            quad(new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), new Vec3(x0, y1, z0), side, side, side, side);
            quad(new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x1, y1, z0), side, side, side, side);
        }
    }

    // ---------------------------------------------------------------- Farben

    public static int argb(int rgb, float alpha) {
        return (Mth.clamp(Math.round(alpha * 255.0F), 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        int r = Math.round(Mth.lerp(t, (a >> 16) & 0xFF, (b >> 16) & 0xFF));
        int g = Math.round(Mth.lerp(t, (a >> 8) & 0xFF, (b >> 8) & 0xFF));
        int bl = Math.round(Mth.lerp(t, a & 0xFF, b & 0xFF));
        return (r << 16) | (g << 8) | bl;
    }

    public static int scale(int rgb, float f) {
        int r = Mth.clamp(Math.round(((rgb >> 16) & 0xFF) * f), 0, 255);
        int g = Mth.clamp(Math.round(((rgb >> 8) & 0xFF) * f), 0, 255);
        int b = Mth.clamp(Math.round((rgb & 0xFF) * f), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    /** Nova-Verlauf Mint -> Violett -> Mint (t laeuft 0..1 und darf ueberlaufen). */
    public static int nova(float t) {
        float k = t - (float) Math.floor(t);
        return mix(MINT, VIOLET, k < 0.5F ? k * 2.0F : (1.0F - k) * 2.0F);
    }

    /** Farbwahl fuer Effekte mit Einstellungen (Fireflies). */
    public enum ColorMode {
        WHITE("White"), NOVA("Nova"), RAINBOW("Rainbow"), CUSTOM("Custom");

        public final String label;

        ColorMode(String label) {
            this.label = label;
        }

        public static ColorMode byName(String name, ColorMode fallback) {
            for (ColorMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return fallback;
        }
    }

    /**
     * Farbe fuer ein Teilchen. t ist je Teilchen verschieden (z.B. Zufall + Zeit), damit Nova und Rainbow bunt
     * gemischt sind; Custom ist der gewaehlte Farbton mit leichter Helligkeits-Variation.
     */
    public static int color(ColorMode mode, float hue, float t) {
        float k = t - (float) Math.floor(t);
        return switch (mode) {
            // fast reines Weiss mit einem Hauch kuehlem Blau, damit es lebendig wirkt
            case WHITE -> mix(0xFFFFFF, 0xDDEBFF, 0.35F * (0.5F + 0.5F * Mth.sin(k * 6.2831855F)));
            case NOVA -> nova(t);
            case RAINBOW -> Mth.hsvToRgb(k, 0.6F, 1.0F) & 0xFFFFFF;
            case CUSTOM -> {
                int base = Mth.hsvToRgb(hue - (float) Math.floor(hue), 0.65F, 1.0F) & 0xFFFFFF;
                yield mix(base, 0xFFFFFF, 0.18F * (0.5F + 0.5F * Mth.sin(k * 6.2831855F)));
            }
        };
    }

    /** Zahl aus den gespeicherten Einstellungen lesen (begrenzt auf min..max, sonst Standardwert). */
    public static float number(java.util.Properties properties, String key, float fallback, float min, float max) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Float.parseFloat(value.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
