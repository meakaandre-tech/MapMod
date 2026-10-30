#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// One tile of the painted map: two triangles over Rect (pixels of the painted texture, y down), carrying the tile's
// sample coordinates (Samples) to the fragment shader. No vertex buffer: the corners come from gl_VertexIndex.
layout(std140) uniform MapTile {
    vec4 Rect;      // x0, y0, x1, y1
    vec4 Samples;   // u0, v0, u1, v1: texel coordinates of the tile's textures at those corners
};

layout(location = 0) out vec2 samplePos;

void main() {
    // 0 1 2  2 1 3 over the corners (0, 0) (1, 0) (0, 1) (1, 1)
    int corner = gl_VertexIndex < 3 ? gl_VertexIndex : 6 - gl_VertexIndex;
    vec2 c = vec2(corner & 1, corner >> 1);
    gl_Position = ProjMat * vec4(mix(Rect.xy, Rect.zw, c), 0.0, 1.0);
    samplePos = mix(Samples.xy, Samples.zw, c);
}
