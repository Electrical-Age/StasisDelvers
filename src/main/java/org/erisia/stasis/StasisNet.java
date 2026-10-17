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
        },
        buf -> new Field(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
            buf.readDouble(), buf.readBoolean(), buf.readDouble(), buf.readInt(), buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readFloat()));

    public record Fields(List<Field> fields, int deepSeed) implements CustomPacketPayload {
        public static final Type<Fields> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "fields"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Fields> CODEC = StreamCodec.composite(
            FIELD.apply(ByteBufCodecs.list()), Fields::fields, ByteBufCodecs.INT, Fields::deepSeed, Fields::new);

        @Override
        public Type<Fields> type() {
            return TYPE;
        }
    }

    /** The level's gate rifts (Gates): their deep seed and centres (packed block positions). */
    public record GateRifts(int seed, List<Long> centres) implements CustomPacketPayload {
        public static final Type<GateRifts> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "gate_rifts"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GateRifts> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, GateRifts::seed, ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list()), GateRifts::centres, GateRifts::new);

        @Override
        public Type<GateRifts> type() {
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

    /** The console's readout: v = {state, stored J, max J, in W, draw W, weather factor, preferred, min, max, target,
     *  radius now, goal, override (0/1), target set by signal (0/1)}; text = the structure line. */
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
        if (!p.level().mayInteract(p, pos)) return null;
        return t;
    }

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

    /** Chunk clocks (LocalTime): packed chunk pos -> frozen time of day. */
    public record ChunkClocks(java.util.Map<Long, Double> clocks) implements CustomPacketPayload {
        public static final Type<ChunkClocks> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "chunk_clocks"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChunkClocks> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.clocks().size());
                p.clocks().forEach((k, v) -> { buf.writeLong(k); buf.writeDouble(v); });
            },
            buf -> {
                int n = buf.readVarInt();
                java.util.Map<Long, Double> m = new java.util.HashMap<>();
                for (int i = 0; i < n; i++) m.put(buf.readLong(), buf.readDouble());
                return new ChunkClocks(m);
            });

        @Override
        public Type<ChunkClocks> type() {
            return TYPE;
        }
    }

    static void sendFieldClocks(ServerLevel level, java.util.Map<Integer, double[]> clocks) {
        FieldClocks f = new FieldClocks(clocks);
        for (ServerPlayer p : level.players()) send(p, f);
    }

    static void sendChunkClocks(ServerLevel level, java.util.Map<Long, Double> clocks) {
        ChunkClocks c = new ChunkClocks(clocks);
        for (ServerPlayer p : level.players()) send(p, c);
    }

    static void sendChunkClocks(ServerPlayer player, java.util.Map<Long, Double> clocks) {
        send(player, new ChunkClocks(clocks));
    }

    /** Client side: the latest freeze progress, for the screen effect. */
    public static volatile float clientFreeze;

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
            var r = e.registrar("8");
            r.playToServer(ConsoleQuery.TYPE, ConsoleQuery.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    TowerBlockEntity t = consoleTower(sp, p.pos());
                    if (t != null) send(sp, t.consoleState());
                }
            });
            r.playToServer(ConsoleSet.TYPE, ConsoleSet.CODEC, (p, ctx) -> {
                if (ctx.player() instanceof ServerPlayer sp) {
                    TowerBlockEntity t = consoleTower(sp, p.pos());
                    if (t == null) return;
                    if (p.override() != t.override())
                        StasisDelvers.LOG.info("{} turned the tower at {} OVERRIDE {}", sp.getName().getString(), p.pos().toShortString(), p.override() ? "ON" : "off");
                    t.setControls(p.target(), p.override());
                    send(sp, t.consoleState());
                }
            });
            r.playToClient(ConsoleState.TYPE, ConsoleState.CODEC, (p, ctx) -> consoleSink.accept(p));
            r.playToClient(Fields.TYPE, Fields.CODEC, (p, ctx) -> {
                ClientFields.deepSeed(p.deepSeed());
                ClientFields.set(p.fields(), ctx.player().level());
            });
            r.playToClient(Freeze.TYPE, Freeze.CODEC, (p, ctx) -> clientFreeze = p.progress());
            r.playToClient(GateRifts.TYPE, GateRifts.CODEC, (p, ctx) -> StaticDeeps.setGates(p.seed(), p.centres()));
            r.playToClient(StormOverride.TYPE, StormOverride.CODEC, (p, ctx) ->
                Storms.clientOverride = p.active() ? new double[]{p.start(), p.end(), p.folded()} : null);
            r.playToClient(FieldClocks.TYPE, FieldClocks.CODEC, (p, ctx) -> LocalTime.Client.fieldClocks(p.clocks(), ctx.player().level()));
            r.playToClient(ChunkClocks.TYPE, ChunkClocks.CODEC, (p, ctx) -> LocalTime.Client.chunkClocks(p.clocks()));
            r.playToClient(Gutters.TYPE, Gutters.CODEC, (p, ctx) -> Flames.clientGutters(p.lights(), ctx.player().level()));
            r.playToClient(Bolt.TYPE, Bolt.CODEC, (p, ctx) -> boltSink.accept(p));
        });
    }

    /** Only to clients that have the mod (a vanilla client, or a test's mock player, would refuse the payload). */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendFields(ServerPlayer player) {
        FieldData d = FieldData.get(player.level());
        send(player, new Fields(d.fields(), d.deepSeed()));
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
        send(player, new GateRifts(FieldData.get(player.level()).deepSeed(), Gates.list(player.level())));
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
