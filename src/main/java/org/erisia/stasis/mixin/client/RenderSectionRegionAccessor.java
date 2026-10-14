package org.erisia.stasis.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level behind a chunk-mesh region, so a flame's own light in smooth lighting knows where it is (Flames). */
@Mixin(RenderSectionRegion.class)
public interface RenderSectionRegionAccessor {
    @Accessor("level")
    ClientLevel stasis$level();
}
