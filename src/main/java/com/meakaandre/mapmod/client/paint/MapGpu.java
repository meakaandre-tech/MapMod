package com.meakaandre.mapmod.client.paint;

import com.meakaandre.mapmod.MapMod;
import com.mojang.blaze3d.buffers.Std140Builder;
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
 * picture with v running from 1 at the top to 0 at the bottom, and is drawn on with that. Three kinds of pass:
 * <ul>
 *   <li>{@link #paint}: {@code core/map_paint}, the full painted shader, one draw per tile (a bake, or close zoom);</li>
 *   <li>{@link #quads}: {@code core/map_quad} copying textures with linear filtering (baked tiles and mips on screen,
 *   and a mip's four children into its quarters);</li>
 *   <li>{@link #finish}: {@code core/map_quad} once over the whole picture, adding grain and the vignette.</li>
 * </ul>
 * Render thread only.
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
            .withUniform("ClassA", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("ClassB", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Shore", UniformType.COMBINED_IMAGE_SAMPLER)
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
            .build())
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .build();
    /** Bytes of the palette block: 8 class colours and 11 more vec4s. */
    private static final int PALETTE_SIZE = (8 + 11) * 16;
    /** Bytes of one draw's block, and the stride between them (the strictest offset alignment). */
    private static final int BLOCK_SIZE = 4 * 16, BLOCK_STRIDE = 256;
    /** The shader's noise repeats every this many samples; tile origins are wrapped to it. */
    static final int NOISE_PERIOD = 2048;
    private static final String[] SAMPLERS = {"Fields", "Surface", "ClassA", "ClassB", "Shore"};
    /** How much the relief is exaggerated for the hillshade. */
    private static final float RELIEF = 2.5f;

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
     * position of its texel (0, 0) in samples, wrapped to {@link #NOISE_PERIOD}.
     */
    record PaintQuad(TileTextures tex, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float ox, float oz) {
    }

    /** One texture to copy: its corners in the target's pixels and its texture coordinates there (v up, see above). */
    record TexQuad(GpuTextureView src, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
    }

    /** One projection per target size used: a tile's 512 and the screen's. */
    private final List<Projected> projections = new ArrayList<>();
    private @Nullable GpuBuffer palette, blocks;
    private int paletteVersion = -1, capacity, cursor;
    /** Draw buffers outgrown this frame, let go at the start of the next (their draws may still be in flight). */
    private final List<GpuBuffer> retired = new ArrayList<>();
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
        cursor = 0;
        passes = draws = 0;
        for (GpuBuffer b : retired) b.close();
        retired.clear();
        reserve(expected);
    }

    private void reserve(int n) {
        if (blocks != null && capacity >= cursor + n) return;
        // a buffer already drawn from this frame stays as it is until the next: the next draws go to a new, bigger one
        if (blocks != null) retired.add(blocks);
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
        ByteBuffer data = ByteBuffer.allocateDirect(n * BLOCK_STRIDE).order(ByteOrder.nativeOrder());
        for (int i = 0; i < n; i++) fill.write(Std140Builder.intoBuffer(data.slice(i * BLOCK_STRIDE, BLOCK_SIZE).order(ByteOrder.nativeOrder())), i);
        data.position(0);
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

    /** Paints tiles into the target with the full shader; {@code clear} as {@link #clear}. */
    void paint(Target t, Optional<Vector4fc> clear, List<PaintQuad> quads, PaintPalette pal, int version, boolean contours) {
        if (quads.isEmpty() && clear.isEmpty()) return;
        GpuDevice device = RenderSystem.getDevice();
        if (palette == null || paletteVersion != version) {
            if (palette != null) palette.close();
            palette = paletteBuffer(device, pal);
            paletteVersion = version;
        }
        CommandEncoder encoder = device.createCommandEncoder();
        float flag = contours ? 1f : 0f;
        int first = quads.isEmpty() ? 0 : write(encoder, quads.size(), (b, i) -> {
            PaintQuad q = quads.get(i);
            b.putVec4(q.x0, q.y0, q.x1, q.y1).putVec4(q.u0, q.v0, q.u1, q.v1).putVec4(q.ox, q.oz, 0, 0).putVec4(t.width, t.height, 0f, flag);
        });
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
                TileTextures tex = quads.get(i).tex;
                pass.setUniform("MapTile", block(first + i));
                for (int s = 0; s < SAMPLERS.length; s++) pass.setUniform(SAMPLERS[s], tex.textures[s].getTextureView(), smooth);
                pass.draw(6, 1, 0, 0);
                draws++;
            }
        }
    }

    /** Copies textures into the target with linear filtering; {@code clear} as {@link #clear}. */
    void quads(Target t, Optional<Vector4fc> clear, List<TexQuad> quads) {
        if (quads.isEmpty() && clear.isEmpty()) return;
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        int first = quads.isEmpty() ? 0 : write(encoder, quads.size(), (b, i) -> {
            TexQuad q = quads.get(i);
            b.putVec4(q.x0, q.y0, q.x1, q.y1).putVec4(q.u0, q.v0, q.u1, q.v1).putVec4(0, 0, 0, 0).putVec4(t.width, t.height, 0, 0);
        });
        GpuSampler smooth = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuBufferSlice projection = projected(t.width, t.height);
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(QUAD);
        passes++;
        try (RenderPass pass = encoder.createRenderPass(() -> MapMod.ID + " map quads", t.view, clear)) {
            if (compiled == null || quads.isEmpty()) return;
            pass.setPipeline(compiled);
            pass.setUniform("Projection", projection);
            for (int i = 0; i < quads.size(); i++) {
                pass.setUniform("MapQuad", block(first + i));
                pass.setUniform("Src", quads.get(i).src, smooth);
                pass.draw(6, 1, 0, 0);
                draws++;
            }
        }
    }

    /** Grain and the vignette over {@code src}, into {@code dst} of the same size. */
    void finish(Target src, Target dst, PaintPalette pal) {
        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        int w = dst.width, h = dst.height;
        int first = write(encoder, 1, (b, i) -> b.putVec4(0, 0, w, h).putVec4(0, 1, 1, 0).putVec4(1f, pal.grain(), pal.vignette(), 0)
            .putVec4(w, h, 0, 0));
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuBufferSlice projection = projected(w, h);
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(QUAD);
        passes++;
        try (RenderPass pass = encoder.createRenderPass(() -> MapMod.ID + " map finish", dst.view, Optional.empty())) {
            if (compiled == null) return;
            pass.setPipeline(compiled);
            pass.setUniform("Projection", projection);
            pass.setUniform("MapQuad", block(first));
            pass.setUniform("Src", src.view, nearest);
            pass.draw(6, 1, 0, 0);
            draws++;
        }
    }

    private static GpuBuffer paletteBuffer(GpuDevice device, PaintPalette p) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder b = Std140Builder.onStack(stack, PALETTE_SIZE);
            for (int c = 0; c < 8; c++) colour(b, p.classColour()[c], p.classTint()[c]);
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
        if (blocks != null) blocks.close();
        for (GpuBuffer b : retired) b.close();
        retired.clear();
        for (Projected p : projections) p.buffer.close();
        projections.clear();
        palette = blocks = null;
        paletteVersion = -1;
    }
}
