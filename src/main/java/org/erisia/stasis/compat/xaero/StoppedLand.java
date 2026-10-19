package org.erisia.stasis.compat.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Stasis;

/**
 * Shared logic of the Xaero map highlighters (λ-Nero): which chunks are stopped land, and their tint. The land's rate
 * (Stasis.rateAtLand): drifting deeps passing over running spread land are weather, not land, and would flicker.
 */
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
        return Stasis.rateAtLand(l, centre.getX(), centre.getY(), centre.getZ()) <= 0;
    }

    /** At a block column (at the player's height, else sea level). */
    static boolean stoppedAt(ResourceKey<Level> dim, int x, int z) {
        Level l = level(dim);
        if (l == null) return false;
        var p = Minecraft.getInstance().player;
        int y = p != null ? p.getBlockY() : l.getSeaLevel();
        return Stasis.rateAtLand(l, x, y, z) <= 0;
    }

    static boolean anyStopped(ResourceKey<Level> dim) {
        return level(dim) != null;
    }

    /**
     * Changes when the fields change (the world map caches region highlights by this), and in spreading mode when P has
     * moved by a two-thousandth of the map (~1.4 min at 48 h: P itself moves every second) or rank tiles came or went.
     */
    static int fieldsHash() {
        var s = org.erisia.stasis.Spread.client;
        int spread = s.map() == null ? 0 : 31 * (s.p() / Math.max(1, s.count() / 2000)) + org.erisia.stasis.Spread.clientTiles.generation();
        return ClientFields.fields().hashCode() * 31 + spread;
    }
}
