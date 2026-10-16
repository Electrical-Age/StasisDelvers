package org.erisia.stasis;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

import java.util.List;

/**
 * Swallowed ruins (Baughn: "how much without constructing new structures?" - all of it): fragments of other times,
 * half sunk in a rift's strata. Vanilla's own structure pieces (village houses of every biome, shipwrecks, fossils)
 * are stamped into the strata at hashed spots, 1-2 per rift, and the rock swallows them: a noise cut, tilted
 * towards one side, decides which of their blocks survive (the rest stays riftstone, or the rock fills their rooms).
 * Their chests keep vanilla loot tables (shipwrecks' via their data markers). Deterministic per rift; each chunk
 * places its own slice. Not in gate rifts.
 */
public final class Ruins {
    private Ruins() {
    }

    static volatile List<Identifier> POOL;
    /** Chunk slices placed since start (for /stasis strata). */
    public static final java.util.concurrent.atomic.AtomicInteger SLICES = new java.util.concurrent.atomic.AtomicInteger();

    static List<Identifier> pool(WorldGenLevel level) {
        List<Identifier> p = POOL;
        if (p == null) {
            p = level.getLevel().getStructureTemplateManager().listTemplates()
                .filter(id -> id.getNamespace().equals("minecraft") && (
                    id.getPath().matches("village/[a-z]+/houses/[a-z_]+house_\\d+")
                        || id.getPath().matches("village/[a-z]+/houses/[a-z]+_(library|temple|armorer|butcher_shop|cartographer|fisher|fletcher|mason|shepherd|tannery|tool_smith|weaponsmith)[a-z_]*_\\d+")
                        || id.getPath().startsWith("shipwreck/") || id.getPath().startsWith("fossil/") && !id.getPath().contains("coal")))
                .sorted(java.util.Comparator.comparing(Identifier::toString)).toList();
            POOL = p;
            StasisDelvers.LOG.info("Swallowed ruins: {} vanilla templates in the pool", p.size());
        }
        return p;
    }

    record Ruin(Identifier id, BlockPos origin, Rotation rot, int salt) {
    }

    /** Each deep's ruins, cached (every chunk of the rift asks). */
    static final java.util.Map<Long, List<Ruin>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Deep d's ruins: 1-2 spots in its strata rock (a body or cavity cell away from the rift), underground. */
    static List<Ruin> ruins(WorldGenLevel level, StaticDeeps.Deep d) {
        List<Identifier> pool = pool(level);
        if (pool.isEmpty() || StaticDeeps.isGate(d)) return List.of();
        long key = (long) d.id() << 32 ^ level.getLevel().dimension().hashCode();
        List<Ruin> cached = CACHE.get(key);
        if (cached != null) return cached;
        List<Ruin> out = new java.util.ArrayList<>(2);
        int n = 1 + (Noise.hash32(d.id(), 1, 401, 0x7A1) < 0.4f ? 1 : 0);
        Strata.Bounds b = Strata.bounds(d);
        var sl = level.getLevel();
        int lo = Math.max(b.minY(), level.getMinY() + 8);
        for (int r = 0; r < n; r++)
            for (int tryi = 0; tryi < 60; tryi++) {
                double a = Noise.hash32(d.id(), r, 402 + tryi, 0x7A1) * 2 * Math.PI;
                double rad = (0.45 + 0.5 * Noise.hash32(d.id(), r, 512 + tryi, 0x7A1)) * b.radius();
                int x = (int) Math.floor(d.x() + Math.cos(a) * rad), z = (int) Math.floor(d.z() + Math.sin(a) * rad);
                int hi = Math.min(b.maxY(), (int) d.y() + (int) d.radius());
                if (hi <= lo) break;
                int y = lo + (int) (Noise.hash32(d.id(), r, 622 + tryi, 0x7A1) * (hi - lo));
                int k = Strata.at(d, x, y, z);
                if ((k != Strata.BODY && k != Strata.CAVITY) || StaticDeeps.shape(d, x, y, z) < 0.6f) continue;
                // underground (the generator's own surface, the same from every chunk): a sky rift's strata is air
                // there, and its ruins hung in the sky (AdventurAgent)
                int surf = sl.getChunkSource().getGenerator().getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,
                    sl, sl.getChunkSource().randomState());
                if (y + 10 > surf) continue;
                final int fx = x, fy = y, fz = z;
                if (out.stream().anyMatch(o -> o.origin().distSqr(new BlockPos(fx, fy, fz)) < 24 * 24)) continue;   // apart
                Identifier id = pool.get((int) (Noise.hash32(d.id(), r, 432, 0x7A1) * pool.size()) % pool.size());
                Rotation rot = Rotation.values()[(int) (Noise.hash32(d.id(), r, 433, 0x7A1) * 4) & 3];
                out.add(new Ruin(id, new BlockPos(x, y, z), rot, d.id() * 31 + r));
                break;
            }
        if (CACHE.size() > 512) CACHE.clear();
        CACHE.put(key, List.copyOf(out));
        return out;
    }

    /** The rock swallows the template: per block, a tilted noise cut decides whether it survives. */
    record Swallow(int salt, BlockPos centre, double dx, double dy, double dz, double size) implements StructureProcessor {
        @Override
        public StructureTemplate.StructureBlockInfo processBlock(LevelReader level, BlockPos targetPosition, BlockPos referencePos,
                                                                  BlockPos templateRelativePos, StructureTemplate.StructureBlockInfo info,
                                                                  StructurePlaceSettings settings) {
            BlockPos p = info.pos();
            double side = ((p.getX() - centre.getX()) * dx + (p.getY() - centre.getY()) * dy + (p.getZ() - centre.getZ()) * dz) / size;
            float n = Noise.fbm32(p.getX() / 3.5f, p.getY() / 3.5f, p.getZ() / 3.5f, salt);
            boolean keep = n + 0.45 * side > 0.5;
            if (keep) return info;
            // swallowed: where the template had air, the rock stays (a filled room); where it had a block, the rock too
            return null;
        }

        @Override
        public MapCodec<? extends StructureProcessor> codec() {
            return MapCodec.unit(this);
        }
    }

    /** For /stasis rifts: ", ruins: template at x y z; ...". */
    public static String describe(net.minecraft.server.level.ServerLevel level, StaticDeeps.Deep d) {
        if (!StasisConfig.ruins) return "";
        StringBuilder sb = new StringBuilder();
        for (Ruin r : ruins(level, d))
            sb.append(sb.length() == 0 ? ", ruins: " : "; ").append(r.id().getPath()).append(" at ").append(r.origin().toShortString());
        return sb.toString();
    }

    /** Place this chunk's slice of deep d's ruins. */
    public static boolean place(WorldGenLevel level, StaticDeeps.Deep d, int x0, int z0) {
        boolean any = false;
        BoundingBox chunk = new BoundingBox(x0, level.getMinY(), z0, x0 + 15, level.getMaxY(), z0 + 15);
        for (Ruin r : ruins(level, d)) {
            var t = level.getLevel().getStructureTemplateManager().get(r.id()).orElse(null);
            if (t == null) continue;
            Vec3i size = t.getSize(r.rot());
            BlockPos origin = r.origin().offset(-size.getX() / 2, -size.getY() / 2, -size.getZ() / 2);
            StructurePlaceSettings probe = new StructurePlaceSettings().setRotation(r.rot());
            BoundingBox bb = t.getBoundingBox(probe, origin);
            if (!bb.intersects(chunk)) continue;
            double a = Noise.hash32(r.salt(), 0, 441, 0x7A1) * 2 * Math.PI;
            Swallow sw = new Swallow(r.salt(), bb.getCenter(), Math.cos(a) * 0.8, 0.6, Math.sin(a) * 0.8,
                Math.max(4, Math.max(size.getX(), size.getZ()) / 2.0));
            StructurePlaceSettings s = new StructurePlaceSettings().setRotation(r.rot()).setBoundingBox(chunk)
                .addProcessor(JigsawReplacementProcessor.INSTANCE).addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK).addProcessor(sw);
            RandomSource rnd = RandomSource.create(r.salt() * 0x9E3779B97F4A7C15L + x0 * 31L + z0);
            t.placeInWorld(level, origin, origin, s, rnd, 2);
            // shipwrecks' chests: the data markers above them name their loot
            for (var m : t.filterBlocks(origin, new StructurePlaceSettings().setRotation(r.rot()), Blocks.STRUCTURE_BLOCK)) {
                if (!chunk.isInside(m.pos()) || m.nbt() == null) continue;
                if (StructureMode.valueOf(m.nbt().getStringOr("mode", "DATA")) != StructureMode.DATA) continue;
                var loot = switch (m.nbt().getStringOr("metadata", "")) {
                    case "map_chest" -> BuiltInLootTables.SHIPWRECK_MAP;
                    case "treasure_chest" -> BuiltInLootTables.SHIPWRECK_TREASURE;
                    case "supply_chest" -> BuiltInLootTables.SHIPWRECK_SUPPLY;
                    default -> null;
                };
                if (loot != null && level.getBlockState(m.pos().below()).is(Blocks.CHEST))
                    RandomizableContainer.setBlockEntityLootTable(level, rnd, m.pos().below(), loot);
            }
            SLICES.incrementAndGet();
            StasisDelvers.LOG.debug("ruin {} at {}", r.id(), r.origin());
            any = true;
        }
        return any;
    }
}
