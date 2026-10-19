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
        return new Deep(x, y, z, r, Noise.hashInt(i, j, k, seed ^ 0x1D1D) & 0xFFFFFF);
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

    // ---- return rifts (Lance: the small rift beside each lance arrival pad, leading home; docs/lance.md)

    /** A return rift's radius: its core (0.35 r, about a block) hangs at body height over the pad's far end. */
    public static final float RETURN_RADIUS = 3;

    /** Return rifts per deep seed (the Nether's, the End's), as GATES: saved by Lance in the destination level, synced
     *  with the gate rifts so clients draw them. */
    private static final java.util.Map<Integer, List<Deep>> RETURNS = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile java.util.Set<Integer> returnIds = java.util.Set.of();

    /** A return rift at this centre block (pure: both sides). */
    public static Deep returnRift(net.minecraft.core.BlockPos p) {
        int id = (int) (Noise.hash32(p.getX(), p.getY(), p.getZ(), 0x6E7B) * 0xFFFFFF) | 1;
        return new Deep(p.getX() + 0.5f, p.getY() + 0.5f, p.getZ() + 0.5f, RETURN_RADIUS, id);
    }

    /** Set the return rifts for a deep seed (server: Lance.publish; client: the GateRifts packet). */
    public static void setReturns(int seed, java.util.Collection<Long> centres) {
        List<Deep> l = new ArrayList<>();
        for (long c : centres) l.add(returnRift(net.minecraft.core.BlockPos.of(c)));
        if (l.isEmpty()) RETURNS.remove(seed);
        else RETURNS.put(seed, List.copyOf(l));
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        RETURNS.values().forEach(g -> g.forEach(d -> ids.add(d.id())));
        returnIds = java.util.Set.copyOf(ids);
        SECTIONS.clear();
    }

    // ---- lanced rifts' strength (Lance: a breach steps a rift's effective radius down; docs/lance.md)

    /** Packed (seed, id) -> the share of its radius a rift has now (< 1 only for rifts a lance holds; else absent). */
    private static volatile java.util.Map<Long, Float> strengths = java.util.Map.of();

    static long strengthKey(int seed, int id) {
        return (long) seed << 32 | (id & 0xFFFFFFFFL);
    }

    /**
     * Set the strengths of the lanced rifts of a deep seed (server: Lance.publish; client: the GateRifts packet): id ->
     * share of the radius (absent or 1: whole). Returns the ids whose strength changed (to re-mesh them on the client).
     */
    public static java.util.Set<Integer> setStrengths(int seed, java.util.Map<Integer, Float> byId) {
        java.util.Map<Long, Float> now = new java.util.HashMap<>();
        java.util.Set<Integer> changed = new java.util.HashSet<>();
        for (var e : strengths.entrySet()) {
            if ((int) (e.getKey() >> 32) != seed) now.put(e.getKey(), e.getValue());
            else if (byId.getOrDefault((int) (long) e.getKey(), 1f) != (float) e.getValue()) changed.add((int) (long) e.getKey());
        }
        for (var e : byId.entrySet()) {
            if (e.getValue() >= 1f) continue;
            Float old = strengths.get(strengthKey(seed, e.getKey()));
            if (old == null || old.floatValue() != e.getValue()) changed.add(e.getKey());
            now.put(strengthKey(seed, e.getKey()), e.getValue());
        }
        strengths = java.util.Map.copyOf(now);
        return changed;
    }

    /** The share of its radius rift d (of this deep seed) has now: 1 unless a lance holds it smaller (Lance). */
    public static float strength(int seed, Deep d) {
        if (strengths.isEmpty()) return 1f;
        Float s = strengths.get(strengthKey(seed, d.id()));
        return s == null ? 1f : s;
    }

    /** d's radius as it is now (Lance's breach shrinks it; zones, freezing and the shader follow). */
    public static float effectiveRadius(int seed, Deep d) {
        return d.radius() * strength(seed, d);
    }

    /** Is d a lance's return rift? */
    public static boolean isReturn(Deep d) {
        return d.radius() == RETURN_RADIUS && returnIds.contains(d.id());
    }

    /** Is d a stronghold's gate rift (not the End rift, not a return rift)? */
    public static boolean isStrongholdGate(Deep d) {
        return d.radius() == Gates.GATE_RADIUS && gateIds.contains(d.id());
    }

    // ---- the End's rift (spreading mode, hard mode: EndRift; spreading-mode.md)

    /** The End rift's radius: its core (0.35 r, ~14 blocks) is the black eye the floating end-stone islands circle. */
    public static final float END_RIFT_RADIUS = 40;
    /** The End rift's centre: over the old main island's middle (0, 0), at its old surface's height. */
    public static final int END_RIFT_Y = 64;

    /** Deep seeds of End levels whose island has exploded into the rift (both sides; seed-keyed as GATES). */
    private static volatile java.util.Set<Integer> endRiftSeeds = java.util.Set.of(), endRiftIds = java.util.Set.of();

    /** The End rift of the End level with this deep seed (pure: both sides compute it). */
    public static Deep endRift(int seed) {
        return new Deep(0.5f, END_RIFT_Y + 0.5f, 0.5f, END_RIFT_RADIUS, (Noise.hashInt(seed, 0xE4D, 7, 0x3E1F) & 0xFFFFFF) | 1);
    }

    /**
     * From now on the level with this deep seed has the End rift (server: hard mode in spreading mode, EndRift; client:
     * in the End with the spread's hard flag). Add only: a seed never loses it (hard mode is once only).
     */
    public static void addEndRift(int seed) {
        if (seed == 0 || endRiftSeeds.contains(seed)) return;
        java.util.Set<Integer> s = new java.util.HashSet<>(endRiftSeeds), ids = new java.util.HashSet<>(endRiftIds);
        s.add(seed);
        ids.add(endRift(seed).id());
        endRiftSeeds = java.util.Set.copyOf(s);
        endRiftIds = java.util.Set.copyOf(ids);
        SECTIONS.clear();
    }

    /** Does the level with this deep seed have the End rift? */
    public static boolean hasEndRift(int seed) {
        return endRiftSeeds.contains(seed);
    }

    /** Server stopped / client left: the next world starts without. */
    public static void clearEndRifts() {
        endRiftSeeds = java.util.Set.of();
        endRiftIds = java.util.Set.of();
        RETURNS.clear();
        returnIds = java.util.Set.of();
        strengths = java.util.Map.of();
        SECTIONS.clear();
    }

    /** Bug reports: the gate rifts known for this deep seed. */
    public static int gateCount(int seed) {
        List<Deep> g = GATES.get(seed);
        return g == null ? 0 : g.size();
    }

    /** Is d a gate rift (no strata, no worldgen of its own)? The End rift is one too: no strata, no puffs; it leads to
     *  the End (Gates.destination), where it stands. So is a lance's return rift (Lance). */
    public static boolean isGate(Deep d) {
        return d.radius() == Gates.GATE_RADIUS && gateIds.contains(d.id()) || d.radius() == END_RIFT_RADIUS && endRiftIds.contains(d.id())
            || isReturn(d);
    }

    /** The static deeps whose cells touch the box around (x, y, z) with this margin: at most 8, plus gate rifts. */
    public static List<Deep> near(float x, float y, float z, float margin, int seed) {
        List<Deep> out = new ArrayList<>(2);
        if (endRiftSeeds.contains(seed)) {   // first: the shader keeps the first 4
            Deep e = endRift(seed);
            if (Math.abs(e.x() - x) <= margin + e.radius() && Math.abs(e.y() - y) <= margin + e.radius() && Math.abs(e.z() - z) <= margin + e.radius())
                out.add(e);
        }
        for (Deep g : GATES.getOrDefault(seed, List.of()))
            if (Math.abs(g.x() - x) <= margin + g.radius() && Math.abs(g.y() - y) <= margin + g.radius() && Math.abs(g.z() - z) <= margin + g.radius())
                out.add(g);
        for (Deep g : RETURNS.getOrDefault(seed, List.of()))
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

    /** The centre of d's warped body (where shape() is smallest: x + warp(x) = centre), for the eye and its touch. */
    public static float[] core(Deep d) {
        float x = d.x(), y = d.y(), z = d.z();
        float k = Math.min(1f, d.radius() * d.radius() / 576f);
        for (int i = 0; i < 6; i++) {
            float wx = (Noise.value32(x / 18f, y / 18f, z / 18f, d.id()) - 0.5f) * 16 * k;
            float wy = (Noise.value32(x / 18f + 31, y / 18f, z / 18f, d.id()) - 0.5f) * 10 * k;
            float wz = (Noise.value32(x / 18f, y / 18f + 47, z / 18f, d.id()) - 0.5f) * 16 * k;
            x = d.x() - wx;
            y = d.y() - wy;
            z = d.z() - wz;
        }
        return new float[]{x, y, z};
    }

    /** The eye's half-width at d's core (stasis.fsh: W) for a radius (the effective one); its touch reaches that far. */
    public static float eyeHalfWidth(float radius) {
        return Math.max(0.75f, Math.min(1.8f, 0.6f + radius * 0.018f));
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
            // a lanced rift shrinks round its eye (shape / strength: the warped core stays put; worldgen reads shape)
            float s = shape(d, x, y, z) / strength(seed, d);
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

    /** Per deep seed (each level has its own; worldgen, light and client threads ask at once): section -> may have. */
    private static final java.util.Map<Integer, java.util.Map<Long, Boolean>> SECTIONS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Can a static deep reach into this 16-block section at all? Cached; rejects almost everywhere at once. */
    static boolean sectionMayHave(int seed, int sx, int sy, int sz) {
        if (SECTIONS.size() > 8) SECTIONS.clear();         // (tests try many seeds)
        java.util.Map<Long, Boolean> sections = SECTIONS.computeIfAbsent(seed, k -> new java.util.concurrent.ConcurrentHashMap<>());
        if (sections.size() > 200_000) sections.clear();
        long key = ((long) sx & 0x3FFFFF) << 42 | ((long) sy & 0xFFFFF) << 22 | ((long) sz & 0x3FFFFF);
        return sections.computeIfAbsent(key, k -> {
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
