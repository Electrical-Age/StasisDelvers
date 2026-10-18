package org.erisia.stasis;

import net.minecraft.world.level.Level;

/** The pathing mob's level, added to PathfindingContext (mixin). */
public interface StasisPathContext {
    Level stasis$level();
}
