package nova.nova_client.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("rightClickDelay")
    int nova_client$getRightClickDelay();

    @Accessor("rightClickDelay")
    void nova_client$setRightClickDelay(int delay);

    /** Linksklick wie vom Spieler (bei Speeren: Stoss mit Lunge). */
    @Invoker("startAttack")
    boolean nova_client$startAttack();
}
