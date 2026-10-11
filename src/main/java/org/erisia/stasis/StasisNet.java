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
        ByteBufCodecs.DOUBLE, Field::collapse, Field::new);

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

    /** Client side: the latest freeze progress, for the screen effect. */
    public static volatile float clientFreeze;

    static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> {
            var r = e.registrar("1");
            r.playToClient(Fields.TYPE, Fields.CODEC, (p, ctx) -> ClientFields.set(p.fields(), ctx.player().level()));
            r.playToClient(Freeze.TYPE, Freeze.CODEC, (p, ctx) -> clientFreeze = p.progress());
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
