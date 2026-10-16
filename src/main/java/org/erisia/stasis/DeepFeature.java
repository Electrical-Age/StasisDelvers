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
                    double reach = Math.max(d.radius() * 1.5 + 24, Strata.bounds(d).radius() + 2);
                    double dx = Math.max(0, Math.max(x0 - d.x(), d.x() - (x0 + 16))), dz = Math.max(0, Math.max(z0 - d.z(), d.z() - (z0 + 16)));
                    if (dx * dx + dz * dz <= reach * reach) deeps.add(d);
                }
        long t0 = System.nanoTime();
        for (StaticDeeps.Deep d : deeps) any |= carve(level, d, x0, z0, minY, maxY);
        for (StaticDeeps.Deep d : deeps) any |= strata(level, d, x0, z0, minY, maxY);
        if (StasisConfig.timeScars) for (StaticDeeps.Deep d : deeps) any |= TimeScars.place(level, d, x0, z0, minY, maxY);
        if (StasisConfig.ruins) for (StaticDeeps.Deep d : deeps) any |= Ruins.place(level, d, x0, z0);
        if (any) {
            // vents, spires and sinkholes change the top: the chunk's heightmaps (rain, spawning, sky light, later
            // features) must follow, as for EA's oil outcrops (2caba052)
            Heightmap.primeHeightmaps(level.getChunk(x0 >> 4, z0 >> 4), java.util.EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.WORLD_SURFACE, Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG));
            Stats.record(System.nanoTime() - t0);
        }
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
                        BlockState ore = ore(x, y, z, id ^ 0x0BE);
                        if (ore != null) shell = ore;
                        else {
                            BlockState sc = Gates.scatter(d, x, y, z);   // the destination's plain blocks
                            if (sc != null) shell = sc;
                        }
                        if (shell != null) {
                            level.setBlock(p, shell, 2);
                            changed = true;
                        }
                        continue;
                    }
                    if (s <= 1.0f && !st.isAir() && Noise.hash32(x, y, z, id ^ 0xD15) < 0.012f) {   // a lifted dislocation
                        BlockPos up = p.above(2 + (int) (Noise.hash32(x, y, z, id ^ 0xD16) * 4));
                        if (level.getBlockState(up).isAir() && up.getY() < maxY) {
                            level.setBlock(up, st, 2);
                            level.setBlock(p, air, 2);
                            changed = true;
                        }
                    }
                }
                // a rift in open air (Baughn chose floating islands): shattered shell drifts round it as floating shards
                if (StasisConfig.skyIslands)
                    for (int y = Math.max(yLo, surface + 3); y <= yHi; y++) {
                        float s = StaticDeeps.shape(d, x + 0.5f, y + 0.5f, z + 0.5f);
                        if (s < 0.36f || s > 0.75f) continue;
                        if (Noise.fbm32(x / 7f, y / 5f, z / 7f, id ^ 0xF10) < 0.66f) continue;
                        p.set(x, y, z);
                        if (!level.getBlockState(p).isAir()) continue;
                        float h = Noise.hash32(x, y, z, id ^ 0xF11);
                        level.setBlock(p, (h < 0.1f ? Blocks.CRYING_OBSIDIAN : h < 0.55f ? Blocks.BLACKSTONE : h < 0.75f ? Blocks.TUFF
                            : StasisRegistry.RIFTSTONE.get()).defaultBlockState(), 2);
                        changed = true;
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
                    if (Noise.hash32(x, y, z, id ^ 0x57A) >= 0.08f) continue;
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

    /** Rock the strata may replace: overworld stone, dirt, gravel (never fluids, bedrock, the rift's own shell or air). */
    static boolean replaceable(BlockState st) {
        return st.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || st.is(net.minecraft.tags.BlockTags.DIRT) || st.is(Blocks.GRAVEL)
            || st.is(Blocks.SAND) || st.is(Blocks.CLAY) || st.is(Blocks.CALCITE) || st.is(Blocks.SMOOTH_BASALT)
            || st.is(Blocks.SANDSTONE) || st.is(Blocks.TERRACOTTA) || st.is(net.minecraft.tags.BlockTags.TERRACOTTA);
    }

    /** May a cavity be carved here: rock we replace or air, and no fluid next to it (it would flow during the new-chunk grace). */
    static boolean carvable(WorldGenLevel level, BlockPos.MutableBlockPos p, BlockState st) {
        if (!st.isAir() && !replaceable(st)) return false;
        for (Direction dir : Direction.values()) {
            BlockPos n = p.relative(dir);
            if (!level.getFluidState(n).isEmpty()) return false;
        }
        return true;
    }

    /** Is the wall block w a budding rift crystal: strata rock next to a cavity, at a hashed chance (pure: the same
     * answer from every chunk). */
    static boolean budding(StaticDeeps.Deep d, int x, int y, int z) {
        if (Noise.hash32(x, y, z, d.id() ^ 0xB0D) >= 0.05f) return false;
        int k = Strata.at(d, x, y, z);
        if (k != Strata.BODY && k != Strata.VEIN) return false;
        for (Direction dir : Direction.values())
            if (Strata.at(d, x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ()) == Strata.CAVITY) return true;
        return false;
    }

    private static boolean strata(WorldGenLevel level, StaticDeeps.Deep d, int x0, int z0, int minY, int maxY) {
        Strata.Bounds b = Strata.bounds(d);
        int yLo = Math.max(minY + 1, b.minY()), yHi = Math.min(maxY - 1, b.maxY());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState(), rock = StasisRegistry.RIFTSTONE.get().defaultBlockState();
        BlockState vein = StasisRegistry.RIFTSTONE_VEIN.get().defaultBlockState(), bud = StasisRegistry.BUDDING_RIFT_CRYSTAL.get().defaultBlockState();
        int id = d.id();
        boolean changed = false;
        java.util.List<BlockPos> cavities = new java.util.ArrayList<>();
        for (int x = x0; x < x0 + 16; x++)
            for (int z = z0; z < z0 + 16; z++) {
                if (Math.hypot(x + 0.5 - d.x(), z + 0.5 - d.z()) > b.radius()) continue;
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                for (int y = yLo; y <= yHi; y++) {
                    int k = Strata.at(d, x, y, z);
                    if (k == Strata.OUT) continue;
                    p.set(x, y, z);
                    BlockState st = level.getBlockState(p);
                    switch (k) {
                        case Strata.BODY, Strata.VEIN, Strata.SPIRE -> {
                            // in open air above the ground (a sky rift's strata), the layer floats as broken islands
                            boolean island = StasisConfig.skyIslands && y > surface + 2 && st.isAir()
                                && Noise.fbm32(x / 11f, y / 6f, z / 11f, id ^ 0x15A) > 0.5f;
                            boolean ok = island || replaceable(st) || k == Strata.SPIRE && (st.isAir() || st.canBeReplaced()) && st.getFluidState().isEmpty();
                            if (!ok) continue;
                            BlockState put = budding(d, x, y, z) ? bud : k == Strata.VEIN ? vein : rock;
                            if (put == rock) {
                                BlockState ore = ore(x, y, z, id ^ 0x5A0);
                                if (ore != null) put = ore;
                                else {
                                    BlockState sc = Gates.scatter(d, x, y, z);
                                    if (sc != null) put = sc;
                                }
                            }
                            level.setBlock(p, put, 2);
                            changed = true;
                        }
                        case Strata.CAVITY, Strata.VENT -> {
                            if (y > surface + 1) {   // above ground it's open already (islands' crystals grow into it)
                                if (k == Strata.CAVITY && st.isAir()) cavities.add(p.immutable());
                                continue;
                            }
                            if (!st.isAir() && carvable(level, p, st)) {
                                level.setBlock(p, air, 2);
                                changed = true;
                            }
                            if (k == Strata.CAVITY && level.getBlockState(p).isAir()) cavities.add(p.immutable());
                        }
                        default -> {
                        }
                    }
                }
            }
        // crystal buds in the cavities, on faces of budding walls (the wall may sit in a neighbouring chunk: budding()
        // is a pure function, so both chunks agree)
        for (BlockPos c : cavities) {
            if (Noise.hash32(c.getX(), c.getY(), c.getZ(), id ^ 0xB0E) >= 0.6f) continue;
            for (Direction dir : Direction.values()) {
                BlockPos w = c.relative(dir);
                if (!budding(d, w.getX(), w.getY(), w.getZ())) continue;
                float h = Noise.hash32(c.getX(), c.getY(), c.getZ(), id ^ 0xB0F);
                var block = h < 0.35f ? StasisRegistry.RIFT_CRYSTAL_SMALL : h < 0.6f ? StasisRegistry.RIFT_CRYSTAL_MEDIUM
                    : h < 0.85f ? StasisRegistry.RIFT_CRYSTAL_LARGE : StasisRegistry.RIFT_CRYSTAL_CLUSTER;
                level.setBlock(c, block.get().defaultBlockState().setValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING, dir.getOpposite()), 2);
                changed = true;
                break;
            }
        }
        return changed;
    }

    /** The overworld ores (NeoForge's c:ores_in_ground/stone and /deepslate tags; pack ores included), with weights:
     * the rare ones rarer. Built once the tags are loaded. */
    private static volatile java.util.List<BlockState> ORES;
    private static volatile float[] ORE_CUM;

    static BlockState ore(int x, int y, int z, int seed) {
        if (!StasisConfig.riftOres || Noise.hash32(x, y, z, seed) >= StasisConfig.riftOreRate) return null;
        java.util.List<BlockState> ores = ORES;
        if (ores == null || ores.isEmpty()) {   // (empty: resolved before the tags were bound; try again)
            java.util.List<BlockState> l = new java.util.ArrayList<>();
            java.util.List<Float> w = new java.util.ArrayList<>();
            for (String tag : java.util.List.of("ores_in_ground/stone", "ores_in_ground/deepslate"))
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getTagOrEmpty(net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.fromNamespaceAndPath("c", tag))).forEach(h -> {
                    String n = h.value().getDescriptionId();
                    l.add(h.value().defaultBlockState());
                    w.add(n.contains("diamond") || n.contains("emerald") ? 0.15f : n.contains("gold") || n.contains("lapis") || n.contains("redstone") ? 0.5f : 1f);
                });
            float[] cum = new float[w.size()];
            float t = 0;
            for (int i = 0; i < cum.length; i++) cum[i] = t += w.get(i);
            ORE_CUM = cum;
            ORES = ores = l;
        }
        if (ores.isEmpty()) return null;
        float[] cum = ORE_CUM;
        float u = Noise.hash32(x, y, z, seed ^ 0x0F) * cum[cum.length - 1];
        for (int i = 0; i < cum.length; i++) if (u < cum[i]) return ores.get(i);
        return ores.get(ores.size() - 1);
    }

    /** Worldgen time of chunks the rift features touched (/stasis strata stats). */
    public static final class Stats {
        public static final java.util.concurrent.atomic.AtomicLong CHUNKS = new java.util.concurrent.atomic.AtomicLong(), NANOS = new java.util.concurrent.atomic.AtomicLong(),
            MAX = new java.util.concurrent.atomic.AtomicLong();

        static void record(long ns) {
            CHUNKS.incrementAndGet();
            NANOS.addAndGet(ns);
            MAX.accumulateAndGet(ns, Math::max);
        }
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
