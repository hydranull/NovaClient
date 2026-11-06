package nova.nova_client.client;

/** Zusatzfelder am Render-Zustand eines Item-Entities fuer Item Physics (siehe ItemEntityRenderStateMixin). */
public interface ItemPhysicsState {
    /** Kippwinkel um die X-Achse in Grad (90 = flach liegend). */
    float nova_client$tilt();

    /** Drehung um die Hochachse in Grad (fest je Item). */
    float nova_client$yaw();

    void nova_client$set(float tilt, float yaw);
}
