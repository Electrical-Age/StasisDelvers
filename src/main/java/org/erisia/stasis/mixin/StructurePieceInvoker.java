package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A structure piece's local-to-world position (for the stronghold portal room's gate rift). */
@Mixin(StructurePiece.class)
public interface StructurePieceInvoker {
    @Invoker("getWorldPos")
    BlockPos.MutableBlockPos stasis$worldPos(int x, int y, int z);
}
