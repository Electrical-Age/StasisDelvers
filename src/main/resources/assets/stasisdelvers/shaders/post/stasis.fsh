#version 330
#extension GL_ARB_separate_shader_objects : require

// Stasis Delvers: drain the colour of everything where time is stopped, and the player's freeze warning (grey
// creeping in from the screen edges). Field data comes in a 1024x4 texture of packed floats (StasisPost.java; slot i
// at (i % 1024, i / 1024)):
// [0..15] inverse(projection * view rotation); [16..18] floor(camera); [19..21] fract(camera); [22] stops here (0/1);
// [23] freeze warning 0..1; [24] field count; [25..28] spawn area min x, min z, max x, max z (blocks; empty if
// min > max); [29] time (s); [30] eddy time (s, Eddies.time); [31] drifting deep count; [168 + 2i, 169 + 2i] static
// deep i's lowest and highest ground - floor(camera y); [176..182] the mist's clocks (mistWind ..); [184..186] the strata
// mask's corner - floor(camera); [187] strata mask valid (0/1); [188..191] the weather clock mod each pocket epoch
// length (600, 900, 1200, 1800 s); [192..195] floor(clock / that length); [196] spreading mode decides the
// columns here (0/1: Spread.active), [197] its progress P (exact), [198, 199] the first cell (x, z, global cell
// indices) of the rank window in SpreadSampler (64 x 64 cells, one rank per texel as int bits in r..a, MISSING =
// 2147483647 where no tile came: runs); [200..203] static deep
// i's eye: facing yaw (radians, turned by saccades) + 10 x destination (0 the End, 1 the Nether, 2 other); [204 + 3i ..]
// static deep i's eye centre (the warped core) - floor(camera); [1580 + 4i] its NDC x, y, distance, in front (0/1);
// [1596] strain on reality (0..1, nearness to a core); [1597 + i] static deep i's inhale before a puff (0..1); [216] render distance (blocks); [1312] storm
// intensity; [1313] storm warning 0..1; [1314] wind angle; [1315, 1316] the storm field's noise coordinates at
// floor(camera) (x, z); [1317, 1318] its mist and rift thresholds; [1319] its noise seed; [1320 + 16j + i] the ground
// (y - floor(camera y)) on a 16 x 16 grid of spacing 16 from [1576, 1577] (corner - floor(camera));
// [1024 + 12i ..] drifting deep i (i < MAX_DEEPS, nearest first): centre - floor(camera) (x, z), radius, strength,
// surface y - floor(camera y), edge-noise id, lowest and highest ground - floor(camera y), ground grid corner -
// floor(camera) (x, z) and spacing, 1 unused; [2048 + 64i ..] drifting deep i's ground grid (8 x 8 surface y -
// floor(camera y), row = z); [3584 + 64i ..] static deep i's (corner centre - (1.3 r + 10), spacing 2 (1.3 r + 10) / 7);
// [256] bolt point count; [218] static deep count; [220 + 5i ..] static deep i: centre - floor(camera) (3), radius,
// shape id; [240 + i] static deep i's strength (0.25..1: a lance's breach shrinks it round its eye, Lance; 1 unlanced);
// [260 + 5i ..] bolt point i: NDC x, y, depth, half width (NDC), intensity (0 breaks the line); [32 + 10i ..] field i: centre - floor(camera) (3), radius, collapse, failing,
// column (0/1), seed (24-bit, exact), seconds since it formed (-1: no start-up animation), reserved (a remnant: the time
// [29] as it froze). Column 2: a frozen remnant.
// [1700 + 4i ..] field i's shield: amber share, dropout share, side (yaw, radians: towards (cos, 0, sin)), unused (a
// remnant: the eddy time [30] as it froze). [1752 + 4i ..] field i's reach east, south, west, north as multiples of
// its radius (Field.Stretch: a tower's booms stretch it; 1 = round).
// The shield is drawn here too, after the desaturation, so its colour survives (Baughn's option a): each field's
// sphere is hit by the view ray, and the hit point is placed on a Goldberg-style hexagon grid (a triangular
// lattice on each icosahedron face; the nearest lattice point is the cell, the gap to the second nearest its rim).

#if !defined(MIST_PASS) && !defined(STRATA_PASS)
#define FULL_PASS
#endif
#ifdef FULL_PASS
uniform sampler2D InSampler;
#endif
uniform sampler2D DepthSampler;
uniform sampler2D DataSampler;
uniform sampler2D MistSampler;
#ifdef FULL_PASS
uniform sampler2D MistBufSampler;
uniform sampler2D StrataBufSampler;
uniform sampler2D SpreadSampler;
#endif
#ifdef STRATA_PASS
uniform sampler2D StrataSampler;
#endif

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float data(int i) {
    uvec4 b = uvec4(round(texelFetch(DataSampler, ivec2(i & 1023, i >> 10), 0) * 255.0));
    return uintBitsToFloat(b.r | (b.g << 8) | (b.b << 16) | (b.a << 24));
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

// The 32-bit noise of Noise.java (hash32, value32, fbm32), for patterns the server uses too (collapse).
float hash32(int x, int y, int z, int seed) {
    // (Noise.hashInt: coordinates mixed in one after another, so mirrored cells don't collide)
    uint h = uint(x) * 0x8da6b343u;
    h ^= h >> 15;
    h += uint(y) * 0xd8163841u;
    h ^= h >> 13;
    h += uint(z) * 0xcb1ab31fu;
    h ^= h >> 16;
    h += uint(seed) * 0x9e3779b9u;
    h ^= h >> 16;
    h *= 0x7feb352du;
    h ^= h >> 15;
    h *= 0x846ca68bu;
    h ^= h >> 16;
    return float(h >> 8) / 16777216.0;
}

float value32(vec3 q, int seed) {
    vec3 fl = floor(q);
    ivec3 i = ivec3(fl);
    vec3 f = q - fl;
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(hash32(i.x, i.y, i.z, seed), hash32(i.x + 1, i.y, i.z, seed), f.x);
    float b = mix(hash32(i.x, i.y + 1, i.z, seed), hash32(i.x + 1, i.y + 1, i.z, seed), f.x);
    float c = mix(hash32(i.x, i.y, i.z + 1, seed), hash32(i.x + 1, i.y, i.z + 1, seed), f.x);
    float d = mix(hash32(i.x, i.y + 1, i.z + 1, seed), hash32(i.x + 1, i.y + 1, i.z + 1, seed), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

float fbm32(vec3 q, int seed) {
    return 0.65 * value32(q, seed) + 0.35 * value32(q * 2.03 + vec3(17.1, 5.3, 11.7), seed);
}

// Field.collapseScore: block = world block, off = block centre - field centre (y 0 for columns), dist = distance
// used for the radius share, side = the yaw the collapse leans to. A block has stopped when 1 - score < 1.6 collapse.
float collapseScore(vec3 block, vec3 off, float dist, float r, int seed, float side) {
    float score = 0.45 * min(1.0, dist / r) + 0.55 * fbm32(block / 6.0, seed);
    float len = length(off);
    if (len > 1e-3) {
        int tears = 2 + int(hash32(seed, 7, 0, 0) * 3.0);
        for (int i = 0; i < tears; i++) {
            vec3 nv = vec3(hash32(seed, i, 1, 0), hash32(seed, i, 2, 0), hash32(seed, i, 3, 0)) * 2.0 - 1.0;
            float band = abs(dot(off, nv) / (len * (length(nv) + 1e-6)));
            if (band < 0.07) score -= 0.35 * (1.0 - band / 0.07);
        }
        score += 0.25 * (off.x * cos(side) + off.z * sin(side)) / len;
    }
    return score;
}

// Field.Stretch of field i (east, south, west, north)
vec4 stretchOf(int i) {
    int b = 1752 + 4 * i;
    return vec4(data(b), data(b + 1), data(b + 2), data(b + 3));
}

// d (from a field's centre) in the field's round space: the stretch on d's side divided out (Field.norm * r)
vec3 unstretch(vec3 d, vec4 st) {
    return vec3(d.x / (d.x >= 0.0 ? st.x : st.z), d.y, d.z / (d.z >= 0.0 ? st.y : st.w));
}

// The view ray's span through a stretched field (oc = ray origin - centre): each quarter (by the signs of x and z) is
// the sphere of radius r scaled, so the ray is cut at x = 0 and z = 0 and each piece solved as a sphere; the shape is
// convex, so the pieces' spans join into one (t1 the entry, t2 the exit).
bool fieldSpan(vec3 oc, vec3 v, float r, vec4 st, out float t1, out float t2) {
    float bx = abs(v.x) > 1e-7 ? -oc.x / v.x : 1e30, bz = abs(v.z) > 1e-7 ? -oc.z / v.z : 1e30;
    float b0 = min(bx, bz), b1 = max(bx, bz);
    t1 = 1e30;
    t2 = -1e30;
    for (int j = 0; j < 3; j++) {
        float lo = j == 0 ? -1e30 : j == 1 ? b0 : b1, hi = j == 0 ? b0 : j == 1 ? b1 : 1e30;
        if (hi <= lo) continue;
        // a point inside the piece, to tell its quarter
        float tm = j == 0 ? (b0 < 1e29 ? b0 - 1.0 : 0.0) : j == 1 ? (b1 < 1e29 ? 0.5 * (b0 + b1) : b0 + 1.0) : b1 + 1.0;
        vec3 pm = oc + v * tm;
        vec3 sc = vec3(pm.x >= 0.0 ? st.x : st.z, 1.0, pm.z >= 0.0 ? st.y : st.w);
        vec3 o2 = oc / sc, v2 = v / sc;
        float aa = dot(v2, v2), b = dot(o2, v2), cc = dot(o2, o2) - r * r, disc = b * b - aa * cc;
        if (disc <= 0.0) continue;
        float sq = sqrt(disc), e1 = max((-b - sq) / aa, lo), e2 = min((-b + sq) / aa, hi);
        if (e2 <= e1) continue;
        t1 = min(t1, e1);
        t2 = max(t2, e2);
    }
    return t2 > t1;
}

// Eddies.value: the drifting eddy noise of a weak field (wind from the seed, 0.7 blocks/s)
float eddy(vec3 w, float t, int seed) {
    float ang = hash32(seed, 11, 0, 0) * 6.2831853;
    vec2 vel = vec2(cos(ang), sin(ang)) * 0.7;
    return fbm32(vec3((w.x - vel.x * t) / 6.0, w.y / 6.0 + t * 0.05, (w.z - vel.y * t) / 6.0), seed ^ 0x5eed);
}

float eddyShare(float collapse, float failing) {
    if (collapse > 0.0) return 0.35 + 0.25 * collapse;
    return 0.15 + 0.3 * min(1.0, (failing - 0.7) / 0.3);
}

// StaticDeeps.shape: the organic distance of world point w to static deep i (<= 0.35 rift, <= 1 outer zone)
float staticShape(int i, vec3 w, vec3 camI) {
    int o = 220 + 5 * i;
    vec3 c = vec3(data(o), data(o + 1), data(o + 2)) + camI;
    float R = data(o + 3);
    int id = int(data(o + 4));
    vec3 wp = vec3(value32(w / 18.0, id), value32(w / 18.0 + vec3(31.0, 0.0, 0.0), id), value32(w / 18.0 + vec3(0.0, 47.0, 0.0), id)) - 0.5;
    vec3 dv = w + wp * vec3(16.0, 10.0, 16.0) * min(1.0, R * R / 576.0) - c;   // (StaticDeeps.shape: less warp for gate rifts)
    dv.y *= 1.4;
    float rough = 1.0 + 0.3 * (fbm32(w / 9.0, id ^ 0x77) - 0.5) * 2.0;
    return length(dv) / (R * rough * data(240 + i));   // (a lanced rift: the warp stays the whole rift's, the core put)
}

// Drifting deeps (Deeps): [DEEP_BASE + DEEP_STRIDE * i ..], i < MAX_DEEPS (StasisPost has the same constants)
const int DEEP_BASE = 1024, DEEP_STRIDE = 12, MAX_DEEPS = 24, GROUND_BASE = 2048;
const float DEEP_EDGE = 6.0;                         // nothing past radius + 6 (ragged edge +-3)
const float DEEP_BELOW = 8.0, DEEP_ABOVE = 64.0;     // height band: surface - 8 .. surface + 64 (soft at both ends)
const float MIST_ABOVE = 40.0;                       // the mist: lowest ground - 8 .. highest ground + 40

struct Deep {
    vec2 c;      // centre - floor(camera) (x, z)
    float R, st, yb;
    int id;      // edge noise
};

Deep loadDeep(int i) {
    int o = DEEP_BASE + DEEP_STRIDE * i;
    float st = data(o + 3);
    return Deep(vec2(data(o), data(o + 1)), data(o + 2), st > 1.5 ? st - 2.0 : st, data(o + 4), int(data(o + 5)));
}

// Deeps.horizontal plus the height band, for one deep: intensity 0..1 at q (= world - floor(camera)); camI =
// floor(camera) for the edge noise (world coordinates). Zero outside the cylinder radius + DEEP_EDGE and the band
// (deepSpan relies on that). The mist's shape: swap this for another density later.
float deepShape(Deep dp, vec3 q, vec3 camI) {
    float d = length(q.xz - dp.c);
    if (d > dp.R + DEEP_EDGE || dp.st <= 0.0) return 0.0;
    vec3 w = q + camI;
    // the ragged edge (noise) only near the rim: the inside and far outside skip it
    if (d > dp.R - 8.0) d += 3.0 * (value32(vec3(w.x / 7.0, 0.5, w.z / 7.0), dp.id) - 0.5) * 2.0;
    // seen from afar a flat-topped, straight-sided cylinder read as a box in the sky (AdventurAgent): the mist thins
    // upwards (dense near the ground, ~1/e every 18 blocks above surface + 6) and fades over ~12 blocks at the rim
    float band = smoothstep(dp.yb - DEEP_BELOW, dp.yb - DEEP_BELOW + 2.0, q.y) * (1.0 - smoothstep(dp.yb + DEEP_ABOVE - 24.0, dp.yb + DEEP_ABOVE, q.y));
    band *= exp(-max(0.0, q.y - dp.yb - 6.0) / 18.0);
    return smoothstep(-4.0, 8.0, dp.R - d) * min(1.0, dp.st * 1.5) * band;
}

// all drifting deeps at q (the strongest): for the shields, a few pixels
float deepAt(vec3 q, vec3 camI) {
    int nd = int(data(31));
    float best = 0.0;
    for (int i = 0; i < nd && i < MAX_DEEPS; i++) best = max(best, deepShape(loadDeep(i), q, camI));
    return best;
}

// t interval of the ray o + t dir (dir normalised) inside a vertical cylinder (centre c, radius rr) and the height
// band y0..y1
bool cylSpan(vec2 c, float rr, float y0, float y1, vec3 o, vec3 dir, out vec2 span) {
    float t0 = -1e9, t1 = 1e9;
    vec2 oc = o.xz - c;
    float a = dot(dir.xz, dir.xz), b = dot(oc, dir.xz), cc = dot(oc, oc) - rr * rr;
    if (a < 1e-8) {
        if (cc > 0.0) return false;          // straight up or down, outside the cylinder
    } else {
        float disc = b * b - a * cc;
        if (disc <= 0.0) return false;
        float sq = sqrt(disc);
        t0 = (-b - sq) / a;
        t1 = (-b + sq) / a;
    }
    if (abs(dir.y) < 1e-6) {
        if (o.y < y0 || o.y > y1) return false;
    } else {
        float ta = (y0 - o.y) / dir.y, tb = (y1 - o.y) / dir.y;
        t0 = max(t0, min(ta, tb));
        t1 = min(t1, max(ta, tb));
    }
    span = vec2(max(t0, 0.0), t1);
    return t1 >= span.x;                      // (span.x >= 0: not behind the camera)
}

// the same for a sphere (centre c, radius rr) cut to the height band y0..y1
bool sphereSpan(vec3 c, float rr, float y0, float y1, vec3 o, vec3 dir, out vec2 span) {
    vec3 oc = o - c;
    float b = dot(oc, dir), cc = dot(oc, oc) - rr * rr, disc = b * b - cc;
    if (disc <= 0.0) return false;
    float sq = sqrt(disc), t0 = -b - sq, t1 = -b + sq;
    if (abs(dir.y) < 1e-6) {
        if (o.y < y0 || o.y > y1) return false;
    } else {
        float ta = (y0 - o.y) / dir.y, tb = (y1 - o.y) / dir.y;
        t0 = max(t0, min(ta, tb));
        t1 = min(t1, max(ta, tb));
    }
    span = vec2(max(t0, 0.0), t1);
    return t1 >= span.x;
}

// The mist volume (MistSampler, textures/effect/mistnoise.png from script/gen_mistnoise.py): a tileable 32^3 noise,
// 32 z slices of 34 x 34 texels (each slice with a 1-texel wrapped border, so bilinear filtering wraps inside it), 8
// across and 4 down. p in voxels (period 32). r, g, b: gradient noises; a: inverted Worley (billows). 2 fetches.
vec4 mistTex(vec3 p) {
    p = mod(p, 32.0);
    float z = p.z - 0.5, z0 = floor(z);
    int s0 = int(mod(z0, 32.0)), s1 = (s0 + 1) & 31;
    vec2 xy = p.xy + 1.0;
    const vec2 INV = vec2(1.0 / 272.0, 1.0 / 136.0);
    vec4 a = texture(MistSampler, (vec2(float((s0 & 7) * 34), float((s0 >> 3) * 34)) + xy) * INV);
    vec4 b = texture(MistSampler, (vec2(float((s1 & 7) * 34), float((s1 >> 3) * 34)) + xy) * INV);
    return mix(a, b, z - z0);
}

// The surface under a deep's mist: a G x G grid of heights (StasisPost.grid; y - floor(camera)) from slot base, the
// grid's corner at org (x, z, relative to floor(camera)) with spacing step. Bilinear; clamped at the grid's edge.
const int GG = 8;
float groundAt(int base, vec3 g, vec2 q) {
    vec2 l = clamp((q - g.xy) / g.z, 0.0, float(GG - 1) - 0.001);
    vec2 f0 = floor(l);
    vec2 f = l - f0;
    int o = base + int(f0.y) * GG + int(f0.x);
    return mix(mix(data(o), data(o + 1), f.x), mix(data(o + GG), data(o + GG + 1), f.x), f.y);
}

// Mist density 0..1 at noise point p (voxels) and height h above the ground: domain-warped billows plus a finer
// octave, cut by a coverage remap into patches with holes (Guerrilla's cloud shaping, Schneider 2015), the patches
// shrinking and thinning upwards (1/e per H blocks, with a ragged top). phase: how far the warp has evolved (the
// patches churn in place). Tuned with tools/mist/preview.py (LabAgent's workdir).
float mistAt(vec3 p, float h, float H, float cover, vec2 phase, bool detail) {
    if (h < -8.0) return 0.0;
    vec4 wv = mistTex(p * 0.5 + vec3(phase.x, 11.0, phase.y));
    vec3 q = p + (wv.rgb - 0.5) * 9.0;
    vec4 n0 = mistTex(q);
    float n = 0.6 * n0.a + 0.4 * n0.r;
    // the finer octave only up close (it's lost in the distance anyway): a third of the fetches
    if (detail) n = 0.78 * n + 0.22 * mistTex(q * 2.03 + vec3(5.1, 9.7, 13.3)).g;
    else n = 0.78 * n + 0.11;
    float top = max(h - 6.0 * (wv.a - 0.5), 0.0);
    float cv = cover * (1.0 - 0.5 * min(1.0, top / (3.0 * H)));
    return clamp((n - (1.0 - cv)) / cv, 0.0, 1.0) * exp(-top / H) * smoothstep(-8.0, -5.0, h);
}

// The deeps (drifting and static) this pixel's view ray crosses (up to MAX_HITS, read once per pixel), and their t
// spans (from 0, not clipped at the far end)
const int MAX_HITS = 8;
const int STATIC_GROUND = 2048 + 64 * MAX_DEEPS;     // StasisPost.STATIC_GROUND_BASE
struct Hit {
    int kind;        // 0 drifting deep, 1 static deep
    int idx;
    vec3 c;          // centre - floor(camera) (drifting: y = surface at the centre)
    float R, st;
    vec3 g;          // ground grid corner (x, z) and spacing
    int id;
};
Hit hits[MAX_HITS];
vec2 hitSpan[MAX_HITS];
int hitCount = 0;
vec3 hitCamI;
// the mist's clocks, wrapped on the CPU where each is seamless (StasisPost; a float of game time loses precision
// after days): drifting wind offset (x, z blocks, mod 96 = the noise period), warp phases (mod 32 voxels) of the
// drifting and the static mist, and the static breathing angle (mod 2 pi)
vec2 mistWind, mistPhase, staticPhase;
float breathAngle;

float puffDark = 0.0;    // set by mistDensity for a young puff: how much darker than the dense mist (deepMist)
const float STATIC_REACH = 1.3, STATIC_REACH_ADD = 10.0;   // the mist's reach around a static deep (StasisPost too)

// mist density of hit k at q (= world - floor(camera)): inside the gameplay envelope only
float mistDensity(vec3 q, int k, bool detail) {
    Hit hk = hits[k];
    float id = float(hk.id & 1023);
    if (hk.kind == 0) {
        // drifting: patches carried along with the deep, plus a slow wind of their own through it
        float d = length(q.xz - hk.c.xz);
        // a puff from a rift (strength + 2): a denser, rounder plume, thickest in its middle (the plain deeps' patchy
        // mist hid them: AdventurAgent saw only a faint darkening)
        bool puff = hk.st > 1.5;
        float st = puff ? hk.st - 2.0 : hk.st;
        float env = smoothstep(-4.0, 10.0, hk.R - d) * min(1.0, st * 1.5);
        if (env <= 0.0) return 0.0;
        if (puff) {
            // a plume (second opinion, notes/rifts/second-opinion-a4.md): measured from the surface at its centre, not
            // the ground grid (over the sinkhole that dropped into the pit); young (small) it's a column rising out of
            // the hole, older a lifted billow that settles and spreads; its billows boil upwards; denser while small
            // (the same mass spread out), and dark while young (puffDark: sooty, fresh out of the rift)
            float age = clamp((hk.R - 10.0) / 22.0, 0.0, 1.0);
            float hc = 4.0 + 18.0 * smoothstep(0.0, 0.5, age) - 6.0 * smoothstep(0.5, 1.0, age);
            float thick = 7.0 + 0.45 * hk.R;
            float hy = q.y - hk.c.y;
            float vert = exp(-(hy - hc) * (hy - hc) / (thick * thick)) * smoothstep(-10.0, -2.0, hy);
            float core = 1.0 - smoothstep(0.0, 1.0, d / max(hk.R, 1.0));
            vec3 pp = vec3(q.x - hk.c.x + mistWind.x, q.y + hitCamI.y - mod(data(29) * 0.8, 64.0), q.z - hk.c.z + mistWind.y) * (32.0 / 64.0);
            vec4 n0 = mistTex(pp + id);
            float n = 0.55 * n0.a + 0.45 * n0.r;
            float dn = env * vert * (0.4 + 1.0 * core) * clamp((n - 0.25) / 0.5, 0.0, 1.0) * (32.0 / max(hk.R, 10.0));
            if (dn > 0.0) puffDark = max(puffDark, 1.3 * (1.0 - age));
            return dn;
        }
        float h = q.y - groundAt(GROUND_BASE + 64 * hk.idx, hk.g, q.xz);
        vec3 p = vec3(q.x - hk.c.x + mistWind.x, q.y + hitCamI.y, q.z - hk.c.z + mistWind.y) * (32.0 / 96.0);
        p.y *= 1.5;
        return env * mistAt(p + id, h, 11.0, 0.55, mistPhase, detail);
    }
    // static: anchored, breathing (density, height and coverage swell and ebb over ~16 s, out of step per deep; AdventurAgent
    // found a 30% swing too subtle to see)
    // (the inhale before a puff, slots 1597 + i: the mist draws in towards the rift and thickens)
    float inhale = data(1597 + hk.idx);
    vec3 dv = q - hk.c;
    dv.y *= 1.4;
    float s = length(dv) / (hk.R * 1.15 * (1.0 - 0.45 * inhale));
    float env = 1.0 - smoothstep(0.8, 1.15, s);
    if (env <= 0.0) return 0.0;
    float br = 0.5 + 0.5 * sin(breathAngle + id);
    float h = q.y - groundAt(STATIC_GROUND + 64 * hk.idx, hk.g, q.xz);
    vec3 p = (q + hitCamI) * (32.0 / 96.0);
    p.y *= 1.5;
    return env * (0.4 + 0.6 * br + 1.6 * inhale) * mistAt(p + id, h, 6.0 + 8.0 * br, 0.38 + 0.27 * br, staticPhase, detail);
}

void findDeeps(vec3 o, vec3 dir, float tEnd, vec3 camI) {
    hitCamI = camI;
    mistWind = vec2(data(176), data(177));
    mistPhase = vec2(data(178), data(179));
    staticPhase = vec2(data(180), data(181));
    breathAngle = data(182);
    hitCount = 0;
    int nd = int(data(31));
    for (int i = 0; i < nd && i < MAX_DEEPS; i++) {
        int o8 = DEEP_BASE + DEEP_STRIDE * i;
        vec2 c = vec2(data(o8), data(o8 + 1));
        float R = data(o8 + 2);
        // the ray must pass the circle (3 fetches) before the rest is read
        vec2 oc = o.xz - c;
        float cb = dot(oc, dir.xz), cr = R + DEEP_EDGE;
        if (dot(oc, oc) > cr * cr && (cb > 0.0 || cb * cb < dot(dir.xz, dir.xz) * (dot(oc, oc) - cr * cr))) continue;
        vec2 span;
        // the height band from the deep's (smooth) surface, not the grid's min and max: those change when the grid
        // moves on to the next lattice cell, and the march's samples moved with them (the mist re-formed in one frame)
        float yb = data(o8 + 4);
        // (a puff: a short column around its plume, so the march's steps aren't spent on empty sky)
        bool isPuff = data(o8 + 3) > 1.5;
        if (!cylSpan(c, R + DEEP_EDGE, isPuff ? yb - 12.0 : yb - 32.0 - DEEP_BELOW, isPuff ? yb + 48.0 : yb + 24.0 + MIST_ABOVE, o, dir, span)
            || span.x > tEnd) continue;
        hits[hitCount] = Hit(0, i, vec3(c.x, data(o8 + 4), c.y), R, data(o8 + 3), vec3(data(o8 + 8), data(o8 + 9), data(o8 + 10)), int(data(o8 + 5)));
        hitSpan[hitCount] = span;
        if (++hitCount == MAX_HITS) return;       // nearest first: the farthest are dropped
    }
    int ns = int(data(218));
    for (int i = 0; i < ns && i < 4; i++) {
        int o5 = 220 + 5 * i;
        vec3 c = vec3(data(o5), data(o5 + 1), data(o5 + 2));
        float R = data(o5 + 3), E = R * STATIC_REACH + STATIC_REACH_ADD;
        vec2 span;
        if (!sphereSpan(c, E, data(168 + 2 * i) - DEEP_BELOW, data(169 + 2 * i) + MIST_ABOVE, o, dir, span) || span.x > tEnd) continue;
        // (the mist's envelope follows a lance's breach, R x strength; E stays: the ground grid is laid out for the whole rift)
        hits[hitCount] = Hit(1, i, c, R * data(240 + i), 1.0, vec3(c.x - E, c.z - E, 2.0 * E / float(GG - 1)), int(data(o5 + 4)));
        hitSpan[hitCount] = span;
        if (++hitCount == MAX_HITS) return;
    }
}

// vanilla's render-distance fog (FogRenderer, fog.glsl): linear in cylindrical distance from rd - clamp(rd / 10, 4,
// 64) to rd. 1 where terrain is unfogged, 0 past the render distance: the mist fades out with the terrain.
float viewFade(vec3 rel, float rd) {
    float start = rd - clamp(rd / 10.0, 4.0, 64.0);
    float cyl = max(length(rel.xz), abs(rel.y));
    return 1.0 - clamp((cyl - start) / (rd - start), 0.0, 1.0);
}

// interleaved gradient noise (Jimenez): a per-pixel jitter for the mist steps, stable over time
float ign(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

const int MIST_STEPS = 32;                 // per (reduced-resolution) pixel, all spans
const float MIST_SIGMA = 0.07;             // extinction per block at density 1

// The mist's colour: thin mist reads paler (lit through), dense cores darker and more violet
const vec3 MIST_THIN = vec3(0.66, 0.65, 0.75), MIST_DENSE = vec3(0.47, 0.45, 0.57);

// The deep mist along the ray, 0..tEnd (the hits from findDeeps), front to back: x = 1 - transmittance
// (Beer-Lambert), y = the share of the scattered light that has MIST_DENSE's colour (the light is
// (x - y) MIST_THIN + y MIST_DENSE). Overlapping spans are merged and marched together (the density there is the
// densest deep's); each up to 12 jittered steps, nearest first, MIST_STEPS in all.
vec2 deepMist(vec3 o, vec3 dir, float tEnd, float rd) {
    int ord[MAX_HITS];
    for (int i = 0; i < MAX_HITS; i++) ord[i] = i;
    // by entry (insertion sort)
    for (int i = 1; i < hitCount; i++) {
        int k = ord[i];
        int j = i - 1;
        while (j >= 0 && hitSpan[ord[j]].x > hitSpan[k].x) {
            ord[j + 1] = ord[j];
            j--;
        }
        ord[j + 1] = k;
    }
    float jit = ign(gl_FragCoord.xy);
    float T = 1.0;
    float B = 0.0;
    int used = 0;
    int k = 0;
    for (int g = 0; g < MAX_HITS; g++) {
        if (k >= hitCount || used >= MIST_STEPS || T < 0.005) break;
        float a = hitSpan[ord[k]].x, b = min(hitSpan[ord[k]].y, tEnd);
        int k2 = k + 1;
        for (int j = 0; j < MAX_HITS; j++) {
            if (k2 >= hitCount || hitSpan[ord[k2]].x > b) break;
            b = max(b, min(hitSpan[ord[k2]].y, tEnd));
            k2++;
        }
        if (b > a) {
            int n = min(clamp(int(ceil((b - a) / 3.0)), 5, 12), MIST_STEPS - used);
            float dt = (b - a) / float(n);
            for (int m = 0; m < 12; m++) {
                if (m >= n) break;
                float t = a + (float(m) + jit) * dt;
                vec3 q = o + dir * t;
                float dn = 0.0;
                puffDark = 0.0;
                for (int j = k; j < k2; j++) dn = max(dn, mistDensity(q, ord[j], t < 80.0));
                if (dn > 0.0) {
                    float alpha = 1.0 - exp(-MIST_SIGMA * dn * viewFade(dir * t, rd) * dt);
                    // (a young puff weighs more than its share: (x - y) THIN + y DENSE turns sooty-violet past y = x)
                    B += T * alpha * min(dn, 1.0) * (1.0 + puffDark);
                    T *= 1.0 - alpha;
                }
            }
            used += n;
        }
        k = k2;
    }
    return vec2(1.0 - T, B);
}

const vec3 ICO[12] = vec3[12](
    vec3(-0.5257311, 0.8506508, 0.0), vec3(0.5257311, 0.8506508, 0.0), vec3(-0.5257311, -0.8506508, 0.0), vec3(0.5257311, -0.8506508, 0.0),
    vec3(0.0, -0.5257311, 0.8506508), vec3(0.0, 0.5257311, 0.8506508), vec3(0.0, -0.5257311, -0.8506508), vec3(0.0, 0.5257311, -0.8506508),
    vec3(0.8506508, 0.0, -0.5257311), vec3(0.8506508, 0.0, 0.5257311), vec3(-0.8506508, 0.0, -0.5257311), vec3(-0.8506508, 0.0, 0.5257311));
const ivec3 FACES[20] = ivec3[20](
    ivec3(0, 11, 5), ivec3(0, 5, 1), ivec3(0, 1, 7), ivec3(0, 7, 10), ivec3(0, 10, 11), ivec3(1, 5, 9), ivec3(5, 11, 4), ivec3(11, 10, 2),
    ivec3(10, 7, 6), ivec3(7, 1, 8), ivec3(3, 9, 4), ivec3(3, 4, 2), ivec3(3, 2, 6), ivec3(3, 6, 8), ivec3(3, 8, 9), ivec3(4, 9, 5),
    ivec3(2, 4, 11), ivec3(6, 2, 10), ivec3(8, 6, 7), ivec3(9, 8, 1));

// Hexagon cell of unit direction n at lattice frequency f: x = angular gap between nearest and second-nearest
// lattice points (0 on a cell edge), y = a random number per cell.
vec2 hexCell(vec3 n, float f, out vec3 centre) {
    int best = 0;
    float bd = -2.0;
    for (int i = 0; i < 20; i++) {
        vec3 c = normalize(ICO[FACES[i].x] + ICO[FACES[i].y] + ICO[FACES[i].z]);
        float d = dot(n, c);
        if (d > bd) { bd = d; best = i; }
    }
    vec3 A = ICO[FACES[best].x], B = ICO[FACES[best].y], C = ICO[FACES[best].z];
    vec3 cn = normalize(A + B + C);
    vec3 p = n * (dot(A, cn) / dot(n, cn));
    // barycentric of p in A, B, C
    vec3 v0 = B - A, v1 = C - A, v2 = p - A;
    float d00 = dot(v0, v0), d01 = dot(v0, v1), d11 = dot(v1, v1), d20 = dot(v2, v0), d21 = dot(v2, v1);
    float den = d00 * d11 - d01 * d01;
    float wb = (d11 * d20 - d01 * d21) / den, wc = (d00 * d21 - d01 * d20) / den;
    float gi = wb * f, gj = wc * f;
    float fi = floor(gi), fj = floor(gj);
    float d1 = 9.0, d2 = 9.0;
    vec3 q1 = n;
    for (int a = -1; a <= 2; a++)
        for (int b = -1; b <= 2; b++) {
            float i = fi + float(a), j = fj + float(b);
            vec3 q = normalize(A * (f - i - j) + B * i + C * j);
            float d = acos(clamp(dot(q, n), -1.0, 1.0));
            if (d < d1) { d2 = d1; d1 = d; q1 = q; } else if (d < d2) { d2 = d; }
        }
    centre = q1;
    return vec2(d2 - d1, hash13(floor(q1 * 2048.0)));
}

// Column fields: a hexagon grid on the cylinder, unrolled (u = angle * r, v = height). Same result as hexCell, in
// blocks: x = gap between nearest and second-nearest cell centre, y = a random number per cell.
vec2 hexColumn(vec3 d, float worldY, float r) {
    float circ = 6.2831853 * r;
    float cols = max(6.0, floor(circ / (3.2 * 1.7320508) + 0.5));
    float w = circ / cols, sz = w / 1.7320508, rowH = 1.5 * sz;
    float u = (atan(d.z, d.x) + 3.1415927) * r, v = worldY;
    float r0 = floor(v / rowH);
    float d1 = 99.0, d2 = 99.0;
    vec2 best = vec2(0.0);
    for (int a = -1; a <= 1; a++) {
        float row = r0 + float(a);
        float off = mod(row, 2.0) * 0.5 * w;
        float c0 = floor((u - off) / w);
        for (int b = -1; b <= 1; b++) {
            float col = c0 + float(b);
            vec2 ctr = vec2(off + col * w, row * rowH);
            float dd = length(vec2(u, v) - ctr);
            if (dd < d1) { d2 = d1; d1 = dd; best = vec2(mod(col, cols), row); } else if (dd < d2) { d2 = dd; }
        }
    }
    return vec2(d2 - d1, hash13(vec3(best, 7.0)));
}

// One shield surface hit: colour (rgb) and coverage (a). cell: (gap to the cell edge in blocks, random per cell).
vec4 shieldAt(vec2 cell, vec3 n, vec3 cc, vec3 view, float r, float amber, float dropout, float collapse, float side, float warm, float time, bool farSide) {
    float edge = cell.x;                  // blocks from the cell edge, roughly x2
    float rnd = cell.y;
    float wave = 0.5 + 0.5 * sin(time * 1.3 - n.y * r * 0.18 + rnd * 1.5);
    float fres = pow(1.0 - abs(dot(n, view)), 2.0);
    float rim = 1.0 - smoothstep(0.12, 0.45, edge);
    float gap = 1.0 - smoothstep(0.0, 0.08, edge);
    float a = 0.02 + 0.07 * fres + rim * (0.14 + 0.22 * wave);
    a *= 1.0 - 0.8 * gap;
    if (farSide) a *= 0.5;
    vec3 tint = mix(vec3(0.62, 0.84, 1.0), vec3(1.0, 0.55, 0.2), warm);
    // a failing shield (Baughn 2026-10-17, as shares of all hexes): each hex has one random key (rnd); the hexes with
    // the lowest keys go orange first (key < amber, in 3 steps) and are the ones that drop out (key < lost: a 14 Hz
    // flicker, then the hex shrinks away). lost = dropout while running; in a collapse dropout + collapse x a lean
    // towards the side (1 + 0.9 cos), which averages to 1 over the sphere: half the hexes are gone as it freezes.
    float lost = dropout;
    if (collapse > 0.0) lost += collapse * (1.0 + 0.9 * dot(cc, vec3(cos(side), 0.0, sin(side))));
    float band = clamp((amber - rnd) / 0.03, 0.0, 1.0);
    tint = mix(tint, vec3(1.0, 0.55, 0.2), max(warm, floor(band * 3.0 + 0.999) / 3.0));
    float f = (lost - rnd) / 0.02;        // > 0: lost; the last 0.004 of key above it flickers, the first 0.02 below shrinks
    if (lost > 0.0 && f > -0.2) {
        if (f >= 1.0) return vec4(0.0);
        if (f < 0.0) {
            if (hash13(vec3(rnd * 97.0, floor(time * 14.0), 3.3)) < 0.5) a *= 0.12;
            else tint = mix(tint, vec3(1.0, 0.92, 0.75), 0.6);
        } else {
            if (edge < f * 2.5) return vec4(0.0);   // the hex shrinks away from its edge
            a *= 1.0 - 0.6 * f;
        }
    }
    return vec4(tint * (0.8 + 0.4 * wave), clamp(a, 0.0, 0.9));
}

// The scene distance as the mist pass stores it (b, a: 16 bits): 0..1 over 0..1.5 render distances, 1 for the sky
float mistDepthCode(float tScene, float rd) {
    return tScene < 0.0 ? 1.0 : clamp(tScene / (rd * 1.5), 0.0, 1.0);
}

#ifdef STRATA_PASS
// ---- strata weather (Pockets.java; rift stage 2): the same epochs, from the same hashes

const int PERIODS[4] = int[4](600, 900, 1200, 1800);
const float VIOLET_S = 20.0, RAMP_S = 30.0;

int floorDiv12(int a) {
    return a >= 0 ? a / 12 : -((-a + 11) / 12);
}

// pocket p of the strata of deep id now: x = state (0 clear, 1 mist, 2 violet, 3 rift), y = density (Pockets.weather)
vec2 pocketWeather(int id, ivec3 p) {
    int pi = int(hash32(p.x, p.y, p.z, id ^ 0x90C1) * 4.0) & 3;
    float P = float(PERIODS[pi]);
    float s = data(188 + pi) + hash32(p.x, p.y, p.z, id ^ 0x90C2) * P;
    float carry = floor(s / P);
    float u = s - carry * P;
    int n = int(data(192 + pi) + carry);
    int salt = int(uint(n) * 0x9E3779B1u) ^ id;
    float hk = hash32(p.x, p.y, p.z, salt ^ 0x90C3);
    int k = hk < 0.25 ? 0 : hk < 0.70 ? 1 : 2;
    float clear = P * (0.15 + 0.20 * hash32(p.x, p.y, p.z, salt ^ 0x90C4));
    float c1 = clear * hash32(p.x, p.y, p.z, salt ^ 0x90C5), c2 = clear - c1;
    if (u < c1 || u >= P - c2) return vec2(0.0);
    float rift0 = 0.0, rift1 = 0.0, mist = P - clear;
    if (k > 0) { rift0 = P * (0.07 + 0.14 * hash32(p.x, p.y, p.z, salt ^ 0x90D0)); mist -= VIOLET_S + rift0; }
    if (k > 1) { rift1 = P * (0.07 + 0.14 * hash32(p.x, p.y, p.z, salt ^ 0x90D1)); mist -= VIOLET_S + rift1; }
    float w0 = 0.5 + hash32(p.x, p.y, p.z, salt ^ 0x90E0), w1 = 0.5 + hash32(p.x, p.y, p.z, salt ^ 0x90E1);
    float w2 = k > 1 ? 0.5 + hash32(p.x, p.y, p.z, salt ^ 0x90E2) : 0.0;
    float wsum = w0 + (k > 0 ? w1 : 0.0) + w2;
    float dens = 0.6 * smoothstep(0.0, 1.0, min(u - c1, P - c2 - u) / RAMP_S);
    float st = c1 + mist * w0 / wsum;
    for (int i = 0; i < 2; i++) {
        if (i >= k) break;
        float r = i == 0 ? rift0 : rift1;
        if (u < st) return vec2(1.0, dens);
        if (u < st + VIOLET_S) {
            float f = (u - st) / VIOLET_S;
            return vec2(2.0, 0.6 + 0.4 * f * f);
        }
        // rift: y = how dark, fading in over 2 s and out over 4 s (a one-frame cut looked like a glitch: AdventurAgent)
        if (u < st + VIOLET_S + r) return vec2(3.0, min(1.0, (u - st - VIOLET_S) / 2.0) * min(1.0, (st + VIOLET_S + r - u) / 4.0));
        st += VIOLET_S + r + mist * (i == 0 ? w1 : w2) / wsum;
    }
    return vec2(1.0, dens);
}

// ---- storms (Storms.java; rift stage 3): the storm field over the surface band, on the warped wind: lit grey mist
// where it's over the mist threshold, black rift (with a violet edge) over the rift threshold. The ground from the
// camera's 16 x 16 grid.
float stormGround(vec2 q) {
    vec2 l = clamp((q - vec2(data(1576), data(1577))) / 16.0, 0.0, 14.999);
    vec2 f0 = floor(l), f = l - f0;
    int o = 1320 + int(f0.y) * 16 + int(f0.x);
    return mix(mix(data(o), data(o + 1), f.x), mix(data(o + 16), data(o + 17), f.x), f.y);
}

vec3 camIntS;

void stormMarch(vec3 o, vec3 dir, float tEnd, float jit, inout float T, inout float L, inout float V) {
    float si = data(1312);
    camIntS = vec3(data(16), data(17), data(18));
    if (si <= 0.0) return;
    vec2 no = vec2(data(1315), data(1316));
    float thM = data(1317), thR = data(1318);
    int seed = int(data(1319));
    const int N = 28;
    float dt = tEnd / float(N);
    for (int m = 0; m < N; m++) {
        if (T < 0.01) break;
        vec3 q = o + dir * ((float(m) + jit) * dt);
        float h = q.y - stormGround(q.xz);
        if (h < -6.0 || h > 64.0) continue;
        float nz = fbm32(vec3(no + q.xz / 90.0, 0.5).xzy, seed);
        if (nz < thM) continue;
        float band = smoothstep(-6.0, -3.0, h) * exp(-max(0.0, h - 4.0) / 22.0);
        // rifts: ragged in 3D (a 2D field alone made straight-walled slabs with flat bottoms: AdventurAgent), rising
        // from the ground and frayed at the top
        // (and narrowing upwards to ragged tops of varying height: straight walls with flat tops looked like a bug
        // against the night sky, AdventurAgent)
        float nr = nz + 0.05 * (fbm32((q + vec3(camIntS)) / 14.0, seed ^ 0x77) - 0.5) - max(0.0, h) * 0.0012;
        if (nr >= thR) {
            float top = 12.0 + 34.0 * fbm32(vec3((q.xz + camIntS.xz) / 23.0, 3.7).xzy, seed ^ 0x78);
            float rb = smoothstep(-8.0, 6.0, h + 6.0 * (hash13(floor(q * 0.5)) - 0.5)) * (1.0 - smoothstep(top * 0.4, top, h));
            float k = smoothstep(thR, thR + 0.035, nr) * rb;
            float a = 1.0 - exp(-dt * 0.3 * k);
            V += T * (1.0 - k) * rb * 0.05 * dt;
            T *= 1.0 - a;
        } else {
            float k = smoothstep(thM, thM + 0.04, nz) * si;
            float a = 1.0 - exp(-dt * 0.035 * k * band);
            L += T * a;
            T *= 1.0 - a;
        }
    }
}

// The strata pass (post_effect/stasis.json; this file with STRATA_PASS): the strata weather along the view ray, at
// the mist target's resolution. The strata mask (StrataSampler, client/StrataMask.java) is 64^3 cells of 4 blocks
// around the camera, 8 x 8 tiles of 64 x 64 (x across, y down, a tile per z), each texel the deep id (rgb) and
// a = strata. Out: r = 1 - transmittance, g = the lit mist in it (the rest is a rift's black), b = violet glow.
void main() {
    fragColor = vec4(0.0);
    if (data(22) < 0.5 || (data(187) < 0.5 && data(1312) <= 0.0)) return;
    float depth = texture(DepthSampler, texCoord).r;
    mat4 inv = mat4(data(0), data(1), data(2), data(3), data(4), data(5), data(6), data(7),
                    data(8), data(9), data(10), data(11), data(12), data(13), data(14), data(15));
    vec4 h = inv * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec3 rel = depth > 0.0 ? h.xyz / h.w : vec3(0.0);
    vec3 o = vec3(data(19), data(20), data(21));
    ivec3 camI = ivec3(data(16), data(17), data(18));
    vec4 hs = inv * vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);
    vec3 dir = depth > 0.0 ? normalize(rel) : normalize(hs.xyz / hs.w);
    float tEnd = depth > 0.0 ? min(length(rel), 180.0) : 180.0;
    float T = 1.0, L = 0.0, V = 0.0;
    float jit = ign(gl_FragCoord.xy + 31.0);
    stormMarch(o, dir, depth > 0.0 ? min(length(rel), 200.0) : 200.0, jit, T, L, V);
    if (data(187) < 0.5) {
        fragColor = vec4(1.0 - T, L, clamp(V, 0.0, 1.0), 1.0);
        return;
    }
    vec3 mo = vec3(data(184), data(185), data(186));
    // the ray through the mask's box
    vec3 inv3 = 1.0 / (dir + vec3(equal(dir, vec3(0.0))) * 1e-6);
    vec3 ta3 = (mo - o) * inv3, tb3 = (mo + 256.0 - o) * inv3;
    vec3 tmin = min(ta3, tb3), tmax = max(ta3, tb3);
    float ta = max(max(tmin.x, tmin.y), max(tmin.z, 0.0)), tb = min(min(tmax.x, tmax.y), min(tmax.z, tEnd));
    if (tb <= ta) {
        fragColor = vec4(1.0 - T, L, clamp(V, 0.0, 1.0), 1.0);
        return;
    }
    int n = clamp(int(ceil((tb - ta) / 1.25)), 6, 40);
    float dt = (tb - ta) / float(n);
    ivec3 lastP = ivec3(-2147483647);
    int lastId = -1;
    vec2 w = vec2(0.0);
    for (int m = 0; m < 40; m++) {
        if (m >= n || T < 0.01) break;
        vec3 q = o + dir * (ta + (float(m) + jit) * dt);
        // the lookup is jittered a little, so the 4-block cells and 12-block pockets dither instead of showing planes
        // plus a slow warp of up to ~3 blocks, so pocket and cell borders wander instead of running straight
        vec3 wq = (q + vec3(camI)) * (32.0 / 160.0);
        vec3 qj = q + (mistTex(wq).rgb - 0.5) * 6.0 + (vec3(hash13(q * 1.7), hash13(q * 2.3 + 5.0), hash13(q * 3.1 + 9.0)) - 0.5) * 2.0;
        ivec3 cell = ivec3(floor((qj - mo) / 4.0));
        if (any(lessThan(cell, ivec3(0))) || any(greaterThan(cell, ivec3(63)))) continue;
        vec4 tx = texelFetch(StrataSampler, ivec2((cell.z & 7) * 64 + cell.x, (cell.z >> 3) * 64 + cell.y), 0);
        if (tx.a < 0.5) continue;
        ivec3 b8 = ivec3(round(tx.rgb * 255.0));
        int id = b8.r | (b8.g << 8) | (b8.b << 16);
        ivec3 wb = ivec3(floor(qj)) + camI;
        ivec3 pk = ivec3(floorDiv12(wb.x), floorDiv12(wb.y), floorDiv12(wb.z));
        if (pk != lastP || id != lastId) {
            w = pocketWeather(id, pk);
            lastP = pk;
            lastId = id;
        }
        if (w.x < 0.5) continue;
        vec3 wp = (q + vec3(camI)) * (32.0 / 40.0);
        float nz = 0.6 * mistTex(wp + vec3(data(178), 3.0, data(179))).a + 0.4 * mistTex(wp * 0.5 + 7.0).r;
        float sigma, e = 0.0;
        float riftK = 0.0;
        if (w.x > 2.5) {
            // rift: black, swallows the light (from the violet mist's density as it fades in)
            riftK = w.y;
            sigma = mix(0.22, 2.0, riftK);
        } else {
            sigma = 0.22 * w.y * clamp(0.35 + 1.3 * (nz - 0.5) + 0.5, 0.15, 1.5);
            if (w.x > 1.5) e = (w.y - 0.6) / 0.4;              // violet: the warning glows, brighter as it nears rift
        }
        float alpha = 1.0 - exp(-sigma * dt);
        L += T * alpha * (1.0 - riftK);
        V += T * (0.04 + 0.08 * e) * e * dt;
        T *= 1.0 - alpha;
    }
    fragColor = vec4(1.0 - T, L, clamp(V, 0.0, 1.0), 1.0);
}
#elif defined(MIST_PASS)
// The mist pass (post_effect/stasis.json; this file again, with MIST_PASS defined by the build): the mist march at
// the reduced resolution of the "mist" target. Out: r, g = deepMist; b, a = the scene distance (mistDepthCode).
void main() {
    fragColor = vec4(0.0, 0.0, 1.0, 1.0);
    // (with only strata about, still the scene distance: the strata pass's upsample uses it)
    if (data(22) < 0.5 || (data(31) <= 0.0 && data(218) <= 0.0 && data(187) < 0.5 && data(1312) <= 0.0)) return;
    float depth = texture(DepthSampler, texCoord).r;
    mat4 inv = mat4(data(0), data(1), data(2), data(3), data(4), data(5), data(6), data(7),
                    data(8), data(9), data(10), data(11), data(12), data(13), data(14), data(15));
    vec4 h = inv * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec3 rel = depth > 0.0 ? h.xyz / h.w : vec3(0.0);
    vec3 camFrac = vec3(data(19), data(20), data(21));
    vec3 camInt = vec3(data(16), data(17), data(18));
    float rd = max(data(216), 32.0);
    vec4 hs = inv * vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);
    vec3 dir = depth > 0.0 ? normalize(rel) : normalize(hs.xyz / hs.w);
    float tScene = depth > 0.0 ? length(rel) : -1.0;
    float tEnd = depth > 0.0 ? min(tScene, rd * 1.415) : rd * 1.415;
    float dc = floor(mistDepthCode(tScene, rd) * 65535.0 + 0.5);
    float hi = floor(dc / 256.0);
    vec2 m = vec2(0.0);
    findDeeps(camFrac, dir, tEnd, camInt);
    if (hitCount > 0) m = deepMist(camFrac, dir, tEnd, rd);
    fragColor = vec4(m, hi / 255.0, (dc - hi * 256.0) / 255.0);
}
#else
// The mist pass's result at this pixel: a depth-aware (joint bilateral) upsample of the 4 nearest mist texels, so
// mist behind a near tree doesn't bleed onto it. tScene < 0: the sky.
vec2 mistUp(float tScene, float rd) {
    ivec2 sz = textureSize(MistBufSampler, 0);
    vec2 f = texCoord * vec2(sz) - 0.5;
    vec2 fl = floor(f), fr = f - fl;
    float me = mistDepthCode(tScene, rd), tol = 0.03 * me + 0.002;
    vec2 acc = vec2(0.0), best = vec2(0.0);
    float wsum = 0.0, bestd = 1e9;
    for (int j = 0; j < 4; j++) {
        ivec2 o = ivec2(j & 1, j >> 1);
        vec4 s = texelFetch(MistBufSampler, clamp(ivec2(fl) + o, ivec2(0), sz - 1), 0);
        float dd = abs((s.b * 255.0 * 256.0 + s.a * 255.0) / 65535.0 - me);
        float w = (o.x == 1 ? fr.x : 1.0 - fr.x) * (o.y == 1 ? fr.y : 1.0 - fr.y) * exp(-dd / tol);
        acc += w * s.rg;
        wsum += w;
        if (dd < bestd) {
            bestd = dd;
            best = s.rg;
        }
    }
    return wsum > 1e-4 ? acc / wsum : best;
}

// The strata pass's result here: mistUp's weights (the scene distances of the mist target, same size) on the strata
// target
vec3 strataUp(float tScene, float rd) {
    ivec2 sz = textureSize(MistBufSampler, 0);
    vec2 f = texCoord * vec2(sz) - 0.5;
    vec2 fl = floor(f), fr = f - fl;
    float me = mistDepthCode(tScene, rd), tol = 0.03 * me + 0.002;
    vec3 acc = vec3(0.0), best = vec3(0.0);
    float wsum = 0.0, bestd = 1e9;
    for (int j = 0; j < 4; j++) {
        ivec2 o = clamp(ivec2(fl) + ivec2(j & 1, j >> 1), ivec2(0), sz - 1);
        vec4 s = texelFetch(MistBufSampler, o, 0);
        vec3 v = texelFetch(StrataBufSampler, o, 0).rgb;
        float dd = abs((s.b * 255.0 * 256.0 + s.a * 255.0) / 65535.0 - me);
        float w = ((j & 1) == 1 ? fr.x : 1.0 - fr.x) * ((j >> 1) == 1 ? fr.y : 1.0 - fr.y) * exp(-dd / tol);
        acc += w * v;
        wsum += w;
        if (dd < bestd) {
            bestd = dd;
            best = v;
        }
    }
    return wsum > 1e-4 ? acc / wsum : best;
}

// ---- the rift core's eye (λ-Nero, v5 after "it should be a hole, not a thing"; preview tools/rift/core_v5.py in
// LabAgent's workdir): a tear in the black on the 1/16-block grid, a bit wider than tall, with a black pupil in its
// middle. Around the pupil you see where the rift leads, far away (the End: dark sky, stars, violet haze; the Nether:
// ember light), fixed in view direction, so it shifts with parallax behind the hole. Light leaks past the ragged lips
// here and there; cracks of light run out from them in stop-motion, hold and heal. The eye's own clock now and then
// freezes or steps back. It holds its facing and twitches towards the nearest player (StasisPost: saccades).
const vec3 EYE_RIM = vec3(0.55, 0.42, 0.95), EYE_CRACK = vec3(0.55, 0.45, 0.9);

float segDist(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float h = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
    return length(p - a - ab * h);
}

// the eye's clock: real time, but every ~2 s maybe a 0.25 s freeze or a 0.2 s step back
float eyeClock(float t, int id) {
    float k = floor(t / 2.0), u = t - k * 2.0;
    float r = hash32(int(k), 77, 0, id);
    if (r < 0.35 && u >= 1.0 && u < 1.25) return k * 2.0 + 1.0;
    if (r >= 0.35 && r < 0.55 && u >= 1.0 && u < 1.2) return t - 0.2;
    return t;
}

// the far side through the hole, by view direction (so it sits at infinity); dest 0 the End, 1 the Nether
vec3 farSide(vec3 dir, float t, int dest) {
    // (in direction space, no angles: atan's wrap made a seam in the Nether's embers, AdventurAgent)
    vec3 q = dir * 18.0;
    if (dest == 1) {
        float g = 0.5 + 0.5 * sin(q.x * 0.9 + t * 0.4) * sin(q.y * 0.8 - t * 0.3) * sin(q.z * 0.7 + 1.3);
        return vec3(0.18, 0.03, 0.01) + vec3(0.5, 0.15, 0.03) * g * g;
    }
    float haze = 0.5 + 0.5 * sin(q.x * 0.7 + 0.3 * sin(q.y * 0.9 + t * 0.2)) * cos(q.y * 0.55 - t * 0.15) * cos(q.z * 0.6);
    vec3 c = vec3(0.07, 0.04, 0.13) + vec3(0.3, 0.16, 0.5) * haze * haze;
    ivec3 cell = ivec3(floor(dir * 72.0));
    float st = hash32(cell.x, cell.y, cell.z, 0x57A2);
    if (st > 0.93) c = max(c, vec3(0.85, 0.85, 1.0) * (st - 0.93) / 0.07);
    return c;
}

// x: blocks on the eye's plane (y up), already on the 1/16 grid; W: the tear's half-width. Out: rgb, a = how much it
// replaces what's there (the black)
vec4 eyePattern(vec2 x, float time, int id, float W, vec3 dir, int dest) {
    float lt = eyeClock(time, id);
    float H = W * 0.62 * (0.88 + 0.24 * (0.5 + 0.5 * sin(floor(lt * 2.5) * 1.9)));
    float xx = clamp(1.0 - (x.x / W) * (x.x / W), 0.0, 1.0);
    float edge = H * pow(xx, 0.75);
    float jag = hash32(int(floor(x.x * 16.0)), 5, 0, id) * 1.5 / 16.0;
    bool inside = abs(x.x) < W && abs(x.y) <= edge - jag;
    // the void close to the tear is a hair less black (light bending in)
    vec2 q = x / vec2(W * 1.5, W * 0.9);
    vec3 col = vec3(0.06, 0.03, 0.1) * exp(-dot(q, q) * 1.5);
    float cover = 0.0;
    if (inside) {
        // (λ-Nero, 2026-10-16; the End-sky look is 52afba1) inside, black at the lips going to grey towards the
        // middle, with radial lines and a small feline slit
        float depthIn = clamp(1.0 - abs(x.y) / max(edge, 1e-3), 0.0, 1.0) * clamp(1.0 - abs(x.x) / W, 0.0, 1.0);
        float g = pow(clamp(depthIn * 1.6, 0.0, 1.0), 1.3);
        float grain = hash32(int(floor(x.x * 16.0)), int(floor(x.y * 16.0)), int(floor(lt * 3.0)), id ^ 0x6A);
        vec3 inner = mix(vec3(0.0), vec3(0.52, 0.5, 0.58), g) * (0.92 + 0.08 * grain);
        // the lines back (λ-Nero): radial ticks in the grey, turning backwards in steps on their own clock, so they
        // never line up with the cracks outside
        float rot = -floor(lt * 4.0) * 0.0875;
        float th = atan(x.y / 0.7, x.x) - rot;
        float sect = th * 18.0 / 6.2832;
        float rr = length(x / vec2(W, max(edge, 1e-3)));
        if (abs(fract(sect) - 0.5) < 0.16 && rr > 0.3 && rr < 0.82
            && hash32(int(floor(mod(sect, 18.0))), int(floor(lt * 4.0)), 11, id) < 0.6) inner *= 0.5;
        // a feline slit: wide, thin, pointed at the ends
        float sx = x.x / (W * 0.46);
        if (abs(sx) < 1.0 && abs(x.y) <= W * 0.075 * (1.0 - sx * sx))
            return vec4(0.32, 0.1, 0.55, 1.0);   // purple: it reads feline (black vanished into the dark lips)
        return vec4(inner, 1.0);
    }
    // light leaking past the lips (no outline): here and there, flickering in steps
    if (abs(x.x) < W * 1.02 && abs(x.y) <= edge + 1.0 / 16.0) {
        float leak = hash32(int(floor(x.x * 16.0)), x.y > 0.0 ? 1 : 0, int(floor(lt * 6.0)), id ^ 9);
        if (leak < 0.45) return vec4(EYE_RIM * (0.35 + 0.65 * leak / 0.45), 1.0);
    }
    // cracks: jagged fissures from the lips, growing in 0.08 s steps, holding, healing back
    for (int k = 0; k < 6; k++) {
        float P = 1.3 + hash32(k, 1, 0, id) * 1.0, ph = hash32(k, 2, 0, id) * P;
        float cyc = floor((lt + ph) / P);
        float a = max(0.0, lt + ph - cyc * P);
        if (a > 1.1) continue;
        a = floor(a / 0.08) * 0.08;
        float reach = a < 0.7 ? min(1.0, a / 0.3) : 1.0 - (a - 0.7) / 0.4;
        int ci = int(cyc);
        float up = hash32(k, ci, 3, id) < 0.5 ? -1.0 : 1.0;
        float x0 = (hash32(k, ci, 4, id) * 1.6 - 0.8) * W;
        vec2 p = vec2(x0, up * H * pow(max(0.0, 1.0 - (x0 / W) * (x0 / W)), 0.75));
        float ang = 1.5708 * up + (hash32(k, ci, 5, id) - 0.5) * 1.6 + x0 / W * 0.5;
        int n = int(((0.8 + 1.2 * hash32(k, ci, 6, id)) * W * reach) * 8.0);
        if (length(x - p) > float(n) * 0.125 + 0.1) continue;
        for (int i = 0; i < 16; i++) {
            if (i >= n) break;
            ang += (hash32(k, ci, 10 + i, id) - 0.5) * 1.2;
            vec2 nx = p + vec2(cos(ang), sin(ang)) * 0.125;
            if (segDist(x, p, nx) < 0.5 / 16.0) return vec4(EYE_CRACK, 1.0);
            p = nx;
        }
    }
    return vec4(col, 0.0);
}

// ---- spreading mode (Spread, docs/spreading-mode.md): the server's rule per block column, in exact integers

const int SPREAD_WINDOW = 64, SPREAD_MISSING = 2147483647;

// The rank of global cell c (SpreadTiles.rank): the window's texel, MISSING outside it
int spreadRank(ivec2 c) {
    ivec2 t = c - ivec2(int(data(198)), int(data(199)));
    if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, ivec2(SPREAD_WINDOW)))) return SPREAD_MISSING;
    uvec4 b = uvec4(round(texelFetch(SpreadSampler, t, 0) * 255.0));
    // (the sign bit by hand: an int built from a uint over 2^31 is not portable)
    int v = int(b.r | (b.g << 8) | (b.b << 16) | ((b.a & 127u) << 24));
    return b.a >= 128u ? v - 2147483647 - 1 : v;
}

// SpreadMap.stilled through SpreadMap.stilledSplit (rank x 1024 overflows 32 bits on big maps): the bilinear between
// the cell centres 32i + 16 with integer weights summing to 1024; stilled iff sum w (r - P) < 0, each r - P split
// into high and low 16 bits. SpreadTilesTest checks the Java twin against the exact sum.
bool spreadStilled(ivec2 blk, int p) {
    ivec2 f = blk - 16;
    ivec2 c = f >> 5;                  // floorDiv by 32 (arithmetic shift)
    int a = f.x - c.x * 32, b = f.y - c.y * 32;
    int w00 = (32 - a) * (32 - b), w10 = a * (32 - b), w01 = (32 - a) * b, w11 = a * b;
    int d00 = spreadRank(c) - p, d10 = spreadRank(c + ivec2(1, 0)) - p, d01 = spreadRank(c + ivec2(0, 1)) - p, d11 = spreadRank(c + ivec2(1, 1)) - p;
    int hi = w00 * (d00 >> 16) + w10 * (d10 >> 16) + w01 * (d01 >> 16) + w11 * (d11 >> 16);
    int lo = w00 * (d00 & 0xFFFF) + w10 * (d10 & 0xFFFF) + w01 * (d01 & 0xFFFF) + w11 * (d11 & 0xFFFF);
    return hi + (lo >> 16) < 0;
}

// Does drifting deep dp stop block blk (- floor(camera)) of running spread land? Deeps.intensity at the block's
// centre (the same ragged edge) above Deeps.THRESHOLD, within the height band surface - 6 .. surface + 64 (the
// surface from the deep's ground grid here: Deeps.inBand reads the column's own; close, not exact)
bool deepStops(Deep dp, vec3 blk, vec3 camI, float yb) {
    vec2 bc = blk.xz + 0.5;
    vec3 wc = vec3(bc.x, 0.0, bc.y) + camI;
    float dist = length(bc - dp.c) + 3.0 * (value32(vec3(wc.x / 7.0, 0.5, wc.z / 7.0), dp.id) - 0.5) * 2.0;
    float h = smoothstep(0.0, 4.0, dp.R - dist) * min(1.0, dp.st * 1.5);
    return dp.st > 0.0 && h > 0.5 && blk.y >= floor(yb) - 6.0 && blk.y <= floor(yb) + 64.0;
}

// strata weather colours: the mist a little darker than the deeps' (underground), the warning violet
const vec3 STRATA_MIST = vec3(0.42, 0.40, 0.50), STRATA_VIOLET = vec3(0.62, 0.22, 0.95);

void main() {
    // a rift core bends the light round it (λ-Nero): within ~12 blocks, the picture is pulled towards the core's
    // place on screen (slots 1580 + 4i: its NDC, distance, in front)
    vec2 uv = texCoord;
    if (data(22) > 0.5) {
        int nsd0 = int(data(218));
        for (int i = 0; i < nsd0 && i < 4; i++) {
            int o = 1580 + 4 * i;
            float dist = data(o + 2);
            if (data(o + 3) < 0.5 || dist > 12.0) continue;
            vec2 cuv = vec2(data(o), data(o + 1)) * 0.5 + 0.5;
            vec2 dv = cuv - uv;
            float len = length(dv * vec2(InSize.x / InSize.y, 1.0));
            float w = 1.0 - dist / 12.0;
            uv += dv * 0.09 * w * w * exp(-len * 5.0);
        }
    }
    vec3 c = texture(InSampler, uv).rgb;
    float stops = data(22);
    float warn = data(23);
    float depth = texture(DepthSampler, texCoord).r;
    // 26.3 renders with reversed Z in [0, 1]: the far plane (sky) is 0, and NDC z is the depth value itself
    // the sky (depth 0) takes the camera's place: grey while you stand in stopped land, in colour inside a field (λ-Nero)
    if (stops > 0.5) {
        mat4 inv = mat4(data(0), data(1), data(2), data(3), data(4), data(5), data(6), data(7),
                        data(8), data(9), data(10), data(11), data(12), data(13), data(14), data(15));
        vec4 h = inv * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
        vec3 rel = depth > 0.0 ? h.xyz / h.w : vec3(0.0);         // world position minus the camera
        vec3 camFrac = vec3(data(19), data(20), data(21));
        vec3 p = rel + camFrac;                                    // world position minus floor(camera)
        vec3 camInt = vec3(data(16), data(17), data(18));
        // how much time runs here: 1 inside a field (soft at the edge: the border shouldn't look blocky), 0 outside
        float run = 0.0, vivid = 0.0;
        vec3 w = p + camInt;
        if (w.x >= data(25) && w.z >= data(26) && w.x < data(27) + 1.0 && w.z < data(28) + 1.0) run = 1.0;
        int n = int(data(24));
        for (int i = 0; i < n && i < 13; i++) {
            int o = 32 + 10 * i;
            if (data(o + 6) > 1.5) continue;   // (a frozen remnant: time is stopped there)
            vec3 fc = vec3(data(o), data(o + 1), data(o + 2));
            float r = data(o + 3), collapse = data(o + 4);
            vec3 dv = p - fc;
            if (data(o + 6) > 0.5) dv.y = 0.0;
            vec4 st = stretchOf(i);
            float d = length(unstretch(dv, st));            // (round space: Field.norm * r)
            float edge = d < 1e-3 ? r : (r - d) * length(dv) / d;   // blocks to the shell along the line from the centre
            float inside = smoothstep(0.0, 1.2, edge);
            if (collapse > 0.0) {
                // the same rule as Field.collapsed (patches and tears on the field's seed)
                vec3 blk = floor(w);
                vec3 off = blk + 0.5 - (camInt + fc);
                if (data(o + 6) > 0.5) off.y = 0.0;
                float score = collapseScore(blk, unstretch(off, st), d, r, int(data(o + 7)), data(1700 + 4 * i + 2));
                inside *= 1.0 - smoothstep(-0.02, 0.02, 1.6 * collapse - (1.0 - score));
            }
            float age = data(o + 8);
            if (age >= 0.0 && age < 3.5) {
                // start-up (visual only): the colour comes back inner-first, a noisy front reaching the shell at 2.2 s,
                // then a brief over-vivid flash
                float front = r * smoothstep(0.0, 2.2, age) + 1.5 * (fbm32(floor(w) / 3.0, int(data(o + 7))) - 0.5);
                inside *= 1.0 - smoothstep(front - 1.5, front, d);
                vivid = max(vivid, inside * 0.35 * exp(-(age - 2.5) * (age - 2.5) / 0.08));
            }
            run = max(run, inside);
        }
        // spreading mode: land the stillness hasn't reached runs, whatever the fields (Stasis.rateAt), except where a
        // drifting deep stops it (below). The block column as the server sees it; the sky takes the camera's.
        float spreadRun = 0.0;
        if (data(196) > 0.5) {
            ivec2 blk = ivec2(camInt.xz) + ivec2(floor(p.xz));
            spreadRun = spreadStilled(blk, int(data(197))) ? 0.0 : 1.0;
        }
        bool deepStop = false;
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        // drifting deeps along the view ray, up to the scene or as far as terrain renders (cylindrical: rd sqrt 2)
        float rd = max(data(216), 32.0);
        vec4 hs = inv * vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);
        vec3 dir = depth > 0.0 ? normalize(rel) : normalize(hs.xyz / hs.w);
        float tScene = depth > 0.0 ? length(rel) : 0.0;     // the sky: where the camera is
        float tEnd = depth > 0.0 ? min(tScene, rd * 1.415) : rd * 1.415;
        if (data(31) > 0.0 || data(218) > 0.0) findDeeps(camFrac, dir, tEnd, camInt);
        // a drifting deep (outside fields; fields hold against it): deeper grey, darker, a cold violet cast; fades
        // with distance like the mist
        float deep = 0.0;
        // (only the drifting deeps whose ray span reaches the scene: a loop over all of them costs every pixel)
        for (int i = 0; i < hitCount; i++)
            if (hits[i].kind == 0 && hitSpan[i].x <= tScene + 0.05 && hitSpan[i].y >= tScene - 0.05) {
                // the ground band from the deep's ground grid under this point (not one height for the whole deep)
                Deep dp = loadDeep(hits[i].idx);
                vec3 q = depth > 0.0 ? p : camFrac;
                dp.yb = groundAt(GROUND_BASE + 64 * hits[i].idx, hits[i].g, q.xz);
                deep = max(deep, deepShape(dp, q, camInt));
                // over running spread land a drifting deep stops time (Baughn 2026-10-19), unless a field holds it off
                if (spreadRun > 0.0 && !deepStop) deepStop = deepStops(dp, floor(q), camInt, dp.yb);
            }
        // the spread's running land joins the fields' (a field or the spawn area holds a deep off: run stays theirs)
        run = max(run, deepStop ? 0.0 : spreadRun);
        deep *= (1.0 - run) * viewFade(rel, rd);
        int nsd = int(data(218));
        float riftHere = 0.0, fringe = 0.0;
        for (int i = 0; i < nsd && i < 4; i++) {
            vec3 sc = vec3(data(220 + 5 * i), data(221 + 5 * i), data(222 + 5 * i));
            float sR = data(223 + 5 * i);
            vec3 q = depth > 0.0 ? p : camFrac;
            if (length(q - sc) > sR * 1.6 + 20.0) continue;
            float sh = staticShape(i, q + camInt, camInt);
            deep = max(deep, (1.0 - smoothstep(0.8, 1.05, sh)) * (1.0 - run));
            riftHere = max(riftHere, 1.0 - smoothstep(0.32, 0.36, sh));
            fringe = max(fringe, smoothstep(0.32, 0.36, sh) * (1.0 - smoothstep(0.36, 0.45, sh)));
        }
        vec3 grey = mix(vec3(l), c, 0.18 * (1.0 - deep)) * mix(vec3(0.96, 0.99, 1.04), vec3(0.62, 0.6, 0.74), deep);
        // strain on reality near a rift core (λ-Nero): the world darkens and greys the nearer the core, both where
        // you look (towards it) and all round as you approach (slot 1596)
        float strainAll = data(1596), strainHere = 0.0;
        for (int i = 0; i < nsd && i < 4; i++) {
            vec3 cc = vec3(data(204 + 3 * i), data(205 + 3 * i), data(206 + 3 * i));
            float sR = data(223 + 5 * i) * data(240 + i);
            vec3 q = depth > 0.0 ? p : camFrac + dir * 200.0;
            strainHere = max(strainHere, 1.0 - smoothstep(sR * 0.5, sR * 2.2 + 16.0, length(q - cc)));
        }
        float strain = max(strainAll * 0.6, strainHere) * (1.0 - run);
        grey = mix(grey, vec3(dot(grey, vec3(0.299, 0.587, 0.114))) * vec3(0.92, 0.9, 1.0), strain * 0.8) * (1.0 - 0.45 * strain);
        c = mix(grey, c, run);
        if (data(31) > 0.0 || data(218) > 0.0) {
            // deep mist along the view ray, out to the render distance (fading with the terrain there): marched at a
            // reduced resolution by the mist pass, upsampled here
            vec2 mist = mistUp(depth > 0.0 ? tScene : -1.0, rd);
            c = c * (1.0 - 0.85 * mist.x) + 0.85 * ((mist.x - mist.y) * MIST_THIN + mist.y * MIST_DENSE);
        }
        if (data(187) > 0.5 || data(1312) > 0.0) {
            // strata weather and storms (the strata pass): mist, the violet warning and black rift pockets in the strata caves
            vec3 sm = strataUp(depth > 0.0 ? tScene : -1.0, rd);
            c = c * (1.0 - sm.r) + STRATA_MIST * sm.g + STRATA_VIOLET * sm.b;
        }
        if (nsd > 0) {
            // rifts: no light at all, a faint violet fringe at the edge, and they swallow what lies behind them
            c = mix(c, vec3(0.0), riftHere);
            c += vec3(0.25, 0.1, 0.4) * fringe * 0.6;
            float tEnd = depth > 0.0 ? length(rel) : 200.0;
            vec4 hs2 = inv * vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);
            vec3 dir2 = depth > 0.0 ? normalize(rel) : normalize(hs2.xyz / hs2.w);
            // in open air the rift is a dark body that tapers off through a violet border (Baughn: the hard black blob
            // looked wrong floating in the air; as a hole in the ground it works): absorption falls off from the core
            // (shape 0.22) to the rift's edge (0.36), and the border glows violet out to ~0.5
            float trans = 1.0;
            vec3 glow = vec3(0.0);
            float jit2 = ign(gl_FragCoord.xy + 17.0);
            for (int i = 0; i < nsd && i < 4; i++) {
                vec3 sc = vec3(data(220 + 5 * i), data(221 + 5 * i), data(222 + 5 * i)) - camFrac;
                float sR = data(223 + 5 * i) * 0.8 + 8.0;    // the border lies within ~0.65 r + 8 (warp included)
                float b = dot(sc, dir2), cc = dot(sc, sc) - sR * sR, disc = b * b - cc;
                if (disc <= 0.0) continue;
                float sq = sqrt(disc), ta = max(b - sq, 0.0), tb = min(b + sq, tEnd);
                if (tb <= ta) continue;
                float sl = (tb - ta) / 14.0;
                for (int m = 0; m < 14; m++) {
                    vec3 qq = camFrac + dir2 * (ta + (float(m) + jit2) * sl);
                    float sh = staticShape(i, qq + camInt, camInt);
                    // the inhale before a puff (slot 1597 + i): the black swells and the border flares
                    float inh = data(1597 + i);
                    float core = 1.0 - smoothstep(0.22, 0.36 + 0.08 * inh, sh);
                    float border = smoothstep(0.26, 0.36 + 0.08 * inh, sh) * (1.0 - smoothstep(0.36 + 0.08 * inh, 0.5 + 0.1 * inh, sh));
                    glow += trans * border * sl * (0.035 + 0.09 * inh) * vec3(0.3, 0.12, 0.5);
                    trans *= exp(-sl * 0.6 * core);
                }
            }
            c = c * trans + glow;
            // the eye at each rift's heart, over the black (it's what the black hides): its plane at the warped core
            // (slots 204 + 3i), turned by the CPU's saccades (slot 200 + i: yaw + 10 x destination)
            float eyeTime = data(29);
            // the eye through the same lens pull as the picture (unpulled, it slid off as you came close: AdventurAgent)
            vec4 hsE = inv * vec4(uv * 2.0 - 1.0, 0.5, 1.0);
            vec3 dirE = normalize(hsE.xyz / hsE.w);
            float depthE = texture(DepthSampler, uv).r;
            vec4 hpE = inv * vec4(uv * 2.0 - 1.0, depthE, 1.0);
            float tEndE = depthE > 0.0 ? length(hpE.xyz / hpE.w) : 200.0;
            for (int i = 0; i < nsd && i < 4; i++) {
                vec3 ec = vec3(data(204 + 3 * i), data(205 + 3 * i), data(206 + 3 * i)) - camFrac;
                if (dot(ec, ec) > 128.0 * 128.0) continue;
                float yd = data(200 + i);
                int dest = int(floor((yd + 5.0) / 10.0));
                float f = yd - 10.0 * float(dest);
                float W = clamp(0.6 + data(223 + 5 * i) * data(240 + i) * 0.018, 0.75, 1.8);   // (StaticDeeps.eyeHalfWidth)
                vec3 nrm = vec3(sin(f), 0.0, cos(f)), rt = vec3(cos(f), 0.0, -sin(f));
                float dn = dot(dirE, nrm);
                if (abs(dn) < 1e-3) continue;
                float th = dot(ec, nrm) / dn;
                if (th <= 0.0 || th > tEndE) continue;
                vec3 hp = dirE * th - ec;
                vec2 ex = vec2(dot(hp, rt), hp.y);
                if (abs(ex.x) > W * 2.6 || abs(ex.y) > W * 2.6) continue;
                ex = (floor(ex * 16.0) + 0.5) / 16.0;      // Minecraft's texel grid
                vec4 ep = eyePattern(ex, eyeTime, int(data(224 + 5 * i)), W, dirE, dest);
                // mostly hidden in black fog (Baughn): drifting veils thin to show parts of it now and then
                float veil = value32(vec3(ex / W * 0.9 + vec2(eyeTime * 0.07, 0.0), eyeTime * 0.11), int(data(224 + 5 * i)) ^ 0xF06);
                float vis = 0.12 + 0.88 * smoothstep(0.42, 0.72, veil);
                c = mix(c + ep.rgb * vis, ep.rgb * vis, ep.a);
            }
        }
        // a storm's warning: a violet glow low on the upwind horizon, brightening as it comes (only on the sky)
        if (data(1313) > 0.0) {
            float wa = data(1314);
            vec2 up = -vec2(cos(wa), sin(wa));
            vec2 hd = normalize(dir.xz + vec2(1e-5));
            float facing = max(0.0, dot(hd, up));
            float low = exp(-max(0.0, dir.y + 0.05) * 6.0) * smoothstep(-0.35, -0.05, dir.y);
            // on the sky and, like fog, on far terrain (a sky-only glow cut hard along the skyline: AdventurAgent)
            float far = depth > 0.0 ? smoothstep(rd * 0.35, rd * 1.0, length(rel)) : 1.0;
            c += vec3(0.45, 0.2, 0.75) * data(1313) * pow(facing, 2.0) * low * 0.5 * far;
        }
        if (vivid > 0.0) c = clamp(mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, 1.0 + 2.0 * vivid) * (1.0 + 0.3 * vivid), 0.0, 1.0);
    }
    // the shields, over the (desaturated) scene: every sphere surface the view ray crosses before the scene
    if (stops > 0.5) {
        mat4 inv = mat4(data(0), data(1), data(2), data(3), data(4), data(5), data(6), data(7),
                        data(8), data(9), data(10), data(11), data(12), data(13), data(14), data(15));
        vec4 h = inv * vec4(texCoord * 2.0 - 1.0, depth > 0.0 ? depth : 0.5, 1.0);
        vec3 rel = h.xyz / h.w;
        vec3 v = normalize(rel);
        float tmax = depth > 0.0 ? length(rel) : 1e6;
        vec3 o = vec3(data(19), data(20), data(21));
        float time = data(29);
        int n = int(data(24));
        float camY = data(17);
        for (int i = 0; i < n && i < 13; i++) {
            int k = 32 + 10 * i;
            vec3 fc = vec3(data(k), data(k + 1), data(k + 2));
            float r = data(k + 3), collapse = data(k + 4), failing = data(k + 5);
            // a frozen remnant (Baughn: the collapse's last moment, stopped for good; StasisPost from Remnants): drawn
            // like the field was then, at its own fixed time
            bool frozen = data(k + 6) > 1.5;
            bool column = data(k + 6) > 0.5 && !frozen;
            float ftime = frozen ? data(k + 9) : time;   // (a remnant: the clocks as it froze, so it stays exactly as it was)
            vec3 oc = o - fc, vv = v;
            if (column) { oc.y = 0.0; vv.y = 0.0; }
            if (dot(vv, vv) < 1e-8) continue;
            vec4 st = stretchOf(i);
            float t1, t2;
            if (!fieldSpan(oc, vv, r, st, t1, t2)) continue;
            bool inside = length(unstretch(oc, st)) < r;
            float f = max(4.0, floor(r / 2.3 + 0.5));
            if (collapse > 0.0 || failing > 0.25) {
                // mist in spots (Baughn: mostly clear inside, fading in and out): where the eddies are (Eddies, the
                // stopped pockets from failing 0.7), and before that sparse wisps that come and go, each region on its
                // own slow pulse
                float share = collapse > 0.0 || failing > 0.7 ? eddyShare(collapse, failing) : 0.04 + 0.08 * (failing - 0.25) / 0.45;
                // (thinner wisps in a collapse and its remnant: AdventurAgent saw the whole old field greyed out)
                if (collapse > 0.0) share *= 0.7;
                float thr = 0.5 + (0.5 - share) * 0.45;
                float ta = max(t1, 0.0), tb = min(t2, tmax);
                if (tb > ta) {
                    vec3 camI = vec3(data(16), data(17), data(18));
                    float et = frozen ? data(1700 + 4 * i + 3) : data(30), mist = 0.0, stepLen = (tb - ta) / 12.0;
                    int sd = int(data(k + 7));
                    float jit = ign(gl_FragCoord.xy);   // (jittered steps: fixed ones left seams)
                    for (int m = 0; m < 12; m++) {
                        vec3 wp = camI + o + v * (ta + (float(m) + jit) * stepLen);
                        // each region on its own pulse, the phase smooth in space (a hash per 9-block cube read as
                        // grey boxes with straight edges: AdventurAgent)
                        float pulse = 0.5 + 0.5 * sin(et * 0.5 + value32(wp / 9.0, sd ^ 0x9a1) * 12.566);
                        float dn = smoothstep(thr - 0.03, thr + 0.03, eddy(wp, et, sd)) * pulse * pulse;
                        mist += (1.0 - mist) * dn * min(1.0, stepLen * (collapse > 0.0 ? 0.025 : 0.008));
                    }
                    c = mix(c, vec3(0.78, 0.82, 0.88), clamp(mist * 0.6, 0.0, 1.0));
                }
            }
            // far hit first, then near (back to front)
            for (int h2 = 0; h2 < 2; h2++) {
                float t = h2 == 0 ? t2 : t1;
                if (t <= 0.0 || t >= tmax) continue;
                vec3 hp = o + v * t - fc;
                vec3 hq = hp;
                if (column) hq.y = 0.0;
                // the cell grid on the round shape (cells stretch with the field), the gap in blocks at the local
                // radius; the true normal (the gradient) for the shading
                vec3 nn = normalize(unstretch(hq, st));
                vec3 nrm = normalize(unstretch(unstretch(hq, st), st));
                vec3 centre = nn;
                vec2 cell = column ? hexColumn(nn, hp.y + fc.y + camY, r) : hexCell(nn, f, centre) * vec2(length(hq), 1.0);
                float age = data(k + 8);
                float pop = 1.0;
                if (age >= 0.0 && age < 3.5) {
                    // start-up: hexes pop in from the top down once the colour front reaches the shell, rims flashing
                    float at = 2.0 + 0.5 * (1.0 - centre.y) * 0.5 + 0.3 * cell.y;
                    if (age < at) continue;
                    pop = age - at;
                }
                // (a collapse is amber all over)
                float warm = collapse > 0.0 ? 1.0 : 0.0;
                int sb = 1700 + 4 * i;
                vec4 sh = shieldAt(cell, nrm, centre, -v, r, data(sb), data(sb + 1), collapse, data(sb + 2), warm, ftime, h2 == 0 && !inside);
                if (data(31) > 0.0) {
                    // weather: where a deep crosses the field the hexes brighten (the opposite of dropout), pulsing
                    float dp = deepAt(fc + hp, vec3(data(16), data(17), data(18)));
                    if (dp > 0.05) sh = vec4(mix(sh.rgb, vec3(0.92, 0.96, 1.0), 0.6 * dp),
                        min(0.9, sh.a * (1.0 + 1.5 * dp * (0.75 + 0.25 * sin(ftime * 3.0 + cell.y * 6.0)))));
                }
                if (pop < 0.25) sh = vec4(mix(sh.rgb, vec3(1.0), 0.8 * (1.0 - pop / 0.25)), min(0.9, sh.a + 0.6 * (1.0 - pop / 0.25)));
                c = mix(c, sh.rgb, sh.a);
            }
        }
    }
    // lightning (BoltRenderer): glowing polylines in screen space, hidden behind nearer scene geometry
    int bp = int(data(256));
    if (bp > 1) {
        vec2 ndc = texCoord * 2.0 - 1.0;
        float aspect = OutSize.x / OutSize.y;
        float glow = 0.0;
        for (int i = 0; i < bp - 1 && i < 149; i++) {
            int o = 260 + 5 * i;
            float ia = data(o + 4), ib = data(o + 9);
            if (ia <= 0.0 || ib <= 0.0) continue;
            vec2 a = vec2(data(o), data(o + 1)), b = vec2(data(o + 5), data(o + 6));
            vec2 pa = (ndc - a) * vec2(aspect, 1.0), ba = (b - a) * vec2(aspect, 1.0);
            float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-9), 0.0, 1.0);
            float dist = length(pa - ba * h);
            float w = mix(data(o + 3), data(o + 8), h);
            float bd = mix(data(o + 2), data(o + 7), h);
            if (bd < depth - 1e-5) continue;     // reversed Z: the scene is nearer here
            float core = 1.0 - smoothstep(w * 0.4, w, dist);
            float halo = exp(-dist * dist / (w * w * 9.0)) * 0.35;
            glow = max(glow, (core + halo) * mix(ia, ib, h));
        }
        c = mix(c, vec3(0.85, 0.9, 1.0), clamp(glow, 0.0, 1.0));
    }
    if (warn > 0.0) {
        float v = length((texCoord - 0.5) * vec2(1.7, 1.0));
        float e = smoothstep(1.05 - warn * 1.15, 1.25 - warn * 1.15, v);
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float speck = step(0.97, hash13(vec3(floor(texCoord * OutSize / 4.0), 5.0)));
        c = mix(c, vec3(l) * 0.8 + vec3(0.03, 0.04, 0.06) + speck * 0.05, e);
    }
    fragColor = vec4(c, 1.0);
}
#endif
