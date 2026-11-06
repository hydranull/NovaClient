package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.ModOptionsMenu;
import nova.nova_client.client.NovaGui;

import java.util.List;
import java.util.Properties;

/**
 * Freelook: Kamera und Spieler drehen sich getrennt. "Camera": die Maus dreht nur die Kamera (um dich herum), du
 * laeufst weiter in deine Richtung. "Player": die Maus dreht dich, die Kamera bleibt stehen. Mit den Pfeiltasten
 * dreht man jeweils das andere. Optional schaltet es in die 3rd-Person und die Kamera geht durch Waende.
 */
public class FreelookMod extends GameTickMod {
    public static final String[] MODES = {"Camera", "Player"};

    // Einstellungen
    public static int mode = 0;
    public static boolean thirdPerson = true;
    public static boolean throughWalls = true;
    /** Nur solange die Modul-Taste gedrueckt ist. */
    public static boolean holdKey = false;
    public static float sensitivity = 1.0F;
    public static boolean arrows = true;
    public static float arrowSpeed = 4.0F;

    private static volatile boolean enabled;
    private static float cameraYaw;
    private static float cameraPitch;
    private static CameraType previousView;

    public FreelookMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean on) {
        if (on) {
            if (client.player == null) {
                active = false;
                return;
            }
            cameraYaw = client.player.getYRot();
            cameraPitch = client.player.getXRot();
            previousView = client.options.getCameraType();
            if (thirdPerson && previousView != CameraType.THIRD_PERSON_BACK) {
                client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            enabled = true;
        } else {
            enabled = false;
            if (thirdPerson && previousView != null && client.options.getCameraType() != previousView) {
                client.options.setCameraType(previousView);
            }
            previousView = null;
        }
    }

    @Override
    public void execute(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            return;
        }
        if (holdKey && hasKey() && !InputConstants.isKeyDown(keyCode)) {
            active = false;
            onStateChanged(client, false);
            return;
        }
        if (arrows && client.gui.screen() == null) {
            float yawTurn = (down(client, InputConstants.KEY_RIGHT) ? arrowSpeed : 0.0F) - (down(client, InputConstants.KEY_LEFT) ? arrowSpeed : 0.0F);
            float pitchTurn = (down(client, InputConstants.KEY_DOWN) ? arrowSpeed : 0.0F) - (down(client, InputConstants.KEY_UP) ? arrowSpeed : 0.0F);
            if (mode == 1) {
                cameraYaw += yawTurn;
                cameraPitch += pitchTurn;
            } else if (yawTurn != 0.0F || pitchTurn != 0.0F) {
                player.setYRot(player.getYRot() + yawTurn);
                player.setXRot(Mth.clamp(player.getXRot() + pitchTurn, -90.0F, 90.0F));
            }
        }
        cameraPitch = Mth.clamp(cameraPitch, -90.0F, 90.0F);
    }

    private static boolean down(Minecraft client, int key) {
        return InputConstants.isKeyDown(key);
    }

    // ---------------------------------------------------------------- Fuer die Mixins

    /** Ist die Kamera gerade vom Spieler getrennt? (Freecam hat Vorrang.) */
    public static boolean isActive() {
        return enabled && !FreecamMod.isEnabled();
    }

    /** Dreht die Maus gerade die Kamera statt des Spielers? */
    public static boolean turnsCamera() {
        return isActive() && mode == 0;
    }

    /** Mausbewegung (gleiche Skalierung wie Entity.turn). */
    public static void turn(double yawDelta, double pitchDelta) {
        cameraYaw += (float) yawDelta * 0.15F * sensitivity;
        cameraPitch = Mth.clamp(cameraPitch + (float) pitchDelta * 0.15F * sensitivity, -90.0F, 90.0F);
    }

    public static float yaw() {
        return cameraYaw;
    }

    public static float pitch() {
        return cameraPitch;
    }

    /** Kamera geht durch Waende (kein Heranzoomen an Hindernisse). */
    public static boolean ignoresWalls() {
        return isActive() && throughWalls;
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new ModOptionsMenu(parent, name, "", List.of(
                new ModOptionsMenu.Choice("Mode", MODES, () -> mode, v -> mode = v),
                new ModOptionsMenu.Info(() -> mode == 0 ? "Mouse turns the camera, you keep walking straight"
                        : "Mouse turns you, the camera stays where it is"),
                new ModOptionsMenu.Toggle("Switch to 3rd person", () -> thirdPerson, v -> thirdPerson = v),
                new ModOptionsMenu.Toggle("Camera through walls", () -> throughWalls, v -> throughWalls = v),
                new ModOptionsMenu.Toggle("Only while key is held", () -> holdKey, v -> holdKey = v),
                new ModOptionsMenu.Slider("Camera sensitivity", 0.1D, 3.0D, 0.1D, () -> sensitivity,
                        v -> sensitivity = (float) v, v -> String.format(java.util.Locale.ROOT, "%.1fx", v)),
                new ModOptionsMenu.Toggle("Arrow keys turn the other one", () -> arrows, v -> arrows = v),
                new ModOptionsMenu.Slider("Arrow speed", 0.5D, 10.0D, 0.5D, () -> arrowSpeed,
                        v -> arrowSpeed = (float) v, v -> String.format(java.util.Locale.ROOT, "%.1f°", v))));
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "mode", String.valueOf(mode));
        properties.setProperty(prefix + "thirdPerson", String.valueOf(thirdPerson));
        properties.setProperty(prefix + "throughWalls", String.valueOf(throughWalls));
        properties.setProperty(prefix + "holdKey", String.valueOf(holdKey));
        properties.setProperty(prefix + "sensitivity", String.valueOf(sensitivity));
        properties.setProperty(prefix + "arrows", String.valueOf(arrows));
        properties.setProperty(prefix + "arrowSpeed", String.valueOf(arrowSpeed));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        try {
            mode = Mth.clamp(Integer.parseInt(properties.getProperty(prefix + "mode", String.valueOf(mode)).trim()), 0, MODES.length - 1);
            sensitivity = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "sensitivity", String.valueOf(sensitivity)).trim()), 0.1F, 3.0F);
            arrowSpeed = Mth.clamp(Float.parseFloat(properties.getProperty(prefix + "arrowSpeed", String.valueOf(arrowSpeed)).trim()), 0.5F, 10.0F);
        } catch (NumberFormatException ignored) {
            // Standard behalten
        }
        thirdPerson = Boolean.parseBoolean(properties.getProperty(prefix + "thirdPerson", String.valueOf(thirdPerson)));
        throughWalls = Boolean.parseBoolean(properties.getProperty(prefix + "throughWalls", String.valueOf(throughWalls)));
        holdKey = Boolean.parseBoolean(properties.getProperty(prefix + "holdKey", String.valueOf(holdKey)));
        arrows = Boolean.parseBoolean(properties.getProperty(prefix + "arrows", String.valueOf(arrows)));
    }
}
