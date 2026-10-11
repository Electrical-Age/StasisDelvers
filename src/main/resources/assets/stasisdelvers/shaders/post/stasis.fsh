#version 330
#extension GL_ARB_separate_shader_objects : require

// Stasis Delvers: drain the colour of everything where time is stopped, and the player's freeze warning (grey
// creeping in from the screen edges). Field data comes in a 128x1 texture of packed floats (StasisPost.java):
// [0..15] inverse(projection * view rotation); [16..18] floor(camera); [19..21] fract(camera); [22] stops here (0/1);
// [23] freeze warning 0..1; [24] field count; [25..28] spawn area min x, min z, max x, max z (blocks; empty if
// min > max); [32 + 6i ..] field i: centre - floor(camera) (3), radius, collapse, failing.

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

void main() {
    vec3 c = texture(InSampler, texCoord).rgb;
    float stops = data(22);
    float warn = data(23);
    float depth = texture(DepthSampler, texCoord).r;
    // 26.3 renders with reversed Z in [0, 1]: the far plane (sky) is 0, and NDC z is the depth value itself
    if (stops > 0.5 && depth > 0.0) {
        mat4 inv = mat4(data(0), data(1), data(2), data(3), data(4), data(5), data(6), data(7),
                        data(8), data(9), data(10), data(11), data(12), data(13), data(14), data(15));
        vec4 h = inv * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
        vec3 rel = h.xyz / h.w;                                    // world position minus the camera
        vec3 camFrac = vec3(data(19), data(20), data(21));
        vec3 p = rel + camFrac;                                    // world position minus floor(camera)
        vec3 camInt = vec3(data(16), data(17), data(18));
        // how much time runs here: 1 inside a field (soft at the edge: the border shouldn't look blocky), 0 outside
        float run = 0.0;
        vec3 w = p + camInt;
        if (w.x >= data(25) && w.z >= data(26) && w.x < data(27) + 1.0 && w.z < data(28) + 1.0) run = 1.0;
        int n = int(data(24));
        for (int i = 0; i < n && i < 16; i++) {
            int o = 32 + 6 * i;
            vec3 fc = vec3(data(o), data(o + 1), data(o + 2));
            float r = data(o + 3), collapse = data(o + 4);
            float d = length(p - fc);
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
    if (warn > 0.0) {
        float v = length((texCoord - 0.5) * vec2(1.7, 1.0));
        float e = smoothstep(1.05 - warn * 1.15, 1.25 - warn * 1.15, v);
        float l = dot(c, vec3(0.299, 0.587, 0.114));
        float speck = step(0.97, hash13(vec3(floor(texCoord * OutSize / 4.0), 5.0)));
        c = mix(c, vec3(l) * 0.8 + vec3(0.03, 0.04, 0.06) + speck * 0.05, e);
    }
    fragColor = vec4(c, 1.0);
}
