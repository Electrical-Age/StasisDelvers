package org.erisia.stasis;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Rifts as gates (Baughn, 2026-10-16; rift stage 1b): each static rift leads somewhere, by weight (config
 * riftDestinations; default the End 2 : the Nether 1). Ores stay overworld everywhere, but a rift to another place
 * has some of that place's plain blocks scattered in its shell and strata (config riftScatter; the Nether:
 * netherrack, soul sand). Vanilla End portals are gone: a stronghold's portal room gets no frame, and a gate rift
 * (always to the End) stands where the portal would have been. Gate rifts are extra static deeps, kept per level
 * (saved; sent to clients) since they're only known once the room generates. The gate itself (going through) comes
 * later, with the lance.
 */
public final class Gates {
    private Gates() {
    }

    /** A gate rift's radius: its core (0.35 r, ~3 blocks) sits over the old frame, so the wrecked room around it shows
     *  (at 16 the black swallowed most of it: AdventurAgent). */
    static final float GATE_RADIUS = 9;

    // ---- destinations and scatter (config)

    record Dest(String id, int weight) {
    }

    static volatile List<Dest> destinations = List.of(new Dest("minecraft:the_end", 2), new Dest("minecraft:the_nether", 1));
    static volatile Map<String, List<String>> scatterNames = Map.of("minecraft:the_nether", List.of("minecraft:netherrack", "minecraft:soul_sand"));
    static volatile Map<String, List<BlockState>> scatter;

    static void configure(List<? extends String> dests, List<? extends String> scatterSpec) {
        List<Dest> l = new ArrayList<>();
        for (String s : dests) {
            int c = s.lastIndexOf(':');
            try {
                if (c > 0 && s.indexOf(':') < c) l.add(new Dest(s.substring(0, c), Math.max(0, Integer.parseInt(s.substring(c + 1)))));
                else l.add(new Dest(s, 1));
            } catch (NumberFormatException e) {
                StasisDelvers.LOG.warn("riftDestinations: bad entry {}", s);
            }
        }
        if (l.stream().mapToInt(Dest::weight).sum() == 0) l = List.of(new Dest("minecraft:the_end", 1));
        destinations = List.copyOf(l);
        Map<String, List<String>> m = new HashMap<>();
        for (String s : scatterSpec) {
            int e = s.indexOf('=');
            if (e > 0) m.put(s.substring(0, e).trim(), List.of(s.substring(e + 1).split("\\s*,\\s*")));
        }
        scatterNames = Map.copyOf(m);
        scatter = null;
    }

    /** Where static deep d's rift leads (a dimension id). Gate rifts always lead to the End. */
    public static String destination(StaticDeeps.Deep d) {
        if (StaticDeeps.isGate(d)) return "minecraft:the_end";
        List<Dest> l = destinations;
        int total = l.stream().mapToInt(Dest::weight).sum();
        float u = Noise.hash32(d.id(), 0, 51, 0x6A7E) * total;
        for (Dest x : l) {
            if (u < x.weight()) return x.id();
            u -= x.weight();
        }
        return l.get(l.size() - 1).id();
    }

    /** A scattered block of deep d's destination at (x, y, z), in patches (about rate of the rock), or null. */
    static BlockState scatter(StaticDeeps.Deep d, int x, int y, int z) {
        Map<String, List<BlockState>> m = scatter;
        if (m == null) {
            Map<String, List<BlockState>> r = new HashMap<>();
            scatterNames.forEach((dim, names) -> {
                List<BlockState> bs = new ArrayList<>();
                for (String n : names) {
                    Identifier id = Identifier.tryParse(n);
                    Block b = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
                    if (b != null && b != net.minecraft.world.level.block.Blocks.AIR) bs.add(b.defaultBlockState());
                    else StasisDelvers.LOG.warn("riftScatter: unknown block {}", n);
                }
                if (!bs.isEmpty()) r.put(dim, List.copyOf(bs));
            });
            scatter = m = r;
        }
        List<BlockState> bs = m.get(destination(d));
        if (bs == null) return null;
        // patches: a coarse noise picks the places, a fine hash thins them out
        if (Noise.value32(x / 6f, y / 6f, z / 6f, d.id() ^ 0x5CA7) < 1 - 5 * StasisConfig.riftScatterRate) return null;
        if (Noise.hash32(x, y, z, d.id() ^ 0x5CA8) < 0.35f) return null;
        return bs.get((int) (Noise.value32(x / 9f + 17, y / 9f, z / 9f, d.id() ^ 0x5CA9) * bs.size()) % bs.size());
    }

    /** Tests: scatter(). */
    public static BlockState scatterForTest(StaticDeeps.Deep d, int x, int y, int z) {
        return scatter(d, x, y, z);
    }

    // ---- gate rifts in stronghold portal rooms

    /** A gate rift's id (24 bits) from its centre block. */
    static int gateId(BlockPos p) {
        return (int) (Noise.hash32(p.getX(), p.getY(), p.getZ(), 0x6A7E) * 0xFFFFFF) | 1;
    }

    /** Tests: gate(). */
    public static StaticDeeps.Deep gateForTest(BlockPos p) {
        return gate(p);
    }

    static StaticDeeps.Deep gate(BlockPos p) {
        return new StaticDeeps.Deep(p.getX() + 0.5f, p.getY() + 0.5f, p.getZ() + 0.5f, GATE_RADIUS, gateId(p));
    }

    /** Portal rooms that generated (worldgen threads), for the server thread to record. */
    static final ConcurrentLinkedQueue<Object[]> PENDING = new ConcurrentLinkedQueue<>();

    /** From the portal room mixin (a worldgen thread): a gate rift stands at centre. */
    public static void portalRoom(ServerLevel level, BlockPos centre) {
        PENDING.add(new Object[]{level, centre.immutable()});
    }

    static void tick(ServerTickEvent.Post e) {
        Object[] o;
        while ((o = PENDING.poll()) != null) {
            register((ServerLevel) o[0], (BlockPos) o[1]);
        }
    }

    /**
     * Server thread: a gate rift stands at centre from now on (saved, into StaticDeeps, sent to the level's players).
     * False if it was known. HardRifts registers spreading mode's portal rooms at once at hard mode: isGate (its
     * destination, the End, for the scatter) must answer before the rift is carved.
     */
    static boolean register(ServerLevel level, BlockPos p) {
        Data d = Data.get(level);
        if (d.gates.contains(p.asLong())) return false;
        d.gates.add(p.asLong());
        d.setDirty();
        StasisDelvers.LOG.info("A gate rift stands in the stronghold portal room at {}", p.toShortString());
        publish(level);
        for (var pl : level.players()) StasisNet.sendGates(pl);
        return true;
    }

    /** Tests: forget the gate rift at centre (and tell StaticDeeps and the players). */
    public static void testForget(ServerLevel level, BlockPos p) {
        Data d = Data.get(level);
        if (!d.gates.remove(p.asLong())) return;
        d.setDirty();
        publish(level);
        for (var pl : level.players()) StasisNet.sendGates(pl);
    }

    /** The level's gate rifts into StaticDeeps (server side: on load and when one is added). */
    static void publish(ServerLevel level) {
        if (!Stasis.stops(level)) return;
        StaticDeeps.setGates(FieldData.get(level).deepSeed(), Data.get(level).gates);
    }

    /** This level's gate rift centres (packed block positions), for the client. */
    public static List<Long> list(ServerLevel level) {
        return List.copyOf(Data.get(level).gates);
    }

    public static final class Data extends SavedData {
        static final Codec<Data> CODEC = Codec.LONG.listOf().xmap(Data::new, d -> new ArrayList<>(d.gates));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "rift_gates"), Data::new, CODEC);

        final List<Long> gates = new ArrayList<>();

        public Data() {
        }

        private Data(List<Long> l) {
            gates.addAll(l);
        }

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }
    }
}
