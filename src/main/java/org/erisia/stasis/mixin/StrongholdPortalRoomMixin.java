package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.util.RandomSource;
import org.erisia.stasis.Gates;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla End portals are gone (Baughn, rift stage 1b): a stronghold portal room keeps its stairs, lava and spawner,
 * but its frame and portal are cleared, and a gate rift to the End stands there instead (Gates).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces$PortalRoom")
public abstract class StrongholdPortalRoomMixin {
    @Inject(method = "postProcess", at = @At("TAIL"))
    private void stasis$gateRift(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                                 BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos, CallbackInfo ci) {
        ServerLevel sl = level.getLevel();
        if (!StasisConfig.endPortalRifts || !Stasis.stops(sl)) return;
        StructurePieceInvoker piece = (StructurePieceInvoker) this;
        // the frame ring (x 3..7, z 8..12 at y 3) and the portal inside it
        for (int x = 3; x <= 7; x++)
            for (int z = 8; z <= 12; z++) {
                BlockPos p = piece.stasis$worldPos(x, 3, z).immutable();
                if (!chunkBB.isInside(p)) continue;
                var st = level.getBlockState(p);
                if (st.is(Blocks.END_PORTAL_FRAME) || st.is(Blocks.END_PORTAL)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        BlockPos centre = piece.stasis$worldPos(5, 4, 10).immutable();
        if (chunkBB.isInside(centre)) Gates.portalRoom(sl, centre);
    }
}
