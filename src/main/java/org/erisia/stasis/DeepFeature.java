package org.erisia.stasis;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * Worldgen around static deeps (Baughn: organic, with block-level dislocations players can fall into, stalagmites and
 * other decoration). Runs once per new chunk (placed feature with no placement; biome modifier for the overworld) and
 * only touches its own chunk's columns:
 * <ul>
 * <li>the rift is hollow: a cavern of the deep's organic shape you can fall into;</li>
 * <li>a shattered shell around it (blackstone, crying obsidian, tuff);</li>
 * <li>crevasses: winding cracks from the surface down to the rift through the outer zone, and a sinkhole above the
 * centre when the rift is near the surface;</li>
 * <li>stalagmites and stalactites (pointed dripstone) in the outer zone's open spaces;</li>
 * <li>dislocations: small chunks of ground lifted a few blocks out of place.</li>
 * </ul>
 * Chunks generated before this exist keep their terrain (Baughn: tested in new chunks; the world is reset before the
 * playtest).
 */
public record DeepFeature() implements Feature {
    public static final MapCodec<DeepFeature> CODEC = MapCodec.unit(DeepFeature::new);

    @Override
    public MapCodec<DeepFeature> codec() {
        return CODEC;
    }

    @Override
    public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
        var sl = level.getLevel();
        if (!Stasis.stops(sl)) return false;
        int seed = FieldData.peekDeepSeed(sl);
        if (seed == 0) return false;
        int x0 = origin.getX() & ~15, z0 = origin.getZ() & ~15;
        boolean any = false;
        int minY = level.getMinY(), maxY = level.getMaxY();
        java.util.List<StaticDeeps.Deep> deeps = new java.util.ArrayList<>();
        for (int j = Math.floorDiv(minY, StaticDeeps.CY) - 1; j <= Math.floorDiv(maxY, StaticDeeps.CY) + 1; j++)
            for (int i = Math.floorDiv(x0 + 8, StaticDeeps.CX) - 1; i <= Math.floorDiv(x0 + 8, StaticDeeps.CX) + 1; i++)
                for (int k = Math.floorDiv(z0 + 8, StaticDeeps.CZ) - 1; k <= Math.floorDiv(z0 + 8, StaticDeeps.CZ) + 1; k++) {
                    StaticDeeps.Deep d = StaticDeeps.cell(i, j, k, seed);
                    if (d == null) continue;
                    double reach = d.radius() * 1.5 + 24;
                    double dx = Math.max(0, Math.max(x0 - d.x(), d.x() - (x0 + 16))), dz = Math.max(0, Math.max(z0 - d.z(), d.z() - (z0 + 16)));
                    if (dx * dx + dz * dz <= reach * reach) deeps.add(d);
                }
        for (StaticDeeps.Deep d : deeps) any |= carve(level, d, x0, z0, minY, maxY);
        return any;
    }

    private static boolean carve(WorldGenLevel level, StaticDeeps.Deep d, int x0, int z0, int minY, int maxY) {
        int yLo = Math.max(minY + 1, (int) (d.y() - d.radius() * 1.3 - 12)), yHi = Math.min(maxY - 1, (int) (d.y() + d.radius() * 1.3 + 12));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        int id = d.id();
        boolean changed = false;
        for (int x = x0; x < x0 + 16; x++)
            for (int z = z0; z < z0 + 16; z++) {
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                // crevasse: a winding crack (a ridge of 2D noise) through the outer zone, open from the surface down
                float ridge = Math.abs(Noise.value32(x / 22f, 3.5f, z / 22f, id ^ 0xC4E) - 0.5f);
                boolean crack = ridge < 0.025f;
                // sinkhole: above the centre, if the rift comes within ~40 blocks of the surface
                double hdx = x + 0.5 - d.x(), hdz = z + 0.5 - d.z();
                boolean sink = Math.sqrt(hdx * hdx + hdz * hdz) < d.radius() * 0.22 * (0.8 + 0.4 * Noise.value32(x / 6f, 1.5f, z / 6f, id))
                    && surface - (d.y() + d.radius() * 0.35) < 40;
                int top = Math.min(yHi, surface + 1);
                int riftTop = Integer.MIN_VALUE;
                for (int y = yLo; y <= top; y++) {
                    float s = StaticDeeps.shape(d, x + 0.5f, y + 0.5f, z + 0.5f);
                    if (s > 1.15f) continue;
                    p.set(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (s < 0.30f) {                       // the hollow rift
                        if (!st.isAir()) {
                            level.setBlock(p, air, 2);
                            changed = true;
                        }
                        riftTop = y;
                        continue;
                    }
                    if (s < 0.42f && !st.isAir() && st.getFluidState().isEmpty()) {   // shattered shell
                        float h = Noise.hash32(x, y, z, id);
                        BlockState shell = h < 0.08f ? Blocks.CRYING_OBSIDIAN.defaultBlockState()
                            : h < 0.45f ? Blocks.BLACKSTONE.defaultBlockState() : h < 0.6f ? Blocks.TUFF.defaultBlockState() : null;
                        if (shell != null) {
                            level.setBlock(p, shell, 2);
                            changed = true;
                        }
                        continue;
                    }
                    if (s <= 1.0f && !st.isAir() && Noise.hash32(x, y, z, id ^ 0xD15) < 0.004f) {   // a lifted dislocation
                        BlockPos up = p.above(2 + (int) (Noise.hash32(x, y, z, id ^ 0xD16) * 4));
                        if (level.getBlockState(up).isAir() && up.getY() < maxY) {
                            level.setBlock(up, st, 2);
                            level.setBlock(p, air, 2);
                            changed = true;
                        }
                    }
                }
                // cracks and the sinkhole: from the surface down into the rift (only in the outer zone's columns)
                if ((crack || sink) && riftTop != Integer.MIN_VALUE) {
                    for (int y = riftTop; y <= surface; y++) {
                        p.set(x, y, z);
                        float s = StaticDeeps.shape(d, x + 0.5f, y + 0.5f, z + 0.5f);
                        if (s > 1.0f && !sink) break;
                        if (!level.getBlockState(p).isAir()) {
                            level.setBlock(p, air, 2);
                            changed = true;
                        }
                    }
                }
                // stalagmites and stalactites in the outer zone's open spaces
                for (int y = yLo + 1; y < top - 1; y++) {
                    if (Noise.hash32(x, y, z, id ^ 0x57A) >= 0.03f) continue;
                    float s = StaticDeeps.shape(d, x + 0.5f, y + 0.5f, z + 0.5f);
                    if (s < 0.45f || s > 1.0f) continue;
                    p.set(x, y, z);
                    if (!level.getBlockState(p).isAir()) continue;
                    boolean floor = level.getBlockState(p.below()).isSolidRender(), ceil = level.getBlockState(p.above()).isSolidRender();
                    if (!floor && !ceil) continue;
                    Direction dir = floor ? Direction.UP : Direction.DOWN;
                    int len = 1 + (int) (Noise.hash32(x, y, z, id ^ 0x57B) * 4);
                    changed |= speleothem(level, p.immutable(), dir, len);
                }
            }
        return changed;
    }

    /** A pointed dripstone column of up to len blocks growing in dir (base, middle, frustum, tip), stopping at a block. */
    private static boolean speleothem(WorldGenLevel level, BlockPos start, Direction dir, int len) {
        int n = 0;
        while (n < len && level.getBlockState(start.relative(dir, n)).isAir()) n++;
        if (n == 0) return false;
        for (int i = 0; i < n; i++) {
            SpeleothemThickness t = i == n - 1 ? SpeleothemThickness.TIP : i == n - 2 ? SpeleothemThickness.FRUSTUM
                : i == 0 && n > 3 ? SpeleothemThickness.BASE : SpeleothemThickness.MIDDLE;
            level.setBlock(start.relative(dir, i), Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(PointedDripstoneBlock.TIP_DIRECTION, dir).setValue(PointedDripstoneBlock.THICKNESS, t), 2);
        }
        return true;
    }
}
