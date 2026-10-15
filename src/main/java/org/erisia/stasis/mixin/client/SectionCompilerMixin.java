package org.erisia.stasis.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.erisia.stasis.client.FrozenSprites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Chunk meshing draws blocks in stopped land with still copies of their animated textures (FrozenSprites; Baughn:
 * campfires, magma, fire kept moving in the grey). Wraps the block model call in SectionCompiler.compile (one call:
 * the solid and the layered quad outputs both pass through it).
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @WrapOperation(
        method = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
    private void stasis$frozenTextures(ModelBlockRenderer renderer, BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level,
                                       BlockPos pos, BlockState state, BlockStateModel model, long seed, Operation<Void> original) {
        BlockQuadOutput out = FrozenSprites.begin(output, level, pos);
        try {
            original.call(renderer, out, x, y, z, level, pos, state, model, seed);
        } finally {
            FrozenSprites.end(out);
        }
    }
}
