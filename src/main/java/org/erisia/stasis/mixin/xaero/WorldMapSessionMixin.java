package org.erisia.stasis.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.erisia.stasis.compat.xaero.WorldMapHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import xaero.map.WorldMapSession;
import xaero.map.highlight.HighlighterRegistry;

/** Adds the stopped-land highlighter just before Xaero's World Map closes its highlighter registry (optional). */
@Pseudo
@Mixin(value = WorldMapSession.class, remap = false)
public abstract class WorldMapSessionMixin {
    @WrapOperation(method = "init", at = @At(value = "INVOKE", target = "Lxaero/map/highlight/HighlighterRegistry;end()V"), require = 0)
    private void stasis$register(HighlighterRegistry registry, Operation<Void> end) {
        registry.register(new WorldMapHighlighter());
        end.call(registry);
    }
}
