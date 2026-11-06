package nova.nova_client.client.farmbuilder;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Liest Bauvorlagen: Litematica (.litematic), Sponge/WorldEdit (.schem) und Vanilla-Strukturen (.nbt).
 * Alte Dateien werden mit Minecrafts DataFixer auf die aktuelle Version gebracht (umbenannte Bloecke usw.).
 */
public final class SchematicLoader {
    /** Schutz gegen riesige Dateien (z.B. ganze Staedte): mehr als das baut niemand mit einem Bot. */
    private static final long MAX_VOLUME = 4_000_000L;

    private SchematicLoader() {
    }

    public static boolean isSchematic(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".litematic") || name.endsWith(".schem") || name.endsWith(".schematic") || name.endsWith(".nbt");
    }

    public static Schematic load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("File not found");
        }
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        String fileName = path.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".litematic") || root.contains("Regions")) {
            return litematic(root, fileName);
        }
        if (lower.endsWith(".nbt") || root.contains("palette") || root.contains("palettes")) {
            return structure(root, fileName);
        }
        return sponge(root, fileName);
    }

    // ---------------------------------------------------------------- Litematica

    private static Schematic litematic(CompoundTag root, String fileName) throws IOException {
        int dataVersion = root.getIntOr("MinecraftDataVersion", currentDataVersion());
        CompoundTag meta = root.getCompoundOrEmpty("Metadata");
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        if (regions.keySet().isEmpty()) {
            throw new IOException("No regions in this litematic");
        }

        // Umschliessender Quader aller Regionen (Groessen koennen negativ sein)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (String key : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(key);
            int[] box = regionBox(region);
            minX = Math.min(minX, box[0]);
            minY = Math.min(minY, box[1]);
            minZ = Math.min(minZ, box[2]);
            maxX = Math.max(maxX, box[0] + box[3] - 1);
            maxY = Math.max(maxY, box[1] + box[4] - 1);
            maxZ = Math.max(maxZ, box[2] + box[5] - 1);
        }
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        checkVolume(sx, sy, sz);
        BlockState[] blocks = new BlockState[sx * sy * sz];
        int entities = 0;
        int tiles = 0;
        int unknown = 0;
        ExtrasReader extras = new ExtrasReader();

        for (String key : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(key);
            int[] box = regionBox(region);
            ListTag paletteTag = region.getListOrEmpty("BlockStatePalette");
            BlockState[] palette = new BlockState[paletteTag.size()];
            boolean[] missing = new boolean[paletteTag.size()];
            for (int i = 0; i < palette.length; i++) {
                CompoundTag entry = paletteTag.getCompoundOrEmpty(i);
                palette[i] = parseState(entry, dataVersion);
                missing[i] = palette[i] == null && !isAirName(entry.getStringOr("Name", "minecraft:air"));
            }
            long[] data = region.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
            long mask = (1L << bits) - 1L;
            int rx = box[3], ry = box[4], rz = box[5];
            long volume = (long) rx * ry * rz;
            if (data.length * 64L < volume * bits) {
                throw new IOException("Region '" + key + "' has broken block data");
            }
            for (int y = 0; y < ry; y++) {
                for (int z = 0; z < rz; z++) {
                    for (int x = 0; x < rx; x++) {
                        long index = ((long) y * rz + z) * rx + x;
                        int id = (int) readPacked(data, index, bits, mask);
                        if (id < 0 || id >= palette.length) {
                            continue;
                        }
                        if (missing[id]) {
                            unknown++;
                            continue;
                        }
                        BlockState state = palette[id];
                        if (state == null) {
                            continue;
                        }
                        int wx = box[0] - minX + x;
                        int wy = box[1] - minY + y;
                        int wz = box[2] - minZ + z;
                        blocks[(wy * sz + wz) * sx + wx] = state;
                    }
                }
            }
            entities += region.getListOrEmpty("Entities").size();
            ListTag tileList = region.getListOrEmpty("TileEntities");
            tiles += tileList.size();
            for (int i = 0; i < tileList.size(); i++) {
                CompoundTag te = tileList.getCompoundOrEmpty(i);
                int x = te.getIntOr("x", -1), y = te.getIntOr("y", -1), z = te.getIntOr("z", -1);
                if (x >= 0 && y >= 0 && z >= 0 && x < rx && y < ry && z < rz) {
                    extras.add(((box[1] - minY + y) * sz + box[2] - minZ + z) * sx + box[0] - minX + x,
                            te.getStringOr("id", ""), te);
                }
            }
        }

        String name = meta.getStringOr("Name", stripExtension(fileName));
        String author = meta.getStringOr("Author", "?");
        return new Schematic(name.isBlank() ? stripExtension(fileName) : name, author, fileName, sx, sy, sz, blocks,
                entities, tiles, unknown, extras.build());
    }

    /** {minX, minY, minZ, sizeX, sizeY, sizeZ} einer Region (relativ zum Schematic-Ursprung). */
    private static int[] regionBox(CompoundTag region) {
        CompoundTag pos = region.getCompoundOrEmpty("Position");
        CompoundTag size = region.getCompoundOrEmpty("Size");
        int px = pos.getIntOr("x", 0), py = pos.getIntOr("y", 0), pz = pos.getIntOr("z", 0);
        int sx = size.getIntOr("x", 1), sy = size.getIntOr("y", 1), sz = size.getIntOr("z", 1);
        return new int[]{
                sx < 0 ? px + sx + 1 : px,
                sy < 0 ? py + sy + 1 : py,
                sz < 0 ? pz + sz + 1 : pz,
                Math.max(1, Math.abs(sx)), Math.max(1, Math.abs(sy)), Math.max(1, Math.abs(sz))};
    }

    /** Litematica packt die Palette-Indizes lueckenlos (auch ueber long-Grenzen hinweg). */
    private static long readPacked(long[] data, long index, int bits, long mask) {
        long startOffset = index * bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) (((index + 1L) * bits - 1L) >> 6);
        int startBit = (int) (startOffset & 63L);
        if (startArr == endArr) {
            return (data[startArr] >>> startBit) & mask;
        }
        int endBit = 64 - startBit;
        return ((data[startArr] >>> startBit) | (data[endArr] << endBit)) & mask;
    }

    // ---------------------------------------------------------------- Sponge / WorldEdit (.schem v2 + v3)

    private static Schematic sponge(CompoundTag root, String fileName) throws IOException {
        CompoundTag tag = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        int version = tag.getIntOr("Version", 2);
        int sx = tag.getShortOr("Width", (short) 0) & 0xFFFF;
        int sy = tag.getShortOr("Height", (short) 0) & 0xFFFF;
        int sz = tag.getShortOr("Length", (short) 0) & 0xFFFF;
        if (sx == 0 || sy == 0 || sz == 0) {
            throw new IOException("Unsupported schematic format (old MCEdit .schematic files are not supported)");
        }
        checkVolume(sx, sy, sz);
        int dataVersion = tag.getIntOr("DataVersion", currentDataVersion());
        CompoundTag paletteTag;
        byte[] data;
        ListTag tileList;
        if (version >= 3) {
            CompoundTag blocksTag = tag.getCompoundOrEmpty("Blocks");
            paletteTag = blocksTag.getCompoundOrEmpty("Palette");
            data = blocksTag.getByteArray("Data").orElse(new byte[0]);
            tileList = blocksTag.getListOrEmpty("BlockEntities");
        } else {
            paletteTag = tag.getCompoundOrEmpty("Palette");
            data = tag.getByteArray("BlockData").orElse(new byte[0]);
            tileList = tag.getListOrEmpty("BlockEntities");
        }
        int tiles = tileList.size();
        ExtrasReader extras = new ExtrasReader();
        for (int t = 0; t < tileList.size(); t++) {
            CompoundTag te = tileList.getCompoundOrEmpty(t);
            int[] pos = te.getIntArray("Pos").orElse(new int[0]);
            if (pos.length == 3 && pos[0] >= 0 && pos[1] >= 0 && pos[2] >= 0 && pos[0] < sx && pos[1] < sy && pos[2] < sz) {
                // v3: Inhalt unter "Data", v2: direkt im Eintrag
                extras.add((pos[1] * sz + pos[2]) * sx + pos[0], te.getStringOr("Id", ""),
                        version >= 3 ? te.getCompoundOrEmpty("Data") : te);
            }
        }
        int paletteSize = 0;
        for (String key : paletteTag.keySet()) {
            paletteSize = Math.max(paletteSize, paletteTag.getIntOr(key, 0) + 1);
        }
        BlockState[] palette = new BlockState[paletteSize];
        boolean[] missing = new boolean[paletteSize];
        for (String key : paletteTag.keySet()) {
            int id = paletteTag.getIntOr(key, -1);
            if (id >= 0) {
                palette[id] = parseStateString(key, dataVersion);
                missing[id] = palette[id] == null && !isAirName(key.split("\\[")[0]);
            }
        }
        BlockState[] blocks = new BlockState[sx * sy * sz];
        int unknown = 0;
        int i = 0;
        int index = 0;
        while (i < data.length && index < blocks.length) {
            // VarInt
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[i++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && i < data.length);
            if (value >= 0 && value < palette.length) {
                if (missing[value]) {
                    unknown++;
                } else {
                    blocks[index] = palette[value];
                }
            }
            index++;
        }
        String name = stripExtension(fileName);
        String author = tag.getCompoundOrEmpty("Metadata").getStringOr("Author", "?");
        int entities = tag.getListOrEmpty("Entities").size();
        return new Schematic(name, author, fileName, sx, sy, sz, blocks, entities, tiles, unknown, extras.build());
    }

    // ---------------------------------------------------------------- Vanilla Struktur (.nbt)

    private static Schematic structure(CompoundTag root, String fileName) throws IOException {
        ListTag size = root.getListOrEmpty("size");
        if (size.size() < 3) {
            throw new IOException("Not a structure file");
        }
        int sx = size.getIntOr(0, 0), sy = size.getIntOr(1, 0), sz = size.getIntOr(2, 0);
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new IOException("Empty structure");
        }
        checkVolume(sx, sy, sz);
        int dataVersion = root.getIntOr("DataVersion", currentDataVersion());
        ListTag paletteTag = root.contains("palette") ? root.getListOrEmpty("palette")
                : root.getListOrEmpty("palettes").getListOrEmpty(0);
        BlockState[] palette = new BlockState[paletteTag.size()];
        boolean[] missing = new boolean[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            CompoundTag entry = paletteTag.getCompoundOrEmpty(i);
            palette[i] = parseState(entry, dataVersion);
            missing[i] = palette[i] == null && !isAirName(entry.getStringOr("Name", "minecraft:air"));
        }
        BlockState[] blocks = new BlockState[sx * sy * sz];
        ListTag list = root.getListOrEmpty("blocks");
        int tiles = 0;
        int unknown = 0;
        ExtrasReader extras = new ExtrasReader();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            ListTag pos = entry.getListOrEmpty("pos");
            int x = pos.getIntOr(0, -1), y = pos.getIntOr(1, -1), z = pos.getIntOr(2, -1);
            int id = entry.getIntOr("state", -1);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || id < 0 || id >= palette.length) {
                continue;
            }
            if (missing[id]) {
                unknown++;
                continue;
            }
            blocks[(y * sz + z) * sx + x] = palette[id];
            if (entry.contains("nbt")) {
                tiles++;
                CompoundTag nbt = entry.getCompoundOrEmpty("nbt");
                extras.add((y * sz + z) * sx + x, nbt.getStringOr("id", ""), nbt);
            }
        }
        return new Schematic(stripExtension(fileName), root.getStringOr("author", "?"), fileName, sx, sy, sz, blocks,
                root.getListOrEmpty("entities").size(), tiles, unknown, extras.build());
    }

    // ---------------------------------------------------------------- Block-Daten (Crafter, Inhalt)

    /** Im Survival nicht zu bekommen: steht so etwas in einem Behaelter, wird es gemeldet. */
    private static final Set<String> CREATIVE_ONLY = Set.of("minecraft:barrier", "minecraft:light",
            "minecraft:structure_void", "minecraft:structure_block", "minecraft:jigsaw", "minecraft:command_block",
            "minecraft:chain_command_block", "minecraft:repeating_command_block", "minecraft:command_block_minecart",
            "minecraft:debug_stick", "minecraft:bedrock", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault",
            "minecraft:reinforced_deepslate", "minecraft:end_portal_frame", "minecraft:budding_amethyst",
            "minecraft:petrified_oak_slab", "minecraft:knowledge_book");

    /** Sammelt beim Lesen: gesperrte Crafter-Felder, Behaelter mit Inhalt, Kreativ-Items darin. */
    private static final class ExtrasReader {
        private final Map<Integer, int[]> crafters = new HashMap<>();
        private final Map<String, Integer> creative = new TreeMap<>();
        private int filled;

        void add(int index, String id, CompoundTag data) {
            if (id.endsWith(":crafter")) {
                int[] slots = data.getIntArray("disabled_slots").orElse(new int[0]);
                if (slots.length > 0) {
                    crafters.put(index, slots);
                }
            }
            ListTag items = data.getListOrEmpty("Items");
            if (items.isEmpty()) {
                return;
            }
            filled++;
            for (int i = 0; i < items.size(); i++) {
                CompoundTag item = items.getCompoundOrEmpty(i);
                String itemId = item.getStringOr("id", "");
                if (CREATIVE_ONLY.contains(itemId)) {
                    // Ab 1.20.5 "count" (int), davor "Count" (byte)
                    int count = item.getIntOr("count", item.getByteOr("Count", (byte) 1));
                    creative.merge(itemId, Math.max(1, count), Integer::sum);
                }
            }
        }

        Schematic.Extras build() {
            if (crafters.isEmpty() && filled == 0) {
                return Schematic.Extras.NONE;
            }
            return new Schematic.Extras(Collections.unmodifiableMap(crafters), filled,
                    Collections.unmodifiableMap(creative));
        }
    }

    // ---------------------------------------------------------------- Blockzustaende

    private static int currentDataVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private static boolean isAirName(String name) {
        return name.endsWith(":air") || name.endsWith(":cave_air") || name.endsWith(":void_air")
                || name.endsWith(":structure_void") || name.equals("air");
    }

    /** {Name: "minecraft:chest", Properties: {facing: "north"}} -> BlockState (null = Luft/unbekannt). */
    private static BlockState parseState(CompoundTag entry, int dataVersion) {
        CompoundTag tag = entry;
        int current = currentDataVersion();
        if (dataVersion > 0 && dataVersion < current) {
            try {
                Tag fixed = DataFixers.getDataFixer().update(References.BLOCK_STATE,
                        new Dynamic<>(NbtOps.INSTANCE, (Tag) entry), dataVersion, current).getValue();
                if (fixed instanceof CompoundTag compound) {
                    tag = compound;
                }
            } catch (RuntimeException ignored) {
                // Unveraendert versuchen
            }
        }
        Identifier id = Identifier.tryParse(tag.getStringOr("Name", "minecraft:air"));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        if (state.isAir() || block == Blocks.STRUCTURE_VOID) {
            return null;
        }
        CompoundTag props = tag.getCompoundOrEmpty("Properties");
        for (String key : props.keySet()) {
            Property<?> property = block.getStateDefinition().getProperty(key);
            if (property != null) {
                state = withValue(state, property, props.getStringOr(key, ""));
            }
        }
        return state;
    }

    /** "minecraft:chest[facing=north,type=single]" -> BlockState. */
    private static BlockState parseStateString(String text, int dataVersion) {
        CompoundTag entry = new CompoundTag();
        int bracket = text.indexOf('[');
        entry.putString("Name", bracket < 0 ? text : text.substring(0, bracket));
        if (bracket >= 0 && text.endsWith("]")) {
            CompoundTag props = new CompoundTag();
            for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    props.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            entry.put("Properties", props);
        }
        return parseState(entry, dataVersion);
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static void checkVolume(int sx, int sy, int sz) throws IOException {
        if ((long) sx * sy * sz > MAX_VOLUME) {
            throw new IOException("Schematic is too big (" + sx + "x" + sy + "x" + sz + ")");
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
