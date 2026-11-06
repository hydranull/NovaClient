package nova.nova_client.client.mods;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NameProtectMenu;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
//? if <26.1 {
/*import org.lwjgl.util.tinyfd.TinyFileDialogs;
*///?}

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

/**
 * NameProtect: zeigt statt des eigenen Namens einen frei waehlbaren Namen an - ueberall, wo Text gezeichnet wird
 * (Chat, Tab-Liste, Nametag, Scoreboard, Menues). Dazu Skin Protect: eine eigene Skin-Datei (PNG, 64x64) wird statt
 * des echten Skins angezeigt, oder - ohne Datei - der Standard-Steve-Skin. Beides nur auf dem eigenen Bildschirm -
 * der Server und andere Spieler sehen weiter das Original.
 */
public class NameProtectMod extends GameTickMod {
    public static final Identifier SKIN_ID = Identifier.fromNamespaceAndPath("nova_client", "dynamic/skin_protect");
    private static final Path SKIN_FILE = FabricLoader.getInstance().getConfigDir().resolve("nova_client_skin.png");

    // Einstellungen
    public static String fakeName = "Player";
    public static boolean protectName = true;
    public static boolean protectSkin = false;
    /** Statt einer eigenen Datei den Standard-Steve-Skin von Minecraft verwenden. */
    public static boolean steveSkin = false;

    private static volatile boolean enabled;
    private static String realName;
    private static UUID realId;

    // Skin-Textur
    private static boolean skinLoaded;
    private static boolean skinTried;
    private static String skinStatus = "";
    private static PlayerSkin cachedOriginal;
    private static PlayerSkin cachedSkin;
    private static boolean cachedSteve;
    /** Minecrafts eingebaute Steve-Textur (wie DefaultPlayerSkin sie baut). */
    public static final Identifier STEVE_TEXTURE = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final ClientAsset.ResourceTexture STEVE = new ClientAsset.ResourceTexture(
            Identifier.withDefaultNamespace("entity/player/wide/steve"));
    private static volatile boolean picking;

    public NameProtectMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        NameProtectMod.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Override
    public void execute(Minecraft client) {
        // alles passiert beim Zeichnen (Text/Skin)
    }

    // ---------------------------------------------------------------- Name

    private static String realName() {
        if (realName == null) {
            realName = Minecraft.getInstance().getUser().getName();
        }
        return realName;
    }

    /** Eigenen Namen im Text ersetzen (aufgerufen fuer jeden gezeichneten Text-Abschnitt). */
    public static String protect(String text) {
        if (!enabled || !protectName || text == null || fakeName.isEmpty()) {
            return text;
        }
        String real = realName();
        if (real.isEmpty() || !text.contains(real)) {
            return text;
        }
        return text.replace(real, fakeName);
    }

    // ---------------------------------------------------------------- Skin

    /** Ist das der eigene Spieler? */
    public static boolean isSelf(UUID id) {
        if (realId == null) {
            realId = Minecraft.getInstance().getUser().getProfileId();
        }
        return id != null && id.equals(realId);
    }

    /** Eigener Skin statt des Originals, oder null wenn Skin Protect aus ist / keine Datei geladen ist. */
    public static PlayerSkin skin(PlayerSkin original) {
        if (!enabled || !protectSkin || original == null || !steveSkin && !ensureTexture()) {
            return null;
        }
        if (original != cachedOriginal || steveSkin != cachedSteve || cachedSkin == null) {
            cachedOriginal = original;
            cachedSteve = steveSkin;
            // Steve: Minecrafts eingebaute Textur (wie DefaultPlayerSkin), sonst die eigene Datei; immer breites Modell
            ClientAsset.Texture body = steveSkin ? STEVE
                    : new ClientAsset.DownloadedTexture(SKIN_ID, "nova_client:skin_protect");
            cachedSkin = new PlayerSkin(body, original.cape(), original.elytra(), PlayerModelType.WIDE,
                    original.secure());
        }
        return cachedSkin;
    }

    public static boolean hasSkin() {
        return ensureTexture();
    }

    public static String skinStatus() {
        ensureTexture();
        return skinStatus;
    }

    public static boolean isPicking() {
        return picking;
    }

    /** Skin-Datei beim ersten Bedarf laden (Render-Thread). */
    private static boolean ensureTexture() {
        if (skinLoaded || skinTried) {
            return skinLoaded;
        }
        skinTried = true;
        if (!Files.isRegularFile(SKIN_FILE)) {
            skinStatus = "No skin chosen";
            return false;
        }
        try {
            NativeImage image = NativeImage.read(Files.readAllBytes(SKIN_FILE));
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                skinStatus = "Skin must be 64x64 (is " + image.getWidth() + "x" + image.getHeight() + ")";
                image.close();
                return false;
            }
            Minecraft.getInstance().getTextureManager().register(SKIN_ID,
                    new DynamicTexture(() -> "nova_client skin protect", image));
            skinLoaded = true;
            skinStatus = "Custom skin loaded";
            cachedSkin = null;
        } catch (IOException | RuntimeException e) {
            skinStatus = "Could not read skin: " + e.getMessage();
        }
        return skinLoaded;
    }

    /** Skin neu laden (nach Dateiauswahl / Entfernen). */
    private static void reloadSkin() {
        skinLoaded = false;
        skinTried = false;
        cachedSkin = null;
        ensureTexture();
    }

    /** Oeffnet den Datei-Dialog (das Spiel laeuft weiter) und uebernimmt die gewaehlte PNG. */
    public static void pickSkinFile() {
        if (picking) {
            return;
        }
        picking = true;
        //? if >=26.1 {
        nova.nova_client.client.SdlFileDialog.openPng("Minecraft skin (*.png)", NameProtectMod::useChosenSkin);
        //?} else {
        /*Thread thread = new Thread(() -> {
            String chosen;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png"));
                filters.flip();
                chosen = TinyFileDialogs.tinyfd_openFileDialog("Choose a skin (PNG, 64x64)", "", filters,
                        "Minecraft skin (*.png)", false);
            }
            useChosenSkin(chosen);
        }, "Nova skin picker");
        thread.setDaemon(true);
        thread.start();
        *///?}
    }

    /** Gewaehlte Datei (null = abgebrochen) in den Nova-Ordner kopieren; laeuft im Dialog-Thread. */
    private static void useChosenSkin(String chosen) {
        String message = null;
        if (chosen != null) {
            try {
                Files.createDirectories(SKIN_FILE.getParent());
                Files.copy(Path.of(chosen), SKIN_FILE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                message = "Could not copy skin: " + e.getMessage();
            }
        }
        String error = message;
        Minecraft.getInstance().execute(() -> {
            picking = false;
            if (chosen == null) {
                return; // abgebrochen
            }
            if (error != null) {
                skinStatus = error;
                return;
            }
            protectSkin = true;
            reloadSkin();
        });
    }

    /** Eigene Skin-Datei loeschen (zurueck zum echten Skin). */
    public static void removeSkin() {
        try {
            Files.deleteIfExists(SKIN_FILE);
        } catch (IOException e) {
            skinStatus = "Could not remove skin: " + e.getMessage();
            return;
        }
        Minecraft.getInstance().getTextureManager().release(SKIN_ID);
        reloadSkin();
    }

    // ---------------------------------------------------------------- Einstellungen

    @Override
    public boolean hasSettings() {
        return true;
    }

    @Override
    public Screen createSettings(Screen parent) {
        return new NameProtectMenu(parent);
    }

    @Override
    public void saveSettings(Properties properties, String prefix) {
        properties.setProperty(prefix + "name", fakeName);
        properties.setProperty(prefix + "protectName", String.valueOf(protectName));
        properties.setProperty(prefix + "protectSkin", String.valueOf(protectSkin));
        properties.setProperty(prefix + "steve", String.valueOf(steveSkin));
    }

    @Override
    public void loadSettings(Properties properties, String prefix) {
        fakeName = properties.getProperty(prefix + "name", fakeName);
        protectName = Boolean.parseBoolean(properties.getProperty(prefix + "protectName", String.valueOf(protectName)));
        protectSkin = Boolean.parseBoolean(properties.getProperty(prefix + "protectSkin", String.valueOf(protectSkin)));
        steveSkin = Boolean.parseBoolean(properties.getProperty(prefix + "steve", String.valueOf(steveSkin)));
    }
}
