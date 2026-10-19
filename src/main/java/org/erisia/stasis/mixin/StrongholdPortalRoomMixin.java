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
 * Vanilla End portals are gone (Baughn, rift stage 1b): a stronghold portal room's frame and portal are cleared, a gate
 * rift to the End stands there instead (Gates), and the room is caught mid-collapse around it (GateRoom).
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces$PortalRoom")
public abstract class StrongholdPortalRoomMixin {
    @Inject(method = "postProcess", at = @At("TAIL"))
    private void stasis$gateRift(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                                 BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos, CallbackInfo ci) {
        ServerLevel sl = level.getLevel();
        // spreading mode: the portal room stays intact until hard mode (spread-DESIGN.md: the End's portal is how players
        // reach the dragon whose death starts hard mode; a gate room here would be permanent worldgen)
        if (!StasisConfig.endPortalRifts || StasisConfig.spreading() || !Stasis.stops(sl)) return;
        net.minecraft.world.level.levelgen.structure.StructurePiece piece = (net.minecraft.world.level.levelgen.structure.StructurePiece) (Object) this;
        // the frame ring (x 3..7, z 8..12 at y 3) and the portal inside it
        for (int x = 3; x <= 7; x++)
            for (int z = 8; z <= 12; z++) {
                BlockPos p = piece.getWorldPos(x, 3, z).immutable();
                if (!chunkBB.isInside(p)) continue;
                var st = level.getBlockState(p);
                if (st.is(Blocks.END_PORTAL_FRAME) || st.is(Blocks.END_PORTAL)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        // caught mid-collapse (GateRoom); no new-chunk grace here, or the lava and debris would settle first
        org.erisia.stasis.GateRoom.shatter(level, piece, chunkBB);
        org.erisia.stasis.NewChunkGrace.exempt(sl, chunkPos.pack());
        // recorded from whichever of the room's chunks comes first (Gates dedupes)
        Gates.portalRoom(sl, piece.getWorldPos(5, 4, 10).immutable());
    }
}
