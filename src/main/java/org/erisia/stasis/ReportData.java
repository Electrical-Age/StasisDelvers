package org.erisia.stasis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;

/**
 * Bug reports (client/report, client/StasisReport): the package-private client state of this package, as JSON.
 * Read on the client thread.
 */
public final class ReportData {
    private ReportData() {
    }

    /** LocalTime.Client: all field clocks; chunk clocks of the player's chunk and its 8 neighbours, and the count. */
    public static JsonObject localClocks(int cx, int cz) {
        JsonObject o = new JsonObject();
        JsonObject fields = new JsonObject();
        for (Map.Entry<Integer, double[]> e : LocalTime.Client.FIELDS.entrySet()) {
            double[] v = e.getValue();
            JsonObject f = new JsonObject();
            f.addProperty("clock", v.length > 0 ? v[0] : Double.NaN);
            f.addProperty("step", v.length > 1 ? v[1] : Double.NaN);
            f.addProperty("game_time_received", v.length > 2 ? v[2] : Double.NaN);
            fields.add(String.valueOf(e.getKey()), f);
        }
        o.add("fields", fields);
        JsonObject chunks = new JsonObject();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                Double t = LocalTime.Client.CHUNKS.get(ChunkPos.pack(cx + dx, cz + dz));
                if (t != null) chunks.addProperty((cx + dx) + "," + (cz + dz), t);
            }
        o.add("chunks_near", chunks);
        o.addProperty("chunks_count", LocalTime.Client.CHUNKS.size());
        return o;
    }

    /** Flames.CLIENT (guttering flames): the count, and the ones in the player's chunk. */
    public static JsonObject gutters(int cx, int cz) {
        JsonObject o = new JsonObject();
        o.addProperty("count", Flames.CLIENT.size());
        JsonArray here = new JsonArray();
        for (Map.Entry<Long, Byte> e : Flames.CLIENT.entrySet()) {
            BlockPos p = BlockPos.of(e.getKey());
            if (p.getX() >> 4 != cx || p.getZ() >> 4 != cz) continue;
            JsonObject g = new JsonObject();
            g.addProperty("pos", p.toShortString());
            g.addProperty("light", e.getValue());
            here.add(g);
            if (here.size() >= 64) break;
        }
        o.add("in_player_chunk", here);
        return o;
    }
}
