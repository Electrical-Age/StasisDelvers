package org.erisia.stasis;

import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * Ruined cottages (Baughn 2026-10-17): places where someone tried to survive, failed, and their tower broke down.
 * Fixable but dead; about one per square kilometre (worldgen/structure_set/ruined_cottage.json), found with the map
 * desk's maps (structure tag stasisdelvers:on_cottage_maps). The templates are Baughn's builds: every
 * data/stasisdelvers/structure/ruined_cottage/*.nbt, picked at random; with none, nothing generates. Placed on dry
 * land only. Their tower cores never drop as cores (coreDrop 0: scrap) and come dead: off, store empty, no field; the
 * poles become survey poles and the tower is read anew on the spot (with a seed of its own).
 */
public final class RuinedCottages {
    private RuinedCottages() {
    }

    static final String PREFIX = "ruined_cottage/";

    static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, StasisDelvers.MODID);
    static final DeferredRegister<StructurePieceType> PIECES = DeferredRegister.create(Registries.STRUCTURE_PIECE, StasisDelvers.MODID);
    static final DeferredRegister<MapCodec<? extends StructureProcessor>> PROCESSORS =
        DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, StasisDelvers.MODID);

    static final DeferredHolder<StructureType<?>, StructureType<Cottage>> TYPE = STRUCTURE_TYPES.register("ruined_cottage", () -> () -> Cottage.CODEC);
    static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE =
        PIECES.register("ruined_cottage", () -> (StructurePieceType.StructureTemplateType) Piece::new);
    static final DeferredHolder<MapCodec<? extends StructureProcessor>, MapCodec<RuinedCore>> RUINED_CORE =
        PROCESSORS.register("ruined_core", () -> RuinedCore.CODEC);

    static void register(net.neoforged.bus.api.IEventBus modBus) {
        STRUCTURE_TYPES.register(modBus);
        PIECES.register(modBus);
        PROCESSORS.register(modBus);
    }

    /** The cottage templates there are (Baughn's builds), sorted so a seed always picks the same one. */
    public static List<Identifier> templates(StructureTemplateManager m) {
        // cached (a map search asks for every candidate chunk, and listing all templates each time hung incognito's
        // server twice, 2026-10-17); refreshed every minute, so new templates show up without a restart
        Cache c = cache;
        long now = System.nanoTime();
        if (c == null || c.manager != m || now - c.at > 60_000_000_000L) {
            c = new Cache(m, now, m.listTemplates().filter(id -> id.getNamespace().equals(StasisDelvers.MODID) && id.getPath().startsWith(PREFIX))
                .sorted().toList());
            cache = c;
        }
        return c.list;
    }

    private record Cache(StructureTemplateManager manager, long at, List<Identifier> list) {
    }

    private static volatile Cache cache;

    /** Are there cottage templates at all (else nothing generates, and maps can't find any)? */
    public static boolean dormant(net.minecraft.server.level.ServerLevel level) {
        return templates(level.getStructureTemplateManager()).isEmpty();
    }

    public static final class Cottage extends Structure {
        public static final MapCodec<Cottage> CODEC = simpleCodec(Cottage::new);

        public Cottage(StructureSettings settings) {
            super(settings);
        }

        @Override
        public Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
            List<Identifier> ts = templates(context.structureTemplateManager());
            if (ts.isEmpty() || !context.couldValidBiomeExistOnTopOfChunkCenter()) return Optional.empty();
            int x = context.chunkPos().getMiddleBlockX(), z = context.chunkPos().getMiddleBlockZ();
            var gen = context.chunkGenerator();
            int land = gen.getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
            int top = gen.getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
            if (top != land) return Optional.empty();   // (water over it: dry land only)
            Identifier id = ts.get(context.random().nextInt(ts.size()));
            Rotation rot = Rotation.getRandom(context.random());
            return Optional.of(new GenerationStub(new BlockPos(x, top, z), b -> {
                var t = context.structureTemplateManager().getOrCreate(id);
                var size = t.getSize(rot);
                b.addPiece(new Piece(context.structureTemplateManager(), id, new BlockPos(x - size.getX() / 2, top, z - size.getZ() / 2), rot));
            }));
        }

        @Override
        public StructureType<?> type() {
            return TYPE.get();
        }
    }

    public static final class Piece extends TemplateStructurePiece {
        Piece(StructureTemplateManager m, Identifier id, BlockPos pos, Rotation rot) {
            super(PIECE.get(), 0, m, id, id.toString(), settings(rot), pos);
        }

        Piece(StructureTemplateManager m, CompoundTag tag) {
            super(PIECE.get(), tag, m, id -> settings(tag.read("Rot", Rotation.LEGACY_CODEC).orElse(Rotation.NONE)));
        }

        public static StructurePlaceSettings settings(Rotation rot) {
            return new StructurePlaceSettings().setRotation(rot).setMirror(Mirror.NONE)
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK).addProcessor(RuinedCore.INSTANCE);
        }

        @Override
        protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
            super.addAdditionalSaveData(context, tag);
            tag.store("Rot", Rotation.LEGACY_CODEC, this.placeSettings.getRotation());
        }

        @Override
        protected void handleDataMarker(String marker, BlockPos pos, ServerLevelAccessor level, RandomSource random, BoundingBox box) {
        }
    }

    /** A ruined cottage's tower core: dead (off, empty, no field) and never dropping as a core (Baughn: 0%, scrap). */
    public static final class RuinedCore implements StructureProcessor {
        static final RuinedCore INSTANCE = new RuinedCore();
        public static final MapCodec<RuinedCore> CODEC = MapCodec.unit(INSTANCE);

        @Override
        public StructureTemplate.@Nullable StructureBlockInfo process(LevelReader level, BlockPos target, BlockPos reference,
                StructureTemplate.StructureBlockInfo original, StructureTemplate.StructureBlockInfo current, StructurePlaceSettings settings,
                @Nullable StructureTemplate template) {
            if (current == null) return current;
            // the template's poles: plain survey poles again, so the tower is read anew where it stands
            if (current.state().is(StasisRegistry.TOWER_SEGMENT.get()))
                return new StructureTemplate.StructureBlockInfo(current.pos(), current.state().setValue(TowerPoleBlock.PART, TowerPoleBlock.Part.SURVEY), current.nbt());
            if (!current.state().is(StasisRegistry.TOWER.get())) return current;
            CompoundTag nbt = current.nbt() == null ? new CompoundTag() : current.nbt().copy();
            nbt.putDouble("coreDrop", 0);
            nbt.putString("state", "OFF");
            nbt.putInt("field", 0);
            nbt.putInt("energy", 0);
            // forget the build where the template was made (absolute pole positions); each site gets its own look
            nbt.remove("parted");
            nbt.remove("layout");
            nbt.putString("tstatus", "NONE");
            nbt.putInt("ladder", -1);
            nbt.putInt("seed", (int) net.minecraft.util.Mth.getSeed(current.pos().getX(), 0, current.pos().getZ()));
            return new StructureTemplate.StructureBlockInfo(current.pos(), current.state(), nbt);
        }

        @Override
        public MapCodec<? extends StructureProcessor> codec() {
            return CODEC;
        }
    }
}
