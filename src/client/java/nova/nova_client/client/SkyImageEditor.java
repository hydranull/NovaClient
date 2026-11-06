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
import net.minecraft.client.renderer.texture.DynamicTexture;
import nova.nova_client.client.mods.SkyVfxMod;
import org.joml.Vector3f;

import java.util.List;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Eigene Bilder in einen Sky-VFX-Himmel setzen: links die PNGs aus .minecraft/nova_client/sky, rechts der Himmel als
 * Live-Vorschau (derselbe Shader wie im Spiel), in der man sich umschauen kann. Klick setzt das gewaehlte Bild an die
 * Stelle, Ziehen verschiebt, Mausrad aendert die Groesse, Rechtsklick loescht, rechte Maustaste ziehen schaut herum.
 */
public class SkyImageEditor extends NovaSettingsScreen {
    private static final int LIST_W = 118;
    private static final int ROW = 14;
    private static final int BUTTON_H = 16;
    private static final float FOV = 70.0F;
    private static final float DEFAULT_SIZE = 24.0F;
    /** Aufloesung der Vorschau (echte Pixel). */
    private static final int PREVIEW_W = 768;
    private static final int PREVIEW_H = 432;

    private final SkyStyle style;
    private TextureTarget preview;
    private boolean previewWorks = true;
    private float viewYaw;
    private float viewPitch;
    /** Gewaehltes Bild aus der Liste (wird beim Klick in den Himmel gesetzt). */
    private String brush;
    private SkyImages.Placement selected;
    private SkyImages.Placement dragging;
    private boolean looking;
    private boolean lookMoved;
    private double lastMouseX;
    private double lastMouseY;
    private int scroll;

    public SkyImageEditor(Screen parent, SkyStyle style) {
        super(parent, "Images: " + style.title);
        this.style = style;
        this.viewYaw = style.previewYaw;
        this.viewPitch = Math.max(-30.0F, Math.min(60.0F, style.previewPitch));
        SkyImages.reload();
        List<String> files = SkyImages.files();
        brush = files.isEmpty() ? null : files.getFirst();
    }

    @Override
    protected String subtitle() {
        return "Click to place  -  drag to move  -  scroll to resize  -  right click to delete  -  right drag to look";
    }

    @Override
    protected void init() {
        super.init();
        setPanel(560, 274);
    }

    @Override
    public void onClose() {
        ModConfig.save();
        super.onClose();
    }

    @Override
    public void removed() {
        if (preview != null) {
            preview.destroyBuffers();
            preview = null;
        }
        super.removed();
    }

    // ---------------------------------------------------------------- Geometrie

    private int viewX0() {
        return cx0 + LIST_W + 10;
    }

    private int viewX1() {
        return cx1;
    }

    private int viewY0() {
        return cy0;
    }

    /** Hoehe der Vorschau im Seitenverhaeltnis 16:9 (passt in den Platz ueber der Knopfzeile). */
    private int viewY1() {
        int byWidth = cy0 + Math.round((viewX1() - viewX0()) * PREVIEW_H / (float) PREVIEW_W);
        return Math.min(byWidth, cy1 - BUTTON_H - 22);
    }

    private int bottomY() {
        return cy1 - BUTTON_H;
    }

    private int listTop() {
        return cy0 + 12;
    }

    private int visibleRows() {
        return Math.max(1, (bottomY() - 6 - listTop()) / ROW);
    }

    private boolean inView(double mouseX, double mouseY) {
        return isHovering((int) mouseX, (int) mouseY, viewX0(), viewY0(), viewX1(), viewY1());
    }

    private boolean inOpenFolder(double mx, double my) {
        return isHovering((int) mx, (int) my, cx0, bottomY(), cx0 + (LIST_W - 4) / 2, bottomY() + BUTTON_H);
    }

    private boolean inReload(double mx, double my) {
        return isHovering((int) mx, (int) my, cx0 + (LIST_W - 4) / 2 + 4, bottomY(), cx0 + LIST_W, bottomY() + BUTTON_H);
    }

    private boolean inClear(double mx, double my) {
        return isHovering((int) mx, (int) my, viewX1() - 90, bottomY(), viewX1(), bottomY() + BUTTON_H);
    }

    // ---------------------------------------------------------------- Kamera der Vorschau

    private Vector3f forward() {
        double yaw = Math.toRadians(viewYaw);
        double pitch = Math.toRadians(viewPitch);
        return new Vector3f((float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch),
                (float) (Math.cos(yaw) * Math.cos(pitch)));
    }

    private float tanY() {
        return (float) Math.tan(Math.toRadians(FOV * 0.5F));
    }

    /** Wie die Vorschau gerechnet wird (Seitenverhaeltnis des Render-Ziels; das Bild wird in die Flaeche gezogen). */
    private float tanX() {
        return tanY() * PREVIEW_W / (float) PREVIEW_H;
    }

    /** Richtung (Einheitsvektor) unter einem Punkt der Vorschau - wie im Vorschau-Shader. */
    private Vector3f directionAt(double mouseX, double mouseY) {
        float ndcX = (float) ((mouseX - viewX0()) / (viewX1() - viewX0()) * 2.0D - 1.0D);
        float ndcY = (float) (1.0D - (mouseY - viewY0()) / (viewY1() - viewY0()) * 2.0D);
        Vector3f f = forward();
        Vector3f right = new Vector3f(f).cross(0.0F, 1.0F, 0.0F).normalize();
        Vector3f up = new Vector3f(right).cross(f).normalize();
        return new Vector3f(f).add(right.mul(ndcX * tanX())).add(up.mul(ndcY * tanY())).normalize();
    }

    private static float yawOf(Vector3f d) {
        return (float) Math.toDegrees(Math.atan2(d.x, d.z));
    }

    private static float pitchOf(Vector3f d) {
        return (float) Math.toDegrees(Math.asin(Math.max(-1.0F, Math.min(1.0F, d.y))));
    }

    private static Vector3f direction(float yawDegrees, float pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        return new Vector3f((float) (Math.sin(yaw) * Math.cos(pitch)), (float) Math.sin(pitch),
                (float) (Math.cos(yaw) * Math.cos(pitch)));
    }

    /** Bildschirm-Rechteck eines Bildes {x0, y0, x1, y1} oder null (hinter der Kamera). */
    private float[] screenRect(SkyImages.Placement placement) {
        Vector3f d = direction(placement.yaw, placement.pitch);
        Vector3f f = forward();
        Vector3f right = new Vector3f(f).cross(0.0F, 1.0F, 0.0F).normalize();
        Vector3f up = new Vector3f(right).cross(f).normalize();
        float z = d.dot(f);
        if (z < 0.15F) {
            return null;
        }
        float ndcX = d.dot(right) / z / tanX();
        float ndcY = d.dot(up) / z / tanY();
        float w = viewX1() - viewX0();
        float h = viewY1() - viewY0();
        float cx = viewX0() + (ndcX + 1.0F) * 0.5F * w;
        float cy = viewY0() + (1.0F - ndcY) * 0.5F * h;
        float halfTan = (float) Math.tan(Math.toRadians(placement.size * 0.5F));
        float halfW = halfTan / z / tanX() * 0.5F * w;
        float halfH = halfW / Math.max(0.05F, SkyImages.aspect(placement.file));
        return new float[]{cx - halfW, cy - halfH, cx + halfW, cy + halfH};
    }

    /** Oberstes Bild unter der Maus (zuletzt gezeichnetes zuerst). */
    private SkyImages.Placement placementAt(double mouseX, double mouseY) {
        List<SkyImages.Placement> list = SkyImages.placed(style);
        for (int i = list.size() - 1; i >= 0; i--) {
            float[] rect = screenRect(list.get(i));
            if (rect != null && mouseX >= rect[0] && mouseX <= rect[2] && mouseY >= rect[1] && mouseY <= rect[3]) {
                return list.get(i);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (event.button() == 0) {
            List<String> files = SkyImages.files();
            for (int row = 0; row < visibleRows(); row++) {
                int index = scroll + row;
                int y = listTop() + row * ROW;
                if (index < files.size() && isHovering((int) mx, (int) my, cx0, y, cx0 + LIST_W, y + ROW - 2)) {
                    brush = files.get(index);
                    NovaGui.clickSound();
                    return true;
                }
            }
            if (inOpenFolder(mx, my)) {
                SkyImages.openFolder();
                NovaGui.clickSound();
                return true;
            }
            if (inReload(mx, my)) {
                SkyImages.reload();
                if (brush != null && !SkyImages.files().contains(brush)) {
                    brush = SkyImages.files().isEmpty() ? null : SkyImages.files().getFirst();
                }
                NovaGui.clickSound();
                return true;
            }
            if (inClear(mx, my) && !SkyImages.placed(style).isEmpty()) {
                SkyImages.placed(style).clear();
                selected = null;
                ModConfig.save();
                NovaGui.clickSound();
                return true;
            }
            if (inView(mx, my)) {
                SkyImages.Placement hit = placementAt(mx, my);
                if (hit != null) {
                    selected = hit;
                    dragging = hit;
                } else if (brush != null && SkyImages.placed(style).size() < SkyImages.MAX_PER_SKY) {
                    Vector3f d = directionAt(mx, my);
                    SkyImages.Placement placement = new SkyImages.Placement(brush, yawOf(d), pitchOf(d), DEFAULT_SIZE);
                    SkyImages.placed(style).add(placement);
                    selected = placement;
                    dragging = placement;
                    NovaGui.clickSound();
                }
                return true;
            }
        } else if (event.button() == 1 && inView(mx, my)) {
            looking = true;
            lookMoved = false;
            lastMouseX = mx;
            lastMouseY = my;
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging != null) {
            Vector3f d = directionAt(Math.max(viewX0(), Math.min(viewX1(), event.x())),
                    Math.max(viewY0(), Math.min(viewY1(), event.y())));
            dragging.yaw = yawOf(d);
            dragging.pitch = Math.max(-89.0F, Math.min(89.0F, pitchOf(d)));
            return true;
        }
        if (looking) {
            double dx = event.x() - lastMouseX;
            double dy = event.y() - lastMouseY;
            if (Math.abs(dx) + Math.abs(dy) > 0.5D) {
                lookMoved = true;
            }
            float degreesPerPixel = FOV / Math.max(1, viewY1() - viewY0());
            // Ziehen wie an einer Kugel: nach rechts ziehen = nach links schauen
            viewYaw = (float) (viewYaw + dx * degreesPerPixel);
            viewPitch = (float) Math.max(-80.0F, Math.min(80.0F, viewPitch + dy * degreesPerPixel));
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null && event.button() == 0) {
            dragging = null;
            ModConfig.save();
            return true;
        }
        if (looking && event.button() == 1) {
            looking = false;
            if (!lookMoved) {
                // Rechtsklick ohne Ziehen: Bild darunter loeschen
                SkyImages.Placement hit = placementAt(event.x(), event.y());
                if (hit != null) {
                    SkyImages.placed(style).remove(hit);
                    if (selected == hit) {
                        selected = null;
                    }
                    ModConfig.save();
                    NovaGui.clickSound();
                }
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (inView(mouseX, mouseY)) {
            SkyImages.Placement hit = placementAt(mouseX, mouseY);
            SkyImages.Placement target = hit != null ? hit : selected;
            if (target != null) {
                float factor = scrollY > 0 ? 1.1F : 1.0F / 1.1F;
                target.size = Math.max(SkyImages.MIN_SIZE, Math.min(SkyImages.MAX_SIZE_DEGREES, target.size * factor));
                selected = target;
                ModConfig.save();
            }
            return true;
        }
        int max = Math.max(0, SkyImages.files().size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll + (scrollY > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (selected != null && (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE
                || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE)) {
            SkyImages.placed(style).remove(selected);
            selected = null;
            ModConfig.save();
            return true;
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    protected boolean drawContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float dt) {
        boolean pointer = false;
        List<String> files = SkyImages.files();

        // Liste der PNGs
        NovaGui.text(graphics, font, "Your PNGs (" + files.size() + ")", cx0 + 2, cy0, NovaGui.TEXT_MUTED);
        if (files.isEmpty()) {
            NovaGui.text(graphics, font, "None yet:", cx0 + 2, listTop() + 4, NovaGui.TEXT_DIM);
            NovaGui.text(graphics, font, "1. Open folder", cx0 + 2, listTop() + 18, NovaGui.TEXT_MUTED);
            NovaGui.text(graphics, font, "2. Copy PNGs in", cx0 + 2, listTop() + 30, NovaGui.TEXT_MUTED);
            NovaGui.text(graphics, font, "3. Reload", cx0 + 2, listTop() + 42, NovaGui.TEXT_MUTED);
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, files.size() - visibleRows())));
        for (int row = 0; row < visibleRows(); row++) {
            int index = scroll + row;
            if (index >= files.size()) {
                break;
            }
            String name = files.get(index);
            int y = listTop() + row * ROW;
            boolean chosen = name.equals(brush);
            boolean hover = isHovering(mouseX, mouseY, cx0, y, cx0 + LIST_W, y + ROW - 2);
            pointer |= hover;
            if (chosen || hover) {
                NovaShapes.rect(graphics, cx0, y - 1, cx0 + LIST_W, y + ROW - 2, 4, chosen ? NovaGui.alpha(accent, 0.28F) : NovaGui.RAISED);
            }
            NovaGui.text(graphics, font, NovaGui.ellipsize(font, name, LIST_W - 8), cx0 + 4, y + 2,
                    chosen || hover ? NovaGui.TEXT : NovaGui.TEXT_DIM);
        }
        boolean openHover = inOpenFolder(mouseX, mouseY);
        boolean reloadHover = inReload(mouseX, mouseY);
        pointer |= openHover || reloadHover;
        NovaControls.choice(graphics, font, cx0, bottomY(), cx0 + (LIST_W - 4) / 2, bottomY() + BUTTON_H, "Folder", openHover, false, accent);
        NovaControls.choice(graphics, font, cx0 + (LIST_W - 4) / 2 + 4, bottomY(), cx0 + LIST_W, bottomY() + BUTTON_H, "Reload", reloadHover, false, accent);

        // Himmel-Vorschau
        int x0 = viewX0();
        int y0 = viewY0();
        int x1 = viewX1();
        int y1 = viewY1();
        NovaShapes.rect(graphics, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 5, NovaGui.CARD);
        if (previewWorks) {
            if (preview == null) {
                preview = RenderCompat.target("Nova sky image editor", PREVIEW_W, PREVIEW_H, false);
            }
            // gespiegelt gezeichnet, weil Render-Ziele unten zuerst im Speicher liegen (siehe SkyVfxMenu.PREVIEW_FLIP)
            previewWorks = SkyVfx.renderPreview(style, preview, SkyVfx.time(), true, viewYaw, viewPitch, FOV);
        }
        if (previewWorks && preview != null) {
            Compat.blit(graphics, preview.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                    x0, y0, x1, y1, 0.0F, 1.0F, 0.0F, 1.0F);
        } else {
            NovaGui.centered(graphics, font, "Sky preview not available", (x0 + x1) / 2, (y0 + y1) / 2 - 4, NovaGui.TEXT_MUTED);
        }

        // Bilder in der Vorschau (in derselben Richtung und Groesse wie im Spiel)
        SkyImages.Placement hovered = inView(mouseX, mouseY) ? placementAt(mouseX, mouseY) : null;
        graphics.enableScissor(x0, y0, x1, y1);
        for (SkyImages.Placement placement : SkyImages.placed(style)) {
            float[] rect = screenRect(placement);
            DynamicTexture texture = SkyImages.texture(placement.file);
            if (rect == null || texture == null) {
                continue;
            }
            int rx0 = Math.round(rect[0]);
            int ry0 = Math.round(rect[1]);
            int rx1 = Math.max(rx0 + 1, Math.round(rect[2]));
            int ry1 = Math.max(ry0 + 1, Math.round(rect[3]));
            Compat.blit(graphics, texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                    rx0, ry0, rx1, ry1, 0.0F, 1.0F, 0.0F, 1.0F);
            if (placement == selected || placement == hovered) {
                NovaShapes.outline(graphics, rx0 - 1, ry0 - 1, rx1 + 1, ry1 + 1, 2, placement == selected ? accent : NovaGui.TEXT_MUTED);
            }
        }
        graphics.disableScissor();
        NovaShapes.outline(graphics, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 5, inView(mouseX, mouseY) ? accent : NovaGui.BORDER);
        pointer |= hovered != null;

        // Zeile unter der Vorschau
        int count = SkyImages.placed(style).size();
        String info;
        if (!SkyImages.error().isEmpty()) {
            info = SkyImages.error();
        } else if (brush == null) {
            info = "Put PNG files into .minecraft/nova_client/sky, then click Reload";
        } else if (selected != null) {
            info = selected.file + "  -  " + Math.round(selected.size) + " deg wide  -  Delete key removes it";
        } else {
            info = "Selected: " + brush + "  -  click into the sky to place it (" + count + "/" + SkyImages.MAX_PER_SKY + ")";
        }
        NovaGui.text(graphics, font, NovaGui.ellipsize(font, info, x1 - x0 - 4), x0, y1 + 6, NovaGui.TEXT_DIM);
        boolean clearHover = inClear(mouseX, mouseY) && count > 0;
        pointer |= clearHover;
        NovaControls.choice(graphics, font, x1 - 90, bottomY(), x1, bottomY() + BUTTON_H, "Remove all (" + count + ")", clearHover, false, NovaGui.DANGER);
        if (!SkyVfxMod.isEnabled()) {
            NovaGui.text(graphics, font, "Sky VFX is off - turn it on to see this in game", x0, bottomY() + 4,
                    NovaGui.TEXT_MUTED);
        } else if (SkyVfxMod.style != style) {
            NovaGui.text(graphics, font, "In game you use " + SkyVfxMod.style.title + " - pick " + style.title
                    + " to see these", x0, bottomY() + 4, NovaGui.TEXT_MUTED);
        }
        return pointer;
    }
}
