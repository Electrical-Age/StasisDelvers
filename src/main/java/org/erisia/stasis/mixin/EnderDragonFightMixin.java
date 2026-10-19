package org.erisia.stasis.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.erisia.stasis.HardMode;
import org.erisia.stasis.StasisConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Spreading mode's dragon (spread-DESIGN.md step 4a; Baughn's brief, λ-Nero's End points): the dragon's death starts
 * hard mode (HardMode.enter), and no exit portal opens, no End gateway appears and no egg is placed: the island is about
 * to explode (step 5) and the way home is the flash's teleport, and with no gateways nothing leads past the island's
 * wall (EndBarrier). Nor can the dragon be summoned again: tryRespawn would build an exit portal, and hard mode is once only.
 * <p>
 * No NeoForge event covers this: LivingDeathEvent comes 10 s before setDragonKilled (the death animation) and can't stop
 * the portal, gateway or egg, which setDragonKilled places directly. Outside spreading mode vanilla runs untouched.
 */
@Mixin(EnderDragonFight.class)
public abstract class EnderDragonFightMixin {
    @Shadow private ServerBossEvent dragonEvent;
    @Shadow private ServerLevel level;
    @Shadow private boolean dragonKilled;
    @Shadow private boolean hasPreviouslyKilledDragon;
    @Shadow private @Nullable UUID dragonUUID;

    /** setDragonKilled in spreading mode: vanilla's bookkeeping (boss bar, the killed flags), then hard mode, no portal. */
    @Inject(method = "setDragonKilled", at = @At("HEAD"), cancellable = true)
    private void stasis$hardMode(EnderDragon dragon, CallbackInfo ci) {
        if (!StasisConfig.spreading()) return;
        ci.cancel();
        if (!dragon.getUUID().equals(dragonUUID)) return;   // (vanilla ignores other dragons too)
        dragonEvent.setProgress(0.0F);
        dragonEvent.setVisible(false);
        hasPreviouslyKilledDragon = true;
        dragonKilled = true;
        ((EnderDragonFight) (Object) this).setDirty();
        HardMode.enter(level.getServer(), "the ender dragon died");
    }

    /** No respawning the dragon in spreading mode (it would rebuild the exit portal; hard mode is once only). */
    @Inject(method = "tryRespawn", at = @At("HEAD"), cancellable = true)
    private void stasis$noRespawn(CallbackInfo ci) {
        if (StasisConfig.spreading()) ci.cancel();
    }
}
