package nova.nova_client.client;

//? if >=26.1 {
import com.mojang.renderpearl.api.GpuFormat;
//?}
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import nova.nova_client.client.mods.SkyVfxMod;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Einstellungen fuer Sky VFX: Himmel als Kacheln mit bewegter Live-Vorschau, dazu die Geschwindigkeit.
 * Die Vorschau zeichnet derselbe Shader wie der Himmel im Spiel (SkyVfx.renderPreview), jedes Bild neu.
 * Funktioniert in jedem Design (auch Classic), da es ueber Rechtsklick auf das Modul geoeffnet wird.
 */
public class SkyVfxMenu extends NovaSettingsScreen {
    private static final int COLUMNS = 2;
    private static final int GAP = 6;
    /** Vorschaubilder in echten Pixeln (Seitenverhaeltnis der Kachel 2:1). */
    private static final int PREVIEW_W = 384;
    private static final int PREVIEW_H = 192;
    /**
     * Vorschau oben/unten gespiegelt zeichnen: Render-Ziele liegen im Speicher mit unten zuerst, das GUI zeigt v = 0
     * oben (wie beim VR-Fenster und Vanillas Bild-im-Bild) - ungespiegelt stand der Himmel kopf.
     */
    private static final boolean PREVIEW_FLIP = true;

    private final Map<SkyStyle, TextureTarget> previews = new EnumMap<>(SkyStyle.class);
    private final Map<SkyStyle, Float> hoverAnim = new EnumMap<>(SkyStyle.class);
    private boolean previewsWork = true;
    private boolean draggingSpeed;
    private boolean draggingBrightness;

    public SkyVfxMenu(Screen parent) {
        super(parent, "Sky VFX");
    }

    @Override
    protected String subtitle() {
        return "Current sky: " + SkyVfxMod.style.title;
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    public void removed() {
        for (TextureTarget target : previews.values()) {
            target.destroyBuffers();
        }
        previews.clear();
        super.removed();
    }

    // ---------------------------------------------------------------- Geometrie

    private int tileWidth() {
        return (cx1 - cx0 - GAP * (COLUMNS - 1)) / COLUMNS;
    }

    private int previewHeight() {
        // ab zwei Reihen etwas flacher, damit alles ins Fenster passt
        return Math.round(tileWidth() * (SkyStyle.values().length > COLUMNS ? 0.4F : 0.5F));
    }

    private int tileHeight() {
        return previewHeight() + 20;
    }

    private int tileX(int index) {
        return cx0 + (index % COLUMNS) * (tileWidth() + GAP);
    }

    private int tileY(int index) {
        return cy0 + (index / COLUMNS) * (tileHeight() + GAP);
    }

    private int infoY() {
        int rows = (SkyStyle.values().length + COLUMNS - 1) / COLUMNS;
        return cy0 + rows * (tileHeight() + GAP) + 4;
    }

    private int speedY() {
        return infoY() + 20;
    }

    private int imagesX0() {
        return cx1 - Math.max(120, NovaGui.width(font, "Add images to " + SkyVfxMod.style.title) + 16);
    }

    private boolean imagesHovered(double mouseX, double mouseY) {
        return isHovering((int) mouseX, (int) mouseY, imagesX0(), infoY() - 3, cx1, infoY() + 13);
    }

    private int speedX0() {
        return cx0 + NovaGui.width(font, "Speed") + 14;
    }

    private int speedX1() {
        return cx1 - NovaGui.width(font, "3.00x") - 12;
    }

    private boolean speedHovered(double mouseX, double mouseY) {
        return isHovering((int) mouseX, (int) mouseY, speedX0() - 6, speedY() - 2, speedX1() + 6, speedY() + 12);
    }

    private int brightnessY() {
        return speedY() + 18;
    }

    private boolean brightnessHovered(double mouseX, double mouseY) {
        return isHovering((int) mouseX, (int) mouseY, speedX0() - 6, brightnessY() - 2, speedX1() + 6, brightnessY() + 12);
    }

    private void setBrightnessFromMouse(double mouseX) {
        float t = Math.max(0.0F, Math.min(1.0F, (float) ((mouseX - speedX0()) / (speedX1() - speedX0()))));
        SkyVfxMod.setBrightness(SkyVfxMod.style, SkyVfxMod.MIN_BRIGHTNESS
                + (SkyVfxMod.MAX_BRIGHTNESS - SkyVfxMod.MIN_BRIGHTNESS) * t);
    }

    private void setSpeedFromMouse(double mouseX) {
        float t = (float) ((mouseX - speedX0()) / (speedX1() - speedX0()));
        t = Math.max(0.0F, Math.min(1.0F, t));
        SkyVfxMod.setSpeed(SkyVfxMod.MIN_SPEED + (SkyVfxMod.MAX_SPEED - SkyVfxMod.MIN_SPEED) * t);
    }

    private SkyStyle styleAt(double mouseX, double mouseY) {
        SkyStyle[] styles = SkyStyle.values();
        for (int i = 0; i < styles.length; i++) {
            int x = tileX(i);
            int y = tileY(i);
            if (isHovering((int) mouseX, (int) mouseY, x, y, x + tileWidth() - 1, y + tileHeight() - 1)) {
                return styles[i];
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            SkyStyle clicked = styleAt(event.x(), event.y());
            if (imagesHovered(event.x(), event.y())) {
                NovaGui.clickSound();
                net.minecraft.client.Minecraft.getInstance().gui.setScreen(new SkyImageEditor(this, SkyVfxMod.style));
                return true;
            }
            if (clicked != null) {
                SkyVfxMod.style = clicked;
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (speedHovered(event.x(), event.y())) {
                draggingSpeed = true;
                setSpeedFromMouse(event.x());
                return true;
            }
            if (brightnessHovered(event.x(), event.y())) {
                draggingBrightness = true;
                setBrightnessFromMouse(event.x());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSpeed) {
            setSpeedFromMouse(event.x());
            return true;
        }
        if (draggingBrightness) {
            setBrightnessFromMouse(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingSpeed || draggingBrightness) {
            draggingSpeed = false;
            draggingBrightness = false;
            ModConfig.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (speedHovered(mouseX, mouseY)) {
            SkyVfxMod.setSpeed(SkyVfxMod.speed + (scrollY > 0 ? 0.05F : -0.05F));
            ModConfig.save();
            return true;
        }
        if (brightnessHovered(mouseX, mouseY)) {
            SkyVfxMod.setBrightness(SkyVfxMod.style, SkyVfxMod.brightness(SkyVfxMod.style) + (scrollY > 0 ? 0.05F : -0.05F));
            ModConfig.save();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isLeft() || event.isRight()) {
            SkyStyle[] styles = SkyStyle.values();
            int next = Math.floorMod(SkyVfxMod.style.ordinal() + (event.isRight() ? 1 : -1), styles.length);
            SkyVfxMod.style = styles[next];
            ModConfig.save();
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- Vorschau

    /** Alle Vorschaubilder mit dem Himmel-Shader neu zeichnen (kostet die Grafikkarte kaum etwas). */
    private void updatePreviews() {
        if (!previewsWork) {
            return;
        }
        float time = SkyVfx.time();
        for (SkyStyle style : SkyStyle.values()) {
            TextureTarget target = previews.computeIfAbsent(style,
                    s -> RenderCompat.target("Nova sky preview " + s.name(), PREVIEW_W, PREVIEW_H, false));
            if (!SkyVfx.renderPreview(style, target, time, PREVIEW_FLIP)) {
                previewsWork = false; // Shader fehlt: Kacheln ohne Bild
                return;
            }
        }
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        updatePreviews();

        SkyStyle hovered = styleAt(mouseX, mouseY);
        SkyStyle[] styles = SkyStyle.values();
        int tileW = tileWidth();
        int tileH = tileHeight();
        int previewH = previewHeight();
        for (int i = 0; i < styles.length; i++) {
            SkyStyle style = styles[i];
            int x0 = tileX(i);
            int y0 = tileY(i);
            int x1 = x0 + tileW;
            int y1 = y0 + tileH;
            boolean selected = SkyVfxMod.style == style;
            boolean hover = style == hovered;
            pointer |= hover;
            float h = NovaGui.approach(hoverAnim.getOrDefault(style, 0.0F), hover ? 1.0F : 0.0F, 14.0F, dt);
            hoverAnim.put(style, h);

            NovaShapes.rect(graphics, x0, y0, x1, y1, 5, NovaGui.mix(NovaGui.CARD, NovaGui.RAISED, h));

            // Live-Vorschau (leicht hereingezoomt beim Hover)
            TextureTarget preview = previewsWork ? previews.get(style) : null;
            if (preview != null) {
                float zoom = 0.06F * h;
                Compat.blit(graphics, preview.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                        x0 + 3, y0 + 3, x1 - 3, y0 + 3 + previewH, zoom, 1.0F - zoom, zoom, 1.0F - zoom);
            }
            NovaShapes.outline(graphics, x0, y0, x1, y1, 5, selected ? accent : NovaGui.mix(NovaGui.BORDER, NovaGui.TEXT_MUTED, h));
            NovaGui.centered(graphics, font, NovaGui.ellipsize(font, style.title, tileW - 8), (x0 + x1) / 2,
                    y0 + previewH + 8, selected ? NovaGui.TEXT : NovaGui.mix(NovaGui.TEXT_DIM, NovaGui.TEXT, h));
        }

        // Beschreibung des gezeigten Himmels
        SkyStyle info = hovered != null ? hovered : SkyVfxMod.style;
        String text = info.title + "  -  " + info.description;
        NovaGui.text(graphics, font, NovaGui.ellipsize(font, text, imagesX0() - cx0 - 8), cx0 + 2, infoY() + 2, NovaGui.TEXT_MUTED);

        // Eigene Bilder
        boolean imagesHover = imagesHovered(mouseX, mouseY);
        pointer |= imagesHover;
        int count = SkyImages.placed(SkyVfxMod.style).size();
        String imagesLabel = count > 0 ? "Your images in " + SkyVfxMod.style.title + " (" + count + ")"
                : "Add images to " + SkyVfxMod.style.title;
        NovaControls.choice(graphics, font, imagesX0(), infoY() - 3, cx1, infoY() + 13, imagesLabel, imagesHover, count > 0, accent);

        // Geschwindigkeit
        int sy = speedY();
        int sx0 = speedX0();
        int sx1 = speedX1();
        boolean speedHover = speedHovered(mouseX, mouseY) || draggingSpeed;
        pointer |= speedHover;
        NovaGui.text(graphics, font, "Speed", cx0 + 2, sy + 1, speedHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaShapes.rect(graphics, sx0, sy + 3, sx1, sy + 7, 2, NovaGui.SWITCH_OFF);
        float t = (SkyVfxMod.speed - SkyVfxMod.MIN_SPEED) / (SkyVfxMod.MAX_SPEED - SkyVfxMod.MIN_SPEED);
        int kx = sx0 + Math.round((sx1 - sx0) * t);
        NovaShapes.rect(graphics, sx0, sy + 3, kx, sy + 7, 2, accent);
        // Markierung bei 1x (Menue-Tempo)
        int oneX = sx0 + Math.round((sx1 - sx0) * (1.0F - SkyVfxMod.MIN_SPEED) / (SkyVfxMod.MAX_SPEED - SkyVfxMod.MIN_SPEED));
        graphics.fill(oneX, sy + 9, oneX + 1, sy + 12, NovaGui.TEXT_MUTED);
        NovaControls.knob(graphics, kx, sy + 5, speedHover, accent);
        String value = String.format(Locale.ROOT, "%.2fx", SkyVfxMod.speed);
        NovaGui.text(graphics, font, value, cx1 - NovaGui.width(font, value), sy + 1, NovaGui.TEXT);

        // Helligkeit des gewaehlten Himmels
        int by = brightnessY();
        boolean lightHover = brightnessHovered(mouseX, mouseY) || draggingBrightness;
        pointer |= lightHover;
        NovaGui.text(graphics, font, "Light", cx0 + 2, by + 1, lightHover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        NovaShapes.rect(graphics, sx0, by + 3, sx1, by + 7, 2, NovaGui.SWITCH_OFF);
        float lt = (SkyVfxMod.brightness(SkyVfxMod.style) - SkyVfxMod.MIN_BRIGHTNESS)
                / (SkyVfxMod.MAX_BRIGHTNESS - SkyVfxMod.MIN_BRIGHTNESS);
        int lx = sx0 + Math.round((sx1 - sx0) * lt);
        NovaShapes.rect(graphics, sx0, by + 3, lx, by + 7, 2, accent);
        NovaControls.knob(graphics, lx, by + 5, lightHover, accent);
        String light = Math.round(SkyVfxMod.brightness(SkyVfxMod.style) * 100.0F) + "%";
        NovaGui.text(graphics, font, light, cx1 - NovaGui.width(font, light), by + 1, NovaGui.TEXT);

        if (!SkyVfxMod.isEnabled()) {
            NovaGui.centered(graphics, font, "Module is off - enable it to see the sky in game", (cx0 + cx1) / 2,
                    by + 20, NovaGui.TEXT_MUTED);
        }
        return pointer;
    }
}
