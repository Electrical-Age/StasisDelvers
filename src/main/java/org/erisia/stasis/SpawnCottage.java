package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelData;

/**
 * The spawn site (Baughn 2026-10-13; 2026-10-18 his own build on Incognito, as data/stasisdelvers/structure/
 * spawn_cottage.nbt): a new world gets it near world spawn, on flat dry land (as the ruined towers), its sides blended
 * into the terrain round it like a low hill. The template includes the ground under the build; EA's devices in it come
 * with their node data (data/stasisdelvers/spawn/spawn_cottage_eln.nbt, positions relative to the template: EA keeps
 * nodes outside the chunks, so a template alone gives dead blocks; stasis/tools/spawn/extract_eln.py). Its towers start
 * full and locked. Placed once per world (FieldData.cottage); nothing happens while the template doesn't exist.
 */
public final class SpawnCottage {
    private SpawnCottage() {
    }

    public static final Identifier TEMPLATE = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spawn_cottage");
    public static final Identifier ELN = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spawn/spawn_cottage_eln.nbt");
    /** Blocks over which the sides blend into the terrain round the site. */
    static final int BLEND = 16;

    static void serverStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        ServerLevel level = e.getServer().overworld();
        FieldData data = FieldData.get(level);
        if (data.cottageDone()) return;
        var template = level.getStructureTemplateManager().get(TEMPLATE);
        if (template.isEmpty()) return;
        data.markCottage();
        BlockPos spawn = level.getRespawnData().pos();
        place(level, template.get(), spawn, eln(level));
    }

    /** The EA data shipped with the template, or null. */
    static CompoundTag eln(ServerLevel level) {
        var r = level.getServer().getResourceManager().getResource(ELN);
        if (r.isEmpty()) return null;
        try (var in = r.get().open()) {
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (java.io.IOException ex) {
            StasisDelvers.LOG.error("Spawn site: can't read {}", ELN, ex);
            return null;
        }
    }

    /**
     * Places the site near spawn (x, z) and returns the new spawn point. The template's ground level is the median of
     * the heights of its border columns' top natural block; the site is the nearest spot (within 160 blocks) whose
     * generated surface over the footprint and its blend ring varies by at most 3 blocks and is dry.
     */
    public static BlockPos place(ServerLevel level, StructureTemplate t, BlockPos around) {
        return place(level, t, around, null);
    }

    /** eln: the EA data that goes with the template (eln()), or null. */
    public static BlockPos place(ServerLevel level, StructureTemplate t, BlockPos around, CompoundTag eln) {
        var size = t.getSize();
        int ground = eln != null && eln.contains("ground") ? eln.getIntOr("ground", 0) : 0;
        int[] site = findSite(level, around.getX() - size.getX() / 2, around.getZ() - size.getZ() / 2, size.getX(), size.getZ());
        BlockPos origin = new BlockPos(site[0], site[2] - ground, site[1]);
        // what was there: the surface round the site, for the blend (before the template changes it)
        // every chunk round the site generated first, a chunk beyond the blend too: an unloaded chunk's height reads as the
        // bottom of the world (a trench all round, in the lab), and a neighbour generated later puts its features and
        // terrain edge into the placed site (a stone ridge through the east edge)
        int margin = BLEND + 32;
        for (int cx = (origin.getX() - margin) >> 4; cx <= (origin.getX() + size.getX() + margin) >> 4; cx++)
            for (int cz = (origin.getZ() - margin) >> 4; cz <= (origin.getZ() + size.getZ() + margin) >> 4; cz++) level.getChunk(cx, cz);
        StructurePlaceSettings settings = new StructurePlaceSettings();
        t.placeInWorld(level, origin, origin, settings, RandomSource.create(0), net.minecraft.world.level.block.Block.UPDATE_ALL);
        // the template's top is above the ground; terrain higher than it inside the footprint goes
        for (int x = 0; x < size.getX(); x++)
            for (int z = 0; z < size.getZ(); z++) {
                int wx = origin.getX() + x, wz = origin.getZ() + z;
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                for (int y = origin.getY() + size.getY(); y < top; y++) level.setBlock(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState(), 2);
                // and under it, down to the land below (no floating site)
                for (int y = origin.getY() - 1; y > level.getMinY() && !solidGround(level.getBlockState(new BlockPos(wx, y, wz))); y--)
                    level.setBlock(new BlockPos(wx, y, wz), (origin.getY() - y <= 3 ? Blocks.DIRT : Blocks.STONE).defaultBlockState(), 2);
            }
        blend(level, origin, size.getX(), size.getZ(), origin.getY() + ground);
        if (eln != null) placeNodes(level, origin, eln);
        BlockPos spawnPos = origin.offset(size.getX() / 2, size.getY(), size.getZ() / 2);
        if (eln != null && eln.contains("spawn")) {
            int[] s = eln.getIntArray("spawn").orElse(new int[0]);
            if (s.length == 3) spawnPos = origin.offset(s[0], s[1], s[2]);
        }
        for (var info : t.filterBlocks(origin, settings, Blocks.STRUCTURE_BLOCK)) {
            if (info.nbt() == null) continue;
            if ("DATA".equals(info.nbt().getStringOr("mode", "")) && "spawn".equals(info.nbt().getStringOr("metadata", ""))) {
                spawnPos = info.pos();
                level.setBlock(info.pos(), Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
        // every tower in it starts full (its field comes up on its first tick) and locked (Baughn: no one changes the
        // spawn tower's settings; feeding it is fine)
        for (int x = 0; x < size.getX(); x++)
            for (int yy = 0; yy < size.getY(); yy++)
                for (int z = 0; z < size.getZ(); z++)
                    if (level.getBlockEntity(origin.offset(x, yy, z)) instanceof TowerBlockEntity tower) {
                        tower.locked = true;
                        tower.setChanged();
                        if (tower.getBlockState().getValue(TowerBlock.CELL) == TowerBlock.MASTER) tower.fillStore();
                    }
        level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, spawnPos, 0f, 0f));
        StasisDelvers.LOG.info("Spawn site placed at {} ({}), spawn {}", origin.toShortString(), size.toShortString(), spawnPos.toShortString());
        return spawnPos;
    }

    static boolean solidGround(BlockState s) {
        return !s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /**
     * {x0, z0, surface y}: the corner of the nearest flat dry site for a w x d footprint, from the generator's noise.
     * First in plains, meadow or forest (AdventurAgent: in a savanna the grass tint turned the build olive), then
     * anywhere.
     */
    static int[] findSite(ServerLevel level, int x0, int z0, int w, int d) {
        int[] s = findSite(level, x0, z0, w, d, true);
        return s != null ? s : findSite(level, x0, z0, w, d, false);
    }

    static boolean goodBiome(ServerLevel level, int x, int y, int z) {
        var b = level.getUncachedNoiseBiome(x >> 2, y >> 2, z >> 2);
        return b.is(net.minecraft.tags.BiomeTags.IS_FOREST) || b.is(net.minecraft.tags.BiomeTags.IS_TAIGA) || b.is(net.minecraft.world.level.biome.Biomes.PLAINS)
            || b.is(net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS) || b.is(net.minecraft.world.level.biome.Biomes.MEADOW);
    }

    static int[] findSite(ServerLevel level, int x0, int z0, int w, int d, boolean biome) {
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        int[] best = null;
        int bestSpread = Integer.MAX_VALUE;
        long t0 = System.nanoTime();
        for (int r = 0; r <= 160; r += 8)
            for (int dx = -r; dx <= r; dx += 8)
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // (the ring at r)
                    if (System.nanoTime() - t0 > 8_000_000_000L) return best;   // (a server tick: the watchdog kills it at 60 s)
                    int cx = x0 + dx, cz = z0 + dz, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    if (biome && !goodBiome(level, cx + w / 2, 64, cz + d / 2)) continue;
                    int mid = gen.getBaseHeight(cx + w / 2, cz + d / 2, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                    boolean dry = true;
                    for (int sx = -4; sx <= w + 4 && dry; sx += 8)
                        for (int sz = -4; sz <= d + 4; sz += 8) {
                            int top = gen.getBaseHeight(cx + sx, cz + sz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                            int land = gen.getBaseHeight(cx + sx, cz + sz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
                            if (top != land) {
                                dry = false;
                                break;
                            }
                            lo = Math.min(lo, top);
                            hi = Math.max(hi, top);
                            if (hi - lo >= bestSpread) {   // (no better than the best so far)
                                dry = false;
                                break;
                            }
                        }
                    if (!dry) continue;
                    if (hi - lo < bestSpread) {
                        bestSpread = hi - lo;
                        best = new int[]{cx, cz, mid};
                    }
                    if (bestSpread <= 3) return best;
                }
        if (!biome && best == null) best = new int[]{x0, z0, level.getHeight(Heightmap.Types.WORLD_SURFACE, x0 + w / 2, z0 + d / 2)};
        return biome && bestSpread > 6 ? null : best;
    }

    /** The ground's top block in a column: down from the highest motion-blocking block past logs, plants and leaves. */
    static int groundAt(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        var m = new BlockPos.MutableBlockPos(x, y, z);
        while (y > level.getMinY()) {
            BlockState s = level.getBlockState(m.setY(y));
            if (solidGround(s) && !s.is(net.minecraft.tags.BlockTags.LOGS) && !s.is(net.minecraft.tags.BlockTags.LEAVES)
                && s.isCollisionShapeFullBlock(level, m)) return y;
            y--;
        }
        return y;
    }

    /**
     * The sides blend into the terrain like a low hill (AdventurAgent: 8 blocks read as a pad with terraces and dirt
     * walls): in a ring BLEND wide round the footprint each column's ground eases from the site's level to the land's
     * (smoothstepped over the distance), then the target heights are smoothed with their neighbours so no step is
     * more than a block; earth is added (grass on top) or the land above dug away, trees there included.
     */
    static void blend(ServerLevel level, BlockPos origin, int w, int d, int siteY) {
        int n = w + 2 * BLEND, m = d + 2 * BLEND;

        int[][] land = new int[n][m];
        double[][] target = new double[n][m];
        boolean[][] ring = new boolean[n][m];
        for (int i = 0; i < n; i++)
            for (int k = 0; k < m; k++) {
                int x = i - BLEND, z = k - BLEND;
                int ox = x < 0 ? -x : x >= w ? x - w + 1 : 0, oz = z < 0 ? -z : z >= d ? z - d + 1 : 0;
                double dist = Math.sqrt(ox * ox + oz * oz);
                land[i][k] = groundAt(level, origin.getX() + x, origin.getZ() + z);
                if (dist == 0) target[i][k] = siteY;
                else if (dist >= BLEND) target[i][k] = land[i][k];
                else {
                    double t = dist / BLEND;
                    t = t * t * (3 - 2 * t);
                    target[i][k] = siteY + (land[i][k] - siteY) * t;
                    ring[i][k] = true;
                }
            }
        // smoothed (3 passes of a 3x3 mean over the ring; the footprint and the land beyond stay fixed)
        for (int pass = 0; pass < 3; pass++) {
            double[][] next = new double[n][m];
            for (int i = 0; i < n; i++)
                for (int k = 0; k < m; k++) {
                    if (!ring[i][k]) {
                        next[i][k] = target[i][k];
                        continue;
                    }
                    double sum = 0;
                    int c = 0;
                    for (int a = -1; a <= 1; a++)
                        for (int b = -1; b <= 1; b++) {
                            int ii = i + a, kk = k + b;
                            if (ii < 0 || kk < 0 || ii >= n || kk >= m) continue;
                            sum += target[ii][kk];
                            c++;
                        }
                    next[i][k] = sum / c;
                }
            target = next;
        }
        for (int i = 0; i < n; i++)
            for (int k = 0; k < m; k++) {
                if (!ring[i][k]) continue;
                int wx = origin.getX() + i - BLEND, wz = origin.getZ() + k - BLEND;
                int t = (int) Math.round(target[i][k]), l = land[i][k];
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                if (t >= l) {
                    for (int y = l + 1; y <= t; y++)
                        level.setBlock(new BlockPos(wx, y, wz), (y == t ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
                    if (t > l) for (int y = t + 1; y < top; y++) clearPlant(level, new BlockPos(wx, y, wz));
                } else {
                    for (int y = t + 1; y < Math.max(top, l + 1); y++) level.setBlock(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState(), 2);
                    level.setBlock(new BlockPos(wx, t, wz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
            }
    }

    /** Plants and trees standing where the ground was raised past them go (they'd be buried or float). */
    static void clearPlant(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        if (s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.canBeReplaced())
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
    }

    /** EA's nodes and ghosts of the site, moved to where the template went, into EA's node and ghost managers. */
    static void placeNodes(ServerLevel level, BlockPos origin, CompoundTag eln) {
        int dim = mods.eln.misc.Dimensions.id(level);
        CompoundTag nodes = new CompoundTag();
        var src = eln.getCompoundOrEmpty("nodes");
        for (String k : src.keySet()) {
            CompoundTag n = src.getCompoundOrEmpty(k).copy();
            move(n, origin, dim, "cx", "cy", "cz", "cd");
            if (n.contains("node")) {
                CompoundTag nd = n.getCompoundOrEmpty("node");
                move(nd, origin, dim, "cx", "cy", "cz", "cd");
                n.put("node", nd);
            }
            if (n.contains("legacy")) {
                CompoundTag lg = n.getCompoundOrEmpty("legacy");
                if (lg.contains("node")) {
                    CompoundTag nd = lg.getCompoundOrEmpty("node");
                    move(nd, origin, dim, "cx", "cy", "cz", "cd");
                    lg.put("node", nd);
                }
                n.put("legacy", lg);
            }
            for (String ek : n.keySet())
                if (ek.startsWith("ED") && n.get(ek) instanceof CompoundTag ed && ed.contains("lbCoordInstx")) {
                    move(ed, origin, dim, "lbCoordInstx", "lbCoordInsty", "lbCoordInstz", "lbCoordInstd");
                    n.put(ek, ed);
                }
            nodes.put(k, n);
        }
        CompoundTag ghosts = new CompoundTag();
        var gs = eln.getCompoundOrEmpty("ghost");
        for (String k : gs.keySet()) {
            CompoundTag g = gs.getCompoundOrEmpty(k).copy();
            move(g, origin, dim, "elemCoordx", "elemCoordy", "elemCoordz", "elemCoordd");
            move(g, origin, dim, "obserCoordx", "obserCoordy", "obserCoordz", "obserCoordd");
            ghosts.put(k, g);
        }
        mods.eln.Eln.ghostManager.loadFromNBT(ghosts);
        mods.eln.node.NodeManager.instance.loadFromNbt(nodes);
        StasisDelvers.LOG.info("Spawn site: {} EA nodes, {} ghosts", nodes.size(), ghosts.size());
    }

    static void move(CompoundTag t, BlockPos origin, int dim, String kx, String ky, String kz, String kd) {
        if (!t.contains(kx)) return;
        t.putInt(kx, t.getIntOr(kx, 0) + origin.getX());
        t.putInt(ky, t.getIntOr(ky, 0) + origin.getY());
        t.putInt(kz, t.getIntOr(kz, 0) + origin.getZ());
        if (t.contains(kd)) t.putInt(kd, dim);
    }
}
