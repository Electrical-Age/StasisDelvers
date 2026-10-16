package org.erisia.stasis;

import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Static deeps (Baughn, design v3): rarer than the drifting kind, everywhere including underground, and they never
 * move. Each one is organic (a domain-warped blob with a noisy radius) and bottoms out in a rift: a core where the
 * stasis is so strong that no light gets through and nothing protects (any player freezes into a statue in about
 * 3 s), and that no field can hold. Worldgen (DeepFeature) breaks the ground around them in new chunks. Placement: a
 * jittered 3D grid of 512 x 192 x 512 blocks, about 25% of cells holding one, radius 24-64; the rift is the inner 35%.
 * Both sides compute the same deeps from the level's deep seed; the shader gets the nearby ones.
 */
public final class StaticDeeps {
    private StaticDeeps() {
    }

    static final int CX = 512, CY = 192, CZ = 512;
    static final float OCCUPANCY = 0.25f, RIFT = 0.35f;

    public record Deep(float x, float y, float z, float radius, int id) {
    }

    /** The static deep of grid cell (i, j, k), or null. */
    public static Deep cell(int i, int j, int k, int seed) {
        // only the cells that overlap the overworld's build height (-64..320): j = -1, 0, 1
        if (j < -1 || j > 1 || Noise.hash32(i, j, k, seed ^ 0x57A7) >= OCCUPANCY) return null;
        float r = 24 + 40 * Noise.hash32(i, j, k, seed ^ 0x57A8);
        float x = i * CX + r + (CX - 2 * r) * Noise.hash32(i, j, k, seed ^ 0x57A9);
        float y = Math.max(-50, Math.min(280, j * CY + r * 0.6f + (CY - 1.2f * r) * Noise.hash32(i, j, k, seed ^ 0x57AA)));
        float z = k * CZ + r + (CZ - 2 * r) * Noise.hash32(i, j, k, seed ^ 0x57AB);
        // 24-bit id: the shader gets it as an exact float
        return new Deep(x, y, z, r, ((i * 73856093) ^ (j * 19349663) ^ (k * 83492791) ^ seed) & 0xFFFFFF);
    }

    /** Gate rifts (Gates: stronghold portal rooms), per deep seed (each level has its own): extra static deeps, known
     *  once their room generates. (One list for all levels lost the Overworld's gates when the End loaded after it.) */
    private static final java.util.Map<Integer, List<Deep>> GATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile java.util.Set<Integer> gateIds = java.util.Set.of();

    /** Set the gate rifts for a deep seed (server: Gates.publish; client: the Gates packet). */
    public static void setGates(int seed, java.util.Collection<Long> centres) {
        List<Deep> l = new ArrayList<>();
        for (long c : centres) l.add(Gates.gate(net.minecraft.core.BlockPos.of(c)));
        if (l.isEmpty()) GATES.remove(seed);
        else GATES.put(seed, List.copyOf(l));
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        GATES.values().forEach(g -> g.forEach(d -> ids.add(d.id())));
        gateIds = java.util.Set.copyOf(ids);
        SECTIONS.clear();
    }

    /** Is d a gate rift (no strata, no worldgen of its own)? */
    public static boolean isGate(Deep d) {
        return d.radius() == Gates.GATE_RADIUS && gateIds.contains(d.id());
    }

    /** The static deeps whose cells touch the box around (x, y, z) with this margin: at most 8, plus gate rifts. */
    public static List<Deep> near(float x, float y, float z, float margin, int seed) {
        List<Deep> out = new ArrayList<>(2);
        for (Deep g : GATES.getOrDefault(seed, List.of()))
            if (Math.abs(g.x() - x) <= margin + g.radius() && Math.abs(g.y() - y) <= margin + g.radius() && Math.abs(g.z() - z) <= margin + g.radius())
                out.add(g);
        int i0 = (int) Math.floor((x - margin) / CX), i1 = (int) Math.floor((x + margin) / CX);
        int j0 = (int) Math.floor((y - margin) / CY), j1 = (int) Math.floor((y + margin) / CY);
        int k0 = (int) Math.floor((z - margin) / CZ), k1 = (int) Math.floor((z + margin) / CZ);
        for (int i = i0; i <= i1; i++)
            for (int j = j0; j <= j1; j++)
                for (int k = k0; k <= k1; k++) {
                    Deep d = cell(i, j, k, seed);
                    if (d != null) out.add(d);
                }
        return out;
    }

    /**
     * The organic distance measure of a point to deep d: the distance warped by noise, relative to the radius
     * (<= 1 inside, <= RIFT in the rift). The shader has the same (stasis.fsh: staticShape).
     */
    public static float shape(Deep d, float x, float y, float z) {
        // domain warp: the blob bulges and sends out lobes
        // (smaller for the small gate rifts, r < 24: else the warp moves their core off the room's middle)
        float k = Math.min(1f, d.radius() * d.radius() / 576f);
        float wx = (Noise.value32(x / 18f, y / 18f, z / 18f, d.id()) - 0.5f) * 16 * k;
        float wy = (Noise.value32(x / 18f + 31, y / 18f, z / 18f, d.id()) - 0.5f) * 10 * k;
        float wz = (Noise.value32(x / 18f, y / 18f + 47, z / 18f, d.id()) - 0.5f) * 16 * k;
        float dx = x + wx - d.x(), dy = (y + wy - d.y()) * 1.4f, dz = z + wz - d.z();
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float rough = 1 + 0.3f * (Noise.fbm32(x / 9f, y / 9f, z / 9f, d.id() ^ 0x77) - 0.5f) * 2;
        return dist / (d.radius() * rough);
    }

    /** 0 outside, 1 = the outer zone, 2 = the rift, at a block (static deeps; only in stopping dimensions). */
    public static int zone(Level level, int x, int y, int z) {
        if (!Stasis.stops(level)) return 0;
        return zone(Deeps.seed(level), x + 0.5f, y + 0.5f, z + 0.5f);
    }

    public static int zone(int seed, float x, float y, float z) {
        if (seed == 0 || !sectionMayHave(seed, (int) Math.floor(x) >> 4, (int) Math.floor(y) >> 4, (int) Math.floor(z) >> 4)) return 0;
        int best = 0;
        for (Deep d : near(x, y, z, 100, seed)) {
            float dx = x - d.x(), dy = y - d.y(), dz = z - d.z();
            if (dx * dx + dy * dy + dz * dz > (d.radius() + 20) * (d.radius() + 20)) continue;
            float s = shape(d, x, y, z);
            if (s <= RIFT) return 2;
            if (s <= 1) best = 1;
        }
        return best;
    }

    public static boolean inRift(Level level, int x, int y, int z) {
        if (!Stasis.stops(level)) return false;
        int seed = Deeps.seed(level);
        if (seed == 0 || !sectionMayHave(seed, x >> 4, y >> 4, z >> 4)) return false;
        return zone(seed, x + 0.5f, y + 0.5f, z + 0.5f) == 2;
    }

    private static final java.util.Map<Long, Boolean> SECTIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile int sectionsSeed;

    /** Can a static deep reach into this 16-block section at all? Cached; rejects almost everywhere at once. */
    static boolean sectionMayHave(int seed, int sx, int sy, int sz) {
        if (sectionsSeed != seed) {
            SECTIONS.clear();
            sectionsSeed = seed;
        }
        if (SECTIONS.size() > 200_000) SECTIONS.clear();
        long key = ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
        return SECTIONS.computeIfAbsent(key, k -> {
            float cx = sx * 16 + 8, cy = sy * 16 + 8, cz = sz * 16 + 8;
            for (Deep d : near(cx, cy, cz, 120, seed)) {
                float dx = cx - d.x(), dy = cy - d.y(), dz = cz - d.z();
                float reach = d.radius() * 1.4f + 28 + 14;   // warp, roughness and the section's own half-diagonal
                if (dx * dx + dy * dy + dz * dz <= reach * reach) return true;
            }
            return false;
        });
    }
}
