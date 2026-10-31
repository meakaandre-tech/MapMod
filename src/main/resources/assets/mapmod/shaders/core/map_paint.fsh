#version 330
#extension GL_ARB_separate_shader_objects : require

// The painted map (MM-3; MM-4 bakes it once per tile and adds grain and the vignette in a pass of their own). Relief: smooth height (Catmull-Rom, unwarped), a hillshade from the northwest, darker lowlands
// and paler peaks, snow above a height, optional contour lines. Everything else is read at a position warped by
// low-frequency noise in world space (seamless across tiles, still when panning or zooming) with cubic B-spline weights.
// MM-8, the land's colour: the real block colours, brushed into patches on the CPU (TilePrep's Kuwahara filter), read
// with B-spline weights and split in two where the 4x4 texels round a pixel hold two colours: each side's mean, blended
// across a soft, wandering border where their weights cross, so flat land is smooth, borders between blocks are crisp,
// and no sample's square shows. Forests are darkened by canopy with a soft brush; water from a signed distance to the
// shore (a smooth coast and ribbon rivers), deep to shallow with a pale band along the shore, tinted by the water's own
// colour. Unexplored land is paper. There is no
// nearest-neighbour look-up anywhere, so no sample's square or block edge ever shows. Painted live on screen, the paper
// grain and the vignette go on from the screen position (as map_quad does); baked, they are left out. On screen the fog
// over unexplored land goes on too (MM-5), from the tile's fog mask and the same static clouds as map_quad's; baked,
// unexplored samples only fade softly to paper (the fog, not the bake, decides what is hidden, so exploring bakes nothing).

uniform sampler2D Fields;   // r, g: height + 32768 (high, low byte)   b: water depth   a: explored
uniform sampler2D Surface;  // rgb: the land's painted colour (MM-8)   a: canopy
uniform sampler2D Effects;  // r: share of forest floor   g: share of snow (MM-8)
uniform sampler2D Water;    // rgb: the water's own colour   a: 1 on water (MM-8)
uniform sampler2D Shore;    // r: signed distance to the shore in samples, 128 + 4 d (water positive)
uniform sampler2D Fog;      // r: explored, 0 to 1 (MM-5; on screen only)

layout(std140) uniform MapPaint {
    vec4 WaterShallow;      // rgb, a: depth (blocks) at which water is fully deep
    vec4 WaterDeep;         // rgb, a: distance from the shore (blocks) at which water is fully deep
    vec4 Paper;             // rgb
    vec4 SnowColour;        // rgb
    vec4 HeightTint;        // x: low height  y: high height  z: shade at low  w: light at high
    vec4 Params;            // x: hillshade mix  y: snow above  z: snow blend  w: relief
    vec4 ShoreColour;       // rgb, a: width of the shore band (blocks)
    vec4 Edges;             // x: border softness  y: warp (blocks)
    vec4 Forest;            // x: darkening at full canopy  y: brush strength
    vec4 Contour;           // x: interval (blocks)  y: strength
    vec4 Finish;            // x: paper grain  y: vignette
    vec4 FogColour;         // rgb, a: cloud strength
    vec4 Colour;            // x: how much the water's own colour tints it  y: blocks a sample (MM-8)
};

layout(std140) uniform MapTile {
    vec4 Rect;
    vec4 Samples;
    vec4 Origin;            // xy: world position of texel (0, 0) in noise units (4 blocks), wrapped to PERIOD  z: noise units a texel
    vec4 View;              // xy: picture size (pixels)  z: 1 on screen (fog, grain and vignette)  w: 1 with contours on
    vec4 MaskUv;
    vec4 Mask;              // x: 1 with a fog mask
};

layout(location = 0) in vec2 samplePos;   // in texels of this tile's textures: texel i's centre is at i
layout(location = 1) in vec2 maskUv;

layout(location = 0) out vec4 fragColor;

// world-space noise is laid out in noise units of 4 blocks (MM-8: no longer a sample, which is 2 blocks now, so it looks
// as before) and repeats every PERIOD of them (8192 blocks), so tile origins can be wrapped on the CPU and the numbers
// here stay small at any distance from spawn
const float PERIOD = 2048.0;
// how much the hillshade's slope is sharpened against the smoothed slope (MM-7)
const float SHARPEN = 0.6;

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

// the warp at a world position (noise units), in noise units: two octaves, 64 and 16 blocks across
vec2 warp(vec2 ws) {
    vec2 big = vec2(vnoise(ws / 16.0, PERIOD / 16.0), vnoise(ws / 16.0 + vec2(53.0, 17.0), PERIOD / 16.0));
    vec2 small = vec2(vnoise(ws / 4.0 + vec2(7.0, 91.0), PERIOD / 4.0), vnoise(ws / 4.0 + vec2(29.0, 3.0), PERIOD / 4.0));
    return ((big - 0.5) * 1.4 + (small - 0.5) * 0.6) * (Edges.y / 4.0);
}

vec4 catmullRom(float t) {
    float t2 = t * t, t3 = t2 * t;
    return vec4(-0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1.0, -1.5 * t3 + 2.0 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2);
}

vec4 catmullRomSlope(float t) {
    float t2 = t * t;
    return vec4(-1.5 * t2 + 2.0 * t - 0.5, 4.5 * t2 - 5.0 * t, -4.5 * t2 + 4.0 * t + 0.5, 1.5 * t2 - t);
}

vec4 bsplineSlope(float t) {
    float s = 1.0 - t;
    return vec4(-0.5 * s * s, 1.5 * t * t - 2.0 * t, -1.5 * t * t + t + 0.5, 0.5 * t * t);
}

vec4 bspline(float t) {
    float s = 1.0 - t, t2 = t * t, t3 = t2 * t;
    return vec4(s * s * s, 3.0 * t3 - 6.0 * t2 + 4.0, -3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0, t3) / 6.0;
}

// paper grain (fixed to the screen, like the paper under a painting) and the vignette: the same as map_quad's
vec3 finish(vec3 c) {
    vec2 fc = gl_FragCoord.xy;
    float g = 0.65 * vnoise(fc / 2.0, 0.0) + 0.35 * vnoise(fc / 6.0, 0.0);
    c *= 1.0 + Finish.x * (g - 0.5) * 2.0;
    vec2 hs = 0.5 * vec2(View.x / max(View.y, 1.0), 1.0);
    float r = length((fc / max(View.xy, vec2(1.0)) - 0.5) * vec2(View.x / max(View.y, 1.0), 1.0)) / length(hs);
    return c * (1.0 - Finish.y * smoothstep(0.45, 1.0, r));
}

// static clouds in world space (noise units), 0 to 1: the same as map_quad's
float clouds(vec2 ws) {
    float spp = max(length(fwidth(ws)), 1e-4);
    float c = 0.5 * mix(vnoise(ws / 64.0, PERIOD / 64.0), 0.5, smoothstep(16.0, 48.0, spp));
    c += 0.3 * mix(vnoise(ws / 16.0 + vec2(19.0, 7.0), PERIOD / 16.0), 0.5, smoothstep(4.0, 12.0, spp));
    c += 0.2 * mix(vnoise(ws / 4.0 + vec2(5.0, 31.0), PERIOD / 4.0), 0.5, smoothstep(1.0, 3.0, spp));
    return c;
}

// unexplored land fades into fog: the same as map_quad's
vec3 fog(vec3 c, float mask, vec2 ws) {
    float cl = clouds(ws);
    float s = FogColour.a;
    float shown = smoothstep(0.55, 0.95, mask + (cl - 0.5) * 0.3 * s);
    vec3 f = mix(FogColour.rgb, Paper.rgb, s * smoothstep(0.35, 0.75, cl));
    return mix(f, c, shown);
}

void main() {
    ivec2 lim = textureSize(Fields, 0) - 1;
    float bps = Colour.y;

    // relief and the explored mask, where the sample is
    vec2 base = floor(samplePos);
    vec2 t = samplePos - base;
    ivec2 b = ivec2(base);
    vec4 hx = catmullRom(t.x), hz = catmullRom(t.y), sx = catmullRomSlope(t.x), sz = catmullRomSlope(t.y);
    vec4 mx = bspline(t.x), mz = bspline(t.y), bx = bsplineSlope(t.x), bz = bsplineSlope(t.y);
    float h = 0.0, dhdx = 0.0, dhdz = 0.0, explored = 0.0, smoothX = 0.0, smoothZ = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec4 f = texelFetch(Fields, clamp(b + ivec2(i - 1, j - 1), ivec2(0), lim), 0);
            float v = floor(f.r * 255.0 + 0.5) * 256.0 + floor(f.g * 255.0 + 0.5) - 32768.0;
            h += hx[i] * hz[j] * v;
            dhdx += sx[i] * hz[j] * v;
            dhdz += hx[i] * sz[j] * v;
            smoothX += bx[i] * mz[j] * v;
            smoothZ += mx[i] * bz[j] * v;
            explored += f.a > 0.5 ? mx[i] * mz[j] : 0.0;
        }
    }

    // everything else at the warped position
    vec2 ws = Origin.xy + samplePos * Origin.z;
    vec2 wp = samplePos + warp(ws) / Origin.z;
    vec2 wbase = floor(wp);
    vec2 wt = wp - wbase;
    ivec2 wb = ivec2(wbase);
    vec4 wx = bspline(wt.x), wz = bspline(wt.y);
    vec3 land;
    vec3 cols[16];
    float wts[16];
    vec3 mean = vec3(0.0), wtint = vec3(0.0);
    vec2 fx = vec2(0.0);
    float canopy = 0.0, landW = 0.0, wetW = 0.0, depth = 0.0, sdf = 0.0, tintW = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            int k = i + j * 4;
            ivec2 p = clamp(wb + ivec2(i - 1, j - 1), ivec2(0), lim);
            float m = wx[i] * wz[j];
            vec4 f = texelFetch(Fields, p, 0);
            vec4 s = texelFetch(Surface, p, 0);
            sdf += m * (texelFetch(Shore, p, 0).r * 255.0 - 128.0) / 4.0;
            float e = f.a > 0.5 ? m : 0.0;
            float d = floor(f.b * 255.0 + 0.5);
            cols[k] = s.rgb;
            wts[k] = 0.0;
            if (d > 0.0) {
                wetW += e;
                depth += e * d;
                vec4 wc = texelFetch(Water, p, 0);
                wtint += e * wc.rgb;
                tintW += e * wc.a;
            } else {
                wts[k] = e;
                landW += e;
                mean += e * s.rgb;
                canopy += e * s.a;
                fx += e * texelFetch(Effects, p, 0).rg;
            }
        }
    }

    // unexplored samples fade softly to paper: the fog's mask, not this, decides what is shown
    float shown = smoothstep(0.5, 0.9, explored);

    // land: the colours round the pixel, smooth where they agree; where they hold two, each side's mean, blended across
    // a soft border where their weights cross. The split is along the colours' main axis (the farthest from the mean,
    // refined twice against their spread), so it follows a border between any two colours, not only light and dark
    // (no branch round this: fwidth below needs every pixel of the quad to run it)
    {
        mean /= max(landW, 1e-6);
        vec3 axis = vec3(0.0);
        float far = -1.0;
        for (int k = 0; k < 16; k++) {
            vec3 dv = cols[k] - mean;
            float q = wts[k] > 0.0 ? dot(dv, dv) : -1.0;
            if (q > far) {
                far = q;
                axis = dv;
            }
        }
        for (int it = 0; it < 2; it++) {
            vec3 next = vec3(0.0);
            for (int k = 0; k < 16; k++) {
                vec3 dv = cols[k] - mean;
                next += wts[k] * dot(dv, axis) * dv;
            }
            axis = dot(next, next) > 1e-12 ? next : axis;
        }
        vec3 ca = vec3(0.0), cb = vec3(0.0);
        float wa = 0.0, wb2 = 0.0;
        for (int k = 0; k < 16; k++) {
            if (dot(cols[k] - mean, axis) > 0.0) {
                ca += wts[k] * cols[k];
                wa += wts[k];
            } else {
                cb += wts[k] * cols[k];
                wb2 += wts[k];
            }
        }
        ca = wa > 0.0 ? ca / wa : mean;
        cb = wb2 > 0.0 ? cb / wb2 : mean;
        float share = wa / max(wa + wb2, 1e-6);
        float soft = max(Edges.x, 1.5 * fwidth(share));
        vec3 crisp = mix(cb, ca, smoothstep(0.5 - soft, 0.5 + soft, share));
        // two colours only where they truly differ: a gentle gradient (a biome's tint) stays smooth
        float split = smoothstep(0.03, 0.10, length(ca - cb));
        land = mix(mean, crisp, split);
    }
    if (landW <= 0.0) land = vec3(1.0);
    land *= mix(HeightTint.z, HeightTint.w, smoothstep(HeightTint.x, HeightTint.y, h));
    land = mix(land, SnowColour.rgb, smoothstep(Params.y - Params.z, Params.y + Params.z, h));
    vec2 effect = landW > 0.0 ? fx / landW : vec2(0.0);
    // snow keeps its brightness
    land = mix(land, max(land, SnowColour.rgb * 0.96), 0.5 * effect.y);

    // forests: darker under canopy, with a soft brush in world space that fades out before it would alias
    float cov = landW > 0.0 ? canopy / landW : 0.0;
    float spp = length(fwidth(ws));
    float brush = (vnoise(ws / 2.0 + vec2(11.0, 5.0), PERIOD / 2.0) - 0.5) * (1.0 - smoothstep(0.3, 0.9, spp))
        + 0.6 * (vnoise(ws + vec2(3.0, 41.0), PERIOD) - 0.5) * (1.0 - smoothstep(0.15, 0.45, spp));
    land *= 1.0 - Forest.x * cov;
    land *= 1.0 + Forest.y * max(cov, effect.x) * brush * 2.0;

    // hillshade: normals from the slope (a sample is Colour.y blocks), lit from the northwest. MM-7: the slope is
    // sharpened against its B-spline-smoothed self (an unsharp mask on the height's gradient), so ridges and cliffs read
    // crisp without any sample's square showing
    dhdx += SHARPEN * (dhdx - smoothX);
    dhdz += SHARPEN * (dhdz - smoothZ);
    vec3 n = normalize(vec3(-dhdx / bps * Params.w, -dhdz / bps * Params.w, 1.0));
    vec3 light = normalize(vec3(-1.0, -1.0, 1.4));
    float shade = clamp(dot(n, light) / light.z, 0.0, 1.6);
    land *= mix(1.0, shade, Params.x);

    // contours: a faint line every interval of height, a pixel or so wide, gone where they would crowd
    if (View.w > 0.5) {
        float x = h / Contour.x;
        float cw = max(fwidth(x), 0.0001);
        float d = abs(fract(x + 0.5) - 0.5) / cw;
        float line = (1.0 - smoothstep(0.5, 1.5, d)) * (1.0 - smoothstep(0.3, 0.7, cw));
        land *= 1.0 - Contour.y * line;
    }

    // water: deep to shallow by depth and distance from the shore, tinted by the water's own colour, a pale band along
    // the shore, the coast where the distance field crosses zero
    float dist = sdf * bps;
    float avgDepth = wetW > 0.0 ? depth / wetW : 0.0;
    float deep = clamp(0.5 * avgDepth / WaterShallow.a + 0.5 * max(dist, 0.0) / WaterDeep.a, 0.0, 1.0);
    vec3 sea = mix(WaterShallow.rgb, WaterDeep.rgb, smoothstep(0.0, 1.0, deep));
    if (tintW > 0.0) {
        vec3 own = wtint / tintW;
        float lum = dot(own, vec3(0.299, 0.587, 0.114));
        vec3 hue = lum > 0.001 ? own / lum : vec3(1.0);
        float seaLum = dot(sea, vec3(0.299, 0.587, 0.114));
        sea = mix(sea, clamp(hue * seaLum, 0.0, 1.0), Colour.x);
    }
    float band = 1.0 - smoothstep(0.0, max(ShoreColour.a, 0.01), dist);
    sea = mix(sea, ShoreColour.rgb, 0.8 * band);
    float wa2 = max(fwidth(sdf), 0.0001);
    float water = smoothstep(-wa2, wa2, sdf);

    vec3 colour = mix(land, sea, water);
    vec3 c = mix(Paper.rgb, colour, shown);
    if (View.z > 0.5) c = finish(fog(c, Mask.x > 0.5 ? texture(Fog, maskUv).r : 0.0, ws));
    fragColor = vec4(c, 1.0);
}
