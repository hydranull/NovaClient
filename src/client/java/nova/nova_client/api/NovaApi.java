package nova.nova_client.api;

/** Was ein Add-on bei Nova tun darf (stabile Schnittstelle, aendert sich nicht mit jeder Nova-Version). */
public interface NovaApi {
    /** Modul anmelden: erscheint im Nova-Menue in seiner Kategorie, mit Schalter, Taste und Speichern wie jedes andere. */
    void register(AddonModule module);

    /** Minecraft-Version, fuer die Nova gerade laeuft (z.B. "26.3"). */
    String minecraftVersion();

    /** Kleine Karte rechts oben (wie Nova-Benachrichtigungen). */
    void notify(String title, String text);
}
