package nova.nova_client.client.toolskins;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import nova.nova_client.client.mods.ToolSkinsMod;
import nova.nova_client.mixin.client.ItemStackRenderStateAccessor;

/**
 * Hyper Tools: Vanilla-Modell zuerst fuellen lassen (Halte-Position, Partikel, Verzauberung), dann in seiner Ebene
 * das flache Sprite durch das Laserschwert ersetzen.
 */
final class ToolSkinItemModel implements ItemModel {
    private final ItemModel original;
    private final String itemId;

    ToolSkinItemModel(ItemModel original, String itemId) {
        this.original = original;
        this.itemId = itemId;
    }

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context,
                       ClientLevel level, ItemOwner owner, int seed) {
        original.update(state, stack, resolver, context, level, owner, seed);
        if (!ToolSkinsMod.shows(context, owner)) {
            return;
        }
        ToolSkinMesh mesh = ToolSkinAssets.mesh(itemId);
        if (mesh == null) {
            return;
        }
        ItemStackRenderStateAccessor access = (ItemStackRenderStateAccessor) state;
        int count = access.nova_client$activeLayerCount();
        if (count <= 0) {
            return;
        }
        ItemStackRenderState.LayerRenderState layer = access.nova_client$layers()[count - 1];
        ToolSkinRenderer.Arg arg = ToolSkinRenderer.arg(mesh, context);
        layer.setupSpecialModel(ToolSkinRenderer.INSTANCE, arg);
        layer.setExtents(ToolSkinRenderer.supplier(arg.extents()));
        state.appendModelIdentityElement(arg);
    }
}
