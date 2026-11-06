package nova.nova_client.api;

/**
 * Ein Modul eines Add-ons. Nova kuemmert sich um Schalter, Taste, Speichern und das Menue; das Add-on fuellt nur die
 * Methoden. Alle Aufrufe kommen auf dem Minecraft-Hauptthread. Wirft eine Methode einen Fehler, schaltet Nova das Modul
 * aus (ein kaputtes Add-on darf das Spiel nie stoeren).
 */
public abstract class AddonModule {
    private final String name;
    private final String description;
    private final String category;
    private boolean enabled;

    /**
     * @param category COMBAT, MISC, DONUT, BASEFINDING, RENDER, VISUALS oder NOVA (unbekannt = MISC)
     */
    protected AddonModule(String name, String description, String category) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.category = category == null ? "MISC" : category;
    }

    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public final String category() {
        return category;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    /** Von Nova beim Umschalten. */
    public final void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            if (enabled) {
                onEnable();
            } else {
                onDisable();
            }
        }
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

    /** Jeden Spiel-Tick (20 pro Sekunde), solange das Modul an ist und man in einer Welt ist. */
    public void onTick() {
    }

    /** Jedes Bild im HUD, solange das Modul an ist. */
    public void onHud(AddonCanvas canvas) {
    }
}
