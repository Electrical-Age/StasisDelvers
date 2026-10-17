package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * End sites (Baughn 2026-10-17; notes/timestop/END-SITES-PLAN.md): the End's towers and frozen airships replace end
 * cities and ships (whose biome tag is emptied, so they no longer generate). Builders make them in creative, so the
 * sites go into worldgen now: each places the newest template under structure/&lt;prefix&gt;/ if there is one, else a
 * small placeholder marker to /locate and build on. Every site's StructureStart is saved in its chunk, so later a
 * patch can swap the markers of already generated sites for the real builds (phase 2).
 */
public final class EndSites {
    private EndSites() {
    }

    static final DeferredHolder<StructureType<?>, StructureType<Site>> TYPE =
        RuinedCottages.STRUCTURE_TYPES.register("end_site", () -> () -> Site.CODEC);
    static final DeferredHolder<StructurePieceType, StructurePieceType> MARKER =
        RuinedCottages.PIECES.register("end_site_marker", () -> (StructurePieceType.ContextlessType) Marker::new);

    /** Loads the class (its registrations) before the registers are attached to the mod bus. */
    static void init() {
    }

    public static final class Site extends Structure {
        public static final MapCodec<Site> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i),
            Codec.STRING.fieldOf("templates").forGetter(s -> s.prefix),
            Codec.BOOL.optionalFieldOf("air", false).forGetter(s -> s.air)
        ).apply(i, Site::new));

        final String prefix;
        final boolean air;

        public Site(StructureSettings settings, String prefix, boolean air) {
            super(settings);
            this.prefix = prefix;
            this.air = air;
        }

        @Override
        public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
            if (!context.couldValidBiomeExistOnTopOfChunkCenter()) return Optional.empty();
            int x = context.chunkPos().getMiddleBlockX(), z = context.chunkPos().getMiddleBlockZ();
            int top = context.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(),
                context.randomState());
            int y;
            if (air) y = Math.max(top + 24, 70 + context.random().nextInt(40));
            else if (top > context.heightAccessor().getMinY() + 40) y = top;
            else return Optional.empty();   // (a tower needs an island under it)
            List<Identifier> ts = context.structureTemplateManager().listTemplates()
                .filter(id -> id.getNamespace().equals(StasisDelvers.MODID) && id.getPath().startsWith(prefix + "/")).sorted().toList();
            Rotation rot = Rotation.getRandom(context.random());
            Identifier id = ts.isEmpty() ? null : ts.get(context.random().nextInt(ts.size()));
            BlockPos at = new BlockPos(x, y, z);
            return Optional.of(new GenerationStub(at, b -> {
                if (id == null) b.addPiece(new Marker(at, air));
                else {
                    var size = context.structureTemplateManager().getOrCreate(id).getSize(rot);
                    b.addPiece(new RuinedCottages.Piece(context.structureTemplateManager(), id,
                        new BlockPos(x - size.getX() / 2, y, z - size.getZ() / 2), rot));
                }
            }));
        }

        @Override
        public StructureType<?> type() {
            return TYPE.get();
        }
    }

    /**
     * The placeholder: a 5x5 pad (end stone bricks on an island, purpur floating in the air) with a crying-obsidian post
     * and an end rod on top, so builders can find the spot (/locate structure stasisdelvers:end_tower_site).
     */
    public static final class Marker extends StructurePiece {
        final BlockPos at;
        final boolean air;

        Marker(BlockPos at, boolean air) {
            super(MARKER.get(), 0, new BoundingBox(at.getX() - 2, at.getY(), at.getZ() - 2, at.getX() + 2, at.getY() + 5, at.getZ() + 2));
            this.at = at;
            this.air = air;
        }

        Marker(CompoundTag tag) {
            super(MARKER.get(), tag);
            this.at = new BlockPos(tag.getIntOr("MX", 0), tag.getIntOr("MY", 0), tag.getIntOr("MZ", 0));
            this.air = tag.getBooleanOr("Air", false);
        }

        @Override
        protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
            tag.putInt("MX", at.getX());
            tag.putInt("MY", at.getY());
            tag.putInt("MZ", at.getZ());
            tag.putBoolean("Air", air);
        }

        @Override
        public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox box,
                                ChunkPos chunkPos, BlockPos reference) {
            BlockState pad = (air ? Blocks.PURPUR_BLOCK : Blocks.END_STONE_BRICKS).defaultBlockState();
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) put(level, box, at.offset(dx, 0, dz), pad);
            for (int dy = 1; dy <= 4; dy++) put(level, box, at.above(dy), Blocks.CRYING_OBSIDIAN.defaultBlockState());
            put(level, box, at.above(5), Blocks.END_ROD.defaultBlockState());
        }

        static void put(WorldGenLevel level, BoundingBox box, BlockPos p, BlockState s) {
            if (box.isInside(p)) level.setBlock(p, s, 2);
        }
    }
}
