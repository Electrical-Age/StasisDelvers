package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelData;

/**
 * The starter cottage (Baughn 2026-10-13): instead of an arbitrary running spawn area, a new world gets a canned
 * cottage at world spawn: solar panels, a small tower, a basic charger. Its tower starts with a full store, so its field
 * is up from the first tick. Template: data/stasisdelvers/structure/spawn_cottage.nbt (a vanilla structure block
 * save); a data structure block with metadata "spawn" in it marks the spawn point (else the template's top centre).
 * Placed once per world (FieldData.cottage); nothing happens while the template doesn't exist.
 */
public final class SpawnCottage {
    private SpawnCottage() {
    }

    public static final Identifier TEMPLATE = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spawn_cottage");

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

    /** Places the cottage centred on spawn (x, z) on the surface; returns the new spawn point. */
    public static BlockPos place(ServerLevel level, StructureTemplate t, BlockPos around) {
        var size = t.getSize();
        int y = level.getChunk(around).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, around.getX(), around.getZ()) + 1;
        BlockPos origin = new BlockPos(around.getX() - size.getX() / 2, y, around.getZ() - size.getZ() / 2);
        StructurePlaceSettings settings = new StructurePlaceSettings();
        t.placeInWorld(level, origin, origin, settings, RandomSource.create(0), net.minecraft.world.level.block.Block.UPDATE_ALL);
        BlockPos spawnPos = origin.offset(size.getX() / 2, size.getY(), size.getZ() / 2);
        for (var info : t.filterBlocks(origin, settings, Blocks.STRUCTURE_BLOCK)) {
            if (info.nbt() == null) continue;
            if ("DATA".equals(info.nbt().getStringOr("mode", "")) && "spawn".equals(info.nbt().getStringOr("metadata", ""))) {
                spawnPos = info.pos();
                level.setBlock(info.pos(), Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
        // every tower in it starts full: its field comes up on its first tick
        for (int x = 0; x < size.getX(); x++)
            for (int yy = 0; yy < size.getY(); yy++)
                for (int z = 0; z < size.getZ(); z++)
                    if (level.getBlockEntity(origin.offset(x, yy, z)) instanceof TowerBlockEntity tower) tower.fillStore();
        level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, spawnPos, 0f, 0f));
        StasisDelvers.LOG.info("Spawn cottage placed at {} ({}), spawn {}", origin.toShortString(), size.toShortString(), spawnPos.toShortString());
        return spawnPos;
    }
}
