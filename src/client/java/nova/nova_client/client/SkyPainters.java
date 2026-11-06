package nova.nova_client.client;

/**
 * Lichtadern wie beim Himmel Nova Tide, auf der CPU fuer das Nova Cape (der Himmel selbst laeuft im Shader,
 * shaders/core/nova_sky.fsh - dort steht dieselbe Kaustik).
 */
final class SkyPainters {
    private static final float TAU = 6.2831855F;

    private SkyPainters() {
    }

    /** Lichtadern wie bei Nova Tide an einer Stelle (fuer das Nova Cape), ungefaehr 0..1.5. */
    static float tideLight(float x, float y, float time) {
        return caustic(x - 250.0F, y - 250.0F, time * 0.35F + 23.0F, 5);
    }

    /** Sinus ueber Tabelle, fuer andere Painter im Paket. */
    static float fastSin(float value) {
        return fsin(value);
    }

    /**
     * Wasser-Kaustik (nach "Tileable Water Caustic" von Dave Hoskins): helle, verschlungene Lichtadern.
     * Periodisch mit 2 Pi in px.
     */
    private static float caustic(float px, float py, float time, int iterations) {
        float ix = px;
        float iy = py;
        float c = 1.0F;
        for (int n = 0; n < iterations; n++) {
            float t = time * (1.0F - 3.5F / (n + 1));
            float nx = px + fcos(t - ix) + fsin(t + iy);
            float ny = py + fsin(t - iy) + fcos(t + ix);
            ix = nx;
            iy = ny;
            float a = 1.25F / Math.max(Math.abs(fsin(ix + t)), 1.0E-4F);
            float b = 1.25F / Math.max(Math.abs(fcos(iy + t)), 1.0E-4F);
            c += 1.0F / (float) Math.sqrt(a * a + b * b);
        }
        c /= iterations;
        float value = Math.abs(1.17F - (float) Math.pow(c, 1.4F));
        float v2 = value * value;
        float v4 = v2 * v2;
        return v4 * v4;
    }

    // Schneller Sinus ueber Tabelle (die Kaustik braucht ~40 Sinus je Pixel)
    private static final int SIN_SIZE = 4096;
    private static final float[] SIN_TABLE = new float[SIN_SIZE + 1];

    static {
        for (int i = 0; i <= SIN_SIZE; i++) {
            SIN_TABLE[i] = (float) Math.sin(i * TAU / SIN_SIZE);
        }
    }

    private static float fsin(float value) {
        float f = value * (SIN_SIZE / TAU);
        int i = (int) Math.floor(f);
        float k = f - i;
        int a = i & (SIN_SIZE - 1);
        return SIN_TABLE[a] + (SIN_TABLE[a + 1] - SIN_TABLE[a]) * k;
    }

    private static float fcos(float value) {
        return fsin(value + TAU * 0.25F);
    }
}
