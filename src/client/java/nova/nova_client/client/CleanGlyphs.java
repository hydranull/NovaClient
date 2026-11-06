package nova.nova_client.client;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.UnbakedGlyph;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.glyphs.EmptyGlyph;

/**
 * Glatte Schriften (TTF) sauber fett und mit Schatten: Minecraft faelscht "fett", indem es jedes Zeichen ein zweites Mal
 * 1 GUI-Einheit weiter rechts zeichnet, und setzt den Schatten 1 GUI-Einheit versetzt - bei der Pixel-Schrift ist das
 * genau ein Schrift-Pixel, bei einer glatten Schrift aber 2-4 Bildschirm-Pixel: Striche verschmieren, kleine Zahlen
 * werden zu Klecksen. Hier bekommen TTF-Zeichen einen Fett-Versatz von genau einem Bildschirm-Pixel und einen
 * halb so grossen Schatten (mindestens ein Pixel). Breite und Darstellung bleiben dabei zueinander passend.
 * <p>
 * Ausserdem liegt jedes Zeichen auf ganzen Raster-Pixeln: Minecraft setzt die Oberkante auf 7 GUI-Einheiten minus
 * Bearing - bei krummen Dichten (z.B. 1,5) ein halber Pixel daneben, das Zeichen wird dann zwischen zwei
 * Pixelreihen abgetastet und wirkt abgehackt. Zusammen mit dem auf ganze Pixel gesetzten Text-Anfang
 * ({@link NovaFonts#snapX}) trifft so jedes Raster-Pixel genau ein Bildschirm-Pixel.
 */
public final class CleanGlyphs {
    private CleanGlyphs() {
    }

    /** Zeichen aus einer TTF-Schrift mit oversample Raster-Pixeln je GUI-Einheit. */
    public static UnbakedGlyph wrap(UnbakedGlyph glyph, float oversample) {
        // Leerzeichen bringen ihr eigenes fertiges Zeichen mit - unveraendert lassen, sonst passen Breite und Bild nicht
        if (glyph == null || glyph instanceof EmptyGlyph || glyph instanceof Clean || oversample <= 0.0F) {
            return glyph;
        }
        float pixel = 1.0F / oversample;
        float shadow = Math.max(1, Math.round(oversample * 0.5F)) * pixel;
        return new Clean(glyph, new Info(glyph.info().getAdvance(), pixel, shadow));
    }

    private record Info(float advance, float bold, float shadow) implements GlyphInfo {
        @Override
        public float getAdvance() {
            return advance;
        }

        @Override
        public float getBoldOffset() {
            return bold;
        }

        @Override
        public float getShadowOffset() {
            return shadow;
        }
    }

    private record Clean(UnbakedGlyph glyph, GlyphInfo info) implements UnbakedGlyph {
        @Override
        public BakedGlyph bake(UnbakedGlyph.Stitcher stitcher) {
            // Beim Einbauen ins Atlas-Bild unsere Werte mitgeben, damit das fertige Zeichen sie auch benutzt
            return glyph.bake(new UnbakedGlyph.Stitcher() {
                @Override
                public BakedGlyph stitch(GlyphInfo ignored, GlyphBitmap bitmap) {
                    return stitcher.stitch(info, new Aligned(bitmap));
                }

                @Override
                public BakedGlyph getMissing() {
                    return stitcher.getMissing();
                }
            });
        }
    }

    /** Zeichen-Bild mit Kanten auf ganzen Raster-Pixeln (links/oben gerundet, Breite/Hoehe unveraendert). */
    private record Aligned(GlyphBitmap bitmap) implements GlyphBitmap {
        private float snap(float value) {
            float oversample = bitmap.getOversample();
            return oversample > 0.0F ? Math.round(value * oversample) / oversample : value;
        }

        @Override
        public int getPixelWidth() {
            return bitmap.getPixelWidth();
        }

        @Override
        public int getPixelHeight() {
            return bitmap.getPixelHeight();
        }

        @Override
        public void upload(int x, int y, com.mojang.renderpearl.api.textures.GpuTexture texture) {
            bitmap.upload(x, y, texture);
        }

        @Override
        public boolean isColored() {
            return bitmap.isColored();
        }

        @Override
        public float getOversample() {
            return bitmap.getOversample();
        }

        @Override
        public float getBearingLeft() {
            return bitmap.getBearingLeft();
        }

        @Override
        public float getBearingTop() {
            return bitmap.getBearingTop();
        }

        @Override
        public float getLeft() {
            return snap(bitmap.getLeft());
        }

        @Override
        public float getRight() {
            return getLeft() + getPixelWidth() / getOversample();
        }

        @Override
        public float getTop() {
            return snap(bitmap.getTop());
        }

        @Override
        public float getBottom() {
            return getTop() + getPixelHeight() / getOversample();
        }
    }
}
