package nova.nova_client.client.mods;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nova.nova_client.client.SchematicBuilderMenu;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaNotify;
import nova.nova_client.client.farmbuilder.BuildPlan;
import nova.nova_client.client.farmbuilder.ChestStore;
import nova.nova_client.client.farmbuilder.Estimate;
import nova.nova_client.client.farmbuilder.FarmBot;
import nova.nova_client.client.farmbuilder.Materials;
import nova.nova_client.client.farmbuilder.Placer;
import nova.nova_client.client.farmbuilder.Schematic;
import nova.nova_client.client.farmbuilder.SchematicLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Schematic Builder: Bauvorlage (.litematic, .schem, .nbt) laden, Platz in der Welt waehlen, Material-Kisten markieren - dann baut ein
 * Bot sie von selbst und moeglichst menschlich nach. Zeigt vorher Flaeche, Materialliste und geschaetzte Zeit.
 */
public class SchematicBuilderMod extends GameTickMod {
    /**
     * Blockaenderung vom Server (Haupt-Thread, BEVOR sie angewendet wird): der Bot rechnet betroffene Standplaetze
     * neu - aber nur, wenn sich wirklich etwas Begehbares/Sichtbares aendert (siehe FarmBot.onWorldChanged).
     */
    public static void onBlockChanged(BlockPos pos, net.minecraft.world.level.block.state.BlockState now) {
        BOT.onWorldChanged(pos, now);
    }

    public static final String[] SPEEDS = {"Relaxed", "Normal", "Fast", "Turbo"};
    private static final float[] SPEED_FACTORS = {0.75F, 1.0F, 1.3F, 2.0F};

    private static SchematicBuilderMod instance;

    // Einstellungen (gespeichert)
    public static String fileName = "";
    public static BlockPos origin;
    public static int rotation;
    public static int speedIndex = 1;
    public static boolean breakWrong = true;
    public static boolean pauseNearPlayers = true;
    public static boolean humanPauses = true;
    public static boolean storeJunk = true;
    public static boolean useTowers = true;
    public static boolean digThrough = true;
    /** Hauptmodus: streng Schicht fuer Schicht von unten nach oben. */
    public static boolean layerByLayer = true;
    public static boolean showPreview = true;
    public static final ChestStore CHESTS = new ChestStore();

    // Laufzeit
    public static Schematic schematic;
    public static String loadError = "";
    private static BuildPlan plan;
    public static boolean selectingChests;
    public static final FarmBot BOT = new FarmBot();

    // Kisten-Erkennung
    private static BlockPos lastLooked;
    private static BlockPos openedContainer;
    private static boolean containerOpen;

    // Vorschau (im Tick gebaut, pro Frame nur gezeichnet)
    private static BlockPos[] ghost = new BlockPos[0];
    private static int[] ghostColor = new int[0];
    private static int ghostTicks;

    public SchematicBuilderMod(String name, ModCategory category) {
        super(name, category);
        instance = this;
    }

    public static SchematicBuilderMod instance() {
        return instance;
    }

    // ================================================================ Dateien

    public static Path schematicDir() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("schematics");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // wird beim Auflisten gemeldet
        }
        return dir;
    }

    /** Alle Bauvorlagen im schematics-Ordner (auch Unterordner), relativ zum Ordner. */
    public static List<String> listFiles() {
        Path dir = schematicDir();
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, 3)) {
            stream.filter(Files::isRegularFile).filter(SchematicLoader::isSchematic)
                    .forEach(p -> names.add(dir.relativize(p).toString().replace('\\', '/')));
        } catch (IOException ignored) {
            // leer
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static boolean load(String name) {
        try {
            schematic = SchematicLoader.load(schematicDir().resolve(name));
            fileName = name;
            loadError = "";
            if (origin == null && Minecraft.getInstance().player != null) {
                origin = Minecraft.getInstance().player.blockPosition();
            }
            rebuildPlan();
            return true;
        } catch (IOException | RuntimeException e) {
            schematic = null;
            plan = null;
            loadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    /** Dateien per Drag & Drop ins Menue: in den schematics-Ordner kopieren. Gibt den ersten Namen zurueck. */
    public static String importFiles(List<Path> files) {
        String first = null;
        for (Path file : files) {
            if (!SchematicLoader.isSchematic(file)) {
                continue;
            }
            try {
                Path target = schematicDir().resolve(file.getFileName().toString());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (first == null) {
                    first = target.getFileName().toString();
                }
            } catch (IOException e) {
                loadError = "Copy failed: " + e.getMessage();
            }
        }
        return first;
    }

    // ================================================================ Platzierung

    public static BuildPlan plan() {
        return plan;
    }

    public static void rebuildPlan() {
        plan = schematic != null && origin != null ? new BuildPlan(schematic, origin, rotation) : null;
        ghostTicks = 0;
    }

    /** Ecke (unten Nord-West) dort, wo du stehst. */
    public static void cornerAtFeet() {
        if (Minecraft.getInstance().player != null) {
            origin = Minecraft.getInstance().player.blockPosition();
            rebuildPlan();
        }
    }

    /** Mitte der Flaeche dort, wo du stehst. */
    public static void centerOnMe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && plan != null) {
            BlockPos feet = mc.player.blockPosition();
            origin = feet.offset(-plan.sizeX / 2, 0, -plan.sizeZ / 2);
            rebuildPlan();
        } else {
            cornerAtFeet();
        }
    }

    /** Ecke auf den Block, auf den du schaust (eins darueber). */
    public static void cornerAtCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            origin = hit.getBlockPos().relative(hit.getDirection());
            rebuildPlan();
        }
    }

    public static void nudge(int dx, int dy, int dz) {
        if (origin != null) {
            origin = origin.offset(dx, dy, dz);
            rebuildPlan();
        }
    }

    /** 90 Grad drehen, die Mitte bleibt dabei an derselben Stelle. */
    public static void rotate() {
        if (plan == null) {
            rotation = (rotation + 1) & 3;
            rebuildPlan();
            return;
        }
        double cx = origin.getX() + plan.sizeX / 2.0D;
        double cz = origin.getZ() + plan.sizeZ / 2.0D;
        rotation = (rotation + 1) & 3;
        rebuildPlan();
        origin = BlockPos.containing(cx - plan.sizeX / 2.0D, origin.getY(), cz - plan.sizeZ / 2.0D);
        rebuildPlan();
    }

    public static float speed() {
        return SPEED_FACTORS[Mth.clamp(speedIndex, 0, SPEED_FACTORS.length - 1)];
    }

    public static long estimateSeconds() {
        if (plan == null) {
            return 0L;
        }
        if (BOT.running()) {
            return BOT.etaSeconds();
        }
        return Estimate.seconds(plan, plan.totalPlacements(), CHESTS.averageDistance(plan.origin), speed());
    }

    private static FarmBot.Settings settings() {
        FarmBot.Settings s = new FarmBot.Settings();
        s.speed = speed();
        s.breakWrong = breakWrong;
        s.pauseNearPlayers = pauseNearPlayers;
        s.humanPauses = humanPauses;
        s.storeJunk = storeJunk;
        s.useTowers = useTowers;
        s.digThrough = digThrough;
        s.layerByLayer = layerByLayer;
        return s;
    }

    // ================================================================ An / Aus

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        if (enabled) {
            String problem = startProblem();
            if (problem != null) {
                NovaNotify.chat("Schematic Builder", "§c" + problem);
                active = false;
                return;
            }
            selectingChests = false;
            BOT.start(plan, CHESTS, settings());
            NovaNotify.chat("Schematic Builder", "§7Started: §f" + schematic.name + "§7 (" + plan.sizeX + "x" + plan.sizeZ
                    + ", " + Estimate.format(estimateSeconds()) + "). Move or press a key to take over.");
        } else {
            BOT.stop();
            nova.nova_client.client.ModConfig.save();
        }
    }

    /** Warum kann nicht gestartet werden? null = alles bereit. */
    public static String startProblem() {
        Minecraft mc = Minecraft.getInstance();
        if (schematic == null) {
            return "Load a schematic first.";
        }
        if (plan == null) {
            return "Choose a position first.";
        }
        if (mc.player == null) {
            return "Not in a world.";
        }
        if (mc.player.isCreative() || mc.player.isSpectator()) {
            return "Only works in survival.";
        }
        if (Math.sqrt(plan.origin.distSqr(mc.player.blockPosition())) > 96) {
            return "You are too far away from the build area.";
        }
        if (CHESTS.isEmpty() && !hasAllInInventory()) {
            return "Select at least one chest with materials.";
        }
        return null;
    }

    private static boolean hasAllInInventory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || plan == null) {
            return false;
        }
        for (var e : plan.materials.entrySet()) {
            if (FarmBot.countInventory(mc.player, e.getKey()) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Pause/Weiter (Menue). */
    public static void togglePause() {
        BOT.setPaused(!BOT.paused(), "paused in menu");
    }

    @Override
    public void execute(Minecraft client) {
        if (client.player == null) {
            return;
        }
        if (BOT.phase() == FarmBot.Phase.IDLE) {
            active = false; // z.B. durch ein Profil eingeschaltet, ohne dass der Bot gestartet wurde
            return;
        }
        BOT.tick();
        if (BOT.phase() == FarmBot.Phase.DONE) {
            active = false;
            BOT.stop();
        }
    }

    /**
     * Baut der Bot gerade? Dann werden alle Spieler-Eingaben ignoriert (Maus, Klicks, Tasten) - gestoppt wird nur
     * ueber die eigene Taste des Moduls (oder im Menue).
     */
    public static boolean locksInput() {
        return instance != null && instance.active && BOT.running() && !BOT.paused();
    }

    /** Vor Minecrafts Tastenverarbeitung: Spieltasten neutralisieren, AFK-FPS-Limit verhindern. */
    public static void startTick(Minecraft mc) {
        if (!locksInput()) {
            return;
        }
        // Minecraft drosselt nach einer Minute ohne Eingabe auf 30 FPS (nach 10 Minuten auf 10) - der Bot zaehlt als Eingabe
        mc.getFramerateLimitTracker().onInputReceived();
        if (mc.gui.screen() != null) {
            return;
        }
        for (KeyMapping key : mc.options.keyMappings) {
            if (key == mc.options.keyChat || key == mc.options.keyCommand || key == mc.options.keyPlayerList
                    || key == mc.options.keyScreenshot || key == mc.options.keyFullscreen || isNovaKey(key)) {
                continue;
            }
            if (key == mc.options.keyUse && BOT.eating() && mc.player != null && mc.player.isUsingItem()) {
                // Bot isst: Benutzen gedrueckt halten, sonst bricht Minecraft das Essen sofort ab
                while (key.consumeClick()) {
                    // Klick verwerfen
                }
                key.setDown(true);
                continue;
            }
            while (key.consumeClick()) {
                // Klick verwerfen
            }
            key.setDown(false);
        }
    }

    private static boolean isNovaKey(KeyMapping key) {
        String name = key.getName();
        return name.startsWith("Open Nova") || name.startsWith("Open Spotify");
    }

    /** Hat der Bot gerade selbst eine Kiste bzw. das Inventar offen? */
    public static boolean botScreenOpen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return locksInput() && screen instanceof AbstractContainerScreen<?>;
    }

    /** Name der Stopp-Taste (eigene Taste des Moduls), leer = keine gesetzt. */
    public static String stopKeyName() {
        return instance != null && instance.hasKey() ? instance.keyName() : "";
    }

    /** Eingabe fuer KeyboardInputMixin (null = normale Tastatur). */
    public static Input inputOverride() {
        return instance != null && instance.active ? BOT.inputOverride() : null;
    }

    // ================================================================ Immer (auch wenn aus): Kisten + Vorschau

    public static void globalTick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (instance != null && !instance.active && BOT.running()) {
            BOT.stop();
        }
        Screen screen = mc.gui.screen();
        if (screen == null) {
            containerOpen = false;
            openedContainer = null;
            if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                lastLooked = hit.getBlockPos();
            }
        } else if (screen instanceof AbstractContainerScreen<?> containerScreen && !(screen instanceof InventoryScreen)) {
            if (!containerOpen) {
                containerOpen = true;
                openedContainer = lastLooked != null && ChestStore.isStorage(level, lastLooked) ? lastLooked : null;
                if (selectingChests && openedContainer != null) {
                    if (CHESTS.add(level, openedContainer)) {
                        NovaNotify.chat("Schematic Builder", "§aChest added §7(" + CHESTS.chests().size() + " selected)");
                        NovaNotify.sound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4F);
                    } else {
                        NovaNotify.chat("Schematic Builder", "§7This chest is already selected.");
                    }
                    nova.nova_client.client.ModConfig.save();
                }
            }
            if (openedContainer != null && CHESTS.contains(level, openedContainer)) {
                CHESTS.snapshot(level, openedContainer, containerScreen.getMenu(), mc.player);
            }
        }
        if (--ghostTicks <= 0) {
            ghostTicks = 10;
            rebuildGhost(mc, level);
        }
    }

    /** Fehlende Bloecke der untersten offenen Schicht in deiner Naehe (fuer die Vorschau). */
    private static void rebuildGhost(Minecraft mc, ClientLevel level) {
        if (plan == null || !(showPreview || instance != null && instance.active || mc.gui.screen() instanceof SchematicBuilderMenu)) {
            ghost = new BlockPos[0];
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        int layer = Integer.MAX_VALUE;
        for (BuildPlan.Target t : plan.targets) {
            if (t.pos().getY() >= layer) {
                break; // Liste ist nach Hoehe sortiert
            }
            if (t.need().kind() == Materials.Kind.PLACE && !Placer.done(level.getBlockState(t.pos()), t.state())) {
                layer = t.pos().getY();
            }
        }
        List<BlockPos> positions = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        for (BuildPlan.Target t : plan.targets) {
            if (t.pos().getY() < layer || t.pos().getY() > layer + 1) {
                continue;
            }
            if (t.pos().distToCenterSqr(eye.x, eye.y, eye.z) > 32 * 32) {
                continue;
            }
            BlockState have = level.getBlockState(t.pos());
            if (Placer.done(have, t.state())) {
                continue;
            }
            positions.add(t.pos());
            boolean wrong = !have.isAir() && !have.canBeReplaced();
            colors.add(wrong ? 0xFF5470 : t.pos().getY() == layer ? 0x5EF2C2 : 0x7AB8FF);
            if (positions.size() >= 700) {
                break;
            }
        }
        ghost = positions.toArray(new BlockPos[0]);
        ghostColor = colors.stream().mapToInt(Integer::intValue).toArray();
    }

    public static boolean wantsGizmos() {
        Minecraft mc = Minecraft.getInstance();
        return plan != null && (showPreview || instance != null && instance.active || selectingChests
                || mc.gui.screen() instanceof SchematicBuilderMenu) || selectingChests;
    }

    /** Pro Frame mit aktivem Gizmo-Collector. */
    public static void emitGizmos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        int accent = NovaGui.accent(ModCategory.NOVA) & 0xFFFFFF;
        if (plan != null) {
            AABB box = plan.box();
            Gizmos.cuboid(box, GizmoStyle.stroke(0xFF000000 | accent, 2.5F)).setAlwaysOnTop();
            // Bodenflaeche leicht einfaerben
            AABB floor = new AABB(box.minX, box.minY, box.minZ, box.maxX, box.minY + 0.02D, box.maxZ);
            Gizmos.cuboid(floor, GizmoStyle.fill(0x22000000 | accent));
            BlockPos[] positions = ghost;
            int[] colors = ghostColor;
            for (int i = 0; i < positions.length && i < colors.length; i++) {
                Gizmos.cuboid(new AABB(positions[i]).deflate(0.06D),
                        GizmoStyle.strokeAndFill(0xB0000000 | colors[i], 1.0F, 0x18000000 | colors[i]));
            }
            BuildPlan.Target current = BOT.currentTarget();
            if (instance != null && instance.active && current != null) {
                Gizmos.cuboid(current.pos(), GizmoStyle.strokeAndFill(0xFFFFFFFF, 2.0F, 0x40000000 | accent)).setAlwaysOnTop();
            }
            BlockPos mining = BOT.currentMine();
            if (instance != null && instance.active && mining != null) {
                Gizmos.cuboid(mining, GizmoStyle.strokeAndFill(0xFFFF5470, 2.0F, 0x30FF5470)).setAlwaysOnTop();
            }
        }
        if (selectingChests || mc.gui.screen() instanceof SchematicBuilderMenu || instance != null && instance.active
                || plan != null && showPreview) {
            for (BlockPos chest : CHESTS.chests()) {
                Gizmos.cuboid(chest, GizmoStyle.strokeAndFill(0xFFFFD27A, 2.0F, 0x30FFD27A)).setAlwaysOnTop();
            }
        }
    }

    // ================================================================ HUD

    public static void renderHud(GuiGraphicsExtractor g, DeltaTracker delta) {
        BOT.frame();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        boolean on = instance != null && instance.active;
        if (!on && !selectingChests) {
            return;
        }
        Font font = mc.font;
        int accent = NovaGui.accent(ModCategory.NOVA);
        List<String> lines = new ArrayList<>();
        if (on) {
            lines.add(BOT.status());
            int total = Math.max(1, BOT.totalCount());
            lines.add(BOT.doneCount() + " / " + total + " blocks  ·  " + Estimate.format(BOT.etaSeconds()) + " left");
            if (!BOT.missing().isEmpty()) {
                lines.add("§cMissing: " + String.join(", ", BOT.missingNames()));
            }
            String key = stopKeyName();
            lines.add(key.isEmpty() ? "§eNo stop key bound - stop it in the menu" : "§7Press §f" + key + "§7 to stop");
        } else {
            lines.add("Open each material chest once to add it");
            lines.add(CHESTS.chests().size() + " selected  ·  press the menu again to finish");
        }
        int width = 150;
        for (String line : lines) {
            width = Math.max(width, font.width(line) + 12);
        }
        int x0 = 6;
        int y0 = mc.getWindow().getGuiScaledHeight() / 2 - 30;
        int height = 18 + lines.size() * 11 + (on ? 8 : 0);
        NovaGui.hudBackground(g, x0, y0, x0 + width, y0 + height);
        g.fill(x0, y0, x0 + 2, y0 + height, accent);
        NovaGui.text(g, font, on ? "Schematic Builder" : "Selecting chests", x0 + 7, y0 + 5, accent);
        int y = y0 + 17;
        for (String line : lines) {
            NovaGui.text(g, font, line, x0 + 7, y, NovaGui.TEXT_DIM);
            y += 11;
        }
        if (on) {
            int bx0 = x0 + 7;
            int bx1 = x0 + width - 7;
            float progress = BOT.doneCount() / (float) Math.max(1, BOT.totalCount());
            g.fill(bx0, y + 1, bx1, y + 4, 0x40FFFFFF);
            g.fill(bx0, y + 1, bx0 + Math.round((bx1 - bx0) * Mth.clamp(progress, 0.0F, 1.0F)), y + 4, accent);
        }
    }

    // ================================================================ Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new SchematicBuilderMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "file", fileName == null ? "" : fileName);
        if (origin != null) {
            properties.setProperty(prefix + "origin", origin.getX() + "," + origin.getY() + "," + origin.getZ());
        }
        properties.setProperty(prefix + "rotation", String.valueOf(rotation));
        properties.setProperty(prefix + "chests", CHESTS.serialize());
        properties.setProperty(prefix + "speed", String.valueOf(speedIndex));
        properties.setProperty(prefix + "breakWrong", String.valueOf(breakWrong));
        properties.setProperty(prefix + "pauseNearPlayers", String.valueOf(pauseNearPlayers));
        properties.setProperty(prefix + "humanPauses", String.valueOf(humanPauses));
        properties.setProperty(prefix + "storeJunk", String.valueOf(storeJunk));
        properties.setProperty(prefix + "useTowers", String.valueOf(useTowers));
        properties.setProperty(prefix + "digThrough", String.valueOf(digThrough));
        properties.setProperty(prefix + "layerByLayer", String.valueOf(layerByLayer));
        properties.setProperty(prefix + "showPreview", String.valueOf(showPreview));
        properties.setProperty(prefix + "scaffolds", BOT.serializeScaffolds());
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        fileName = properties.getProperty(prefix + "file", fileName);
        String o = properties.getProperty(prefix + "origin", "");
        String[] xyz = o.split(",");
        if (xyz.length == 3) {
            try {
                origin = new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
            } catch (NumberFormatException ignored) {
                // Standard behalten
            }
        }
        try {
            rotation = Integer.parseInt(properties.getProperty(prefix + "rotation", String.valueOf(rotation))) & 3;
            speedIndex = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "speed", String.valueOf(speedIndex))), 0, SPEEDS.length - 1);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        CHESTS.deserialize(properties.getProperty(prefix + "chests", CHESTS.serialize()));
        breakWrong = bool(properties, prefix + "breakWrong", breakWrong);
        pauseNearPlayers = bool(properties, prefix + "pauseNearPlayers", pauseNearPlayers);
        humanPauses = bool(properties, prefix + "humanPauses", humanPauses);
        storeJunk = bool(properties, prefix + "storeJunk", storeJunk);
        useTowers = bool(properties, prefix + "useTowers", useTowers);
        digThrough = bool(properties, prefix + "digThrough", digThrough);
        layerByLayer = bool(properties, prefix + "layerByLayer", layerByLayer);
        showPreview = bool(properties, prefix + "showPreview", showPreview);
        BOT.deserializeScaffolds(properties.getProperty(prefix + "scaffolds", ""));
        if (fileName != null && !fileName.isBlank() && (schematic == null || !fileName.equals(schematic.fileName))) {
            load(fileName);
        } else {
            rebuildPlan();
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        return Boolean.parseBoolean(p.getProperty(key, String.valueOf(def)));
    }

    /** Fuer das Menue: bestes Werkzeug dieser Art im Inventar oder in den Kisten (null = keins). */
    public static Item findTool(nova.nova_client.client.farmbuilder.Tools.Kind kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (int i = 0; i < 36; i++) {
                if (kind.matches(mc.player.getInventory().getItem(i))) {
                    return mc.player.getInventory().getItem(i).getItem();
                }
            }
        }
        return nova.nova_client.client.farmbuilder.Tools.bestInChests(CHESTS, kind);
    }

    /** Empfohlenes Essen fuer den Bot (steht immer in der Materialliste): 1 Stapel. */
    public static final int FOOD_WANTED = 64;

    /** Fuer das Menue: Essen (egal welches) in Kisten und Inventar - ohne Essen, das die Farm selbst braucht. */
    public static int availableFood() {
        java.util.Set<Item> items = new java.util.HashSet<>(CHESTS.allItems());
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            for (int i = 0; i < 36; i++) {
                items.add(mc.player.getInventory().getItem(i).getItem());
            }
        }
        int n = 0;
        for (Item item : items) {
            if (nova.nova_client.client.farmbuilder.Food.edible(item) && (plan == null || !plan.materials.containsKey(item))) {
                n += available(item);
            }
        }
        return n;
    }

    /** Empfohlene Hilfsbloecke (Erde/Bruchstein ...) fuer Stuetzen unter schwebenden Teilen und Tuerme: 1 Stapel. */
    public static final int HELPERS_WANTED = 64;

    /** Fuer das Menue: Hilfsbloecke in Kisten/Inventar - ohne das, was die Farm selbst davon braucht. */
    public static int availableHelpers() {
        int n = 0;
        for (Item item : FarmBot.helperItems()) {
            n += Math.max(0, available(item) - (plan == null ? 0 : plan.materials.getOrDefault(item, 0)));
        }
        return n;
    }

    /** Fuer das Menue: Material in Kisten/Inventar. */
    public static int available(Item item) {
        Minecraft mc = Minecraft.getInstance();
        int inv = mc.player == null ? 0 : FarmBot.countInventory(mc.player, item);
        return inv + CHESTS.available(item);
    }

    public static String sizeText() {
        if (plan == null) {
            return "-";
        }
        return String.format(Locale.ROOT, "%d x %d area, %d high", plan.sizeX, plan.sizeZ, plan.sizeY);
    }
}
