package nova.nova_client.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import nova.nova_client.client.mods.AdminListMod;

import java.util.List;

/**
 * Einstellungen fuer die Adminlist: Groesse, Position, Aussehen und Meldungen. Die Teammitglieder stehen fest in
 * AdminListMod.STAFF_NAMES, es gibt nichts einzutragen.
 */
public class AdminListMenu extends ModOptionsMenu {
    public AdminListMenu(Screen parent) {
        super(parent, "Adminlist", List.of(
                new Segmented("Size", percent(AdminListMod.SCALES), () -> AdminListMod.scaleIndex, i -> AdminListMod.scaleIndex = i),
                new Actions(List.of(
                        open("Move on screen", HudEditorScreen::new),
                        open("Customize look", back -> new HudStyleMenu(back, "Adminlist", AdminListMod.style,
                                AdminListMod::previewRows, 1, false)))),
                new Section("Alerts"),
                new Toggle("Chat: join, leave, vanish", () -> AdminListMod.chat, v -> AdminListMod.chat = v),
                new Toggle("Staff in your region", () -> AdminListMod.regionAlert, v -> AdminListMod.regionAlert = v),
                new Toggle("Sound", () -> AdminListMod.sound, v -> AdminListMod.sound = v),
                new Section("Online now"),
                new Info(AdminListMenu::online)));
    }

    private static String online() {
        List<AdminListMod.Staff> staff = AdminListMod.findStaff(Minecraft.getInstance());
        if (staff.isEmpty()) {
            return "Nobody from the team is online";
        }
        StringBuilder names = new StringBuilder();
        for (AdminListMod.Staff s : staff) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(s.name());
            String status = AdminListMod.status(s);
            if (!status.isEmpty()) {
                names.append(" (").append(status).append(')');
            }
        }
        return staff.size() + ": " + names;
    }
}
