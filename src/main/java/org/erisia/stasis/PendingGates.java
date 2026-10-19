package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
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
     * centre Gates.portalRoom takes in stopped mode, so hard mode can hand it on as it is.
     */
    public record Room(BoundingBox box, BlockPos centre) {
        static final Codec<Room> CODEC = RecordCodecBuilder.create(i -> i.group(
            BoundingBox.CODEC.fieldOf("box").forGetter(Room::box),
            BlockPos.CODEC.fieldOf("centre").forGetter(Room::centre)).apply(i, Room::new));
    }

    /** Portal rooms that generated (worldgen threads), for the server thread to record. */
    static final ConcurrentLinkedQueue<Object[]> PENDING = new ConcurrentLinkedQueue<>();

    /** From the portal room mixin (a worldgen thread), in spreading mode. */
    public static void portalRoom(ServerLevel level, BoundingBox box, BlockPos centre) {
        PENDING.add(new Object[]{level, new Room(box, centre.immutable())});
    }

    static void tick(ServerTickEvent.Post e) {
        Object[] o;
        while ((o = PENDING.poll()) != null) {
            ServerLevel level = (ServerLevel) o[0];
            Room r = (Room) o[1];
            Data d = Data.get(level);
            if (d.rooms.stream().anyMatch(x -> x.centre().equals(r.centre()))) continue;
            d.rooms.add(r);
            d.setDirty();
            StasisDelvers.LOG.info("Stronghold portal room at {} recorded for hard mode", r.centre().toShortString());
        }
    }

    /** This level's recorded portal rooms (server thread). */
    public static List<Room> list(ServerLevel level) {
        return List.copyOf(Data.get(level).rooms);
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
