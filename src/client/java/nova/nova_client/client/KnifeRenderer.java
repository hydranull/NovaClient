package nova.nova_client.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.KnivesMod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Knives: zeichnet in der Ego-Sicht statt des Schwerts beide Arme mit je einem Shadow Dagger, der flach auf dem
 * Handruecken liegt (Griff auf dem Handruecken, Klinge zeigt ueber die Faust hinaus nach vorne).
 *
 * <p>Posen: jede Hand hat einen "Hand-Rahmen" H in Kamera-Koordinaten (Ursprung = Mitte der Faust, X rechts,
 * Y = Handruecken, Z = Richtung Schulter). Der Arm (Vanilla-Spielerarm mit Skin) wird so gezeichnet, dass seine Faust
 * auf H liegt; der Dolch sitzt mit einem Versatz in H. Die Werte sind gegen das Referenzvideo eingepasst (Hand-FOV 70).
 *
 * <p>Animationen: Ziehen (Arme kommen hoch, Dolche drehen sich einmal), Stich beim Schlagen (beide Arme stossen
 * Richtung Fadenkreuz), Inspect per Rechtsklick (rechter Dolch dreht sich einmal um den Griff und wird seitlich
 * angeschaut), leichtes Atmen im Stand.
 */
public final class KnifeRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Knives");
    private static final Identifier MESH_FILE = Identifier.fromNamespaceAndPath("nova_client", "knives/shadow_daggers.nkm");
    private static final Identifier TEXTURE_FILE = Identifier.fromNamespaceAndPath("nova_client", "knives/shadow_daggers.png");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("nova_client", "knives/shadow_daggers_linear");

    // ---------------------------------------------------------------- Posen (rechte Haupthand / linke Zweithand)

    /** Grundhaltung: Faeuste vor dem Koerper, Handruecken zur Kamera, Klinge nach vorne-oben. */
    private static final Pose MAIN_IDLE = new Pose(0.600F, -0.513F, -1.074F, 8.7F, 21.2F, 49.3F,
            0.011F, 0.135F, 0.1225F, -86.1F, 9.2F, 5.7F);
    private static final Pose OFF_IDLE = new Pose(-0.220F, -0.550F, -1.120F, -20.0F, 30.0F, -40.0F,
            -0.011F, 0.135F, 0.1225F, -86.1F, -9.2F, -5.7F);
    /** Stich: Arm schiesst Richtung Fadenkreuz. */
    private static final Pose MAIN_ATTACK = new Pose(0.683F, -0.394F, -1.973F, 35.3F, 18.0F, 63.0F,
            0.011F, 0.135F, 0.1225F, -86.1F, 9.2F, 5.7F);
    private static final Pose OFF_ATTACK = new Pose(-0.800F, -0.360F, -1.700F, -30.0F, 18.0F, -63.0F,
            -0.011F, 0.135F, 0.1225F, -86.1F, -9.2F, -5.7F);
    /** Inspect: Arm hoch und eingedreht, Dolch liegt quer (Klinge nach links), Flachseite zur Kamera. */
    private static final Pose INSPECT_HOLD = new Pose(0.713F, -0.398F, -1.308F, 34.8F, 41.6F, 58.1F,
            0.087F, 0.140F, 0.004F, -92.3F, 78.4F, -22.8F);

    // ---------------------------------------------------------------- Zeiten (Sekunden)

    private static final float DRAW_TIME = 0.5F;
    private static final float INSPECT_RAISE = 0.28F;
    private static final float INSPECT_SPIN = 0.40F;
    private static final float INSPECT_HOLD_END = 2.45F;
    private static final float INSPECT_END = 2.85F;
    private static final float BLEND_TIME = 0.15F;

    // ---------------------------------------------------------------- Zustand

    private static Mesh[] meshes;
    private static boolean loadFailed;
    private static double inspectStart = -1.0D;
    private static double drawStart = -1.0D;
    private static double blendStart = -1.0D;
    private static State blendMain;
    private static State blendOff;
    private static State lastMain;
    private static State lastOff;
    private static double lastFrame = -10.0D;
    private static Item lastItem;
    /** Haupthand wurde in diesem Frame als Messer gezeichnet (fuer die Zweithand im selben Frame). */
    private static boolean mainDrawn;

    private KnifeRenderer() {
    }

    public static void reset() {
        inspectStart = -1.0D;
        drawStart = -1.0D;
        blendStart = -1.0D;
        lastMain = null;
        lastOff = null;
        lastFrame = -10.0D;
        lastItem = null;
        mainDrawn = false;
    }

    /** Rechtsklick oder Inspect-Taste: Inspect starten (laeuft eins schon, nicht neu anfangen). */
    public static void startInspect() {
        double now = now();
        if (inspectStart >= 0.0D && inspectTime(now) < INSPECT_END) {
            return;
        }
        if (drawStart >= 0.0D && now - drawStart < DRAW_TIME) {
            return;
        }
        inspectStart = now;
    }

    private static double now() {
        return System.nanoTime() / 1.0E9D;
    }

    /** Sekunden seit Inspect-Beginn, mit dem Tempo aus den Einstellungen. */
    private static float inspectTime(double now) {
        return (float) ((now - inspectStart) * KnivesMod.inspectSpeed);
    }

    // ---------------------------------------------------------------- Einstieg aus dem Mixin

    /**
     * Ersetzt FirstPersonHandsAndItemsRenderer.submitArmWithItem fuer eine Hand. Rueckgabe true = Vanilla nicht zeichnen.
     */
    public static boolean render(AbstractClientPlayer player, InteractionHand hand, float attack, ItemStack stack,
                                 float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        Minecraft client = Minecraft.getInstance();
        if (!KnivesMod.isEnabled() || player != client.player || player.isScoping()) {
            mainDrawn = false;
            return false;
        }
        if (hand == InteractionHand.OFF_HAND) {
            // Zweites Schwert in der Zweithand: der zweite Dolch liegt schon auf dem Arm
            boolean hide = mainDrawn && KnivesMod.bothHands && KnivesMod.isKnife(stack);
            mainDrawn = false;
            return hide;
        }
        mainDrawn = false;
        if (!KnivesMod.isKnife(stack) || !ensureLoaded()) {
            return false;
        }
        double now = now();
        if (now - lastFrame > 0.25D || stack.getItem() != lastItem) {
            // Messer neu in der Hand (oder lange nicht gezeichnet): ziehen
            inspectStart = -1.0D;
            blendStart = -1.0D;
            drawStart = KnivesMod.drawAnimation ? now : -1.0D;
        }
        lastFrame = now;
        lastItem = stack.getItem();

        float time = (float) (now % 3600.0D);
        State main = sway(MAIN_IDLE.resolve(), time, 0.0F);
        State off = sway(OFF_IDLE.resolve(), time, 1.7F);

        if (attack > 0.0F && inspectStart >= 0.0D) {
            // Schlagen bricht den Inspect ab, weich aus der letzten Pose heraus
            inspectStart = -1.0D;
            beginBlend();
        }
        if (inspectStart >= 0.0D) {
            float t = inspectTime(now);
            if (t >= INSPECT_END) {
                inspectStart = -1.0D;
            } else {
                main = inspect(main, t, time);
            }
        }
        if (drawStart >= 0.0D) {
            float t = (float) (now - drawStart);
            if (t >= DRAW_TIME) {
                drawStart = -1.0D;
            } else {
                main = draw(main, t, 1.0F);
                off = draw(off, t, -1.0F);
            }
        }
        if (attack > 0.0F) {
            float stab = Mth.sin(Mth.sqrt(attack) * Mth.PI);
            main = State.mix(main, MAIN_ATTACK.resolve(), stab);
            off = State.mix(off, OFF_ATTACK.resolve(), stab);
        }
        if (blendStart >= 0.0D) {
            float t = (float) ((now - blendStart) / BLEND_TIME);
            if (t >= 1.0F || blendMain == null) {
                blendStart = -1.0D;
            } else {
                float k = smooth(t);
                main = State.mix(blendMain, main, k);
                off = State.mix(blendOff, off, k);
            }
        }
        lastMain = main;
        lastOff = off;

        boolean rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        // Beim Wegwechseln (sichtbares Item != gehaltenes) wie Vanilla nach unten senken; das Absenken nach jedem
        // Schlag (Angriffs-Abklingzeit) bleibt aus, sonst wuerden die Arme nach jedem Stich wegtauchen
        float drop = !ItemStack.isSameItem(stack, player.getMainHandItem()) ? inverseArmHeight * 0.6F : 0.0F;
        boolean slim = player.getSkin().model() == PlayerModelType.SLIM;
        drawHand(player, rightHanded ? main : main.mirror(), rightHanded, slim, drop, poseStack, collector, light);
        if (KnivesMod.bothHands) {
            drawHand(player, rightHanded ? off : off.mirror(), !rightHanded, slim, drop, poseStack, collector, light);
        }
        mainDrawn = true;
        return true;
    }

    private static void beginBlend() {
        if (lastMain != null) {
            blendMain = lastMain;
            blendOff = lastOff;
            blendStart = now();
        }
    }

    // ---------------------------------------------------------------- Animationen

    /** Atmen im Stand: Faust hebt und senkt sich leicht, Arm dreht minimal. */
    private static State sway(State s, float time, float phase) {
        Pose bob = Pose.offset(0.0F, 0.004F * Mth.sin(time * 1.7F + phase), 0.0F, 0.0F, 0.0F, 1.0F * Mth.sin(time * 1.3F + phase));
        return s.add(bob);
    }

    /**
     * Inspect (wie im Video): Hand dreht sich ein (Ring wird sichtbar), Dolch dreht sich einmal um den Griff, dann
     * liegt er quer vor der Kamera, wackelt leicht und geht zurueck in die Grundhaltung.
     */
    private static State inspect(State idle, float t, float time) {
        State hold = INSPECT_HOLD.resolve();
        State pre = State.mix(idle, hold, 0.35F);
        if (t < INSPECT_RAISE) {
            return State.mix(idle, pre, easeInOut(t / INSPECT_RAISE));
        }
        if (t < INSPECT_RAISE + INSPECT_SPIN) {
            float u = (t - INSPECT_RAISE) / INSPECT_SPIN;
            // fast linear, am Ende abbremsen
            float k = 0.5F * u + 0.5F * (1.0F - (1.0F - u) * (1.0F - u));
            State s = State.mix(pre, hold, easeInOut(u));
            s.spin = 360.0F * k;
            return s;
        }
        float h = t - INSPECT_RAISE - INSPECT_SPIN;
        State held = hold.add(Pose.offset(0.006F * Mth.sin(h * 2.1F), 0.008F * Mth.sin(h * 1.4F), 0.0F,
                0.0F, 0.0F, 3.0F * Mth.sin(h * 1.2F)));
        held.spin = 4.0F * Mth.sin(h * 1.6F);
        if (t < INSPECT_HOLD_END) {
            return held;
        }
        return State.mix(held, idle, easeInOut((t - INSPECT_HOLD_END) / (INSPECT_END - INSPECT_HOLD_END)));
    }

    /** Ziehen: Arme kommen von unten hoch, die Dolche drehen sich dabei einmal in die Hand. */
    private static State draw(State target, float t, float direction) {
        float k = easeOut(t / DRAW_TIME);
        State start = target.add(Pose.offset(0.0F, -0.32F, 0.08F, 0.0F, -28.0F, 0.0F));
        State s = State.mix(start, target, k);
        s.spin = direction * -360.0F * (1.0F - k);
        return s;
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float easeInOut(float t) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        return 0.5F - 0.5F * Mth.cos(Mth.PI * t);
    }

    private static float easeOut(float t) {
        t = 1.0F - Mth.clamp(t, 0.0F, 1.0F);
        return 1.0F - t * t * t;
    }

    // ---------------------------------------------------------------- Zeichnen

    private static void drawHand(AbstractClientPlayer player, State s, boolean right, boolean slim, float drop,
                                 PoseStack poseStack, SubmitNodeCollector collector, int light) {
        Minecraft client = Minecraft.getInstance();
        //? if >=26.1 {
        @SuppressWarnings("unchecked")
        AvatarRenderer<AbstractClientPlayer> renderer = (AvatarRenderer<AbstractClientPlayer>) client.getEntityRenderDispatcher()
                .getRenderer(player);
        //?} else {
        /*AvatarRenderer<AbstractClientPlayer> renderer = client.getEntityRenderDispatcher().getPlayerRenderer(player);
        *///?}
        ModelPart arm = right ? renderer.getModel().rightArm : renderer.getModel().leftArm;
        float side = right ? 1.0F : -1.0F;
        // Handruecken: Abstand von der Armmitte (schmale Arme sind 3 statt 4 Pixel dick)
        float halfThick = (slim ? 1.5F : 2.0F) / 16.0F;

        poseStack.pushPose();
        poseStack.translate(s.fist.x + side * KnivesMod.offsetX, s.fist.y + KnivesMod.offsetY - drop,
                s.fist.z + KnivesMod.offsetZ);
        Compat.rotate(poseStack, s.arm);

        if (!player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(handToPart(right, slim, arm.getInitialPose(), right ? 0.1F : -0.1F));
            Identifier skin = player.getSkin().body().texturePath();
            if (right) {
                renderer.renderRightHand(poseStack, collector, light, skin, player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
            } else {
                renderer.renderLeftHand(poseStack, collector, light, skin, player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
            }
            poseStack.popPose();
        }

        poseStack.translate(s.off.x, s.off.y + halfThick - 0.125F, s.off.z);
        Compat.rotate(poseStack, s.dagger);
        Compat.rotate(poseStack, new Quaternionf().rotationZ(s.spin * Mth.DEG_TO_RAD));
        float size = KnivesMod.size;
        poseStack.scale(size, size, size);
        Mesh mesh = meshes[right ? 0 : 1];
        // Glanz/Kanten je Ecke aus der Blickrichtung (Kamera schaut entlang -Z)
        Quaternionf view = new Quaternionf(s.arm).mul(s.dagger).rotateZ(s.spin * Mth.DEG_TO_RAD);
        mesh.shade(view);
        collector.submitCustomGeometry(poseStack, renderType(), (pose, buffer) -> mesh.emit(pose, buffer, light));
        poseStack.popPose();
    }

    /**
     * Matrix vom Hand-Rahmen H in den Raum vor ModelPart.translateAndRotate: so landet die Faust des Arm-Wuerfels
     * genau auf H (Handruecken = H.Y, Schulter = H.Z).
     */
    private static Matrix4f handToPart(boolean right, boolean slim, PartPose initial, float zRot) {
        float width = slim ? 3.0F : 4.0F;
        float fistX = (right ? 1.0F - width / 2.0F : width / 2.0F - 1.0F) / 16.0F;
        float fistY = 10.0F / 16.0F;
        float s = right ? 1.0F : -1.0F;
        // Spalten: Teil-X -> -s*H.Y, Teil-Y -> -H.Z, Teil-Z -> s*H.X
        Matrix4f m = new Matrix4f(
                0.0F, -s, 0.0F, 0.0F,
                0.0F, 0.0F, -1.0F, 0.0F,
                s, 0.0F, 0.0F, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        m.translate(-fistX, -fistY, 0.0F);
        m.rotateZ(-zRot);
        m.translate(-initial.x() / 16.0F, -initial.y() / 16.0F, -initial.z() / 16.0F);
        return m;
    }

    private static RenderType renderType() {
        //? if >=26.1 {
        return RenderTypes.entityCutout(TEXTURE);
        //?} else {
        /*return RenderTypes.entityCutoutNoCull(TEXTURE);
        *///?}
    }

    // ---------------------------------------------------------------- Laden

    private static boolean ensureLoaded() {
        if (meshes != null) {
            return true;
        }
        if (loadFailed) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        try {
            meshes = readMeshes(client);
            NativeImage image;
            try (InputStream in = open(client, TEXTURE_FILE)) {
                image = NativeImage.read(in);
            }
            DynamicTexture texture = new DynamicTexture(() -> "nova knives", image) {
                {
                    // Glatt statt pixelig: das Modell ist viel feiner als Minecraft-Texturen
                    this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE,
                            AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
                }
            };
            client.getTextureManager().register(TEXTURE, texture);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not load the knife model", e);
            meshes = null;
            loadFailed = true;
            return false;
        }
    }

    private static InputStream open(Minecraft client, Identifier id) throws IOException {
        Optional<Resource> resource = client.getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            throw new IOException("missing " + id);
        }
        return resource.get().open();
    }

    private static Mesh[] readMeshes(Minecraft client) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(open(client, MESH_FILE)))) {
            if (in.readInt() != 0x4E4B4D31) { // "NKM1"
                throw new IOException("bad knife mesh");
            }
            int count = in.readInt();
            Mesh[] result = new Mesh[count];
            for (int m = 0; m < count; m++) {
                int vertices = in.readInt();
                int indices = in.readInt();
                float[] pos = new float[vertices * 3];
                float[] normal = new float[vertices * 3];
                float[] uv = new float[vertices * 2];
                for (int v = 0; v < vertices; v++) {
                    pos[v * 3] = in.readFloat();
                    pos[v * 3 + 1] = in.readFloat();
                    pos[v * 3 + 2] = in.readFloat();
                    normal[v * 3] = in.readShort() / 32767.0F;
                    normal[v * 3 + 1] = in.readShort() / 32767.0F;
                    normal[v * 3 + 2] = in.readShort() / 32767.0F;
                    uv[v * 2] = in.readUnsignedShort() / 65535.0F;
                    uv[v * 2 + 1] = in.readUnsignedShort() / 65535.0F;
                }
                int[] index = new int[indices];
                for (int i = 0; i < indices; i++) {
                    index[i] = in.readUnsignedShort();
                }
                result[m] = new Mesh(pos, normal, uv, index);
            }
            if (count < 2) {
                throw new IOException("knife mesh needs two daggers");
            }
            return result;
        }
    }

    // ---------------------------------------------------------------- Datentypen

    /** Ein Dolch: Dreiecke im Dolch-Rahmen (Ursprung = Griff, +Y = Klingenspitze, +Z = Flachseite), in Bloecken. */
    private static final class Mesh {
        final float[] pos;
        final float[] normal;
        final float[] uv;
        final int[] index;
        /** Helligkeit je Ecke (Glanz + Kantenlicht), wird jeden Frame vor dem Einreichen neu gerechnet. */
        final int[] color;

        Mesh(float[] pos, float[] normal, float[] uv, int[] index) {
            this.pos = pos;
            this.normal = normal;
            this.uv = uv;
            this.index = index;
            this.color = new int[pos.length / 3];
        }

        /** Metall-Look: Grundhelligkeit, Glanzpunkt von oben-links-vorne und helle Kanten zur Kamera hin. */
        void shade(Quaternionf rotation) {
            Vector3f n = new Vector3f();
            // Halbvektor zwischen Licht (oben links vorne) und Blickrichtung (zur Kamera = +Z)
            float hx = -0.20F, hy = 0.34F, hz = 0.92F;
            for (int v = 0; v < color.length; v++) {
                rotation.transform(normal[v * 3], normal[v * 3 + 1], normal[v * 3 + 2], n);
                float facing = Math.abs(n.z);
                float spec = Math.max(0.0F, n.x * hx + n.y * hy + n.z * hz);
                spec *= spec;
                spec *= spec;
                spec *= spec;
                float rim = 1.0F - facing;
                rim *= rim;
                float value = Mth.clamp(0.74F + 0.45F * spec * spec + 0.32F * rim, 0.0F, 1.0F);
                int c = (int) (value * 255.0F);
                color[v] = 0xFF000000 | c << 16 | c << 8 | c;
            }
        }

        void emit(PoseStack.Pose pose, VertexConsumer buffer, int light) {
            for (int i = 0; i + 2 < index.length; i += 3) {
                vertex(pose, buffer, index[i], light);
                vertex(pose, buffer, index[i + 1], light);
                vertex(pose, buffer, index[i + 2], light);
                // Render-Typen zeichnen Vierecke: Dreieck mit doppelter letzter Ecke
                vertex(pose, buffer, index[i + 2], light);
            }
        }

        private void vertex(PoseStack.Pose pose, VertexConsumer buffer, int v, int light) {
            buffer.addVertex(pose, pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2])
                    .setColor(color[v])
                    .setUv(uv[v * 2], uv[v * 2 + 1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(pose, normal[v * 3], normal[v * 3 + 1], normal[v * 3 + 2]);
        }
    }

    /** Pose in Zahlen (Grad): Faust-Position + Arm-Drehung (Y, X, Z), Dolch-Versatz + Dolch-Drehung (Y, X, Z). */
    private record Pose(float fx, float fy, float fz, float yaw, float pitch, float roll,
                        float ox, float oy, float oz, float rx, float ry, float rz) {
        static Pose offset(float fx, float fy, float fz, float yaw, float pitch, float roll) {
            return new Pose(fx, fy, fz, yaw, pitch, roll, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }

        State resolve() {
            return new State(new Vector3f(fx, fy, fz), euler(yaw, pitch, roll), new Vector3f(ox, oy, oz), euler(ry, rx, rz), 0.0F);
        }

        private static Quaternionf euler(float y, float x, float z) {
            return new Quaternionf().rotationY(y * Mth.DEG_TO_RAD).rotateX(x * Mth.DEG_TO_RAD).rotateZ(z * Mth.DEG_TO_RAD);
        }
    }

    /** Aufgeloeste Pose zum Mischen (Drehungen als Quaternionen). */
    private static final class State {
        final Vector3f fist;
        final Quaternionf arm;
        final Vector3f off;
        final Quaternionf dagger;
        float spin;

        State(Vector3f fist, Quaternionf arm, Vector3f off, Quaternionf dagger, float spin) {
            this.fist = fist;
            this.arm = arm;
            this.off = off;
            this.dagger = dagger;
            this.spin = spin;
        }

        static State mix(State a, State b, float t) {
            return new State(new Vector3f(a.fist).lerp(b.fist, t), new Quaternionf(a.arm).slerp(b.arm, t),
                    new Vector3f(a.off).lerp(b.off, t), new Quaternionf(a.dagger).slerp(b.dagger, t),
                    Mth.lerp(t, a.spin, b.spin));
        }

        /** Kleine Zusatzbewegung: Faust verschieben, Arm zusaetzlich drehen (lokal). */
        State add(Pose p) {
            Quaternionf extra = new Quaternionf().rotationY(p.yaw() * Mth.DEG_TO_RAD).rotateX(p.pitch() * Mth.DEG_TO_RAD)
                    .rotateZ(p.roll() * Mth.DEG_TO_RAD);
            return new State(new Vector3f(fist).add(p.fx(), p.fy(), p.fz()), new Quaternionf(arm).mul(extra),
                    new Vector3f(off), new Quaternionf(dagger), spin);
        }

        /** Spiegeln an der Bildmitte (linke statt rechte Seite). */
        State mirror() {
            return new State(new Vector3f(-fist.x, fist.y, fist.z), new Quaternionf(arm.x, -arm.y, -arm.z, arm.w),
                    new Vector3f(-off.x, off.y, off.z), new Quaternionf(dagger.x, -dagger.y, -dagger.z, dagger.w), -spin);
        }
    }
}
