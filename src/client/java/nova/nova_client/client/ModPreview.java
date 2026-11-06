package nova.nova_client.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import nova.nova_client.client.mods.*;
import org.joml.Matrix3x2f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static java.util.Map.entry;

/**
 * Vorschau eines Moduls im Nova-Menue: eine kleine, bewegte Szene, die zeigt, was das Modul macht - am eigenen
 * Charakter (3D wie im Inventar, mit Mobs und Bloecken drumherum), als HUD-Element mit dem Code des Moduls selbst oder
 * als kleine Oberflaechen-Animation (Inventar, Chat, Karte, Toast). Die Szenen stehen in {@link PreviewScenes}, hier
 * sind die Buehne und ihre Bausteine. Module ohne Szene zeigen keine Vorschau.
 */
final class ModPreview {
    /** Hoehen der Buehne: Charakter, Szene, flach (Toasts, Leisten, Chat). */
    static final int TALL = 116;
    static final int MEDIUM = 96;
    static final int FLAT = 72;
    /** Breite fuer Toasts (die Fund-Karte ist 176 breit). */
    static final int TOAST = NovaToasts.CARD_W + 20;
    /** Rand um HUD-Elemente auf der Buehne. */
    static final int PAD = 12;
    private static final int TALL_SMALL = 88;
    private static final int MIN_H = 48;
    private static final int MAX_H = 160;
    private static final int FULL_BRIGHT = 15728880;
    private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/ModPreview");

    /** Eine Szene: Hoehe und Mindestbreite der Buehne (0 = so breit wie die Karte) und das Zeichnen. */
    record Scene(int height, int width, Consumer<ModPreview> draw) {
    }

    private static Map.Entry<Class<?>, Scene> scene(Class<?> type, int height, Consumer<ModPreview> draw) {
        return entry(type, new Scene(height, 0, draw));
    }

    private static Map.Entry<Class<?>, Scene> scene(Class<?> type, int height, int width, Consumer<ModPreview> draw) {
        return entry(type, new Scene(height, width, draw));
    }

    private static final Map<Class<?>, Scene> SCENES = Map.ofEntries(
            // Combat
            scene(AutoTotemMod.class, TALL, PreviewScenes::autoTotem),
            scene(AutoInvTotemMod.class, MEDIUM, PreviewScenes::autoInvTotem),
            scene(HoverTotemMod.class, MEDIUM, PreviewScenes::hoverTotem),
            scene(AutoDoubleHandMod.class, FLAT, PreviewScenes::autoDoubleHand),
            scene(ReachMod.class, MEDIUM, PreviewScenes::reach),
            scene(LegitCrystalMacroMod.class, MEDIUM, PreviewScenes::crystalMacro),
            scene(AnchorMacroMod.class, MEDIUM, PreviewScenes::anchorMacro),
            scene(KeyPearlMod.class, MEDIUM, PreviewScenes::keyPearl),
            scene(LungeMacroMod.class, TALL, PreviewScenes::lunge),
            scene(BowAimbotMod.class, MEDIUM, PreviewScenes::bowAimbot),
            scene(TriggerBotMod.class, MEDIUM, PreviewScenes::triggerBot),
            scene(KillAuraMod.class, MEDIUM, PreviewScenes::killAura),
            scene(ShieldBreakerMod.class, MEDIUM, PreviewScenes::shieldBreaker),
            scene(HitboxesMod.class, TALL, PreviewScenes::hitboxes),
            scene(MacroMod.class, MEDIUM, PreviewScenes::macro),
            // Misc
            scene(AutoToolMod.class, MEDIUM, PreviewScenes::autoTool),
            scene(FastPlaceMod.class, MEDIUM, PreviewScenes::fastPlace),
            scene(FastXpMod.class, TALL, PreviewScenes::fastXp),
            scene(FreecamMod.class, TALL, PreviewScenes::freecam),
            scene(FreelookMod.class, TALL, PreviewScenes::freelook),
            scene(BoatFlyMod.class, MEDIUM, PreviewScenes::boatFly),
            scene(StreamProtectMod.class, FLAT, PreviewScenes::streamProtect),
            scene(SprintMod.class, TALL, PreviewScenes::sprint),
            scene(AutoWalkMod.class, TALL, PreviewScenes::autoWalk),
            scene(FlightMod.class, TALL, PreviewScenes::flight),
            scene(AutoReconnectMod.class, MEDIUM, PreviewScenes::autoReconnect),
            scene(SpotifyMod.class, MEDIUM, PreviewScenes::spotify),
            scene(NoFallMod.class, TALL, PreviewScenes::noFall),
            scene(FpsBoostMod.class, FLAT, PreviewScenes::fpsBoost),
            scene(PlayerDetectorMod.class, MEDIUM, PreviewScenes::playerDetector),
            scene(LootPreviewMod.class, MEDIUM, PreviewScenes::lootPreview),
            scene(AssistantMod.class, FLAT, PreviewScenes::assistant),
            scene(NameProtectMod.class, TALL, PreviewScenes::nameProtect),
            scene(TridentUtilMod.class, TALL, PreviewScenes::trident),
            scene(CoordSnapperMod.class, MEDIUM, PreviewScenes::coordSnapper),
            scene(ChatMacroMod.class, FLAT, PreviewScenes::chatMacro),
            scene(DiscordPresenceMod.class, MEDIUM, PreviewScenes::discord),
            // Donut
            scene(AutoChunkReloadMod.class, MEDIUM, PreviewScenes::chunkReload),
            scene(AuctionSniperMod.class, MEDIUM, PreviewScenes::auctionSniper),
            scene(AutoSellMod.class, MEDIUM, PreviewScenes::autoSell),
            scene(SpawnerProtectMod.class, MEDIUM, PreviewScenes::spawnerProtect),
            scene(AntiVanishMod.class, MEDIUM, PreviewScenes::antiVanish),
            scene(FakeMediaMod.class, TALL, PreviewScenes::fakeMedia),
            scene(TpaHereMacroMod.class, FLAT, PreviewScenes::tpaHere),
            scene(ScoreboardMod.class, 120, 150, PreviewScenes::scoreboard),
            scene(RegionMapMod.class, 120, 130, PreviewScenes::regionMap),
            // Base Finding
            scene(SusChunkFinderMod.class, MEDIUM, PreviewScenes::susChunk),
            scene(HoleEspMod.class, MEDIUM, PreviewScenes::holeEsp),
            scene(AdvancedStashFinderMod.class, MEDIUM, PreviewScenes::stashFinder),
            scene(TunnelBaseFinderMod.class, MEDIUM, PreviewScenes::tunnel),
            scene(SeedFinderMod.class, FLAT, PreviewScenes::seedFinder),
            scene(ActivityInspectorMod.class, MEDIUM, PreviewScenes::activity),
            scene(PrimeChunkFinderMod.class, MEDIUM, PreviewScenes::primeChunk),
            scene(PlayerBypassMod.class, MEDIUM, PreviewScenes::playerBypass),
            scene(NetheriteFinderMod.class, MEDIUM, PreviewScenes::netherite),
            scene(QuantumMod.class, MEDIUM, PreviewScenes::quantum),
            scene(AmethystBypassMod.class, MEDIUM, PreviewScenes::amethyst),
            scene(BlockEntityDebugMod.class, MEDIUM, PreviewScenes::blockEntityDebug),
            // Render
            scene(FullbrightMod.class, MEDIUM, PreviewScenes::fullbright),
            scene(ClearLiquidsMod.class, MEDIUM, PreviewScenes::clearLiquids),
            scene(EspMod.class, MEDIUM, PreviewScenes::esp),
            scene(BlockEspMod.class, MEDIUM, PreviewScenes::blockEsp),
            scene(StorageEspMod.class, MEDIUM, PreviewScenes::storageEsp),
            scene(BlockNotifierMod.class, FLAT, TOAST, PreviewScenes::blockNotifier),
            scene(SpawnerNotifierMod.class, FLAT, TOAST, PreviewScenes::spawnerNotifier),
            scene(WeatherNotifierMod.class, FLAT, TOAST, PreviewScenes::weatherNotifier),
            scene(TraderEspMod.class, MEDIUM, PreviewScenes::mobEsp),
            scene(VillagerEspMod.class, MEDIUM, PreviewScenes::mobEsp),
            scene(PillagerEspMod.class, MEDIUM, PreviewScenes::mobEsp),
            scene(ItemFrameEspMod.class, MEDIUM, PreviewScenes::collectibleEsp),
            scene(VineEspMod.class, MEDIUM, PreviewScenes::vineEsp),
            scene(KelpEspMod.class, MEDIUM, PreviewScenes::kelpEsp),
            scene(NameTagsMod.class, TALL, PreviewScenes::nameTags),
            scene(WaypointsMod.class, MEDIUM, PreviewScenes::waypoints),
            scene(XrayMod.class, MEDIUM, PreviewScenes::xray),
            // Visuals
            scene(HudInfoMod.class, 80, 0, PreviewScenes::hudInfo),
            scene(AdminListMod.class, 68, 0, PreviewScenes::adminList),
            scene(PotionHudMod.class, 72, 0, PreviewScenes::potionHud),
            scene(HotkeyListMod.class, 60, 0, PreviewScenes::hotkeys),
            scene(RadarMod.class, 130, 116, PreviewScenes::radar),
            scene(TimeChangerMod.class, MEDIUM, PreviewScenes::timeChanger),
            scene(SkyVfxMod.class, MEDIUM, PreviewScenes::skyVfx),
            scene(CrosshairMod.class, FLAT, PreviewScenes::crosshair),
            scene(GlowingOutlinesMod.class, TALL, PreviewScenes::glowingOutlines),
            scene(PearlTrajectoryMod.class, MEDIUM, PreviewScenes::pearlTrajectory),
            scene(DamageNumbersMod.class, MEDIUM, PreviewScenes::damageNumbers),
            scene(KillEffectsMod.class, MEDIUM, PreviewScenes::killEffects),
            scene(FirefliesMod.class, TALL, PreviewScenes::fireflies),
            scene(JumpCirclesMod.class, TALL, PreviewScenes::jumpCircles),
            scene(ItemPhysicsMod.class, MEDIUM, PreviewScenes::itemPhysics),
            scene(ToolSkinsMod.class, MEDIUM, PreviewScenes::hyperTools),
            scene(NovaCapeMod.class, TALL, PreviewScenes::cape),
            scene(SwingSpeedMod.class, TALL, PreviewScenes::swing),
            scene(VrMenuMod.class, MEDIUM, PreviewScenes::vrMenu),
            scene(MotionBlurMod.class, FLAT, PreviewScenes::motionBlur),
            scene(VividMod.class, MEDIUM, PreviewScenes::vivid),
            scene(BlockOverlayMod.class, MEDIUM, PreviewScenes::blockOverlay),
            scene(LowHealthWarningMod.class, 76, 0, PreviewScenes::lowHealth),
            scene(TargetHudMod.class, 68, 176, PreviewScenes::targetHud),
            scene(ZoomMod.class, MEDIUM, PreviewScenes::zoom),
            scene(ArmourViewMod.class, 76, 142, PreviewScenes::armourView),
            scene(CustomFontMod.class, 56, 0, PreviewScenes::customFont),
            // Nova
            scene(NovaPlusMod.class, 64, 0, PreviewScenes::novaPlus),
            scene(SchematicBuilderMod.class, MEDIUM, PreviewScenes::schematic),
            scene(NovaSocialMod.class, MEDIUM, PreviewScenes::social));

    // Buehne des aktuellen Bildes (fuer die Szenen)
    GuiGraphicsExtractor g;
    Font font;
    Mod mod;
    int x0;
    int y0;
    int x1;
    int y1;
    /** Sekunden seit dem Wechsel, Einblenden der Karte, Modul an (0..1). */
    float t;
    float a;
    float on;

    // Groesse der Buehne: Vorgabe der Szene, HUD-Elemente melden ihre echte Groesse
    private Mod sized;
    private int wantW;
    private int wantH;
    private int hudW;
    private int hudH;
    private boolean failed;

    /** Laesst sich fuer dieses Modul (gerade) etwas zeigen? */
    static boolean has(Mod mod) {
        Minecraft mc = Minecraft.getInstance();
        if (!SCENES.containsKey(mod.getClass()) || mc.player == null || mc.level == null) {
            return false;
        }
        return !(mod instanceof ScoreboardMod) || ScoreboardMod.sidebarObjective(mc) != null;
    }

    int stageWidth(Mod mod, int min) {
        size(mod);
        return Math.max(min, wantW);
    }

    int stageHeight(Mod mod, int screenHeight) {
        size(mod);
        int h = wantH == TALL && screenHeight < 380 ? TALL_SMALL : wantH;
        return Math.min(MAX_H, Math.max(MIN_H, h));
    }

    /** Beim Wechsel: Groesse aus der Szene (HUD-Elemente korrigieren sie nach dem ersten Bild). */
    private void size(Mod mod) {
        if (mod == sized) {
            return;
        }
        sized = mod;
        failed = false;
        Scene scene = SCENES.get(mod.getClass());
        wantW = scene.width();
        wantH = scene.height();
        hudW = Math.max(0, wantW - 2 * PAD);
        hudH = Math.max(0, wantH - 2 * PAD);
    }

    /** Buehne x0..x1 / y0..y1 mit der Szene des Moduls. */
    void draw(GuiGraphicsExtractor g, Font font, Mod mod, int x0, int y0, int x1, int y1, float t, float a, float on) {
        this.g = g;
        this.font = font;
        this.mod = mod;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.t = t;
        this.a = a;
        this.on = on;
        size(mod);
        NovaShapes.rect(g, x0, y0, x1, y1, 9, NovaGui.alpha(NovaGui.INSET, 0.8F * a));
        if (failed) {
            return;
        }
        g.enableScissor(x0 + 1, y0 + 1, x1 - 1, y1 - 1);
        try {
            SCENES.get(mod.getClass()).draw().accept(this);
        } catch (RuntimeException e) {
            // eine kaputte Szene darf das Menue nie stoeren
            failed = true;
            LOGGER.warn("Preview of {} failed", mod.name, e);
        } finally {
            g.disableScissor();
        }
    }

    // ---------------------------------------------------------------- Mass und Zeit

    int cx() {
        return (x0 + x1) / 2;
    }

    int cy() {
        return (y0 + y1) / 2;
    }

    int w() {
        return x1 - x0;
    }

    int h() {
        return y1 - y0;
    }

    /** Stelle in einer Schleife der Laenge period (Sekunden), 0..1. */
    float phase(float period) {
        return (t % period) / period;
    }

    /** 0..1 zwischen from und to, davor 0, danach 1. */
    static float window(float phase, float from, float to) {
        return Math.max(0.0F, Math.min(1.0F, (phase - from) / (to - from)));
    }

    /** 0 -> 1 -> 0 zwischen from und to (Sinusbogen), sonst 0. */
    static float bump(float phase, float from, float to) {
        float f = window(phase, from, to);
        return f <= 0.0F || f >= 1.0F ? 0.0F : (float) Math.sin(f * Math.PI);
    }

    static float ease(float f) {
        float k = 1.0F - Math.max(0.0F, Math.min(1.0F, f));
        return 1.0F - k * k * k;
    }

    static float lerp(float from, float to, float f) {
        return from + (to - from) * f;
    }

    // ---------------------------------------------------------------- 2D-Bausteine

    int col(int color) {
        return NovaGui.alpha(color, a);
    }

    int col(int color, float factor) {
        return NovaGui.alpha(color, factor * a);
    }

    void text(NovaType.Face face, String text, int x, int y, int color) {
        NovaType.draw(g, font, face, text, x, y, col(color));
    }

    void centered(NovaType.Face face, String text, int cx, int y, int color) {
        NovaType.centered(g, font, face, text, cx, y, col(color));
    }

    /** Minecraft-Schrift (Chat, Namensschilder), mit Schatten. */
    void vanilla(String text, int x, int y, int color) {
        g.text(font, text, x, y, col(color), true);
    }

    void item(ItemStack stack, int x, int y) {
        if (a > 0.5F && !stack.isEmpty()) {
            g.item(stack, x, y);
        }
    }

    /** Item mittig um (cx, cy), skaliert. */
    void item(ItemStack stack, float cx, float cy, float scale) {
        if (a > 0.5F && !stack.isEmpty() && scale > 0.05F) {
            NovaGui.item(g, stack, cx, cy, scale);
        }
    }

    void rect(int x0, int y0, int x1, int y1, int radius, int color) {
        NovaShapes.rect(g, x0, y0, x1, y1, radius, col(color));
    }

    void outline(int x0, int y0, int x1, int y1, int radius, int color) {
        NovaShapes.outline(g, x0, y0, x1, y1, radius, col(color));
    }

    void disc(float x, float y, float radius, int color) {
        NovaShapes.disc(g, x, y, radius, col(color));
    }

    void ring(float x, float y, float radius, float thickness, int color) {
        NovaShapes.ring(g, x, y, radius, thickness, col(color));
    }

    void line(float x0, float y0, float x1, float y1, float width, int color) {
        NovaShapes.beam(g, x0, y0, x1, y1, width, col(color));
    }

    /** Gestrichelte Linie; offset (0..1) laesst die Striche wandern. */
    void dashed(float x0, float y0, float x1, float y1, float width, float dash, float offset, int color) {
        float length = (float) Math.hypot(x1 - x0, y1 - y0);
        if (length < 0.5F) {
            return;
        }
        float step = dash * 2.0F;
        for (float s = -step + (offset % 1.0F) * step; s < length; s += step) {
            float from = Math.max(0.0F, s) / length;
            float to = Math.min(length, s + dash) / length;
            if (to > from) {
                line(lerp(x0, x1, from), lerp(y0, y1, from), lerp(x0, x1, to), lerp(y0, y1, to), width, color);
            }
        }
    }

    void glow(float x, float y, float rx, float ry, int color) {
        NovaTextures.radial(g, x, y, rx, ry, col(color));
    }

    /** Funken, die von (x, y) nach aussen fliegen und verblassen; progress 0..1. */
    void burst(float x, float y, float progress, int color, int count, float radius) {
        if (progress <= 0.0F || progress >= 1.0F) {
            return;
        }
        float d = radius * ease(progress);
        float size = 1.6F * (1.0F - progress) + 0.4F;
        for (int i = 0; i < count; i++) {
            double angle = i * Math.PI * 2.0D / count + 0.4D;
            float spread = 0.7F + 0.3F * (float) Math.sin(i * 2.3D);
            disc(x + (float) Math.cos(angle) * d * spread, y + (float) Math.sin(angle) * d * spread, size,
                    NovaGui.alpha(color, 1.0F - progress));
        }
        glow(x, y, radius * 0.6F, radius * 0.6F, NovaGui.alpha(color, 0.35F * (1.0F - progress)));
    }

    /** Kleine Pille mit Text, mittig um cx (Ergebnis, Hinweis). */
    void badge(String text, int cx, int y, int color, float show) {
        if (show <= 0.01F) {
            return;
        }
        int w = NovaType.width(font, NovaType.Face.CAPTION, text) + 12;
        NovaShapes.rect(g, cx - w / 2, y, cx + w - w / 2, y + 12, 6, col(NovaGui.alpha(color, 0.26F), show));
        NovaType.centered(g, font, NovaType.Face.CAPTION, text, cx, NovaType.centerY(NovaType.Face.CAPTION, y, 12),
                col(NovaGui.mix(color, 0xFFFFFFFF, 0.45F), show));
    }

    /** Inventar-Feld 18x18 mit Item. */
    void slot(int x, int y, ItemStack stack, boolean highlight) {
        rect(x, y, x + 18, y + 18, 4, highlight ? 0xFF262B36 : 0xFF181B21);
        outline(x, y, x + 18, y + 18, 4, highlight ? NovaGui.alpha(NovaGui.BRAND, 0.85F) : NovaGui.BORDER_STRONG);
        item(stack, x + 1, y + 1);
    }

    /** Reihe von Feldern ab (x, y); selected = hervorgehobenes Feld (-1 = keins). */
    void slots(int x, int y, ItemStack[] stacks, int selected) {
        for (int i = 0; i < stacks.length; i++) {
            slot(x + i * 20, y, stacks[i], i == selected);
        }
    }

    /** Taste wie im Hotkeys-HUD, rechtsbuendig an x1; gedrueckt = Akzentfarbe. */
    void keycap(String key, int x1, int y, boolean pressed) {
        HudCards.keycap(g, font, key, x1, y, pressed ? NovaGui.BRAND : 0xFF8A8A94, a);
    }

    /** Fund-Karte wie bei den Notifiern (NovaToasts), show = Einblenden. */
    void toast(int x, int y, float show, String title, String badge, String where, List<ItemStack> icons) {
        if (show > 0.01F) {
            NovaToasts.card(g, font, x, y, show * a, title, badge, where, a > 0.5F ? icons : List.of());
        }
    }

    /** Chat-Zeile wie im Spiel: halb durchsichtiges Schwarz, weisse Schrift. */
    void chat(int x, int y, String text, float show) {
        if (show <= 0.01F) {
            return;
        }
        g.fill(x, y, x + font.width(text) + 4, y + 10, NovaGui.alpha(0x80000000, show * a));
        g.text(font, text, x + 2, y + 1, NovaGui.alpha(0xFFFFFFFF, show * a), true);
    }

    /** Mauszeiger als heller Punkt. */
    void cursor(float x, float y) {
        disc(x, y, 3.2F, 0x80000000);
        disc(x, y, 2.2F, 0xFFFFFFFF);
    }

    /** Herzen wie im Spiel (full volle von count). */
    void hearts(int x, int y, int count, int full) {
        if (a <= 0.5F) {
            return;
        }
        for (int i = 0; i < count; i++) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, x + i * 8, y, 9, 9);
            if (i < full) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, x + i * 8, y, 9, 9);
            }
        }
    }

    /** Zelle einer Karte (Chunks, Abschnitte), size gross. */
    void cell(int x, int y, int size, int color) {
        rect(x, y, x + size, y + size, 2, color);
    }

    /** Kleines Fenster (Flaeche + Haarlinie). */
    void panel(int x0, int y0, int x1, int y1) {
        rect(x0, y0, x1, y1, 7, NovaGui.PANEL);
        outline(x0, y0, x1, y1, 7, NovaGui.BORDER_STRONG);
    }

    /** Kleine Landschaft: Himmel und Boden (fuer Effekte auf dem Bildschirm). */
    void landscape(int x0, int y0, int x1, int y1) {
        int mid = y0 + (y1 - y0) * 3 / 5;
        g.fillGradient(x0, y0, x1, mid, col(0xFF3D6A9A), col(0xFF7FA9CF));
        g.fillGradient(x0, mid, x1, y1, col(0xFF4C7A34), col(0xFF2F4F22));
    }

    // ---------------------------------------------------------------- HUD-Elemente

    /** Linke obere Ecke fuer ein HUD-Element der zuletzt gemessenen Groesse, mittig auf der Buehne. */
    int hudX() {
        return cx() - hudW / 2;
    }

    int hudY() {
        return cy() - hudH / 2;
    }

    /** Das HUD-Element war so gross; die Buehne richtet sich ab dem naechsten Bild danach. */
    void measured(Rect drawn) {
        if (drawn != null) {
            hudW = drawn.x1 - drawn.x0;
            hudH = drawn.y1 - drawn.y0;
            wantW = hudW + 2 * PAD;
            wantH = hudH + 2 * PAD;
        }
    }

    // ---------------------------------------------------------------- 3D-Szene

    /** Seiten eines Quaders mit Nachbarblock (fuer cube/frame/faces): -x, +x, -y, +y, -z, +z. */
    static final int WEST = 1;
    static final int EAST = 2;
    static final int DOWN = 4;
    static final int UP = 8;
    static final int NORTH = 16;
    static final int SOUTH = 32;

    private final Vector3f translation = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private final Quaternionf camera = new Quaternionf();
    private final Vector3f point = new Vector3f();
    private final boolean[] front = new boolean[6];
    private float centerX;
    private float centerY;
    /** GUI-Pixel je Block der aktuellen Szene. */
    float scale;
    /** Ergebnis von project(): GUI-Koordinaten und Tiefe (kleiner = naeher am Betrachter). */
    float px;
    float py;
    float pz;
    private boolean sceneOpen;

    private ClientLevel level;
    private EntityRenderState anchor;
    private final Map<Item, EntityRenderState> items = new HashMap<>();
    private Zombie zombie;
    private Zombie guard;
    private Villager villager;
    private WanderingTrader trader;
    private Pillager pillager;
    private EndCrystal crystal;
    private Boat boat;
    private RemotePlayer stranger;
    private int nextId = -1_000_000;

    /**
     * Neue 3D-Szene: scale = GUI-Pixel je Block, (originX, originY) = wo der Ursprung der Szene auf der Buehne liegt,
     * yaw = Drehung um die Hochachse, tilt = Blick von oben (negativ). Ohne Drehung zeigt +x nach rechts und Blickrichtung
     * 0 (Grad) zum Betrachter.
     */
    void scene(float scale, float originX, float originY, float yaw, float tilt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            reset(mc.level);
        }
        this.scale = scale;
        centerX = (x0 + x1) / 2.0F;
        centerY = (y0 + y1) / 2.0F;
        camera.rotationX(tilt);
        rotation.rotationZ((float) Math.PI).rotateX(tilt).rotateY((float) Math.PI + yaw);
        translation.set((originX - centerX) / scale, (originY - centerY) / scale, 0.0F);
        PreviewScene.begin(anchor);
        sceneOpen = true;
    }

    /** Blickrichtung (Grad) zum Betrachter bei dieser Drehung der Szene. */
    static float facing(float sceneYaw) {
        return (float) Math.toDegrees(sceneYaw);
    }

    /** Alle Teile der Szene in ein Entity-Bild; davor Gezeichnetes liegt dahinter, danach Gezeichnetes davor. */
    void render() {
        if (!sceneOpen) {
            return;
        }
        sceneOpen = false;
        g.nextStratum();
        if (a >= 0.35F) {
            // das Entity-Bild kennt keine Deckkraft: erst zeigen, wenn die Karte fast da ist
            g.entity(anchor, scale, translation, rotation, camera, x0, y0, x1, y1);
        }
        g.nextStratum();
    }

    /** Welt-Punkt der Szene -> GUI-Koordinaten (px, py) und Tiefe pz, genau wie das Entity-Bild ihn zeichnet. */
    void project(float x, float y, float z) {
        rotation.transform(point.set(x, y, z));
        px = centerX + scale * (translation.x + point.x);
        py = centerY + scale * (translation.y + point.y);
        pz = point.z;
    }

    /** Weicher Schatten auf dem Boden (y = 0) bei (x, z). */
    void floor(float x, float z, float radius, float strength) {
        project(x, 0.0F, z);
        glow(px, py, radius * scale, radius * scale * 0.3F, NovaGui.alpha(0xFF000000, strength));
    }

    /** Licht hinter der Szene bei (x, y, z); an = Akzentfarbe. */
    void spot(float x, float y, float z, float radius) {
        project(x, y, z);
        glow(px, py, radius * scale, radius * scale, NovaGui.alpha(0xFFFFFFFF, 0.06F * (1.0F - on)));
        glow(px, py, radius * scale, radius * scale, NovaGui.alpha(NovaGui.BRAND, 0.28F * on));
    }

    /** Linie zwischen zwei Welt-Punkten; side 0 = immer, 1 = nur wenn vor middle, -1 = nur wenn dahinter. */
    void edge(float ax, float ay, float az, float bx, float by, float bz, float width, int color, int side, float middle) {
        project(ax, ay, az);
        float sx = px;
        float sy = py;
        float sz = pz;
        project(bx, by, bz);
        if (side != 0 && ((sz + pz) / 2.0F <= middle) != (side > 0)) {
            return;
        }
        line(sx, sy, px, py, width, color);
    }

    /** Zeigt eine Seite mit dieser Aussen-Normalen zum Betrachter? */
    private boolean facesViewer(float nx, float ny, float nz) {
        rotation.transform(point.set(nx, ny, nz));
        return point.z < -1.0E-4F;
    }

    /**
     * Welche der 6 Seiten eines Quaders zum Betrachter zeigen (-x, +x, -y, +y, -z, +z); covered = Seiten, an denen ein
     * Nachbarblock liegt (Bits wie die Reihenfolge, z.B. {@link #WEST}) - die sieht man nicht.
     */
    private void frontFaces(int covered) {
        front[0] = (covered & WEST) == 0 && facesViewer(-1.0F, 0.0F, 0.0F);
        front[1] = (covered & EAST) == 0 && facesViewer(1.0F, 0.0F, 0.0F);
        front[2] = (covered & DOWN) == 0 && facesViewer(0.0F, -1.0F, 0.0F);
        front[3] = (covered & UP) == 0 && facesViewer(0.0F, 1.0F, 0.0F);
        front[4] = (covered & NORTH) == 0 && facesViewer(0.0F, 0.0F, -1.0F);
        front[5] = (covered & SOUTH) == 0 && facesViewer(0.0F, 0.0F, 1.0F);
    }

    /**
     * Kanten eines Quaders (Welt-Koordinaten): side 1 = sichtbare Kanten (eine angrenzende Seite zeigt zum Betrachter),
     * -1 = verdeckte, 0 = alle. So entsteht kein Drahtgitter, bei dem man nicht weiss, wo vorne ist.
     */
    void cube(float ax, float ay, float az, float bx, float by, float bz, float width, int color, int side) {
        cube(ax, ay, az, bx, by, bz, width, color, side, 0);
    }

    /** Wie cube, Seiten mit Nachbarblock (covered) zaehlen als verdeckt. */
    void cube(float ax, float ay, float az, float bx, float by, float bz, float width, int color, int side, int covered) {
        frontFaces(covered);
        float[] lo = {ax, ay, az};
        float[] hi = {bx, by, bz};
        for (int corner = 0; corner < 8; corner++) {
            for (int axis = 0; axis < 3; axis++) {
                if ((corner & (1 << axis)) != 0) {
                    continue;
                }
                // die Kante grenzt an je eine Seite der beiden anderen Achsen
                boolean visible = false;
                for (int o = 0; o < 3; o++) {
                    if (o != axis && front[o * 2 + ((corner >> o) & 1)]) {
                        visible = true;
                    }
                }
                if (side != 0 && visible != side > 0) {
                    continue;
                }
                int other = corner | (1 << axis);
                project((corner & 1) == 0 ? lo[0] : hi[0], (corner & 2) == 0 ? lo[1] : hi[1], (corner & 4) == 0 ? lo[2] : hi[2]);
                float sx = px;
                float sy = py;
                project((other & 1) == 0 ? lo[0] : hi[0], (other & 2) == 0 ? lo[1] : hi[1], (other & 4) == 0 ? lo[2] : hi[2]);
                line(sx, sy, px, py, width, color);
            }
        }
    }

    /** Rahmen wie im Spiel: sichtbare Kanten voll, verdeckte mit Anteil hidden (0 = gar nicht, ESP sieht durch Waende). */
    void frame(float ax, float ay, float az, float bx, float by, float bz, float width, int color, float hidden) {
        frame(ax, ay, az, bx, by, bz, width, color, hidden, 0);
    }

    /** Wie frame, Seiten mit Nachbarblock (covered) zaehlen als verdeckt. */
    void frame(float ax, float ay, float az, float bx, float by, float bz, float width, int color, float hidden, int covered) {
        if (hidden > 0.0F) {
            cube(ax, ay, az, bx, by, bz, width, NovaGui.alpha(color, hidden), -1, covered);
        }
        cube(ax, ay, az, bx, by, bz, width, color, 1, covered);
    }

    /** Die zum Betrachter zeigenden Seiten eines Quaders fuellen (z.B. Fuellung des Block Overlays). */
    void faces(float ax, float ay, float az, float bx, float by, float bz, int color) {
        faces(ax, ay, az, bx, by, bz, color, 0);
    }

    /** Wie faces, ohne die Seiten mit Nachbarblock (covered). */
    void faces(float ax, float ay, float az, float bx, float by, float bz, int color, int covered) {
        frontFaces(covered);
        float[] lo = {ax, ay, az};
        float[] hi = {bx, by, bz};
        for (int face = 0; face < 6; face++) {
            if (!front[face]) {
                continue;
            }
            int axis = face / 2;
            int u = (axis + 1) % 3;
            int v = (axis + 2) % 3;
            float[] c = new float[3];
            c[axis] = (face & 1) == 0 ? lo[axis] : hi[axis];
            // drei Ecken der Seite: das Bild ist ein Parallelogramm
            c[u] = lo[u];
            c[v] = lo[v];
            project(c[0], c[1], c[2]);
            float ox = px;
            float oy = py;
            c[u] = hi[u];
            project(c[0], c[1], c[2]);
            float ux = px;
            float uy = py;
            c[u] = lo[u];
            c[v] = hi[v];
            project(c[0], c[1], c[2]);
            g.pose().pushMatrix();
            g.pose().mul(new Matrix3x2f(ux - ox, uy - oy, px - ox, py - oy, ox, oy));
            g.fill(0, 0, 1, 1, col(color));
            g.pose().popMatrix();
        }
    }

    /** Eigener Charakter bei (x, y, z), Blickrichtung bodyYaw (Grad); der Zustand zum Anpassen. */
    AvatarRenderState character(float x, float y, float z, float bodyYaw) {
        AvatarRenderState state = (AvatarRenderState) renderState(Minecraft.getInstance().player);
        stand(state);
        state.bodyRot = bodyYaw;
        PreviewScene.add(state, x, y, z, 1.0F, 0.0F, 0.0F);
        return state;
    }

    /** Mob bei (x, y, z), Blickrichtung bodyYaw (Grad), ruhig stehend. */
    LivingEntityRenderState mob(LivingEntity entity, float x, float y, float z, float bodyYaw) {
        LivingEntityRenderState state = (LivingEntityRenderState) renderState(entity);
        state.bodyRot = bodyYaw;
        state.yRot = 0.0F;
        state.xRot = 0.0F;
        state.walkAnimationSpeed = 0.0F;
        state.hasRedOverlay = false;
        state.deathTime = 0.0F;
        PreviewScene.add(state, x, y, z, 1.0F, 0.0F, 0.0F);
        return state;
    }

    /** Beliebiges Entity (Kristall, Boot ...) bei (x, y, z) in Groesse size. */
    EntityRenderState thing(Entity entity, float x, float y, float z, float size) {
        EntityRenderState state = renderState(entity);
        PreviewScene.add(state, x, y, z, size, 0.0F, 0.0F);
        return state;
    }

    /** Ganzer Block wie in der Welt (echtes Block-Modell) mit der Unterseite bei y, mittig ueber (x, z). */
    void block(Block block, float x, float y, float z) {
        PreviewScene.addBlock(block.defaultBlockState(), x, y, z, 1.0F);
    }

    /** Block mit eigener Groesse (0..1, z.B. beim Erscheinen), Unterseite bei y. */
    void block(Block block, float x, float y, float z, float size) {
        block(block.defaultBlockState(), x, y, z, size);
    }

    /** Bestimmter Block-Zustand (z.B. Kiste mit dem Schloss nach vorne). */
    void block(BlockState state, float x, float y, float z, float size) {
        if (size > 0.02F) {
            PreviewScene.addBlock(state, x, y, z, size);
        }
    }

    /** Item frei in der Szene (fliegend, liegend): Groesse, Drehung um die Hochachse und Kippung (Bogenmass). */
    void item(Item item, float x, float y, float z, float size, float yaw, float pitch) {
        PreviewScene.add(itemState(item), x, y, z, size, yaw, pitch);
    }

    /** Item in die Haupt- oder Neben-Hand (nur in dieser Szene, das echte Inventar bleibt, wie es ist). */
    void hold(ArmedEntityRenderState state, LivingEntity owner, boolean mainHand, ItemStack stack) {
        HumanoidArm arm = mainHand ? state.mainArm : state.mainArm.getOpposite();
        boolean right = arm == HumanoidArm.RIGHT;
        ItemStackRenderState target = right ? state.rightHandItemState : state.leftHandItemState;
        Minecraft.getInstance().getItemModelResolver().updateForLiving(target, stack,
                right ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND, owner);
        HumanoidModel.ArmPose pose = stack.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        if (right) {
            state.rightHandItemStack = stack;
            state.rightArmPose = pose;
        } else {
            state.leftHandItemStack = stack;
            state.leftArmPose = pose;
        }
    }

    /** Haltung eines Arms (Haupt- oder Neben-Hand), z.B. Bogen spannen oder Schild heben. */
    static void armPose(ArmedEntityRenderState state, boolean mainHand, HumanoidModel.ArmPose pose) {
        HumanoidArm arm = mainHand ? state.mainArm : state.mainArm.getOpposite();
        if (arm == HumanoidArm.RIGHT) {
            state.rightArmPose = pose;
        } else {
            state.leftArmPose = pose;
        }
    }

    /** Schlag zwischen from und to der Schleife (sonst Ruhe). */
    static void swing(HumanoidRenderState state, float phase, float from, float to) {
        swing(state, phase, from, to, SwingAnimationType.WHACK);
    }

    /** Wie {@link #swing(HumanoidRenderState, float, float, float)}, mit eigener Schlag-Art (z.B. STAB). */
    static void swing(HumanoidRenderState state, float phase, float from, float to, SwingAnimationType type) {
        Compat.previewSwing(state, window(phase, from, to), type);
    }

    Zombie zombie() {
        if (zombie == null) {
            zombie = prepare(new Zombie(level));
            zombie.setAggressive(true);
        }
        return zombie;
    }

    /** Zombie mit Schild in der Neben-Hand (Arme unten, damit man den Schild sieht). */
    Zombie guard() {
        if (guard == null) {
            guard = prepare(new Zombie(level));
            guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }
        return guard;
    }

    Villager villager() {
        if (villager == null) {
            villager = create("villager");
        }
        return villager;
    }

    WanderingTrader trader() {
        if (trader == null) {
            trader = create("wandering_trader");
        }
        return trader;
    }

    Pillager pillager() {
        if (pillager == null) {
            pillager = create("pillager");
            pillager.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        }
        return pillager;
    }

    EndCrystal crystal() {
        if (crystal == null) {
            crystal = prepare(new EndCrystal(level, 0.0D, 0.0D, 0.0D));
            crystal.setShowBottom(false);
        }
        return crystal;
    }

    Boat boat() {
        if (boat == null) {
            boat = create("oak_boat");
            boat.setYRot(90.0F);
        }
        return boat;
    }

    /** Entity nach Namen (die Typ-Konstanten liegen je Version in einer anderen Klasse). */
    @SuppressWarnings("unchecked")
    private <T extends Entity> T create(String id) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace(id));
        return prepare((T) type.create(level, EntitySpawnReason.LOAD));
    }

    /** Ein anderer Spieler (Standard-Skin). */
    RemotePlayer stranger() {
        if (stranger == null) {
            stranger = prepare(new RemotePlayer(level, new GameProfile(UUID.fromString("5f2b8c1e-3a4d-4e6f-9b70-1c2d3e4f5a6b"), "Steve")));
        }
        return stranger;
    }

    private void reset(ClientLevel level) {
        this.level = level;
        items.clear();
        PreviewScene.forgetBlocks();
        zombie = null;
        guard = null;
        villager = null;
        trader = null;
        pillager = null;
        crystal = null;
        boat = null;
        stranger = null;
        anchor = renderState(prepare(new ItemEntity(level, 0.0D, 0.0D, 0.0D, ItemStack.EMPTY)));
    }

    /** Kommt nie in die Welt, braucht aber eine ID (Zufalls-Startwert der Modelle); negativ = kollidiert mit keiner. */
    private <T extends Entity> T prepare(T entity) {
        entity.setId(nextId--);
        return entity;
    }

    private EntityRenderState itemState(Item item) {
        EntityRenderState state = items.get(item);
        if (state == null) {
            state = renderState(prepare(new ItemEntity(level, 0.0D, 0.0D, 0.0D, new ItemStack(item))));
            // ohne Drehen und Schweben
            state.ageInTicks = 0.0F;
            if (state instanceof ItemEntityRenderState itemState) {
                itemState.bobOffset = 0.0F;
            }
            items.put(item, state);
        }
        return state;
    }

    /** Zustand fuers Zeichnen: voll beleuchtet, ohne Schatten, Umriss, Feuer und Namen. */
    private static EntityRenderState renderState(Entity entity) {
        EntityRenderer<? super Entity, ?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        EntityRenderState state = renderer.createRenderState(entity, 1.0F);
        state.lightCoords = FULL_BRIGHT;
        state.shadowPieces.clear();
        state.outlineColor = 0;
        state.nameTag = null;
        state.displayFireAnimation = false;
        return state;
    }

    /** Ruhig stehend, ohne Schatten, Umriss und Vanilla-Namen (wie im Inventar). */
    private static void stand(AvatarRenderState state) {
        state.scoreText = null;
        state.pose = Pose.STANDING;
        state.bedOrientation = null;
        state.isCrouching = false;
        state.isFallFlying = false;
        state.isVisuallySwimming = false;
        state.swimAmount = 0.0F;
        state.isPassenger = false;
        state.isAutoSpinAttack = false;
        state.isUsingItem = false;
        state.walkAnimationPos = 0.0F;
        state.walkAnimationSpeed = 0.0F;
        state.deathTime = 0.0F;
        state.hasRedOverlay = false;
        state.isInvisible = false;
        state.isInvisibleToPlayer = false;
        state.isUpsideDown = false;
        state.xRot = 0.0F;
        state.yRot = 0.0F;
        Compat.previewSwing(state, 0.0F, SwingAnimationType.WHACK);
        // Groesse aus Attributen herausrechnen (wie im Inventar)
        state.boundingBoxWidth /= state.scale;
        state.boundingBoxHeight /= state.scale;
        state.scale = 1.0F;
    }
}
