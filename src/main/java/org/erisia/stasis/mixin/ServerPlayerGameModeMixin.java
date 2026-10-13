package org.erisia.stasis.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.erisia.stasis.PlayerRates;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block breaking is checked against the server's own tick count (gameTicks). For a player in a slow field (whose client
 * mines at the field's rate) count only the ticks that rate runs in, so the server's destroy check agrees with the
 * client (tickzone's fix).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
    @Shadow
    private int gameTicks;

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "tick", at = @At("HEAD"))
    private void stasis$fieldTicks(CallbackInfo ci) {
        if (!PlayerRates.ticksNow(player)) gameTicks--;   // tick() adds it back: no net tick
    }
}
