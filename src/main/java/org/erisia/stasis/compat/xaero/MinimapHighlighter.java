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

    /**
     * The line is about the block the player stands on: Xaero passes block coordinates here and chunk coordinates to
     * addChunkHighlightTooltips (it shifted them; we read the chunk numbers as blocks, so the line answered for a
     * spot far away: λ-Nero and AdventurAgent saw it disagree with the field).
     */
    @Override
    public void addBlockHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dim, int blockX, int blockZ, int width) {
        if (StoppedLand.stoppedAt(dim, blockX, blockZ)) compiler.addLine(Component.literal("Time stopped"));
    }

    @Override
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dim, int chunkX, int chunkZ, int width) {
        // (unused: addBlockHighlightTooltips answers for the player's block)
    }
}
