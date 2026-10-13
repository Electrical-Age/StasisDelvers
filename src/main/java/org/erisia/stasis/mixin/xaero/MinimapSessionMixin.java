package org.erisia.stasis.mixin.xaero;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.erisia.stasis.compat.xaero.MinimapHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import xaero.common.minimap.highlight.HighlighterRegistry;
import xaero.hud.minimap.module.MinimapSession;

/** Adds the stopped-land highlighter just before Xaero's Minimap closes its highlighter registry. Optional: the mixin
 *  config is not required, so a changed Xaero just loses the overlay. */
@Pseudo
@Mixin(value = MinimapSession.class, remap = false)
public abstract class MinimapSessionMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/highlight/HighlighterRegistry;end()V"), require = 0)
    private void stasis$register(HighlighterRegistry registry, Operation<Void> end) {
        registry.register(new MinimapHighlighter());
        end.call(registry);
    }
}
