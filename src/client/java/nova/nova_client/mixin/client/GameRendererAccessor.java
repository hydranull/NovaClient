package nova.nova_client.mixin.client;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
//? if <26.1 {
/*import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.gen.Invoker;
*///?}

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("resourcePool")
    CrossFrameResourcePool nova_client$resourcePool();

    //? if <26.1 {
    /*/^* Sichtfeld des aktuellen Bildes (nur 1.21.11; ab 26.1 liefert die Kamera die fertige Matrix). ^/
    @Invoker("getFov")
    float nova_client$getFov(Camera camera, float partialTick, boolean useFovSetting);
    *///?}
}
