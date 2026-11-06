package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import nova.nova_client.client.ItemPhysicsState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Item Physics: Kippwinkel und Drehung je Item vom Extrahieren bis zum Zeichnen mitnehmen. */
@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements ItemPhysicsState {
    @Unique
    private float nova_client$tilt = 90.0F;
    @Unique
    private float nova_client$yaw;

    @Override
    public float nova_client$tilt() {
        return nova_client$tilt;
    }

    @Override
    public float nova_client$yaw() {
        return nova_client$yaw;
    }

    @Override
    public void nova_client$set(float tilt, float yaw) {
        nova_client$tilt = tilt;
        nova_client$yaw = yaw;
    }
}
