package nova.nova_client.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kill Effect "Squash": der getoetete Spieler steht mit seinem echten Skin da, ein Amboss faellt vom Himmel und
 * drueckt ihn wie im Cartoon platt (flach und breit, federt nach). Ueber dem platten Spieler kreisen Sterne,
 * dann versinkt alles im Boden. Gezeichnet mit dem echten Spielermodell und dem echten Amboss-Block.
 */
public final class SquashEffect {
    private static final long FALL_MS = 450L;
    private static final long SINK_AT = 2700L;
    private static final long LIFE_MS = 3300L;
    private static final double DROP_HEIGHT = 7.0D;
    /** Groesse des Spielermodells (wie bei Vanilla). */
    private static final float PLAYER_SCALE = 0.9375F;
    private static final double PLAYER_HEIGHT = 1.8D;
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final class Squash {
        final Vec3 pos;
        final float yaw;
        final PlayerSkin skin;
        final long born = System.currentTimeMillis();
        final AvatarRenderState state = new AvatarRenderState();
        boolean impacted;

        Squash(Vec3 pos, float yaw, PlayerSkin skin) {
            this.pos = pos;
            this.yaw = yaw;
            this.skin = skin;
            state.skin = skin;
            state.showHat = true;
            state.showJacket = true;
            state.showLeftPants = true;
            state.showRightPants = true;
            state.showLeftSleeve = true;
            state.showRightSleeve = true;
        }
    }

    private static final List<Squash> SQUASHES = new ArrayList<>();
    private static PlayerModel wideModel;
    private static PlayerModel slimModel;

    private SquashEffect() {
    }

    public static boolean isEmpty() {
        return SQUASHES.isEmpty();
    }

    public static void clear() {
        SQUASHES.clear();
    }

    /** Startet den Effekt; bei Mobs ohne Skin faellt nur der Amboss. */
    public static void start(Vec3 pos, LivingEntity target) {
        PlayerSkin skin = target instanceof AbstractClientPlayer player ? player.getSkin() : null;
        float yaw = target != null ? target.getYRot() : 0.0F;
        if (target != null && target == Minecraft.getInstance().player) {
            // Vorschau mit dem eigenen Skin: zu dir drehen
            yaw += 180.0F;
        }
        SQUASHES.add(new Squash(pos, yaw, skin));
    }

    // ---------------------------------------------------------------- Zeitverlauf

    /** Hoehe ueber dem Boden, Breite (Faktor). */
    private static float[] squash(long age) {
        if (age < FALL_MS) {
            return new float[]{1.0F, 1.0F};
        }
        float t = Math.min(1.0F, (age - FALL_MS) / 90.0F);
        float ease = 1.0F - (1.0F - t) * (1.0F - t);
        float flat = 1.0F - 0.94F * ease;
        // Nachfedern nach dem Aufprall
        float since = (age - FALL_MS - 90L) / 1000.0F;
        if (since > 0.0F) {
            flat += 0.035F * (float) Math.exp(-since * 6.0F) * (float) Math.sin(since * 30.0F);
        }
        return new float[]{Math.max(0.03F, flat), 1.0F + 0.6F * ease};
    }

    /** Wie weit alles am Ende im Boden versinkt. */
    private static double sink(long age) {
        if (age < SINK_AT) {
            return 0.0D;
        }
        float t = (age - SINK_AT) / (float) (LIFE_MS - SINK_AT);
        return t * t * 1.4D;
    }

    /** Unterkante des Ambosses. */
    private static double anvilBottom(Squash s, long age, float flat) {
        if (age < FALL_MS) {
            float t = age / (float) FALL_MS;
            return s.pos.y + PLAYER_HEIGHT + DROP_HEIGHT * (1.0F - t * t);
        }
        return s.pos.y + (s.skin != null ? PLAYER_HEIGHT * flat : 0.0D) - sink(age);
    }

    // ---------------------------------------------------------------- Modelle (aus NovaFx, jeden Frame)

    public static void submit(LevelRenderContext context, Vec3 camera) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        long now = System.currentTimeMillis();
        SQUASHES.removeIf(s -> now - s.born > LIFE_MS);
        if (level == null || SQUASHES.isEmpty()) {
            return;
        }
        SubmitNodeCollector collector = context.submitNodeCollector();
        PoseStack pose = context.poseStack();
        for (Squash s : SQUASHES) {
            long age = now - s.born;
            float[] shape = squash(age);
            if (age >= FALL_MS && !s.impacted) {
                s.impacted = true;
                impact(level, s);
            }

            // Spieler mit echtem Skin, platt gedrueckt (Skalierung von den Fuessen aus)
            if (s.skin != null) {
                pose.pushPose();
                pose.translate(s.pos.x - camera.x, s.pos.y - sink(age) - camera.y, s.pos.z - camera.z);
                Compat.rotate(pose, Axis.YP.rotationDegrees(180.0F - s.yaw));
                pose.scale(shape[1], shape[0], shape[1]);
                pose.scale(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE);
                // Modelle sind wie bei Vanilla auf dem Kopf und um 1.5 verschoben
                pose.scale(-1.0F, -1.0F, 1.0F);
                pose.translate(0.0F, -1.501F, 0.0F);
                PlayerModel model = model(client, s.skin.model() == PlayerModelType.SLIM);
                //? if >=26.1 {
                collector.submitModel(model, s.state, pose, model.renderType(s.skin.body().texturePath()), FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, -1);
                //?} else {
                /*collector.submitModel(model, s.state, pose, model.renderType(s.skin.body().texturePath()), FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY, -1, null);
                *///?}
                pose.popPose();
            }

            // Amboss als echter Block
            double bottom = anvilBottom(s, age, shape[0]);
            BlockPos blockPos = BlockPos.containing(s.pos.x, bottom, s.pos.z);
            MovingBlockRenderState block = new MovingBlockRenderState();
            block.blockState = Blocks.ANVIL.defaultBlockState().setValue(AnvilBlock.FACING, Direction.fromYRot(s.yaw + 90.0F));
            block.blockPos = blockPos;
            block.randomSeedPos = blockPos;
            block.biome = level.getBiome(blockPos);
            //? if >=26.1 {
            block.cardinalLighting = level.cardinalLighting();
            block.lightEngine = level.getLightEngine();
            //?} else {
            /*block.level = level;
            *///?}
            pose.pushPose();
            pose.translate(s.pos.x - camera.x - 0.5D, bottom - camera.y, s.pos.z - camera.z - 0.5D);
            //? if >=26.1 {
            collector.submitMovingBlock(pose, block, 0);
            //?} else {
            /*collector.submitMovingBlock(pose, block);
            *///?}
            pose.popPose();
        }
    }

    private static PlayerModel model(Minecraft client, boolean slim) {
        if (slim) {
            if (slimModel == null) {
                slimModel = new PlayerModel(client.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slimModel;
        }
        if (wideModel == null) {
            wideModel = new PlayerModel(client.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return wideModel;
    }

    /** Aufprall: Geraeusch, Staubwolke, Funken. */
    private static void impact(ClientLevel level, Squash s) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2.0D * i / 24.0D;
            level.addParticle(ParticleTypes.CLOUD, s.pos.x + Math.cos(angle) * 0.6D, s.pos.y + 0.1D,
                    s.pos.z + Math.sin(angle) * 0.6D, Math.cos(angle) * 0.15D, 0.02D, Math.sin(angle) * 0.15D);
        }
        for (int i = 0; i < 16; i++) {
            level.addParticle(ParticleTypes.CRIT, s.pos.x, s.pos.y + 0.3D, s.pos.z, (r.nextDouble() - 0.5D) * 0.9D,
                    r.nextDouble() * 0.5D, (r.nextDouble() - 0.5D) * 0.9D);
        }
        if (nova.nova_client.client.mods.KillEffectsMod.sound) {
            level.playLocalSound(s.pos.x, s.pos.y, s.pos.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.2F, 0.8F, false);
        }
    }

    // ---------------------------------------------------------------- Sterne (aus NovaFx)

    /** Cartoon-Sterne, die ueber dem platten Spieler im Kreis fliegen. */
    public static void render(NovaFx.Painter painter) {
        long now = System.currentTimeMillis();
        for (Squash s : SQUASHES) {
            long age = now - s.born;
            if (age < FALL_MS + 100L || age > SINK_AT) {
                continue;
            }
            float fadeIn = Math.min(1.0F, (age - FALL_MS - 100L) / 200.0F);
            float[] shape = squash(age);
            double top = anvilBottom(s, age, shape[0]) + 1.15D;
            for (int i = 0; i < 5; i++) {
                double angle = age / 260.0D + Math.PI * 2.0D * i / 5.0D;
                Vec3 star = new Vec3(s.pos.x + Math.cos(angle) * 0.75D, top + Math.sin(angle * 2.0D) * 0.06D,
                        s.pos.z + Math.sin(angle) * 0.75D);
                int color = i % 2 == 0 ? 0xFFE066 : NovaFx.LAVENDER;
                painter.spark(star, 0.07D, color, fadeIn);
            }
        }
    }
}
