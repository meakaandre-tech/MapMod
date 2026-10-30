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
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;

/**
 * Paints the map's tiles into a texture of its own, a paper background and then one draw of {@code core/map_paint} per tile, the way the game paints
 * its picture-in-picture views: an orthographic projection over the texture's pixels (y down, flipped as the game flips
 * it) and the result drawn on screen with v from 1 to 0. Render thread only.
 */
final class PaintRenderer implements AutoCloseable {
    static final RenderPipeline PIPELINE = RenderPipeline.builder()
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
    /** Bytes of the palette block: 8 class colours and 11 more vec4s. */
    private static final int PALETTE_SIZE = (8 + 11) * 16;
    /** Bytes of one tile's block, and the stride between them in the tile buffer (the strictest offset alignment). */
    private static final int TILE_SIZE = 4 * 16, TILE_STRIDE = 256;
    /** The shader's noise repeats every this many samples; tile origins are wrapped to it. */
    static final int NOISE_PERIOD = 2048;
    private static final String[] SAMPLERS = {"Fields", "Surface", "ClassA", "ClassB", "Shore"};
    /** How much the relief is exaggerated for the hillshade. */
    private static final float RELIEF = 2.5f;

    /**
     * One tile to paint: its textures, its corners in the texture's pixels, the texels at those corners, and the world
     * position of its texel (0, 0) in samples, wrapped to {@link #NOISE_PERIOD}.
     */
    record Quad(TileTextures tex, float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float ox, float oz) {
    }

    private @Nullable GpuTexture texture;
    private @Nullable GpuTextureView view;
    private int width, height;
    private final Projection projection = new Projection();
    private final ProjectionMatrixBuffer projectionBuffer = new ProjectionMatrixBuffer(MapMod.ID + " map paint");
    private @Nullable GpuBuffer palette, tileBlocks;
    private int paletteVersion = -1, tileCapacity;
    /** A texture bound to every sampler while the paper background is drawn (it reads none of them). */
    private @Nullable DynamicTexture blank;

    GpuTextureView view() {
        if (view == null) throw new IllegalStateException("nothing painted yet");
        return view;
    }

    /** The texture's v at the top and the bottom of the picture: flipped, as the game draws its own painted views. */
    float v0() {
        return 1f;
    }

    float v1() {
        return 0f;
    }

    /**
     * Paints the quads into a {@code w} by {@code h} picture over a paper background that has the same grain and
     * vignette, so where no tile lies blends with unexplored land inside one. Contours are drawn if asked.
     */
    void paint(int w, int h, PaintPalette pal, int version, List<Quad> quads, boolean contours) {
        GpuDevice device = RenderSystem.getDevice();
        if (texture == null || width != w || height != h) {
            if (view != null) view.close();
            if (texture != null) texture.close();
            texture = device.createTexture(() -> MapMod.ID + " painted map", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, w, h, 1, 1);
            view = device.createTextureView(texture);
            width = w;
            height = h;
        }
        if (palette == null || paletteVersion != version) {
            if (palette != null) palette.close();
            palette = paletteBuffer(device, pal);
            paletteVersion = version;
        }
        if (blank == null) {
            blank = new DynamicTexture(() -> MapMod.ID + " map blank", new NativeImage(1, 1, false));
            blank.upload();
        }
        int draws = quads.size() + 1;
        if (tileBlocks == null || tileCapacity < draws) {
            if (tileBlocks != null) tileBlocks.close();
            tileCapacity = Math.max(16, Integer.highestOneBit(draws - 1) << 1);
            tileBlocks = device.createBuffer(() -> MapMod.ID + " map tiles", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, (long) tileCapacity * TILE_STRIDE);
        }
        CommandEncoder encoder = device.createCommandEncoder();
        // block 0 is the paper background over the whole picture, then one per tile
        ByteBuffer data = ByteBuffer.allocateDirect(draws * TILE_STRIDE).order(ByteOrder.nativeOrder());
        float flag = contours ? 1f : 0f;
        Std140Builder.intoBuffer(data.slice(0, TILE_SIZE).order(ByteOrder.nativeOrder()))
            .putVec4(0, 0, w, h).putVec4(0, 0, 1, 1).putVec4(0, 0, 0, 0).putVec4(w, h, 1f, flag);
        for (int i = 0; i < quads.size(); i++) {
            Quad q = quads.get(i);
            Std140Builder.intoBuffer(data.slice((i + 1) * TILE_STRIDE, TILE_SIZE).order(ByteOrder.nativeOrder()))
                .putVec4(q.x0, q.y0, q.x1, q.y1).putVec4(q.u0, q.v0, q.u1, q.v1).putVec4(q.ox, q.oz, 0, 0).putVec4(w, h, 0f, flag);
        }
        data.position(0);
        encoder.writeToBuffer(tileBlocks.slice(0, (long) draws * TILE_STRIDE), data);
        projection.setupOrtho(-1000f, 1000f, w, h, true);
        int paper = pal.paper();
        Vector4f clear = new Vector4f((paper >> 16 & 255) / 255f, (paper >> 8 & 255) / 255f, (paper & 255) / 255f, 1f);
        GpuSampler smooth = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        // everything that writes to a buffer happens before the pass opens: no other command may run while it is open
        GpuBufferSlice projected = projectionBuffer.getBuffer(projection);
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(PIPELINE);
        try (RenderPass pass = encoder.createRenderPass(() -> MapMod.ID + " painted map", view, Optional.of(clear))) {
            if (compiled == null) return;
            pass.setPipeline(compiled);
            pass.setUniform("Projection", projected);
            pass.setUniform("MapPaint", palette);
            for (int i = 0; i < draws; i++) {
                TileTextures tex = i == 0 ? null : quads.get(i - 1).tex;
                pass.setUniform("MapTile", tileBlocks.slice((long) i * TILE_STRIDE, TILE_SIZE));
                for (int t = 0; t < SAMPLERS.length; t++) {
                    pass.setUniform(SAMPLERS[t], (tex == null ? blank : tex.textures[t]).getTextureView(), smooth);
                }
                pass.draw(6, 1, 0, 0);
            }
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

    /** Compiles the pipeline now: null if it compiled, else what went wrong. */
    static @Nullable String compileCheck() {
        try {
            return RenderSystem.getCompiledPipelineNullable(PIPELINE) == null ? "the map paint shader did not compile (see the log)" : null;
        } catch (RuntimeException e) {
            return e.toString();
        }
    }

    @Override
    public void close() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        if (palette != null) palette.close();
        if (tileBlocks != null) tileBlocks.close();
        if (blank != null) blank.close();
        projectionBuffer.close();
        blank = null;
        view = null;
        texture = null;
        palette = null;
        tileBlocks = null;
    }
}
