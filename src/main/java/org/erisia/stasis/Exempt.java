package org.erisia.stasis;

import com.mojang.serialization.Codec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Players the stasis mechanic leaves alone (Baughn 2026-10-22: a command that disables the whole mechanic for a
 * player): they never freeze (in stopped land, deeps, rifts or at the eye) and run at full speed in slow fields
 * (Freezing.tick, PlayerRates.rate). They still see everything. /stasis exempt <player> on|off; kept with the world
 * (SavedData stasisdelvers:exempt on the overworld).
 */
public final class Exempt {
    private Exempt() {
    }

    /** Server thread: the exempt players (a copy of the saved set, for a cheap per-tick check). */
    private static final Set<UUID> SET = new HashSet<>();

    public static boolean is(ServerPlayer p) {
        return !SET.isEmpty() && SET.contains(p.getUUID());
    }

    static void load(MinecraftServer server) {
        SET.clear();
        SET.addAll(Data.get(server).players);
    }

    public static boolean set(MinecraftServer server, UUID id, boolean on) {
        Data d = Data.get(server);
        boolean changed = on ? d.players.add(id) : d.players.remove(id);
        if (changed) d.setDirty();
        load(server);
        return changed;
    }

    public static final class Data extends SavedData {
        static final Codec<Data> CODEC = UUIDUtil.STRING_CODEC.listOf().xmap(Data::new, d -> new ArrayList<>(d.players));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "exempt"), Data::new, CODEC);

        final Set<UUID> players = new HashSet<>();

        public Data() {
        }

        private Data(List<UUID> l) {
            players.addAll(l);
        }

        static Data get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(TYPE);
        }
    }
}
