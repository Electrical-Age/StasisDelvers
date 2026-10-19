package org.erisia.stasis.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.erisia.stasis.SpawnUse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Spawn protection lets players use Electrical Age and Stasis blocks (chargers, consoles, the map desk; Baughn
 * 2026-10-17) as with an empty hand: placing, breaking and item use stay blocked (SpawnUse: the held item does nothing there).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class SpawnUseMixin {
    private static final String HANDLE = "handleUseItemOn(Lnet/minecraft/network/protocol/game/ServerboundUseItemOnPacket;)V";

    @WrapOperation(method = HANDLE, require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;isUnderSpawnProtection(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)Z"))
    private boolean stasis$usableAtSpawn(MinecraftServer server, ServerLevel level, BlockPos pos, Player player, Operation<Boolean> original) {
        return original.call(server, level, pos, player) && !SpawnUse.usable(level, pos);
    }

    @WrapOperation(method = HANDLE, require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;mayInteract(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean stasis$mayUseAtSpawn(ServerLevel level, Entity entity, BlockPos pos, Operation<Boolean> original) {
        return original.call(level, entity, pos) || SpawnUse.blockOnly(entity, level, pos) && level.getWorldBorder().isWithinBounds(pos);
    }
}
