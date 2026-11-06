package nova.nova_client.client;

/** Time Changer (26.3): von ClientClockInstanceMixin eingebaut - markiert die Uhr der Oberwelt. */
public interface NovaClock {
    void nova_client$setOverworld(boolean overworld);
}
