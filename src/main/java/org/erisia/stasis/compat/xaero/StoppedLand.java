package org.erisia.stasis.compat.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Stasis;

/** Shared logic of the Xaero map highlighters (λ-Nero): which chunks are stopped land, and their tint. */
final class StoppedLand {
    private StoppedLand() {
    }

    /** Translucent grey (ARGB), as Xaero's highlighters take it. */
    static final int GREY = 0x70707070;

    static Level level(ResourceKey<Level> dim) {
        Level l = Minecraft.getInstance().level;
        return l != null && l.dimension().equals(dim) && Stasis.stops(l) ? l : null;
    }

    /** A chunk counts as stopped land when its centre is (field edges show in 16-block steps). */
    static boolean stopped(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        Level l = level(dim);
        if (l == null) return false;
        BlockPos centre = new BlockPos((chunkX << 4) + 8, l.getSeaLevel(), (chunkZ << 4) + 8);
        return !Stasis.isRunning(l, centre.getX(), centre.getY(), centre.getZ());
    }

    static boolean anyStopped(ResourceKey<Level> dim) {
        return level(dim) != null;
    }

    /** Changes when the fields change (the world map caches region highlights by this). */
    static int fieldsHash() {
        return ClientFields.fields().hashCode();
    }
}
