#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// One textured quad of the map (MM-4, MM-9): two triangles over Rect (pixels of the target, y down) reading Src at
// Samples (texture coordinates at the corners), and Src2 at Samples2 for the blend between mip levels. No vertex buffer:
// the corners come from gl_VertexIndex.
layout(std140) uniform MapQuad {
    vec4 Rect;       // x0, y0, x1, y1
    vec4 Samples;    // u0, v0, u1, v1
    vec4 Params;     // x: 0 copy, 1 on screen, 2 background  w: share of Src2
    vec4 Background; // rgb
    vec4 Samples2;   // Src2's u0, v0, u1, v1
};

layout(location = 0) out vec2 uv;
layout(location = 1) out vec2 uv2;

void main() {
    int corner = gl_VertexIndex < 3 ? gl_VertexIndex : 6 - gl_VertexIndex;
    vec2 c = vec2(corner & 1, corner >> 1);
    gl_Position = ProjMat * vec4(mix(Rect.xy, Rect.zw, c), 0.0, 1.0);
    uv = mix(Samples.xy, Samples.zw, c);
    uv2 = mix(Samples2.xy, Samples2.zw, c);
}
