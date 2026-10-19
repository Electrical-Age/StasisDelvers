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
 * rift to the End stands there instead (Gates), and the room is caught mid-collapse around it (GateRoom). In spreading
 * mode (Baughn 2026-10-19) the room stays a working End portal room until hard mode, and is recorded (PendingGates);
 * HardRifts rewrites it at hard mode. From hard mode on, new rooms generate as in stopped mode.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces$PortalRoom")
public abstract class StrongholdPortalRoomMixin {
    @Inject(method = "postProcess", at = @At("TAIL"))
    private void stasis$gateRift(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                                 BoundingBox chunkBB, ChunkPos chunkPos, BlockPos referencePos, CallbackInfo ci) {
        ServerLevel sl = level.getLevel();
        if (!StasisConfig.endPortalRifts || !Stasis.stops(sl)) return;   // (in spreading mode the overworld stops() too)
        net.minecraft.world.level.levelgen.structure.StructurePiece piece = (net.minecraft.world.level.levelgen.structure.StructurePiece) (Object) this;
        // spreading mode: the portal room stays intact until hard mode (spread-DESIGN.md: the End's portal is how players
        // reach the dragon whose death starts hard mode; a gate room here would be permanent worldgen). It is recorded
        // (whole piece and gate centre; PendingGates dedupes) so hard mode can turn it into a gate room then.
        // After hard mode began (HardRifts rewrites the rooms recorded before), a new room generates as in stopped mode;
        // a chunk of a room recorded before the instant (a stronghold straddling it) is noted, so it isn't shattered twice.
        boolean spreading = StasisConfig.spreading();
        if (spreading && !org.erisia.stasis.Spread.hardNow()) {
            org.erisia.stasis.PendingGates.portalRoom(sl, piece.getBoundingBox(), piece.getWorldPos(5, 4, 10).immutable(), piece.getOrientation());
            return;
        }
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
        if (spreading) org.erisia.stasis.PendingGates.shatteredAtWorldgen(sl, piece.getWorldPos(5, 4, 10).immutable(), chunkPos.pack());
    }
}
