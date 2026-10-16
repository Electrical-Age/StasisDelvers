package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.erisia.stasis.Pockets;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StaticDeeps;
import org.erisia.stasis.Strata;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The strata mask for the strata pass (stasis.fsh STRATA_PASS; rift stage 2): which 4-block cells around the camera
 * are strata (Pockets' mask: a strata cavity or vent at one of the cell's 8 corners, on the same 4-block lattice as the
 * server), and whose. 64^3 cells (256 blocks) around the camera, made on a background thread whenever the camera
 * enters another 32-block cell; the old mask stays in use until the new one is ready. A 512 x 512 texture: 8 x 8
 * tiles of 64 x 64 (x across, y down, a tile per z); per texel the deep id (rgb, 24 bits) and a = 255 for strata.
 */
public final class StrataMask {
    private StrataMask() {
    }

    static final Identifier TEX = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "textures/effect/stratamask.png");
    static final int N = 64, CELL = Pockets.CELL, SIZE = 512, SPAN = N * CELL;
    static DynamicTexture texture;

    /** The mask in the texture: its corner (blocks), and whether any cell is strata. */
    static int ox, oy, oz;
    static boolean valid;
    /** The mask being made (or made, not yet uploaded), for this corner and seed. */
    static long jobKey = Long.MIN_VALUE;
    static CompletableFuture<Result> job;

    record Result(int ox, int oy, int oz, int[] abgr, boolean any) {
    }

    static void init() {
        texture = new DynamicTexture(() -> "stasisdelvers strata mask", SIZE, SIZE, true);
        Minecraft.getInstance().getTextureManager().register(TEX, texture);
    }

    /** Each frame (render thread): start a new mask if the camera moved on, upload a finished one; fill the slots. */
    static void frame(double cx, double cy, double cz, int seed, float[] d, double fx, double fy, double fz) {
        if (texture == null) init();
        int nx = Math.floorDiv((int) Math.floor(cx), 32) * 32 - 112, ny = Math.floorDiv((int) Math.floor(cy), 32) * 32 - 112,
            nz = Math.floorDiv((int) Math.floor(cz), 32) * 32 - 112;
        long key = ((long) nx * 73856093L) ^ ((long) ny * 19349663L) ^ ((long) nz * 83492791L) ^ ((long) seed << 20);
        if (seed == 0) valid = false;
        else if (key != jobKey && (job == null || job.isDone())) {
            if (job != null && job.isDone()) upload(job.join());
            jobKey = key;
            // the ground on the lattice (render thread; the job can't read the level): strata above it is open sky
            var mc = net.minecraft.client.Minecraft.getInstance();
            int[] ground = new int[(N + 1) * (N + 1)];
            for (int k = 0; k <= N; k++)
                for (int i = 0; i <= N; i++) {
                    double g = StasisPost.surface(mc, nx + i * CELL, nz + k * CELL, Double.NaN);
                    ground[k * (N + 1) + i] = Double.isNaN(g) ? Integer.MAX_VALUE : (int) g;
                }
            job = CompletableFuture.supplyAsync(() -> make(nx, ny, nz, seed, ground), net.minecraft.util.Util.backgroundExecutor());
        } else if (job != null && job.isDone()) {
            upload(job.join());
            job = null;
        }
        d[184] = (float) (ox - fx);
        d[185] = (float) (oy - fy);
        d[186] = (float) (oz - fz);
        d[187] = valid ? 1 : 0;
    }

    static void upload(Result r) {
        var img = texture.getPixels();
        if (img == null) return;
        for (int i = 0; i < SIZE * SIZE; i++) img.setPixelABGR(i % SIZE, i / SIZE, r.abgr()[i]);
        texture.upload();
        ox = r.ox();
        oy = r.oy();
        oz = r.oz();
        valid = r.any();
    }

    /** The mask with corner (x0, y0, z0): the lattice points first (each Strata.at once), then the cells. */
    static Result make(int x0, int y0, int z0, int seed, int[] ground) {
        int L = N + 1;
        int[] lat = new int[L * L * L];
        java.util.Arrays.fill(lat, -1);
        List<StaticDeeps.Deep> deeps = new ArrayList<>();
        for (StaticDeeps.Deep d : StaticDeeps.near(x0 + SPAN / 2f, y0 + SPAN / 2f, z0 + SPAN / 2f, SPAN / 2f + Pockets.REACH, seed)) {
            if (StaticDeeps.isGate(d)) continue;
            Strata.Bounds b = Strata.bounds(d);
            if (b.maxY() < y0 || b.minY() > y0 + SPAN) continue;
            double dx = Math.max(0, Math.max(x0 - d.x(), d.x() - (x0 + SPAN))), dz = Math.max(0, Math.max(z0 - d.z(), d.z() - (z0 + SPAN)));
            if (dx * dx + dz * dz <= b.radius() * b.radius()) deeps.add(d);
        }
        boolean any = false;
        for (StaticDeeps.Deep d : deeps) {
            Strata.Shape shape = Strata.shapeOf(d);
            Strata.Bounds b = Strata.bounds(d);
            int j0 = Math.max(0, Math.floorDiv(b.minY() - y0, CELL)), j1 = Math.min(N, Math.floorDiv(b.maxY() - y0, CELL) + 1);
            int i0 = Math.max(0, (int) Math.floor((d.x() - b.radius() - x0) / CELL)), i1 = Math.min(N, (int) Math.ceil((d.x() + b.radius() - x0) / CELL));
            int k0 = Math.max(0, (int) Math.floor((d.z() - b.radius() - z0) / CELL)), k1 = Math.min(N, (int) Math.ceil((d.z() + b.radius() - z0) / CELL));
            double r2 = (b.radius() + CELL) * (b.radius() + CELL);
            for (int k = k0; k <= k1; k++)
                for (int i = i0; i <= i1; i++) {
                    double ddx = x0 + i * CELL - d.x(), ddz = z0 + k * CELL - d.z();
                    if (ddx * ddx + ddz * ddz > r2) continue;
                    for (int j = j0; j <= j1; j++) {
                        int li = (k * L + j) * L + i;
                        if (lat[li] >= 0) continue;
                        if (y0 + j * CELL > (long) ground[k * L + i] + 1) continue;   // above the ground: open sky
                        if (Pockets.open(Strata.at(shape, d, x0 + i * CELL, y0 + j * CELL, z0 + k * CELL))) {
                            lat[li] = d.id() & 0xFFFFFF;
                            any = true;
                        }
                    }
                }
        }
        int[] abgr = new int[SIZE * SIZE];
        if (any)
            for (int k = 0; k < N; k++)
                for (int j = 0; j < N; j++)
                    for (int i = 0; i < N; i++) {
                        int id = -1;
                        for (int c = 0; c < 8 && id < 0; c++)
                            id = lat[((k + (c >> 2 & 1)) * L + j + (c >> 1 & 1)) * L + i + (c & 1)];
                        if (id < 0) continue;
                        int px = (k & 7) * N + i, py = (k >> 3) * N + j;
                        abgr[py * SIZE + px] = 0xFF000000 | (id >> 16 & 0xFF) << 16 | (id >> 8 & 0xFF) << 8 | (id & 0xFF);
                    }
        return new Result(x0, y0, z0, abgr, any);
    }
}
