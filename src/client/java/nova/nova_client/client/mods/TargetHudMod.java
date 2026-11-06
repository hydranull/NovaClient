package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.CombatEvents;
import nova.nova_client.client.HudEditorScreen;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.Rect;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Target HUD: im Kampf eine Karte unter dem Fadenkreuz mit Kopf, Name, Leben (weich nachlaufender Balken, Gold fuer
 * Absorption), Entfernung und Ruestung + Waffe des Gegners. Ziel ist, wen du anschaust oder gerade getroffen hast; die
 * Karte bleibt kurz stehen, wenn du wegschaust.
 */
public class TargetHudMod extends RenderMod {
    private static final int W = 152;
    private static final int H = 44;
    private static final int FACE = 26;
    private static final long KEEP_MS = 2500L;
    private static final double MAX_DISTANCE = 16.0D;
    private static final EquipmentSlot[] GEAR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

    public static boolean mobs = false;
    public static float scale = 1.0F;
    /** Eigene Position (Anteil vom Bildschirm, linke obere Ecke); -1 = unter dem Fadenkreuz. */
    public static float posX = -1.0F;
    public static float posY = -1.0F;
    public static Rect bounds;

    private LivingEntity target;
    private long seenAt;
    private float shown;
    private float health;
    private long lastNanos;

    public TargetHudMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        target = null;
        shown = 0.0F;
        bounds = null;
    }

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        long nanos = System.nanoTime();
        float dt = lastNanos == 0L ? 0.0F : Math.min(0.1F, (nanos - lastNanos) / 1.0E9F);
        lastNanos = nanos;
        boolean editor = client.gui.screen() instanceof HudEditorScreen;
        LivingEntity next = editor ? client.player : pick(client);
        if (next != null && next != target) {
            target = next;
            health = next.getHealth();
        }
        if (next != null) {
            seenAt = Util.getMillis();
        }
        boolean keep = target != null && target.isAlive() && (next != null || Util.getMillis() - seenAt < KEEP_MS
                || CombatEvents.hitByMeRecently(target.getId(), KEEP_MS));
        shown = NovaGui.approach(shown, keep ? 1.0F : 0.0F, keep ? 12.0F : 7.0F, dt);
        if (shown <= 0.01F || target == null) {
            bounds = null;
            if (shown <= 0.01F) {
                target = null;
            }
            return;
        }
        health += (target.getHealth() - health) * Math.min(1.0F, dt * 6.0F);

        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        float w = W * scale;
        float h = H * scale;
        float left = posX >= 0.0F ? Mth.clamp(posX * screenW, 0.0F, screenW - w) : (screenW - w) / 2.0F;
        float top = posY >= 0.0F ? Mth.clamp(posY * screenH, 0.0F, screenH - h) : screenH / 2.0F + 22.0F;
        bounds = new Rect(Math.round(left), Math.round(top), Math.round(left + w), Math.round(top + h));

        float appear = NovaGui.easeOutCubic(shown);
        g.pose().pushMatrix();
        g.pose().translate(left, top + (1.0F - appear) * 6.0F);
        g.pose().scale(scale, scale);
        draw(g, client.font, client, target, health, appear);
        g.pose().popMatrix();
    }

    /** Vorschau im Nova-Menue: die Karte mit dir selbst als Ziel (in Originalgroesse), linke obere Ecke bei (x, y). */
    public static Rect drawPreview(GuiGraphicsExtractor g, Font font, int x, int y, float a) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        draw(g, font, client, client.player, client.player.getHealth(), a);
        g.pose().popMatrix();
        return new Rect(x, y, x + W, y + H);
    }

    /** Wen du anschaust (innerhalb von 16 Bloecken); Mobs nur, wenn eingeschaltet. */
    private static LivingEntity pick(Minecraft client) {
        Entity looked = client.crosshairPickEntity;
        if (looked instanceof LivingEntity living && living != client.player && living.isAlive()
                && (mobs || living instanceof Player) && living.distanceTo(client.player) <= MAX_DISTANCE) {
            return living;
        }
        return null;
    }

    /** Karte ab (0, 0); health = angezeigtes Leben (im Spiel weich nachlaufend). */
    private static void draw(GuiGraphicsExtractor g, Font font, Minecraft client, LivingEntity entity, float health, float a) {
        NovaShapes.rect(g, 0, 0, W, H, 8, NovaGui.alpha(0xE60D0D13, a));
        NovaShapes.outline(g, 0, 0, W, H, 8, NovaGui.alpha(0x24FFFFFF, a));

        // Kopf (Spieler) oder Anfangsbuchstabe (Mob)
        int fx = 8;
        int fy = (H - FACE) / 2;
        if (entity instanceof AbstractClientPlayer player) {
            PlayerFaceExtractor.extractRenderState(g, player.getSkin(), fx, fy, FACE, NovaGui.alpha(0xFFFFFFFF, a));
        } else {
            NovaShapes.rect(g, fx, fy, fx + FACE, fy + FACE, 6, NovaGui.alpha(NovaGui.BRAND, 0.35F * a));
            String letter = entity.getName().getString().substring(0, 1).toUpperCase(Locale.ROOT);
            NovaGui.text(g, font, letter, fx + (FACE - NovaGui.width(font, letter)) / 2, fy + 9, NovaGui.alpha(0xFFFFFFFF, a), false);
        }

        int x0 = fx + FACE + 8;
        int x1 = W - 8;
        float max = Math.max(1.0F, entity.getMaxHealth());
        float absorption = entity.getAbsorptionAmount();
        String hp = String.format(Locale.ROOT, "%.1f", entity.getHealth() + absorption);
        int hpW = NovaGui.width(font, hp);
        NovaGui.text(g, font, NovaGui.ellipsize(font, entity.getName().getString(), x1 - x0 - hpW - 6), x0, 7,
                NovaGui.alpha(0xFFF4F5F8, a), false);
        float ratio = Mth.clamp(health / max, 0.0F, 1.0F);
        int barColor = ratio > 0.6F ? 0xFF5BE38A : ratio > 0.3F ? 0xFFFFC857 : 0xFFFF5C6C;
        NovaGui.text(g, font, hp, x1 - hpW, 7, NovaGui.alpha(barColor, a), false);

        // Lebensbalken: weich nachlaufend, Absorption in Gold am Ende
        int by = 20;
        NovaShapes.rect(g, x0, by, x1, by + 4, 2, NovaGui.alpha(0x1FFFFFFF, a));
        int fill = x0 + Math.round((x1 - x0) * ratio);
        if (fill > x0 + 1) {
            NovaShapes.rect(g, x0, by, fill, by + 4, 2, NovaGui.alpha(barColor, a));
        }
        if (absorption > 0.0F) {
            int gold = Math.round((x1 - x0) * Mth.clamp(absorption / max, 0.0F, 1.0F));
            NovaShapes.rect(g, x1 - gold, by, x1, by + 4, 2, NovaGui.alpha(0xFFFFD34D, a));
        }

        // Ruestung + Waffe klein, Entfernung rechts
        float itemScale = 0.62F;
        int ix = x0;
        for (EquipmentSlot slot : GEAR) {
            ItemStack stack = entity.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                g.pose().pushMatrix();
                g.pose().translate(ix, 28);
                g.pose().scale(itemScale, itemScale);
                g.item(stack, 0, 0);
                g.pose().popMatrix();
                ix += 11;
            }
        }
        String distance = String.format(Locale.ROOT, "%.1f m", entity.distanceTo(client.player));
        NovaGui.text(g, font, distance, x1 - NovaGui.width(font, distance), 30, NovaGui.alpha(0xFF8C93A3, a), false);
    }

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "Health, gear and distance of who you fight", List.of(
                new ModOptionsMenu.Toggle("Mobs too", () -> mobs, v -> mobs = v),
                new ModOptionsMenu.Slider("Size", 0.6D, 1.6D, 0.05D, () -> scale, v -> scale = (float) v,
                        v -> Math.round(v * 100.0D) + "%"),
                new ModOptionsMenu.Action(() -> "Move on screen",
                        () -> Minecraft.getInstance().gui.setScreen(new HudEditorScreen(Minecraft.getInstance().gui.screen())))));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "mobs", String.valueOf(mobs));
        properties.setProperty(prefix + "scale", String.valueOf(scale));
        properties.setProperty(prefix + "x", String.valueOf(posX));
        properties.setProperty(prefix + "y", String.valueOf(posY));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        mobs = Boolean.parseBoolean(properties.getProperty(prefix + "mobs", String.valueOf(mobs)));
        try {
            scale = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "scale", String.valueOf(scale)).trim()), 0.6F, 1.6F);
            posX = Float.parseFloat(properties.getProperty(prefix + "x", String.valueOf(posX)).trim());
            posY = Float.parseFloat(properties.getProperty(prefix + "y", String.valueOf(posY)).trim());
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
