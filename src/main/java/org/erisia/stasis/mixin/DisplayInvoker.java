package org.erisia.stasis.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A display entity's transformation (GateRoom: the shattered frame's tilted shards). */
@Mixin(Display.class)
public interface DisplayInvoker {
    @Invoker("setTransformation")
    void stasis$setTransformation(Transformation t);
}
