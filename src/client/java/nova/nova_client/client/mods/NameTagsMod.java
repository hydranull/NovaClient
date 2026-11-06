package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NameTagsMenu;
import nova.nova_client.client.NovaGui;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Name tags: Schild ueber gedroppten Items (Anzahl + Name) und ueber Spielern (Name, Leben, Item in der Hand und
 * Rueestung). Die eigene Figur bekommt ihr Schild nur in der Third-Person-Ansicht.
 */
public class NameTagsMod extends RenderMod {
    private static final double ITEM_RANGE = 32.0D;
    private static final double PLAYER_RANGE = 128.0D;
    /** Hoechstens so viele Schilder pro Bild (die naechsten). */
    private static final int MAX_TAGS = 64;
    /** Reihenfolge der Icons ueber dem Kopf: Haupthand, Rueestung von oben nach unten. */
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};
    public static final float MIN_SIZE = 0.5F;
    public static final float MAX_SIZE = 2.5F;

    // Einstellungen
    /** Groesse der Schilder (1 = normal). */
    public static float size = 1.0F;

    // volatile: wird auch vom Render-Thread (AvatarRendererMixin) gelesen
    private static volatile boolean enabled;

    public NameTagsMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        NameTagsMod.enabled = enabled;
    }

    /** Aktiv? Dann wird der normale Spielername ausgeblendet. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Ist das ein Nametag vom Server? Viele Server zeigen Namen/Raenge ueber eigene Text-Displays oder unsichtbare
     * Armorstands, die auf dem Spieler sitzen oder direkt ueber seinem Kopf schweben.
     */
    public static boolean isServerTag(Entity entity) {
        if (!enabled || !(entity instanceof Display.TextDisplay || entity instanceof ArmorStand && entity.isInvisible())) {
            return false;
        }
        if (entity.getRootVehicle() instanceof Player) {
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return false;
        }
        for (Player player : client.level.players()) {
            double dx = entity.getX() - player.getX();
            double dz = entity.getZ() - player.getZ();
            double dy = entity.getY() - player.getY();
            if (dx * dx + dz * dz < 0.8D * 0.8D && dy > player.getBbHeight() - 1.0D && dy < player.getBbHeight() + 2.0D) {
                return true;
            }
        }
        return false;
    }

    private record Tag(Entity entity, float x, float y, double distance) {
    }

    @Override
    public void execute(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.getDebugOverlay().showDebugScreen()) {
            return;
        }
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Camera camera = client.gameRenderer.mainCamera();
        Vec3 cameraPos = camera.position();
        Matrix4f matrix = Compat.viewProjection(new Matrix4f());
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();

        List<Tag> tags = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            Vec3 anchor;
            double range;
            if (entity instanceof ItemEntity) {
                anchor = entity.getPosition(partialTick).add(0.0D, entity.getBbHeight() + 0.45D, 0.0D);
                range = ITEM_RANGE;
            } else if (entity instanceof Player player && !player.isSpectator()) {
                if (player == client.player && !camera.isDetached()) {
                    continue;
                }
                // Dort, wo sonst der normale Minecraft-Name sitzt (der ist ausgeblendet)
                anchor = entity.getPosition(partialTick).add(0.0D, entity.getBbHeight() + 0.5D, 0.0D);
                range = PLAYER_RANGE;
            } else {
                continue;
            }
            double distance = anchor.distanceTo(cameraPos);
            if (distance > range) {
                continue;
            }
            float[] screen = project(matrix, anchor.subtract(cameraPos), screenW, screenH);
            if (screen != null) {
                tags.add(new Tag(entity, screen[0], screen[1], distance));
            }
        }

        // Nur die naechsten MAX_TAGS, Spieler zuerst (hunderte Items an einer Farm kosten sonst viele FPS und sind
        // eh unlesbar)
        if (tags.size() > MAX_TAGS) {
            tags.sort(Comparator.comparing((Tag tag) -> tag.entity() instanceof ItemEntity)
                    .thenComparingDouble(Tag::distance));
            tags.subList(MAX_TAGS, tags.size()).clear();
        }
        // Weit entfernte zuerst, nahe liegen oben drauf
        tags.sort(Comparator.comparingDouble(Tag::distance).reversed());
        Font font = client.font;
        for (Tag tag : tags) {
            // Nah normal gross, weiter weg etwas kleiner (aber lesbar)
            float scale = size * (float) Mth.clamp(1.0D - tag.distance() / 48.0D, 0.55D, 1.0D);
            g.pose().pushMatrix();
            g.pose().translate(tag.x(), tag.y());
            g.pose().scale(scale, scale);
            if (tag.entity() instanceof ItemEntity item) {
                drawItem(g, font, item.getItem());
            } else if (tag.entity() instanceof Player player) {
                drawPlayer(g, font, player, tag.distance());
            }
            g.pose().popMatrix();
        }
    }

    /** Welt-Position (relativ zur Kamera) auf den Bildschirm; null, wenn hinter der Kamera oder ausserhalb. */
    private static float[] project(Matrix4f matrix, Vec3 relative, int screenW, int screenH) {
        Vector4f v = new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1.0F);
        matrix.transform(v);
        if (v.w <= 0.05F) {
            return null;
        }
        float nx = v.x / v.w;
        float ny = v.y / v.w;
        if (Math.abs(nx) > 1.2F || Math.abs(ny) > 1.2F) {
            return null;
        }
        return new float[]{(nx + 1.0F) * 0.5F * screenW, (1.0F - ny) * 0.5F * screenH};
    }

    // ---------------------------------------------------------------- Zeichnen (Ursprung = Ankerpunkt, Schild waechst nach oben)

    /** Vorschau im Nova-Menue: das Schild eines Spielers in 3 Metern, Ursprung = Ankerpunkt ueber dem Kopf. */
    public static void drawPreview(GuiGraphicsExtractor g, Font font, Player player) {
        drawPlayer(g, font, player, 3.0D);
    }

    private static void drawItem(GuiGraphicsExtractor g, Font font, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        String text = stack.getCount() + "x " + stack.getHoverName().getString();
        int w = 16 + 3 + font.width(text) + 6;
        int x0 = -w / 2;
        int y0 = -18;
        NovaGui.item(g, stack, x0 + 3 + 8, y0 + 9, 0.8F);
        g.text(font, text, x0 + 3 + 16 + 3, y0 + 5, 0xFFFFFFFF, true);
    }

    private static void drawPlayer(GuiGraphicsExtractor g, Font font, Player player, double distance) {
        // Unten: Name, Leben, Entfernung
        float health = player.getHealth() + player.getAbsorptionAmount();
        String hp = String.format(Locale.ROOT, "%.1f", health / 2.0F) + "❤";
        String name = player.getName().getString();
        String dist = Math.round(distance) + "m";
        int hpColor = NovaGui.mix(0xFFFF4D5E, 0xFF3DDC97, Mth.clamp(player.getHealth() / Math.max(1.0F, player.getMaxHealth()), 0.0F, 1.0F));
        int textW = font.width(name) + 4 + font.width(hp) + 4 + font.width(dist);
        int w = textW + 8;
        int tx = -w / 2 + 4;
        g.text(font, name, tx, -10, 0xFFFFFFFF, true);
        tx += font.width(name) + 4;
        g.text(font, hp, tx, -10, hpColor, true);
        tx += font.width(hp) + 4;
        g.text(font, dist, tx, -10, 0xFFAAAAAA, true);

        // Darueber: Item in der Hand + Rueestung
        List<ItemStack> items = new ArrayList<>();
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                items.add(stack);
            }
        }
        if (items.isEmpty()) {
            return;
        }
        int size = 18;
        int rowW = items.size() * size;
        int x = -rowW / 2;
        int y = -12 - 2 - size - 3;
        for (ItemStack stack : items) {
            g.item(stack, x + 1, y);
            if (stack.getCount() > 1) {
                String count = String.valueOf(stack.getCount());
                g.text(font, count, x + size - font.width(count), y + 9, 0xFFFFFFFF, true);
            }
            if (stack.isDamageableItem()) {
                float durability = 1.0F - stack.getDamageValue() / (float) Math.max(1, stack.getMaxDamage());
                g.fill(x + 2, y + size, x + 2 + Math.round(14 * durability), y + size + 2,
                        NovaGui.mix(0xFFFF4D5E, 0xFF3DDC97, durability));
            }
            x += size;
        }
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new NameTagsMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "size", String.valueOf(size));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            size = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "size", String.valueOf(size)).trim()), MIN_SIZE, MAX_SIZE);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
    }
}
