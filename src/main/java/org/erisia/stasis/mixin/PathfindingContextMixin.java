package org.erisia.stasis.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remembers the pathing mob's level (WalkNodeEvaluatorMixin asks it where time is stopped). */
@Mixin(PathfindingContext.class)
public abstract class PathfindingContextMixin implements org.erisia.stasis.StasisPathContext {
    @Unique
    private Level stasis$level;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void stasis$remember(CollisionGetter level, Mob mob, CallbackInfo ci) {
        stasis$level = mob.level();
    }

    @Override
    public Level stasis$level() {
        return stasis$level;
    }
}
