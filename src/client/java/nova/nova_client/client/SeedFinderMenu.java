package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Util;
import nova.nova_client.client.mods.SeedFinderMod;
import nova.nova_client.client.seedfinder.SeedCracker;
import nova.nova_client.client.seedfinder.SeedData;
import nova.nova_client.client.seedfinder.StructureKind;

import java.util.List;
import java.util.Locale;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Seed Finder: zeigt den Seed (Kopieren, /seed versuchen), was schon gesammelt ist (Strukturen, End-Saeulen,
 * Seed-Hash) und wie viel noch fehlt, dazu Rechnen/Abbrechen und Strukturen von Hand eintragen.
 */
public class SeedFinderMenu extends NovaSettingsScreen {
    private static final int ROW = 14;
    private static final int BUTTON_H = 18;

    private StructureKind addKind = StructureKind.DESERT_PYRAMID;
    private String message = "";
    private long messageAt;
    private long clearArmedAt;
    private int scroll;

    public SeedFinderMenu(Screen parent) {
        super(parent, "Seed Finder");
    }

    @Override
    protected String subtitle() {
        return "World: " + SeedData.current().serverName();
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    private void say(String text) {
        message = text;
        messageAt = Util.getMillis();
    }

    // ---------------------------------------------------------------- Geometrie

    private int seedBoxBottom() {
        return cy0 + 34;
    }

    private int copyX0() {
        return cx1 - 132;
    }

    private int seedCmdX0() {
        return cx1 - 64;
    }

    private int barY() {
        return seedBoxBottom() + 24;
    }

    private int chipsY() {
        return barY() + 12;
    }

    private int listTop() {
        return chipsY() + 26;
    }

    private int buttonsY() {
        return cy1 - BUTTON_H;
    }

    private int listBottom() {
        return buttonsY() - 16;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ROW);
    }

    private int buttonX(int index) {
        int w = (cx1 - cx0 - 3 * 6) / 4;
        return cx0 + index * (w + 6);
    }

    private int buttonW() {
        return (cx1 - cx0 - 3 * 6) / 4;
    }

    private boolean inButton(double mx, double my, int index) {
        return isHovering((int) mx, (int) my, buttonX(index), buttonsY(), buttonX(index) + buttonW(), buttonsY() + BUTTON_H);
    }

    private boolean inCopy(double mx, double my) {
        return isHovering((int) mx, (int) my, copyX0(), cy0 + 8, copyX0() + 62, cy0 + 26);
    }

    private boolean inSeedCmd(double mx, double my) {
        return isHovering((int) mx, (int) my, seedCmdX0(), cy0 + 8, cx1 - 8, cy0 + 26);
    }

    /** Index der Struktur unter der Maus, deren Entfernen-Kreuz getroffen wird (oder -1). */
    private int removeAt(double mx, double my, List<SeedData.Found> list) {
        for (int row = 0; row < visibleRows(); row++) {
            int index = scroll + row;
            if (index >= list.size()) {
                break;
            }
            int y = listTop() + row * ROW;
            if (isHovering((int) mx, (int) my, cx1 - 16, y, cx1 - 2, y + ROW - 2)) {
                return index;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SeedData data = SeedData.current();
        Minecraft client = Minecraft.getInstance();
        double mx = event.x();
        double my = event.y();
        if (event.button() == 0 && data.seed != null && inCopy(mx, my)) {
            client.keyboardHandler.setClipboard(String.valueOf(data.seed));
            say("Seed copied to clipboard");
            NovaGui.clickSound();
            return true;
        }
        if (event.button() == 0 && inSeedCmd(mx, my)) {
            SeedFinderMod.trySeedCommand(client);
            say("Sent /seed - only works if you have permission");
            NovaGui.clickSound();
            return true;
        }
        if (event.button() == 0) {
            int remove = removeAt(mx, my, data.structures);
            if (remove >= 0) {
                data.remove(data.structures.get(remove));
                NovaGui.clickSound();
                return true;
            }
        }
        // Knoepfe unten
        if (inButton(mx, my, 0) && event.button() == 0) {
            if (SeedFinderMod.running()) {
                SeedFinderMod.cancel();
            } else if (!SeedFinderMod.ready(data)) {
                say("Not enough data yet - " + SeedFinderMod.progressText(data));
            } else {
                SeedFinderMod.startCrack(data);
            }
            NovaGui.clickSound();
            return true;
        }
        if (inButton(mx, my, 1)) {
            if (event.button() == 1) {
                StructureKind[] kinds = StructureKind.values();
                addKind = kinds[(addKind.ordinal() + 1) % kinds.length];
            } else if (event.button() == 0) {
                say(SeedFinderMod.addAtCrosshair(client, addKind));
            }
            NovaGui.clickSound();
            return true;
        }
        if (inButton(mx, my, 2) && event.button() == 0) {
            SeedFinderMod.autoCrack = !SeedFinderMod.autoCrack;
            ModConfig.save();
            NovaGui.clickSound();
            return true;
        }
        if (inButton(mx, my, 3) && event.button() == 0) {
            if (Util.getMillis() - clearArmedAt < 3000L) {
                data.clear();
                clearArmedAt = 0L;
                scroll = 0;
                say("Data for this world cleared");
            } else {
                clearArmedAt = Util.getMillis();
            }
            NovaGui.clickSound();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inButton(mouseX, mouseY, 1)) {
            StructureKind[] kinds = StructureKind.values();
            addKind = kinds[Math.floorMod(addKind.ordinal() + (scrollY > 0 ? -1 : 1), kinds.length)];
            return true;
        }
        int max = Math.max(0, SeedData.current().structures.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll + (scrollY > 0 ? -1 : 1)));
        return true;
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        SeedData data = SeedData.current();
        boolean running = SeedFinderMod.running();

        // Seed-Kasten
        NovaShapes.rect(graphics, cx0, cy0, cx1, seedBoxBottom(), 6, NovaGui.CARD);
        NovaShapes.outline(graphics, cx0, cy0, cx1, seedBoxBottom(), 6, data.seed != null ? accent : NovaGui.BORDER);
        NovaGui.text(graphics, font, "WORLD SEED", cx0 + 10, cy0 + 6, NovaGui.TEXT_MUTED);
        String seedText = data.seed != null ? String.valueOf(data.seed) : "Unknown";
        NovaGui.text(graphics, font, seedText, cx0 + 10, cy0 + 19, data.seed != null ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        if (data.seed != null) {
            String source = switch (data.source) {
                case "singleplayer" -> "from this singleplayer world";
                case "/seed" -> "from /seed";
                default -> "cracked from the world";
            };
            int sx = cx0 + 16 + NovaGui.width(font, seedText);
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, source, copyX0() - sx - 6), sx, cy0 + 19, NovaGui.TEXT_MUTED);
            boolean copyHover = inCopy(mouseX, mouseY);
            pointer |= copyHover;
            NovaControls.choice(graphics, font, copyX0(), cy0 + 8, copyX0() + 62, cy0 + 26, "Copy", copyHover, false, accent);
        }
        boolean cmdHover = inSeedCmd(mouseX, mouseY);
        pointer |= cmdHover;
        NovaControls.choice(graphics, font, seedCmdX0(), cy0 + 8, cx1 - 8, cy0 + 26, "/seed", cmdHover, false, accent);

        // Status
        String statusText;
        if (Util.getMillis() - messageAt < 5000L && !message.isEmpty()) {
            statusText = message;
        } else if (running) {
            statusText = String.format(Locale.ROOT, "Cracking: %s  %.0f%%", SeedFinderMod.stage(), SeedFinderMod.progress() * 100.0);
        } else if (data.seed != null) {
            statusText = "Done - the seed is known for this world";
        } else if (!SeedFinderMod.status.isEmpty()) {
            statusText = SeedFinderMod.status;
        } else {
            statusText = "Fly around with the module on - deserts, jungles and swamps help most";
        }
        NovaGui.text(graphics, font, NovaGui.ellipsize(font, statusText, cx1 - cx0 - 4), cx0 + 2, seedBoxBottom() + 8,
                NovaGui.TEXT_DIM);

        // Balken: Daten bzw. Fortschritt
        int by = barY();
        SeedCracker.Input input = data.input();
        double needed = SeedFinderMod.neededBits(data);
        double bits = SeedCracker.informationBits(input);
        float fill = running ? (float) SeedFinderMod.progress() : (float) Math.min(1.0, bits / needed);
        NovaShapes.rect(graphics, cx0, by, cx1, by + 5, 2, NovaGui.SWITCH_OFF);
        NovaShapes.rect(graphics, cx0, by, cx0 + Math.round((cx1 - cx0) * fill), by + 5, 2, fill >= 1.0F && !running ? NovaGui.SUCCESS : accent);

        // Chips: Hash, End-Saeulen, Daten
        int chipY = chipsY();
        int x = cx0;
        x = chip(graphics, x, chipY, data.hashedSeed != null ? "Seed hash: received" : "Seed hash: not sent",
                data.hashedSeed != null ? NovaGui.SUCCESS : NovaGui.DANGER);
        x = chip(graphics, x, chipY, "End pillars: " + data.pillarsSeen() + "/10",
                data.pillarsSeen() == 10 ? NovaGui.SUCCESS : NovaGui.TEXT_MUTED);
        chip(graphics, x, chipY, String.format(Locale.ROOT, "Data: %.0f/%.0f bits", Math.min(bits, needed), needed),
                bits >= needed ? NovaGui.SUCCESS : NovaGui.TEXT_MUTED);

        // Liste der Strukturen
        List<SeedData.Found> list = data.structures;
        int top = listTop();
        NovaGui.text(graphics, font, "Found structures (" + list.size() + ")", cx0 + 2, top - 11, NovaGui.TEXT_MUTED);
        if (list.isEmpty()) {
            NovaGui.text(graphics, font, "None yet - temples, swamp huts and buried treasure count", cx0 + 2, top + 3,
                    NovaGui.TEXT_MUTED);
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - visibleRows())));
        for (int row = 0; row < visibleRows(); row++) {
            int index = scroll + row;
            if (index >= list.size()) {
                break;
            }
            SeedData.Found found = list.get(index);
            int y = top + row * ROW;
            boolean hover = isHovering(mouseX, mouseY, cx0, y, cx1, y + ROW - 2);
            if (hover) {
                NovaShapes.rect(graphics, cx0, y - 1, cx1, y + ROW - 2, 4, NovaGui.RAISED);
            }
            NovaGui.text(graphics, font, found.kind().title, cx0 + 6, y + 2, hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
            String where = "x " + found.blockX() + ", z " + found.blockZ() + (found.manual() ? "  (manual)" : "");
            NovaGui.text(graphics, font, where, cx0 + 120, y + 2, NovaGui.TEXT_MUTED);
            boolean removeHover = isHovering(mouseX, mouseY, cx1 - 16, y, cx1 - 2, y + ROW - 2);
            pointer |= removeHover;
            NovaGui.text(graphics, font, "x", cx1 - 11, y + 2, removeHover ? NovaGui.DANGER : NovaGui.TEXT_MUTED);
        }
        if (list.size() > visibleRows()) {
            String more = (scroll + 1) + "-" + Math.min(list.size(), scroll + visibleRows()) + " of " + list.size();
            NovaGui.text(graphics, font, more, cx1 - NovaGui.width(font, more) - 2, top - 11, NovaGui.TEXT_MUTED);
        }

        // Knoepfe
        String[] labels = {
                running ? "Cancel" : "Crack now",
                "Add: " + addKind.title,
                "Auto crack: " + (SeedFinderMod.autoCrack ? "On" : "Off"),
                Util.getMillis() - clearArmedAt < 3000L ? "Click again to clear" : "Clear data"};
        for (int i = 0; i < 4; i++) {
            boolean hover = inButton(mouseX, mouseY, i);
            pointer |= hover;
            boolean active = i == 2 && SeedFinderMod.autoCrack || i == 0 && running;
            NovaControls.choice(graphics, font, buttonX(i), buttonsY(), buttonX(i) + buttonW(), buttonsY() + BUTTON_H, labels[i], hover, active, i == 3 && Util.getMillis() - clearArmedAt < 3000L ? NovaGui.DANGER : accent);
        }
        if (inButton(mouseX, mouseY, 1)) {
            String hint = "Left click: add the structure you look at  -  right click / scroll: change type";
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, hint, cx1 - cx0), cx0 + 2, buttonsY() - 11, NovaGui.TEXT_MUTED);
        }
        return pointer;
    }

    /** Kleines Etikett mit Punkt; gibt das x fuer das naechste zurueck. */
    private int chip(GuiGraphicsExtractor graphics, int x, int y, String text, int color) {
        int w = NovaGui.width(font, text) + 16;
        NovaShapes.rect(graphics, x, y, x + w, y + 14, 7, NovaGui.CARD);
        NovaShapes.outline(graphics, x, y, x + w, y + 14, 7, NovaGui.BORDER);
        NovaShapes.rect(graphics, x + 5, y + 5, x + 9, y + 9, 2, color);
        NovaGui.text(graphics, font, text, x + 12, y + 3, NovaGui.TEXT_DIM);
        return x + w + 6;
    }
}
