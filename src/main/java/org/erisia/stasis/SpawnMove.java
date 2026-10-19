package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

/**
 * Spreading mode: when the stillness reaches the world spawn's column, the world spawn moves, once, to the nearest
 * still-viable place (Baughn 2026-10-19, Discord 1561784319549374485): highlands of temporal stability >= 90 % (rank
 * >= ceil(0.9 N), and above P once P is past that, so the place still runs), by the regular spawn rules: on the
 * surface, on solid ground that is no fluid, ice over water or leaves, not at sea or on a river, not in a rift or a
 * deep's outer zone, and the column running. Players with their own bed or anchor keep it (Freezing.respawnPosition's
 * rules); those who respawn at world spawn go to the new one.
 * <p>
 * Once only: if the new spawn stills too (hard mode on to 100 %), it stays. A spawn outside the map (rank -1, stilled
 * from the start, e.g. a /setworldspawn far out) doesn't trigger a move: only the stillness rising over it does.
 * <p>
 * The search runs on the server thread over several ticks: the candidate cells (SpreadMap.cellsAtLeast, nearest the
 * old spawn first) are tried a few positions each, first against the generator's noise (dry, not ocean or river, the
 * stability there), and only a position that passes is loaded (generated if need be) and checked block by block. At
 * most CANDIDATES_PER_TICK cells and LOADS_PER_TICK chunk loads a tick; it resumes where it stopped. The state (moved,
 * where from, where to, or no site found) is saved in Spread.Clock.
 */
public final class SpawnMove {
    private SpawnMove() {
    }

    /** Clock.spawnMove: not moved, moved, or searched and found no site (not searched again). */
    static final int NONE = 0, MOVED = 1, NO_SITE = 2;
    static final double TARGET = StabilityCompassItem.TARGET;
    static final int CANDIDATES_PER_TICK = 16, LOADS_PER_TICK = 2;
    /** Offsets from a cell's centre (32i + 16, the first column of a chunk), all within that one chunk. */
    static final int[][] OFFSETS = {{0, 0}, {8, 8}, {8, 0}, {0, 8}, {12, 4}, {4, 12}, {15, 15}};

    /** The search under way (memory only: a restart starts it over), or null. */
    private record Search(SpreadMap map, int minRank, int[] cells, int fromX, int fromZ) {
    }

    private static Search search;
    private static int next, offset;
    private static long lastLog;

    /** GameTests: the mover only runs in test mode (Spread.testInstall) while a test arms it. */
    private static volatile boolean testArmed;

    public static void testArm(boolean on) {
        testArmed = on;
        search = null;
    }

    static void reset() {
        search = null;
        testArmed = false;
    }

    /** ServerTickEvent.Post, after Spread.tick. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (!StasisConfig.spreading || Spread.testing && !testArmed) return;
        step(e.getServer());
    }

    static void step(MinecraftServer srv) {
        ServerLevel ow = srv.overworld();
        Spread.State s = Spread.server;
        if (!(s.map() instanceof SpreadMap m) || !Spread.active(ow)) {
            search = null;
            return;
        }
        Spread.Clock k = Spread.Clock.get(ow);
        if (k.spawnMove != NONE) return;
        LevelData.RespawnData spawn = ow.getRespawnData();
        if (spawn.dimension() != Level.OVERWORLD) return;
        int sx = spawn.pos().getX(), sz = spawn.pos().getZ();
        if (search == null || search.map() != m) {
            if (m.rank1024(sx, sz) < 0 || !SpreadMap.stilled(m, sx, sz, s.p())) return;
            int min = Math.max((int) Math.ceil(TARGET * s.count()), s.p() + 1);
            // nearest qualifying cell, then up to its island's peak (it stills last), and candidates nearest that peak
            int[] near = SpreadMap.nearestAtLeast(m, sx, sz, min, 2 * m.n);
            int[] peak = near == null ? new int[]{sx, sz} : SpreadMap.climb(m, near[0], near[1]);
            search = new Search(m, min, SpreadMap.cellsAtLeast(m, peak[0], peak[1], min), sx, sz);
            next = 0;
            offset = 0;
            lastLog = System.nanoTime();
            StasisDelvers.LOG.info("The world spawn {} has stilled (P {} of {}): looking for a new spawn among {} cells of rank >= {}",
                spawn.pos().toShortString(), s.p(), s.count(), search.cells().length, min);
        }
        Search q = search;
        int loads = 0;
        for (int tried = 0; tried < CANDIDATES_PER_TICK && next < q.cells().length; ) {
            int c = q.cells()[next];
            int x = (m.cellX0 + c % m.n) * SpreadMap.CELL + SpreadMap.HALF + OFFSETS[offset][0];
            int z = (m.cellZ0 + c / m.n) * SpreadMap.CELL + SpreadMap.HALF + OFFSETS[offset][1];
            if (prefilter(ow, m, q.minRank(), x, z)) {
                if (!ow.hasChunk(x >> 4, z >> 4)) {
                    if (loads >= LOADS_PER_TICK) break;   // (the rest next tick, from this position)
                    loads++;
                }
                BlockPos at = check(ow, x, z);
                if (at != null) {
                    move(srv, ow, k, q, at);
                    return;
                }
            }
            if (++offset == OFFSETS.length) {
                offset = 0;
                next++;
                tried++;
            }
        }
        if (next >= q.cells().length) {
            k.spawnMove = NO_SITE;
            k.spawnOldX = q.fromX();
            k.spawnOldZ = q.fromZ();
            k.setDirty();
            search = null;
            StasisDelvers.LOG.warn("The world spawn has stilled, and no site of stability >= {} / {} was found: it stays", q.minRank(), m.count);
        } else if (System.nanoTime() - lastLog > 5_000_000_000L) {
            lastLog = System.nanoTime();
            StasisDelvers.LOG.info("Looking for a new world spawn: {} of {} cells tried", next, q.cells().length);
        }
    }

    /** From the generator's noise, no chunk loaded: dry land, not ocean or river, and stable enough right here. */
    static boolean prefilter(ServerLevel level, SpreadMap m, int minRank, int x, int z) {
        if (m.rank1024(x, z) < (long) minRank * SpreadMap.SCALE) return false;
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        int top = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
        int floor = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
        if (top != floor) return false;
        // (ice counts as surface in both heightmaps: the biome says it's a frozen sea or river)
        return !atSea(level.getUncachedNoiseBiome(x >> 2, Math.max(top, level.getSeaLevel()) >> 2, z >> 2));
    }

    static boolean atSea(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> b) {
        return b.is(BiomeTags.IS_OCEAN) || b.is(BiomeTags.IS_DEEP_OCEAN) || b.is(BiomeTags.IS_RIVER);
    }

    /**
     * The spawn position at column (x, z) of a loaded (or now generated) chunk, or null: the top of the ground (the
     * MOTION_BLOCKING_NO_LEAVES heightmap), solid, not a fluid, leaves, a log or ice, two free blocks of air above, no rift or
     * deep zone there, not at sea, and the column running at the current P.
     */
    static BlockPos check(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinY() + 1 || y >= level.getMaxY() - 2) return null;
        BlockPos at = new BlockPos(x, y, z);
        var ground = level.getBlockState(at.below());
        if (!SpawnCottage.solidGround(ground) || ground.is(BlockTags.LEAVES) || ground.is(BlockTags.LOGS) || ground.is(BlockTags.ICE)) return null;
        for (BlockPos p : new BlockPos[]{at, at.above()}) {
            var st = level.getBlockState(p);
            if (!st.getFluidState().isEmpty() || !st.getCollisionShape(level, p).isEmpty()) return null;
        }
        for (int dy = -1; dy <= 1; dy++) if (StaticDeeps.zone(level, x, y + dy, z) != 0) return null;
        if (atSea(level.getBiome(at))) return null;
        if (!Stasis.spreadRuns(level, x, z)) return null;
        return at;
    }

    private static void move(MinecraftServer srv, ServerLevel ow, Spread.Clock k, Search q, BlockPos at) {
        ow.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, at, 0f, 0f));
        k.spawnMove = MOVED;
        k.spawnOldX = q.fromX();
        k.spawnOldZ = q.fromZ();
        k.spawnX = at.getX();
        k.spawnY = at.getY();
        k.spawnZ = at.getZ();
        k.setDirty();
        search = null;
        StasisDelvers.LOG.info("The world spawn moved from {} {} to {} (rank {} of {}; candidate cell {} of {})", q.fromX(), q.fromZ(), at.toShortString(),
            q.map().rank1024(at.getX(), at.getZ()) / SpreadMap.SCALE, q.map().count, next + 1, q.cells().length);
        srv.getPlayerList().broadcastSystemMessage(Component.literal("The world's spawn has moved to the highlands at " + at.getX() + ", "
            + at.getZ() + ": the old spawn has stilled.").withStyle(net.minecraft.ChatFormatting.GOLD), false);
    }

    /** For /stasis spread status. */
    static String status(Spread.Clock k) {
        return switch (k.spawnMove) {
            case MOVED -> "spawn moved once, from " + k.spawnOldX + " " + k.spawnOldZ + " to " + k.spawnX + " " + k.spawnY + " " + k.spawnZ;
            case NO_SITE -> "spawn stilled at " + k.spawnOldX + " " + k.spawnOldZ + ", no site found to move it to";
            default -> search != null ? "spawn stilled, searching for a new one (" + next + " of " + search.cells().length + " cells tried)"
                : "spawn not moved";
        };
    }
}
