package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.capture.SurfaceTile;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The painted map's GPU work (MM-4), on render targets of its own the way the game paints its picture-in-picture views:
 * an orthographic projection over the target's pixels (y down, flipped as the game flips it), so every target holds its
 * picture with v running from 1 at the top to 0 at the bottom, and is drawn on with that. Two kinds of pass:
 * <ul>
 *   <li>{@link #paint}: {@code core/map_paint}, the full painted shader, one draw per tile (a bake, or close zoom);</li>
 *   <li>{@link #quads}: {@code core/map_quad} copying textures with linear filtering (baked tiles and mips on screen,
 *   and a mip's four children into its quarters), or laying paper.</li>
 * </ul>
 * On screen both add the paper grain and the vignette as they write each pixel, from its screen position: one pass over
 * the screen's pixels with no picture read back in the same frame (on a software rasterizer a read-back stalls the
 * render thread until the earlier pass is drawn). Render thread only.
 */
final class MapGpu implements AutoCloseable {
    static final RenderPipeline PAINT = RenderPipeline.builder()
        .withLocation(MapMod.id("pipeline/map_paint"))
        .withVertexShader(MapMod.id("core/map_paint"))
        .withFragmentShader(MapMod.id("core/map_paint"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("MapPaint", UniformType.UNIFORM_BUFFER)
            .withUniform("MapTile", UniformType.UNIFORM_BUFFER)
            .withUniform("Fields", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Surface", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Effects", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Water", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Shore", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Fog", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .build();
    static final RenderPipeline QUAD = RenderPipeline.builder()
        .withLocation(MapMod.id("pipeline/map_quad"))
        .withVertexShader(MapMod.id("core/map_quad"))
        .withFragmentShader(MapMod.id("core/map_quad"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("MapQuad", UniformType.UNIFORM_BUFFER)
            .withUniform("Src", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Src2", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Fog", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .build();
    /** Bytes of the palette block: 13 vec4s (MM-8: the class colours are worked in on the CPU, {@link TilePrep}). */
    private static final int PALETTE_SIZE = 13 * 16;
    /** Bytes of one draw's block (the larger of a quad's 9 vec4s and a tile's 6), and the stride between them (the strictest offset alignment). */
    private static final int BLOCK_SIZE = 9 * 16, BLOCK_STRIDE = 256;
    /**
     * Blocks in one unit of the shaders' world-space noise: 4, what a sample was before MM-8, so the warp, the brush, the
     * grain of forests and the clouds look as they did when samples went to 2 blocks.
     */
    static final int NOISE_UNIT = 4;
    /** The shader's noise repeats every this many noise units (8192 blocks); tile origins are wrapped to it. */
    static final int NOISE_PERIOD = 2048;
    private static final String[] SAMPLERS = {"Fields", "Surface", "Effects", "Water", "Shore"};
    /** How much the relief is exaggerated for the hillshade. */
    private static final float RELIEF = 3.5f;

    /** A texture to draw into and read from: its view, its size, and the projection over its pixels. */
    static final class Target implements AutoCloseable {
        final GpuTexture texture;
        final GpuTextureView view;
        final int width, height;

        Target(String name, int width, int height) {
            GpuDevice device = RenderSystem.getDevice();
            texture = device.createTexture(() -> MapMod.ID + " " + name, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            view = device.createTextureView(texture);
            this.width = width;
            this.height = height;
        }

        /** Bytes it takes on the GPU. */
        long bytes() {
            return (long) width * height * 4;
        }

        @Override
        public void close() {
            view.close();
            texture.close();
        }
    }

    /**
     * One tile to paint: its textures, its corners in the target's pixels, the texels at those corners, and the world
     * position of its texel (0, 0) in noise units ({@link #NOISE_UNIT} blocks), wrapped to {@link #NOISE_PERIOD}. On screen (MM-5) also its fog mask (null:
     * all unexplored) and the mask's texture coordinates at the corners.
     */
    record PaintQuad(TileTextures tex, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float ox, float oz,
                     @Nullable GpuTextureView mask, float m0, float n0, float m1, float n1) {
        PaintQuad(TileTextures tex, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float ox, float oz) {
            this(tex, x0, y0, x1, y1, u0, v0, u1, v1, ox, oz, null, 0, 0, 0, 0);
        }

        PaintQuad withMask(FogMasks.@Nullable Uv m) {
            return m == null ? this : new PaintQuad(tex, x0, y0, x1, y1, u0, v0, u1, v1, ox, oz, m.view(), m.u0(), m.v0(), m.u1(), m.v1());
        }
    }

    /**
     * One texture to copy: its corners in the target's pixels and its texture coordinates there (v up, see above). A
     * null source lays plain paper. On screen (MM-5) it can also blend a second source over it ({@code fade} of it: the
     * cross-fade between mip levels), and carries its fog mask (null: all unexplored) with the mask's coordinates at the
     * corners, and its world position at the corners in noise units (wrapped to {@link #NOISE_PERIOD}) for the clouds.
     */
    record TexQuad(@Nullable GpuTextureView src, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                   @Nullable GpuTextureView src2, float s0, float t0, float s1, float t1, float fade,
                   @Nullable GpuTextureView mask, float m0, float n0, float m1, float n1,
                   float wx0, float wz0, float wx1, float wz1) {
        TexQuad(@Nullable GpuTextureView src, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
            this(src, x0, y0, x1, y1, u0, v0, u1, v1, null, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** Paper over the whole of a target. */
        static TexQuad paper(Target t) {
            return new TexQuad(null, 0, 0, t.width, t.height, 0, 1, 1, 0);
        }

        TexQuad withFade(@Nullable GpuTextureView from, float s0, float t0, float s1, float t1, float fade) {
            return new TexQuad(src, x0, y0, x1, y1, u0, v0, u1, v1, from, s0, t0, s1, t1, fade, mask, m0, n0, m1, n1, wx0, wz0, wx1, wz1);
        }

        TexQuad withMask(FogMasks.@Nullable Uv m) {
            return m == null ? this : new TexQuad(src, x0, y0, x1, y1, u0, v0, u1, v1, src2, s0, t0, s1, t1, fade, m.view(), m.u0(), m.v0(), m.u1(), m.v1(), wx0, wz0, wx1, wz1);
        }

        TexQuad withWorld(float wx0, float wz0, float wx1, float wz1) {
            return new TexQuad(src, x0, y0, x1, y1, u0, v0, u1, v1, src2, s0, t0, s1, t1, fade, mask, m0, n0, m1, n1, wx0, wz0, wx1, wz1);
        }
    }

    /** One projection per target size used: a tile's 512 and the screen's. */
    private final List<Projected> projections = new ArrayList<>();
    /**
     * Frames whose draw buffers are kept apart: a frame never writes a buffer the last few frames' draws read, which
     * would make the driver wait for them.
     */
    static final int FRAMES = 3;
    private @Nullable GpuBuffer palette, blocks;
    private final @Nullable GpuBuffer[] ring = new GpuBuffer[FRAMES];
    private final int[] capacities = new int[FRAMES];
    private int paletteVersion = -1, capacity, cursor, slot;
    /** Where draw blocks are put together before they are written (reused: allocating one each pass costs). */
    private @Nullable ByteBuffer scratch;
    /** Bound as the source of a paper quad, which reads none. */
    private @Nullable DynamicTexture blank;
    /** Draw buffers outgrown, per frame slot, let go when that slot comes round again. */
    @SuppressWarnings("unchecked")
    private final List<GpuBuffer>[] retired = new List[]{new ArrayList<>(), new ArrayList<>(), new ArrayList<>()};
    /** Passes and draws this frame (for the stats). */
    int passes, draws;

    private static final class Projected {
        final int w, h;
        final Projection projection = new Projection();
        final ProjectionMatrixBuffer buffer;

        Projected(int w, int h) {
            this.w = w;
            this.h = h;
            buffer = new ProjectionMatrixBuffer(MapMod.ID + " map " + w + "x" + h);
            projection.setupOrtho(-1000f, 1000f, w, h, true);
        }
    }

    /** Starts a frame: the draw blocks are written from the start again, with room for {@code expected} draws. */
    void beginFrame(int expected) {
        ring[slot] = blocks;
        capacities[slot] = capacity;
        slot = (slot + 1) % FRAMES;
        blocks = ring[slot];
        capacity = capacities[slot];
        cursor = 0;
        passes = draws = 0;
        for (GpuBuffer b : retired[slot]) b.close();
        retired[slot].clear();
        reserve(expected);
    }

    private void reserve(int n) {
        if (blocks != null && capacity >= cursor + n) return;
        // a buffer already drawn from this frame stays as it is until the next: the next draws go to a new, bigger one
        if (blocks != null) retired[slot].add(blocks);
        capacity = Math.max(64, Integer.highestOneBit(Math.max(1, cursor + n) - 1) << 2);
        cursor = 0;
        blocks = RenderSystem.getDevice().createBuffer(() -> MapMod.ID + " map draws", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
            (long) capacity * BLOCK_STRIDE);
    }

    private GpuBufferSlice projected(int w, int h) {
        for (Projected p : projections) if (p.w == w && p.h == h) return p.buffer.getBuffer(p.projection);
        // a screen size from before is let go: at most a tile's and the screen's are kept
        while (projections.size() >= 3) {
            Projected old = projections.remove(projections.size() - 1);
            old.buffer.close();
        }
        Projected p = new Projected(w, h);
        projections.add(0, p);
        return p.buffer.getBuffer(p.projection);
    }

    /** Writes the draws' blocks, {@link #BLOCK_SIZE} bytes each from {@code fill}, and returns where the first is. */
    private int write(CommandEncoder encoder, int n, BlockWriter fill) {
        reserve(n);
        int first = cursor;
        if (scratch == null || scratch.capacity() < n * BLOCK_STRIDE) {
            scratch = ByteBuffer.allocateDirect(Math.max(64, Integer.highestOneBit(n) << 1) * BLOCK_STRIDE).order(ByteOrder.nativeOrder());
        }
        ByteBuffer data = scratch.clear().limit(n * BLOCK_STRIDE);
        for (int i = 0; i < n; i++) fill.write(Std140Builder.intoBuffer(data.slice(i * BLOCK_STRIDE, BLOCK_SIZE).order(ByteOrder.nativeOrder())), i);
        data.position(0).limit(n * BLOCK_STRIDE);
        encoder.writeToBuffer(blocks.slice((long) first * BLOCK_STRIDE, (long) n * BLOCK_STRIDE), data);
        cursor += n;
        return first;
    }

    private interface BlockWriter {
        void write(Std140Builder b, int i);
    }

    private GpuBufferSlice block(int i) {
        return blocks.slice((long) i * BLOCK_STRIDE, BLOCK_SIZE);
    }

    private static Optional<Vector4fc> colour(int rgb) {
        return Optional.of(new Vector4f((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, 1f));
    }

    /** The paper colour as a clear, or none to draw over what the target holds. */
    static Optional<Vector4fc> clear(@Nullable PaintPalette paper) {
        return paper == null ? Optional.empty() : colour(paper.paper());
    }

    /** Paints tiles into the target with the full shader; {@code clear} as {@link #clear}; on screen with the fog, grain and vignette. */
    void paint(Target t, Optional<Vector4fc> clear, List<PaintQuad> quads, PaintPalette pal, int version, boolean contours, boolean screen) {
        if (quads.isEmpty() && clear.isEmpty()) return;
        GpuDevice device = RenderSystem.getDevice();
        if (palette == null || paletteVersion != version) {
            if (palette != null) palette.close();
            palette = paletteBuffer(device, pal);
            paletteVersion = version;
        }
        CommandEncoder encoder = device.createCommandEncoder();
        float flag = contours ? 1f : 0f, finish = screen ? 1f : 0f;
        int first = quads.isEmpty() ? 0 : write(encoder, quads.size(), (b, i) -> {
            PaintQuad q = quads.get(i);
            b.putVec4(q.x0, q.y0, q.x1, q.y1).putVec4(q.u0, q.v0, q.u1, q.v1)
                .putVec4(q.ox, q.oz, (float) SurfaceTile.SPACING / NOISE_UNIT, 0).putVec4(t.width, t.height, finish, flag)
                .putVec4(q.m0, q.n0, q.m1, q.n1).putVec4(q.mask == null ? 0 : 1, 0, 0, 0);
        });
        GpuTextureView none = blank();
        GpuSampler smooth = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        // everything that writes to a buffer happens before the pass opens: no other command may run while it is open
        GpuBufferSlice projection = projected(t.width, t.height);
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(PAINT);
        passes++;
        try (RenderPass pass = encoder.createRenderPass(() -> MapMod.ID + " map paint", t.view, clear)) {
            if (compiled == null || quads.isEmpty()) return;
            pass.setPipeline(compiled);
            pass.setUniform("Projection", projection);
            pass.setUniform("MapPaint", palette);
            for (int i = 0; i < quads.size(); i++) {
                PaintQuad q = quads.get(i);
                TileTextures tex = q.tex;
                pass.setUniform("MapTile", block(first + i));
                for (int s = 0; s < SAMPLERS.length; s++) pass.setUniform(SAMPLERS[s], tex.textures[s].getTextureView(), smooth);
                pass.setUniform("Fog", q.mask == null ? none : q.mask, smooth);
                pass.draw(6, 1, 0, 0);
                draws++;
            }
        }
    }

    /** A 1x1 texture of zeros: bound where a quad has no second source or no fog mask (all unexplored). */
    private GpuTextureView blank() {
        if (blank == null) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixel(0, 0, 0);
            blank = new DynamicTexture(() -> MapMod.ID + " map blank", image);
            blank.upload();
        }
        return blank.getTextureView();
    }

    /**
     * Copies textures (or lays paper) into the target with linear filtering, or nearest ({@code nearest}: a mip's
     * child made again from it, texel for texel); {@code clear} as {@link #clear}. With a palette ({@code screen}) every
     * pixel also gets the fog, its grain and the vignette.
     */
    void quads(Target t, Optional<Vector4fc> clear, List<TexQuad> quads, @Nullable PaintPalette screen, boolean nearest) {
        if (quads.isEmpty() && clear.isEmpty()) return;
        GpuDevice device = RenderSystem.getDevice();
        GpuTextureView none = blank();
        CommandEncoder encoder = device.createCommandEncoder();
        float grain = screen == null ? 0 : screen.grain(), vignette = screen == null ? 0 : screen.vignette();
        int paper = screen == null ? 0 : screen.paper();
        int fog = screen == null ? 0 : screen.fogColour();
        float clouds = screen == null ? 0 : screen.fogClouds();
        int first = quads.isEmpty() ? 0 : write(encoder, quads.size(), (b, i) -> {
            TexQuad q = quads.get(i);
            float mode = q.src == null ? 2 : screen == null ? 0 : 1;
            float fade = q.src2 == null ? 0 : q.fade;
            b.putVec4(q.x0, q.y0, q.x1, q.y1).putVec4(q.u0, q.v0, q.u1, q.v1).putVec4(mode, grain, vignette, fade)
                .putVec4(t.width, t.height, q.mask == null ? 0 : 1, 0);
            colour(b, paper, 0);
            b.putVec4(q.s0, q.t0, q.s1, q.t1).putVec4(q.m0, q.n0, q.m1, q.n1).putVec4(q.wx0, q.wz0, q.wx1, q.wz1);
            colour(b, fog, clouds);
        });
        GpuSampler smooth = RenderSystem.getSamplerCache().getClampToEdge(nearest ? FilterMode.NEAREST : FilterMode.LINEAR);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuBufferSlice projection = projected(t.width, t.height);
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(QUAD);
        passes++;
        try (RenderPass pass = encoder.createRenderPass(() -> MapMod.ID + " map quads", t.view, clear)) {
            if (compiled == null || quads.isEmpty()) return;
            pass.setPipeline(compiled);
            pass.setUniform("Projection", projection);
            for (int i = 0; i < quads.size(); i++) {
                pass.setUniform("MapQuad", block(first + i));
                TexQuad q = quads.get(i);
                pass.setUniform("Src", q.src == null ? none : q.src, smooth);
                pass.setUniform("Src2", q.src2 == null ? none : q.src2, smooth);
                pass.setUniform("Fog", q.mask == null ? none : q.mask, linear);
                pass.draw(6, 1, 0, 0);
                draws++;
            }
        }
    }

    private static GpuBuffer paletteBuffer(GpuDevice device, PaintPalette p) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder b = Std140Builder.onStack(stack, PALETTE_SIZE);
            colour(b, p.waterShallow(), p.deepAt());
            colour(b, p.waterDeep(), p.deepDist());
            colour(b, p.paper(), 0);
            colour(b, p.snow(), 0);
            b.putVec4(p.lowHeight(), p.highHeight(), p.lowShade(), p.highLight());
            b.putVec4(p.hillshade(), p.snowAbove(), p.snowBlend(), RELIEF);
            colour(b, p.shore(), p.shoreWidth());
            b.putVec4(p.edgeSoftness(), p.edgeWarp(), 0, 0);
            b.putVec4(p.forestDarken(), p.forestStipple(), 0, 0);
            b.putVec4(p.contourInterval(), p.contourStrength(), 0, 0);
            b.putVec4(p.grain(), p.vignette(), 0, 0);
            colour(b, p.fogColour(), p.fogClouds());
            b.putVec4(p.waterTint(), SurfaceTile.SPACING, 0, 0);
            return device.createBuffer(() -> MapMod.ID + " map palette", GpuBuffer.USAGE_UNIFORM, b.get());
        }
    }

    private static void colour(Std140Builder b, int rgb, float w) {
        b.putVec4((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, w);
    }

    /** Compiles both pipelines now: null if they compiled, else what went wrong. */
    static @Nullable String compileCheck() {
        try {
            if (RenderSystem.getCompiledPipelineNullable(PAINT) == null) return "the map paint shader did not compile (see the log)";
            if (RenderSystem.getCompiledPipelineNullable(QUAD) == null) return "the map quad shader did not compile (see the log)";
            return null;
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    @Override
    public void close() {
        if (palette != null) palette.close();
        ring[slot] = blocks;
        for (int i = 0; i < FRAMES; i++) {
            if (ring[i] != null) ring[i].close();
            ring[i] = null;
            capacities[i] = 0;
            for (GpuBuffer b : retired[i]) b.close();
            retired[i].clear();
        }
        capacity = 0;
        if (blank != null) blank.close();
        blank = null;
        for (Projected p : projections) p.buffer.close();
        projections.clear();
        palette = blocks = null;
        paletteVersion = -1;
    }
}
