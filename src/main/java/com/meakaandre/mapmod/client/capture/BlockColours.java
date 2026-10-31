package com.meakaandre.mapmod.client.capture;

import com.meakaandre.mapmod.MapMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The colour a block shows from above (MM-8): the average colour of its top face's texture, as the loaded resource packs
 * give it, times its tint. Works for any block of any mod with no code of its own: the top face is read off the block's
 * baked model (the quads facing up), the texture from the resources (so a resource pack that changes a texture changes
 * the map), and the tint from the game's block colours: a block tinted with the grass or foliage colour takes its
 * biome's, water takes its biome's water colour, any other tint is taken as it is.
 * <p>
 * Each block state is worked out once and cached (averaging a texture costs a little; a state is met again and again);
 * the cache starts again whenever the game's block models are swapped, which is every resource reload (F3+T, a pack
 * switched on or off), and the capture then samples the loaded chunks again ({@link Capture}). A block with no up face
 * (a fluid, a plant) falls back on its fluid's texture or its particle texture, and anything that cannot be read on its
 * map colour. Safe from any thread.
 */
public final class BlockColours {
    /** How a look is tinted. */
    static final byte NONE = 0, GRASS = 1, FOLIAGE = 2, WATER = 3, FIXED = 4;

    /**
     * A block state's colour before the biome: the texture's average (0xRRGGBB), how it is tinted, a fixed tint's colour,
     * and the texture it came from (for {@code /mapmod probe}).
     */
    public record Look(int average, byte tint, int fixed, String texture) {
        int colour(@Nullable Holder<Biome> biome, int x, int z) {
            int t = switch (tint) {
                case GRASS -> biome == null ? GrassColor.getDefaultColor() : biome.value().getGrassColor(x, z);
                case FOLIAGE -> biome == null ? FoliageColor.FOLIAGE_DEFAULT : biome.value().getFoliageColor();
                case WATER -> biome == null ? 0x3F76E4 : biome.value().getWaterColor();
                case FIXED -> fixed;
                default -> 0xFFFFFF;
            };
            return multiply(average, t);
        }

        /** What tints it, for the probe. */
        public String tintName() {
            return switch (tint) {
                case GRASS -> "biome grass";
                case FOLIAGE -> "biome foliage";
                case WATER -> "biome water";
                case FIXED -> String.format("#%06X", fixed & 0xFFFFFF);
                default -> "none";
            };
        }
    }

    private static final Map<BlockState, Look> CACHE = new ConcurrentHashMap<>();
    /** Texture averages by sprite name: blocks share textures. */
    private static final Map<Identifier, Integer> AVERAGES = new ConcurrentHashMap<>();
    /** The block models the cache was filled from: when the game swaps them (a reload), the cache starts again. */
    private static volatile @Nullable Object models;
    /** Times the cache started again for new models (for the selftests and the capture). */
    private static volatile int generation;

    private BlockColours() {
    }

    /** The colour a block shows from above at a position, its biome's tint applied. */
    static int colour(BlockState state, @Nullable Holder<Biome> biome, int x, int z) {
        return look(state).colour(biome, x, z);
    }

    /** A block state's look, worked out once per set of block models. */
    public static Look look(BlockState state) {
        check();
        Look l = CACHE.get(state);
        if (l != null) return l;
        l = compute(state);
        CACHE.put(state, l);
        return l;
    }

    /** The cache's generation: it moves on every time the game's block models are swapped. */
    public static int generation() {
        check();
        return generation;
    }

    /** Starts the cache again if the game's block models are not the ones it was filled from. */
    private static void check() {
        Object now = currentModels();
        if (now == models) return;
        synchronized (BlockColours.class) {
            if (now == models) return;
            CACHE.clear();
            AVERAGES.clear();
            models = now;
            generation++;
        }
    }

    private static @Nullable Object currentModels() {
        try {
            return Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Look compute(BlockState state) {
        try {
            FluidState fluid = state.getFluidState();
            Minecraft mc = Minecraft.getInstance();
            if (!fluid.isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                FluidModel fm = mc.getModelManager().getFluidStateModelSet().get(fluid);
                TextureAtlasSprite sprite = fm.stillMaterial().sprite();
                Integer avg = average(sprite);
                if (avg != null) {
                    byte tint = fluid.is(FluidTags.WATER) ? WATER : NONE;
                    int fixed = 0xFFFFFF;
                    if (tint == NONE && fm.tintSource() != null) {
                        fixed = fm.tintSource().color(state) & 0xFFFFFF;
                        if (fixed != 0xFFFFFF) tint = FIXED;
                    }
                    return new Look(avg, tint, fixed, sprite.contents().name().toString());
                }
            }
            BlockStateModelSet set = mc.getModelManager().getBlockStateModelSet();
            BlockStateModel model = set.get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            long r = 0, g = 0, b = 0, n = 0;
            int tintIndex = -1;
            String texture = "";
            for (BlockStateModelPart part : parts) {
                List<BakedQuad> quads = new ArrayList<>(part.getQuads(Direction.UP));
                for (BakedQuad q : part.getQuads(null)) if (q.direction() == Direction.UP) quads.add(q);
                for (BakedQuad q : quads) {
                    TextureAtlasSprite sprite = q.materialInfo().sprite();
                    Integer avg = average(sprite);
                    if (avg == null) continue;
                    r += avg >> 16 & 255;
                    g += avg >> 8 & 255;
                    b += avg & 255;
                    n++;
                    if (texture.isEmpty()) texture = sprite.contents().name().toString();
                    if (tintIndex < 0 && q.materialInfo().isTinted()) tintIndex = q.materialInfo().tintIndex();
                }
            }
            if (n == 0) {
                // no face up (a cross plant, an entity-drawn block): its particle texture
                TextureAtlasSprite sprite = model.particleMaterial().sprite();
                Integer avg = average(sprite);
                if (avg != null) return tinted(state, avg, -1, sprite.contents().name().toString());
                return mapColour(state);
            }
            int avg = (int) ((r + n / 2) / n) << 16 | (int) ((g + n / 2) / n) << 8 | (int) ((b + n / 2) / n);
            return tinted(state, avg, tintIndex, texture);
        } catch (RuntimeException e) {
            MapMod.LOGGER.debug("no colour from the model of {}: {}", state, e.toString());
            return mapColour(state);
        }
    }

    /** A look with the tint the game gives that layer of the block, read as the biome's where it is one of its defaults. */
    private static Look tinted(BlockState state, int average, int tintIndex, String texture) {
        if (tintIndex < 0) return new Look(average, NONE, 0xFFFFFF, texture);
        BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
        if (source == null) return new Look(average, NONE, 0xFFFFFF, texture);
        int c = source.color(state) & 0xFFFFFF;
        if (c == (GrassColor.getDefaultColor() & 0xFFFFFF)) return new Look(average, GRASS, c, texture);
        if (c == (FoliageColor.FOLIAGE_DEFAULT & 0xFFFFFF)) return new Look(average, FOLIAGE, c, texture);
        if (c == 0xFFFFFF) return new Look(average, NONE, c, texture);
        return new Look(average, FIXED, c, texture);
    }

    /** The block's map colour, for anything whose texture cannot be read. */
    private static Look mapColour(BlockState state) {
        int c;
        try {
            c = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col & 0xFFFFFF;
        } catch (RuntimeException e) {
            c = 0x808080;
        }
        return new Look(c, NONE, 0xFFFFFF, "map colour");
    }

    /**
     * The average colour of a sprite's first frame, weighted by alpha (so the holes in leaves do not darken them),
     * read from the resources as they are loaded now; null if it cannot be read.
     */
    static @Nullable Integer average(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        Identifier name = contents.name();
        Integer cached = AVERAGES.get(name);
        if (cached != null) return cached;
        Integer avg = readAverage(Minecraft.getInstance().getResourceManager(), name, contents.width(), contents.height());
        if (avg != null) AVERAGES.put(name, avg);
        return avg;
    }

    /** The alpha-weighted average of the first {@code w} x {@code h} frame of {@code textures/<name>.png}. */
    static @Nullable Integer readAverage(ResourceManager resources, Identifier name, int w, int h) {
        Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
        Optional<Resource> res = resources.getResource(file);
        if (res.isEmpty()) return null;
        try (InputStream in = res.get().open(); NativeImage image = NativeImage.read(in)) {
            int fw = Math.min(w, image.getWidth()), fh = Math.min(h, image.getHeight());
            if (fw <= 0 || fh <= 0) return null;
            double r = 0, g = 0, b = 0, a = 0;
            for (int y = 0; y < fh; y++) {
                for (int x = 0; x < fw; x++) {
                    int p = image.getPixel(x, y);
                    double al = (p >>> 24) / 255.0;
                    r += (p >> 16 & 255) * al;
                    g += (p >> 8 & 255) * al;
                    b += (p & 255) * al;
                    a += al;
                }
            }
            if (a <= 0) return null;
            return (int) Math.round(r / a) << 16 | (int) Math.round(g / a) << 8 | (int) Math.round(b / a);
        } catch (Exception e) {
            return null;
        }
    }

    /** Two colours multiplied channel by channel, 0xRRGGBB. */
    static int multiply(int a, int b) {
        int r = (a >> 16 & 255) * (b >> 16 & 255) / 255, g = (a >> 8 & 255) * (b >> 8 & 255) / 255, bl = (a & 255) * (b & 255) / 255;
        return r << 16 | g << 8 | bl;
    }
}
