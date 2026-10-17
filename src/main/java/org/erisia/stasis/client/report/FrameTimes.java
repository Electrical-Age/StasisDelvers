package org.erisia.stasis.client.report;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.Arrays;

/** The last 600 frame times, for the bug report's p50/p95/p99/max (stutter a single fps value hides). Render thread. */
public final class FrameTimes {
    private FrameTimes() {
    }

    static final int N = 600;
    private static final long[] RING = new long[N];
    private static int next, count;

    public static void frame() {
        long ns = Minecraft.getInstance().getFrameTimeNs();
        if (ns <= 0) return;
        RING[next] = ns;
        next = (next + 1) % N;
        if (count < N) count++;
    }

    static JsonObject percentiles() {
        JsonObject o = new JsonObject();
        o.addProperty("samples", count);
        if (count == 0) return o;
        long[] s = Arrays.copyOf(count == N ? RING : Arrays.copyOf(RING, count), count);
        Arrays.sort(s);
        o.addProperty("p50_ms", s[(int) (0.50 * (count - 1))] / 1e6);
        o.addProperty("p95_ms", s[(int) (0.95 * (count - 1))] / 1e6);
        o.addProperty("p99_ms", s[(int) (0.99 * (count - 1))] / 1e6);
        o.addProperty("max_ms", s[count - 1] / 1e6);
        return o;
    }
}
