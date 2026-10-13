package org.erisia.stasis.compat.xaero;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

import java.util.List;

/** Xaero's World Map: stopped land in grey (registered by mixin.xaero.WorldMapSessionMixin). */
public class WorldMapHighlighter extends ChunkHighlighter {
    public WorldMapHighlighter() {
        super(true);
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dim, int regionX, int regionZ) {
        return StoppedLand.anyStopped(dim) ? StoppedLand.fieldsHash() * 31 + 1 : 0;
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dim, int regionX, int regionZ) {
        return StoppedLand.anyStopped(dim);
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return StoppedLand.stopped(dim, chunkX, chunkZ);
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        if (!chunkIsHighlit(dim, chunkX, chunkZ)) return null;
        for (int i = 0; i < 5; i++) resultStore[i] = StoppedLand.GREY;
        return resultStore;
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return Component.literal("Time stopped");
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dim, int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dim, int x, int z, int width) {
    }
}
