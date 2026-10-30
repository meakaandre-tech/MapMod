#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// One textured quad of the painted map (MM-4): two triangles over Rect (pixels of the target, y down) reading Src at
// Samples (texture coordinates at the corners). No vertex buffer: the corners come from gl_VertexIndex. MM-5 carries a
// second source for the blend between mip levels, the fog mask's coordinates and the world position (for the clouds).
layout(std140) uniform MapQuad {
    vec4 Rect;      // x0, y0, x1, y1
    vec4 Samples;   // u0, v0, u1, v1
    vec4 Params;    // x: 0 copy, 1 copy and finish, 2 paper and finish  y: paper grain  z: vignette  w: share of Src2
    vec4 View;      // xy: size of the target (pixels)  z: 1 with a fog mask
    vec4 Paper;     // rgb
    vec4 Samples2;  // Src2's u0, v0, u1, v1
    vec4 MaskUv;    // the fog mask's u0, v0, u1, v1
    vec4 World;     // world position at the corners, in samples: x0, z0, x1, z1
    vec4 FogColour; // rgb, a: cloud strength
};

layout(location = 0) out vec2 uv;
layout(location = 1) out vec2 uv2;
layout(location = 2) out vec2 maskUv;
layout(location = 3) out vec2 world;

void main() {
    int corner = gl_VertexIndex < 3 ? gl_VertexIndex : 6 - gl_VertexIndex;
    vec2 c = vec2(corner & 1, corner >> 1);
    gl_Position = ProjMat * vec4(mix(Rect.xy, Rect.zw, c), 0.0, 1.0);
    uv = mix(Samples.xy, Samples.zw, c);
    uv2 = mix(Samples2.xy, Samples2.zw, c);
    maskUv = mix(MaskUv.xy, MaskUv.zw, c);
    world = mix(World.xy, World.zw, c);
}
