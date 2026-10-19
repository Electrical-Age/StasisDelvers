package org.erisia.stasis;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The End rift's plan (EndRiftPlan, spreading mode step 5): deterministic, its block count bounded, every island block
 * in the shell and outside every static deep, the parts partition the main island's columns (the pillars' sectors
 * outside the core, the core inside), an island placed with a pillar's part never meets a column still standing then,
 * and every lifted block has a target.
 */
class EndRiftPlanTest {
    static EndRiftPlan plan(int seed) {
        return EndRiftPlan.of(seed, EndRiftPlan.vanillaSpikes());
    }

    @Property(tries = 6)
    void deterministicAndBounded(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed) {
        EndRiftPlan a = plan(seed), b = EndRiftPlan.build(seed, EndRiftPlan.vanillaSpikes());
        assertEquals(a.totalBlocks(), b.totalBlocks());
        for (int p = 0; p < a.parts; p++) {
            assertArrayEquals(a.blocks(p), b.blocks(p));
            assertArrayEquals(a.columns(p), b.columns(p));
        }
        assertTrue(a.totalBlocks() <= EndRiftPlan.BLOCK_BUDGET, a.toString());
        assertTrue(a.totalBlocks() > EndRiftPlan.BLOCK_BUDGET / 3, "the islands are there: " + a);
        assertTrue(a.islands.size() >= 15, a.toString());
    }

    @Property(tries = 6)
    void islandsInTheShellOutsideTheDeeps(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed) {
        EndRiftPlan pl = plan(seed);
        var deeps = EndRiftPlan.deeps(seed);
        Set<Long> seen = new HashSet<>();
        for (int p = 0; p < pl.parts; p++)
            for (long b : pl.blocks(p)) {
                int x = BlockPos.getX(b), y = BlockPos.getY(b), z = BlockPos.getZ(b);
                assertTrue(seen.add(b), "one block, one part");
                assertTrue(pl.isIsland(b));
                double d = Math.sqrt(x * x + Math.pow(y - StaticDeeps.END_RIFT_Y, 2) + z * z);
                assertTrue(d >= EndRiftPlan.SHELL_MIN - 1 && d <= EndRiftPlan.SHELL_MAX + 1, x + " " + y + " " + z + " at " + d);
                assertTrue(y >= 0 && y < 256);
                for (var dp : deeps) assertTrue(StaticDeeps.shape(dp, x + 0.5f, y + 0.5f, z + 0.5f) > 1, "in a deep at " + x + " " + y + " " + z);
            }
        assertEquals(pl.totalBlocks(), seen.size());
    }

    @Test
    void partsPartitionTheIslandsColumns() {
        EndRiftPlan pl = plan(12345);
        Set<Long> seen = new HashSet<>();
        for (int p = 0; p < pl.parts; p++)
            for (long c : pl.columns(p)) {
                assertTrue(seen.add(c));
                assertEquals(p, pl.partOf(BlockPos.getX(c), BlockPos.getZ(c)));
            }
        int n = 0;
        for (int x = -EndRiftPlan.ISLAND_RADIUS; x <= EndRiftPlan.ISLAND_RADIUS; x++)
            for (int z = -EndRiftPlan.ISLAND_RADIUS; z <= EndRiftPlan.ISLAND_RADIUS; z++) {
                int p = pl.partOf(x, z);
                double r = Math.hypot(x, z);
                if (r <= EndRiftPlan.ISLAND_RADIUS) {
                    n++;
                    assertTrue(p >= 0);
                    // the core holds the pillars (42 out, up to 5 wide) and nothing outside its wobble
                    if (r <= 50) assertEquals(pl.core, p, x + " " + z);
                    if (r >= EndRiftPlan.CORE_RADIUS + EndRiftPlan.CORE_WOBBLE + 1) assertTrue(p != pl.core, x + " " + z);
                } else assertEquals(-1, p);
            }
        assertEquals(n, seen.size(), "every column of the island in exactly one part");
        // every pillar's sector has columns, the farthest chunks first
        for (int p = 0; p < pl.core; p++) {
            long[] c = pl.columns(p);
            assertTrue(c.length > 1000, "sector " + p + ": " + c.length);
            double first = Math.hypot((BlockPos.getX(c[0]) >> 4) * 16 + 8, (BlockPos.getZ(c[0]) >> 4) * 16 + 8);
            double last = Math.hypot((BlockPos.getX(c[c.length - 1]) >> 4) * 16 + 8, (BlockPos.getZ(c[c.length - 1]) >> 4) * 16 + 8);
            assertTrue(first > last, "outside in: " + first + " " + last);
        }
    }

    @Property(tries = 6)
    void earlyIslandsMeetNoStandingColumn(@ForAll @IntRange(min = 1, max = 0xFFFFFE) int seed) {
        EndRiftPlan pl = plan(seed);
        for (int p = 0; p < pl.core; p++)
            for (long b : pl.blocks(p)) {
                int x = BlockPos.getX(b), y = BlockPos.getY(b), z = BlockPos.getZ(b);
                int cp = pl.partOf(x, z);
                assertTrue(y > EndRiftPlan.ISLAND_TOP || cp == -1 || cp == p, "part " + p + " block over column of part " + cp);
            }
    }

    @Test
    void everyLiftedBlockHasATarget() {
        EndRiftPlan pl = plan(777);
        StringBuilder sb = new StringBuilder(pl.toString()).append("; per part (columns/island blocks):");
        for (int p = 0; p < pl.parts; p++) sb.append(' ').append(pl.columns(p).length).append('/').append(pl.blocks(p).length);
        System.out.println(sb);
        for (int p = 0; p < pl.parts; p++)
            for (int i = 0; i < 400; i++) {
                long t = pl.target(p, i, 400);
                assertTrue(t != -1);
                int[] ti = pl.targetIndex(p, i, 400);
                assertEquals(t, pl.blocks(ti[0])[ti[1]]);
            }
        // an empty part still sends its shards somewhere
        EndRiftPlan none = EndRiftPlan.of(777, java.util.List.of());
        assertEquals(1, none.parts);
        assertTrue(none.target(0, 3, 10) != -1);
    }
}
