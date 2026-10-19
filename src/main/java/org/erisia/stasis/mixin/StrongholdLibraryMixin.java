package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.erisia.stasis.StasisConfig;
import org.erisia.stasis.StasisRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spreading mode (spread-DESIGN.md step 3; Baughn 2026-10-19): there is no spawn cottage, so the surveyor's desk (maps to
 * ruined cottages) stands in each stronghold library instead. The library is a code-built piece (no template, so no
 * structure processor applies, and NeoForge has no event per structure piece): a TAIL hook on its postProcess.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces$Library")
public abstract class StrongholdLibraryMixin {
    /** The desk's spot in the piece: on the floor against the back wall, facing the entry door (local x 4..6, z 0), in
     *  the aisle behind the last shelf row (z 11), clear of the tall library's ladder (x 10). One fixed spot, so the
     *  desk can't depend on which of the library's chunks generates first: there is exactly one per library. */
    private static final int DESK_X = 6, DESK_Y = 1, DESK_Z = 13;

    @Inject(method = "postProcess", at = @At("TAIL"))
    private void stasis$surveyorsDesk(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                                      BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos, CallbackInfo ci) {
        // (in spreading mode the overworld stops(); the mod switched off or a dimension left out: no desk)
        if (!StasisConfig.spreading() || !org.erisia.stasis.Stasis.stops(level.getLevel())) return;
        StructurePiece piece = (StructurePiece) (Object) this;
        BlockPos p = piece.getWorldPos(DESK_X, DESK_Y, DESK_Z).immutable();
        // only from the chunk it's in (worldgen writes stay in the chunk being generated)
        if (!chunkBB.isInside(p)) return;
        var st = level.getBlockState(p);
        if (!st.isAir() && !st.is(Blocks.COBWEB)) return;   // (something else is there: leave it)
        // in a cave the library has holes in its floor (generateBox skips air): give the desk a floor (same column)
        BlockPos below = p.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP))
            level.setBlock(below, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        level.setBlock(p, StasisRegistry.MAP_DESK_BLOCK.get().defaultBlockState(), 2);
    }
}
