package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.MapMod;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Block colours for the map, as Xaero's map makes them: the average colour of the block's top texture (from the
 * resource packs in use, so any mod's blocks and any texture pack work), tinted by the biome for grass, leaves and water
 * the way the game tints them. A block whose texture cannot be found takes the map colour the server sent with it.
 */
public final class BlockColours {
    /** A texture's average colour, by block name; NONE when no texture was found. */
    private static final Map<String, Integer> AVERAGE = new HashMap<>();
    private static final int NONE = -1;
    /** Biomes by name, looked up once per world. */
    private static final Map<String, Optional<Holder.Reference<Biome>>> BIOMES = new HashMap<>();
    /** Endings a block's name may have where its texture is that of the block it is made of (oak_stairs: oak_planks). */
    private static final String[] SHAPES = {"_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_pressure_plate", "_button", "_carpet"};
    /** Leaves with fixed colours of their own (the game does not tint them by biome). */
    private static final int SPRUCE_LEAVES = 0x619961, BIRCH_LEAVES = 0x80A755;

    /** How a block is tinted. */
    private enum Tint { NONE, GRASS, FOLIAGE, WATER }

    private BlockColours() {
    }

    /** Forgets every colour (resource packs or the world changed). */
    public static void clear() {
        AVERAGE.clear();
        BIOMES.clear();
    }

    /** A block's colour on the map in a biome (0xRRGGBB), or {@code fallback} when its texture cannot be found. */
    public static int colour(String block, @Nullable String biome, int fallback) {
        int average = average(block);
        if (average == NONE) return fallback;
        Tint tint = tint(block, average);
        if (tint == Tint.NONE) return average;
        int t = switch (tint) {
            case GRASS -> {
                Biome b = biome(biome);
                yield b == null ? 0x79C05A : b.getGrassColor(0, 0);
            }
            case FOLIAGE -> {
                if (block.endsWith("spruce_leaves")) yield SPRUCE_LEAVES;
                if (block.endsWith("birch_leaves")) yield BIRCH_LEAVES;
                Biome b = biome(biome);
                yield b == null ? 0x59AE30 : b.getFoliageColor();
            }
            case WATER -> {
                Biome b = biome(biome);
                yield b == null ? 0x3F76E4 : b.getWaterColor();
            }
            default -> 0xFFFFFF;
        };
        return multiply(average, t);
    }

    private static int multiply(int a, int b) {
        int r = (a >> 16 & 255) * (b >> 16 & 255) / 255, g = (a >> 8 & 255) * (b >> 8 & 255) / 255, bl = (a & 255) * (b & 255) / 255;
        return r << 16 | g << 8 | bl;
    }

    /**
     * Grass, leaves, vines and water have grey textures the game tints by biome; a block of that kind whose texture is
     * already coloured (cherry leaves, a mod's coloured grass) is left as it is.
     */
    private static Tint tint(String block, int average) {
        String path = block.substring(block.indexOf(':') + 1);
        if (path.equals("water") || path.endsWith("water_cauldron")) return Tint.WATER;
        int r = average >> 16 & 255, g = average >> 8 & 255, b = average & 255;
        boolean grey = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 24;
        if (!grey) return Tint.NONE;
        if (path.endsWith("leaves") || path.equals("vine")) return Tint.FOLIAGE;
        if (path.equals("grass_block") || path.endsWith("grass") || path.contains("fern") || path.equals("sugar_cane") || path.equals("lily_pad")) {
            return Tint.GRASS;
        }
        return Tint.NONE;
    }

    private static @Nullable Biome biome(@Nullable String name) {
        if (name == null || name.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Optional<Holder.Reference<Biome>> b = BIOMES.computeIfAbsent(name, n -> {
            Identifier id = Identifier.tryParse(n);
            if (id == null) return Optional.empty();
            return mc.level.registryAccess().get(ResourceKey.create(Registries.BIOME, id));
        });
        return b.map(Holder::value).orElse(null);
    }

    // ------------------------------------------------------------------------------------------------ textures

    private static int average(String block) {
        Integer known = AVERAGE.get(block);
        if (known != null) return known;
        int found = NONE;
        Identifier id = Identifier.tryParse(block);
        if (id != null) {
            for (String path : candidates(id.getPath())) {
                found = textureAverage(Identifier.fromNamespaceAndPath(id.getNamespace(), "textures/block/" + path + ".png"));
                if (found != NONE) break;
            }
        }
        AVERAGE.put(block, found);
        return found;
    }

    /** Texture names a block's top may have, likeliest first. */
    private static String[] candidates(String path) {
        if (path.equals("water")) return new String[]{"water_still"};
        if (path.equals("lava")) return new String[]{"lava_still"};
        String base = path;
        for (String shape : SHAPES) {
            if (path.endsWith(shape)) {
                base = path.substring(0, path.length() - shape.length());
                break;
            }
        }
        if (!base.equals(path)) {
            // oak_stairs: oak_planks; stone_brick_slab: stone_bricks; cobblestone_wall: cobblestone
            return new String[]{base + "_planks", base + "s", base, base + "_top", base + "_block"};
        }
        String noBlock = path.endsWith("_block") ? path.substring(0, path.length() - "_block".length()) : path;
        return new String[]{path + "_top", path, noBlock + "_top", noBlock, path + "_side"};
    }

    /** The average colour of a texture's opaque pixels (of its first frame), or NONE. */
    private static int textureAverage(Identifier texture) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
        if (resource.isEmpty()) return NONE;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth(), h = Math.min(image.getHeight(), w);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = image.getPixel(x, y);
                    int a = c >>> 24;
                    if (a < 128) continue;
                    r += c >> 16 & 255;
                    g += c >> 8 & 255;
                    b += c & 255;
                    n++;
                }
            }
            if (n == 0) return NONE;
            return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        } catch (IOException | RuntimeException e) {
            MapMod.LOGGER.debug("unreadable texture {}", texture, e);
            return NONE;
        }
    }
}
