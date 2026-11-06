package nova.nova_client.api;

/**
 * Einstieg eines Nova-Add-ons. Ein Add-on ist eine normale Fabric-Mod, die in ihrer fabric.mod.json den Einstiegspunkt
 * {@code "nova": ["dein.paket.DeinAddon"]} nennt und von {@code nova_client} abhaengt. Nova ruft
 * {@link #onInitialize(NovaApi)} beim Start auf - dort meldet das Add-on seine Module an.
 * <p>
 * Veroeffentlicht wird ueber den Nova Hub (nur vom Nova-Team gepruefte und signierte Add-ons).
 */
public interface NovaAddon {
    void onInitialize(NovaApi api);
}
