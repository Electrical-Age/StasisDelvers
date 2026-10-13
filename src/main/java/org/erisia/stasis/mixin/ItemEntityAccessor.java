package org.erisia.stasis.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The raw pickup delay (32767 = never), for the stopped-land pickup override (StasisEvents.pickup). */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("pickupDelay")
    int stasis$pickupDelay();
}
