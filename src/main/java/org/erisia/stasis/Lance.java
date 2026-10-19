package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The rift lance, L1: travel through an opened gate (notes/rifts/LANCE-v2.md with Argon's review and Baughn's decisions
 * of 2026-10-19; docs/lance.md). Travel comes first; the multiblock, its power and the breach come in L2, so for now a
 * test command opens and closes gates (/stasis lance open|close|list).
 * <ul>
 * <li>Gate state per rift ({@link GateData}, in the rift's level, keyed by the deep's id): CLOSED or OPEN (the latched
 * gate disc, the rift held at {@link #OPEN_SCALE} of its radius). L1 keeps the 25 % as server state only: the client still
 * draws the whole rift. Freezing spares a player outside the held rift and in the disc ({@link #spares}).</li>
 * <li>A player stepping into an open gate's centre (within {@link #REACH} of the core, not merely touching the rift) goes
 * to the destination ({@link #kind}: Baughn's decisions 5 and 6). Players only, with a per-player cooldown.</li>
 * <li>Each destination gets once an arrival pad and a small return rift beside it ({@link ArrivalData}, in the destination
 * level; later trips only clear the arrival's foot pocket). Return rifts are registered like gate rifts, so clients
 * draw them (StaticDeeps.setReturns, the GateRifts payload).</li>
 * <li>Stepping into a return rift goes home ({@link #choose}, Baughn's decision 3): from the central End rift islands to
 * the lance the player came out of if its gate is open, else a random open one, else theirs regardless; from other
 * return rifts to their own home rift, in the same order. "Home" is the base of the lance ({@link #basePosition}), for L1
 * a safe spot at the rift's rim.</li>
 * </ul>
 */
public final class Lance {
    private Lance() {
    }

    /** An open gate's rift is held at this share of its radius (LANCE-v2: the breach's floor). */
    public static final float OPEN_SCALE = 0.25f;
    /** Stepping into a disc or a return rift: the player's centre within this of the core, horizontally (intent). */
    static final double REACH = 1.5;
    /** After a trip no other trip for this long (ticks): no ping-pong between a pad and a gate. */
    static final int COOLDOWN = 100;
    /** The return rift's core hangs this far along the pad from the arrival spot, at body height. */
    static final int RETURN_ALONG = 5;

    /** Persisted player NBT: the gate the player last came out of ({dim, id}); arrived in the End by lance. */
    static final String FROM_TAG = "stasisdelvers_lance_from", END_TAG = "stasisdelvers_lance_end";

    /** Where an open gate leads (Baughn's decisions 5 and 6, LANCE-v2 point 6). */
    public enum Kind {
        /** The Nether: a fixed spot per rift (its coordinates / 8), on ground, above the lava sea. */
        NETHER,
        /** The outer End, >= 1024 out, near an End site (End towers). */
        OUTER_END,
        /** Stopped mode's stronghold gate: 150-200 blocks from the main island's middle, in view of it. */
        STRONGHOLD_END,
        /** Spreading mode after hard mode, a stronghold gate: one of the End rift's floating islands. */
        CENTRAL_END
    }

    /** A gate by level and deep id. */
    public record Ref(String dim, int id) {
    }

    // ---- saved state

    /** No lance (Gate.lance). */
    public static final long NO_LANCE = Long.MIN_VALUE;

    /**
     * A rift with a lance gate: its geometry (so it can be found again from anywhere), whether it is open (latched), and
     * the breach (L2): `step` 0..{@link #STEPS} (the rift's strength {@link #strength}), `progress` 0..1 within the step
     * (up while breaching, down while relaxing), `grace` ticks without power so far, the lance core bound to it
     * (`lance`, a packed block position, or {@link #NO_LANCE}) and when its last lance was dismantled (game time; -1:
     * none ever was, as for gates opened by the test command, which then stay as set).
     */
    public record Gate(int id, float x, float y, float z, float r, boolean open, int step, float progress, int grace, long lance,
                       long dismantled) {
        static final Codec<Gate> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Gate::id),
            Codec.FLOAT.fieldOf("x").forGetter(Gate::x),
            Codec.FLOAT.fieldOf("y").forGetter(Gate::y),
            Codec.FLOAT.fieldOf("z").forGetter(Gate::z),
            Codec.FLOAT.fieldOf("r").forGetter(Gate::r),
            Codec.BOOL.optionalFieldOf("open", false).forGetter(Gate::open),
            // (L1 saves have no step: an open gate there is at the floor, not snapped back to the whole rift)
            Codec.INT.optionalFieldOf("step").forGetter(g -> java.util.Optional.of(g.step())),
            Codec.FLOAT.optionalFieldOf("progress", 0f).forGetter(Gate::progress),
            Codec.INT.optionalFieldOf("grace", 0).forGetter(Gate::grace),
            Codec.LONG.optionalFieldOf("lance", NO_LANCE).forGetter(Gate::lance),
            Codec.LONG.optionalFieldOf("dismantled", -1L).forGetter(Gate::dismantled)
        ).apply(i, (id, x, y, z, r, open, step, progress, grace, lance, dis) ->
            new Gate(id, x, y, z, r, open, step.orElse(open ? STEPS : 0), progress, grace, lance, dis)));

        public StaticDeeps.Deep deep() {
            return new StaticDeeps.Deep(x, y, z, r, id);
        }

        static Gate of(StaticDeeps.Deep d, boolean open) {
            return new Gate(d.id(), d.x(), d.y(), d.z(), d.radius(), open, open ? STEPS : 0, 0, 0, NO_LANCE, -1);
        }

        public boolean hasLance() {
            return lance != NO_LANCE;
        }

        /** The rift's share of its radius now (quantised by step). */
        public float strength() {
            return Lance.strength(step);
        }

        Gate withBreach(boolean open, int step, float progress, int grace) {
            return new Gate(id, x, y, z, r, open, step, progress, grace, lance, dismantled);
        }

        Gate withLance(long lance, long dismantled) {
            return new Gate(id, x, y, z, r, open, step, progress, grace, lance, dismantled);
        }
    }

    /** The breach's quantised steps from the whole rift to the floor ({@link #OPEN_SCALE}). */
    public static final int STEPS = 8;

    /** A rift's share of its radius at a breach step: 1 at 0, OPEN_SCALE at STEPS, evenly between. */
    public static float strength(int step) {
        return 1f - (1f - OPEN_SCALE) * Math.max(0, Math.min(STEPS, step)) / STEPS;
    }

    /** SavedData stasisdelvers:lance_gates, in the rift's level. */
    public static final class GateData extends SavedData {
        static final Codec<GateData> CODEC = Gate.CODEC.listOf().optionalFieldOf("gates", List.of()).codec()
            .xmap(GateData::new, d -> new ArrayList<>(d.gates.values()));
        static final SavedDataType<GateData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "lance_gates"), GateData::new, CODEC);

        final Map<Integer, Gate> gates = new LinkedHashMap<>();

        public GateData() {
        }

        private GateData(List<Gate> l) {
            for (Gate g : l) gates.put(g.id(), g);
        }

        public static GateData get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }

        public Gate gate(int id) {
            return gates.get(id);
        }
    }

    /** An arrival pad and its return rift (centre block), made once per destination; home: the gate it was made for. */
    public record Arrival(String key, long foot, long ret, String homeDim, int homeId, boolean central, boolean dormant) {
        static final Codec<Arrival> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("key").forGetter(Arrival::key),
            Codec.LONG.fieldOf("foot").forGetter(Arrival::foot),
            Codec.LONG.fieldOf("ret").forGetter(Arrival::ret),
            Codec.STRING.fieldOf("home_dim").forGetter(Arrival::homeDim),
            Codec.INT.fieldOf("home_id").forGetter(Arrival::homeId),
            Codec.BOOL.optionalFieldOf("central", false).forGetter(Arrival::central),
            Codec.BOOL.optionalFieldOf("dormant", false).forGetter(Arrival::dormant)
        ).apply(i, Arrival::new));

        Arrival withDormant(boolean d) {
            return new Arrival(key, foot, ret, homeDim, homeId, central, d);
        }

        public BlockPos footPos() {
            return BlockPos.of(foot);
        }

        public BlockPos retPos() {
            return BlockPos.of(ret);
        }

        public Ref home() {
            return new Ref(homeDim, homeId);
        }
    }

    /** SavedData stasisdelvers:lance_arrivals, in the destination level. */
    public static final class ArrivalData extends SavedData {
        static final Codec<ArrivalData> CODEC = Arrival.CODEC.listOf().optionalFieldOf("arrivals", List.of()).codec()
            .xmap(ArrivalData::new, d -> new ArrayList<>(d.arrivals.values()));
        static final SavedDataType<ArrivalData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "lance_arrivals"), ArrivalData::new, CODEC);

        final Map<String, Arrival> arrivals = new LinkedHashMap<>();

        public ArrivalData() {
        }

        private ArrivalData(List<Arrival> l) {
            for (Arrival a : l) arrivals.put(a.key(), a);
        }

        public static ArrivalData get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }

        public List<Arrival> all() {
            return List.copyOf(arrivals.values());
        }

        /** The arrival whose return rift has this deep id, or null. */
        Arrival byReturn(int id) {
            for (Arrival a : arrivals.values()) if (!a.dormant() && StaticDeeps.returnRift(a.retPos()).id() == id) return a;
            return null;
        }
    }

    /** The level's return rift centres (packed block positions), for StaticDeeps and the GateRifts payload; not the
     *  dormant ones (their home lance is gone: {@link #dormancy}). */
    public static List<Long> returns(ServerLevel level) {
        return ArrivalData.get(level).arrivals.values().stream().filter(a -> !a.dormant()).map(Arrival::ret).toList();
    }

    /** The level's lanced rifts' strengths for the GateRifts payload: id << 4 | step, for each gate past step 0. */
    public static List<Integer> steps(ServerLevel level) {
        List<Integer> out = new ArrayList<>();
        for (Gate g : GateData.get(level).gates.values()) if (g.step() > 0) out.add(packStep(g.id(), g.step()));
        return out;
    }

    public static int packStep(int id, int step) {
        return (id & 0xFFFFFF) << 4 | (step & 0xF);
    }

    /** Unpack GateRifts' steps into strengths by id (both sides set StaticDeeps from this). */
    public static Map<Integer, Float> strengths(List<Integer> packed) {
        Map<Integer, Float> m = new HashMap<>();
        for (int v : packed) m.put(v >>> 4, strength(v & 0xF));
        return m;
    }

    /** The level's return rifts and its lanced rifts' strengths into StaticDeeps (level load, and on every change). */
    static void publish(ServerLevel level) {
        int seed = FieldData.get(level).deepSeed();
        StaticDeeps.setReturns(seed, returns(level));
        if (!StaticDeeps.setStrengths(seed, strengths(steps(level))).isEmpty()) Stasis.bumpVersion();
    }

    /** publish, and tell the level's players (the GateRifts payload). */
    public static void publishAndSend(ServerLevel level) {
        publish(level);
        for (ServerPlayer pl : level.players()) StasisNet.sendGates(pl);
    }

    // ---- gate state

    /** Can a lance hold this rift? Not the End rift (it is where lances lead), not a return rift. */
    static boolean lanceable(StaticDeeps.Deep d) {
        return !StaticDeeps.isReturn(d) && !(d.radius() == StaticDeeps.END_RIFT_RADIUS && StaticDeeps.isGate(d));
    }

    /**
     * Open or close the gate on rift d (in level) at once (the test command, tests): latched at the floor, or whole. Its
     * lance stays bound; a gate with no lance never had one counts as held by the command (it doesn't relax).
     */
    public static Gate setOpen(ServerLevel level, StaticDeeps.Deep d, boolean open) {
        GateData gd = GateData.get(level);
        Gate old = gd.gate(d.id());
        Gate g = Gate.of(d, open);
        if (old != null) g = g.withLance(old.lance(), -1);
        gd.gates.put(d.id(), g);
        gd.setDirty();
        publishAndSend(level);
        StasisDelvers.LOG.info("Lance gate on rift {} in {} {}", d.id(), level.dimension().identifier(), open ? "opened" : "closed");
        return g;
    }

    /** Is the gate on rift id (in level) open? */
    public static boolean isOpen(ServerLevel level, int id) {
        Gate g = GateData.get(level).gate(id);
        return g != null && g.open();
    }

    /**
     * The rift's effective radius: the breach steps it down to {@link #OPEN_SCALE}, where the gate latches (StaticDeeps
     * holds the same for zones; clients get it in GateRifts).
     */
    public static float effectiveRadius(ServerLevel level, StaticDeeps.Deep d) {
        Gate g = GateData.get(level).gate(d.id());
        return g == null ? d.radius() : d.radius() * g.strength();
    }

    /** The rift nearest to pos (its core), or the one with this id within ~4 km or with a gate here; null if none. */
    public static StaticDeeps.Deep find(ServerLevel level, Vec3 pos, Integer id) {
        int seed = FieldData.get(level).deepSeed();
        if (seed == 0) return null;
        if (id != null) {
            Gate g = GateData.get(level).gate(id);
            if (g != null) return g.deep();
            for (StaticDeeps.Deep d : StaticDeeps.near((float) pos.x, (float) pos.y, (float) pos.z, 4096, seed))
                if (d.id() == id && lanceable(d)) return d;
            return null;
        }
        StaticDeeps.Deep best = null;
        double bd = Double.MAX_VALUE;
        for (StaticDeeps.Deep d : StaticDeeps.near((float) pos.x, (float) pos.y, (float) pos.z, 512, seed)) {
            if (!lanceable(d)) continue;
            float[] c = StaticDeeps.core(d);
            double dd = pos.distanceToSqr(c[0], c[1], c[2]);
            if (dd < bd) {
                bd = dd;
                best = d;
            }
        }
        return best;
    }

    // ---- destinations

    /**
     * Where an open gate leads (pure). Stopped mode: the rift's Gates destination, a stronghold gate to the End near the
     * main island. Spreading mode before hard mode: the Nether, whatever the rift (Baughn's decision 5: the End is reached
     * by the stronghold portal then). After hard mode: stronghold gates to the End rift's islands, End-bound rifts to the
     * outer End, the rest to the Nether (decision 6).
     */
    public static Kind kind(boolean spreading, boolean hard, boolean strongholdGate, String gatesDest) {
        boolean toEnd = strongholdGate || "minecraft:the_end".equals(gatesDest);
        if (spreading && !hard) return Kind.NETHER;
        if (strongholdGate) return spreading ? Kind.CENTRAL_END : Kind.STRONGHOLD_END;
        return toEnd ? Kind.OUTER_END : Kind.NETHER;
    }

    static Kind kind(MinecraftServer srv, StaticDeeps.Deep d) {
        return kind(StasisConfig.spreading, StasisConfig.spreading && Spread.hard(srv), StaticDeeps.isStrongholdGate(d), Gates.destination(d));
    }

    /** The destination level's key: the End, or for NETHER the rift's own non-End destination (config) or the Nether. */
    static ResourceKey<Level> destDim(Kind k, String gatesDest) {
        if (k != Kind.NETHER) return Level.END;
        if (gatesDest.equals("minecraft:the_end") || gatesDest.equals("minecraft:overworld")) return Level.NETHER;
        Identifier id = Identifier.tryParse(gatesDest);
        return id == null ? Level.NETHER : ResourceKey.create(Registries.DIMENSION, id);
    }

    static ResourceKey<Level> dimKey(String dim) {
        Identifier id = Identifier.tryParse(dim);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    static String dimId(Level level) {
        return level.dimension().identifier().toString();
    }

    /** Where gate g (in home) leads now: the level, or null if the server has none such. */
    static ServerLevel destination(MinecraftServer srv, StaticDeeps.Deep d) {
        return srv.getLevel(destDim(kind(srv, d), Gates.destination(d)));
    }

    /** A destination spot: its arrival key, the foot block, the pad's direction (towards the return rift). */
    record Spot(String key, BlockPos foot, Direction along, boolean central) {
    }

    /** The arrival key of gate g (from home) going to a kind: per rift, or per island on the End rift's islands. */
    static String key(ServerLevel dest, Kind k, Gate g, String homeDim) {
        if (k == Kind.CENTRAL_END) {
            int idx = island(dest, g);
            if (idx >= 0) return "island:" + idx;
        }
        return "rift:" + homeDim + ":" + g.id();
    }

    /** The End rift island gate g lands on (deterministic per rift; a roomy one), or -1. */
    static int island(ServerLevel end, Gate g) {
        EndRiftPlan pl = EndRift.plan(end);
        List<Integer> roomy = new ArrayList<>();
        for (int i = 0; i < pl.islands.size(); i++) if (Math.min(pl.islands.get(i).rx(), pl.islands.get(i).rz()) >= 5) roomy.add(i);
        if (roomy.isEmpty()) for (int i = 0; i < pl.islands.size(); i++) roomy.add(i);
        if (roomy.isEmpty()) return -1;
        return roomy.get(Math.floorMod(Noise.hashInt(g.id(), 4, 0, 0x1A7D), roomy.size()));
    }

    static Spot spot(ServerLevel dest, Kind k, Gate g, String key) {
        int id = g.id();
        float u1 = Noise.hash32(id, 1, 0, 0x1A7C), u2 = Noise.hash32(id, 2, 0, 0x1A7C);
        Direction dir = Direction.from2DDataValue((int) (Noise.hash32(id, 3, 0, 0x1A7C) * 4) & 3);
        switch (k) {
            case NETHER -> {
                int x0 = Mth.floor(g.x() / 8), z0 = Mth.floor(g.z() / 8);
                BlockPos f = netherGround(dest, x0, z0);
                return new Spot(key, f != null ? f : new BlockPos(x0, 70, z0), dir, false);
            }
            case OUTER_END -> {
                double a = 2 * Math.PI * u1, dist = 1100 + 500 * u2;
                int tx = (int) Math.round(Math.cos(a) * dist), tz = (int) Math.round(Math.sin(a) * dist);
                ChunkPos site = endSite(dest, tx >> 4, tz >> 4);
                if (site != null) {   // beside the site, on the side towards the main island
                    int sx = site.getMiddleBlockX() - (int) Math.round(Math.cos(a) * 24), sz = site.getMiddleBlockZ() - (int) Math.round(Math.sin(a) * 24);
                    // (unless the nearest site lies inside the outer End: the arrival came out at 993 once)
                    if (Math.hypot(sx, sz) >= 1040) {
                        tx = sx;
                        tz = sz;
                    }
                }
                BlockPos f = endGround(dest, tx, tz, 32);
                return new Spot(key, f != null ? f : new BlockPos(tx, 64, tz), dir, false);
            }
            case CENTRAL_END -> {
                int idx = island(dest, g);
                if (idx >= 0) {
                    EndRiftPlan.Island is = EndRift.plan(dest).islands.get(idx);
                    // the pad points away from the rift's core (the return rift never near it)
                    Direction away = Math.abs(is.x()) >= Math.abs(is.z()) ? (is.x() >= 0 ? Direction.EAST : Direction.WEST)
                        : (is.z() >= 0 ? Direction.SOUTH : Direction.NORTH);
                    // on the island's highest planned block under the pad's whole footprint: EndRift places island blocks
                    // into air later, and would bury a pad stamped lower (or fill the return rift's core)
                    Set<Long> foot = new java.util.HashSet<>();
                    for (BlockPos q : padColumns(new BlockPos(is.x(), 0, is.z()), away)) foot.add(BlockPos.asLong(q.getX(), 0, q.getZ()));
                    int top = is.y();
                    for (long[] b : EndRiftPlan.shape(is))
                        if (foot.contains(BlockPos.asLong((int) b[0], 0, (int) b[2]))) top = Math.max(top, (int) b[1]);
                    return new Spot(key, new BlockPos(is.x(), top + 1, is.z()), away, true);
                }
                return spot(dest, Kind.STRONGHOLD_END, g, key);
            }
            default -> {   // STRONGHOLD_END
                double a = 2 * Math.PI * u1, dist = 150 + 50 * u2;
                int x = (int) Math.round(Math.cos(a) * dist), z = (int) Math.round(Math.sin(a) * dist);
                BlockPos f = endGround(dest, x, z, 0);
                // the pad runs round the island (tangential), so the return rift is as near as the arrival
                Direction tangent = Math.abs(x) >= Math.abs(z) ? Direction.SOUTH : Direction.EAST;
                return new Spot(key, f != null ? f : new BlockPos(x, 64, z), tangent, false);
            }
        }
    }

    /** The End site (End tower) placement chunk nearest the grid cell of chunk (cx, cz), or null without the set. */
    static ChunkPos endSite(ServerLevel end, int cx, int cz) {
        var key = ResourceKey.create(Registries.STRUCTURE_SET, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "end_tower_sites"));
        var set = end.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).get(key);
        if (set.isEmpty() || !(set.get().value().placement() instanceof RandomSpreadStructurePlacement rs)) return null;
        return rs.getPotentialStructureChunk(end.getChunkSource().getGeneratorState().getLevelSeed(), cx, cz);
    }

    /** Ground in the End near (x, z): a column with a solid top, rings 8 apart out to radius; null if none. */
    static BlockPos endGround(ServerLevel end, int x, int z, int radius) {
        for (int r = 0; r <= radius; r += 8)
            for (int dx = -r; dx <= r; dx += 8)
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = x + dx, cz = z + dz;
                    end.getChunk(cx >> 4, cz >> 4);
                    int y = end.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                    BlockPos f = new BlockPos(cx, y, cz);
                    if (y > end.getMinY() + 1 && standable(end, f)) return f;
                }
        return null;
    }

    /** Ground in the Nether near (x, z): solid, not lava, three blocks of air, over the lava sea and under the roof. */
    static BlockPos netherGround(ServerLevel nether, int x, int z) {
        int hi = Math.min(nether.getMaxY() - 4, 112), lo = Math.max(nether.getMinY() + 2, nether.getSeaLevel() + 2);
        for (int r = 0; r <= 32; r += 8)
            for (int dx = -r; dx <= r; dx += 8)
                for (int dz = -r; dz <= r; dz += 8) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = x + dx, cz = z + dz;
                    nether.getChunk(cx >> 4, cz >> 4);
                    for (int y = hi; y >= lo; y--) {
                        BlockPos f = new BlockPos(cx, y, cz);
                        if (standable(nether, f) && open(nether, f.above(2))) return f;
                    }
                }
        return null;
    }

    /** Can a player stand with feet at f: a sturdy top below (not lava, magma or fire), two free blocks? */
    static boolean standable(ServerLevel level, BlockPos f) {
        BlockState below = level.getBlockState(f.below());
        if (!below.isFaceSturdy(level, f.below(), Direction.UP) || below.is(Blocks.MAGMA_BLOCK) || !below.getFluidState().isEmpty()) return false;
        return open(level, f) && open(level, f.above());
    }

    static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty() && !s.is(Blocks.FIRE) && !s.is(Blocks.SOUL_FIRE);
    }

    /** The arrival for gate g (from home) in dest: made once (the pad, the return rift), else the foot pocket cleared. */
    static Arrival arrival(ServerLevel dest, Kind k, Gate g, String homeDim) {
        ArrivalData ad = ArrivalData.get(dest);
        String key = key(dest, k, g, homeDim);
        Arrival a = ad.arrivals.get(key);
        if (a != null && a.dormant()) {   // its home has a gate again: the return rift wakes
            a = a.withDormant(false);
            ad.arrivals.put(key, a);
            ad.setDirty();
            publishAndSend(dest);
        }
        if (a != null) {
            BlockPos f = a.footPos();
            for (BlockPos q : new BlockPos[]{f, f.above()}) if (!dest.getBlockState(q).isAir()) dest.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
            return a;
        }
        Spot s = spot(dest, k, g, key);
        a = build(dest, s, homeDim, g.id());
        ad.arrivals.put(key, a);
        ad.setDirty();
        publishAndSend(dest);
        StasisDelvers.LOG.info("Lance arrival {} made in {} at {} (return rift at {})", key, dimId(dest), a.footPos().toShortString(),
            a.retPos().toShortString());
        return a;
    }

    /**
     * The pad: 3 wide, from one block behind the arrival to one past the return rift, riftstone with obsidian corners (all
     * riftstone on the End rift's islands, where EndRift's lifts take obsidian), the space above it cleared 3 high. The
     * return rift's centre hangs RETURN_ALONG along the pad, one up (a player's body height).
     */
    static Arrival build(ServerLevel dest, Spot s, String homeDim, int homeId) {
        BlockPos f = s.foot();
        Direction d = s.along();
        BlockState rift = StasisRegistry.RIFTSTONE.get().defaultBlockState(), obs = Blocks.OBSIDIAN.defaultBlockState();
        List<BlockPos> pad = padColumns(f.below(), d);
        for (int i = 0; i < pad.size(); i++) {
            BlockPos base = pad.get(i);
            int a = i / 3 - 1, b = i % 3 - 1;
            boolean corner = (a == -1 || a == RETURN_ALONG + 1) && b != 0;
            dest.setBlock(base, corner && !s.central() ? obs : rift, 3);
            for (int h = 1; h <= 3; h++) {
                BlockPos up = base.above(h);
                if (!dest.getBlockState(up).isAir()) dest.setBlock(up, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        BlockPos ret = f.relative(d, RETURN_ALONG).above();
        return new Arrival(s.key(), f.asLong(), ret.asLong(), homeDim, homeId, s.central(), false);
    }

    /** The pad's blocks at base's height: along d from one behind to one past the return rift, 3 wide (row by row). */
    static List<BlockPos> padColumns(BlockPos base, Direction d) {
        Direction side = d.getClockWise();
        List<BlockPos> l = new ArrayList<>();
        for (int a = -1; a <= RETURN_ALONG + 1; a++)
            for (int b = -1; b <= 1; b++) l.add(base.relative(d, a).relative(side, b));
        return l;
    }

    // ---- going home

    /**
     * The gate a return trip goes to (pure; Baughn's decision 3): `preferred` if its gate is open, else a random open one
     * (`pick` chooses), else `fallback` (the player's own, regardless). Null only if all three are missing.
     */
    public static Ref choose(Ref preferred, List<Ref> open, Ref fallback, int pick) {
        if (preferred != null && open.contains(preferred)) return preferred;
        if (!open.isEmpty()) return open.get(Math.floorMod(pick, open.size()));
        return fallback != null ? fallback : preferred;
    }

    /** The open gates that lead to this level now (any level's), for {@link #choose}. */
    static List<Ref> openLeadingTo(MinecraftServer srv, ResourceKey<Level> to) {
        List<Ref> out = new ArrayList<>();
        for (ServerLevel l : srv.getAllLevels())
            for (Gate g : GateData.get(l).gates.values())
                if (g.open() && destDim(kind(srv, g.deep()), Gates.destination(g.deep())) == to) out.add(new Ref(dimId(l), g.id()));
        return out;
    }

    /**
     * Where a trip home lands: the base of the gate's lance (Baughn: "the base of the tower"), a standable spot beside
     * its core, the side away from the rift first. With no lance standing (the test command's gates, a dismantled one):
     * a safe spot at the rift's rim ({@link #rim}).
     */
    public static BlockPos basePosition(ServerLevel level, Gate g) {
        if (g.hasLance()) {
            BlockPos core = BlockPos.of(g.lance());
            level.getChunk(core.getX() >> 4, core.getZ() >> 4);
            if (level.getBlockEntity(core) instanceof LanceBlockEntity) {
                float[] c = StaticDeeps.core(g.deep());
                List<BlockPos> around = new ArrayList<>();
                for (int dy : new int[]{0, 1, -1})
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) around.add(core.offset(dx, dy, dz));
                // the side away from the eye first (nobody walks straight back in), the core's own level before others
                around.sort(java.util.Comparator.comparingInt((BlockPos q) -> Math.abs(q.getY() - core.getY()))
                    .thenComparingDouble(q -> -Math.hypot(q.getX() + 0.5 - c[0], q.getZ() + 0.5 - c[2])));
                for (BlockPos f : around) if (standable(level, f)) return f;
            }
        }
        return rim(level, g);
    }

    /**
     * A safe spot at rift g's rim: the nearest ring (outside its rift zone, every 2 blocks out to r + 12, 16 directions)
     * with a standable spot outside the rift, the one nearest the core's height; else the surface beside it.
     */
    static BlockPos rim(ServerLevel level, Gate g) {
        StaticDeeps.Deep d = g.deep();
        float[] c = StaticDeeps.core(d);
        int seed = FieldData.get(level).deepSeed(), y0 = Mth.floor(c[1]), span = (int) Math.ceil(d.radius()) + 4;
        int top = Math.min(y0 + span, level.getMaxY() - 2), bottom = Math.max(y0 - span, level.getMinY() + 1);
        for (float dist = d.radius() * StaticDeeps.RIFT + 2; dist <= d.radius() + 12; dist += 2) {
            BlockPos best = null;
            int bestDy = Integer.MAX_VALUE;
            for (int k = 0; k < 16; k++) {
                double a = k * Math.PI / 8;
                int x = Mth.floor(c[0] + Math.cos(a) * dist), z = Mth.floor(c[2] + Math.sin(a) * dist);
                level.getChunk(x >> 4, z >> 4);
                for (int y = top; y >= bottom; y--) {
                    BlockPos f = new BlockPos(x, y, z);
                    if (Math.abs(y - y0) < bestDy && standable(level, f) && StaticDeeps.zone(seed, x + 0.5f, y + 0.5f, z + 0.5f) != 2
                        && StaticDeeps.zone(seed, x + 0.5f, y + 1.5f, z + 0.5f) != 2) {
                        best = f;
                        bestDy = Math.abs(y - y0);
                    }
                }
            }
            if (best != null) return best;
        }
        int x = Mth.floor(c[0] + d.radius() + 4), z = Mth.floor(c[2]);
        level.getChunk(x >> 4, z >> 4);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    // ---- travel

    /** Players mid-trip (HardMode.travel lets them into a closed End). Server thread. */
    private static final Set<UUID> TRAVELLING = new java.util.HashSet<>();
    /** Each player's last trip (game time). */
    private static final Map<UUID, Long> LAST = new HashMap<>();

    /** Is this entity on a lance trip right now (HardMode.travel)? */
    public static boolean travelling(Entity e) {
        return e instanceof ServerPlayer && TRAVELLING.contains(e.getUUID());
    }

    /** Did this player come into the End by lance (and not leave since)? HardMode.login leaves them there. */
    public static boolean arrivedByLance(ServerPlayer p) {
        return persisted(p).getBooleanOr(END_TAG, false);
    }

    static CompoundTag persisted(ServerPlayer p) {
        return p.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
    }

    static void putPersisted(ServerPlayer p, CompoundTag t) {
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, t);
    }

    /** The gate the player last came out of, or null. */
    public static Ref from(ServerPlayer p) {
        CompoundTag t = persisted(p).getCompoundOrEmpty(FROM_TAG);
        String dim = t.getStringOr("dim", "");
        return dim.isEmpty() ? null : new Ref(dim, t.getIntOr("id", 0));
    }

    static void setFrom(ServerPlayer p, Ref r) {
        CompoundTag t = persisted(p), f = new CompoundTag();
        f.putString("dim", r.dim());
        f.putInt("id", r.id());
        t.put(FROM_TAG, f);
        putPersisted(p, t);
    }

    static void setInEnd(ServerPlayer p, boolean in) {
        CompoundTag t = persisted(p);
        if (in) t.putBoolean(END_TAG, true);
        else t.remove(END_TAG);
        putPersisted(p, t);
    }

    /**
     * One player's step (from Freezing.tick, before the freeze, so the gate wins over the rift's statue in the same tick;
     * tests drive it the same way). True if the player was sent somewhere.
     */
    public static boolean step(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level) || !p.isAlive() || p.isRemoved() || p.isSpectator()) return false;
        long now = level.getGameTime();
        Long last = LAST.get(p.getUUID());
        if (last != null && now - last < COOLDOWN && now >= last) return false;
        int seed = FieldData.get(level).deepSeed();
        if (seed == 0) return false;
        float x = (float) p.getX(), y = (float) p.getY() + 0.9f, z = (float) p.getZ();
        GateData gd = null;
        for (StaticDeeps.Deep d : StaticDeeps.near(x, y, z, 3, seed)) {
            float dx = x - d.x(), dy = y - d.y(), dz = z - d.z(), reach = d.radius() + 12;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            if (StaticDeeps.isReturn(d)) {
                if (!atCore(p, StaticDeeps.core(d))) continue;
                Arrival a = ArrivalData.get(level).byReturn(d.id());
                if (a == null) continue;
                goHome(p, level, a);
                return true;
            }
            if (gd == null) gd = GateData.get(level);
            Gate g = gd.gate(d.id());
            if (g != null && g.open() && atCore(p, StaticDeeps.core(d)) && travel(p, level, g)) return true;
        }
        return false;
    }

    /** Is the player in the disc's centre: within REACH of core c horizontally, the core between their feet and head? */
    static boolean atCore(ServerPlayer p, float[] c) {
        double dx = p.getX() - c[0], dz = p.getZ() - c[2];
        return dx * dx + dz * dz <= REACH * REACH && c[1] >= p.getY() - 0.5 && c[1] <= p.getY() + 2.3;
    }

    /**
     * Freezing: is this player (in a rift's zone) spared the rift's statue? In a return rift's body (the door home), or in
     * an open gate's disc. (Outside the part a lance holds the rift to, StaticDeeps.zone already says outer zone.)
     */
    public static boolean spares(ServerLevel level, ServerPlayer p) {
        int seed = FieldData.get(level).deepSeed();
        if (seed == 0) return false;
        float x = (float) p.getX(), y = (float) p.getY() + 0.9f, z = (float) p.getZ();
        GateData gd = null;
        for (StaticDeeps.Deep d : StaticDeeps.near(x, y, z, 4, seed)) {
            float dx = x - d.x(), dy = y - d.y(), dz = z - d.z(), reach = d.radius() + 12;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            if (StaticDeeps.isReturn(d)) {
                float[] c = StaticDeeps.core(d);
                if (Math.pow(x - c[0], 2) + Math.pow(y - c[1], 2) + Math.pow(z - c[2], 2) <= 3.5 * 3.5) return true;
                continue;
            }
            if (gd == null) gd = GateData.get(level);
            Gate g = gd.gate(d.id());
            if (g == null || !g.open()) continue;
            float[] c = StaticDeeps.core(d);
            if (Math.pow(x - c[0], 2) + Math.pow(z - c[2], 2) <= (REACH + 1) * (REACH + 1) && Math.abs(y - c[1]) <= 2.5) return true;
        }
        return false;
    }

    /** Through open gate g (in home): to the arrival (made on the first trip), with grace. False if it leads nowhere. */
    static boolean travel(ServerPlayer p, ServerLevel home, Gate g) {
        MinecraftServer srv = home.getServer();
        StaticDeeps.Deep d = g.deep();
        Kind k = kind(srv, d);
        ServerLevel dest = srv.getLevel(destDim(k, Gates.destination(d)));
        if (dest == null) {
            LAST.put(p.getUUID(), home.getGameTime());
            p.sendOverlayMessage(Component.literal("The gate opens onto nothing.").withStyle(ChatFormatting.DARK_PURPLE));
            return false;
        }
        String homeDim = dimId(home);
        Arrival a = arrival(dest, k, g, homeDim);
        setFrom(p, new Ref(homeDim, g.id()));
        setInEnd(p, dest.dimension() == Level.END);
        BlockPos f = a.footPos(), r = a.retPos();
        send(p, dest, Vec3.atBottomCenterOf(f), yawTowards(f, r));
        StasisDelvers.LOG.info("{} went through the lance gate on rift {} in {} to {} {}", p.getName().getString(), g.id(), homeDim, dimId(dest),
            f.toShortString());
        p.sendOverlayMessage(Component.literal("The gate takes you.").withStyle(ChatFormatting.LIGHT_PURPLE));
        return true;
    }

    /** Through a return rift (in level, arrival a): home by {@link #choose}; the respawn point if nothing is known. */
    static void goHome(ServerPlayer p, ServerLevel level, Arrival a) {
        MinecraftServer srv = level.getServer();
        Ref pref, fallback;
        if (a.central()) {
            Ref came = from(p);
            pref = came;
            fallback = came != null ? came : a.home();
        } else {
            pref = a.home();
            fallback = pref;
        }
        List<Ref> open = openLeadingTo(srv, level.dimension());
        Ref r = choose(pref, open, fallback, open.isEmpty() ? 0 : p.getRandom().nextInt(open.size()));
        ResourceKey<Level> hk = r == null ? null : dimKey(r.dim());
        ServerLevel hl = hk == null ? null : srv.getLevel(hk);
        Gate g = hl == null ? null : GateData.get(hl).gate(r.id());
        if (level.dimension() == Level.END) setInEnd(p, false);
        if (g == null) {
            TeleportTransition t = Freezing.safeRespawn(p, p.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING));
            send(p, t.newLevel(), t.position(), t.yRot());
            StasisDelvers.LOG.info("{} took a return rift with no home gate known: sent to their respawn point", p.getName().getString());
            return;
        }
        BlockPos b = basePosition(hl, g);
        float[] c = StaticDeeps.core(g.deep());
        send(p, hl, Vec3.atBottomCenterOf(b), yawTowards(b, BlockPos.containing(c[0], c[1], c[2])));
        StasisDelvers.LOG.info("{} took a return rift in {} home to rift {} in {} ({})", p.getName().getString(), dimId(level), g.id(), r.dim(),
            b.toShortString());
        p.sendOverlayMessage(Component.literal("The rift lets you go home.").withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    static float yawTowards(BlockPos from, BlockPos to) {
        return (float) (Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX())) - 90);
    }

    /** Teleport a player (a lance trip: HardMode lets it into a closed End), with the grace of a new place and a cooldown. */
    static void send(ServerPlayer p, ServerLevel to, Vec3 pos, float yaw) {
        if (p.isPassenger()) p.stopRiding();
        TRAVELLING.add(p.getUUID());
        try {
            p.teleport(new TeleportTransition(to, pos, Vec3.ZERO, yaw, 0, TeleportTransition.DO_NOTHING));
        } finally {
            TRAVELLING.remove(p.getUUID());
        }
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        Freezing.giveGrace(p);   // (the destinations are stopped land: the dimension change's grace, also within a level)
        LAST.put(p.getUUID(), to.getGameTime());
        to.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8f, 0.5f);
    }

    // ---- events

    /** PlayerChangedDimensionEvent: leaving the End (any way) ends "arrived by lance". */
    static void changedDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && e.getFrom() == Level.END && e.getTo() != Level.END) setInEnd(p, false);
    }

    static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        LAST.remove(e.getEntity().getUUID());
    }

    static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent e) {
        LAST.clear();
        TRAVELLING.clear();
        DRIVEN.clear();
    }

    /** Tests: the pad's blocks of an arrival (to take them away again). */
    public static List<BlockPos> testPad(Arrival a) {
        BlockPos f = a.footPos(), r = a.retPos();
        return padColumns(f.below(), Direction.getApproximateNearest(r.getX() - f.getX(), 0, r.getZ() - f.getZ()));
    }

    /** Tests: forget the player's cooldown. */
    public static void testCooldown(ServerPlayer p) {
        LAST.remove(p.getUUID());
    }

    /** Tests: forget a gate (in level) and every arrival in dest made for it, with its return rift. */
    public static void testForget(ServerLevel level, int id, ServerLevel dest) {
        GateData gd = GateData.get(level);
        if (gd.gates.remove(id) != null) gd.setDirty();
        if (dest == null) return;
        ArrivalData ad = ArrivalData.get(dest);
        if (ad.arrivals.values().removeIf(a -> a.homeId() == id && a.homeDim().equals(dimId(level)))) {
            ad.setDirty();
            publish(dest);
        }
    }

    // ---- the breach (L2): costs and times (pure; LanceTest), the state machine, binding, orphans, dormancy

    /** The rift radii the costs scale between: a stronghold gate (r 9) and the largest wild rifts (Baughn: r 60). */
    public static final float R_SMALL = 9, R_LARGE = 60;

    /** Hold draw (W) for a radius: Baughn's ~10 kW "to break even", the same for every rift. */
    public static double holdW(float r, double hold) {
        return hold;
    }

    /** Breach draw (W): the hold draw times a factor rising linearly from `min` at r 9 to `max` at r 60 (clamped). */
    public static double breachW(float r, double hold, double min, double max) {
        double t = Mth.clamp((r - R_SMALL) / (R_LARGE - R_SMALL), 0, 1);
        return hold * (min + (max - min) * t);
    }

    /** Seconds of a whole breach (or relax) given its r 9 length: longer by sqrt(r / 9) on larger rifts. */
    public static double seconds(float r, double atSmall) {
        return atSmall * Math.sqrt(Math.max(1, r) / R_SMALL);
    }

    public static double holdW(float r) {
        return holdW(r, StasisConfig.lanceHoldW);
    }

    public static double breachW(float r) {
        return breachW(r, StasisConfig.lanceHoldW, StasisConfig.lanceBreachMin, StasisConfig.lanceBreachMax);
    }

    /**
     * One tick's inputs to a gate: `want` (a bound, standing lance with a focus that fits), `powered` (its draw met this
     * tick), `storm` (a storm over the rift), and the lengths in ticks of a breach step, a relax step and the grace.
     */
    public record Drive(boolean want, boolean powered, boolean storm, double stepTicks, double relaxTicks, int graceTicks) {
    }

    /** The Drive lengths for a rift of radius r (config; `speed` > 1 only in tests). */
    public static Drive drive(float r, boolean want, boolean powered, boolean storm, double speed) {
        double step = seconds(r, StasisConfig.lanceBreachSeconds) * 20 / STEPS / speed, relax = seconds(r, StasisConfig.lanceRelaxSeconds) * 20 / STEPS / speed;
        return new Drive(want, powered, storm, Math.max(1, step), Math.max(1, relax), (int) Math.round(StasisConfig.lanceGraceSeconds * 20 / speed));
    }

    /**
     * One tick of a gate (pure; LanceTest). Powered and wanted: the grace resets; a gate not yet latched breaches (no
     * progress in a storm), stepping down every stepTicks, and latches OPEN at the last step; a latched gate holds.
     * Otherwise a gate past step 0 counts its grace (the klaxon), then unlatches and relaxes back (progress runs down,
     * a step every relaxTicks; never a snap: it would swallow the gantry). Power back mid-relax breaches on from there.
     */
    static final float EPS = 1e-4f;

    public static Gate advance(Gate g, Drive d) {
        boolean open = g.open();
        int step = g.step(), grace = g.grace();
        float pr = g.progress();
        if (d.want() && d.powered()) {
            grace = 0;
            if (!open && !d.storm()) {
                pr += (float) (1 / d.stepTicks());
                if (pr >= 1 - EPS) {   // (float sums of 1/n: a step after n ticks, not n + 1)
                    step = Math.min(STEPS, step + 1);
                    pr = 0;
                    if (step == STEPS) open = true;
                }
            }
        } else if (open || step > 0 || pr > 0) {
            if (grace < d.graceTicks()) grace++;
            else {
                open = false;
                pr -= (float) (1 / d.relaxTicks());
                if (pr < -EPS) {
                    if (step > 0) {
                        step--;
                        pr += 1;
                    } else pr = 0;
                }
                if (pr < 0) pr = 0;
            }
        } else grace = 0;
        if (step == 0 && pr == 0 && !open) grace = 0;
        return g.withBreach(open, step, pr, grace);
    }

    /** Is the gate's klaxon sounding (without power, in its grace window)? */
    public static boolean klaxon(Gate g, int graceTicks) {
        return g.grace() > 0 && g.grace() <= graceTicks && (g.open() || g.step() > 0 || g.progress() > 0);
    }

    /** Game time a lance last drove each gate (memory; orphans are driven by {@link #tick}). */
    private static final Map<Ref, Long> DRIVEN = new HashMap<>();

    /**
     * A tick of gate `id` (in level) from its lance (or from {@link #tick} for a gate whose lance is gone). Saves it, and
     * on a new step or a latch tells StaticDeeps and the level's players. Returns the gate as it is now (null if none).
     */
    public static Gate drive(ServerLevel level, int id, boolean want, boolean powered, boolean storm, double speed) {
        GateData gd = GateData.get(level);
        Gate g = gd.gate(id);
        if (g == null) return null;
        Gate n = advance(g, drive(g.r(), want, powered, storm, speed));
        DRIVEN.put(new Ref(dimId(level), id), level.getGameTime());
        if (n.equals(g)) return g;
        gd.gates.put(id, n);
        gd.setDirty();
        if (n.step() != g.step() || n.open() != g.open()) {
            publishAndSend(level);
            float[] c = StaticDeeps.core(n.deep());
            if (n.open() && !g.open()) {
                level.playSound(null, c[0], c[1], c[2], SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 2f, 1.4f);
                StasisDelvers.LOG.info("Lance gate on rift {} in {} latched OPEN", id, dimId(level));
            } else if (!n.open() && g.open()) {
                level.playSound(null, c[0], c[1], c[2], SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 2f, 0.5f);
                StasisDelvers.LOG.info("Lance gate on rift {} in {} unlatched: no power", id, dimId(level));
            } else {
                level.playSound(null, c[0], c[1], c[2], n.step() > g.step() ? SoundEvents.RESPAWN_ANCHOR_CHARGE : SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(),
                    SoundSource.BLOCKS, 1.5f, 0.5f + 0.1f * n.step());
                StasisDelvers.LOG.info("Lance breach on rift {} in {}: step {} ({} % of r {})", id, dimId(level), n.step(),
                    Math.round(100 * n.strength()), n.r());
            }
        }
        return n;
    }

    /** The lanceable rift whose eye a boom tip covers: within 1.5 r horizontally of its core, the tip above it; nearest. */
    public static StaticDeeps.Deep riftUnder(ServerLevel level, BlockPos tip) {
        int seed = FieldData.get(level).deepSeed();
        if (seed == 0) return null;
        float tx = tip.getX() + 0.5f, ty = tip.getY() + 0.5f, tz = tip.getZ() + 0.5f;
        StaticDeeps.Deep best = null;
        double bd = Double.MAX_VALUE;
        for (StaticDeeps.Deep d : StaticDeeps.near(tx, ty, tz, 96, seed)) {
            if (!lanceable(d)) continue;
            float[] c = StaticDeeps.core(d);
            double h = Math.hypot(tx - c[0], tz - c[2]);
            if (h > 1.5 * d.radius() || ty <= c[1] || h >= bd) continue;
            bd = h;
            best = d;
        }
        return best;
    }

    /**
     * Bind the lance core at `core` (in level) to rift d: refused (a reason) if another lance still stands bound to it
     * (one lance per rift; a lance in an unloaded chunk counts as standing). Wakes the gate's dormant return rifts.
     */
    public static String bind(ServerLevel level, StaticDeeps.Deep d, BlockPos core) {
        GateData gd = GateData.get(level);
        Gate g = gd.gate(d.id());
        if (g == null) g = Gate.of(d, false);
        if (g.hasLance() && g.lance() != core.asLong()) {
            BlockPos other = BlockPos.of(g.lance());
            boolean stands = !level.isLoaded(other)
                || level.getBlockEntity(other) instanceof LanceBlockEntity l && l.boundId() == d.id();
            if (stands) return "This rift already has a lance, at " + other.toShortString() + ".";
        }
        if (g.lance() == core.asLong() && g.dismantled() < 0) return null;
        gd.gates.put(d.id(), g.withLance(core.asLong(), -1));
        gd.setDirty();
        wake(level.getServer(), new Ref(dimId(level), d.id()));
        StasisDelvers.LOG.info("Lance at {} in {} bound to rift {}", core.toShortString(), dimId(level), d.id());
        return null;
    }

    /** The lance at `core` no longer holds rift id (dismantled, or its boom moved): the gate is an orphan from now. */
    public static void unbind(ServerLevel level, int id, BlockPos core) {
        GateData gd = GateData.get(level);
        Gate g = gd.gate(id);
        if (g == null || g.lance() != core.asLong()) return;
        gd.gates.put(id, g.withLance(NO_LANCE, level.getGameTime()));
        gd.setDirty();
        StasisDelvers.LOG.info("Lance at {} in {} let go of rift {}", core.toShortString(), dimId(level), id);
    }

    /** A return rift goes dormant this long after its home lance is gone (Argon: 15 min), with nobody near it. */
    static final long DORMANT_AFTER = 15 * 60 * 20;
    /** ... nobody within this of it, on the far side. */
    static final double DORMANT_NOBODY = 256;

    /** The arrivals made for gate `home` come back (their home has a lance again). */
    static void wake(MinecraftServer srv, Ref home) {
        for (ServerLevel l : srv.getAllLevels()) {
            ArrivalData ad = ArrivalData.get(l);
            boolean any = false;
            for (var e : ad.arrivals.entrySet())
                if (e.getValue().dormant() && e.getValue().home().equals(home)) {
                    e.setValue(e.getValue().withDormant(false));
                    any = true;
                }
            if (any) {
                ad.setDirty();
                publishAndSend(l);
            }
        }
    }

    /**
     * Should this arrival's return rift go dormant (pure)? Its home gate's lance is gone (not merely unpowered: while
     * the multiblock stands it is always usable), for DORMANT_AFTER, and nobody is near it. Never on the End rift's
     * islands (shared by several gates: Baughn's decision 3 sends their players to any lance).
     */
    public static boolean dormant(Arrival a, Gate home, long now, boolean nobodyNear) {
        return !a.central() && home != null && !home.hasLance() && home.dismantled() >= 0 && now - home.dismantled() >= DORMANT_AFTER && nobodyNear;
    }

    /** Every 5 s: put return rifts whose home lance has been gone a while to sleep (Argon). */
    static void dormancy(MinecraftServer srv) {
        for (ServerLevel l : srv.getAllLevels()) {
            ArrivalData ad = ArrivalData.get(l);
            boolean any = false;
            for (var e : ad.arrivals.entrySet()) {
                Arrival a = e.getValue();
                if (a.dormant()) continue;
                ResourceKey<Level> hk = dimKey(a.homeDim());
                ServerLevel hl = hk == null ? null : srv.getLevel(hk);
                Gate home = hl == null ? null : GateData.get(hl).gate(a.homeId());
                if (home == null || home.hasLance()) continue;
                BlockPos r = a.retPos();
                boolean nobody = l.getNearestPlayer(r.getX() + 0.5, r.getY() + 0.5, r.getZ() + 0.5, DORMANT_NOBODY, false) == null;
                if (dormant(a, home, l.getGameTime(), nobody)) {
                    e.setValue(a.withDormant(true));
                    any = true;
                    StasisDelvers.LOG.info("Return rift {} in {} went dormant: its home lance is gone", a.key(), dimId(l));
                }
            }
            if (any) {
                ad.setDirty();
                publishAndSend(l);
            }
        }
    }

    /**
     * ServerTickEvent.Post: gates whose lance is gone (or hasn't driven them for 10 s: its chunk unloaded) relax as if
     * unpowered; dormancy every 5 s. Gates that never had a lance (the test command's) stay as set.
     */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        MinecraftServer srv = e.getServer();
        for (ServerLevel l : srv.getAllLevels()) {
            GateData gd = GateData.get(l);
            if (gd.gates.isEmpty()) continue;
            long now = l.getGameTime();
            for (Gate g : List.copyOf(gd.gates.values())) {
                if (!(g.open() || g.step() > 0 || g.progress() > 0)) continue;
                boolean orphan;
                if (!g.hasLance()) orphan = g.dismantled() >= 0;
                else {
                    Long last = DRIVEN.get(new Ref(dimId(l), g.id()));
                    if (last == null) DRIVEN.put(new Ref(dimId(l), g.id()), now);
                    orphan = last != null && now - last > 200;
                }
                if (orphan) {
                    drive(l, g.id(), false, false, false, testSpeed);
                    if (g.hasLance()) DRIVEN.put(new Ref(dimId(l), g.id()), now - 201);   // (still an orphan next tick)
                }
            }
        }
        if (srv.getTickCount() % 100 == 0) dormancy(srv);
    }

    /** Tests: the speed orphans relax at (1 in play). */
    public static double testSpeed = 1;

    // ---- the test command (/stasis lance)

    public static int command(CommandSourceStack src, boolean open, Integer id) {
        ServerLevel level = src.getLevel();
        StaticDeeps.Deep d = find(level, src.getPosition(), id);
        if (d == null || !lanceable(d)) {
            src.sendFailure(Component.literal(id == null ? "No rift within ~512 blocks" : "No rift " + id + " known here"));
            return 0;
        }
        Gate g = setOpen(level, d, open);
        float[] c = StaticDeeps.core(d);
        ServerLevel dest = destination(level.getServer(), d);
        Kind k = kind(level.getServer(), d);
        String where = dest == null ? "nowhere (no such level)" : k + " in " + dimId(dest);
        src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
            "Gate on rift %d %s; core %.0f %.0f %.0f, radius %.0f%s; leads to %s", g.id(), open ? "OPEN" : "CLOSED", c[0], c[1], c[2], d.radius(),
            open ? String.format(java.util.Locale.ROOT, " (held at %.1f)", d.radius() * OPEN_SCALE) : "", where)), true);
        return 1;
    }

    public static int list(CommandSourceStack src) {
        MinecraftServer srv = src.getServer();
        int n = 0;
        for (ServerLevel l : srv.getAllLevels()) {
            for (Gate g : GateData.get(l).gates.values()) {
                n++;
                float[] c = StaticDeeps.core(g.deep());
                ServerLevel dest = destination(srv, g.deep());
                String line = String.format(java.util.Locale.ROOT, "%s rift %d %s, step %d/%d, core %.0f %.0f %.0f, r %.0f, to %s%s, %s", dimId(l), g.id(),
                    g.open() ? "OPEN" : "closed", g.step(), STEPS, c[0], c[1], c[2], g.r(), kind(srv, g.deep()),
                    dest == null ? " (no such level)" : " in " + dimId(dest),
                    g.hasLance() ? "lance at " + BlockPos.of(g.lance()).toShortString() : g.dismantled() >= 0 ? "lance dismantled" : "no lance");
                src.sendSuccess(() -> Component.literal(line), false);
            }
            for (Arrival a : ArrivalData.get(l).arrivals.values())
                src.sendSuccess(() -> Component.literal("  arrival " + a.key() + " in " + dimId(l) + " at " + a.footPos().toShortString()
                    + ", return rift " + a.retPos().toShortString() + (a.dormant() ? " (dormant)" : "")), false);
        }
        if (n == 0) src.sendSuccess(() -> Component.literal("No lance gates"), false);
        return n;
    }
}
