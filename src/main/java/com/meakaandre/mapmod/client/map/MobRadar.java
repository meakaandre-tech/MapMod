package com.meakaandre.mapmod.client.map;

import com.meakaandre.mapmod.MapMod;
import com.meakaandre.mapmod.client.paint.PaintMap;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.AgeableMobRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * The mob radar: the mobs round the player on the fullscreen map, each drawn as its own face (read off
 * its model's head and its texture, so it works for any mob a mod adds too), with no names, in a soft frame (MM-5). A
 * mob on land the group has not explored is hidden under the fog. Hostile and passive mobs each have a filter switch
 * (under "Mobs"), and the radar a layer switch of its own in the top bar.
 */
public final class MobRadar {
    /** Size a face is drawn at on the fullscreen map (pixels). */
    private static final int SIZE = 9;
    /** Blocks above or below the player a mob may be and still show. */
    private static final int HEIGHT_RANGE = 24;
    /** A mob's texture is looked up again this often (ticks): it may change (a sheared sheep, a converting zombie). */
    private static final int TEXTURE_TICKS = 40;
    /** A soft frame round each face, so it stands out on any ground: a dark ring a pixel out, a faint one past it. */
    private static final int OUTLINE = 0x90101418, HALO = 0x30101418;
    /** A mob whose face cannot be found: a dot in its kind's colour. */
    private static final int HOSTILE_DOT = 0xFFE5533D, PASSIVE_DOT = 0xFF6BCB5A;

    /** Where a face is on a texture, as fractions of the texture. */
    private record Face(float u0, float v0, float u1, float v1) {
    }

    /** A mob's texture, and the tick it was looked up. */
    private record Skin(@Nullable Identifier texture, long at) {
    }

    private static final Map<String, Face> FACES = new HashMap<>();
    /** Model and entity kinds known to have no face to read. */
    private static final Map<String, Boolean> FACELESS = new HashMap<>();
    private static final Map<Integer, Skin> SKINS = new HashMap<>();
    private static @Nullable Field adultModel, babyModel;
    private static boolean fieldsLooked;

    private MobRadar() {
    }

    public static boolean hostile(Entity e) {
        return e instanceof Enemy;
    }

    /** Draws the mobs in a view, as the filters allow. */
    public static void draw(GuiGraphicsExtractor g, MapDraw.View v) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !MapClient.mappedHere()) return;
        if (!MapFilters.on(MapFilters.MOBS) || !MapLayer.MOBS.on()) return;
        boolean hostiles = MapFilters.on(MapFilters.HOSTILE), passives = MapFilters.on(MapFilters.PASSIVE);
        if (!hostiles && !passives) return;
        int size = SIZE;
        long now = mc.level.getGameTime();
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity living) || e instanceof Player || e.isRemoved() || e.isInvisible() || !living.isAlive()) continue;
            // mobs far above or below (in caves under the player) are left off, as on the surface map they are not
            if (Math.abs(e.getY() - mc.player.getY()) > HEIGHT_RANGE) continue;
            boolean hostile = hostile(e);
            if (hostile ? !hostiles : !passives) continue;
            double x = e.xo + (e.getX() - e.xo) * pt, z = e.zo + (e.getZ() - e.zo) * pt;
            // under the fog: where the map has nothing captured, or (where the group's fog decides) the group has not explored
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (PaintMap.height(bx, bz) == null || (!MapClient.fogFromCapture() && !MapClient.explored(bx, bz))) continue;
            double sx = v.sx(x), sy = v.sy(z);
            if (!v.inside(sx, sy, size)) continue;
            int x0 = (int) Math.round(sx) - size / 2, y0 = (int) Math.round(sy) - size / 2;
            face(g, mc, living, x0, y0, size, hostile, now);
        }
        // forget the textures of mobs long gone
        if (now % (TEXTURE_TICKS * 10L) == 0) SKINS.values().removeIf(s -> now - s.at > TEXTURE_TICKS * 10L);
    }

    private static void face(GuiGraphicsExtractor g, Minecraft mc, LivingEntity e, int x0, int y0, int size, boolean hostile, long now) {
        Skin skin = SKINS.get(e.getId());
        if (skin == null || now - skin.at >= TEXTURE_TICKS || now < skin.at) {
            skin = new Skin(texture(mc, e), now);
            SKINS.put(e.getId(), skin);
        }
        Face face = skin.texture == null ? null : face(mc, e);
        // rounded: the faint ring leaves its corners out
        g.fill(x0 - 2, y0 - 1, x0 + size + 2, y0 + size + 1, HALO);
        g.fill(x0 - 1, y0 - 2, x0 + size + 1, y0 + size + 2, HALO);
        g.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, OUTLINE);
        if (face == null) {
            g.fill(x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1, hostile ? HOSTILE_DOT : PASSIVE_DOT);
            return;
        }
        g.blit(skin.texture, x0, y0, x0 + size, y0 + size, face.u0, face.u1, face.v0, face.v1);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static @Nullable Identifier texture(Minecraft mc, LivingEntity e) {
        try {
            EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(e);
            if (!(renderer instanceof LivingEntityRenderer living)) return null;
            if (!(renderer.createRenderState(e, 1f) instanceof LivingEntityRenderState state)) return null;
            return living.getTextureLocation(state);
        } catch (RuntimeException ex) {
            MapMod.LOGGER.debug("no map face for {}", e.getType(), ex);
            return null;
        }
    }

    /** The front of the mob's head on its texture: read off the head of its model (adult or baby, as it is). */
    @SuppressWarnings("rawtypes")
    private static @Nullable Face face(Minecraft mc, LivingEntity e) {
        String key = EntityType.getKey(e.getType()) + (e.isBaby() ? "/baby" : "");
        Face known = FACES.get(key);
        if (known != null || FACELESS.containsKey(key)) return known;
        Face found = null;
        try {
            EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(e);
            if (renderer instanceof LivingEntityRenderer living) {
                EntityModel<?> model = model(living, e.isBaby());
                if (model != null) found = front(model.root());
            }
        } catch (RuntimeException ex) {
            MapMod.LOGGER.debug("no map face for {}", e.getType(), ex);
        }
        if (found == null) FACELESS.put(key, true);
        else FACES.put(key, found);
        return found;
    }

    /** The renderer's model for an adult or a baby (an ageable mob's renderer keeps one of each). */
    @SuppressWarnings("rawtypes")
    private static @Nullable EntityModel<?> model(LivingEntityRenderer renderer, boolean baby) {
        if (renderer instanceof AgeableMobRenderer) {
            if (!fieldsLooked) {
                fieldsLooked = true;
                try {
                    adultModel = AgeableMobRenderer.class.getDeclaredField("adultModel");
                    babyModel = AgeableMobRenderer.class.getDeclaredField("babyModel");
                    adultModel.setAccessible(true);
                    babyModel.setAccessible(true);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    adultModel = babyModel = null;
                }
            }
            Field f = baby ? babyModel : adultModel;
            if (f != null) {
                try {
                    Object m = f.get(renderer);
                    if (m instanceof EntityModel<?> model) return model;
                } catch (ReflectiveOperationException ex) {
                    MapMod.LOGGER.debug("no ageable model", ex);
                }
            }
        }
        return renderer.getModel() instanceof EntityModel<?> model ? model : null;
    }

    /**
     * The front face of a model's head: the cube of its "head" part (or, for a mob without one, its biggest cube), and on
     * it the face that looks forward. Its texture coordinates are already fractions of the texture.
     */
    private static @Nullable Face front(ModelPart root) {
        ModelPart.Cube[] head = {null};
        ModelPart.Cube[] biggest = {null};
        float[] most = {0};
        root.visit(new PoseStack(), (pose, path, index, cube) -> {
            boolean isHead = path.equals("head") || path.endsWith("/head");
            if (isHead && index == 0 && head[0] == null) head[0] = cube;
            float volume = (cube.maxX - cube.minX) * (cube.maxY - cube.minY) * (cube.maxZ - cube.minZ);
            if (volume > most[0]) {
                most[0] = volume;
                biggest[0] = cube;
            }
        });
        ModelPart.Cube cube = head[0] != null ? head[0] : biggest[0];
        if (cube == null) return null;
        ModelPart.Polygon front = null;
        for (ModelPart.Polygon p : cube.polygons) {
            if (p.normal().z() < -0.5f) front = p;
        }
        if (front == null || front.vertices().length == 0) return null;
        float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (ModelPart.Vertex vert : front.vertices()) {
            u0 = Math.min(u0, vert.u());
            v0 = Math.min(v0, vert.v());
            u1 = Math.max(u1, vert.u());
            v1 = Math.max(v1, vert.v());
        }
        if (u1 <= u0 || v1 <= v0) return null;
        return new Face(u0, v0, u1, v1);
    }

    /** For the selftests: whether a mob's face was read off its model. */
    public static boolean hasFace(LivingEntity e) {
        return face(Minecraft.getInstance(), e) != null && texture(Minecraft.getInstance(), e) != null;
    }
}
