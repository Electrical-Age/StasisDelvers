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
// length (600, 900, 1200, 1800 s); [192..195] floor(clock / that length); [196..199] unused; [200..203] static deep
// i's core facing (yaw, radians: the eye turns to the nearest player); [204..215] unused; [216] render distance (blocks);
// [1024 + 12i ..] drifting deep i (i < MAX_DEEPS, nearest first): centre - floor(camera) (x, z), radius, strength,
// surface y - floor(camera y), edge-noise id, lowest and highest ground - floor(camera y), ground grid corner -
// floor(camera) (x, z) and spacing, 1 unused; [2048 + 64i ..] drifting deep i's ground grid (8 x 8 surface y -
// floor(camera y), row = z); [3584 + 64i ..] static deep i's (corner centre - (1.3 r + 10), spacing 2 (1.3 r + 10) / 7);
// [256] bolt point count; [218] static deep count; [220 + 5i ..] static deep i: centre - floor(camera) (3), radius,
// shape id;
// [260 + 5i ..] bolt point i: NDC x, y, depth, half width (NDC), intensity (0 breaks the line); [32 + 10i ..] field i: centre - floor(camera) (3), radius, collapse, failing,
// column (0/1), seed (24-bit, exact), seconds since it formed (-1: no start-up animation), reserved.
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
    uint h = uint(x) * 0x8da6b343u ^ uint(y) * 0xd8163841u ^ uint(z) * 0xcb1ab31fu ^ uint(seed) * 0x9e3779b9u;
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
// used for the radius share. A block has stopped when 1 - score < collapse.
float collapseScore(vec3 block, vec3 off, float dist, float r, int seed) {
    float score = 0.45 * min(1.0, dist / r) + 0.55 * fbm32(block / 6.0, seed);
    float len = length(off);
    if (len > 1e-3) {
        int tears = 2 + int(hash32(seed, 7, 0, 0) * 3.0);
        for (int i = 0; i < tears; i++) {
            vec3 nv = vec3(hash32(seed, i, 1, 0), hash32(seed, i, 2, 0), hash32(seed, i, 3, 0)) * 2.0 - 1.0;
            float band = abs(dot(off, nv) / (len * (length(nv) + 1e-6)));
            if (band < 0.07) score -= 0.35 * (1.0 - band / 0.07);
        }
    }
    return score;
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
    return length(dv) / (R * rough);
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
    vec3 dv = q - hk.c;
    dv.y *= 1.4;
    float s = length(dv) / (hk.R * 1.15);
    float env = 1.0 - smoothstep(0.8, 1.15, s);
    if (env <= 0.0) return 0.0;
    float br = 0.5 + 0.5 * sin(breathAngle + id);
    float h = q.y - groundAt(STATIC_GROUND + 64 * hk.idx, hk.g, q.xz);
    vec3 p = (q + hitCamI) * (32.0 / 96.0);
    p.y *= 1.5;
    return env * (0.4 + 0.6 * br) * mistAt(p + id, h, 6.0 + 8.0 * br, 0.38 + 0.27 * br, staticPhase, detail);
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
        hits[hitCount] = Hit(1, i, c, R, 1.0, vec3(c.x - E, c.z - E, 2.0 * E / float(GG - 1)), int(data(o5 + 4)));
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
vec4 shieldAt(vec2 cell, vec3 n, vec3 view, float r, float failing, float warm, float time, bool farSide) {
    float edge = cell.x;                  // blocks from the cell edge, roughly x2
    float rnd = cell.y;
    float wave = 0.5 + 0.5 * sin(time * 1.3 - n.y * r * 0.18 + rnd * 1.5);
    float fres = pow(1.0 - abs(dot(n, view)), 2.0);
    float rim = 1.0 - smoothstep(0.12, 0.45, edge);
    float gap = 1.0 - smoothstep(0.0, 0.08, edge);
    float a = 0.02 + 0.07 * fres + rim * (0.14 + 0.22 * wave);
    a *= 1.0 - 0.8 * gap;
    if (farSide) a *= 0.5;
    // a failing shield (failing = 1 - charge while the store drains): a share of the cells is dark, growing from
    // none at 60% charge to 90% when empty. Which cells changes all the time (Baughn: the same ones always went first):
    // each cell's level wanders on its own slow noise; cells near the threshold flicker before they go
    if (failing > 0.0) {
        float p = 0.9 * smoothstep(0.4, 1.0, failing);
        float tt = time * (0.35 + 0.5 * rnd) + rnd * 61.0;
        float k = floor(tt), fr = smoothstep(0.0, 1.0, fract(tt));
        float lvl = mix(hash13(vec3(rnd * 131.0, k, 7.1)), hash13(vec3(rnd * 131.0, k + 1.0, 7.1)), fr);
        float m = lvl - p;
        if (m < 0.0) a *= 0.0;
        else if (m < 0.06 && hash13(vec3(rnd * 97.0, floor(time * 14.0), 3.3)) < 0.5) a *= 0.15;
    }
    vec3 tint = mix(vec3(0.62, 0.84, 1.0), vec3(1.0, 0.55, 0.2), warm);
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

// The strata pass (post_effect/stasis.json; this file with STRATA_PASS): the strata weather along the view ray, at
// the mist target's resolution. The strata mask (StrataSampler, client/StrataMask.java) is 64^3 cells of 4 blocks
// around the camera, 8 x 8 tiles of 64 x 64 (x across, y down, a tile per z), each texel the deep id (rgb) and
// a = strata. Out: r = 1 - transmittance, g = the lit mist in it (the rest is a rift's black), b = violet glow.
void main() {
    fragColor = vec4(0.0);
    if (data(22) < 0.5 || data(187) < 0.5) return;
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
    vec3 mo = vec3(data(184), data(185), data(186));
    // the ray through the mask's box
    vec3 inv3 = 1.0 / (dir + vec3(equal(dir, vec3(0.0))) * 1e-6);
    vec3 ta3 = (mo - o) * inv3, tb3 = (mo + 256.0 - o) * inv3;
    vec3 tmin = min(ta3, tb3), tmax = max(ta3, tb3);
    float ta = max(max(tmin.x, tmin.y), max(tmin.z, 0.0)), tb = min(min(tmax.x, tmax.y), min(tmax.z, tEnd));
    if (tb <= ta) return;
    int n = clamp(int(ceil((tb - ta) / 1.25)), 6, 40);
    float dt = (tb - ta) / float(n), jit = ign(gl_FragCoord.xy + 31.0);
    float T = 1.0, L = 0.0, V = 0.0;
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
    if (data(22) < 0.5 || (data(31) <= 0.0 && data(218) <= 0.0 && data(187) < 0.5)) return;
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

// ---- the rift core's eye (λ-Nero, settled look v3; prototype tools/rift/core_anim.py in LabAgent's workdir): an
// upright disc at the rift's centre turned to the nearest player. A black horizontal slit, a faint eye circle of ~1/2
// block area, urchin tendrils lashing out from the rim up and down and fading (each slot redrawn with new hashes every
// cycle), kept within the pupil's width +15% by a soft tanh bound, and faint lines inside that never meet them.
const float EYE_R = 0.399, EYE_PUPIL = 0.391;
const vec3 EYE_STRONG = vec3(0.75, 0.37, 0.94), EYE_FAINT = vec3(0.33, 0.22, 0.43), EYE_RING = vec3(0.24, 0.2, 0.28);

float segDist(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a;
    float h = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
    return length(p - a - ab * h);
}

// x, y in blocks on the disc (y up); the eye's colour (added) and the slit's cover (a)
vec4 eyePattern(vec2 x, float time, int id) {
    float r = length(x);
    vec3 col = EYE_RING * 0.9 * (1.0 - smoothstep(0.0, 0.025, abs(r - EYE_R)));
    // faint inner lines: short strokes on a jittered field, re-drawn about once a second, never reaching the rim
    if (r < EYE_R * 0.82) {
        float cyc = floor(time * 0.9);
        vec2 cell = floor(x / 0.12);
        float hh = hash32(int(cell.x), int(cell.y), int(cyc), id ^ 0xE7E);
        float ang = hh * 6.2832;
        vec2 dl = vec2(cos(ang), sin(ang));
        vec2 lc = x - (cell + 0.5) * 0.12;
        float line = 1.0 - smoothstep(0.004, 0.012, abs(dot(lc, vec2(-dl.y, dl.x))));
        line *= 1.0 - smoothstep(0.03, 0.06, abs(dot(lc, dl)));
        col += EYE_FAINT * line * step(0.55, hh) * sin(3.1416 * fract(time * 0.9));
    }
    // tendrils
    float tend = 0.0;
    float PB = EYE_PUPIL * 1.15;
    for (int k = 0; k < 22; k++) {
        float P = 0.55 + 0.6 * hash32(k, 0, 1, id ^ 0x7E4);
        float ph = hash32(k, 0, 2, id ^ 0x7E4) * P;
        float cyc = floor((time + ph) / P);
        float a = time + ph - cyc * P;
        int ci = int(cyc);
        float outT = 0.12 + 0.13 * hash32(k, ci, 3, id);
        float life = outT + 0.35 + 0.35 * hash32(k, ci, 4, id);
        if (a > life) continue;
        float up = hash32(k, ci, 5, id) < 0.5 ? -1.0 : 1.0;
        vec3 v3 = normalize(vec3(hash32(k, ci, 6, id) - 0.5, (abs(hash32(k, ci, 7, id) - 0.5) * 6.0 + 1.0) * up, hash32(k, ci, 8, id) - 0.5));
        vec2 d2 = v3.xy;
        float fore = length(d2);
        d2 /= max(fore, 1e-3);
        float len = (0.7 + 0.6 * hash32(k, ci, 9, id)) * (0.35 + 0.65 * fore);
        float curl = (hash32(k, ci, 10, id) - 0.5) * 4.4;
        float wave = 2.0 + 3.0 * hash32(k, ci, 11, id), phase = 6.3 * hash32(k, ci, 12, id);
        float x0 = (hash32(k, ci, 13, id) * 1.8 - 0.9) * EYE_PUPIL;
        vec2 st = vec2(x0, sqrt(max(EYE_R * EYE_R - x0 * x0, 0.0)) * up * 1.02);
        // cheap reject: the tendril stays within its length of its start
        if (length(x - st) > len + 0.3) continue;
        float head = pow(min(1.0, a / outT), 0.6);
        float fade = a < outT ? 1.0 : 1.0 - (a - outT) / (life - outT);
        vec2 perp = vec2(-d2.y, d2.x);
        vec2 prev = st;
        for (int m = 1; m <= 8; m++) {
            float s = head * float(m) / 8.0;
            float bend = curl * s * s * sin(phase + a * 9.0) + 0.12 * sin(wave * s * 6.28 + phase + a * 14.0);
            vec2 p = st + d2 * s * len + perp * bend * 0.25 * s;
            p.x = PB * tanh(p.x / PB);
            float w = 0.022 * (1.0 - 0.7 * float(m) / 8.0) * fore + 0.008;
            float dd = segDist(x, prev, p);
            tend = max(tend, fade * (1.0 - smoothstep(w * 0.5, w, dd)) * (fade < 1.0 ? 0.5 + 0.5 * float(m) / 8.0 : 1.0));
            prev = p;
        }
    }
    col += EYE_STRONG * tend * 1.3;
    // the slit: black, opaque
    vec2 sl = x / vec2(EYE_R * 0.98, EYE_R * 0.075);
    float slit = 1.0 - smoothstep(0.85, 1.0, dot(sl, sl));
    return vec4(col, slit);
}

// strata weather colours: the mist a little darker than the deeps' (underground), the warning violet
const vec3 STRATA_MIST = vec3(0.42, 0.40, 0.50), STRATA_VIOLET = vec3(0.62, 0.22, 0.95);

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
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
            vec3 fc = vec3(data(o), data(o + 1), data(o + 2));
            float r = data(o + 3), collapse = data(o + 4);
            vec3 dv = p - fc;
            if (data(o + 6) > 0.5) dv.y = 0.0;
            float d = length(dv);
            float inside = 1.0 - smoothstep(r - 1.2, r, d);
            if (collapse > 0.0) {
                // the same rule as Field.collapsed (patches and tears on the field's seed)
                vec3 blk = floor(w);
                vec3 off = blk + 0.5 - (camInt + fc);
                if (data(o + 6) > 0.5) off.y = 0.0;
                float score = collapseScore(blk, off, d, r, int(data(o + 7)));
                inside *= 1.0 - smoothstep(-0.02, 0.02, collapse - (1.0 - score));
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
            }
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
        c = mix(grey, c, run);
        if (data(31) > 0.0 || data(218) > 0.0) {
            // deep mist along the view ray, out to the render distance (fading with the terrain there): marched at a
            // reduced resolution by the mist pass, upsampled here
            vec2 mist = mistUp(depth > 0.0 ? tScene : -1.0, rd);
            c = c * (1.0 - 0.85 * mist.x) + 0.85 * ((mist.x - mist.y) * MIST_THIN + mist.y * MIST_DENSE);
        }
        if (data(187) > 0.5) {
            // strata weather (the strata pass): mist, the violet warning and black rift pockets in the strata caves
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
                    float core = 1.0 - smoothstep(0.22, 0.36, sh);
                    float border = smoothstep(0.26, 0.36, sh) * (1.0 - smoothstep(0.36, 0.5, sh));
                    glow += trans * border * sl * 0.035 * vec3(0.3, 0.12, 0.5);
                    trans *= exp(-sl * 0.6 * core);
                }
            }
            c = c * trans + glow;
            // the eye at each rift's heart, over the black (it's what the black hides)
            float eyeTime = data(29);
            for (int i = 0; i < nsd && i < 4; i++) {
                vec3 ec = vec3(data(220 + 5 * i), data(221 + 5 * i), data(222 + 5 * i)) - camFrac;
                if (dot(ec, ec) > 96.0 * 96.0) continue;
                float f = data(200 + i);
                vec3 nrm = vec3(sin(f), 0.0, cos(f)), rt = vec3(cos(f), 0.0, -sin(f));
                float dn = dot(dir2, nrm);
                if (abs(dn) < 1e-3) continue;
                float th = dot(ec, nrm) / dn;
                if (th <= 0.0 || th > tEnd) continue;
                vec3 hp = dir2 * th - ec;
                vec2 ex = vec2(dot(hp, rt), hp.y);
                if (abs(ex.x) > 1.8 || abs(ex.y) > 1.8) continue;
                vec4 ep = eyePattern(ex, eyeTime, int(data(224 + 5 * i)));
                c = mix(c + ep.rgb, vec3(0.0), ep.a);
            }
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
            bool column = data(k + 6) > 0.5;
            if (collapse >= 1.0) continue;
            vec3 oc = o - fc, vv = v;
            if (column) { oc.y = 0.0; vv.y = 0.0; }
            float aa = dot(vv, vv);
            if (aa < 1e-8) continue;
            float b = dot(oc, vv), cc = dot(oc, oc) - r * r, disc = b * b - aa * cc;
            if (disc <= 0.0) continue;
            float sq = sqrt(disc);
            float t1 = (-b - sq) / aa, t2 = (-b + sq) / aa;
            bool inside = cc < 0.0;
            float f = max(4.0, floor(r / 2.3 + 0.5));
            if (collapse > 0.0 || failing > 0.7) {
                // mist where the eddies are (Eddies): pale, cold, densest in the stopped pockets
                float thr = 0.5 + (0.5 - eddyShare(collapse, failing)) * 0.45;
                float ta = max(t1, 0.0), tb = min(t2, tmax);
                if (tb > ta) {
                    vec3 camI = vec3(data(16), data(17), data(18));
                    float et = data(30), mist = 0.0, stepLen = (tb - ta) / 12.0;
                    int sd = int(data(k + 7));
                    for (int m = 0; m < 12; m++) {
                        vec3 wp = camI + o + v * (ta + (float(m) + 0.5) * stepLen);
                        float dn = smoothstep(thr - 0.03, thr + 0.03, eddy(wp, et, sd));
                        mist += (1.0 - mist) * dn * min(1.0, stepLen * 0.025);
                    }
                    c = mix(c, vec3(0.78, 0.82, 0.88), mist * 0.6);
                }
            }
            // far hit first, then near (back to front)
            for (int h2 = 0; h2 < 2; h2++) {
                float t = h2 == 0 ? t2 : t1;
                if (t <= 0.0 || t >= tmax) continue;
                vec3 hp = o + v * t - fc;
                vec3 nn = hp;
                if (column) nn.y = 0.0;
                nn = normalize(nn);
                vec3 centre = nn;
                vec2 cell = column ? hexColumn(nn, hp.y + fc.y + camY, r) : hexCell(nn, f, centre) * vec2(r, 1.0);
                // collapse: a hex dies (after a rim flash) when the land under its centre stops (Field.collapsed);
                // the failing dropout is off then
                float brk = 1.0;
                if (collapse > 0.0) {
                    vec3 camI = vec3(data(16), data(17), data(18));
                    // the land a little way in under the hex (at the shell itself everything goes early: outer first)
                    vec3 under = centre * (0.6 * r);
                    if (column) under.y = hp.y;
                    vec3 blk = floor(camI + fc + under);
                    vec3 off = blk + 0.5 - (camI + fc);
                    if (column) off.y = 0.0;
                    brk = (1.0 - collapseScore(blk, off, 0.6 * r, r, int(data(k + 7)))) - collapse;
                    if (brk < 0.0) continue;
                }
                float age = data(k + 8);
                float pop = 1.0;
                if (age >= 0.0 && age < 3.5) {
                    // start-up: hexes pop in from the top down once the colour front reaches the shell, rims flashing
                    float at = 2.0 + 0.5 * (1.0 - centre.y) * 0.5 + 0.3 * cell.y;
                    if (age < at) continue;
                    pop = age - at;
                }
                // warm colour as the dropout starts (failing 0.4 = 60% charge), full amber in a collapse
                float warm = collapse > 0.0 ? 1.0 : smoothstep(0.35, 0.9, failing);
                vec4 sh = shieldAt(cell, nn, -v, r, collapse > 0.0 ? 0.0 : failing, warm, time, h2 == 0 && !inside);
                if (data(31) > 0.0) {
                    // weather: where a deep crosses the field the hexes brighten (the opposite of dropout), pulsing
                    float dp = deepAt(fc + centre * r, vec3(data(16), data(17), data(18)));
                    if (dp > 0.05) sh = vec4(mix(sh.rgb, vec3(0.92, 0.96, 1.0), 0.6 * dp),
                        min(0.9, sh.a * (1.0 + 1.5 * dp * (0.75 + 0.25 * sin(time * 3.0 + cell.y * 6.0)))));
                }
                if (pop < 0.25) sh = vec4(mix(sh.rgb, vec3(1.0), 0.8 * (1.0 - pop / 0.25)), min(0.9, sh.a + 0.6 * (1.0 - pop / 0.25)));
                // about to die: the rim crackles white-gold (rim only, flickering; ~0.4 s in a real collapse)
                if (brk < 0.015) {
                    float rimm = 1.0 - smoothstep(0.1, 0.4, cell.x);
                    float fl = step(0.4, hash13(vec3(cell.y * 53.0, floor(time * 18.0), 9.1)));
                    sh = vec4(mix(sh.rgb, vec3(1.0, 0.95, 0.85), rimm * fl), min(0.9, sh.a + 0.5 * rimm * fl));
                }
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
