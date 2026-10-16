package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Time scars (rift worldgen, before the reset; Baughn and λ-Nero): things caught mid-moment around rifts when the
 * world crashed.
 * - Falls: water (lava deep down) leaking from a cave ceiling, stopped mid-fall short of the floor. In stopped land a
 *   fluid is solid; mined with a shovel it gives stilled water or lava (StilledLiquids).
 * - Hanging gravel: gravel and sand caught falling from a ceiling.
 * - A volley of arrows hanging in the air.
 * Chunks that get scars get no new-chunk grace (or the falls would finish falling first); a field covering them later
 * lets time resume: the water falls, the gravel lands, the arrows fly.
 * Only in rift chunks (the shell and strata), deterministic per chunk.
 */
public final class TimeScars {
    private TimeScars() {
    }

    /** Counts since start (falls, gravel, volleys), for /stasis strata. */
    public static final java.util.concurrent.atomic.AtomicInteger FALLS = new java.util.concurrent.atomic.AtomicInteger(),
        GRAVEL = new java.util.concurrent.atomic.AtomicInteger(), VOLLEYS = new java.util.concurrent.atomic.AtomicInteger();

    public static boolean place(WorldGenLevel level, StaticDeeps.Deep d, int x0, int z0, int minY, int maxY) {
        int id = d.id();
        int cx = x0 >> 4, cz = z0 >> 4;
        boolean any = false;
        // a few candidate columns per chunk
        // a rift above the ground gets extra candidates for falls under its floating islands
        int nk = d.y() - d.radius() * 0.35f > level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x0 + 8, z0 + 8) ? 10 : 6;
        for (int k = 0; k < nk; k++) {
            float h = Noise.hash32(cx, cz, 300 + k, id);
            int x = x0 + (int) (Noise.hash32(cx, cz, 310 + k, id) * 16), z = z0 + (int) (Noise.hash32(cx, cz, 320 + k, id) * 16);
            if (Math.hypot(x + 0.5 - d.x(), z + 0.5 - d.z()) > Math.max(d.radius() * 1.3 + 12, Strata.bounds(d).radius())) continue;
            int type = k < 2 || k >= 6 ? 0 : k < 4 ? 1 : 2;   // falls (and the sky extras), gravel, arrows
            float chance = type == 0 ? 0.35f : type == 1 ? 0.35f : 0.06f;
            if (h >= chance) continue;
            // a ceiling: air under solid stone, with at least 6 blocks of air below, top-down from the rift's height
            int yHi = Math.min(maxY - 2, (int) (d.y() + d.radius() * 1.3 + 12)), yLo = Math.max(minY + 2, (int) (d.y() - d.radius() * 1.6 - 12));
            int start = yLo + (int) (Noise.hash32(cx, cz, 330 + k, id) * Math.max(1, yHi - yLo));
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int ceil = Integer.MIN_VALUE, gap = 0;
            for (int y = start; y >= yLo; y--) {
                p.set(x, y, z);
                if (!level.getBlockState(p).isAir()) continue;
                BlockState above = level.getBlockState(p.above());
                if (!above.isSolidRender() || !above.getFluidState().isEmpty()) continue;
                int n = 0;
                while (n < 40 && y - n - 1 > yLo && level.getBlockState(p.set(x, y - n - 1, z)).isAir()) n++;
                if (n >= 6) {
                    ceil = y;
                    gap = n + 1;
                    break;
                }
            }
            if (ceil == Integer.MIN_VALUE) continue;
            float u = Noise.hash32(cx, cz, 340 + k, id);
            switch (type) {
                case 0 -> {
                    // the leak: the ceiling block turns source, falling fluid below it, stopping in mid-air
                    boolean lava = ceil < 0 && u < 0.5f || u < 0.12f;
                    BlockState src = (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState();
                    BlockState fall = src.setValue(LiquidBlock.LEVEL, 8);
                    level.setBlock(new BlockPos(x, ceil + 1, z), src, 2);
                    // short: frozen mid-fall, not a pillar (long columns from sky islands read as pillars: AdventurAgent)
                    // (a messy sky rift keeps the old long columns: DeepFeature.messiness)
                    float mess = DeepFeature.messiness(d);
                    int len = 2 + (int) (Noise.hash32(cx, cz, 350 + k, id) * (Math.min(6, gap - 4) * (1 - mess) + (gap - 4) * mess));
                    for (int i = 0; i < len; i++) level.setBlock(new BlockPos(x, ceil - i, z), fall, 2);
                    FALLS.incrementAndGet();
                    any = true;
                }
                case 1 -> {
                    // gravel (sand in some rifts) caught falling: 1-3 blocks with gaps, as if just let go
                    // (clear of the ceiling: right under it, it read as ordinary ceiling gravel, AdventurAgent)
                    BlockState g = (id & 4) == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.SAND.defaultBlockState();
                    int y = ceil - 2 - (int) (Noise.hash32(cx, cz, 355 + k, id) * 3);
                    int n = 1 + (int) (u * 3);
                    for (int i = 0; i < n && y > ceil - gap + 2; i++) {
                        level.setBlock(new BlockPos(x, y, z), g, 2);
                        y -= 1 + (int) (Noise.hash32(cx, cz, 360 + k * 7 + i, id) * 3);
                    }
                    GRAVEL.incrementAndGet();
                    any = true;
                }
                default -> {
                    // a volley: 4-7 arrows mid-flight, all one way, slightly spread
                    double ang = u * 2 * Math.PI, pitch = (Noise.hash32(cx, cz, 370 + k, id) - 0.6) * 0.6;
                    int n = 4 + (int) (Noise.hash32(cx, cz, 380 + k, id) * 4);
                    double vy = ceil - gap * 0.5;
                    for (int i = 0; i < n; i++) {
                        double ox = (Noise.hash32(i, cx, 390 + k, id) - 0.5) * 3, oy = (Noise.hash32(i, cz, 391 + k, id) - 0.5) * 2,
                            oz = (Noise.hash32(i, cx ^ cz, 392 + k, id) - 0.5) * 3;
                        BlockPos bp = BlockPos.containing(x + 0.5 + ox, vy + oy, z + 0.5 + oz);
                        if (!level.getBlockState(bp).isAir()
                            || (bp.getX() >> 4) != cx || (bp.getZ() >> 4) != cz) continue;
                        Arrow a = new Arrow(EntityTypes.ARROW, level.getLevel());
                        a.setPos(x + 0.5 + ox, vy + oy, z + 0.5 + oz);
                        a.shoot(Math.cos(ang) * Math.cos(pitch), Math.sin(pitch), Math.sin(ang) * Math.cos(pitch), 2.5f, 0);
                        a.pickup = net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup.ALLOWED;
                        level.addFreshEntity(a);
                    }
                    VOLLEYS.incrementAndGet();
                    any = true;
                }
            }
        }
        if (any) NewChunkGrace.exempt(level.getLevel(), net.minecraft.world.level.ChunkPos.pack(cx, cz));
        return any;
    }

    /** A crop's stage after the crash: random per position (any stage, ripe included). */
    public static BlockState cropAge(net.minecraft.world.level.block.CropBlock crop, BlockState st, BlockPos pos) {
        int max = crop.getMaxAge();
        int age = (int) (Noise.hash32(pos.getX(), pos.getY(), pos.getZ(), 0xC209) * (max + 1));
        return crop.getStateForAge(Math.min(max, age));
    }
}
