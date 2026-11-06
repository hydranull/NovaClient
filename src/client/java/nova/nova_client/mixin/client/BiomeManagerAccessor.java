package nova.nova_client.mixin.client;

import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Seed Finder: SHA-256-Hash des Welt-Seeds, den der Server beim Betreten schickt (fuer die Biom-Uebergaenge). */
@Mixin(BiomeManager.class)
public interface BiomeManagerAccessor {
    @Accessor("biomeZoomSeed")
    long nova_client$biomeZoomSeed();
}
