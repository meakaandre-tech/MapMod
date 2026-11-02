#version 330
#extension GL_ARB_separate_shader_objects : require

// The map's quads (MM-9). Textures hold one texel a block (a mip a texel per 2^k blocks), premultiplied: explored land
// opaque in its colour, unexplored land transparent black.
//  - copy (mode 0): the texel as it is: a tile's picture into its baked texture (nearest, texel for texel), or a mip's
//    four children each into a quarter (linear, so every texel is the exact mean of a 2x2 block);
//  - on screen (mode 1): close up read nearest (the sampler), so blocks are crisp squares; zoomed out, where a pixel
//    spans more than a texel, four taps spread over the pixel's footprint average it, so the picture does not shimmer as
//    it moves; then laid over the flat background, so unexplored land is the background with hard chunk edges;
//  - background (mode 2): the background on screen, transparent in a texture.
// Mid-blend between two mip levels a quad also reads the old level (Src2) and mixes it in.

uniform sampler2D Src;
uniform sampler2D Src2;

layout(std140) uniform MapQuad {
    vec4 Rect;
    vec4 Samples;
    vec4 Params;     // x: 0 copy, 1 on screen, 2 background  w: share of Src2
    vec4 Background; // rgb  a: 1 on screen
    vec4 Samples2;
};

layout(location = 0) in vec2 uv;
layout(location = 1) in vec2 uv2;

layout(location = 0) out vec4 fragColor;

// a pixel's footprint averaged: one tap where it covers a texel or less, else four spread over it
vec4 area(sampler2D s, vec2 p) {
    vec2 size = vec2(textureSize(s, 0));
    vec2 texels = fwidth(p) * size;
    if (max(texels.x, texels.y) <= 1.0) return texture(s, p);
    vec2 d = 0.25 * fwidth(p);
    return 0.25 * (texture(s, p + vec2(-d.x, -d.y)) + texture(s, p + vec2(d.x, -d.y))
        + texture(s, p + vec2(-d.x, d.y)) + texture(s, p + vec2(d.x, d.y)));
}

void main() {
    if (Params.x > 1.5) {
        fragColor = Background.a > 0.5 ? vec4(Background.rgb, 1.0) : vec4(0.0);
        return;
    }
    if (Params.x < 0.5) {
        fragColor = texture(Src, uv);
        return;
    }
    vec4 c = area(Src, uv);
    if (Params.w > 0.0) c = mix(c, area(Src2, uv2), Params.w);
    fragColor = vec4(c.rgb + Background.rgb * (1.0 - c.a), 1.0);
}
