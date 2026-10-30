#version 330
#extension GL_ARB_separate_shader_objects : require

// The painted map's quads (MM-4): a baked tile or mip drawn with linear filtering (also how a mip is made from its four
// children: each drawn into a quarter, so every texel is the mean of a 2x2 block), or plain paper. On screen the fog
// over unexplored land (MM-5), the paper grain and the vignette go on as each pixel is written: the fog from the cell's
// mask and a static cloud texture in world space, the grain from the screen position, so the grain stays one screen
// pixel fine at every zoom and never scales with the map; baked tiles and mips carry none of it. Mid-blend between two
// mip levels a quad also reads the old level (Src2) and mixes it in.

uniform sampler2D Src;
uniform sampler2D Src2;
uniform sampler2D Fog;

layout(std140) uniform MapQuad {
    vec4 Rect;
    vec4 Samples;
    vec4 Params;            // x: 0 copy, 1 copy and finish, 2 paper and finish  y: paper grain  z: vignette  w: share of Src2
    vec4 View;              // xy: size of the target (pixels)  z: 1 with a fog mask
    vec4 Paper;             // rgb
    vec4 Samples2;
    vec4 MaskUv;
    vec4 World;
    vec4 FogColour;         // rgb, a: cloud strength
};

layout(location = 0) in vec2 uv;
layout(location = 1) in vec2 uv2;
layout(location = 2) in vec2 maskUv;
layout(location = 3) in vec2 world;

layout(location = 0) out vec4 fragColor;

// world-space noise repeats every PERIOD samples (8192 blocks), as map_paint's does
const float PERIOD = 2048.0;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

// smooth value noise over cells of size 1, repeating every n cells (n = 0: never)
float vnoise(vec2 p, float n) {
    vec2 i = floor(p), f = p - i;
    vec2 u = f * f * (3.0 - 2.0 * f);
    vec2 j = i + 1.0;
    if (n > 0.0) {
        i = mod(i, n);
        j = mod(j, n);
    }
    float a = hash(i), b = hash(vec2(j.x, i.y)), c = hash(vec2(i.x, j.y)), d = hash(j);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// static clouds in world space (samples), 0 to 1: three octaves, 256, 64 and 16 blocks across, each settling to its
// middle before it is fine enough to alias
float clouds(vec2 ws) {
    float spp = max(length(fwidth(ws)), 1e-4);
    float c = 0.5 * mix(vnoise(ws / 64.0, PERIOD / 64.0), 0.5, smoothstep(16.0, 48.0, spp));
    c += 0.3 * mix(vnoise(ws / 16.0 + vec2(19.0, 7.0), PERIOD / 16.0), 0.5, smoothstep(4.0, 12.0, spp));
    c += 0.2 * mix(vnoise(ws / 4.0 + vec2(5.0, 31.0), PERIOD / 4.0), 0.5, smoothstep(1.0, 3.0, spp));
    return c;
}

// unexplored land fades into fog: its edge from the mask, roughened by the clouds, which also mottle the fog itself
vec3 fog(vec3 c, float mask) {
    float cl = clouds(world);
    float s = FogColour.a;
    // only land at least half way into the blurred mask shows, so the bake's own soft edge never does
    float m = clamp(mask * 2.0 - 1.0, 0.0, 1.0);
    float shown = smoothstep(0.6, 0.95, m + (cl - 0.5) * 0.4 * s);
    vec3 f = mix(FogColour.rgb, Paper.rgb, s * smoothstep(0.35, 0.75, cl));
    return mix(f, c, shown);
}

// paper grain, fixed to the screen like the paper under a painting, and the vignette
vec3 finish(vec3 c) {
    vec2 fc = gl_FragCoord.xy;
    float g = 0.65 * vnoise(fc / 2.0, 0.0) + 0.35 * vnoise(fc / 6.0, 0.0);
    c *= 1.0 + Params.y * (g - 0.5) * 2.0;
    vec2 v = max(View.xy, vec2(1.0));
    vec2 hs = 0.5 * vec2(v.x / v.y, 1.0);
    float r = length((fc / v - 0.5) * vec2(v.x / v.y, 1.0)) / length(hs);
    return c * (1.0 - Params.z * smoothstep(0.45, 1.0, r));
}

void main() {
    vec3 c = Params.x > 1.5 ? Paper.rgb : texture(Src, uv).rgb;
    if (Params.w > 0.0) c = mix(c, texture(Src2, uv2).rgb, Params.w);
    if (Params.x > 0.5) {
        float mask = View.z > 0.5 ? texture(Fog, maskUv).r : 0.0;
        c = finish(fog(c, mask));
    }
    fragColor = vec4(c, 1.0);
}
