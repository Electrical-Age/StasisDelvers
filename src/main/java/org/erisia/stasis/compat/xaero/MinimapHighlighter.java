package org.erisia.stasis.compat.xaero;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/** Xaero's Minimap: stopped land in grey (registered by mixin.xaero.MinimapSessionMixin). */
public class MinimapHighlighter extends ChunkHighlighter {
    public MinimapHighlighter() {
        super(true);
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
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dim, int x, int z, int width) {
        // the chunk's centre decides its tint, but the line is about where the player stands (AdventurAgent: it said
        // "Time stopped" in a small field whose chunk centre was outside)
        if (StoppedLand.stoppedAt(dim, x, z)) compiler.addLine(Component.literal("Time stopped"));
    }
}
