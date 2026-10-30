package org.erisia.stasis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/** Server to client: the fields of the player's level, and the player's freeze progress (for the warning effect). */
public final class StasisNet {
    private StasisNet() {
    }

    static final StreamCodec<RegistryFriendlyByteBuf, Field> FIELD = StreamCodec.of(
        (buf, f) -> {
            buf.writeVarInt(f.id());
            buf.writeDouble(f.x());
            buf.writeDouble(f.y());
            buf.writeDouble(f.z());
            buf.writeDouble(f.radius());
            buf.writeDouble(f.failing());
            buf.writeDouble(f.collapse());
            buf.writeBoolean(f.column());
            buf.writeDouble(f.rate());
            buf.writeInt(f.seed());
            buf.writeLong(f.formedAt());
            buf.writeFloat((float) f.amber());
            buf.writeFloat((float) f.dropout());
            buf.writeFloat((float) f.side());
            var st = f.stretch();
            buf.writeFloat((float) st.e());
            buf.writeFloat((float) st.s());
            buf.writeFloat((float) st.w());
            buf.writeFloat((float) st.n());
        },
        buf -> new Field(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
            buf.readDouble(), buf.readBoolean(), buf.readDouble(), buf.readInt(), buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
            new Field.Stretch(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat())));

    public record Fields(List<Field> fields, int deepSeed) implements CustomPacketPayload {
        public static final Type<Fields> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "fields"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Fields> CODEC = StreamCodec.composite(
            FIELD.apply(ByteBufCodecs.list()), Fields::fields, ByteBufCodecs.INT, Fields::deepSeed, Fields::new);

        @Override
        public Type<Fields> type() {
            return TYPE;
        }
    }

    /**
     * The level's gate rifts (Gates) and lance return rifts (Lance): their deep seed and centres (packed block positions);
     * and the lanced rifts' breach steps (Lance.packStep: id << 4 | step, for steps above 0), so zones and the shader shrink.
     */
    public record GateRifts(int seed, List<Long> centres, List<Long> returns, List<Integer> steps) implements CustomPacketPayload {
        public static final Type<GateRifts> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "gate_rifts"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GateRifts> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, GateRifts::seed, ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), GateRifts::centres,
            ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), GateRifts::returns, ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()),
            GateRifts::steps, GateRifts::new);

        @Override
        public Type<GateRifts> type() {
            return TYPE;
        }
    }

    // ---- spreading mode (Spread, SpreadSync): the state and the rank tiles around the player

    /**
     * The spread's state: reset (login, respawn, dimension change, a new map: forget all tiles), whether the server has
     * a map yet, N, P, hard mode, complete, the End stilled (HardMode), and the storms' origin (game seconds at the
     * hard-mode instant, NaN before: Storms.origin, so the client's tau and storms match the server's). Sent when P
     * moves (at most once a second) and on those occasions.
     */
    public record SpreadState(boolean reset, boolean map, int count, int p, boolean hard, boolean complete, boolean endStill,
                              boolean endEvent, double stormOrigin) implements CustomPacketPayload {
        public static final Type<SpreadState> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spread_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SpreadState> CODEC = StreamCodec.of(
            (buf, s) -> {
                buf.writeByte((s.reset() ? 1 : 0) | (s.map() ? 2 : 0) | (s.hard() ? 4 : 0) | (s.complete() ? 8 : 0) | (s.endStill() ? 16 : 0) | (s.endEvent() ? 32 : 0));
                buf.writeVarInt(s.count());
                buf.writeVarInt(s.p());
                buf.writeDouble(s.stormOrigin());
            },
            buf -> {
                int f = buf.readByte();
                return new SpreadState((f & 1) != 0, (f & 2) != 0, buf.readVarInt(), buf.readVarInt(), (f & 4) != 0, (f & 8) != 0, (f & 16) != 0,
                    (f & 32) != 0, buf.readDouble());
            });

        @Override
        public Type<SpreadState> type() {
            return TYPE;
        }
    }

    /**
     * One rank tile (SpreadTiles: 32 x 32 cells, row by row). On the wire: a tile of one value (all -1 beyond the map's
     * radius) is that value alone; otherwise each rank + 1 as a VarInt (ranks are -1..N-1: 1-3 bytes each, ~3 KB).
     */
    public record SpreadTile(int tx, int tz, int[] ranks) implements CustomPacketPayload {
        public static final Type<SpreadTile> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spread_tile"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SpreadTile> CODEC = StreamCodec.of(
            (buf, t) -> {
                if (t.ranks().length != SpreadTiles.TILE_CELLS) throw new IllegalArgumentException("spread tile: " + t.ranks().length + " cells");
                buf.writeVarInt(t.tx());
                buf.writeVarInt(t.tz());
                int[] r = t.ranks();
                boolean uniform = true;
                for (int v : r) uniform &= v == r[0];
                buf.writeBoolean(uniform);
                if (uniform) buf.writeVarInt(r[0] + 1);
                else for (int v : r) buf.writeVarInt(v + 1);
            },
            buf -> {
                int tx = buf.readVarInt(), tz = buf.readVarInt();
                int[] r = new int[SpreadTiles.TILE_CELLS];
                if (buf.readBoolean()) java.util.Arrays.fill(r, buf.readVarInt() - 1);
                else for (int i = 0; i < r.length; i++) r[i] = buf.readVarInt() - 1;
                return new SpreadTile(tx, tz, r);
            });

        @Override
        public Type<SpreadTile> type() {
            return TYPE;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SpreadTile t && t.tx == tx && t.tz == tz && java.util.Arrays.equals(t.ranks, ranks);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * tx + tz) + java.util.Arrays.hashCode(ranks);
        }
    }

    /** Tiles the client should drop (SpreadTiles.key): the player moved away from them. */
    public record SpreadForget(List<Long> tiles) implements CustomPacketPayload {
        public static final Type<SpreadForget> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "spread_forget"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SpreadForget> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), SpreadForget::tiles, SpreadForget::new);

        @Override
        public Type<SpreadForget> type() {
            return TYPE;
        }
    }

    /** The storm test override (/stasis storm start|stop): active, start, end, earlier overrides' tau offset. */
    public record StormOverride(boolean active, double start, double end, double folded) implements CustomPacketPayload {
        public static final Type<StormOverride> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "storm_override"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StormOverride> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, StormOverride::active, ByteBufCodecs.DOUBLE, StormOverride::start, ByteBufCodecs.DOUBLE, StormOverride::end,
            ByteBufCodecs.DOUBLE, StormOverride::folded, StormOverride::new);

        @Override
        public Type<StormOverride> type() {
            return TYPE;
        }
    }

    // ---- the tower console (CONSOLE-PLAN.md C): the open screen asks for the state twice a second; buttons set
    // the target radius (NaN: the preferred range) and OVERRIDE

    public record ConsoleQuery(net.minecraft.core.BlockPos pos) implements CustomPacketPayload {
        public static final Type<ConsoleQuery> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "console_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleQuery> CODEC = StreamCodec.composite(
            net.minecraft.core.BlockPos.STREAM_CODEC, ConsoleQuery::pos, ConsoleQuery::new);

        @Override
        public Type<ConsoleQuery> type() {
            return TYPE;
        }
    }

    /** Console: start or stop feeding the tower from your batteries and suit. */
    public record ConsoleFeed(net.minecraft.core.BlockPos pos) implements CustomPacketPayload {
        public static final Type<ConsoleFeed> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "console_feed"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleFeed> CODEC = StreamCodec.composite(
            net.minecraft.core.BlockPos.STREAM_CODEC, ConsoleFeed::pos, ConsoleFeed::new);

        @Override
        public Type<ConsoleFeed> type() {
            return TYPE;
        }
    }

    /** Client -> server: dig (left-click) at the stopped liquid in view (StilledLiquids.dig). */
    public record DigStilled() implements CustomPacketPayload {
        public static final Type<DigStilled> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "dig_stilled"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DigStilled> CODEC = StreamCodec.unit(new DigStilled());

        @Override
        public Type<DigStilled> type() {
            return TYPE;
        }
    }

    public record ConsoleSet(net.minecraft.core.BlockPos pos, double target, boolean override) implements CustomPacketPayload {
        public static final Type<ConsoleSet> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "console_set"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleSet> CODEC = StreamCodec.composite(
            net.minecraft.core.BlockPos.STREAM_CODEC, ConsoleSet::pos, ByteBufCodecs.DOUBLE, ConsoleSet::target, ByteBufCodecs.BOOL, ConsoleSet::override,
            ConsoleSet::new);

        @Override
        public Type<ConsoleSet> type() {
            return TYPE;
        }
    }

    /** The console's readout: v = the values TowerBlockEntity.CONSOLE_NAMES names (20: state, store, power, radii,
     *  switches, and for the asker locked / feeding / kJ fed); text = the structure line. */
    /** The console's rate slider: the temporal accelerators' step (TowerBlockEntity.setAccelStep). */
    public record ConsoleRate(net.minecraft.core.BlockPos pos, int step) implements CustomPacketPayload {
        public static final Type<ConsoleRate> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "console_rate"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleRate> CODEC = StreamCodec.composite(
            net.minecraft.core.BlockPos.STREAM_CODEC, ConsoleRate::pos, ByteBufCodecs.VAR_INT, ConsoleRate::step, ConsoleRate::new);

        @Override
        public Type<ConsoleRate> type() {
            return TYPE;
        }
    }

    public record ConsoleState(net.minecraft.core.BlockPos pos, List<Double> v, String text) implements CustomPacketPayload {
        public static final Type<ConsoleState> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "console_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ConsoleState> CODEC = StreamCodec.composite(
            net.minecraft.core.BlockPos.STREAM_CODEC, ConsoleState::pos, ByteBufCodecs.DOUBLE.apply(ByteBufCodecs.list()), ConsoleState::v,
            ByteBufCodecs.STRING_UTF8, ConsoleState::text, ConsoleState::new);

        @Override
        public Type<ConsoleState> type() {
            return TYPE;
        }
    }

    /** Client: where console states go (client/ConsoleScreen), and how a right-click opens it. */
    public static volatile java.util.function.Consumer<ConsoleState> consoleSink = c -> {
    };
    public static volatile java.util.function.Consumer<net.minecraft.core.BlockPos> consoleOpener = p -> {
    };

    /** The tower a player may work the console of: a formed master within 8 blocks, and theirs to touch. */
    public static TowerBlockEntity consoleTower(ServerPlayer p, net.minecraft.core.BlockPos pos) {
        if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 8 * 8) return null;
        if (!(p.level().getBlockEntity(pos) instanceof TowerBlockEntity t) || !t.isMaster()) return null;
        // (spawn protection doesn't close the console, Baughn: a locked tower guards its settings itself)
        if (!p.level().getWorldBorder().isWithinBounds(pos)) return null;
        return t;
    }

    // ---- bug reports (notes/bugreport/SPEC.md 4): the client asks for the server's view when the bug-report key is
    // pressed (client/report/BugReport); the server answers with a JSON string (ReportResponder). Optional channels:
    // a client or server without them still connects, and the report says "no_channel".

    /** look: the block the player looks at (honoured within 8 blocks); face: its Direction ordinal, -1 if none. */
    public record ReportQuery(int nonce, java.util.Optional<net.minecraft.core.BlockPos> look, byte face) implements CustomPacketPayload {
        public static final Type<ReportQuery> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "report_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReportQuery> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, ReportQuery::nonce, net.minecraft.core.BlockPos.STREAM_CODEC.apply(ByteBufCodecs::optional), ReportQuery::look,
            ByteBufCodecs.BYTE, ReportQuery::face, ReportQuery::new);

        @Override
        public Type<ReportQuery> type() {
            return TYPE;
        }
    }

    /** json: at most REPORT_MAX bytes (UTF-8). */
    public record ReportState(int nonce, String json) implements CustomPacketPayload {
        public static final int REPORT_MAX = 64 * 1024;
        public static final Type<ReportState> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "report_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ReportState> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, ReportState::nonce, ByteBufCodecs.stringUtf8(REPORT_MAX), ReportState::json, ReportState::new);

        @Override
        public Type<ReportState> type() {
            return TYPE;
        }
    }

    /** Client: where report replies go (client/report/BugReport). */
    public static volatile java.util.function.Consumer<ReportState> reportSink = s -> {
    };

    /** 0: fine .. 1: frozen. */
    public record Freeze(float progress) implements CustomPacketPayload {
        public static final Type<Freeze> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "freeze"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Freeze> CODEC =
            ByteBufCodecs.FLOAT.<RegistryFriendlyByteBuf>cast().map(Freeze::new, Freeze::progress);

        @Override
        public Type<Freeze> type() {
            return TYPE;
        }
    }

    /** Field clocks (LocalTime): id -> {time of day, step per tick}. */
    public record FieldClocks(java.util.Map<Integer, double[]> clocks) implements CustomPacketPayload {
        public static final Type<FieldClocks> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "field_clocks"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FieldClocks> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.clocks().size());
                p.clocks().forEach((id, v) -> { buf.writeVarInt(id); buf.writeDouble(v[0]); buf.writeDouble(v[1]); });
            },
            buf -> {
                int n = buf.readVarInt();
                java.util.Map<Integer, double[]> m = new java.util.HashMap<>();
                for (int i = 0; i < n; i++) m.put(buf.readVarInt(), new double[]{buf.readDouble(), buf.readDouble()});
                return new FieldClocks(m);
            });

        @Override
        public Type<FieldClocks> type() {
            return TYPE;
        }
    }

    static void sendFieldClocks(ServerLevel level, java.util.Map<Integer, double[]> clocks) {
        FieldClocks f = new FieldClocks(clocks);
        for (ServerPlayer p : level.players()) send(p, f);
    }

    /** Client side: the latest freeze progress, for the screen effect. */
    public static volatile float clientFreeze;

    /** The level's working riftstone rooms (Rooms), whole, whenever one forms, changes or stops, and on joining. */
    public record RoomsSync(List<Rooms.Room> rooms) implements CustomPacketPayload {
        public static final Type<RoomsSync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "rooms"));
        public static final StreamCodec<RegistryFriendlyByteBuf, RoomsSync> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.rooms().size());
                for (Rooms.Room r : p.rooms()) {
                    buf.writeLong(r.key());
                    buf.writeInt(r.x0());
                    buf.writeInt(r.y0());
                    buf.writeInt(r.z0());
                    buf.writeVarInt(r.sx());
                    buf.writeVarInt(r.sy());
                    buf.writeVarInt(r.sz());
                    buf.writeLongArray(r.bits());
                    buf.writeDouble(r.rate());
                }
            },
            buf -> {
                int n = buf.readVarInt();
                List<Rooms.Room> l = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) {
                    long key = buf.readLong();
                    int x0 = buf.readInt(), y0 = buf.readInt(), z0 = buf.readInt(), sx = buf.readVarInt(), sy = buf.readVarInt(), sz = buf.readVarInt();
                    long[] bits = buf.readLongArray();
                    l.add(new Rooms.Room(key, x0, y0, z0, sx, sy, sz, bits, buf.readDouble()));
                }
                return new RoomsSync(l);
            });

        @Override
        public Type<RoomsSync> type() {
            return TYPE;
        }
    }

    static void sendRooms(ServerLevel level, List<Rooms.Room> rooms) {
        RoomsSync r = new RoomsSync(rooms);
        for (ServerPlayer p : level.players()) send(p, r);
    }

    /** Acceleration fields' achieved rates (Speeds: field id -> rate the budget allows), for the client's extra ticks. */
    public record FieldSpeeds(java.util.Map<Integer, Double> achieved) implements CustomPacketPayload {
        public static final Type<FieldSpeeds> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "field_speeds"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FieldSpeeds> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.achieved().size());
                p.achieved().forEach((id, v) -> { buf.writeVarInt(id); buf.writeDouble(v); });
            },
            buf -> {
                int n = buf.readVarInt();
                java.util.Map<Integer, Double> m = new java.util.HashMap<>();
                for (int i = 0; i < n; i++) m.put(buf.readVarInt(), buf.readDouble());
                return new FieldSpeeds(m);
            });

        @Override
        public Type<FieldSpeeds> type() {
            return TYPE;
        }
    }

    static void sendFieldSpeeds(ServerLevel level, java.util.Map<Integer, Double> achieved) {
        FieldSpeeds f = new FieldSpeeds(achieved);
        for (ServerPlayer p : level.players()) send(p, f);
    }

    /** Flame gutters (pos -> light; 0 = recovered). */
    public record Gutters(java.util.Map<Long, Byte> lights) implements CustomPacketPayload {
        public static final Type<Gutters> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "gutters"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Gutters> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.lights().size());
                p.lights().forEach((k, v) -> { buf.writeLong(k); buf.writeByte(v); });
            },
            buf -> {
                int n = buf.readVarInt();
                java.util.Map<Long, Byte> m = new java.util.HashMap<>();
                for (int i = 0; i < n; i++) m.put(buf.readLong(), buf.readByte());
                return new Gutters(m);
            });

        @Override
        public Type<Gutters> type() {
            return TYPE;
        }
    }

    /** A lightning bolt to draw (visual only): from, to (world), seed for its shape. */
    public record Bolt(double fx, double fy, double fz, double tx, double ty, double tz, int seed) implements CustomPacketPayload {
        public static final Type<Bolt> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "bolt"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Bolt> CODEC = StreamCodec.of(
            (buf, b) -> {
                buf.writeDouble(b.fx()); buf.writeDouble(b.fy()); buf.writeDouble(b.fz());
                buf.writeDouble(b.tx()); buf.writeDouble(b.ty()); buf.writeDouble(b.tz());
                buf.writeInt(b.seed());
            },
            buf -> new Bolt(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readInt()));

        @Override
        public Type<Bolt> type() {
            return TYPE;
        }
    }

    /** Clients draw bolts through this (set by the client; the server never calls it). */
    /** Client: the lanced rifts' strengths as they came (seed, id -> share; StasisClient re-meshes the ones that changed). */
    public static volatile java.util.function.BiConsumer<Integer, java.util.Map<Integer, Float>> riftStrengths = (s, m) -> {
    };

    public static volatile java.util.function.Consumer<Bolt> boltSink = b -> {
    };

    /** To players within 160 blocks of the bolt's end. */
    static void sendBolt(ServerLevel level, Bolt b) {
        for (ServerPlayer p : level.players())
            if (p.distanceToSqr(b.tx(), b.ty(), b.tz()) < 160 * 160) send(p, b);
    }

    static void sendGutters(ServerPlayer player, java.util.Map<Long, Byte> lights) {
        send(player, new Gutters(lights));
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            var r = e.registrar(BuildInfo.channelVersion("18"));   // (+ the build commit: a mismatched pack is refused at login)  18: console_rate (the accelerator slider); 17: rooms (riftstone rooms); 16: field_speeds (acceleration fields' achieved rates); 15: gate_rifts carries the lanced rifts' breach steps; 14: gate_rifts carries the lance's return rifts; 13: spread_state carries the End event (fog); 12: the End's stillness and the storms' origin
            r.playToServer(DigStilled.TYPE, DigStilled.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) StilledLiquids.dig(sp);
            });
            r.playToServer(ConsoleQuery.TYPE, ConsoleQuery.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    TowerBlockEntity t = consoleTower(sp, p.pos());
                    if (t != null) send(sp, t.consoleState(sp));
                }
            });
            r.playToServer(ConsoleFeed.TYPE, ConsoleFeed.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    TowerBlockEntity t = consoleTower(sp, p.pos());
                    if (t == null) return;
                    t.toggleFeed(sp);
                    send(sp, t.consoleState(sp));
                }
            });
            r.playToServer(ConsoleSet.TYPE, ConsoleSet.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    TowerBlockEntity t = consoleTower(sp, p.pos());
                    if (t == null) return;
                    if (!t.locked && p.override() != t.override())
                        StasisDelvers.LOG.info("{} turned the tower at {} OVERRIDE {}", sp.getName().getString(), p.pos().toShortString(), p.override() ? "ON" : "off");
                    t.setControls(p.target(), p.override());
                    send(sp, t.consoleState(sp));
                }
            });
            r.playToServer(ConsoleRate.TYPE, ConsoleRate.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    TowerBlockEntity t = consoleTower(sp, p.pos());
                    if (t == null) return;
                    t.setAccelStep(p.step());
                    send(sp, t.consoleState(sp));
                }
            });
            r.playToClient(ConsoleState.TYPE, ConsoleState.CODEC, (p, ctx) -> consoleSink.accept(p));
            r.playToClient(Fields.TYPE, Fields.CODEC, (p, ctx) -> {
                ClientFields.deepSeed(p.deepSeed());
                ClientFields.set(p.fields(), ctx.player().level());
            });
            r.playToClient(Freeze.TYPE, Freeze.CODEC, (p, ctx) -> clientFreeze = p.progress());
            r.playToClient(GateRifts.TYPE, GateRifts.CODEC, (p, ctx) -> {
                StaticDeeps.setGates(p.seed(), p.centres());
                StaticDeeps.setReturns(p.seed(), p.returns());
                var strengths = Lance.strengths(p.steps());
                StaticDeeps.setStrengths(p.seed(), strengths);
                riftStrengths.accept(p.seed(), strengths);
            });
            r.playToClient(StormOverride.TYPE, StormOverride.CODEC, (p, ctx) ->
                Storms.clientOverride = p.active() ? new double[]{p.start(), p.end(), p.folded()} : null);
            r.playToClient(FieldClocks.TYPE, FieldClocks.CODEC, (p, ctx) -> LocalTime.Client.fieldClocks(p.clocks(), ctx.player().level()));
            r.playToClient(Gutters.TYPE, Gutters.CODEC, (p, ctx) -> Flames.clientGutters(p.lights(), ctx.player().level()));
            r.playToClient(FieldSpeeds.TYPE, FieldSpeeds.CODEC, (p, ctx) -> Speeds.clientAchieved(p.achieved()));
            r.playToClient(RoomsSync.TYPE, RoomsSync.CODEC, (p, ctx) -> Rooms.clientSet(ctx.player().level(), p.rooms()));
            r.playToClient(Bolt.TYPE, Bolt.CODEC, (p, ctx) -> boltSink.accept(p));
            r.playToClient(SpreadState.TYPE, SpreadState.CODEC, (p, ctx) ->
                Spread.clientState(p.reset(), p.map(), p.count(), p.p(), p.hard(), p.complete(), p.endStill(), p.endEvent(), p.stormOrigin()));
            r.playToClient(SpreadTile.TYPE, SpreadTile.CODEC, (p, ctx) -> Spread.clientTile(p.tx(), p.tz(), p.ranks()));
            r.playToClient(SpreadForget.TYPE, SpreadForget.CODEC, (p, ctx) -> Spread.clientForget(p.tiles()));
            // optional per channel: a peer without the bug-report channels (same network version) still connects
            var opt = r.optional();
            opt.playToServer(ReportQuery.TYPE, ReportQuery.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    String json = ReportResponder.answer(sp, p);
                    if (json != null) send(sp, new ReportState(p.nonce(), json));
                }
            });
            opt.playToClient(ReportState.TYPE, ReportState.CODEC, (p, ctx) -> reportSink.accept(p));
        });
    }

    /** Only to clients that have the mod (a vanilla client, or a test's mock player, would refuse the payload). */
    static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendFields(ServerPlayer player) {
        FieldData d = FieldData.get(player.level());
        send(player, new Fields(d.fields(), d.deepSeed()));
        send(player, new RoomsSync(Rooms.all(player.level())));
        sendGates(player);
    }

    static StormOverride stormPacket(ServerLevel level) {
        Storms.Forced o = Storms.Forced.get(level);
        return new StormOverride(o.active, o.start, o.end, o.folded);
    }

    static void sendStorm(ServerLevel level) {
        StormOverride p = stormPacket(level);
        for (ServerPlayer pl : level.players()) send(pl, p);
    }

    static void sendGates(ServerPlayer player) {
        send(player, stormPacket(player.level()));
        send(player, new GateRifts(FieldData.get(player.level()).deepSeed(), Gates.list(player.level()), Lance.returns(player.level()),
            Lance.steps(player.level())));
    }

    public static void sendFields(ServerLevel level) {
        FieldData d = FieldData.get(level);
        Fields f = new Fields(d.fields(), d.deepSeed());
        for (ServerPlayer p : level.players()) send(p, f);
    }

    public static void sendFreeze(ServerPlayer player, float progress) {
        send(player, new Freeze(progress));
    }
}
