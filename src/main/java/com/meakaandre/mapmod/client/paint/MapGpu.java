package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The map's GPU work (MM-4; MM-9 one kind of pass), on render targets of its own the way the game paints its
 * picture-in-picture views: an orthographic projection over the target's pixels (y down, flipped as the game flips it),
 * so every target holds its picture with v running from 1 at the top to 0 at the bottom, and is drawn on with that.
 * Every pass is {@link #quads}: {@code core/map_quad} drawing textures (a tile's picture into its baked texture texel for
 * texel, a mip's four children into its quarters, baked tiles and mips on screen) or the background. On screen the
 * pixels are composited over the flat background as they are written: one pass over the screen's pixels with no picture
 * read back in the same frame (on a software rasterizer a read-back stalls the render thread until the earlier pass is
 * drawn). Render thread only.
 */
final class MapGpu implements AutoCloseable {
    static final RenderPipeline QUAD = RenderPipeline.builder()
        .withLocation(MapMod.id("pipeline/map_quad"))
        .withVertexShader(MapMod.id("core/map_quad"))
        .withFragmentShader(MapMod.id("core/map_quad"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayout.builder()
            .withUniform("MapQuad", UniformType.UNIFORM_BUFFER)
            .withUniform("Src", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Src2", UniformType.COMBINED_IMAGE_SAMPLER)
            .build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .build();
    /** Bytes of one draw's block (5 vec4s), and the stride between them (the strictest offset alignment). */
    private static final int BLOCK_SIZE = 5 * 16, BLOCK_STRIDE = 256;
    /** A clear to transparent black: a baked texture where no land is. */
    static final Optional<Vector4fc> TRANSPARENT = Optional.of(new Vector4f(0, 0, 0, 0));

    /** A texture to draw into and read from: its view, its size, and the projection over its pixels. */
    static final class Target implements AutoCloseable {
        final GpuTexture texture;
        final GpuTextureView view;
        final int width, height;

        Target(String name, int width, int height) {
            GpuDevice device = RenderSystem.getDevice();
            // copy source too: the selftests read a baked tile or mip back (MM-8d)
            texture = device.createTexture(() -> MapMod.ID + " " + name, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
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
     * One texture to draw: its corners in the target's pixels and its texture coordinates there (a target's v is up, see
     * above; a tile's picture has v 0 at its north edge). A null source lays the background (transparent in a texture).
     * On screen it can also blend a second source over it ({@code fade} of it: the cross-fade between mip levels), and
     * is read nearest ({@code nearest}: close up, crisp blocks) or with linear filtering.
     */
    record TexQuad(@Nullable GpuTextureView src, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1,
                   @Nullable GpuTextureView src2, float s0, float t0, float s1, float t1, float fade, boolean nearest) {
        TexQuad(@Nullable GpuTextureView src, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
            this(src, x0, y0, x1, y1, u0, v0, u1, v1, null, 0, 0, 0, 0, 0, false);
        }

        /** The background over the whole of a target. */
        static TexQuad background(Target t) {
            return new TexQuad(null, 0, 0, t.width, t.height, 0, 1, 1, 0);
        }

        TexQuad withFade(@Nullable GpuTextureView from, float s0, float t0, float s1, float t1, float fade) {
            return new TexQuad(src, x0, y0, x1, y1, u0, v0, u1, v1, from, s0, t0, s1, t1, fade, nearest);
        }

        TexQuad nearest(boolean on) {
            return new TexQuad(src, x0, y0, x1, y1, u0, v0, u1, v1, src2, s0, t0, s1, t1, fade, on);
        }
    }

    /**
     * For the selftests (MM-8d): reads a target's pixels back from the GPU, as ARGB, row by row as the texture holds
     * them. The copy runs with the frame's other commands, so the result arrives a frame or so later.
     */
    static java.util.concurrent.CompletableFuture<int[]> readBack(Target t) {
        GpuDevice device = RenderSystem.getDevice();
        int n = t.width * t.height;
        GpuBuffer buffer = device.createBuffer(() -> MapMod.ID + " map read-back", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) n * 4);
        java.util.concurrent.CompletableFuture<int[]> out = new java.util.concurrent.CompletableFuture<>();
        device.createCommandEncoder().copyTextureToBuffer(t.texture, buffer, 0, () -> {
            try (GpuBufferSlice.MappedView v = buffer.map(true, false)) {
                ByteBuffer d = v.data();
                int[] px = new int[n];
                for (int i = 0; i < n; i++) {
                    int r = d.get(i * 4) & 255, g = d.get(i * 4 + 1) & 255, b = d.get(i * 4 + 2) & 255, a = d.get(i * 4 + 3) & 255;
                    px[i] = a << 24 | r << 16 | g << 8 | b;
                }
                out.complete(px);
            } catch (RuntimeException e) {
                out.completeExceptionally(e);
            } finally {
                buffer.close();
            }
        }, 0);
        return out;
    }

    /** One projection per target size used: a tile's 512 and the screen's. */
    private final List<Projected> projections = new ArrayList<>();
    /**
     * Frames whose draw buffers are kept apart: a frame never writes a buffer the last few frames' draws read, which
     * would make the driver wait for them.
     */
    static final int FRAMES = 3;
    private @Nullable GpuBuffer blocks;
    private final @Nullable GpuBuffer[] ring = new GpuBuffer[FRAMES];
    private final int[] capacities = new int[FRAMES];
    private int capacity, cursor, slot;
    /** Where draw blocks are put together before they are written (reused: allocating one each pass costs). */
    private @Nullable ByteBuffer scratch;
    /** Bound as the source of a background quad, which reads none. */
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

    /** A 1x1 texture of zeros: bound where a quad has no second source. */
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
     * Draws textures (or lays the background) into the target; {@code clear} to clear it first ({@link #TRANSPARENT}
     * for a baked texture), or empty to draw over what it holds. With a palette ({@code screen}) every pixel is laid over
     * its background; without, texels are copied as they are, with linear filtering, or nearest where a quad says so (a
     * tile's picture texel for texel, a mip's child made again from it).
     */
    void quads(Target t, Optional<Vector4fc> clear, List<TexQuad> quads, @Nullable PaintPalette screen) {
        if (quads.isEmpty() && clear.isEmpty()) return;
        GpuDevice device = RenderSystem.getDevice();
        GpuTextureView none = blank();
        CommandEncoder encoder = device.createCommandEncoder();
        int background = screen == null ? 0 : screen.background();
        float onScreen = screen == null ? 0 : 1;
        int first = quads.isEmpty() ? 0 : write(encoder, quads.size(), (b, i) -> {
            TexQuad q = quads.get(i);
            float mode = q.src == null ? 2 : onScreen;
            float fade = q.src2 == null ? 0 : q.fade;
            b.putVec4(q.x0, q.y0, q.x1, q.y1).putVec4(q.u0, q.v0, q.u1, q.v1).putVec4(mode, 0, 0, fade);
            colour(b, background, onScreen);
            b.putVec4(q.s0, q.t0, q.s1, q.t1);
        });
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
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
                GpuSampler sampler = q.nearest ? nearest : linear;
                pass.setUniform("Src", q.src == null ? none : q.src, sampler);
                pass.setUniform("Src2", q.src2 == null ? none : q.src2, sampler);
                pass.draw(6, 1, 0, 0);
                draws++;
            }
        }
    }

    private static void colour(Std140Builder b, int rgb, float w) {
        b.putVec4((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, w);
    }

    /** Compiles the pipeline now: null if it compiled, else what went wrong. */
    static @Nullable String compileCheck() {
        try {
            if (RenderSystem.getCompiledPipelineNullable(QUAD) == null) return "the map quad shader did not compile (see the log)";
            return null;
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    @Override
    public void close() {
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
        blocks = null;
    }
}
