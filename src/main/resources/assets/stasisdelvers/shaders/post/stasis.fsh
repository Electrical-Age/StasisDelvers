#version 330
#extension GL_ARB_separate_shader_objects : require

// Stasis Delvers: drain the colour of everything where time is stopped, and the player's freeze warning (grey
// creeping in from the screen edges). Field data comes in a 128x1 texture of packed floats (StasisPost.java):
// [0..15] inverse(projection * view rotation); [16..18] floor(camera); [19..21] fract(camera); [22] stops here (0/1);
// [23] freeze warning 0..1; [24] field count; [25..28] spawn area min x, min z, max x, max z (blocks; empty if
// min > max); [29] time (s); [32 + 7i ..] field i: centre - floor(camera) (3), radius, collapse, failing, column (0/1).
// The shield is drawn here too, after the desaturation, so its colour survives (Baughn's option a): each field's
// sphere is hit by the view ray, and the hit point is placed on a Goldberg-style hexagon grid (a triangular
// lattice on each icosahedron face; the nearest lattice point is the cell, the gap to the second nearest its rim).

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D DataSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float data(int i) {
    uvec4 b = uvec4(round(texelFetch(DataSampler, ivec2(i, 0), 0) * 255.0));
    return uintBitsToFloat(b.r | (b.g << 8) | (b.b << 16) | (b.a << 24));
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
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
vec2 hexCell(vec3 n, float f) {
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
vec4 shieldAt(vec2 cell, vec3 n, vec3 view, float r, float failing, float time, bool farSide) {
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
    vec3 tint = mix(vec3(0.62, 0.84, 1.0), vec3(1.0, 0.55, 0.2), min(1.0, failing * 2.0));
    return vec4(tint * (0.8 + 0.4 * wave), clamp(a, 0.0, 0.9));
}

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
        float run = 0.0;
        vec3 w = p + camInt;
        if (w.x >= data(25) && w.z >= data(26) && w.x < data(27) + 1.0 && w.z < data(28) + 1.0) run = 1.0;
        int n = int(data(24));
        for (int i = 0; i < n && i < 13; i++) {
            int o = 32 + 7 * i;
            vec3 fc = vec3(data(o), data(o + 1), data(o + 2));
            float r = data(o + 3), collapse = data(o + 4);
            vec3 dv = p - fc;
            if (data(o + 6) > 0.5) dv.y = 0.0;
            float d = length(dv);
            float inside = 1.0 - smoothstep(r - 1.2, r, d);
            if (collapse > 0.0) {
                // the same rule as Field.collapsed (outer first, in patches); fine detail differs, the look is close
                vec3 b = floor(w) / 5.0;
                float score = 0.7 * min(1.0, d / r) + 0.3 * hash13(floor(b));
                inside *= 1.0 - smoothstep(-0.02, 0.02, collapse - (1.0 - score));
            }
            run = max(run, inside);
        }
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        vec3 grey = mix(vec3(l), c, 0.18) * vec3(0.96, 0.99, 1.04);
        c = mix(grey, c, run);
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
            int k = 32 + 7 * i;
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
            // far hit first, then near (back to front)
            for (int h2 = 0; h2 < 2; h2++) {
                float t = h2 == 0 ? t2 : t1;
                if (t <= 0.0 || t >= tmax) continue;
                vec3 hp = o + v * t - fc;
                vec3 nn = hp;
                if (column) nn.y = 0.0;
                nn = normalize(nn);
                vec2 cell = column ? hexColumn(nn, hp.y + fc.y + camY, r) : hexCell(nn, f) * vec2(r, 1.0);
                vec4 sh = shieldAt(cell, nn, -v, r, failing, time, h2 == 0 && !inside);
                c = mix(c, sh.rgb, sh.a);
            }
        }
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
