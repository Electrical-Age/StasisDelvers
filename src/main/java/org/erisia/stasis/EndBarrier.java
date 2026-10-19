package org.erisia.stasis;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Spreading mode's wall round the End's main island (λ-Nero, spread-DESIGN.md: before the dragon dies every way into the
 * End leads to the main island, and a barrier that looks like a time field with black lines keeps people on it; no outer
 * End). A vertical cylinder of radius endBarrierRadius round 0, 0. Movement stops at it like a world border's
 * ({@link #clamp}, from mixin/EndBarrierMixin on both sides: the client predicts it, so nothing snaps back; AdventurAgent
 * found the server-only put-back rubber-banding). As a fallback (a pearl, lag, a client without the mod's movement) the
 * server puts a player well past it (SLACK) back where they last stood on the ground inside, or onto the arrival
 * platform; the client draws it (client/EndBarrierFx). Creative and spectator players pass.
 * <p>
 * Every way in already lands inside: the End portal on the obsidian platform at (100, 50, 0), and no End gateway ever
 * appears in spreading mode (EnderDragonFightMixin), so no pearl or player goes through one. It stays after hard mode
 * (the End is stilled then, and nobody gets in until the lance): step 5 may change {@link #active}.
 */
public final class EndBarrier {
    private EndBarrier() {
    }

    /** Does the wall stand in this level (both sides: the config is synced)? */
    public static boolean active(Level level) {
        return StasisConfig.spreading && StasisConfig.endBarrier && level.dimension() == Level.END;
    }

    public static int radius() {
        return StasisConfig.endBarrierRadius;
    }

    /** The server puts a player back only this far past the wall (the clamp keeps everyone else inside). */
    public static final double SLACK = 2;

    /**
     * Entity.collide's result, clamped to the wall (EndBarrierMixin; both sides). For a survival or adventure player, or
     * a vehicle one steers: the horizontal end point is pulled radially back to the wall (the player's box touching it),
     * so a player walking into it stops and one walking along it slides round it; one already outside may move inward or
     * along, not further out. Returns `move` itself when nothing changes.
     */
    public static Vec3 clamp(net.minecraft.world.entity.Entity e, Vec3 move) {
        if (!StasisConfig.spreading || !StasisConfig.endBarrier || (move.x == 0 && move.z == 0) || !active(e.level())) return move;
        net.minecraft.world.entity.Entity who = e instanceof net.minecraft.world.entity.player.Player ? e : e.getControllingPassenger();
        if (!(who instanceof net.minecraft.world.entity.player.Player p) || p.isCreative() || p.isSpectator()) return move;
        double lim = radius() - e.getBbWidth() / 2;
        double x = e.getX(), z = e.getZ(), nx = x + move.x, nz = z + move.z, d2 = nx * nx + nz * nz;
        if (d2 <= lim * lim) return move;
        double d = Math.sqrt(d2), to = Math.max(lim, Math.min(Math.sqrt(x * x + z * z), d)), s = to / d;
        if (s >= 1) return move;
        return new Vec3(nx * s - x, move.y, nz * s - z);
    }

    /** Where each player last stood on the ground inside (server thread). */
    private static final Map<UUID, Vec3> LAST = new HashMap<>();
    private static final Map<UUID, Long> TOLD = new HashMap<>();

    /** PlayerTickEvent.Post. */
    static void tick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) {
        if (e.getEntity() instanceof ServerPlayer p) check(p);
    }

    /** One check of one player (public for tests). Returns true if the player was put back (more than SLACK past the wall). */
    public static boolean check(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level) || !active(level) || p.isCreative() || p.isSpectator()) {
            LAST.remove(p.getUUID());
            return false;
        }
        double r = radius(), d2 = p.getX() * p.getX() + p.getZ() * p.getZ();
        if (d2 <= r * r) {
            if (p.onGround() && d2 <= (r - 1) * (r - 1)) LAST.put(p.getUUID(), p.position());
            return false;
        }
        if (d2 <= (r + SLACK) * (r + SLACK)) return false;   // (the clamp holds them: no snap back)
        Vec3 back = LAST.get(p.getUUID());
        if (back == null || back.x * back.x + back.z * back.z > r * r) back = fallback(level, p, r);
        if (p.isPassenger()) p.stopRiding();
        p.teleportTo(back.x, back.y, back.z);
        p.setDeltaMovement(Vec3.ZERO);
        // (the client keeps its own momentum otherwise, and would run into the wall again and again)
        p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(p));
        p.resetFallDistance();
        long now = level.getGameTime();
        Long last = TOLD.get(p.getUUID());
        if (last == null || now - last > 60 || now < last) {
            TOLD.put(p.getUUID(), now);
            p.sendOverlayMessage(Component.literal("A wall of stilled time holds you to the island").withStyle(ChatFormatting.DARK_AQUA));
        }
        return true;
    }

    /** No ground inside on record: the island's surface just inside the wall towards the player, or the arrival platform. */
    static Vec3 fallback(ServerLevel level, ServerPlayer p, double r) {
        double a = Math.atan2(p.getZ(), p.getX());
        int x = (int) Math.floor(Math.cos(a) * (r - 4)), z = (int) Math.floor(Math.sin(a) * (r - 4));
        level.getChunk(x >> 4, z >> 4);   // (loaded, so the heightmap is real; rare: no ground on record)
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        if (y > level.getMinY() + 1) return new Vec3(x + 0.5, y, z + 0.5);
        BlockPos spawn = ServerLevel.END_SPAWN_POINT;
        if (!level.getBlockState(spawn.below()).isSolid())
            net.minecraft.world.level.levelgen.feature.EndPlatformFeature.createEndPlatform(level, spawn.below(), true);
        return new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
    }

    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        LAST.remove(e.getEntity().getUUID());
        TOLD.remove(e.getEntity().getUUID());
    }
}
