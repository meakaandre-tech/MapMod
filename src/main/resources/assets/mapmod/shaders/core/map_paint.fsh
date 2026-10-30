#version 330
#extension GL_ARB_separate_shader_objects : require

// The painted map, v1 (MM-2): smooth height (bicubic), hillshade from the northwest, one palette colour per surface
// class times the biome tint, darker lowlands and paler peaks, snow above a height, water flat blue by depth, and paper
// where nothing is explored. Every field is sampled smoothly over the sixteen samples round the point: height with
// Catmull-Rom weights (it passes through every sample), and the class, water and explored masks with cubic B-spline
// weights, whose half-way lines are smooth curves; there is no nearest-neighbour look-up anywhere, so no sample's square
// or block edge ever shows.

uniform sampler2D Fields;   // r, g: height + 32768 (high, low byte)   b: water depth   a: explored
uniform sampler2D Surface;  // rgb: biome tint   a: class * 32 + 16

layout(std140) uniform MapPaint {
    vec4 ClassColour[8];    // rgb, a: how much the biome tint tints it
    vec4 WaterShallow;      // rgb, a: depth (blocks) at which water is fully deep
    vec4 WaterDeep;         // rgb
    vec4 Paper;             // rgb
    vec4 SnowColour;        // rgb
    vec4 HeightTint;        // x: low height  y: high height  z: shade at low  w: light at high
    vec4 Params;            // x: hillshade mix  y: snow above  z: snow blend  w: relief
};

layout(location = 0) in vec2 samplePos;   // in texels of this tile's textures: texel i's centre is at i

layout(location = 0) out vec4 fragColor;

// Catmull-Rom weights of the four samples round t, and their derivatives
vec4 catmullRom(float t) {
    float t2 = t * t, t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2);
}

vec4 catmullRomSlope(float t) {
    float t2 = t * t;
    return vec4(-1.5 * t2 + 2.0 * t - 0.5, 4.5 * t2 - 5.0 * t, -4.5 * t2 + 4.0 * t + 0.5, 1.5 * t2 - t);
}

// cubic B-spline weights: smooth and never negative, for the masks
vec4 bspline(float t) {
    float s = 1.0 - t, t2 = t * t, t3 = t2 * t;
    return vec4(s * s * s, 3.0 * t3 - 6.0 * t2 + 4.0, -3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0, t3) / 6.0;
}

void main() {
    vec2 base = floor(samplePos);
    vec2 t = samplePos - base;
    ivec2 b = ivec2(base);
    vec4 hx = catmullRom(t.x), hz = catmullRom(t.y), sx = catmullRomSlope(t.x), sz = catmullRomSlope(t.y);
    vec4 mx = bspline(t.x), mz = bspline(t.y);

    float h = 0.0, dhdx = 0.0, dhdz = 0.0;
    float explored = 0.0, wet = 0.0, depth = 0.0;
    float classWeight[8];
    for (int c = 0; c < 8; c++) classWeight[c] = 0.0;
    vec3 tint = vec3(0.0);
    float tintWeight = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            ivec2 p = b + ivec2(i - 1, j - 1);
            vec4 f = texelFetch(Fields, p, 0);
            vec4 s = texelFetch(Surface, p, 0);
            float v = floor(f.r * 255.0 + 0.5) * 256.0 + floor(f.g * 255.0 + 0.5) - 32768.0;
            h += hx[i] * hz[j] * v;
            dhdx += sx[i] * hz[j] * v;
            dhdz += hx[i] * sz[j] * v;
            float e = f.a > 0.5 ? mx[i] * mz[j] : 0.0;
            float d = floor(f.b * 255.0 + 0.5);
            explored += e;
            if (d > 0.0) {
                wet += e;
                depth += e * d;
            } else {
                int c = clamp(int(s.a * 255.0) / 32, 0, 7);
                classWeight[c] += e;
                tint += e * s.rgb;
                tintWeight += e;
            }
        }
    }

    // edges a pixel wide, smoothed so they do not stair-step
    float aa = max(fwidth(explored), 0.001);
    float shown = smoothstep(0.5 - aa, 0.5 + aa, explored);
    float wetShare = wet / max(explored, 0.0001);
    float wa = max(fwidth(wetShare), 0.001);
    float water = smoothstep(0.5 - wa, 0.5 + wa, wetShare);

    // land: the class with the most weight here, tinted, darker low and paler high, snow above the line
    int best = 0;
    for (int c = 1; c < 8; c++) if (classWeight[c] > classWeight[best]) best = c;
    vec4 cls = ClassColour[best];
    vec3 biome = tintWeight > 0.0 ? tint / tintWeight : vec3(1.0);
    biome /= max(max(biome.r, biome.g), max(biome.b, 0.001));
    vec3 land = cls.rgb * mix(vec3(1.0), biome, cls.a);
    land *= mix(HeightTint.z, HeightTint.w, smoothstep(HeightTint.x, HeightTint.y, h));
    land = mix(land, SnowColour.rgb, smoothstep(Params.y - Params.z, Params.y + Params.z, h));

    // hillshade: normals from the slope (a sample is 4 blocks), lit from the northwest, mixed in at Params.x
    vec3 n = normalize(vec3(-dhdx / 4.0 * Params.w, -dhdz / 4.0 * Params.w, 1.0));
    vec3 light = normalize(vec3(-1.0, -1.0, 1.4));
    float shade = clamp(dot(n, light) / light.z, 0.0, 1.6);
    land *= mix(1.0, shade, Params.x);

    // water: flat, from shallow to deep blue by depth
    vec3 sea = mix(WaterShallow.rgb, WaterDeep.rgb, smoothstep(0.0, WaterShallow.a, depth / max(wet, 0.0001)));

    vec3 colour = mix(land, sea, water);
    fragColor = vec4(mix(Paper.rgb, colour, shown), 1.0);
}
