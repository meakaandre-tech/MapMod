#version 330
#extension GL_ARB_separate_shader_objects : require

// The painted map's quads (MM-4): a baked tile or mip drawn with linear filtering (also how a mip is made from its four
// children: each drawn into a quarter, so every texel is the mean of a 2x2 block), or plain paper. On screen the paper
// grain and the vignette go on as each pixel is written, from the screen position, so the grain stays one screen pixel
// fine at every zoom and never scales with the map; baked tiles and mips carry neither.

uniform sampler2D Src;

layout(std140) uniform MapQuad {
    vec4 Rect;
    vec4 Samples;
    vec4 Params;            // x: 0 copy, 1 copy and finish, 2 paper and finish  y: paper grain  z: vignette
    vec4 View;              // xy: size of the target (pixels)
    vec4 Paper;             // rgb
};

layout(location = 0) in vec2 uv;

layout(location = 0) out vec4 fragColor;

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float vnoise(vec2 p) {
    vec2 i = floor(p), f = p - i;
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i), b = hash(i + vec2(1.0, 0.0)), c = hash(i + vec2(0.0, 1.0)), d = hash(i + 1.0);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// paper grain, fixed to the screen like the paper under a painting, and the vignette
vec3 finish(vec3 c) {
    vec2 fc = gl_FragCoord.xy;
    float g = 0.65 * vnoise(fc / 2.0) + 0.35 * vnoise(fc / 6.0);
    c *= 1.0 + Params.y * (g - 0.5) * 2.0;
    vec2 v = max(View.xy, vec2(1.0));
    vec2 hs = 0.5 * vec2(v.x / v.y, 1.0);
    float r = length((fc / v - 0.5) * vec2(v.x / v.y, 1.0)) / length(hs);
    return c * (1.0 - Params.z * smoothstep(0.45, 1.0, r));
}

void main() {
    vec3 c = Params.x > 1.5 ? Paper.rgb : texture(Src, uv).rgb;
    fragColor = vec4(Params.x > 0.5 ? finish(c) : c, 1.0);
}
