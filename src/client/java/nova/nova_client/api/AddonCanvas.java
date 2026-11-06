package nova.nova_client.api;

/** Zeichnen im HUD mit Novas Schrift und Formen (GUI-Pixel, Farben als ARGB). */
public interface AddonCanvas {
    int width();

    int height();

    int textWidth(String text);

    /** Text in Novas Schrift, linke obere Ecke (x, y). */
    void text(String text, int x, int y, int color);

    /** Rechteck mit runden Ecken. */
    void rect(int x0, int y0, int x1, int y1, int radius, int color);

    /** Dunkler Kasten mit feiner Kante wie die Nova-HUDs. */
    void hudBox(int x0, int y0, int x1, int y1);
}
