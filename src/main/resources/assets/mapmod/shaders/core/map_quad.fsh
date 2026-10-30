#version 330
#extension GL_ARB_separate_shader_objects : require

// The painted map's quads (MM-4). Copy: a baked tile or mip drawn with linear filtering (also how a mip is made from
// its four children: each drawn into a quarter, so every texel is the mean of a 2x2 block). Finish: the paper grain and
// the vignette over the whole picture at screen resolution, so the grain stays one screen pixel fine at every zoom.

uniform sampler2D Src;

layout(std140) uniform MapQuad {
    vec4 Rect;
    vec4 Samples;
    vec4 Params;            // x: 0 copy, 1 finish  y: paper grain  z: vignette
    vec4 View;              // xy: size of the target (pixels)
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

void main() {
    if (Params.x < 0.5) {
        fragColor = vec4(texture(Src, uv).rgb, 1.0);
        return;
    }
    vec2 fc = gl_FragCoord.xy;
    vec3 c = texelFetch(Src, ivec2(fc), 0).rgb;
    // paper grain, fixed to the screen like the paper under a painting
    float g = 0.65 * vnoise(fc / 2.0) + 0.35 * vnoise(fc / 6.0);
    c *= 1.0 + Params.y * (g - 0.5) * 2.0;
    vec2 v = max(View.xy, vec2(1.0));
    vec2 hs = 0.5 * vec2(v.x / v.y, 1.0);
    float r = length((fc / v - 0.5) * vec2(v.x / v.y, 1.0)) / length(hs);
    fragColor = vec4(c * (1.0 - Params.z * smoothstep(0.45, 1.0, r)), 1.0);
}
