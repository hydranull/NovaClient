package nova.nova_client.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;

/**
 * Der Nova-Ladebildschirm fuer alles, was laedt: Spielstart und Ressourcen (LoadingOverlay), Server verbinden, Welt
 * laden, Welt erstellen und speichern. Ruhig und klar: dunkle Flaeche mit dem Sternenhimmel des Nova-Menues (wie der
 * Titelbildschirm), das Nova-Logo in der Mitte, darunter ein haarfeiner Fortschrittsbalken und eine Statuszeile (Prozent in Mono).
 * <p>
 * Beim allerersten Laden gibt es noch keine Schriften und keine Mod-Ressourcen: das Logo kommt deshalb direkt
 * aus der Mod-Datei (eigene Texturen), Text erst, sobald die Schriften geladen sind.
 */
public final class NovaLoadingScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Loading");
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("nova_client", "dynamic/loading_logo");
    private static final long EPOCH = Util.getMillis();

    private static boolean texturesTried;
    private static int logoWidth;
    private static int logoHeight;

    private NovaLoadingScreen() {
    }

    /** Wie der Titelbildschirm: mit "Classic menu" kommen auch Minecrafts eigene Ladebildschirme zurueck. */
    public static boolean enabled() {
        return UiSettings.novaScreens;
    }

    /**
     * Ganzer Bildschirm. alpha 0..1 (Ein-/Ausblenden), progress 0..1 oder kleiner 0 fuer "unbestimmt",
     * status = Zeile unter dem Balken (null = keine, z.B. solange es noch keine Schriften gibt).
     */
    public static void draw(GuiGraphicsExtractor g, int width, int height, float alpha, float progress, String status) {
        float a = Mth.clamp(alpha, 0.0F, 1.0F);
        if (a <= 0.004F) {
            return;
        }
        ensureTextures();
        float t = (Util.getMillis() - EPOCH) / 1000.0F;
        float cx = width / 2.0F;
        float cy = height / 2.0F;

        g.fill(0, 0, width, height, NovaGui.alpha(NovaGui.BG, a));
        NovaStars.draw(g, 0, 0, width, height, a);

        // Logo (Wortmarke)
        float logoW = Mth.clamp(width * 0.22F, 90.0F, 170.0F);
        float logoH = logoWidth > 0 ? logoW * logoHeight / logoWidth : 0.0F;
        float logoTop = cy - logoH * 0.7F;
        drawLogo(g, cx, logoTop, logoW, a);

        // Fortschritt: haarfeiner Balken in der Akzentfarbe
        int barW = Math.round(logoW * 0.5F);
        int x0 = Math.round(cx - barW / 2.0F);
        int x1 = x0 + barW;
        int y0 = Math.round(cy + logoH * 0.48F + 12.0F);
        int y1 = y0 + 1;
        g.fill(x0, y0, x1, y1, NovaGui.alpha(0xFFFFFFFF, 0.08F * a));
        if (progress >= 0.0F) {
            int fill = x0 + Math.round(barW * Mth.clamp(progress, 0.0F, 1.0F));
            if (fill > x0) {
                g.fill(x0, y0, fill, y1, NovaGui.alpha(NovaGui.BRAND, a));
            }
        } else {
            // unbestimmt: ein Stueck des Balkens gleitet hin und her
            int piece = Math.max(8, barW / 3);
            float slide = 0.5F - 0.5F * Mth.cos(t * 2.4F);
            int px = x0 + Math.round((barW - piece) * slide);
            g.fill(px, y0, px + piece, y1, NovaGui.alpha(NovaGui.BRAND, a));
        }

        if (status != null && !status.isBlank()) {
            Font font = Minecraft.getInstance().font;
            int textW = NovaType.width(font, NovaType.Face.CAPTION, status);
            String percent = progress >= 0.0F ? Math.round(Mth.clamp(progress, 0.0F, 1.0F) * 100.0F) + "%" : "";
            int percentW = percent.isEmpty() ? 0 : NovaType.width(font, NovaType.Face.MONO, percent) + 8;
            int tx = Math.round(cx) - (textW + percentW) / 2;
            NovaType.draw(g, font, NovaType.Face.CAPTION, status, tx, y1 + 9, NovaGui.alpha(NovaGui.TEXT_MUTED, a));
            if (!percent.isEmpty()) {
                NovaType.draw(g, font, NovaType.Face.MONO, percent, tx + textW + 8, y1 + 9, NovaGui.alpha(NovaGui.TEXT_DIM, a));
            }
        }
    }

    /**
     * Nova-Wortmarke "nova" mittig bei cx, Oberkante top, Breite width (GUI-Pixel). Das Bild ist "nova" aus Geist 700
     * (wie die Wortmarke im Menue, vorberechnet, weil es beim Spielstart noch keine Schriften gibt).
     */
    public static void drawLogo(GuiGraphicsExtractor g, float cx, float top, float width, float alpha) {
        ensureTextures();
        if (logoWidth <= 0 || alpha <= 0.004F) {
            return;
        }
        float scale = width / logoWidth;
        float height = logoHeight * scale;
        float left = cx - width / 2.0F;
        g.pose().pushMatrix();
        g.pose().translate(left, top);
        g.pose().scale(scale, height / logoHeight);
        g.blit(RenderPipelines.GUI_TEXTURED, LOGO, 0, 0, 0, 0, logoWidth, logoHeight, logoWidth, logoHeight,
                logoWidth, logoHeight, NovaGui.alpha(0xFFFFFFFF, alpha));
        g.pose().popMatrix();
    }

    /** Hoehe der Wortmarke bei dieser Breite. */
    public static float logoHeight(float width) {
        ensureTextures();
        return logoWidth > 0 ? width * logoHeight / logoWidth : 0.0F;
    }

    /**
     * Ladebildschirm statt eines Minecraft-Bildschirms (Verbinden, Welt laden, ...): Nova zeichnen, dann nur die
     * Knoepfe des Bildschirms (z.B. "Abbrechen") darueber.
     */
    public static void drawScreen(GuiGraphicsExtractor g, Screen screen, List<Renderable> widgets, int mouseX, int mouseY,
                                  float partialTick, float progress, Component status) {
        draw(g, screen.width, screen.height, 1.0F, progress, status == null ? null : status.getString());
        // Knoepfe ("Abbrechen") unten in die Mitte statt quer ueber Logo und Ladebalken
        int bottom = screen.height - 12;
        for (int i = widgets.size() - 1; i >= 0; i--) {
            if (widgets.get(i) instanceof AbstractWidget button && !(button instanceof StringWidget)) {
                button.setWidth(Math.min(button.getWidth(), 120));
                button.setX((screen.width - button.getWidth()) / 2);
                button.setY(bottom - button.getHeight());
                bottom -= button.getHeight() + 4;
            }
        }
        for (Renderable widget : widgets) {
            //? if >=26.1 {
            widget.extractRenderState(g, mouseX, mouseY, partialTick);
            //?} else {
            /*widget.render(g, mouseX, mouseY, partialTick);
            *///?}
        }
    }

    /** Logo einmal anlegen - direkt aus der Mod-Datei, damit es schon beim Spielstart geht. */
    private static void ensureTextures() {
        if (texturesTried) {
            return;
        }
        texturesTried = true;
        try {
            try (InputStream in = NovaLoadingScreen.class.getResourceAsStream("/assets/nova_client/textures/gui/wordmark.png")) {
                if (in != null) {
                    NativeImage image = NativeImage.read(in);
                    DynamicTexture logo = new DynamicTexture(() -> "nova loading logo", image) {
                        {
                            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                        }
                    };
                    Minecraft.getInstance().getTextureManager().register(LOGO, logo);
                    logoWidth = image.getWidth();
                    logoHeight = image.getHeight();
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Nova loading screen textures could not be created", e);
        }
    }
}
