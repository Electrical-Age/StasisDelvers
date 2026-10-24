package org.erisia.stasis.client;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The post chain's DhDepth input (post_effect/stasis.json): Distant Horizons' depth texture when DH is loaded and has
 * one (compat/dh/DhDepth), else a 1 x 1 stand-in (the shader then ignores it: slot 1700 is 0). Asked every frame, so
 * DH's texture can be recreated (a resize) without the chain knowing.
 */
final class DhDepthTexture extends DynamicTexture {
    static final boolean DH = net.neoforged.fml.ModList.get().isLoaded("distanthorizons");

    DhDepthTexture() {
        super(() -> "stasisdelvers dh depth", 1, 1, true);
    }

    @Override
    public GpuTextureView getTextureView() {
        if (DH) {
            GpuTextureView v = org.erisia.stasis.compat.dh.DhDepth.depth();
            if (v != null) return v;
        }
        return super.getTextureView();
    }
}
