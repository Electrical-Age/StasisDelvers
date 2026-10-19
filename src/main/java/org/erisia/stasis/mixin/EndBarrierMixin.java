package org.erisia.stasis.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.erisia.stasis.EndBarrier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The End's wall (EndBarrier) stops a player as a world border does: Entity.collide's movement is clamped to the
 * cylinder (EndBarrier.clamp), on both sides, so the client predicts it and nothing snaps back (AdventurAgent: the
 * server-side put-back rubber-banded every 0.4 s). A circle can't be a VoxelShape (axis-aligned boxes make a staircase
 * whose inner corners catch a player sliding along it), so this clamps the result instead of adding a collider.
 * No event covers an entity's movement.
 */
@Mixin(Entity.class)
public abstract class EndBarrierMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void stasis$endBarrier(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Vec3 m = cir.getReturnValue();
        Vec3 c = EndBarrier.clamp((Entity) (Object) this, m);
        if (c != m) cir.setReturnValue(c);
    }
}
