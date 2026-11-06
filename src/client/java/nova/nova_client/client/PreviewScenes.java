package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.Objective;
import nova.nova_client.client.mods.*;

import java.util.List;
import java.util.Locale;

/**
 * Die Szenen der Modul-Vorschau (Buehne und Bausteine in {@link ModPreview}): kurze Schleifen, die zeigen, was ein
 * Modul macht. Kampf und Bewegung am eigenen Charakter (mit Zombie, Kristall, Bloecken), HUD-Module mit ihrem eigenen
 * Zeichen-Code, alles andere als kleine Oberflaechen-Animation im Stil des Menues.
 */
final class PreviewScenes {
    private static final int TEXT = NovaGui.TEXT;
    private static final int DIM = NovaGui.TEXT_DIM;
    private static final int MUTED = NovaGui.TEXT_MUTED;
    private static final int BRAND = NovaGui.BRAND;
    private static final int GOOD = NovaGui.SUCCESS;
    private static final int BAD = NovaGui.DANGER;
    private static final int GOLD_GLOW = 0xFFF5D54A;
    private static final int CELL = 0xFF1D2128;
    private static final int CELL_LIGHT = 0xFF2A2F38;

    private static final ItemStack TOTEM = new ItemStack(Items.TOTEM_OF_UNDYING);
    private static final ItemStack SWORD = new ItemStack(Items.DIAMOND_SWORD);
    private static final ItemStack PICKAXE = new ItemStack(Items.DIAMOND_PICKAXE);
    private static final ItemStack AXE = new ItemStack(Items.DIAMOND_AXE);
    private static final ItemStack SHOVEL = new ItemStack(Items.DIAMOND_SHOVEL);
    private static final ItemStack PEARL = new ItemStack(Items.ENDER_PEARL);
    private static final ItemStack GAPPLE = new ItemStack(Items.GOLDEN_APPLE);
    private static final ItemStack STEAK = new ItemStack(Items.COOKED_BEEF);
    private static final ItemStack BRICKS = new ItemStack(Items.COBBLESTONE);
    private static final ItemStack BOW = new ItemStack(Items.BOW);
    private static final ItemStack XP = new ItemStack(Items.EXPERIENCE_BOTTLE);
    private static final ItemStack SPEAR = new ItemStack(Items.IRON_SPEAR);
    private static final ItemStack TRIDENT = new ItemStack(Items.TRIDENT);
    private static final ItemStack CRYSTAL = new ItemStack(Items.END_CRYSTAL);
    private static final ItemStack GLOWSTONE = new ItemStack(Items.GLOWSTONE);
    private static final ItemStack DIAMOND = new ItemStack(Items.DIAMOND);
    private static final ItemStack EMERALD = new ItemStack(Items.EMERALD);
    private static final ItemStack GOLD = new ItemStack(Items.GOLD_INGOT);
    private static final ItemStack IRON = new ItemStack(Items.IRON_INGOT);
    private static final ItemStack NETHERITE = new ItemStack(Items.NETHERITE_INGOT);
    private static final ItemStack ELYTRA = new ItemStack(Items.ELYTRA);
    private static final ItemStack STONE = new ItemStack(Items.STONE);
    private static final ItemStack DIAMOND_ORE = new ItemStack(Items.DIAMOND_ORE);
    private static final ItemStack SHULKER = new ItemStack(Items.SHULKER_BOX);
    private static final ItemStack SPAWNER = new ItemStack(Items.SPAWNER);
    private static final ItemStack ENDER_CHEST = new ItemStack(Items.ENDER_CHEST);
    private static final ItemStack CHEST = new ItemStack(Items.CHEST);
    private static final ItemStack AMETHYST = new ItemStack(Items.AMETHYST_CLUSTER);
    private static final ItemStack DEBRIS = new ItemStack(Items.ANCIENT_DEBRIS);
    private static final ItemStack PICK_NETHERITE = new ItemStack(Items.NETHERITE_PICKAXE);
    private static final ItemStack VINE = new ItemStack(Items.VINE);
    private static final ItemStack KELP = new ItemStack(Items.KELP);
    private static final ItemStack FRAME = new ItemStack(Items.ITEM_FRAME);
    private static final Identifier CROSSHAIR = Identifier.withDefaultNamespace("hud/crosshair");
    private static final String SEED = "-4172144997902289642";

    // Custom Font: Beispielzeilen in der gewaehlten Schrift (neu nur beim Wechsel der Schrift)
    private static CustomFonts.Entry fontEntry;
    private static Component fontLine1;
    private static Component fontLine2;
    private static long capePaintedAt;

    private PreviewScenes() {
    }

    // ---------------------------------------------------------------- Helfer

    private static float window(float phase, float from, float to) {
        return ModPreview.window(phase, from, to);
    }

    private static float ease(float f) {
        return ModPreview.ease(f);
    }

    private static float lerp(float from, float to, float f) {
        return ModPreview.lerp(from, to, f);
    }

    /** Sichtbar zwischen from und to, mit kurzem Ein- und Ausblenden. */
    private static float shown(float phase, float from, float to) {
        return Math.max(0.0F, Math.min(window(phase, from, from + 0.06F), 1.0F - window(phase, to - 0.06F, to)));
    }

    private static float sin(float x) {
        return (float) Math.sin(x);
    }

    private static float cos(float x) {
        return (float) Math.cos(x);
    }

    private static LocalPlayer me() {
        return Minecraft.getInstance().player;
    }

    private static String myName() {
        return me().getName().getString();
    }

    /** Taste des Moduls (sonst ein Beispiel). */
    private static String keyName(ModPreview p, String fallback) {
        return p.mod.hasKey() ? p.mod.keyName().toUpperCase(Locale.ROOT) : fallback;
    }

    /** Taste oben links auf der Buehne. */
    private static void key(ModPreview p, String key, boolean pressed) {
        p.keycap(key, p.x0 + 8 + HudCards.keycapWidth(p.font, key), p.y0 + 6, pressed);
    }

    /** Eigener Charakter mittig, leicht von oben, mit Licht dahinter und Schatten (Charakter-Szenen). */
    private static AvatarRenderState hero(ModPreview p, float yaw) {
        return hero(p, yaw, 0.4F);
    }

    /** Wie hero, mit eigener Groesse (Anteil der Buehnenhoehe je Block; kleiner = Platz fuer ein Namensschild). */
    private static AvatarRenderState hero(ModPreview p, float yaw, float size) {
        p.scene(p.h() * size, p.cx(), p.y1 - 10, 0.0F, -0.12F);
        p.spot(0.0F, 0.95F, 0.0F, 1.15F);
        p.floor(0.0F, 0.0F, 0.5F, 0.6F);
        return p.character(0.0F, 0.0F, 0.0F, yaw);
    }

    /** Seitenansicht: Charakter links mit Blick nach rechts, das Gegenueber distance Bloecke rechts davon. */
    private static AvatarRenderState duel(ModPreview p, float distance) {
        float scale = Math.min(p.h() * 0.33F, (p.w() - 18) / (distance + 1.2F));
        p.scene(scale, p.cx() - distance * scale / 2.0F, p.y1 - 12, 0.0F, -0.1F);
        p.floor(0.0F, 0.0F, 0.45F, 0.55F);
        return p.character(0.0F, 0.0F, 0.0F, -90.0F);
    }

    /** Kleine Welt aus Gras, Stamm und Laub (fuer Licht- und Sicht-Effekte). */
    private static void meadow(ModPreview p) {
        p.scene(p.h() * 0.16F, p.cx(), p.cy() + 16, 0.6F, -0.5F);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                p.block(Blocks.GRASS_BLOCK, x, 0.0F, z);
            }
        }
        p.block(Blocks.OAK_LOG, 1.0F, 1.0F, -1.0F);
        p.block(Blocks.OAK_LOG, 1.0F, 2.0F, -1.0F);
        p.block(Blocks.OAK_LEAVES, 1.0F, 3.0F, -1.0F);
        p.block(Blocks.OAK_LEAVES, 0.0F, 3.0F, -1.0F);
        p.block(Blocks.OAK_LEAVES, 1.0F, 3.0F, 0.0F);
        p.block(Blocks.PUMPKIN, -1.0F, 1.0F, 1.0F);
        p.render();
    }

    /** Linke obere Ecke eines Rasters, mittig auf der Buehne (etwas unter der Mitte, oben bleibt Platz fuer Text). */
    private static int gridX(ModPreview p, int cols, int size, int gap) {
        return p.cx() - (cols * (size + gap) - gap) / 2;
    }

    private static int gridY(ModPreview p, int rows, int size, int gap) {
        return p.cy() - (rows * (size + gap) - gap) / 2 + 4;
    }

    /** Fester "Zufall" je Zelle (Rauschen in Karten). */
    private static float noise(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
    }

    // ================================================================ Combat

    static void autoTotem(ModPreview p) {
        float ph = p.phase(2.6F);
        AvatarRenderState s = hero(p, 20.0F);
        p.hold(s, me(), false, ph > 0.5F && ph < 0.66F ? ItemStack.EMPTY : TOTEM);
        p.render();
        p.project(0.0F, 1.1F, 0.0F);
        p.burst(p.px, p.py, window(ph, 0.5F, 0.85F), GOLD_GLOW, 14, p.h() * 0.38F);
        p.badge("+1 Totem", p.cx(), p.y0 + 6, GOOD, shown(ph, 0.66F, 0.98F));
    }

    static void autoInvTotem(ModPreview p) {
        float ph = p.phase(2.6F);
        float move = ease(window(ph, 0.3F, 0.62F));
        int gx = p.x0 + 10;
        int gy = p.cy() - 12;
        p.text(NovaType.Face.CAPTION, "Inventory", gx, gy - 14, MUTED);
        ItemStack[] grid = {SWORD, PEARL, GAPPLE, STEAK, TOTEM, BRICKS};
        for (int i = 0; i < grid.length; i++) {
            p.slot(gx + (i % 3) * 20, gy + (i / 3) * 20, i == 4 && move > 0.0F ? ItemStack.EMPTY : grid[i], false);
        }
        int ox = p.x1 - 28;
        int oy = gy + 10;
        p.centered(NovaType.Face.CAPTION, "Off-hand", ox + 9, gy - 14, MUTED);
        p.slot(ox, oy, move >= 1.0F ? TOTEM : ItemStack.EMPTY, move >= 1.0F);
        if (move > 0.0F && move < 1.0F) {
            p.item(TOTEM, Math.round(lerp(gx + 21, ox + 1, move)), Math.round(lerp(gy + 21, oy + 1, move) - sin(move * 3.14159F) * 12.0F));
        }
    }

    static void hoverTotem(ModPreview p) {
        float ph = p.phase(2.8F);
        float hover = ease(window(ph, 0.05F, 0.35F));
        float move = ease(window(ph, 0.42F, 0.62F));
        int gx = p.cx() - 39;
        int gy = p.y0 + 16;
        int by = gy + 44;
        ItemStack[] grid = {SWORD, PEARL, TOTEM, GAPPLE};
        ItemStack[] bar = {SWORD, PICKAXE, ItemStack.EMPTY, PEARL};
        p.text(NovaType.Face.CAPTION, "Inventory", gx, gy - 12, MUTED);
        p.text(NovaType.Face.CAPTION, "Hotbar", gx, by - 12, MUTED);
        for (int i = 0; i < 4; i++) {
            p.slot(gx + i * 20, gy, i == 2 && move > 0.0F ? ItemStack.EMPTY : grid[i], i == 2 && hover >= 1.0F && move <= 0.0F);
            p.slot(gx + i * 20, by, i == 2 && move >= 1.0F ? TOTEM : bar[i], i == 2 && move >= 1.0F);
        }
        if (move > 0.0F && move < 1.0F) {
            p.item(TOTEM, gx + 41, Math.round(lerp(gy + 1, by + 1, move)));
        }
        p.cursor(lerp(p.x1 - 10, gx + 53, hover), lerp(p.y1 - 8, gy + 13, hover));
    }

    static void autoDoubleHand(ModPreview p) {
        float ph = p.phase(2.2F);
        ItemStack[] bar = {SWORD, TOTEM, GAPPLE, TOTEM, PEARL};
        int x = p.cx() - 49;
        int y = p.cy() - 5;
        float refill = window(ph, 0.5F, 0.62F);
        boolean gone = ph > 0.38F;
        for (int i = 0; i < bar.length; i++) {
            p.slot(x + i * 20, y, i == 1 && gone ? ItemStack.EMPTY : bar[i], i == 1 && refill > 0.0F);
        }
        if (refill > 0.0F) {
            p.item(TOTEM, x + 29, y + 9, ease(refill));
        }
        p.burst(x + 29, y + 9, window(ph, 0.38F, 0.7F), GOLD_GLOW, 12, 22.0F);
        p.badge("Refilled", p.cx(), p.y0 + 8, GOOD, shown(ph, 0.62F, 0.96F));
    }

    static void reach(ModPreview p) {
        float ph = p.phase(1.6F);
        // auf der Buehne hoechstens 3,6 Bloecke (sonst winzig), die Zahl zeigt die echte Reichweite
        float distance = Math.min(ReachMod.distance, 3.6F);
        AvatarRenderState s = duel(p, distance);
        p.hold(s, me(), true, SWORD);
        ModPreview.swing(s, ph, 0.15F, 0.45F);
        p.floor(distance, 0.0F, 0.45F, 0.55F);
        LivingEntityRenderState z = p.mob(p.zombie(), distance, 0.0F, 0.0F, 90.0F);
        z.hasRedOverlay = ph > 0.3F && ph < 0.5F;
        p.render();
        p.project(0.35F, 0.0F, 0.0F);
        float ax = p.px;
        float ay = p.py + 4.0F;
        p.project(distance - 0.35F, 0.0F, 0.0F);
        p.dashed(ax, ay, p.px, ay, 1.0F, 3.0F, 0.0F, NovaGui.alpha(BRAND, 0.85F));
        p.centered(NovaType.Face.MONO, String.format(Locale.ROOT, "%.1f m", ReachMod.distance), Math.round((ax + p.px) / 2.0F), p.y0 + 6, TEXT);
    }

    static void crystalMacro(ModPreview p) {
        float ph = p.phase(1.8F);
        AvatarRenderState s = duel(p, 2.2F);
        p.hold(s, me(), true, CRYSTAL);
        ModPreview.swing(s, ph, 0.45F, 0.62F);
        p.block(Blocks.OBSIDIAN, 2.2F, 0.0F, 0.0F);
        if (ph > 0.12F && ph < 0.62F) {
            EntityRenderState crystal = p.thing(p.crystal(), 2.2F, 1.0F, 0.0F, 0.75F * ease(window(ph, 0.12F, 0.26F)));
            crystal.ageInTicks = p.t * 20.0F;
        }
        p.render();
        p.project(2.2F, 1.9F, 0.0F);
        p.burst(p.px, p.py, window(ph, 0.62F, 0.95F), 0xFFFFC2EE, 16, p.scale * 1.4F);
        key(p, "RMB", ph < 0.62F);
    }

    static void anchorMacro(ModPreview p) {
        float ph = p.phase(2.0F);
        AvatarRenderState s = duel(p, 2.2F);
        p.hold(s, me(), true, GLOWSTONE);
        if (ph < 0.35F) {
            ModPreview.swing(s, ph, 0.1F, 0.3F);
        } else {
            ModPreview.swing(s, ph, 0.45F, 0.6F);
        }
        float charge = ph < 0.6F ? window(ph, 0.2F, 0.4F) : 0.0F;
        if (ph < 0.6F || ph > 0.9F) {
            // nach dem Glowstone leuchtet der Anker (geladen)
            BlockState anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, charge >= 0.5F ? 1 : 0);
            p.block(anchor, 2.2F, 0.0F, 0.0F, ph > 0.9F ? ease(window(ph, 0.9F, 1.0F)) : 1.0F);
        }
        p.render();
        p.project(2.2F, 1.0F, 0.0F);
        p.glow(p.px, p.py, p.scale * 0.8F, p.scale * 0.45F, NovaGui.alpha(0xFFFFB347, 0.7F * charge));
        p.burst(p.px, p.py - p.scale * 0.4F, window(ph, 0.6F, 0.9F), 0xFFFFB347, 16, p.scale * 1.6F);
        key(p, "RMB", ph < 0.6F);
    }

    static void keyPearl(ModPreview p) {
        float ph = p.phase(2.0F);
        AvatarRenderState s = duel(p, 3.2F);
        p.hold(s, me(), true, ph > 0.08F && ph < 0.3F ? PEARL : SWORD);
        ModPreview.swing(s, ph, 0.18F, 0.36F);
        float fly = window(ph, 0.26F, 0.8F);
        if (fly > 0.0F && fly < 1.0F) {
            float y = 1.5F + 1.4F * sin(fly * 3.14159F) - 1.3F * fly;
            p.item(Items.ENDER_PEARL, lerp(0.5F, 3.2F, fly), y, 0.0F, 0.8F, p.t * 8.0F, 0.0F);
        }
        p.render();
        p.project(3.2F, 0.2F, 0.0F);
        p.burst(p.px, p.py, window(ph, 0.8F, 1.0F), 0xFFB070FF, 12, p.scale * 0.9F);
        key(p, keyName(p, "G"), ph > 0.04F && ph < 0.2F);
    }

    static void lunge(ModPreview p) {
        float ph = p.phase(1.8F);
        float scale = p.h() * 0.38F;
        p.scene(scale, p.cx() - scale * 0.4F, p.y1 - 10, 0.0F, -0.12F);
        float push = ease(window(ph, 0.25F, 0.42F)) * (1.0F - ease(window(ph, 0.6F, 0.9F))) * 0.8F;
        p.spot(push, 0.95F, 0.0F, 1.15F);
        p.floor(push, 0.0F, 0.5F, 0.6F);
        AvatarRenderState s = p.character(push, 0.0F, 0.0F, -90.0F);
        p.hold(s, me(), true, SPEAR);
        ModPreview.swing(s, ph, 0.25F, 0.5F, SwingAnimationType.STAB);
        p.render();
        key(p, keyName(p, "V"), ph > 0.2F && ph < 0.45F);
    }

    static void bowAimbot(ModPreview p) {
        AvatarRenderState s = duel(p, 3.0F);
        float z = 0.9F * sin(p.t * 1.4F);
        s.bodyRot = (float) Math.toDegrees(Math.atan2(-3.0F, z));
        p.hold(s, me(), true, BOW);
        ModPreview.armPose(s, true, HumanoidModel.ArmPose.BOW_AND_ARROW);
        s.isUsingItem = true;
        p.floor(3.0F, z, 0.45F, 0.5F);
        LivingEntityRenderState target = p.mob(p.zombie(), 3.0F, 0.0F, z, 90.0F);
        target.walkAnimationPos = p.t * 5.0F;
        target.walkAnimationSpeed = 0.5F;
        p.render();
        p.project(0.0F, 1.45F, 0.0F);
        float ax = p.px;
        float ay = p.py;
        p.project(3.0F, 1.3F, z);
        p.dashed(ax, ay, p.px, p.py, 0.8F, 3.0F, p.t * 2.0F, NovaGui.alpha(BRAND, 0.9F));
        p.ring(p.px, p.py, 4.5F, 1.0F, BRAND);
    }

    static void triggerBot(ModPreview p) {
        float scale = p.h() * 0.26F;
        p.scene(scale, p.cx() - scale * 1.2F, p.y1 - 22, 0.0F, -0.5F);
        float z = 1.5F * sin(p.t * 1.3F);
        boolean onTarget = Math.abs(z) < 0.3F;
        float hit = (p.t * 3.0F) % 1.0F;
        p.floor(0.0F, 0.0F, 0.45F, 0.5F);
        p.floor(2.4F, z, 0.45F, 0.5F);
        AvatarRenderState s = p.character(0.0F, 0.0F, 0.0F, -90.0F);
        p.hold(s, me(), true, SWORD);
        if (onTarget) {
            Compat.previewSwing(s, hit, SwingAnimationType.WHACK);
        }
        LivingEntityRenderState zombie = p.mob(p.zombie(), 2.4F, 0.0F, z, cos(p.t * 1.3F) >= 0.0F ? 0.0F : 180.0F);
        zombie.walkAnimationPos = p.t * 6.0F;
        zombie.walkAnimationSpeed = 0.6F;
        zombie.hasRedOverlay = onTarget && hit > 0.4F;
        p.render();
        p.project(0.3F, 1.5F, 0.0F);
        float ax = p.px;
        float ay = p.py;
        p.project(3.4F, 1.5F, 0.0F);
        p.line(ax, ay, p.px, p.py, 0.9F, NovaGui.alpha(onTarget ? BAD : BRAND, 0.85F));
        p.ring(p.px, p.py, 3.0F, 1.0F, onTarget ? BAD : BRAND);
    }

    static void killAura(ModPreview p) {
        p.scene(p.h() * 0.21F, p.cx(), p.y1 - 26, 0.3F, -0.55F);
        float step = 0.55F;
        int target = (int) (p.t / step) % 3;
        float local = (p.t % step) / step;
        p.floor(0.0F, 0.0F, 0.45F, 0.55F);
        for (int i = 0; i < 3; i++) {
            float angle = (float) Math.toRadians(30.0F + i * 120.0F);
            float x = sin(angle) * 1.8F;
            float z = cos(angle) * 1.8F;
            p.floor(x, z, 0.45F, 0.5F);
            LivingEntityRenderState zombie = p.mob(p.zombie(), x, 0.0F, z, 180.0F - (float) Math.toDegrees(angle));
            zombie.hasRedOverlay = i == target && local > 0.3F && local < 0.6F;
        }
        AvatarRenderState s = p.character(0.0F, 0.0F, 0.0F, -(30.0F + target * 120.0F));
        p.hold(s, me(), true, SWORD);
        ModPreview.swing(s, local, 0.12F, 0.55F);
        p.render();
    }

    static void shieldBreaker(ModPreview p) {
        float ph = p.phase(2.2F);
        AvatarRenderState s = duel(p, 2.0F);
        p.hold(s, me(), true, AXE);
        ModPreview.swing(s, ph, 0.25F, 0.5F);
        p.floor(2.0F, 0.0F, 0.45F, 0.5F);
        ArmedEntityRenderState guard = (ArmedEntityRenderState) p.mob(p.guard(), 2.0F, 0.0F, 0.0F, 90.0F);
        boolean broken = ph > 0.42F && ph < 0.92F;
        ModPreview.armPose(guard, false, broken ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.BLOCK);
        guard.hasRedOverlay = ph > 0.4F && ph < 0.5F;
        p.render();
        p.project(1.7F, 1.2F, 0.0F);
        p.burst(p.px, p.py, window(ph, 0.42F, 0.7F), 0xFFE0E6F0, 10, p.scale * 0.8F);
        p.badge("Shield disabled", p.cx(), p.y0 + 6, BRAND, shown(ph, 0.45F, 0.92F));
    }

    static void hitboxes(ModPreview p) {
        float yaw = 0.55F;
        p.scene(p.h() * 0.6F / 1.8F, p.cx(), p.y1 - 16, yaw, -0.12F);
        p.spot(0.0F, 0.95F, 0.0F, 1.15F);
        p.floor(0.0F, 0.0F, 0.5F, 0.6F);
        AvatarRenderState s = p.character(0.0F, 0.0F, 0.0F, ModPreview.facing(yaw) + 20.0F);
        s.yRot = 25.0F;
        s.xRot = 12.0F;
        hitbox(p, s, -1);
        p.render();
        hitbox(p, s, 1);
    }

    /** Hitbox wie das Modul sie zeichnet (Farben, Deckkraft, Breite, Leuchten); side 1 = vordere, -1 = hintere Linien. */
    private static void hitbox(ModPreview p, AvatarRenderState s, int side) {
        float alpha = HitboxesMod.opacity / 100.0F;
        if (alpha <= 0.0F) {
            return;
        }
        float half = s.boundingBoxWidth / 2.0F;
        float top = s.boundingBoxHeight;
        float width = Math.max(0.6F, HitboxesMod.lineWidth * 0.5F);
        int box = NovaGui.alpha(0xFF000000 | HitboxesMod.boxColor, alpha);
        if (HitboxesMod.glow > 0) {
            p.cube(-half, 0.0F, -half, half, top, half, width + HitboxesMod.glowSize * 0.5F,
                    NovaGui.alpha(box, HitboxesMod.glow / 100.0F * 0.25F), side);
        }
        p.cube(-half, 0.0F, -half, half, top, half, width, box, side);
        p.project(0.0F, top / 2.0F, 0.0F);
        float middle = p.pz;
        float eye = s.eyeHeight;
        if (HitboxesMod.showEye) {
            int c = NovaGui.alpha(0xFF000000 | HitboxesMod.eyeColor, alpha);
            p.edge(-half, eye, -half, half, eye, -half, width, c, side, middle);
            p.edge(half, eye, -half, half, eye, half, width, c, side, middle);
            p.edge(half, eye, half, -half, eye, half, width, c, side, middle);
            p.edge(-half, eye, half, -half, eye, -half, width, c, side, middle);
        }
        if (HitboxesMod.showLook) {
            double yaw = Math.toRadians(s.bodyRot + s.yRot);
            double pitch = Math.toRadians(s.xRot);
            float dx = (float) (-Math.sin(yaw) * Math.cos(pitch)) * 2.0F;
            float dy = (float) -Math.sin(pitch) * 2.0F;
            float dz = (float) (Math.cos(yaw) * Math.cos(pitch)) * 2.0F;
            p.edge(0.0F, eye, 0.0F, dx, eye + dy, dz, width + 0.5F, NovaGui.alpha(0xFF000000 | HitboxesMod.lookColor, alpha), side, middle);
        }
    }

    static void macro(ModPreview p) {
        String[] steps = {"Hold W", "Jump", "Chat: /home", "Wait 1s"};
        float ph = p.phase(3.2F);
        int current = Math.min(steps.length - 1, (int) (ph * steps.length));
        float within = ph * steps.length - current;
        int x0 = p.x0 + 12;
        int x1 = p.x1 - 12;
        int y = p.cy() - HudCards.stackHeight(steps.length) / 2;
        for (int i = 0; i < steps.length; i++) {
            int top = y + HudCards.top(i);
            boolean active = i == current;
            HudCards.card(p.g, x0, top, x1, active ? BRAND : i < current ? NovaGui.alpha(BRAND, 0.45F) : 0xFF5A5A64, p.a);
            NovaGui.text(p.g, p.font, steps[i], x0 + 8, top + HudCards.TEXT_Y, p.col(active ? TEXT : DIM), true);
            if (active) {
                HudCards.progress(p.g, x0, top, x1, within, BRAND, p.a);
            }
        }
    }

    // ================================================================ Misc

    static void autoTool(ModPreview p) {
        float ph = p.phase(2.6F);
        int cx = p.cx();
        int cy = p.y0 + 28;
        float aim = ease(window(ph, 0.05F, 0.28F));
        float dig = window(ph, 0.36F, 0.82F);
        boolean broken = ph > 0.82F;
        if (!broken) {
            p.item(STONE, cx, cy, 1.5F);
        }
        if (dig > 0.0F && !broken) {
            p.rect(cx - 16, cy + 16, cx + 16, cy + 18, 1, NovaGui.TRACK);
            p.rect(cx - 16, cy + 16, cx - 16 + Math.round(32 * dig), cy + 18, 1, BRAND);
        }
        p.burst(cx, cy, window(ph, 0.82F, 1.0F), 0xFF9AA0A8, 12, 20.0F);
        p.slots(cx - 39, p.y1 - 26, new ItemStack[]{SWORD, PICKAXE, AXE, SHOVEL}, ph > 0.32F ? 1 : 0);
        p.cursor(lerp(p.x1 - 12, cx + 3, aim), lerp(p.y0 + 10, cy + 3, aim));
    }

    static void fastPlace(ModPreview p) {
        float ph = p.phase(2.0F);
        p.scene(p.h() * 0.17F, p.cx(), p.y1 - 30, 0.55F, -0.5F);
        for (int i = 0; i < 5; i++) {
            float show = ease(window(ph, 0.06F + i * 0.08F, 0.12F + i * 0.08F)) * (1.0F - window(ph, 0.9F, 1.0F));
            p.block(Blocks.GRASS_BLOCK, i - 2.0F, 0.0F, 0.0F, show);
        }
        p.render();
        p.badge("No delay", p.cx(), p.y0 + 6, BRAND, shown(ph, 0.45F, 0.9F));
    }

    static void fastXp(ModPreview p) {
        AvatarRenderState s = hero(p, 25.0F);
        p.hold(s, me(), true, XP);
        Compat.previewSwing(s, (p.t % 0.24F) / 0.24F, SwingAnimationType.WHACK);
        p.render();
        for (int i = 0; i < 7; i++) {
            float k = (p.t * 0.9F + i / 7.0F) % 1.0F;
            float angle = i * 2.1F + p.t;
            p.project(cos(angle) * 0.6F, 0.2F + k * 1.6F, sin(angle) * 0.6F);
            int c = NovaGui.mix(0xFF9BFF3C, 0xFFF5E663, (i % 3) / 2.0F);
            p.glow(p.px, p.py, 5.0F, 5.0F, NovaGui.alpha(c, 0.5F * (1.0F - k)));
            p.disc(p.px, p.py, 1.6F, NovaGui.alpha(c, 1.0F - k));
        }
    }

    static void freecam(ModPreview p) {
        hero(p, 15.0F);
        p.render();
        float angle = p.t * 0.9F;
        p.project(cos(angle) * 1.3F, 1.9F + 0.25F * sin(angle * 2.0F), sin(angle) * 1.3F);
        float camX = p.px;
        float camY = p.py;
        p.project(0.0F, 1.62F, 0.0F);
        p.dashed(p.px, p.py, camX, camY, 0.8F, 2.5F, p.t, NovaGui.alpha(BRAND, 0.7F));
        int ix = Math.round(camX);
        int iy = Math.round(camY);
        p.rect(ix - 7, iy - 5, ix + 7, iy + 5, 3, 0xFF2A2F3A);
        p.outline(ix - 7, iy - 5, ix + 7, iy + 5, 3, BRAND);
        p.ring(ix, iy, 2.6F, 1.0F, TEXT);
    }

    static void freelook(ModPreview p) {
        float yaw = 1.1F * sin(p.t * 0.7F);
        p.scene(p.h() * 0.4F, p.cx(), p.y1 - 10, yaw, -0.12F);
        p.spot(0.0F, 0.95F, 0.0F, 1.15F);
        p.floor(0.0F, 0.0F, 0.5F, 0.6F);
        p.character(0.0F, 0.0F, 0.0F, 0.0F);
        p.render();
        p.badge("Camera turns, you don't", p.cx(), p.y0 + 6, BRAND, 1.0F);
    }

    static void boatFly(ModPreview p) {
        float lift = 0.55F + 0.18F * sin(p.t * 2.0F);
        p.scene(p.h() * 0.3F, p.cx(), p.y1 - 12, 0.0F, -0.15F);
        p.floor(0.0F, 0.0F, 0.8F, 0.35F);
        clouds(p, p.y1 - 22);
        p.thing(p.boat(), 0.0F, lift, 0.0F, 1.0F);
        AvatarRenderState s = p.character(0.0F, lift + 0.1F, 0.0F, -90.0F);
        s.isPassenger = true;
        p.render();
    }

    /** Wolken, die langsam nach links ziehen, auf Hoehe y. */
    private static void clouds(ModPreview p, int y) {
        int span = p.w() + 40;
        for (int i = 0; i < 3; i++) {
            float x = p.x1 + 20 - ((p.t * 14.0F + i * span / 3.0F) % span);
            p.glow(x, y - i * 6, 16.0F, 5.0F, NovaGui.alpha(0xFFFFFFFF, 0.22F));
        }
    }

    static void streamProtect(ModPreview p) {
        float ph = p.phase(2.6F);
        int x = p.cx() - 49;
        int y = p.cy() - 5;
        p.slots(x, y, new ItemStack[]{SWORD, TOTEM, PEARL, GAPPLE, DIAMOND}, -1);
        // Abdeckung schiebt sich von links ueber die Leiste und rechts wieder weg (deckend: nie halb Items, halb Text)
        int left = x - 3 + Math.round(104 * ease(window(ph, 0.82F, 0.97F)));
        int right = x - 3 + Math.round(104 * ease(window(ph, 0.25F, 0.42F)));
        if (right - left > 1) {
            // eigene Ebene, sonst laegen die Items ueber der Abdeckung
            p.g.nextStratum();
            int radius = Math.min(6, (right - left) / 2);
            p.rect(left, y - 3, right, y + 21, radius, 0xFF1D2028);
            p.outline(left, y - 3, right, y + 21, radius, NovaGui.BORDER_STRONG);
            float label = Math.min(window(ph, 0.4F, 0.5F), 1.0F - window(ph, 0.78F, 0.84F));
            int ex = p.cx() - 28;
            p.ring(ex, y + 9, 4.0F, 1.1F, NovaGui.alpha(DIM, label));
            p.line(ex - 5, y + 14, ex + 5, y + 4, 1.2F, NovaGui.alpha(DIM, label));
            p.text(NovaType.Face.CAPTION, "Hidden on stream", ex + 9, y + 6, NovaGui.alpha(DIM, label));
        }
    }

    static void sprint(ModPreview p) {
        p.scene(p.h() * 0.4F, p.cx(), p.y1 - 10, 0.0F, -0.12F);
        groundLines(p, 1.6F);
        p.floor(0.0F, 0.0F, 0.5F, 0.6F);
        AvatarRenderState s = p.character(0.0F, 0.0F, 0.0F, -90.0F);
        s.walkAnimationPos = p.t * 14.0F;
        s.walkAnimationSpeed = 1.0F;
        p.render();
        for (int i = 0; i < 4; i++) {
            float k = (p.t * 2.2F + i / 4.0F) % 1.0F;
            p.project(-0.3F - k * 0.9F, 0.05F + k * 0.2F, 0.0F);
            p.disc(p.px, p.py, 2.0F * (1.0F - k) + 0.5F, NovaGui.alpha(0xFFBFC6D1, 0.6F * (1.0F - k)));
        }
    }

    static void autoWalk(ModPreview p) {
        p.scene(p.h() * 0.4F, p.cx(), p.y1 - 10, 0.0F, -0.12F);
        groundLines(p, 0.8F);
        p.floor(0.0F, 0.0F, 0.5F, 0.6F);
        AvatarRenderState s = p.character(0.0F, 0.0F, 0.0F, -90.0F);
        s.walkAnimationPos = p.t * 8.0F;
        s.walkAnimationSpeed = 0.55F;
        p.render();
        key(p, "W", true);
    }

    /** Striche am Boden, die nach links ziehen (Bewegung nach rechts). */
    private static void groundLines(ModPreview p, float speed) {
        for (int i = 0; i < 6; i++) {
            float x = ((i * 0.9F - p.t * speed) % 5.4F + 5.4F) % 5.4F - 2.7F;
            p.project(x, 0.0F, 0.35F);
            float ax = p.px;
            float ay = p.py;
            p.project(x + 0.35F, 0.0F, 0.35F);
            p.line(ax, ay, p.px, p.py, 1.0F, NovaGui.alpha(0xFFFFFFFF, 0.14F));
        }
    }

    static void flight(ModPreview p) {
        p.scene(p.h() * 0.32F, p.cx(), p.y1 - 8, 0.0F, -0.12F);
        float lift = 0.85F + 0.12F * sin(p.t * 1.8F);
        p.floor(0.0F, 0.0F, 0.35F, 0.4F);
        clouds(p, p.y1 - 34);
        p.character(0.0F, lift, 0.0F, 20.0F);
        p.render();
    }

    static void autoReconnect(ModPreview p) {
        float ph = p.phase(3.4F);
        int x0 = p.x0 + 10;
        int x1 = p.x1 - 10;
        int y0 = p.cy() - 24;
        int y1 = p.cy() + 24;
        int cx = p.cx();
        p.panel(x0, y0, x1, y1);
        if (ph < 0.32F) {
            p.disc(cx, y0 + 12, 3.0F, BAD);
            p.centered(NovaType.Face.STRONG, "Disconnected", cx, y0 + 20, TEXT);
            p.centered(NovaType.Face.CAPTION, "Server restarting", cx, y0 + 33, DIM);
        } else if (ph < 0.72F) {
            float f = window(ph, 0.32F, 0.72F);
            p.centered(NovaType.Face.STRONG, "Reconnecting in " + (3 - Math.min(2, (int) (f * 3.0F))), cx, y0 + 14, TEXT);
            p.rect(x0 + 12, y0 + 32, x1 - 12, y0 + 34, 1, NovaGui.TRACK);
            p.rect(x0 + 12, y0 + 32, x0 + 12 + Math.round((x1 - x0 - 24) * f), y0 + 34, 1, BRAND);
        } else {
            p.disc(cx, y0 + 12, 3.0F, GOOD);
            p.centered(NovaType.Face.STRONG, "Connected", cx, y0 + 20, TEXT);
            p.centered(NovaType.Face.CAPTION, "Back in the game", cx, y0 + 33, DIM);
        }
    }

    static void spotify(ModPreview p) {
        int x0 = p.x0 + 8;
        int x1 = p.x1 - 8;
        int y0 = p.cy() - 24;
        int y1 = p.cy() + 24;
        p.panel(x0, y0, x1, y1);
        if (p.a > 0.5F) {
            SpotifyWidget.vinyl(p.g, x0 + 21, p.cy() - 4, 13, BRAND, p.a);
            SpotifyWidget.equalizer(p.g, x1 - 18, y0 + 17, 4, 2, 1, 8, NovaGui.alpha(BRAND, 0.6F), BRAND);
            SpotifyWidget.progressBar(p.g, x0 + 10, y1 - 8, x1 - 10, 2, (p.t * 0.04F + 0.3F) % 1.0F, BRAND, 0.0F);
        }
        p.text(NovaType.Face.STRONG, "Nova Radio", x0 + 40, y0 + 10, TEXT);
        p.text(NovaType.Face.CAPTION, "Lo-fi beats", x0 + 40, y0 + 23, DIM);
    }

    static void noFall(ModPreview p) {
        float ph = p.phase(2.4F);
        p.scene(p.h() * 0.3F, p.cx(), p.y1 - 10, 0.0F, -0.12F);
        float fall = window(ph, 0.0F, 0.38F);
        p.floor(0.0F, 0.0F, 0.25F + 0.25F * fall, 0.6F);
        p.character(0.0F, 1.3F * (1.0F - fall * fall), 0.0F, 20.0F);
        p.render();
        p.project(0.0F, 0.05F, 0.0F);
        p.burst(p.px, p.py, window(ph, 0.38F, 0.62F), 0xFFBFC6D1, 10, p.scale * 0.8F);
        p.hearts(p.x0 + 8, p.y0 + 8, 5, 5);
        p.badge("0 damage", p.x1 - 32, p.y0 + 7, GOOD, shown(ph, 0.42F, 0.95F));
    }

    static void fpsBoost(ModPreview p) {
        float ph = p.phase(3.0F);
        float k = ease(window(ph, 0.1F, 0.55F));
        p.centered(NovaType.Face.TITLE, Math.round(60 + 180 * k) + " FPS", p.cx(), p.y0 + 10, k >= 1.0F ? GOOD : TEXT);
        int gx0 = p.x0 + 12;
        int gx1 = p.x1 - 12;
        int gy = p.y1 - 10;
        float prevX = gx0;
        float prevY = gy - 6;
        for (int i = 1; i <= 12; i++) {
            float f = i / 12.0F;
            float x = lerp(gx0, gx1, f);
            float y = gy - 6 - 22.0F * Math.min(k, f) - 1.5F * sin(i * 1.7F + p.t * 3.0F);
            p.line(prevX, prevY, x, y, 1.2F, BRAND);
            prevX = x;
            prevY = y;
        }
    }

    static void playerDetector(ModPreview p) {
        float ph = p.phase(3.0F);
        duel(p, 3.2F);
        float walk = ease(window(ph, 0.05F, 0.5F));
        float x = lerp(4.6F, 2.0F, walk);
        p.floor(x, 0.0F, 0.45F, 0.5F);
        LivingEntityRenderState other = p.mob(p.stranger(), x, 0.0F, 0.0F, 90.0F);
        other.walkAnimationPos = p.t * 8.0F;
        other.walkAnimationSpeed = walk < 1.0F ? 0.7F : 0.0F;
        p.render();
        float alarm = shown(ph, 0.5F, 0.95F);
        if (alarm > 0.0F) {
            p.project(x, 1.0F, 0.0F);
            p.ring(p.px, p.py, p.scale * (0.8F + 0.3F * ((p.t * 2.0F) % 1.0F)), 1.2F, NovaGui.alpha(BAD, alarm));
        }
        p.badge("Disconnected", p.cx(), p.y0 + 6, BAD, alarm);
    }

    static void lootPreview(ModPreview p) {
        float ph = p.phase(3.0F);
        int ix = p.x0 + 22;
        int iy = p.cy();
        p.item(SHULKER, ix, iy, 1.5F);
        float open = ease(window(ph, 0.1F, 0.3F)) * (1.0F - window(ph, 0.88F, 1.0F));
        if (open > 0.01F) {
            int x0 = ix + 16;
            int y0 = iy - 31;
            p.rect(x0, y0, x0 + 62, y0 + 62, 6, NovaGui.alpha(NovaGui.PANEL, open));
            p.outline(x0, y0, x0 + 62, y0 + 62, 6, NovaGui.alpha(NovaGui.BORDER_STRONG, open));
            ItemStack[] loot = {DIAMOND, TOTEM, GAPPLE, PEARL, EMERALD, NETHERITE, XP, GOLD, ELYTRA};
            if (open > 0.6F) {
                for (int i = 0; i < loot.length; i++) {
                    p.item(loot[i], x0 + 4 + (i % 3) * 19, y0 + 4 + (i / 3) * 19);
                }
            }
        }
        p.cursor(ix + 5, iy + 6);
    }

    static void assistant(ModPreview p) {
        float ph = p.phase(3.4F);
        String command = ".toggle sprint";
        int typed = Math.min(command.length(), (int) (window(ph, 0.05F, 0.4F) * command.length()));
        int x = p.x0 + 6;
        int y = p.cy() - 12;
        p.chat(x, y, "> " + command.substring(0, typed) + (ph < 0.45F && NovaGui.blink() ? "_" : ""), 1.0F);
        p.chat(x, y + 12, "§9[Nova]§f Sprint enabled", shown(ph, 0.5F, 0.97F));
    }

    static void nameProtect(ModPreview p) {
        float ph = p.phase(3.0F);
        AvatarRenderState s = hero(p, 0.0F, 0.32F);
        p.render();
        String fake = NameProtectMod.fakeName.isEmpty() ? "Player" : NameProtectMod.fakeName;
        float swap = ease(window(ph, 0.35F, 0.5F)) * (1.0F - ease(window(ph, 0.88F, 1.0F)));
        p.project(0.0F, s.boundingBoxHeight + 0.35F, 0.0F);
        nameTag(p, myName(), p.px, p.py, 1.0F - swap);
        nameTag(p, fake, p.px, p.py, swap);
    }

    /** Namensschild wie in Minecraft (dunkles Feld, weisse Schrift) mittig ueber (x, y). */
    private static void nameTag(ModPreview p, String text, float x, float y, float show) {
        if (show <= 0.02F) {
            return;
        }
        int w = p.font.width(text);
        int ix = Math.round(x) - w / 2;
        int iy = Math.round(y) - 9;
        p.g.fill(ix - 2, iy - 1, ix + w + 2, iy + 9, p.col(0x40000000, show));
        p.g.text(p.font, text, ix, iy, p.col(0xFFFFFFFF, show), false);
    }

    /**
     * Trident Util: kurz aufladen (Dreizack zurueck), dann hebt der Spieler ohne Wasser ab - mit sauberen Streifen statt
     * des Riptide-Wirbels (der ist in der kleinen Vorschau nur Pixelrauschen).
     */
    static void trident(ModPreview p) {
        float ph = p.phase(2.4F);
        float charge = window(ph, 0.05F, 0.3F);
        float arc = window(ph, 0.32F, 0.92F);
        float lift = 1.2F * sin(arc * 3.14159F);
        p.scene(p.h() * 0.3F, p.cx(), p.y1 - 12, 0.0F, -0.12F);
        p.spot(0.0F, 0.95F + lift, 0.0F, 1.1F);
        p.floor(0.0F, 0.0F, 0.45F - 0.2F * lift / 1.2F, 0.55F);
        AvatarRenderState s = p.character(0.0F, lift, 0.0F, 25.0F);
        p.hold(s, me(), true, TRIDENT);
        ModPreview.armPose(s, true, charge > 0.0F && charge < 1.0F || arc > 0.0F && arc < 0.5F
                ? HumanoidModel.ArmPose.THROW_TRIDENT : HumanoidModel.ArmPose.ITEM);
        p.render();
        // aufsteigen: helle Streifen unter dem Spieler, die nach unten auslaufen
        float rise = arc > 0.0F && arc < 0.5F ? 1.0F - arc * 2.0F : 0.0F;
        if (rise > 0.0F) {
            for (int i = -1; i <= 1; i++) {
                p.project(i * 0.22F, lift - 0.1F, 0.0F);
                float top = p.py;
                p.project(i * 0.22F, Math.max(0.0F, lift - 0.9F - 0.2F * Math.abs(i)), 0.0F);
                p.line(p.px, top, p.px, p.py, 1.0F, NovaGui.alpha(0xFFBFE9FF, 0.7F * rise));
            }
        }
        p.badge("No water needed", p.cx(), p.y0 + 6, BRAND, 1.0F);
    }

    static void coordSnapper(ModPreview p) {
        float ph = p.phase(2.8F);
        p.landscape(p.x0 + 1, p.y0 + 1, p.x1 - 1, p.y1 - 1);
        int cx = p.cx();
        int cy = p.cy() - 8;
        p.item(DIAMOND_ORE, cx, cy, 1.25F);
        p.line(cx - 5, cy, cx + 5, cy, 1.0F, 0xFFFFFFFF);
        p.line(cx, cy - 5, cx, cy + 5, 1.0F, 0xFFFFFFFF);
        float chip = ease(window(ph, 0.3F, 0.42F)) * (1.0F - window(ph, 0.88F, 1.0F));
        if (chip > 0.01F) {
            String coords = "128 64 -256";
            int w = NovaType.width(p.font, NovaType.Face.MONO, coords) + 48;
            int y = p.y1 - 20 + Math.round((1.0F - chip) * 8.0F);
            p.rect(cx - w / 2, y, cx + w - w / 2, y + 14, 7, NovaGui.alpha(NovaGui.PANEL, chip));
            p.text(NovaType.Face.MONO, coords, cx - w / 2 + 7, y + 3, NovaGui.alpha(TEXT, chip));
            p.text(NovaType.Face.CAPTION, "Copied", cx + w - w / 2 - 34, y + 4, NovaGui.alpha(GOOD, chip));
        }
    }

    static void chatMacro(ModPreview p) {
        float ph = p.phase(2.4F);
        key(p, keyName(p, "G"), ph > 0.15F && ph < 0.3F);
        p.chat(p.x0 + 6, p.y1 - 18, "<" + myName() + "> gg wp", shown(ph, 0.3F, 0.95F));
    }

    static void discord(ModPreview p) {
        int x0 = p.x0 + 8;
        int x1 = p.x1 - 8;
        int y0 = p.cy() - 26;
        int y1 = p.cy() + 26;
        p.rect(x0, y0, x1, y1, 8, 0xFF232428);
        p.outline(x0, y0, x1, y1, 8, NovaGui.BORDER);
        p.rect(x0 + 8, y0 + 10, x0 + 38, y0 + 40, 7, NovaGui.mix(BRAND, 0xFF000000, 0.35F));
        p.centered(NovaType.Face.HEADING, "n", x0 + 23, y0 + 18, TEXT);
        p.text(NovaType.Face.STRONG, "Minecraft", x0 + 46, y0 + 10, TEXT);
        p.text(NovaType.Face.CAPTION, "Nova Client", x0 + 46, y0 + 23, DIM);
        int seconds = 724 + (int) p.t;
        p.text(NovaType.Face.MONO, String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60), x0 + 46, y0 + 34, MUTED);
    }

    // ================================================================ Donut

    static void chunkReload(ModPreview p) {
        float ph = p.phase(1.8F);
        int size = 14;
        int gap = 3;
        int gx = gridX(p, 5, size, gap);
        int gy = gridY(p, 3, size, gap);
        float flash = 1.0F - window(ph, 0.0F, 0.45F);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 5; c++) {
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, NovaGui.mix(CELL, NovaGui.mix(CELL, BRAND, 0.55F), flash));
            }
        }
        p.outline(gx + 2 * (size + gap) - 1, gy + size + gap - 1, gx + 2 * (size + gap) + size + 1, gy + 2 * size + gap + 1, 3, BRAND);
        p.text(NovaType.Face.MONO, "Y " + (-40 - (int) (p.t * 4.0F) % 24), p.x0 + 8, p.y0 + 7, DIM);
        p.badge("Reloaded", p.x1 - 32, p.y0 + 5, BRAND, flash);
    }


    static void auctionSniper(ModPreview p) {
        float ph = p.phase(2.8F);
        ItemStack[] stacks = {ELYTRA, NETHERITE, TOTEM};
        String[] prices = {"$900", "$380", "$650"};
        int x0 = p.x0 + 8;
        int x1 = p.x1 - 8;
        p.text(NovaType.Face.CAPTION, "Max $500", x0, p.y0 + 6, MUTED);
        float bought = window(ph, 0.4F, 0.5F);
        for (int i = 0; i < 3; i++) {
            int y = p.y0 + 20 + i * 22;
            boolean deal = i == 1 && bought > 0.0F;
            p.rect(x0, y, x1, y + 18, 5, deal ? NovaGui.alpha(GOOD, 0.22F) : 0xFF181B21);
            p.item(stacks[i], x0 + 1, y + 1);
            NovaType.right(p.g, p.font, NovaType.Face.MONO, prices[i], x1 - 6, y + 5, p.col(deal ? GOOD : i == 1 ? TEXT : DIM));
            if (deal) {
                p.text(NovaType.Face.CAPTION, "Bought", x0 + 22, y + 6, GOOD);
            }
        }
    }

    static void autoSell(ModPreview p) {
        float ph = p.phase(2.6F);
        ItemStack[] loot = {DIAMOND, EMERALD, GOLD, IRON};
        int coinX = p.x1 - 22;
        int coinY = p.cy() + 6;
        int sold = 0;
        for (int i = 0; i < loot.length; i++) {
            float f = ease(window(ph, 0.1F + i * 0.12F, 0.28F + i * 0.12F));
            if (f >= 1.0F) {
                sold++;
                continue;
            }
            float x = lerp(p.x0 + 18 + i * 18, coinX, f);
            float y = lerp(p.cy() + 6, coinY, f) - sin(f * 3.14159F) * 12.0F;
            p.item(loot[i], x, y, 1.0F);
        }
        p.disc(coinX, coinY, 9.0F, GOOD);
        p.centered(NovaType.Face.STRONG, "$", coinX, coinY - 4, 0xFF0B2A1A);
        p.text(NovaType.Face.MONO, "+$" + sold * 310, p.x0 + 10, p.y0 + 8, sold > 0 ? GOOD : DIM);
        p.text(NovaType.Face.CAPTION, "/sell", p.x1 - 34, p.y0 + 9, MUTED);
    }

    static void spawnerProtect(ModPreview p) {
        float ph = p.phase(3.0F);
        int sx = p.x0 + 26;
        int ex = p.x1 - 26;
        int y = p.cy() + 6;
        float danger = shown(ph, 0.08F, 0.5F);
        float secure = ease(window(ph, 0.45F, 0.7F));
        p.item(ENDER_CHEST, ex, y, 1.25F);
        if (danger > 0.0F) {
            p.ring(sx, y, 14.0F + 4.0F * ((p.t * 2.0F) % 1.0F), 1.2F, NovaGui.alpha(BAD, danger));
            p.text(NovaType.Face.CAPTION, "Player nearby", p.x0 + 8, p.y0 + 7, NovaGui.alpha(BAD, danger));
        }
        if (secure < 1.0F) {
            p.item(SPAWNER, lerp(sx, ex, secure), y - sin(secure * 3.14159F) * 14.0F, 1.25F * (1.0F - 0.4F * secure));
        }
        p.badge("Secured", p.cx(), p.y1 - 18, GOOD, shown(ph, 0.7F, 0.97F));
    }

    static void antiVanish(ModPreview p) {
        float ph = p.phase(3.0F);
        int x0 = p.x0 + 10;
        int x1 = p.x1 - 10;
        int y0 = p.cy() - 30;
        p.panel(x0, y0, x1, y0 + 60);
        String[] names = {"Steve", "Alex", "Notch"};
        for (int i = 0; i < names.length; i++) {
            p.vanilla(names[i], x0 + 7, y0 + 6 + i * 13, 0xFFFFFFFF);
        }
        float reveal = shown(ph, 0.35F, 0.95F);
        if (reveal > 0.0F) {
            p.g.text(p.font, "Mod_Kai", x0 + 7, y0 + 45, p.col(0xFF9AA0AA, reveal), true);
            int w = NovaType.width(p.font, NovaType.Face.CAPTION, "vanished") + 10;
            p.rect(x1 - 6 - w, y0 + 44, x1 - 6, y0 + 54, 5, NovaGui.alpha(NovaGui.alpha(BAD, 0.3F), reveal));
            p.text(NovaType.Face.CAPTION, "vanished", x1 - 1 - w, y0 + 46, NovaGui.alpha(0xFFFFB3B0, reveal));
        }
    }

    static void fakeMedia(ModPreview p) {
        AvatarRenderState s = hero(p, 0.0F, 0.32F);
        p.render();
        p.project(0.0F, s.boundingBoxHeight + 0.35F, 0.0F);
        String name = myName();
        int nameW = p.font.width(name);
        int badgeW = NovaType.width(p.font, NovaType.Face.CAPTION, "MEDIA") + 8;
        int x = Math.round(p.px) - (nameW + 3 + badgeW) / 2;
        int y = Math.round(p.py) - 9;
        p.g.fill(x - 2, y - 1, x + nameW + 3 + badgeW + 2, y + 9, p.col(0x40000000));
        p.g.text(p.font, name, x, y, p.col(0xFFFFFFFF), false);
        p.rect(x + nameW + 3, y - 1, x + nameW + 3 + badgeW, y + 9, 4, 0xFFFF4F9A);
        p.text(NovaType.Face.CAPTION, "MEDIA", x + nameW + 7, y + 1, 0xFFFFFFFF);
    }

    static void tpaHere(ModPreview p) {
        float every = 0.8F;
        int sent = (int) (p.t / every);
        float local = (p.t % every) / every;
        for (int i = 0; i < 3 && sent - i >= 0; i++) {
            p.chat(p.x0 + 6, p.y1 - 16 - i * 12, "/tpahere Steve", i == 0 ? ease(Math.min(1.0F, local * 4.0F)) : 1.0F - i * 0.3F);
        }
        float angle = local * 6.2832F - 1.5708F;
        p.ring(p.x1 - 14, p.y0 + 14, 6.0F, 1.2F, NovaGui.TRACK);
        p.disc(p.x1 - 14 + cos(angle) * 6.0F, p.y0 + 14 + sin(angle) * 6.0F, 1.8F, BRAND);
    }

    static void scoreboard(ModPreview p) {
        Objective objective = ScoreboardMod.sidebarObjective(Minecraft.getInstance());
        if (objective != null) {
            ScoreboardMod.draw(p.g, p.font, objective, p.x1 - ModPreview.PAD, p.cy());
        }
    }

    static void regionMap(ModPreview p) {
        p.measured(RegionMapMod.drawPreview(p.g, p.font, p.hudX(), p.hudY()));
    }

    // ================================================================ Base Finding

    static void susChunk(ModPreview p) {
        int size = 13;
        int gap = 3;
        int gx = gridX(p, 6, size, gap);
        int gy = gridY(p, 4, size, gap);
        float pulse = 0.5F + 0.5F * sin(p.t * 4.0F);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 6; c++) {
                boolean flagged = c == 3 && r == 1;
                int color = flagged ? NovaGui.mix(0xFF2A2440, 0xFFB388FF, 0.35F + 0.35F * pulse)
                        : NovaGui.mix(CELL, CELL_LIGHT, noise(c, r));
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, color);
            }
        }
        p.item(AMETHYST, gx + 3 * (size + gap) + size / 2.0F, gy + (size + gap) + size / 2.0F, 0.6F);
        p.badge("Suspicious chunk", p.cx(), p.y0 + 5, 0xFFB388FF, 1.0F);
    }

    static void holeEsp(ModPreview p) {
        p.scene(p.h() * 0.19F, p.cx(), p.cy() + 12, 0.6F, -0.75F);
        for (int y = -2; y <= 0; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || z != 0) {
                        p.block(y == 0 ? Blocks.GRASS_BLOCK : Blocks.STONE, x, y, z);
                    }
                }
            }
        }
        p.render();
        float pulse = 0.65F + 0.35F * sin(p.t * 3.0F);
        p.frame(-0.5F, -2.0F, -0.5F, 0.5F, 1.0F, 0.5F, 1.2F, NovaGui.alpha(BRAND, pulse), 0.3F);
        p.badge("1x1 hole", p.cx(), p.y0 + 5, BRAND, 1.0F);
    }

    static void stashFinder(ModPreview p) {
        float ph = p.phase(3.0F);
        int size = 16;
        int gap = 3;
        int gx = gridX(p, 5, size, gap);
        int gy = gridY(p, 3, size, gap);
        int[] counts = {0, 2, 1, 0, 3, 1, 0, 48, 2, 0, 0, 1, 0, 0, 2};
        float found = window(ph, 0.35F, 0.45F);
        for (int i = 0; i < counts.length; i++) {
            int x = gx + (i % 5) * (size + gap);
            int y = gy + (i / 5) * (size + gap);
            boolean stash = counts[i] > 10;
            p.cell(x, y, size, stash ? NovaGui.mix(CELL, 0xFF8A6A1C, found) : CELL);
            if (stash && found >= 1.0F) {
                p.item(CHEST, x + size / 2.0F, y + size / 2.0F, 0.7F);
            } else {
                p.centered(NovaType.Face.CAPTION, String.valueOf(counts[i]), x + size / 2, y + 5, counts[i] > 0 ? DIM : MUTED);
            }
        }
        p.badge("Stash · 48 chests", p.cx(), p.y0 + 5, GOLD_GLOW, shown(ph, 0.45F, 0.95F));
    }

    static void tunnel(ModPreview p) {
        float ph = p.phase(3.2F);
        int cols = 8;
        int rows = 4;
        int size = 11;
        int gap = 1;
        int gx = gridX(p, cols, size, gap);
        int gy = gridY(p, rows, size, gap);
        int dug = (int) (window(ph, 0.05F, 0.9F) * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int color = r == rows - 1 ? 0xFF2B2B2E : NovaGui.mix(0xFF4E535C, 0xFF5E636C, noise(c, r));
                if (r == rows - 2 && c < dug) {
                    color = 0xFF0E1014;
                }
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, color);
            }
        }
        p.item(PICK_NETHERITE, gx + Math.min(dug, cols - 1) * (size + gap) + size / 2.0F, gy + (rows - 2) * (size + gap) + size / 2.0F, 0.7F);
        p.text(NovaType.Face.CAPTION, "Bedrock", gx, gy + rows * (size + gap) + 2, MUTED);
    }

    static void seedFinder(ModPreview p) {
        float ph = p.phase(3.2F);
        int known = (int) (window(ph, 0.1F, 0.8F) * SEED.length());
        StringBuilder text = new StringBuilder(SEED.length());
        for (int i = 0; i < SEED.length(); i++) {
            text.append(i < known ? SEED.charAt(i) : (char) ('0' + (int) ((p.t * 23.0F + i * 7) % 10)));
        }
        p.text(NovaType.Face.CAPTION, "World seed", p.x0 + 10, p.cy() - 16, MUTED);
        p.text(NovaType.Face.MONO, text.toString(), p.x0 + 10, p.cy() - 2, known >= SEED.length() ? GOOD : TEXT);
    }

    static void activity(ModPreview p) {
        float ph = p.phase(3.0F);
        int cols = 9;
        int rows = 5;
        int size = 10;
        int gap = 1;
        int gx = gridX(p, cols, size, gap);
        int gy = gridY(p, rows, size, gap);
        int scan = (int) (window(ph, 0.05F, 0.85F) * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean dug = r == 2 && c >= 1 && c <= 7 || c == 6 && r <= 2;
                int color = NovaGui.mix(CELL, CELL_LIGHT, noise(c, r) * 0.6F);
                if (dug && c <= scan) {
                    color = NovaGui.mix(0xFF5A4A1E, 0xFFFFC857, 0.5F);
                }
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, color);
            }
        }
        p.text(NovaType.Face.CAPTION, "Light data", p.x0 + 8, p.y0 + 5, MUTED);
    }

    static void primeChunk(ModPreview p) {
        int size = 13;
        int gap = 3;
        int gx = gridX(p, 6, size, gap);
        int gy = gridY(p, 4, size, gap);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 6; c++) {
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, NovaGui.mix(CELL, CELL_LIGHT, noise(c + 3, r)));
            }
        }
        float x = gx + 4 * (size + gap) + size / 2.0F;
        float y = gy + 2 * (size + gap) + size / 2.0F;
        float k = (p.t * 0.8F) % 1.0F;
        p.disc(x, y, 3.0F, BRAND);
        p.ring(x, y, 4.0F + 10.0F * k, 1.2F, NovaGui.alpha(BRAND, 1.0F - k));
        p.text(NovaType.Face.CAPTION, "Y < 0  ·  light changed", p.x0 + 8, p.y0 + 5, MUTED);
    }

    static void playerBypass(ModPreview p) {
        float ph = p.phase(3.2F);
        int cols = 8;
        int rows = 5;
        int size = 10;
        int gap = 1;
        int gx = gridX(p, cols, size, gap);
        int gy = gridY(p, rows, size, gap);
        float clean = ease(window(ph, 0.3F, 0.5F)) * (1.0F - window(ph, 0.9F, 1.0F));
        int[] ores = {0xFF55D6E6, 0xFFE6C255, 0xFFB0B8C4, 0xFF3E8F52};
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                boolean built = c >= 3 && c <= 5 && r >= 1 && r <= 2;
                float n = noise(c * 3 + 1, r * 5 + 2);
                int fake = n > 0.72F ? ores[(int) (n * 37) % 4] : 0xFF4E535C;
                int color = built ? NovaGui.mix(fake, 0xFFB98A52, clean) : NovaGui.mix(fake, 0xFF4E535C, clean);
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, color);
            }
        }
        if (clean > 0.5F) {
            p.outline(gx + 3 * (size + gap) - 2, gy + (size + gap) - 2, gx + 6 * (size + gap) + 1, gy + 3 * (size + gap) + 1, 3, BRAND);
        }
        p.badge(clean > 0.5F ? "Built by a player" : "Anti-xray noise", p.cx(), p.y0 + 5, clean > 0.5F ? BRAND : MUTED, 1.0F);
    }

    static void netherite(ModPreview p) {
        int size = 13;
        int gap = 3;
        int gx = gridX(p, 6, size, gap);
        int gy = gridY(p, 4, size, gap);
        float pulse = 0.5F + 0.5F * sin(p.t * 3.0F);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 6; c++) {
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, NovaGui.mix(0xFF2A1B1B, 0xFF3A2424, noise(c, r + 7)));
            }
        }
        int x = gx + 2 * (size + gap);
        int y = gy + 2 * (size + gap);
        p.outline(x - 2, y - 2, x + size + 2, y + size + 2, 3, NovaGui.alpha(0xFFE08A5A, 0.6F + 0.4F * pulse));
        p.item(DEBRIS, x + size / 2.0F, y + size / 2.0F, 0.65F);
        p.badge("Ancient debris", p.cx(), p.y0 + 5, 0xFFE08A5A, 1.0F);
    }

    static void quantum(ModPreview p) {
        float ph = p.phase(3.0F);
        int size = 9;
        int gap = 2;
        int left = p.x0 + 12;
        int right = p.x1 - 12 - 4 * (size + gap) + gap;
        int y = gridY(p, 4, size, gap);
        float diff = window(ph, 0.35F, 0.5F);
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int base = NovaGui.mix(CELL, CELL_LIGHT, noise(c, r + 11));
                p.cell(left + c * (size + gap), y + r * (size + gap), size, base);
                boolean changed = c == 2 && r == 1;
                p.cell(right + c * (size + gap), y + r * (size + gap), size, changed ? NovaGui.mix(base, 0xFF55D6E6, diff) : base);
            }
        }
        p.text(NovaType.Face.CAPTION, "Before", left, y - 12, MUTED);
        p.text(NovaType.Face.CAPTION, "After", right, y - 12, MUTED);
        p.line(p.cx() - 6, p.cy() + 4, p.cx() + 6, p.cy() + 4, 1.2F, DIM);
        if (diff >= 1.0F) {
            p.ring(right + 2 * (size + gap) + size / 2.0F, y + (size + gap) + size / 2.0F, 7.0F, 1.2F, BRAND);
        }
    }

    static void amethyst(ModPreview p) {
        float ph = p.phase(3.0F);
        int cols = 11;
        int rows = 7;
        int size = 8;
        int gap = 1;
        int gx = gridX(p, cols, size, gap);
        int gy = gridY(p, rows, size, gap);
        float reveal = ease(window(ph, 0.3F, 0.5F)) * (1.0F - window(ph, 0.9F, 1.0F));
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float d = (float) Math.hypot(c - 5, (r - 3) * 1.1F);
                boolean shell = d > 1.6F && d < 3.0F;
                int stone = NovaGui.mix(0xFF4E535C, 0xFF5E636C, noise(c + 20, r));
                p.cell(gx + c * (size + gap), gy + r * (size + gap), size, shell ? NovaGui.mix(stone, 0xFF9A6BE0, reveal) : stone);
            }
        }
        if (reveal > 0.5F) {
            p.item(AMETHYST, p.cx(), gy + 3 * (size + gap) + size / 2.0F, 0.7F);
        }
    }

    static void blockEntityDebug(ModPreview p) {
        p.scene(p.h() * 0.2F, p.cx(), p.cy() + 18, 0.6F, -0.45F);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 0; z++) {
                p.block(Blocks.STONE, x, 0.0F, z);
            }
        }
        p.block(chest(), -1.0F, 1.0F, 0.0F, 1.0F);
        p.block(Blocks.SPAWNER, 1.0F, 1.0F, 0.0F);
        p.render();
        mark(p, -1.0F, 1.0F, 0.0F, true, 0xFF000000 | StorageEspMod.Type.CHEST.color);
        mark(p, 1.0F, 1.0F, 0.0F, false, 0xFF000000 | StorageEspMod.Type.SPAWNER.color);
    }

    /** Kiste mit dem Schloss zum Betrachter (ohne Drehung der Szene zeigt +z nach vorne). */
    private static BlockState chest() {
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
    }

    /** ESP-Markierung eines Blocks (Unterseite bei y, mittig ueber x/z): leichte Fuellung, Kanten, verdeckte schwach. */
    private static void mark(ModPreview p, float x, float y, float z, boolean chest, int color) {
        // Kisten sind kleiner als ein ganzer Block (1/16 Rand, 14/16 hoch)
        float inset = chest ? 0.0625F : 0.0F;
        float top = chest ? 0.875F : 1.0F;
        p.faces(x - 0.5F + inset, y, z - 0.5F + inset, x + 0.5F - inset, y + top, z + 0.5F - inset, NovaGui.alpha(color, 0.16F));
        p.frame(x - 0.5F + inset, y, z - 0.5F + inset, x + 0.5F - inset, y + top, z + 0.5F - inset, 1.1F, color, 0.3F);
    }


    // ================================================================ Render

    static void fullbright(ModPreview p) {
        float ph = p.phase(3.0F);
        meadow(p);
        float dark = 1.0F - ease(window(ph, 0.35F, 0.55F)) * (1.0F - window(ph, 0.9F, 1.0F));
        p.rect(p.x0, p.y0, p.x1, p.y1, 9, NovaGui.alpha(0xFF05070C, 0.85F * dark));
        p.badge(dark < 0.5F ? "Fullbright" : "Night", p.cx(), p.y0 + 5, dark < 0.5F ? GOLD_GLOW : MUTED, 1.0F);
    }

    static void clearLiquids(ModPreview p) {
        float ph = p.phase(3.0F);
        meadow(p);
        float fog = 1.0F - ease(window(ph, 0.35F, 0.55F)) * (1.0F - window(ph, 0.9F, 1.0F));
        p.rect(p.x0, p.y0, p.x1, p.y1, 9, NovaGui.alpha(0xFF1A4C9E, 0.75F * fog));
        for (int i = 0; i < 5; i++) {
            float k = (p.t * 0.5F + i / 5.0F) % 1.0F;
            p.ring(p.x0 + 14 + i * 20, p.y1 - 8 - k * (p.h() - 16), 1.8F, 0.8F, NovaGui.alpha(0xFFCFE6FF, 0.5F * fog * (1.0F - k)));
        }
        p.badge(fog < 0.5F ? "Clear view" : "Underwater", p.cx(), p.y0 + 5, fog < 0.5F ? GOOD : 0xFF7FB2FF, 1.0F);
    }

    static void esp(ModPreview p) {
        p.scene(p.h() * 0.26F, p.cx(), p.y1 - 16, 0.0F, -0.2F);
        for (int x = -2; x <= 2; x++) {
            p.block(Blocks.STONE_BRICKS, x, 0.0F, 1.3F);
        }
        p.mob(p.stranger(), -0.9F, 0.0F, 0.0F, 20.0F);
        p.mob(p.zombie(), 0.9F, 0.0F, 0.0F, -20.0F);
        p.render();
        espMark(p, -0.9F, 0xFF000000 | EspMod.PLAYER_COLOR);
        espMark(p, 0.9F, 0xFF000000 | EspMod.HOSTILE_COLOR);
        p.project(-0.9F, 2.35F, 0.0F);
        p.centered(NovaType.Face.CAPTION, "Steve 12m", Math.round(p.px), Math.round(p.py), 0xFF000000 | EspMod.PLAYER_COLOR);
    }

    /** Leuchten und Tracer eines markierten Wesens bei x (durch die Wand sichtbar). */
    private static void espMark(ModPreview p, float x, int color) {
        p.project(x, 1.0F, 0.0F);
        p.glow(p.px, p.py, p.scale * 0.55F, p.scale * 1.1F, NovaGui.alpha(color, 0.38F));
        p.line(p.cx(), p.y1 - 2, p.px, p.py, 0.9F, NovaGui.alpha(color, 0.7F));
    }

    static void blockEsp(ModPreview p) {
        p.scene(p.h() * 0.2F, p.cx(), p.cy() + 16, 0.6F, -0.5F);
        // Steinstufe, das Erz vorne in der Mitte (Oberseite und Front frei, links/rechts/hinten Stein)
        for (int x = -1; x <= 1; x++) {
            p.block(x == 0 ? Blocks.DIAMOND_ORE : Blocks.STONE, x, 0.0F, 0.0F);
            p.block(Blocks.STONE, x, 0.0F, -1.0F);
            p.block(Blocks.STONE, x, 1.0F, -1.0F);
        }
        p.render();
        int color = BlockEspMod.colorOf(Blocks.DIAMOND_ORE);
        p.project(0.0F, 0.5F, 0.0F);
        p.glow(p.px, p.py, p.scale * 0.9F, p.scale * 0.9F, NovaGui.alpha(color, 0.3F));
        // Rahmen um das Erz; die Kanten im Stein (durch die Waende) schwach
        int covered = ModPreview.WEST | ModPreview.EAST | ModPreview.NORTH | ModPreview.DOWN;
        p.faces(-0.5F, 0.0F, -0.5F, 0.5F, 1.0F, 0.5F, NovaGui.alpha(color, 0.18F), covered);
        p.frame(-0.5F, 0.0F, -0.5F, 0.5F, 1.0F, 0.5F, 1.2F, color, 0.35F, covered);
    }

    static void storageEsp(ModPreview p) {
        p.scene(p.h() * 0.24F, p.cx(), p.cy() + 18, 0.5F, -0.45F);
        p.block(chest(), -1.2F, 0.0F, 0.0F, 1.0F);
        p.block(Blocks.BARREL, 0.0F, 0.0F, 0.0F);
        p.block(Blocks.SHULKER_BOX, 1.2F, 0.0F, 0.0F);
        p.render();
        StorageEspMod.Type[] types = {StorageEspMod.Type.CHEST, StorageEspMod.Type.BARREL, StorageEspMod.Type.SHULKER_BOX};
        for (int i = 0; i < 3; i++) {
            mark(p, (i - 1) * 1.2F, 0.0F, 0.0F, i == 0, 0xFF000000 | types[i].color);
        }
    }

    /** Toast gleitet herein, bleibt und gleitet wieder hinaus (wie im Spiel). */
    private static void toast(ModPreview p, String title, String badge, String where, List<ItemStack> icons) {
        float ph = p.phase(3.2F);
        float show = ease(window(ph, 0.05F, 0.2F)) * (1.0F - ease(window(ph, 0.86F, 1.0F)));
        int x = p.cx() - NovaToasts.CARD_W / 2 + Math.round((1.0F - show) * 30.0F);
        p.toast(x, p.cy() - NovaToasts.CARD_H / 2, show, title, badge, where, icons);
    }

    static void blockNotifier(ModPreview p) {
        toast(p, "Diamond Ore", "x4", "18 m  ·  120 -58 64", List.of(DIAMOND_ORE));
    }

    static void spawnerNotifier(ModPreview p) {
        toast(p, "Spawner", "Zombie", "34 m  ·  88 -20 -140", List.of(SPAWNER));
    }

    static void weatherNotifier(ModPreview p) {
        toast(p, "Rain is coming", "in 12s", "Weather", List.of(new ItemStack(Items.WATER_BUCKET)));
    }

    static void mobEsp(ModPreview p) {
        TargetEspMod mod = (TargetEspMod) p.mod;
        LivingEntity target = mod instanceof TraderEspMod ? p.trader() : mod instanceof VillagerEspMod ? p.villager() : p.pillager();
        int color = 0xFF000000 | mod.color();
        p.scene(p.h() * 0.34F, p.cx(), p.y1 - 12, 0.5F, -0.15F);
        p.floor(0.0F, 0.0F, 0.5F, 0.55F);
        float half = target.getBbWidth() / 2.0F + 0.02F;
        float top = target.getBbHeight();
        // hintere Kanten hinter dem Mob, vordere davor
        p.cube(-half, 0.0F, -half, half, top, half, 1.2F, NovaGui.alpha(color, 0.6F), -1);
        p.mob(target, 0.0F, 0.0F, 0.0F, ModPreview.facing(0.5F) + 15.0F);
        p.render();
        p.project(0.0F, top * 0.5F, 0.0F);
        p.line(p.cx(), p.y1 - 2, p.px, p.py, 0.9F, NovaGui.alpha(color, 0.6F));
        p.cube(-half, 0.0F, -half, half, top, half, 1.2F, color, 1);
    }

    static void collectibleEsp(ModPreview p) {
        float pulse = 0.5F + 0.5F * sin(p.t * 3.0F);
        int cx = p.cx();
        int cy = p.cy();
        int color = 0xFFFFC857;
        p.glow(cx, cy, 32.0F, 32.0F, NovaGui.alpha(color, 0.2F * pulse));
        p.item(FRAME, cx, cy, 2.4F);
        p.item(DIAMOND, cx, cy, 1.1F);
        p.outline(cx - 22, cy - 22, cx + 22, cy + 22, 6, NovaGui.alpha(color, 0.55F + 0.45F * pulse));
    }

    static void vineEsp(ModPreview p) {
        int size = 12;
        int x = p.cx() - size / 2;
        int y0 = p.y0 + 10;
        for (int i = 0; i < 6; i++) {
            int y = y0 + i * size;
            p.cell(x - size - 1, y, size, NovaGui.mix(0xFF4E535C, 0xFF5E636C, noise(1, i)));
            p.cell(x + size + 1, y, size, NovaGui.mix(0xFF4E535C, 0xFF5E636C, noise(3, i)));
            p.item(VINE, x + size / 2.0F, y + size / 2.0F, 0.72F);
        }
        float pulse = 0.6F + 0.4F * sin(p.t * 3.0F);
        p.outline(x - 2, y0 - 2, x + size + 2, y0 + 6 * size + 2, 3, NovaGui.alpha(GOOD, pulse));
        p.text(NovaType.Face.CAPTION, "14 long", x + 2 * size + 6, p.cy() - 3, GOOD);
    }

    static void kelpEsp(ModPreview p) {
        int size = 16;
        int gap = 3;
        int gx = gridX(p, 5, size, gap);
        int gy = gridY(p, 3, size, gap);
        float pulse = 0.6F + 0.4F * sin(p.t * 3.0F);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 5; c++) {
                int x = gx + c * (size + gap);
                int y = gy + r * (size + gap);
                p.cell(x, y, size, NovaGui.mix(0xFF16314F, 0xFF1E4068, noise(c, r)));
                if (noise(c + 5, r + 2) > 0.45F) {
                    p.item(KELP, x + size / 2.0F, y + size / 2.0F, 0.7F);
                }
            }
        }
        int fx = gx + 3 * (size + gap);
        int fy = gy + (size + gap);
        p.outline(fx - 2, fy - 2, fx + size + 2, fy + size + 2, 3, NovaGui.alpha(GOOD, pulse));
        p.badge("Fully grown", p.cx(), p.y0 + 5, GOOD, 1.0F);
    }

    static void nameTags(ModPreview p) {
        p.scene(p.h() * 0.5F / 1.8F, p.cx(), p.y1 - 10, 0.0F, -0.12F);
        p.spot(0.0F, 0.95F, 0.0F, 1.15F);
        p.floor(0.0F, 0.0F, 0.5F, 0.6F);
        AvatarRenderState s = p.character(0.0F, 0.0F, 0.0F, 15.0F);
        p.render();
        p.project(0.0F, s.boundingBoxHeight + 0.5F, 0.0F);
        p.g.pose().pushMatrix();
        p.g.pose().translate(Math.round(p.px), Math.round(p.py));
        NameTagsMod.drawPreview(p.g, p.font, me());
        p.g.pose().popMatrix();
    }

    static void waypoints(ModPreview p) {
        p.landscape(p.x0 + 1, p.y0 + 1, p.x1 - 1, p.y1 - 1);
        float bob = 2.0F * sin(p.t * 2.0F);
        int cx = p.cx();
        int y = p.cy() - 10 + Math.round(bob);
        p.line(cx, y + 8, cx, p.y1 - 22, 1.0F, NovaGui.alpha(BRAND, 0.5F));
        NovaShapes.lens(p.g, cx, y, 10.0F, 14.0F, p.col(BRAND));
        p.disc(cx, y, 2.0F, 0xFFFFFFFF);
        p.centered(NovaType.Face.STRONG, "Base", cx, y - 22, TEXT);
        p.centered(NovaType.Face.MONO, "142 m", cx, y + 12, DIM);
    }

    static void xray(ModPreview p) {
        float ph = p.phase(3.2F);
        boolean see = ph > 0.42F && ph < 0.92F;
        p.scene(p.h() * 0.17F, p.cx(), p.cy() + 14, 0.6F, -0.5F);
        for (int y = 0; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    Block ore = y == 0 && x == 0 && z == 0 ? Blocks.DIAMOND_ORE
                            : y == 1 && x == 1 && z == -1 ? Blocks.GOLD_ORE
                            : y == 0 && x == -1 && z == 1 ? Blocks.IRON_ORE
                            : y == 1 && x == -1 && z == -1 ? Blocks.REDSTONE_ORE : null;
                    if (ore != null) {
                        p.block(ore, x, y, z);
                    } else if (!see) {
                        p.block(Blocks.STONE, x, y, z);
                    }
                }
            }
        }
        p.render();
        p.badge("X-ray", p.cx(), p.y0 + 5, BRAND, see ? 1.0F : 0.0F);
    }

    // ================================================================ Visuals

    /** HUD-Karte im Stil des Moduls (HudInfo, Adminlist), mittig auf der Buehne. */
    private static void hudStyle(ModPreview p, HudStyle style, List<HudStyle.Row> rows, int headerRows) {
        int w = style.width(p.font, rows);
        int h = style.height(rows, headerRows);
        int x = p.cx() - w / 2;
        int y = p.cy() - h / 2;
        p.g.pose().pushMatrix();
        p.g.pose().translate(x, y);
        style.draw(p.g, p.font, rows, headerRows, p.a);
        p.g.pose().popMatrix();
        p.measured(new Rect(x, y, x + w, y + h));
    }

    static void hudInfo(ModPreview p) {
        hudStyle(p, HudInfoMod.style, HudInfoMod.previewRows(), 0);
    }

    static void adminList(ModPreview p) {
        hudStyle(p, AdminListMod.style, AdminListMod.previewRows(), 1);
    }

    static void potionHud(ModPreview p) {
        p.measured(PotionHudMod.drawPreview(p.g, p.font, p.hudX(), p.hudY(), p.a));
    }

    static void hotkeys(ModPreview p) {
        p.measured(HotkeyListMod.drawPreview(p.g, p.font, p.hudX(), p.hudY(), p.a));
    }

    static void radar(ModPreview p) {
        p.measured(RadarMod.drawPreview(p.g, p.font, p.hudX(), p.hudY(), p.a));
    }

    static void targetHud(ModPreview p) {
        p.measured(TargetHudMod.drawPreview(p.g, p.font, p.hudX(), p.hudY(), p.a));
    }

    static void novaPlus(ModPreview p) {
        p.measured(NovaPlusMod.drawPreview(p.g, p.font, p.hudX(), p.hudY(), p.a));
    }

    static void armourView(ModPreview p) {
        p.measured(ArmourViewMod.drawPreview(p.g, p.font, p.hudX(), p.hudY()));
    }

    static void lowHealth(ModPreview p) {
        int x0 = p.x0 + ModPreview.PAD;
        int y0 = p.y0 + ModPreview.PAD;
        int x1 = p.x1 - ModPreview.PAD;
        int y1 = p.y1 - ModPreview.PAD;
        p.landscape(x0, y0, x1, y1);
        LowHealthWarningMod.drawPreview(p.g, x0, y0, x1, y1, p.t, p.a);
        p.outline(x0, y0, x1, y1, 2, NovaGui.BORDER);
    }

    static void customFont(ModPreview p) {
        CustomFonts.Entry entry = CustomFontMod.current();
        if (entry != fontEntry) {
            fontEntry = entry;
            Style style = Style.EMPTY.withFont(new FontDescription.Resource(
                    Identifier.fromNamespaceAndPath("nova_client", entry.spec().name())));
            fontLine1 = Component.literal("<" + myName() + "> gg").setStyle(style);
            fontLine2 = Component.literal("Aa Bb Cc 123").setStyle(style);
        }
        int w = Math.max(p.font.width(fontLine1), p.font.width(fontLine2)) + 8;
        int x = p.cx() - w / 2;
        int y = p.cy() - 12;
        p.g.fill(x, y, x + w, y + 24, p.col(0x80000000));
        p.g.text(p.font, fontLine1, x + 4, y + 3, p.col(0xFFFFFFFF), true);
        p.g.text(p.font, fontLine2, x + 4, y + 13, p.col(0xFFFFFFFF), true);
        p.measured(new Rect(x, y, x + w, y + 24));
    }

    static void crosshair(ModPreview p) {
        p.landscape(p.x0 + 1, p.y0 + 1, p.x1 - 1, p.y1 - 1);
        int guiScale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        if (CrosshairMod.style == CrosshairMod.Style.DEFAULT) {
            if (p.a > 0.5F) {
                p.g.blitSprite(RenderPipelines.GUI_TEXTURED, CROSSHAIR, p.cx() - 7, p.cy() - 7, 15, 15);
            }
        } else {
            // doppelt so gross wie im Spiel, damit man die Form erkennt
            CrosshairMod.draw(p.g, p.cx(), p.cy(), 2.0F / guiScale);
        }
    }

    static void timeChanger(ModPreview p) {
        float ph = p.phase(8.0F);
        float day = 0.5F + 0.5F * cos(ph * 6.2832F);
        int top = NovaGui.mix(0xFF070B1A, 0xFF3E77C2, day);
        int bottom = NovaGui.mix(0xFF141B33, NovaGui.mix(0xFFE58A4A, 0xFF9CC4EA, day), Math.min(1.0F, 0.4F + day));
        int ground = p.y1 - 18;
        p.g.fillGradient(p.x0 + 1, p.y0 + 1, p.x1 - 1, ground, p.col(top), p.col(bottom));
        p.g.fill(p.x0 + 1, ground, p.x1 - 1, p.y1 - 1, p.col(NovaGui.mix(0xFF16210F, 0xFF3F6B2C, day)));
        float angle = ph * 6.2832F;
        float sunX = p.cx() + sin(angle) * (p.w() * 0.36F);
        float sunY = ground - cos(angle) * (p.h() * 0.5F);
        p.disc(sunX, sunY, 5.0F, day > 0.35F ? 0xFFFFE08A : 0xFFE8ECF4);
        p.glow(sunX, sunY, 16.0F, 16.0F, NovaGui.alpha(day > 0.35F ? 0xFFFFE08A : 0xFFE8ECF4, 0.35F));
        int hours = (int) (12 + ph * 24.0F) % 24;
        p.text(NovaType.Face.MONO, String.format(Locale.ROOT, "%02d:00", hours), p.x0 + 8, p.y0 + 7, TEXT);
    }

    static void skyVfx(ModPreview p) {
        p.g.fillGradient(p.x0 + 1, p.y0 + 1, p.x1 - 1, p.y1 - 1, p.col(0xFF0A1230), p.col(0xFF06091C));
        for (int i = 0; i < 3; i++) {
            float x = p.cx() + sin(p.t * 0.4F + i * 2.1F) * p.w() * 0.3F;
            float y = p.cy() - 8 + cos(p.t * 0.3F + i * 1.7F) * 10.0F;
            p.glow(x, y, 34.0F, 14.0F, NovaGui.alpha(i == 1 ? NovaFx.VIOLET | 0xFF000000 : NovaFx.MINT | 0xFF000000, 0.35F));
        }
        for (int i = 0; i < 14; i++) {
            float twinkle = 0.4F + 0.6F * (0.5F + 0.5F * sin(p.t * 2.0F + i * 1.3F));
            p.disc(p.x0 + 6 + noise(i, 1) * (p.w() - 12), p.y0 + 6 + noise(i, 2) * (p.h() - 12), 0.8F,
                    NovaGui.alpha(0xFFFFFFFF, 0.6F * twinkle));
        }
    }

    static void glowingOutlines(ModPreview p) {
        AvatarRenderState s = hero(p, 30.0F);
        p.hold(s, me(), true, SWORD);
        p.render();
        int color = GlowingOutlinesMod.color();
        p.project(-0.35F, 0.95F, 0.25F);
        p.glow(p.px, p.py, 14.0F, 20.0F, NovaGui.alpha(color, 0.45F));
    }

    static void pearlTrajectory(ModPreview p) {
        AvatarRenderState s = duel(p, 3.6F);
        p.hold(s, me(), true, PEARL);
        p.render();
        float offset = p.t * 0.8F;
        for (int i = 0; i < 14; i++) {
            float f = ((i + offset) % 14.0F) / 14.0F;
            float y = 1.5F + 1.6F * sin(f * 3.14159F) - 1.5F * f;
            p.project(lerp(0.4F, 3.6F, f), y, 0.0F);
            p.disc(p.px, p.py, 1.2F, NovaGui.alpha(BRAND, 0.4F + 0.6F * f));
        }
        p.project(3.6F, 0.0F, 0.0F);
        NovaTextures.halo(p.g, p.px, p.py, 8.0F, 2.6F, p.col(BRAND));
    }

    static void damageNumbers(ModPreview p) {
        float ph = p.phase(1.8F);
        AvatarRenderState s = duel(p, 2.0F);
        p.hold(s, me(), true, SWORD);
        ModPreview.swing(s, ph, 0.15F, 0.4F);
        p.floor(2.0F, 0.0F, 0.45F, 0.5F);
        LivingEntityRenderState zombie = p.mob(p.zombie(), 2.0F, 0.0F, 0.0F, 90.0F);
        zombie.hasRedOverlay = ph > 0.28F && ph < 0.45F;
        p.render();
        float rise = window(ph, 0.3F, 0.95F);
        if (rise > 0.0F && rise < 1.0F) {
            p.project(2.0F, 2.1F + rise * 0.6F, 0.0F);
            p.centered(NovaType.Face.HEADING, "-6.5", Math.round(p.px), Math.round(p.py), NovaGui.alpha(0xFFFF5470, 1.0F - rise * rise));
        }
    }

    static void killEffects(ModPreview p) {
        float ph = p.phase(2.4F);
        p.scene(p.h() * 0.3F, p.cx(), p.y1 - 14, 0.0F, -0.15F);
        p.floor(0.0F, 0.0F, 0.5F, 0.5F);
        float death = window(ph, 0.15F, 0.5F);
        if (ph < 0.6F) {
            LivingEntityRenderState zombie = p.mob(p.zombie(), 0.0F, 0.0F, 0.0F, -30.0F);
            zombie.deathTime = death * 20.0F;
            zombie.hasRedOverlay = death > 0.0F;
        }
        p.render();
        p.project(0.0F, 0.7F, 0.0F);
        float effect = window(ph, 0.45F, 0.9F);
        if (effect > 0.0F && effect < 1.0F) {
            for (int i = 0; i < 6; i++) {
                double angle = i * Math.PI / 3.0D + 0.3D;
                float d = 6.0F + 26.0F * ease(effect);
                p.line(p.px + (float) Math.cos(angle) * d * 0.4F, p.py + (float) Math.sin(angle) * d * 0.4F,
                        p.px + (float) Math.cos(angle) * d, p.py + (float) Math.sin(angle) * d, 1.4F, NovaGui.alpha(BRAND, 1.0F - effect));
            }
        }
        p.burst(p.px, p.py, effect, 0xFFFFFFFF, 12, p.scale * 1.2F);
    }

    static void fireflies(ModPreview p) {
        hero(p, 15.0F);
        fireflyLights(p, -1);
        p.render();
        fireflyLights(p, 1);
    }

    /** Gluehwuermchen um den Charakter; side 1 = die vorderen, -1 = die hinteren. */
    private static void fireflyLights(ModPreview p, int side) {
        p.project(0.0F, 1.0F, 0.0F);
        float middle = p.pz;
        int count = Math.max(4, Math.min(12, FirefliesMod.amount));
        for (int i = 0; i < count; i++) {
            float angle = p.t * 0.7F + i * 6.2832F / count;
            float radius = 0.8F + 0.2F * sin(p.t + i);
            float height = 0.5F + 1.2F * ((i * 0.37F + p.t * 0.08F) % 1.0F);
            p.project(cos(angle) * radius, height, sin(angle) * radius);
            if ((p.pz <= middle) != (side > 0)) {
                continue;
            }
            int color = 0xFF000000 | NovaFx.color(FirefliesMod.colorMode, FirefliesMod.hue, i / (float) count + p.t * 0.1F);
            float flicker = 0.6F + 0.4F * sin(p.t * 5.0F + i * 2.0F);
            p.glow(p.px, p.py, 6.0F, 6.0F, NovaGui.alpha(color, 0.45F * flicker));
            p.disc(p.px, p.py, 1.3F, NovaGui.alpha(color, flicker));
        }
    }

    static void jumpCircles(ModPreview p) {
        float ph = p.phase(1.8F);
        p.scene(p.h() * 0.36F, p.cx(), p.y1 - 14, 0.0F, -0.35F);
        float jump = window(ph, 0.0F, 0.45F);
        p.floor(0.0F, 0.0F, 0.5F - 0.2F * sin(jump * 3.14159F), 0.6F);
        p.character(0.0F, 0.55F * sin(jump * 3.14159F), 0.0F, 15.0F);
        float ring = window(ph, 0.45F, 1.0F);
        p.project(0.0F, 0.0F, 0.0F);
        if (ring > 0.0F && ring < 1.0F) {
            int color = 0xFF000000 | NovaFx.color(JumpCirclesMod.colorMode, JumpCirclesMod.hue, ring);
            float r = p.scale * (0.3F + 1.3F * ease(ring)) * JumpCirclesMod.size;
            NovaTextures.halo(p.g, p.px, p.py, r, r * 0.33F, p.col(color, 1.0F - ring));
        }
        p.render();
    }

    static void itemPhysics(ModPreview p) {
        p.scene(p.h() * 0.24F, p.cx(), p.cy() + 16, 0.5F, -0.55F);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 0; z++) {
                p.block(Blocks.GRASS_BLOCK, x, 0.0F, z);
            }
        }
        float flat = 1.5708F;
        p.item(Items.DIAMOND_SWORD, -0.7F, 1.02F, -0.4F, 1.0F, 0.4F, flat);
        p.item(Items.APPLE, 0.2F, 1.02F, -0.1F, 1.0F, 1.2F, flat);
        p.item(Items.GOLD_INGOT, 0.9F, 1.02F, -0.6F, 1.0F, 2.5F, flat);
        p.render();
    }

    /** Hyper Tools: links das flache Vanilla-Schwert, rechts das Laserschwert - wechselt durch die Seltenheiten. */
    static void hyperTools(ModPreview p) {
        p.landscape(p.x0 + 1, p.y0 + 1, p.x1 - 1, p.y1 - 1);
        net.minecraft.world.item.Item[] tools = {Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.IRON_SWORD,
                Items.GOLDEN_SWORD, Items.STONE_SWORD, Items.COPPER_SWORD, Items.WOODEN_SWORD};
        ItemStack stack = new ItemStack(tools[(int) (p.t / 1.8F) % tools.length]);
        float bob = 1.5F * sin(p.t * 2.0F);
        float size = p.h() / 16.0F;
        ToolSkinsMod.previewForce = 2;
        p.item(stack, p.cx() - p.w() * 0.24F, p.cy() + 2.0F, size * 0.42F);
        ToolSkinsMod.previewForce = 1;
        p.item(stack, p.cx() + p.w() * 0.14F, p.cy() + bob, size * 0.72F);
        ToolSkinsMod.previewForce = 0;
        p.centered(NovaType.Face.CAPTION, "vanilla", Math.round(p.cx() - p.w() * 0.24F), p.y1 - 12, 0x99FFFFFF);
        p.centered(NovaType.Face.CAPTION, "hyper", Math.round(p.cx() + p.w() * 0.14F), p.y1 - 12, 0xFFFFFFFF);
    }

    static void cape(ModPreview p) {
        // das Cape-Muster bewegt sich: 10 neue Bilder pro Sekunde (gemalt im Hintergrund)
        if (Util.getMillis() - capePaintedAt >= 100L) {
            capePaintedAt = Util.getMillis();
            NovaCape.paint();
        }
        AvatarRenderState s = hero(p, 180.0F + 45.0F * sin(p.t * 0.55F));
        s.skin = NovaCape.withCape(s.skin);
        s.showCape = true;
        s.capeLean = 14.0F + 5.0F * sin(p.t * 1.3F);
        s.capeLean2 = 6.0F * sin(p.t * 0.8F);
        s.capeFlap = 0.0F;
        p.render();
    }

    static void swing(ModPreview p) {
        AvatarRenderState s = hero(p, 35.0F);
        // Schlag in der eingestellten Geschwindigkeit, dann eine kurze Pause (auch mit leerer Hand)
        float duration = SwingSpeedMod.duration(6) / 20.0F;
        float local = p.t % (duration + 0.3F);
        Compat.previewSwing(s, local < duration ? local / duration : 0.0F, SwingAnimationType.WHACK);
        p.render();
    }

    static void vrMenu(ModPreview p) {
        p.landscape(p.x0 + 1, p.y0 + 1, p.x1 - 1, p.y1 - 1);
        float sway = 3.0F * sin(p.t * 1.2F);
        float cx = p.cx() + sway;
        float y0 = p.y0 + 14;
        NovaShapes.trapezoid(p.g, cx, y0, 62.0F, 76.0F, 54.0F, p.col(NovaGui.alpha(NovaGui.GLASS, 0.92F)));
        for (int i = 0; i < 3; i++) {
            float w = 46.0F + i * 4.0F;
            p.rect(Math.round(cx - w / 2), Math.round(y0 + 12 + i * 13), Math.round(cx + w / 2), Math.round(y0 + 20 + i * 13), 4,
                    i == 0 ? NovaGui.alpha(BRAND, 0.55F) : NovaGui.alpha(0xFFFFFFFF, 0.12F));
        }
    }

    static void motionBlur(ModPreview p) {
        float x = p.cx() + sin(p.t * 2.4F) * (p.w() * 0.3F);
        float velocity = cos(p.t * 2.4F);
        int y = p.cy() - 7;
        for (int i = 5; i >= 0; i--) {
            float gx = x - velocity * i * 5.0F;
            p.rect(Math.round(gx) - 7, y, Math.round(gx) + 7, y + 14, 4, NovaGui.alpha(0xFFFFFFFF, i == 0 ? 0.95F : 0.18F * (6 - i) / 6.0F));
        }
    }

    static void vivid(ModPreview p) {
        int[] base = {0xFF7FB238, 0xFF78A7FF, 0xFF9C7F4E, 0xFF3F76E4, 0xFFE03C3C};
        int chip = 15;
        int gap = 4;
        int x0 = p.cx() - (base.length * (chip + gap) - gap) / 2 + 10;
        int offY = p.cy() - chip - 4;
        int onY = p.cy() + 4;
        p.text(NovaType.Face.CAPTION, "Off", x0 - 22, offY + 4, MUTED);
        p.text(NovaType.Face.CAPTION, "On", x0 - 22, onY + 4, BRAND);
        for (int i = 0; i < base.length; i++) {
            int x = x0 + i * (chip + gap);
            p.rect(x, offY, x + chip, offY + chip, 4, base[i]);
            p.rect(x, onY, x + chip, onY + chip, 4, vivid(base[i]));
        }
        float sweep = (p.t * 0.5F) % 1.0F;
        p.glow(lerp(x0 - 10, x0 + base.length * (chip + gap) + 10, sweep), onY + chip / 2.0F, 10.0F, 8.0F, NovaGui.alpha(0xFFFFFFFF, 0.25F));
    }

    /** Dieselbe Rechnung wie shaders/core/vivid.fsh (ohne Schaerfen), fuer die Farbfelder. */
    private static int vivid(int argb) {
        float r = (argb >> 16 & 0xFF) / 255.0F;
        float g = (argb >> 8 & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float luma = 0.2126F * r + 0.7152F * g + 0.0722F * b;
        float sat = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        float k = 1.12F + 0.38F * (1.0F - sat);
        return 0xFF000000 | channel(luma + (r - luma) * k) << 16 | channel(luma + (g - luma) * k) << 8 | channel(luma + (b - luma) * k);
    }

    private static int channel(float value) {
        float v = (float) Math.pow(Math.max(0.0F, Math.min(1.0F, value)), 0.94F);
        v = v + (v * v * (3.0F - 2.0F * v) - v) * 0.22F;
        return Math.round(Math.max(0.0F, Math.min(1.0F, v)) * 255.0F);
    }

    /**
     * Block Overlay wie im Spiel: der Rahmen gleitet vom linken auf den mittleren Block, beim Abbauen waechst die
     * Fuellung von innen, dann bricht der Block und waechst nach.
     */
    static void blockOverlay(ModPreview p) {
        float ph = p.phase(3.6F);
        p.scene(p.h() * 0.26F, p.cx(), p.cy() + 14, 0.6F, -0.45F);
        boolean broken = ph >= 0.78F && ph < 0.9F;
        for (int i = -1; i <= 1; i++) {
            if (i != 0 || !broken) {
                p.block(Blocks.GRASS_BLOCK, i, 0.0F, 0.0F, i == 0 && ph >= 0.9F ? ease(window(ph, 0.9F, 1.0F)) : 1.0F);
            }
        }
        p.render();
        float slide = BlockOverlayMod.smooth ? ease(window(ph, 0.08F, 0.22F)) : ph >= 0.15F ? 1.0F : 0.0F;
        float x = ph < 0.9F ? lerp(-1.0F, 0.0F, slide) : -1.0F;
        float show = broken ? 0.0F : ph >= 0.9F ? window(ph, 0.9F, 1.0F) : 1.0F;
        overlayBox(p, x, show, x == 0.0F ? window(ph, 0.3F, 0.78F) : 0.0F);
        p.project(0.0F, 0.5F, 0.0F);
        p.burst(p.px, p.py, window(ph, 0.78F, 0.9F), 0xFF8DB360, 12, p.scale * 0.9F);
    }

    /** Rahmen, Fuellung und Leuchten des Block Overlays um den Block bei x (Einstellungen des Moduls). */
    private static void overlayBox(ModPreview p, float x, float show, float progress) {
        if (show <= 0.01F) {
            return;
        }
        int line = NovaGui.alpha(0xFF000000 | BlockOverlayMod.rgb(), show);
        float width = (float) Math.max(0.8D, BlockOverlayMod.lineWidth * 0.6D);
        float hidden = BlockOverlayMod.seeThrough ? 0.35F : 0.0F;
        // links liegt ein Nachbarblock (ausser ganz links): diese Seite sieht man nicht
        int covered = x > -0.99F ? ModPreview.WEST : 0;
        // leicht groesser als der Block, wie im Spiel
        float x0 = x - 0.51F;
        float x1 = x + 0.51F;
        if (BlockOverlayMod.glow && BlockOverlayMod.style != 2) {
            p.frame(x0, -0.01F, -0.51F, x1, 1.01F, 0.51F, width * 3.0F, NovaGui.alpha(line, 0.22F), 0.0F, covered);
        }
        if (BlockOverlayMod.style != 1) {
            float fill = BlockOverlayMod.style == 2 ? Math.max(0.2F, BlockOverlayMod.fillOpacity / 100.0F) : BlockOverlayMod.fillOpacity / 100.0F;
            p.faces(x0, -0.01F, -0.51F, x1, 1.01F, 0.51F, NovaGui.alpha(line, fill), covered);
        }
        if (BlockOverlayMod.style != 2) {
            p.frame(x0, -0.01F, -0.51F, x1, 1.01F, 0.51F, width, line, hidden, covered);
        }
        if (BlockOverlayMod.miningProgress && progress > 0.01F) {
            float half = 0.5F * progress;
            p.faces(x - half, 0.5F - half, -half, x + half, 0.5F + half, half, NovaGui.alpha(line, 0.35F), covered);
        }
    }

    static void zoom(ModPreview p) {
        float ph = p.phase(3.0F);
        float zoom = 1.0F + 1.5F * ease(window(ph, 0.15F, 0.45F)) * (1.0F - ease(window(ph, 0.75F, 0.95F)));
        int cx = p.cx();
        int cy = p.cy();
        p.g.pose().pushMatrix();
        p.g.pose().translate(cx, cy);
        p.g.pose().scale(zoom, zoom);
        p.g.pose().translate(-cx, -cy);
        p.landscape(p.x0 - 20, p.y0 - 10, p.x1 + 20, p.y1 + 10);
        p.rect(cx - 9, cy - 2, cx + 9, cy + 10, 1, 0xFF8A5A3C);
        NovaShapes.triangle(p.g, cx - 11, cy - 10, cx + 11, cy - 2, p.col(0xFF6E3B2A));
        p.disc(cx + 30, p.y0 + 16, 5.0F, 0xFFFFE08A);
        p.g.pose().popMatrix();
        p.text(NovaType.Face.MONO, String.format(Locale.ROOT, "x%.1f", zoom), p.x0 + 8, p.y0 + 7, TEXT);
    }

    // ================================================================ Nova

    static void schematic(ModPreview p) {
        float ph = p.phase(3.6F);
        p.scene(p.h() * 0.2F, p.cx(), p.cy() + 18, 0.6F, -0.55F);
        int placed = (int) (window(ph, 0.05F, 0.8F) * 9.99F);
        for (int i = 0; i < 9; i++) {
            int x = i % 3 - 1;
            int z = i / 3 - 1;
            if (i < placed) {
                p.block(Blocks.BRICKS, x, 0.0F, z);
            }
        }
        p.render();
        for (int i = placed; i < 9; i++) {
            int x = i % 3 - 1;
            int z = i / 3 - 1;
            p.frame(x - 0.5F, 0.0F, z - 0.5F, x + 0.5F, 1.0F, z + 0.5F, 0.8F, NovaGui.alpha(BRAND, 0.55F), 0.0F);
        }
        p.text(NovaType.Face.MONO, placed + "/9", p.x0 + 8, p.y0 + 7, placed >= 9 ? GOOD : DIM);
    }

    /** Nova Social: Freundesliste mit Online-Status und Server, eine Nachricht kommt herein, dazu ein Party-Ping. */
    static void social(ModPreview p) {
        float ph = p.phase(3.4F);
        int x0 = p.x0 + 12;
        int y = p.y0 + 10;
        String[] names = {"Alex", "Mia", "Sam"};
        String[] where = {"donutsmp.net", "Online", "Offline"};
        for (int i = 0; i < names.length; i++) {
            boolean online = i < 2;
            p.disc(x0 + 3, y + 5, 2.5F, online ? GOOD : 0xFF4A4F59);
            p.text(NovaType.Face.STRONG, names[i], x0 + 11, y, online ? TEXT : DIM);
            p.text(NovaType.Face.CAPTION, where[i], x0 + 11, y + 11, MUTED);
            y += 25;
        }
        // Nachricht von Alex gleitet als Blase herein
        float show = ease(window(ph, 0.2F, 0.32F)) * (1.0F - window(ph, 0.86F, 0.97F));
        if (show > 0.01F) {
            String text = "join my party?";
            int w = NovaType.width(p.font, NovaType.Face.BODY, text) + 16;
            int x1 = p.x1 - 12;
            int by = p.y0 + 14 + Math.round((1.0F - show) * 8.0F);
            p.rect(x1 - w, by, x1, by + 17, 8, NovaGui.alpha(NovaGui.CARD, show));
            p.text(NovaType.Face.BODY, text, x1 - w + 8, by + 4, NovaGui.alpha(TEXT, show));
            p.text(NovaType.Face.CAPTION, "Alex", x1 - w, by - 10, NovaGui.alpha(MUTED, show));
        }
        // Ping der Party: Ring geht auf, darueber Name und Entfernung
        float ping = window(ph, 0.45F, 0.95F);
        if (ping > 0.0F && ping < 1.0F) {
            float cx = p.x1 - 40;
            float cy = p.y1 - 22;
            float wave = (ph * 3.4F % 1.2F) / 1.2F;
            p.ring(cx, cy, 3.0F + 9.0F * wave, 1.1F, NovaGui.alpha(BRAND, 0.7F * (1.0F - wave)));
            p.disc(cx, cy, 3.0F, BRAND);
            p.disc(cx, cy, 1.2F, 0xFFFFFFFF);
            p.badge("Mia  ·  42m", Math.round(cx), Math.round(cy) - 22, BRAND, Math.min(1.0F, ping * 6.0F));
        }
    }
}
