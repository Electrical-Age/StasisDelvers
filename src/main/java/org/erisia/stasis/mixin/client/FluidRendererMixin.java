package org.erisia.stasis.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.erisia.stasis.client.FrozenSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lava and water in stopped land are drawn with still copies of their animated textures (FrozenSprites; Baughn). The
 * still, flowing and overlay sprites of FluidRenderer.tesselate (five Material.Baked.sprite() calls) are swapped;
 * getU/getV are relative to the sprite, so the copy's own rect is used as is.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    private static final String TESSELATE = "tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V";

    @Inject(method = TESSELATE, at = @At("HEAD"))
    private void stasis$frozenFluid(BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState blockState, FluidState fluidState,
                                    CallbackInfo ci, @Share("stasisFrozen") LocalBooleanRef frozen,
                                    @Share("stasisLines") LocalRef<TextureAtlasSprite> lines) {
        frozen.set(FrozenSprites.stopped(level, pos));
        // water lines (WaterLines): the still top of frozen water near a rift core
        lines.set(frozen.get() && fluidState.is(net.minecraft.tags.FluidTags.WATER) ? org.erisia.stasis.client.WaterLines.sprite(pos) : null);
    }

    @WrapOperation(method = TESSELATE, at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/resources/model/sprite/Material$Baked;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite stasis$waterLines(Material.Baked material, Operation<TextureAtlasSprite> original,
                                                @Share("stasisLines") LocalRef<TextureAtlasSprite> lines) {
        TextureAtlasSprite l = lines.get();
        return l != null ? l : original.call(material);
    }

    @WrapOperation(method = TESSELATE, 
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/model/sprite/Material$Baked;sprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite stasis$frozenFluidSprite(Material.Baked material, Operation<TextureAtlasSprite> original,
                                                       @Share("stasisFrozen") LocalBooleanRef frozen) {
        TextureAtlasSprite sprite = original.call(material);
        return frozen.get() ? FrozenSprites.frozenOr(sprite) : sprite;
    }
}
