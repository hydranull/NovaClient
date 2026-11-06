package nova.nova_client.client.hub;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import nova.nova_client.api.AddonCanvas;
import nova.nova_client.api.AddonModule;
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.NovaType;
import nova.nova_client.client.mods.GameTickMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Ein Modul aus einem Add-on, im Nova-Menue wie jedes andere. Fehler im Add-on schalten nur dieses Modul aus (einmal
 * im Log), das Spiel laeuft weiter.
 */
public final class AddonMod extends GameTickMod {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Addons");

    private final AddonModule module;
    private final String addon;

    AddonMod(AddonModule module, String addon) {
        super(module.name(), category(module.category()));
        this.module = module;
        this.addon = addon;
    }

    private static ModCategory category(String name) {
        try {
            return ModCategory.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ModCategory.MISC;
        }
    }

    public String description() {
        return module.description();
    }

    /** Fabric-ID des Add-ons, aus dem das Modul kommt. */
    public String addon() {
        return addon;
    }

    @Override
    public void execute(Minecraft client) {
        run(module::onTick);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        run(() -> module.setEnabled(enabled));
    }

    void hud(GuiGraphicsExtractor g) {
        run(() -> module.onHud(new Canvas(g)));
    }

    private void run(Runnable call) {
        try {
            call.run();
        } catch (Throwable t) {
            LOGGER.error("Add-on {}: module {} failed and was turned off", addon, name, t);
            active = false;
            try {
                module.setEnabled(false);
            } catch (Throwable ignored) {
                // auch das Ausschalten kann scheitern
            }
        }
    }

    /** Zeichnen fuer Add-ons mit Novas Schrift und Formen. */
    private record Canvas(GuiGraphicsExtractor g) implements AddonCanvas {
        @Override
        public int width() {
            return Minecraft.getInstance().getWindow().getGuiScaledWidth();
        }

        @Override
        public int height() {
            return Minecraft.getInstance().getWindow().getGuiScaledHeight();
        }

        @Override
        public int textWidth(String text) {
            return NovaType.width(Minecraft.getInstance().font, NovaType.Face.BODY, text);
        }

        @Override
        public void text(String text, int x, int y, int color) {
            NovaType.draw(g, Minecraft.getInstance().font, NovaType.Face.BODY, text, x, y, color);
        }

        @Override
        public void rect(int x0, int y0, int x1, int y1, int radius, int color) {
            NovaShapes.rect(g, x0, y0, x1, y1, radius, color);
        }

        @Override
        public void hudBox(int x0, int y0, int x1, int y1) {
            NovaShapes.rect(g, x0, y0, x1, y1, NovaGui.HUD_RADIUS, NovaGui.HUD_BACKGROUND);
            NovaShapes.outline(g, x0, y0, x1, y1, NovaGui.HUD_RADIUS, NovaGui.HUD_BORDER);
        }
    }
}
