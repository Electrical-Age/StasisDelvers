package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stronghold portal rooms waiting for hard mode (spread-DESIGN.md, step 3; Baughn 2026-10-19). In spreading mode the
 * room stays a working End portal room until the dragon dies; then hard mode turns each one into a gate room and a full
 * gate rift (Baughn's decision 6). Worldgen can't be re-run, so each room is recorded as it generates: the whole piece's
 * box (what hard mode rewrites) and its gate centre. Same hand-off as Gates: worldgen threads queue, the server thread
 * records, deduplicated by centre (postProcess runs once per chunk the piece touches). SavedData
 * stasisdelvers:pending_gates, per level.
 */
public final class PendingGates {
    private PendingGates() {
    }

    /**
     * A recorded portal room. centre is the piece's local (5, 4, 10): the block above the portal's middle, the same
     * centre Gates.portalRoom takes in stopped mode, so hard mode can hand it on as it is. dir: the piece's orientation
     * (GateRoom.shatter rebuilds the piece from box and dir; saves from before it was kept derive it, orientationOf).
     * Hard mode's progress (HardRifts), per chunk (packed ChunkPos): shattered, the room's part written (at hard mode,
     * or at worldgen for chunks that generated after it); carved, the gate rift's carve written. Mutable, server thread.
     */
    public record Room(BoundingBox box, BlockPos centre, Direction dir, Set<Long> shattered, Set<Long> carved) {
        public Room(BoundingBox box, BlockPos centre, Direction dir) {
            this(box, centre, dir, new HashSet<>(), new HashSet<>());
        }

        static final Codec<Set<Long>> CHUNKS = Codec.LONG.listOf().xmap(HashSet::new, ArrayList::new);
        static final Codec<Room> CODEC = RecordCodecBuilder.create(i -> i.group(
            BoundingBox.CODEC.fieldOf("box").forGetter(Room::box),
            BlockPos.CODEC.fieldOf("centre").forGetter(Room::centre),
            Direction.CODEC.optionalFieldOf("dir").forGetter(r -> Optional.ofNullable(r.dir())),
            CHUNKS.optionalFieldOf("shattered", Set.of()).forGetter(Room::shattered),
            CHUNKS.optionalFieldOf("carved", Set.of()).forGetter(Room::carved)).apply(i, (b, c, d, s, k) ->
            new Room(b, c, d.orElseGet(() -> orientationOf(b, c)), new HashSet<>(s), new HashSet<>(k))));

        /** The gate rift's deep (Gates.gate: radius GATE_RADIUS round the centre). */
        public StaticDeeps.Deep deep() {
            return Gates.gate(centre);
        }
    }

    /**
     * The orientation of a vanilla portal room (11 x 8 x 16) from its box and centre (StructurePiece.getWorldX/Z: local
     * (5, _, 10) is minZ + 10 facing south, maxZ - 10 north, minX + 10 east, maxX - 10 west).
     */
    public static Direction orientationOf(BoundingBox b, BlockPos c) {
        if (b.getXSpan() == 11) return c.getZ() - b.minZ() == 10 ? Direction.SOUTH : Direction.NORTH;
        return c.getX() - b.minX() == 10 ? Direction.EAST : Direction.WEST;
    }

    /** Portal rooms that generated (worldgen threads), for the server thread to record. */
    static final ConcurrentLinkedQueue<Object[]> PENDING = new ConcurrentLinkedQueue<>();

    /** From the portal room mixin (a worldgen thread), in spreading mode before hard mode. */
    public static void portalRoom(ServerLevel level, BoundingBox box, BlockPos centre, Direction dir) {
        // (vanilla always orients stronghold pieces; getOrientation is nullable all the same)
        PENDING.add(new Object[]{level, new Room(box, centre.immutable(), dir == null ? orientationOf(box, centre) : dir)});
    }

    /**
     * Portal room chunks that generated after hard mode began (the mixin gave them stopped mode's gate room at worldgen):
     * if their room was recorded before (a stronghold straddling the instant), HardRifts must not shatter them again.
     */
    static final ConcurrentLinkedQueue<Object[]> SHATTERED = new ConcurrentLinkedQueue<>();

    /** From the portal room mixin (a worldgen thread), in spreading mode after hard mode: chunk got its room at worldgen. */
    public static void shatteredAtWorldgen(ServerLevel level, BlockPos centre, long chunk) {
        SHATTERED.add(new Object[]{level, centre.immutable(), chunk});
    }

    /** Record the rooms worldgen queued (and, in hard mode, hand them to HardRifts at once), then note worldgen's shatters. */
    static void drain(MinecraftServer srv) {
        Object[] o;
        while ((o = PENDING.poll()) != null) {
            ServerLevel level = (ServerLevel) o[0];
            Room r = (Room) o[1];
            Data d = Data.get(level);
            if (find(d, r.centre()) != null) continue;
            d.rooms.add(r);
            d.setDirty();
            StasisDelvers.LOG.info("Stronghold portal room at {} recorded for hard mode", r.centre().toShortString());
            // (generated in the moment before the worldgen threads saw hard mode begin)
            if (Spread.hard(srv)) HardRifts.activate(level, r);
        }
        while ((o = SHATTERED.poll()) != null) {
            Data d = Data.get((ServerLevel) o[0]);
            Room r = find(d, (BlockPos) o[1]);
            if (r != null && r.shattered().add((Long) o[2])) d.setDirty();
        }
    }

    static Room find(Data d, BlockPos centre) {
        for (Room r : d.rooms) if (r.centre().equals(centre)) return r;
        return null;
    }

    /** This level's recorded portal rooms (server thread; the rooms themselves are live: HardRifts' progress). */
    public static List<Room> list(ServerLevel level) {
        return List.copyOf(Data.get(level).rooms);
    }

    /** Tests: the room at centre, or null. */
    public static Room testRoom(ServerLevel level, BlockPos centre) {
        return find(Data.get(level), centre);
    }

    /**
     * Tests: a restart, as far as the progress goes: the level's saved data is written through its codec and read back
     * into a new instance, which replaces the old one (HardRifts then works from the copy).
     */
    public static void testReload(ServerLevel level) {
        var ops = level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        var tag = Data.CODEC.encodeStart(ops, Data.get(level)).getOrThrow();
        Data copy = Data.CODEC.parse(ops, tag).getOrThrow();
        level.getDataStorage().set(Data.TYPE, copy);
    }

    /** Tests: forget the room at centre. */
    public static void testForget(ServerLevel level, BlockPos centre) {
        Data d = Data.get(level);
        if (d.rooms.removeIf(x -> x.centre().equals(centre))) d.setDirty();
    }

    public static final class Data extends SavedData {
        static final Codec<Data> CODEC = Room.CODEC.listOf().xmap(Data::new, d -> new ArrayList<>(d.rooms));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "pending_gates"), Data::new, CODEC);

        final List<Room> rooms = new ArrayList<>();

        public Data() {
        }

        private Data(List<Room> l) {
            rooms.addAll(l);
        }

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }
    }
}
