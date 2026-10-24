package org.erisia.stasis.client;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The post chain's DhDepth and DhColor inputs (post_effect/stasis.json): Distant Horizons' depth or colour texture when DH is loaded and has
 * one (compat/dh/DhDepth), else a 1 x 1 stand-in (the shader then ignores it: slot 1700 is 0). Asked every frame, so
 * DH's texture can be recreated (a resize) without the chain knowing.
 */
final class DhDepthTexture extends DynamicTexture {
    static final boolean DH = net.neoforged.fml.ModList.get().isLoaded("distanthorizons");

    final boolean colour;

    /** colour: DH's colour texture (its alpha says where DH drew), else its depth. */
    DhDepthTexture(boolean colour) {
        super(() -> colour ? "stasisdelvers dh colour" : "stasisdelvers dh depth", 1, 1, true);
        this.colour = colour;
    }

    @Override
    public GpuTextureView getTextureView() {
        if (DH) {
            GpuTextureView v = colour ? org.erisia.stasis.compat.dh.DhDepth.color() : org.erisia.stasis.compat.dh.DhDepth.depth();
            if (v != null) return v;
        }
        return super.getTextureView();
    }
}
