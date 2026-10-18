package org.erisia.stasis.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mobs avoid stopped land (Baughn: they walk into a field's edge and get caught like flies in amber): from running land,
 * a step into stopped land counts as dangerous (a high path cost, not forbidden: a mob that must still can).
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class WalkNodeEvaluatorMixin {
    @Inject(method = "getPathTypeStatic(Lnet/minecraft/world/level/pathfinder/PathfindingContext;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Lnet/minecraft/world/level/pathfinder/PathType;",
        at = @At("RETURN"), cancellable = true)
    private static void stasis$avoidStopped(PathfindingContext context, BlockPos.MutableBlockPos pos, CallbackInfoReturnable<PathType> cir) {
        PathType t = cir.getReturnValue();
        if (t != PathType.WALKABLE && t != PathType.OPEN) return;
        if (!(context instanceof org.erisia.stasis.StasisPathContext sc) || !(sc.stasis$level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        if (!org.erisia.stasis.Stasis.stops(level) || !org.erisia.stasis.Stasis.isRunning(level, context.mobPosition())) return;
        if (!org.erisia.stasis.Stasis.isRunning(level, pos)) cir.setReturnValue(PathType.DAMAGING_IN_NEIGHBOR);
    }
}
