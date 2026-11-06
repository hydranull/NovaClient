package nova.nova_client.mixin.client;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Hyper Tools: an die Ebene des Vanilla-Modells kommen, um dort das 3D-Werkzeug einzusetzen. */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] nova_client$layers();

    @Accessor("activeLayerCount")
    int nova_client$activeLayerCount();
}
