package nova.nova_client.client.social;

/** Einstellungen von Nova Social (gespeichert ueber das Modul Nova Social). */
public final class SocialSettings {
    /** Freunde sehen, auf welchem Server man spielt (und koennen per Klick nachkommen). */
    public static boolean showServer = true;
    /** Kleine Karte, wenn ein Freund online kommt. */
    public static boolean notifyOnline = true;

    private SocialSettings() {
    }
}
