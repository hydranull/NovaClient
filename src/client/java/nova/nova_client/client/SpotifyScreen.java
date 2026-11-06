package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import static nova.nova_client.client.Utils.isHovering;

/**
 * Grosser Spotify-Player im Spiel: Cover mit herausgleitender Schallplatte, aufsteigende Noten,
 * Farbschleier aus dem Album, Visualizer, Fortschritt zum Spulen (klicken/ziehen) und alle Steuerknoepfe.
 * Tasten: Leertaste Play/Pause, Pfeile links/rechts zurueck/weiter, S Shuffle, R Repeat, ESC schliessen.
 */
public class SpotifyScreen extends Screen {
    private static final String[] NOTES = {"♪", "♫"};

    private final Screen parent;
    private final long openedAt = Util.getMillis();
    private long lastFrame = Util.getMillis();
    private final float[] hover = new float[6];
    private float barHover;

    private boolean dragging;
    private float dragProgress;

    // Layout
    private int x0, y0, x1, y1;
    private int coverX, coverY, coverSize;
    private int colX0, colX1;
    private int barY;
    private int controlsY;

    public SpotifyScreen(Screen parent) {
        super(Component.literal("Spotify"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Spotify.screenOpen = true;
        int w = Math.min(400, width - 24);
        int h = Math.min(196, height - 40);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2 - 6;
        x1 = x0 + w;
        y1 = y0 + h;
        coverSize = Math.max(64, Math.min(132, h - 50));
        coverX = x0 + 20;
        coverY = y0 + (h - 16 - coverSize) / 2;
        colX0 = coverX + coverSize + Math.round(coverSize * 0.30F) + 18;
        colX1 = x1 - 20;
        controlsY = y1 - 44;
        barY = controlsY - 28;
    }

    @Override
    public void removed() {
        Spotify.screenOpen = false;
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    // ---------------------------------------------------------------- Geometrie

    /** Knopf-Mitte: 0 Shuffle, 1 Zurueck, 2 Play, 3 Weiter, 4 Repeat. */
    private int buttonX(int index) {
        int mid = (colX0 + colX1) / 2;
        int step = Math.min(34, (colX1 - colX0) / 5);
        return mid + (index - 2) * step;
    }

    private int buttonRadius(int index) {
        return index == 2 ? 15 : 10;
    }

    private boolean buttonHovered(double mx, double my, int index) {
        int cx = buttonX(index);
        int r = buttonRadius(index);
        return isHovering((int) mx, (int) my, cx - r, controlsY - r, cx + r, controlsY + r);
    }

    private boolean barHovered(double mx, double my) {
        return Spotify.hasTimeline() && isHovering((int) mx, (int) my, colX0 - 2, barY - 5, colX1 + 2, barY + 7);
    }

    private float barValue(double mx) {
        return (float) Math.max(0.0D, Math.min(1.0D, (mx - colX0) / (double) (colX1 - colX0)));
    }

    private boolean playable() {
        return SpotifyWidget.statusLines() == null;
    }

    // ---------------------------------------------------------------- Eingabe

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && playable()) {
            if (barHovered(event.x(), event.y())) {
                dragging = true;
                dragProgress = barValue(event.x());
                return true;
            }
            for (int i = 0; i < 5; i++) {
                if (buttonHovered(event.x(), event.y(), i)) {
                    NovaGui.clickSound();
                    SpotifyWidget.press(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging) {
            dragProgress = barValue(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            Spotify.seek(Math.round(Spotify.duration() * dragProgress));
            NovaGui.clickSound();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (playable()) {
            if (event.key() == InputConstants.KEY_SPACE) {
                Spotify.playPause();
                return true;
            }
            if (event.isLeft() || event.isRight()) {
                if (event.isLeft()) {
                    Spotify.previous();
                } else {
                    Spotify.next();
                }
                return true;
            }
            if (event.key() == InputConstants.KEY_S) {
                Spotify.toggleShuffle();
                return true;
            }
            if (event.key() == InputConstants.KEY_R) {
                Spotify.cycleRepeat();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    // ---------------------------------------------------------------- Rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        SpotifyArt.update();
        if (SpotifyArt.hasCover()) {
            // Ganzer Bildschirm ruhig in den Farben des Albums getoent, stark abgedunkelt
            SpotifyArt.drawAmbient(graphics, 0, 0, width, height, 0);
            graphics.fill(0, 0, width, height, NovaGui.alpha(NovaGui.BG, 0.86F));
        } else {
            NovaGui.background(graphics, width, height);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        long now = Util.getMillis();
        float dt = Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        SpotifyWidget.frame();
        float elapsed = UiSettings.animations ? (now - openedAt) / 1000.0F : 100.0F;
        int accent = SpotifyArt.accent();

        NovaGui.pushIntro(graphics, elapsed);
        boolean pointer;
        String[] status = SpotifyWidget.statusLines();
        if (status != null) {
            pointer = drawStatus(graphics, status);
        } else {
            pointer = drawPlayer(graphics, mouseX, mouseY, dt, accent);
        }
        NovaGui.popIntro(graphics);

        // Tasten-Hinweise
        String hint = "SPACE play/pause   ← → skip   S shuffle   R repeat   ESC close";
        NovaGui.centered(graphics, font, hint, width / 2, height - 14, NovaGui.TEXT_MUTED);

        if (pointer) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    private boolean drawStatus(GuiGraphicsExtractor g, String[] status) {
        int w = 240;
        int h = 96;
        int cx = width / 2;
        int top = height / 2 - h / 2;
        SpotifyWidget.cardBackground(g, cx - w / 2, top, cx + w / 2, top + h, 10, SpotifyWidget.GREEN, 0.5F);
        float pulse = 1.0F + 0.06F * (float) Math.sin(NovaGui.seconds() * 3.0F);
        SpotifyWidget.logo(g, cx, top + 30, Math.round(16 * pulse));
        NovaGui.centered(g, font, status[1], cx, top + 56, NovaGui.TEXT);
        String sub = Spotify.SUPPORTED ? "Start the Spotify desktop app - Nova connects automatically"
                : "Spotify control uses Windows media controls";
        NovaGui.centered(g, font, NovaGui.ellipsize(font, sub, w - 16), cx, top + 72, NovaGui.TEXT_MUTED);
        return false;
    }

    private boolean drawPlayer(GuiGraphicsExtractor g, int mouseX, int mouseY, float dt, int accent) {
        float play = SpotifyWidget.playAnim();
        float beat = play * (0.5F + 0.5F * (float) Math.sin(NovaGui.seconds() * 6.5F));

        // Panel
        SpotifyWidget.cardBackground(g, x0, y0, x1, y1, 12, accent, 0.45F + 0.3F * beat);

        // Visualizer am unteren Rand
        drawVisualizer(g, accent, play);

        // Glow hinter dem Cover, dann Cover + Schallplatte
        SpotifyWidget.coverWithVinyl(g, coverX, coverY, coverSize, 10, accent);
        drawNotes(g, accent, play);

        // Rechte Spalte
        int colW = colX1 - colX0;
        String label = Spotify.playing ? "Now playing" : "Paused";
        NovaType.draw(g, font, NovaType.Face.CAPTION, label, colX0, y0 + 18, NovaGui.alpha(accent, 0.95F));
        SpotifyWidget.equalizer(g, colX1 - 22, y0 + 26, 5, 3, 1, 10, accent, NovaGui.BRAND_2);

        // Titel gross (eigener Schnitt, nie skaliert)
        NovaType.draw(g, font, NovaType.Face.TITLE, NovaType.ellipsize(font, NovaType.Face.TITLE, Spotify.title, colW), colX0,
                y0 + 36, NovaGui.TEXT);
        SpotifyWidget.marquee(g, font, Spotify.artist, colX0, y0 + 54, colW, NovaGui.TEXT_DIM);
        if (!Spotify.album.isEmpty()) {
            NovaGui.text(g, font, NovaGui.ellipsize(font, Spotify.album, colW), colX0, y0 + 66, NovaGui.TEXT_MUTED);
        }

        boolean pointer = false;

        // Fortschritt
        if (Spotify.hasTimeline()) {
            boolean hovered = barHovered(mouseX, mouseY) || dragging;
            pointer |= hovered;
            barHover = NovaGui.approach(barHover, hovered ? 1.0F : 0.0F, 14.0F, dt);
            float progress = dragging ? dragProgress : Spotify.progress();
            SpotifyWidget.progressBar(g, colX0, barY, colX1, 3, progress, accent, barHover * 2.0F);
            long shown = dragging ? Math.round(Spotify.duration() * dragProgress) : Spotify.position();
            String left = Spotify.time(shown);
            String right = Spotify.time(Spotify.duration());
            NovaGui.text(g, font, left, colX0, barY + 8, dragging ? NovaGui.TEXT : NovaGui.TEXT_MUTED);
            NovaGui.text(g, font, right, colX1 - NovaGui.width(font, right), barY + 8, NovaGui.TEXT_MUTED);
            if (hovered && !dragging) {
                // Vorschau der Zielzeit ueber dem Balken
                float t = barValue(mouseX);
                String tip = Spotify.time(Math.round(Spotify.duration() * t));
                int tx = colX0 + Math.round((colX1 - colX0) * t);
                int tw = NovaGui.width(font, tip) + 8;
                NovaShapes.rect(g, tx - tw / 2, barY - 17, tx + tw / 2, barY - 5, 4, NovaGui.alpha(0xF0101014, barHover));
                NovaGui.centered(g, font, tip, tx, barY - 15, NovaGui.alpha(NovaGui.TEXT, barHover));
            }
        }

        // Steuerknoepfe
        for (int i = 0; i < 5; i++) {
            int cx = buttonX(i);
            boolean hovered = buttonHovered(mouseX, mouseY, i);
            pointer |= hovered;
            hover[i] = NovaGui.approach(hover[i], hovered ? 1.0F : 0.0F, 16.0F, dt);
            float h = hover[i];
            if (i == 2) {
                int r = buttonRadius(2) + Math.round(h * 1.5F);
                NovaTextures.circle(g, cx, controlsY, r, NovaGui.mix(accent, 0xFFFFFFFF, 0.12F + 0.15F * h));
                NovaTextures.ring(g, cx, controlsY, r, NovaGui.alpha(0x50FFFFFF, 1.0F));
                SpotifyWidget.icon(g, Spotify.playing ? SpotifyWidget.ICON_PAUSE : SpotifyWidget.ICON_PLAY,
                        cx + (Spotify.playing ? 0 : 1), controlsY, 2, 0xFF0B0B0B);
                continue;
            }
            boolean active = (i == 0 && Spotify.shuffle) || (i == 4 && Spotify.repeat != 0);
            if (h > 0.01F) {
                NovaTextures.circle(g, cx, controlsY, buttonRadius(i), NovaGui.alpha(0xFFFFFFFF, 0.12F * h));
            }
            int color = active ? accent : NovaGui.mix(NovaGui.TEXT_DIM, NovaGui.TEXT, h);
            String[] shape = switch (i) {
                case 0 -> SpotifyWidget.ICON_SHUFFLE;
                case 1 -> SpotifyWidget.ICON_PREV;
                case 3 -> SpotifyWidget.ICON_NEXT;
                default -> SpotifyWidget.ICON_REPEAT;
            };
            int scale = i == 1 || i == 3 ? 2 : 1;
            SpotifyWidget.icon(g, shape, cx, controlsY, scale, color);
            if (active) {
                NovaTextures.circle(g, cx, controlsY + 9, 1, accent);
                if (i == 4 && Spotify.repeat == 1) {
                    NovaTextures.circle(g, cx + 6, controlsY - 5, 4, accent);
                    NovaGui.centered(g, font, "1", cx + 7, controlsY - 9, 0xFF0B0B0B);
                }
            }
        }
        return pointer;
    }

    /** Spektrum-Balken am unteren Panelrand, gespiegelt von der Mitte aus. */
    private void drawVisualizer(GuiGraphicsExtractor g, int accent, float play) {
        int bottom = y1 - 3;
        int maxH = 12;
        int bar = 3;
        int gap = 2;
        int count = (x1 - x0 - 24) / (bar + gap);
        int start = x0 + 12;
        float t = NovaGui.seconds();
        for (int i = 0; i < count; i++) {
            float center = Math.abs(i - count / 2.0F) / (count / 2.0F);
            float wave = Math.abs((float) Math.sin(t * 5.0F + i * 0.45F) * 0.55F + (float) Math.sin(t * 8.3F - i * 0.9F) * 0.45F);
            float level = (0.08F + 0.92F * wave * (1.0F - center * 0.6F)) * play;
            int h = Math.max(1, Math.round(maxH * level));
            int bx = start + i * (bar + gap);
            int color = NovaGui.mix(accent, NovaGui.BRAND_2, i / (float) count);
            g.fillGradient(bx, bottom - h, bx + bar, bottom, NovaGui.alpha(color, 0.55F), NovaGui.alpha(color, 0.05F));
        }
    }

    /** Musiknoten, die aus der Schallplatte aufsteigen und verblassen. */
    private void drawNotes(GuiGraphicsExtractor g, int accent, float play) {
        if (play <= 0.01F) {
            return;
        }
        float t = NovaGui.seconds();
        int baseX = coverX + coverSize + Math.round(coverSize * 0.15F);
        int baseY = coverY + coverSize / 2;
        for (int i = 0; i < 6; i++) {
            float phase = (t * 0.32F + i / 6.0F) % 1.0F;
            float x = baseX + (float) Math.sin(i * 2.3F + phase * 5.0F) * 10.0F + (i - 2.5F) * 5.0F;
            float y = baseY - phase * (coverSize * 0.75F);
            float alpha = (float) Math.sin(phase * Math.PI) * 0.75F * play;
            int color = NovaGui.alpha(i % 2 == 0 ? accent : NovaGui.BRAND_2, alpha);
            float scale = 0.8F + 0.5F * phase;
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(scale, scale);
            NovaGui.text(g, font, NOTES[i % 2], 0, 0, color);
            g.pose().popMatrix();
        }
    }
}
