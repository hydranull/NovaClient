package nova.nova_client.client;

/**
 * Auswaehlbare Himmel fuer Sky VFX. Gezeichnet werden sie im Shader (shaders/core/nova_sky.fsh, Reihenfolge = Nummer
 * im Shader), jeweils rundherum - auch unter dem Horizont.
 * <p>
 * horizon: mittlere Farbe am Horizont (RGB) fuer den Nebel in der Ferne. previewYaw/previewPitch: Blickrichtung der
 * Vorschau im Menue (Grad; Yaw 0 = +Z, 90 = +X; Pitch nach oben positiv).
 */
public enum SkyStyle {
    BLACK_HOLE("Black Hole", "Ray-traced black hole: lensed accretion disk, photon ring, bent starlight", 0x05060E, 180.0F, 24.0F),
    RINGED_GIANT("Ringed Giant", "A ringed gas giant with moons and storms, far out in deep space",
            0x0B0D18, 41.0F, 17.0F),
    DUSK("Dusk", "Calm evening sky: soft gradient, warm horizon, a few quiet stars", 0x725C6F, 292.0F, 10.0F),
    MOONLIT("Moonlit", "Quiet night: a clear moon with a soft halo and calm stars", 0x242E45, 133.0F, 24.0F);

    public final String title;
    public final String description;
    public final int horizon;
    public final float previewYaw;
    public final float previewPitch;

    SkyStyle(String title, String description, int horizon, float previewYaw, float previewPitch) {
        this.title = title;
        this.description = description;
        this.horizon = horizon;
        this.previewYaw = previewYaw;
        this.previewPitch = previewPitch;
    }

    public static SkyStyle byName(String name, SkyStyle fallback) {
        for (SkyStyle style : values()) {
            if (style.name().equalsIgnoreCase(name)) {
                return style;
            }
        }
        return fallback;
    }
}
