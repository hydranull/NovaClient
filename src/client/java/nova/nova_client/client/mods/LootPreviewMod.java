package nova.nova_client.client.mods;

import nova.nova_client.client.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.EspOptionsMenu;
import nova.nova_client.client.LootTooltip;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Loot Preview: zeigt den Inhalt von Shulker-Boxen und Kisten als Mini-Inventar.
 * - im Tooltip (Shulker-Items, Endertruhe)
 * - wenn das Fadenkreuz auf einer platzierten Kiste/Shulker/Fass liegt (Inhalt ist bekannt, sobald man sie einmal
 *   geoeffnet oder den gefuellten Shulker selbst platziert hat - der Server schickt ihn sonst nie)
 * - wenn man einen gedroppten Shulker am Boden anschaut
 */
public class LootPreviewMod extends GameTickMod {
    private static final int MAX_REMEMBERED = 600;
    private static final double DROP_RANGE = 8.0D;

    // Einstellungen
    public static boolean enderChest = true;
    /** Vorschau ueberall (Tooltip, platziert, gedroppt) nur mit gedrueckter Shift-Taste. */
    public static boolean requireShift = false;
    public static boolean placed = true;
    public static boolean dropped = true;

    private static LootPreviewMod instance;
    /** Zuletzt gesehener Inhalt der Endertruhe (27 Plaetze), null = noch nie geoeffnet. */
    private static List<ItemStack> enderContents;

    /** Bekannter Inhalt platzierter Behaelter je Position (aelteste zuerst). */
    private static final Map<Long, List<ItemStack>> BLOCKS = new LinkedHashMap<>();
    private static ClientLevel trackedLevel;

    // Behaelter, der gerade per Rechtsklick geoeffnet wird / ist
    private static BlockPos pendingOpen;
    private static int pendingOpenTicks;
    private static BlockPos openPos;

    // Gefuellter Shulker, der gerade platziert wird
    private static BlockPos pendingPlace;
    private static List<ItemStack> pendingPlaceItems;
    private static int pendingPlaceTicks;

    public LootPreviewMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    public static boolean isOn() {
        return instance != null && instance.active;
    }

    /** Vorschau sichtbar? (an + ggf. Shift gedrueckt) - gilt fuer Tooltip, platzierte und gedroppte Behaelter */
    public static boolean showing() {
        return isOn() && (!requireShift || Minecraft.getInstance().hasShiftDown());
    }

    private static boolean isContainer(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ShulkerBoxBlock || block instanceof ChestBlock || block instanceof BarrelBlock;
    }

    // ---------------------------------------------------------------- Mitlesen

    /** Rechtsklick auf einen Block (aus dem Mixin): Oeffnen eines Behaelters oder Platzieren eines Shulkers merken. */
    public static void onUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (!isOn() || player.level() == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        if (isContainer(state) && !player.isSecondaryUseActive()) {
            pendingOpen = pos.immutable();
            pendingOpenTicks = 0;
        }
        ItemStack stack = player.getItemInHand(hand);
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null && stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock) {
            pendingPlace = (state.canBeReplaced() ? pos : pos.relative(hit.getDirection())).immutable();
            pendingPlaceItems = new ArrayList<>();
            Compat.containerItems(contents).forEach(pendingPlaceItems::add);
            pendingPlaceTicks = 0;
        }
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return;
        }
        if (level != trackedLevel) {
            trackedLevel = level;
            BLOCKS.clear();
            pendingOpen = null;
            pendingPlace = null;
            openPos = null;
        }

        // Selbst platzierter, gefuellter Shulker: Inhalt ist bekannt
        if (pendingPlace != null) {
            if (level.getBlockState(pendingPlace).getBlock() instanceof ShulkerBoxBlock) {
                remember(pendingPlace, pendingPlaceItems);
                pendingPlace = null;
            } else if (++pendingPlaceTicks > 20) {
                pendingPlace = null;
            }
        }

        boolean containerScreen = client.gui.screen() instanceof AbstractContainerScreen<?>
                && !(client.gui.screen() instanceof InventoryScreen)
                && !(client.gui.screen() instanceof CreativeModeInventoryScreen);
        if (pendingOpen != null) {
            if (containerScreen) {
                openPos = pendingOpen;
                pendingOpen = null;
            } else if (++pendingOpenTicks > 20) {
                pendingOpen = null;
            }
        }
        if (openPos != null && containerScreen) {
            AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) client.gui.screen();
            List<ItemStack> items = new ArrayList<>();
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container != player.getInventory()) {
                    items.add(slot.getItem().copy());
                }
            }
            if (!items.isEmpty()) {
                remember(openPos, items);
                // Doppelkiste: auch die andere Haelfte kennt den Inhalt
                BlockState state = level.getBlockState(openPos);
                if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    remember(openPos.relative(ChestBlock.getConnectedDirection(state)), items);
                }
            }
        } else if (!containerScreen) {
            openPos = null;
        }

        // Endertruhe beim Oeffnen mitlesen
        if (enderChest && client.gui.screen() instanceof AbstractContainerScreen<?> screen
                && screen.getTitle().getString().equals(Component.translatable("container.enderchest").getString())) {
            List<ItemStack> items = new ArrayList<>();
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container != player.getInventory() && items.size() < 27) {
                    items.add(slot.getItem().copy());
                }
            }
            if (items.size() == 27) {
                enderContents = items;
            }
        }
    }

    private static void remember(BlockPos pos, List<ItemStack> items) {
        BLOCKS.remove(pos.asLong());
        BLOCKS.put(pos.asLong(), items);
        while (BLOCKS.size() > MAX_REMEMBERED) {
            BLOCKS.remove(BLOCKS.keySet().iterator().next());
        }
    }

    // ---------------------------------------------------------------- Tooltip

    /** Tooltip-Bild fuer ein Item, oder leer, wenn es nichts zu zeigen gibt. */
    public static Optional<TooltipComponent> preview(ItemStack stack) {
        if (!showing() || stack.isEmpty()) {
            return Optional.empty();
        }
        List<ItemStack> items = contents(stack);
        if (items != null) {
            return Optional.of(new LootTooltip.Preview(items, tint(stack)));
        }
        if (enderChest && stack.is(Items.ENDER_CHEST) && enderContents != null) {
            return Optional.of(new LootTooltip.Preview(enderContents, 0xFF1F5E5A));
        }
        return Optional.empty();
    }

    /** Inhalt eines Items (Shulker-Box usw.), null wenn leer oder kein Behaelter. */
    private static List<ItemStack> contents(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null || Compat.containerNonEmpty(contents).findAny().isEmpty()) {
            return null;
        }
        List<ItemStack> items = new ArrayList<>();
        Compat.containerItems(contents).forEach(items::add);
        return items;
    }

    /** Farbe des Rasters passend zum Item (z.B. Farbe der Shulker-Box). */
    private static int tint(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block ? tint(block.getBlock()) : 0xFF8B5FA8;
    }

    private static int tint(Block block) {
        int color = block.defaultMapColor().col;
        return color != 0 ? 0xFF000000 | color : 0xFF8B5FA8;
    }

    // ---------------------------------------------------------------- Anzeige in der Welt

    /** HUD: Vorschau neben dem Fadenkreuz fuer angeschaute Behaelter oder gedroppte Shulker. */
    public static void drawHud(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!showing() || client.player == null || client.level == null || client.gui.screen() != null) {
            return;
        }
        Font font = client.font;

        // 1. Gedroppter Shulker, auf den man schaut
        if (dropped) {
            ItemEntity item = lookedAtItem(client);
            if (item != null) {
                ItemStack stack = item.getItem();
                draw(graphics, font, stack.getHoverName().getString(), contents(stack), tint(stack),
                        "Empty (or the server hides the contents)");
                return;
            }
        }

        // 2. Platzierter Behaelter unter dem Fadenkreuz
        if (placed && client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = client.level.getBlockState(pos);
            String name = state.getBlock().getName().getString();
            if (state.getBlock() instanceof EnderChestBlock && enderChest) {
                draw(graphics, font, name, enderContents, 0xFF1F5E5A, "Open your ender chest once");
            } else if (isContainer(state)) {
                List<ItemStack> items = BLOCKS.get(pos.asLong());
                draw(graphics, font, name, items, tint(state.getBlock()), "Open once to preview");
            }
        }
    }

    /** Shulker-Box oder anderes Item, das Inhalt haben kann. */
    private static boolean isContainerItem(ItemStack stack) {
        return stack.has(DataComponents.CONTAINER)
                || stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * Gedroppter Shulker, auf den man schaut: das Item mit dem kleinsten Winkel zur Blickrichtung
     * (grosszuegige Toleranz, Items am Boden sind klein) und nicht hinter dem angeschauten Block.
     */
    private static ItemEntity lookedAtItem(Minecraft client) {
        Entity camera = client.player;
        Vec3 eye = camera.getEyePosition();
        Vec3 look = camera.getViewVector(1.0F);
        double blockDistance = DROP_RANGE;
        if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK) {
            blockDistance = Math.min(blockDistance, client.hitResult.getLocation().distanceTo(eye) + 1.0D);
        }
        ItemEntity best = null;
        double bestAngle = Double.MAX_VALUE;
        AABB area = camera.getBoundingBox().inflate(DROP_RANGE);
        for (Entity entity : client.level.getEntities(camera, area, e -> e instanceof ItemEntity)) {
            ItemEntity item = (ItemEntity) entity;
            if (!isContainerItem(item.getItem())) {
                continue;
            }
            Vec3 center = item.position().add(0.0D, 0.25D, 0.0D);
            Vec3 toItem = center.subtract(eye);
            double distance = toItem.length();
            if (distance < 0.1D || distance > blockDistance) {
                continue;
            }
            double cos = look.dot(toItem.scale(1.0D / distance));
            double angle = Math.acos(Math.max(-1.0D, Math.min(1.0D, cos)));
            // Toleranz: ca. 0.7 Bloecke um das Item herum, mindestens 4 Grad
            double tolerance = Math.max(Math.toRadians(4.0D), Math.atan(0.7D / distance));
            if (angle <= tolerance && angle < bestAngle) {
                bestAngle = angle;
                best = item;
            }
        }
        return best;
    }

    /** Kasten oben in der Mitte: Titel + Raster, oder Hinweis, wenn der Inhalt unbekannt ist. */
    private static void draw(GuiGraphicsExtractor graphics, Font font, String title, List<ItemStack> items, int tint,
                             String unknownHint) {
        int center = graphics.guiWidth() / 2;
        int top = 6;
        if (items == null) {
            String hint = unknownHint;
            int w = Math.max(font.width(title), font.width(hint)) + 10;
            int x = center - w / 2;
            NovaGui.hudBackground(graphics, x, top, x + w, top + 24);
            graphics.text(font, title, center - font.width(title) / 2, top + 3, 0xFFFFFFFF, false);
            graphics.text(font, hint, center - font.width(hint) / 2, top + 14, NovaGui.TEXT_MUTED, false);
            return;
        }
        LootTooltip.Client grid = new LootTooltip.Client(new LootTooltip.Preview(items, tint));
        int w = grid.getWidth(font);
        int h = grid.getHeight(font);
        int x = center - w / 2;
        NovaGui.hudBackground(graphics, x - 2, top, x + w + 2, top + h + 14);
        String name = NovaGui.ellipsize(font, title, w - 4);
        graphics.text(font, name, center - font.width(name) / 2, top + 3, 0xFFFFFFFF, false);
        grid.extractImage(font, x, top + 13, w, h, graphics);
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        List<EspOptionsMenu.Toggle> toggles = List.of(
                new EspOptionsMenu.Toggle("Placed shulkers & chests", () -> placed, () -> placed = !placed),
                new EspOptionsMenu.Toggle("Dropped shulkers on the ground", () -> dropped, () -> dropped = !dropped),
                new EspOptionsMenu.Toggle("Remember ender chest", () -> enderChest, () -> enderChest = !enderChest),
                new EspOptionsMenu.Toggle("Only while holding Shift (everywhere)", () -> requireShift, () -> requireShift = !requireShift));
        return new EspOptionsMenu(parent, name, "", 0xB45CF6, toggles, null);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "enderChest", String.valueOf(enderChest));
        properties.setProperty(prefix + "requireShift", String.valueOf(requireShift));
        properties.setProperty(prefix + "placed", String.valueOf(placed));
        properties.setProperty(prefix + "dropped", String.valueOf(dropped));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        enderChest = Boolean.parseBoolean(properties.getProperty(prefix + "enderChest", String.valueOf(enderChest)));
        requireShift = Boolean.parseBoolean(properties.getProperty(prefix + "requireShift", String.valueOf(requireShift)));
        placed = Boolean.parseBoolean(properties.getProperty(prefix + "placed", String.valueOf(placed)));
        dropped = Boolean.parseBoolean(properties.getProperty(prefix + "dropped", String.valueOf(dropped)));
    }
}
