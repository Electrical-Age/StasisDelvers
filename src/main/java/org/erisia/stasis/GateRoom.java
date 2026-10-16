package org.erisia.stasis;

import com.mojang.math.Transformation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.erisia.stasis.mixin.BlockDisplayInvoker;
import org.erisia.stasis.mixin.DisplayInvoker;
import org.erisia.stasis.mixin.StructurePieceInvoker;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.function.IntFunction;

/**
 * The stronghold portal room, caught mid-collapse when its portal tore into a gate rift (Baughn: "an explosion/
 * implosion halted in the middle"; the plan is Gemini's "snap-back", notes/rifts/second-opinion-a5.md):
 * - the core (within ~3.5 of the centre) is empty: the rift swallowed it;
 * - the outer ring (walls, ceiling) was blasted outward: cracked bricks, blocks punched back into the rock, craters,
 *   the entrance grate blown down the corridor;
 * - the middle ring (stairs, platform, pools) is being pulled in and up: torn away, debris hanging just outside the
 *   core, frozen lava ribbons arcing from the pools towards it, the stone near the core transmuted;
 * - the frame is shards: tilted block displays (any angle, not minable, no ticking), some with their eyes;
 * - the spawner is gone; its silverfish hang mid-leap.
 * Deterministic per room (its gate id); every chunk of the room writes only its own blocks and entities, so the order
 * chunks generate in doesn't matter. The room's chunks get no new-chunk grace (NewChunkGrace.exempt): else lava and
 * debris would settle before time stops.
 */
public final class GateRoom {
    private GateRoom() {
    }

    /** Room-local coordinates of the gate's centre (over the old frame platform). */
    static final int CX = 5, CY = 4, CZ = 10;

    public static void shatter(WorldGenLevel level, StructurePieceInvoker piece, BoundingBox chunkBB) {
        BlockPos c = piece.stasis$worldPos(CX, CY, CZ).immutable();
        int seed = Gates.gateId(c);
        Vec3 cv = Vec3.atCenterOf(c);
        // the frame ring is gone already (StrongholdPortalRoomMixin); now the room, plus 3 blocks of rock around it
        for (int lx = -3; lx <= 13; lx++)
            for (int ly = -1; ly <= 10; ly++)
                for (int lz = -3; lz <= 18; lz++) {
                    BlockPos p = piece.stasis$worldPos(lx, ly, lz).immutable();
                    if (!chunkBB.isInside(p)) continue;
                    boolean inside = lx >= 1 && lx <= 9 && ly >= 1 && ly <= 6 && lz >= 1 && lz <= 14;
                    Vec3 v = Vec3.atCenterOf(p).subtract(cv);
                    double d = v.length();
                    BlockState st = level.getBlockState(p);
                    float h = Noise.hash32(p.getX(), p.getY(), p.getZ(), seed);
                    float patch = Noise.value32(p.getX() / 3f, p.getY() / 3f, p.getZ() / 3f, seed ^ 0x6A1);
                    if (d < 2.8) {   // swallowed
                        if (!st.isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        continue;
                    }
                    if (st.isAir() || st.is(Blocks.LAVA)) {
                        // debris hanging just outside the core, pulled in and up
                        if (inside && d > 3.2 && d < 6.5 && ly >= 2 && h < 0.08f)
                            level.setBlock(p, debris(h * 14.3f), 2);
                        continue;
                    }
                    if (inside && d < 7.5 && ly <= 3 && h < 0.35f && patch > 0.45f) {
                        // the platform, stairs and pool rims torn away towards the core
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        continue;
                    }
                    boolean shell = !inside && lx >= 0 && lx <= 10 && ly >= 0 && ly <= 7 && lz >= 0 && lz <= 15;
                    if (shell && d >= 5.0 && patch > 0.5f && patch <= 0.62f) {
                        // bulged: the wall pushed out a block where the blast hit it (ragged, not a straight wall)
                        BlockPos q = BlockPos.containing(Vec3.atCenterOf(p).add(v.normalize()));
                        if (chunkBB.isInside(q) && level.getBlockState(q).isAir())
                            level.setBlock(q, h < 0.5f ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : st, 2);
                    }
                    if (shell && d >= 6.0 && patch > 0.62f && h < 0.6f) {
                        // blasted outward: a hole in the wall, the block thrown into the rock behind it
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                        Vec3 out = Vec3.atCenterOf(p).add(v.normalize().scale(2.0));
                        BlockPos q = BlockPos.containing(out);
                        if (chunkBB.isInside(q) && !level.getBlockState(q).isAir()) {
                            BlockPos behind = BlockPos.containing(Vec3.atCenterOf(p).add(v.normalize()));
                            if (chunkBB.isInside(behind)) level.setBlock(behind, Blocks.AIR.defaultBlockState(), 2);
                            level.setBlock(q, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 2);
                        }
                        continue;
                    }
                    // the stone near the core transmuted; the rest cracked
                    if (d < 6.5 && h < 0.45f) level.setBlock(p, transmuted(h / 0.45f), 2);
                    else if (d < 11 && st.is(Blocks.STONE_BRICKS) && h < 0.7f) level.setBlock(p, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 2);
                    else if (st.is(Blocks.SPAWNER)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
        // debris thrown out through the walls: where open space lies outside (a cave), blocks hang along rays from the
        // centre 2-7 blocks past the wall, flying away
        for (int i = 0; i < 90; i++) {
            double az = Noise.hash32(i, 20, 0, seed) * 2 * Math.PI, el = (Noise.hash32(i, 21, 0, seed) - 0.3) * 1.2;
            double r = 8.5 + 7 * Noise.hash32(i, 22, 0, seed);
            Vec3 q = cv.add(Math.cos(az) * Math.cos(el) * r, Math.sin(el) * r, Math.sin(az) * Math.cos(el) * r);
            BlockPos bp = BlockPos.containing(q);
            if (!chunkBB.isInside(bp) || !level.getBlockState(bp).isAir()) continue;
            // only past the room (its inside stays as it is) and into open air
            level.setBlock(bp, debris(Noise.hash32(i, 23, 0, seed)), 2);
        }
        // the entrance grate (the door at local 4..6, 1..3, 0) blown out down the corridor
        for (int lx = 4; lx <= 6; lx++)
            for (int ly = 1; ly <= 3; ly++) {
                BlockPos p = piece.stasis$worldPos(lx, ly, 0).immutable();
                if (chunkBB.isInside(p) && level.getBlockState(p).is(Blocks.IRON_BARS)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
            }
        for (int i = 0; i < 3; i++) {
            BlockPos p = piece.stasis$worldPos(4 + i, 1 + (int) (Noise.hash32(i, 1, 0, seed) * 2), -2 - (int) (Noise.hash32(i, 2, 0, seed) * 3)).immutable();
            if (chunkBB.isInside(p) && level.getBlockState(p).isAir()) level.setBlock(p, Blocks.IRON_BARS.defaultBlockState(), 2);
        }
        // frozen lava ribbons from the two side pools up towards the core's edge
        for (int side = 0; side < 2; side++) {
            Vec3 a = Vec3.atCenterOf(piece.stasis$worldPos(side == 0 ? 1 : 9, 1, 2));
            Vec3 toward = cv.subtract(a).normalize();
            Vec3 b = cv.subtract(toward.scale(4.2));
            Vec3 ctrl = a.add(b).scale(0.5).add(0, 3.5, 0);
            for (int k = 0; k <= 40; k++) {
                double t = k / 40.0;
                Vec3 q = a.scale((1 - t) * (1 - t)).add(ctrl.scale(2 * t * (1 - t))).add(b.scale(t * t));
                BlockPos p = BlockPos.containing(q);
                if (!chunkBB.isInside(p) || !level.getBlockState(p).isAir()) continue;
                level.setBlock(p, Noise.hash32(p.getX(), p.getY(), p.getZ(), seed ^ 0x1A7A) < 0.2f
                    ? Blocks.MAGMA_BLOCK.defaultBlockState() : Blocks.LAVA.defaultBlockState(), 2);
            }
        }
        // the frame's shards, tilted towards the core, some still holding their eyes
        for (int i = 0; i < 16; i++) {
            double az = Noise.hash32(i, 3, 0, seed) * 2 * Math.PI, el = (Noise.hash32(i, 4, 0, seed) - 0.35) * 1.1;
            double r = 3.4 + 3.2 * Noise.hash32(i, 5, 0, seed);
            Vec3 pos = cv.add(Math.cos(az) * Math.cos(el) * r, Math.sin(el) * r, Math.sin(az) * Math.cos(el) * r);
            BlockPos bp = BlockPos.containing(pos);
            if (!chunkBB.isInside(bp) || !level.getBlockState(bp).isAir()) continue;
            BlockState shard = i < 3 ? Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.HAS_EYE, true)
                : i % 3 == 0 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.END_STONE_BRICKS.defaultBlockState();
            float scale = 0.45f + 0.45f * Noise.hash32(i, 6, 0, seed);
            Quaternionf q = new Quaternionf().rotateY((float) (-az)).rotateZ((float) (0.4 + 0.9 * Noise.hash32(i, 7, 0, seed)))
                .rotateX((float) (Noise.hash32(i, 8, 0, seed) * 1.2 - 0.6));
            Display.BlockDisplay e = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level.getLevel());
            e.setPos(pos.x, pos.y, pos.z);
            ((BlockDisplayInvoker) e).stasis$setBlockState(shard);
            ((DisplayInvoker) e).stasis$setTransformation(new Transformation(new Matrix4f().rotate(q).scale(scale).translate(-0.5f, -0.5f, -0.5f)));
            level.addFreshEntity(e);
        }
        // the spawner's silverfish, mid-leap away from the blast
        for (int i = 0; i < 3; i++) {
            Vec3 pos = Vec3.atCenterOf(piece.stasis$worldPos(3 + 2 * i, 3, 4)).add(0, Noise.hash32(i, 9, 0, seed) * 1.5, -Noise.hash32(i, 10, 0, seed) * 2);
            BlockPos bp = BlockPos.containing(pos);
            if (!chunkBB.isInside(bp) || !level.getBlockState(bp).isAir()) continue;
            var fish = EntityTypes.SILVERFISH.create(level.getLevel(), EntitySpawnReason.STRUCTURE);
            if (fish == null) continue;
            fish.snapTo(pos.x, pos.y, pos.z, Noise.hash32(i, 11, 0, seed) * 360f, -30f);
            fish.setPersistenceRequired();
            level.addFreshEntity(fish);
        }
    }

    static BlockState debris(float u) {
        IntFunction<Block> pick = k -> switch (k) {
            case 0 -> Blocks.CRACKED_STONE_BRICKS;
            case 1 -> Blocks.STONE_BRICKS;
            case 2 -> Blocks.MOSSY_STONE_BRICKS;
            case 3 -> Blocks.COBBLESTONE;
            default -> Blocks.BLACKSTONE;
        };
        return pick.apply((int) Math.min(4, u * 5)).defaultBlockState();
    }

    static BlockState transmuted(float u) {
        return u < 0.35f ? Blocks.BLACKSTONE.defaultBlockState() : u < 0.55f ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
            : u < 0.8f ? StasisRegistry.RIFTSTONE.get().defaultBlockState() : Blocks.END_STONE.defaultBlockState();
    }
}
