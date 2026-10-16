package org.erisia.stasis;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Storm crystals (Baughn, rift stage 3 D): while a storm runs, rift crystal clusters form on open ground under its
 * rifts, at most one per 32 x 32 cell, and evaporate when it ends. A high-risk harvest: out there a storm rift freezes
 * anyone who isn't inside a field. Every 5 s, around each player (128 blocks), cells whose spot lies in storm rift get
 * their cluster; all of them are recorded (saved) and removed once the storm is over, also in chunks loaded later.
 */
public final class StormCrystals {
    private StormCrystals() {
    }

    static final int CELL = 32, REACH = 128;
    static final float CHANCE = 0.5f;

    public static final class Data extends SavedData {
        static final Codec<Data> CODEC = Codec.LONG.listOf().xmap(Data::new, d -> new ArrayList<>(d.placed));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "storm_crystals"), Data::new, CODEC);

        final Set<Long> placed = new HashSet<>();

        public Data() {
        }

        private Data(List<Long> l) {
            placed.addAll(l);
        }

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }
    }

    /** The cell's crystal spot (x, z), or null if the cell has none. */
    static int[] spot(int seed, int cx, int cz) {
        if (Noise.hash32(cx, cz, 91, seed) >= CHANCE) return null;
        return new int[]{cx * CELL + 2 + (int) (Noise.hash32(cx, cz, 92, seed) * (CELL - 4)), cz * CELL + 2 + (int) (Noise.hash32(cx, cz, 93, seed) * (CELL - 4))};
    }

    static void tick(ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 100 != 0) return;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            if (!Stasis.stops(level)) continue;
            int seed = Deeps.seed(level);
            if (seed == 0) continue;
            double i = Storms.intensity(level);
            Data d = Data.get(level);
            if (i <= 0) {
                if (!d.placed.isEmpty()) evaporate(level, d);
                continue;
            }
            Block cluster = StasisRegistry.RIFT_CRYSTAL_CLUSTER.get();
            for (var p : level.players()) {
                int pcx = Math.floorDiv(p.getBlockX(), CELL), pcz = Math.floorDiv(p.getBlockZ(), CELL);
                for (int cx = pcx - REACH / CELL; cx <= pcx + REACH / CELL; cx++)
                    for (int cz = pcz - REACH / CELL; cz <= pcz + REACH / CELL; cz++) {
                        int[] s = spot(seed, cx, cz);
                        if (s == null || !level.hasChunkAt(new BlockPos(s[0], 0, s[1]))) continue;
                        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s[0], s[1]);
                        BlockPos at = new BlockPos(s[0], y, s[1]);
                        if (d.placed.contains(at.asLong()) || !level.getBlockState(at).isAir()
                            || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)) continue;
                        if (Storms.at(level, at.getX(), at.getY(), at.getZ()) != 2) continue;
                        // a little formation, not a lone cluster (one was hard to see from more than ~4 blocks:
                        // AdventurAgent): 3-6 clusters and big buds within 2 blocks
                        int n = 3 + (int) (Noise.hash32(cx, cz, 94, seed) * 4);
                        for (int k = 0; k < n; k++) {
                            int ox = k == 0 ? 0 : (int) (Noise.hash32(cx, cz, 95 + k, seed) * 5) - 2, oz = k == 0 ? 0 : (int) (Noise.hash32(cx, cz, 105 + k, seed) * 5) - 2;
                            int px = s[0] + ox, pz = s[1] + oz;
                            if (!level.hasChunkAt(new BlockPos(px, 0, pz))) continue;
                            BlockPos q = new BlockPos(px, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz), pz);
                            if (d.placed.contains(q.asLong()) || !level.getBlockState(q).isAir()
                                || !level.getBlockState(q.below()).isFaceSturdy(level, q.below(), Direction.UP)) continue;
                            Block b = k % 3 == 2 ? StasisRegistry.RIFT_CRYSTAL_LARGE.get() : cluster;
                            level.setBlock(q, b.defaultBlockState().setValue(AmethystClusterBlock.FACING, Direction.UP), Block.UPDATE_CLIENTS);
                            d.placed.add(q.asLong());
                        }
                        d.setDirty();
                    }
            }
        }
    }

    /** The storm is over: every recorded crystal still there in a loaded chunk goes (the rest when they load). */
    static void evaporate(ServerLevel level, Data d) {
        var it = d.placed.iterator();
        while (it.hasNext()) {
            BlockPos at = BlockPos.of(it.next());
            if (!level.hasChunkAt(at)) continue;
            if (level.getBlockState(at).is(StasisRegistry.RIFT_CRYSTAL_CLUSTER.get()) || level.getBlockState(at).is(StasisRegistry.RIFT_CRYSTAL_LARGE.get()))
                level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            it.remove();
        }
        d.setDirty();
    }

    /** Public for tests: evaporate now if no storm runs. */
    public static void testSweep(ServerLevel level) {
        if (Storms.intensity(level) <= 0) evaporate(level, Data.get(level));
    }

    public static int testCount(ServerLevel level) {
        return Data.get(level).placed.size();
    }
}
