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
    static final int BLEND = 8;

    static void serverStarted(net.neoforged.neoforge.event.server.ServerStartedEvent e) {
        ServerLevel level = e.getServer().overworld();
        FieldData data = FieldData.get(level);
        if (data.cottageDone()) return;
        var template = level.getStructureTemplateManager().get(TEMPLATE);
        if (template.isEmpty()) return;
        data.markCottage();
        BlockPos spawn = level.getRespawnData().pos();
        place(level, template.get(), spawn);
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
        var size = t.getSize();
        CompoundTag eln = eln(level);
        int ground = eln != null && eln.contains("ground") ? eln.getIntOr("ground", 0) : 0;
        int[] site = findSite(level, around.getX() - size.getX() / 2, around.getZ() - size.getZ() / 2, size.getX(), size.getZ());
        BlockPos origin = new BlockPos(site[0], site[2] - ground, site[1]);
        // what was there: the surface round the site, for the blend (before the template changes it)
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

    /** {x0, z0, surface y}: the corner of the nearest flat dry site for a w x d footprint, from the generator's noise. */
    static int[] findSite(ServerLevel level, int x0, int z0, int w, int d) {
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        int[] best = null;
        int bestSpread = Integer.MAX_VALUE;
        for (int r = 0; r <= 160; r += 8)
            for (int dx = -r; dx <= r; dx += 8)
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;   // (the ring at r)
                    int cx = x0 + dx, cz = z0 + dz, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                    boolean dry = true;
                    for (int sx = -BLEND / 2; sx <= w + BLEND / 2 && dry; sx += 5)
                        for (int sz = -BLEND / 2; sz <= d + BLEND / 2; sz += 5) {
                            int top = gen.getBaseHeight(cx + sx, cz + sz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                            int land = gen.getBaseHeight(cx + sx, cz + sz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
                            if (top != land) {
                                dry = false;
                                break;
                            }
                            lo = Math.min(lo, top);
                            hi = Math.max(hi, top);
                        }
                    if (!dry) continue;
                    if (hi - lo < bestSpread) {
                        bestSpread = hi - lo;
                        best = new int[]{cx, cz, gen.getBaseHeight(cx + w / 2, cz + d / 2, Heightmap.Types.WORLD_SURFACE_WG, level, rs)};
                    }
                    if (bestSpread <= 3) return best;
                }
        if (best == null) best = new int[]{x0, z0, level.getHeight(Heightmap.Types.WORLD_SURFACE, x0 + w / 2, z0 + d / 2)};
        return best;
    }

    /**
     * The sides blend into the terrain like a low hill: in a ring BLEND wide round the footprint, each column's surface
     * eases from the site's ground (at its edge) to the land's (at the ring's outside), smoothstepped; earth added or
     * dug away to get there.
     */
    static void blend(ServerLevel level, BlockPos origin, int w, int d, int siteY) {
        for (int x = -BLEND; x < w + BLEND; x++)
            for (int z = -BLEND; z < d + BLEND; z++) {
                int ox = x < 0 ? -x : x >= w ? x - w + 1 : 0, oz = z < 0 ? -z : z >= d ? z - d + 1 : 0;
                double dist = Math.sqrt(ox * ox + oz * oz);
                if (dist == 0 || dist > BLEND) continue;
                int wx = origin.getX() + x, wz = origin.getZ() + z;
                int land = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                double t = dist / BLEND;
                t = t * t * (3 - 2 * t);
                int target = (int) Math.round(siteY + (land - siteY) * t);
                if (target > land) {
                    for (int y = land + 1; y <= target; y++)
                        level.setBlock(new BlockPos(wx, y, wz), (y == target ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 2);
                } else if (target < land) {
                    for (int y = target + 1; y <= land; y++) level.setBlock(new BlockPos(wx, y, wz), Blocks.AIR.defaultBlockState(), 2);
                    BlockPos top = new BlockPos(wx, target, wz);
                    if (level.getBlockState(top).is(Blocks.DIRT) || level.getBlockState(top).is(Blocks.STONE))
                        level.setBlock(top, Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                }
            }
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
