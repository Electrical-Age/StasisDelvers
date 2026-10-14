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

    static final StreamCodec<RegistryFriendlyByteBuf, Field> FIELD = StreamCodec.composite(
        ByteBufCodecs.VAR_INT, Field::id, ByteBufCodecs.DOUBLE, Field::x, ByteBufCodecs.DOUBLE, Field::y,
        ByteBufCodecs.DOUBLE, Field::z, ByteBufCodecs.DOUBLE, Field::radius, ByteBufCodecs.DOUBLE, Field::failing,
        ByteBufCodecs.DOUBLE, Field::collapse, ByteBufCodecs.BOOL, Field::column, ByteBufCodecs.DOUBLE, Field::rate, Field::new);

    public record Fields(List<Field> fields) implements CustomPacketPayload {
        public static final Type<Fields> TYPE = new Type<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "fields"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Fields> CODEC =
            FIELD.apply(ByteBufCodecs.list()).map(Fields::new, Fields::fields);

        @Override
        public Type<Fields> type() {
            return TYPE;
        }
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

    static void sendGutters(ServerPlayer player, java.util.Map<Long, Byte> lights) {
        send(player, new Gutters(lights));
    }

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            var r = e.registrar("4");
            r.playToClient(Fields.TYPE, Fields.CODEC, (p, ctx) -> ClientFields.set(p.fields(), ctx.player().level()));
            r.playToClient(Freeze.TYPE, Freeze.CODEC, (p, ctx) -> clientFreeze = p.progress());
            r.playToClient(FieldClocks.TYPE, FieldClocks.CODEC, (p, ctx) -> LocalTime.Client.fieldClocks(p.clocks(), ctx.player().level()));
            r.playToClient(ChunkClocks.TYPE, ChunkClocks.CODEC, (p, ctx) -> LocalTime.Client.chunkClocks(p.clocks()));
            r.playToClient(Gutters.TYPE, Gutters.CODEC, (p, ctx) -> Flames.clientGutters(p.lights()));
        });
    }

    /** Only to clients that have the mod (a vanilla client, or a test's mock player, would refuse the payload). */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendFields(ServerPlayer player) {
        send(player, new Fields(FieldData.get(player.level()).fields()));
    }

    public static void sendFields(ServerLevel level) {
        Fields f = new Fields(FieldData.get(level).fields());
        for (ServerPlayer p : level.players()) send(p, f);
    }

    public static void sendFreeze(ServerPlayer player, float progress) {
        send(player, new Freeze(progress));
    }
}
