package nova.nova_client.client.hub;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import nova.nova_client.api.AddonModule;
import nova.nova_client.api.NovaAddon;
import nova.nova_client.api.NovaApi;
import nova.nova_client.client.Mods;
import nova.nova_client.client.social.SocialHud;
import nova.nova_client.client.update.UpdateManager;
import nova.nova_client.client.mods.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Startet die installierten Add-ons: jede Fabric-Mod mit dem Einstiegspunkt "nova" bekommt beim Start die {@link NovaApi}
 * und meldet ihre Module an. Ein Add-on, das dabei abstuerzt, wird uebersprungen.
 */
public final class AddonLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Addons");
    private static final List<AddonMod> MODS = new ArrayList<>();

    private AddonLoader() {
    }

    /** Nach Mods.init(), vor dem Laden der Config (damit Einstellungen und Schalter der Add-on-Module mitkommen). */
    public static void load() {
        for (EntrypointContainer<NovaAddon> container : FabricLoader.getInstance().getEntrypointContainers("nova", NovaAddon.class)) {
            String id = container.getProvider().getMetadata().getId();
            try {
                container.getEntrypoint().onInitialize(new Api(id));
                LOGGER.info("Nova add-on {} loaded", id);
            } catch (Throwable t) {
                LOGGER.error("Nova add-on {} failed to start", id, t);
            }
        }
    }

    /** Jedes HUD-Bild: die aktiven Add-on-Module zeichnen. */
    public static void renderHud(GuiGraphicsExtractor g) {
        for (AddonMod mod : MODS) {
            if (mod.active) {
                mod.hud(g);
            }
        }
    }

    /** Fabric-IDs aller Add-ons, die gerade geladen sind. */
    public static List<String> loaded() {
        List<String> ids = new ArrayList<>();
        for (EntrypointContainer<NovaAddon> container : FabricLoader.getInstance().getEntrypointContainers("nova", NovaAddon.class)) {
            ids.add(container.getProvider().getMetadata().getId());
        }
        return ids;
    }

    private record Api(String addon) implements NovaApi {
        @Override
        public void register(AddonModule module) {
            if (module == null || module.name() == null || module.name().isBlank()) {
                return;
            }
            for (Mod existing : Mods.mods) {
                if (existing instanceof AddonMod other && other.addon().equals(addon) && other.name.equals(module.name())) {
                    return; // doppelt angemeldet
                }
            }
            AddonMod mod = new AddonMod(module, addon);
            MODS.add(mod);
            Mods.mods.add(mod);
        }

        @Override
        public String minecraftVersion() {
            String version = UpdateManager.minecraftVersion();
            return version == null ? "" : version;
        }

        @Override
        public void notify(String title, String text) {
            SocialHud.toast(null, title, "add-on", text);
        }
    }
}
