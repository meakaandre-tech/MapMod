package com.meakaandre.mapmod.client.map;

import java.util.Arrays;

/**
 * The 3D map's model, in plain Java (no game classes, so it can be tried out on its own): a camera, the terrain mesh
 * and the rasteriser that draws it into pixels, a triangle at a time with a depth buffer, colours and light blended
 * across each triangle, and the world position each pixel shows kept for clicks.
 */
public final class MapModel {
    /** The camera's field of view (vertical, degrees). */
    static final double FOV = 40;
    /** Heights are drawn this much taller than they are, so hills read from above. */
    static final float EXAGGERATION = 1.25f;
    /** Mesh cells along one side of the area drawn at most. */
    static final int MAX_CELLS = 256;
    /** Blocks either way of the target the model reaches at most. */
    static final double MAX_REACH = 2000;
    /** The background beyond the model. */
    static final int BACK = 0x141A26;

    private MapModel() {
    }

    /**
     * A camera: looking at world (cx, cy, cz) from {@code dist} away, turned {@code yaw} degrees clockwise from north
     * and tilted {@code pitch} degrees down from the horizon, for a screen rectangle; {@code focal} is the screen
     * pixels a block at one block's distance.
     */
    public record Cam(double cx, double cy, double cz, double yaw, double pitch, double dist, double focal, int left, int top, int width, int height) {
        double fx() {
            return Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
        }

        double fy() {
            return -Math.sin(Math.toRadians(pitch));
        }

        double fz() {
            return -Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
        }

        /** Screen x and y of a world point; NaN when it is behind the camera. */
        public double[] project(double x, double y, double z) {
            double fx = fx(), fy = fy(), fz = fz();
            double rx = Math.cos(Math.toRadians(yaw)), rz = Math.sin(Math.toRadians(yaw));
            // up = right x forward
            double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
            double px = x - (cx - fx * dist), py = (y - cy) * EXAGGERATION + cy - (cy - fy * dist), pz = z - (cz - fz * dist);
            double depth = px * fx + py * fy + pz * fz;
            if (depth < 1) return new double[]{Double.NaN, Double.NaN};
            double sx = px * rx + pz * rz, sy = px * ux + py * uy + pz * uz;
            return new double[]{left + width / 2.0 + focal * sx / depth, top + height / 2.0 - focal * sy / depth};
        }

        /** Where a ray through a screen point meets the level of the camera's target (for points off the model). */
        public double[] ground(double sx, double sy) {
            double fx = fx(), fy = fy(), fz = fz();
            double rx = Math.cos(Math.toRadians(yaw)), rz = Math.sin(Math.toRadians(yaw));
            double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
            double a = (sx - left - width / 2.0) / focal, b = -(sy - top - height / 2.0) / focal;
            double dx = fx + rx * a + ux * b, dy = fy + uy * b, dz = fz + rz * a + uz * b;
            double ox = cx - fx * dist, oy = cy - fy * dist, oz = cz - fz * dist;
            if (dy >= -1e-4) dy = -1e-4;
            double t = (cy - oy) / dy;
            return new double[]{ox + dx * t, oz + dz * t};
        }

        /** Blocks from the target to the farthest ground on screen (the same however the camera is turned). */
        public double reach() {
            double far = 0;
            for (int k = 0; k < 4; k++) {
                double[] g = ground(left + (k & 1) * width, top + (k >> 1) * height);
                far = Math.max(far, Math.hypot(g[0] - cx, g[1] - cz));
            }
            return Math.min(MAX_REACH, far * 1.05 + 8);
        }

        boolean same(Cam o) {
            return o != null && o.cx == cx && o.cy == cy && o.cz == cz && o.yaw == yaw && o.pitch == pitch && o.dist == dist
                && o.left == left && o.top == top && o.width == width && o.height == height;
        }
    }

    /**
     * The map read for the model: heights and colours on a grid of {@code side} by {@code side} points {@code res}
     * blocks apart from (x0, z0). Read once on the client thread; the camera may move over it without reading again.
     */
    public record Field(double x0, double z0, double res, int side, float[] height, int[] colour) {
        boolean covers(Cam cam) {
            double r = cam.reach();
            return cam.cx() - r >= x0 && cam.cz() - r >= z0 && cam.cx() + r <= x0 + (side - 1) * res && cam.cz() + r <= z0 + (side - 1) * res
                && resFor(r) == res;
        }

        float height(double x, double z) {
            double gx = (x - x0) / res, gz = (z - z0) / res;
            int i = Math.clamp((int) Math.floor(gx), 0, side - 2), j = Math.clamp((int) Math.floor(gz), 0, side - 2);
            float fx = (float) Math.clamp(gx - i, 0, 1), fz = (float) Math.clamp(gz - j, 0, 1);
            int k = j * side + i;
            float a = height[k] + (height[k + 1] - height[k]) * fx, b = height[k + side] + (height[k + side + 1] - height[k + side]) * fx;
            return a + (b - a) * fz;
        }

        int colour(double x, double z) {
            double gx = (x - x0) / res, gz = (z - z0) / res;
            int i = Math.clamp((int) Math.floor(gx), 0, side - 2), j = Math.clamp((int) Math.floor(gz), 0, side - 2);
            float fx = (float) Math.clamp(gx - i, 0, 1), fz = (float) Math.clamp(gz - j, 0, 1);
            int k = j * side + i;
            return mix(mix(colour[k], colour[k + 1], fx), mix(colour[k + side], colour[k + side + 1], fx), fz);
        }
    }

    /** Points across a field at most. */
    static final int FIELD_SIDE = 640;

    /** The grid spacing of the field read for a camera reaching {@code reach} blocks: whole blocks, at most FIELD_SIDE points across twice the reach. */
    static double resFor(double reach) {
        double res = 1;
        while (2 * reach * 1.5 / res > FIELD_SIDE - 1) res *= 2;
        return res;
    }

    /** A finished drawing: its pixels, and the world position each shows (NaN where it shows nothing). */
    public record Frame(Cam cam, int width, int height, int[] rgb, float[] wx, float[] wz) {
    }

    /** Mesh step (blocks) for a camera. */
    static double step(Cam cam) {
        return Math.max(1, Math.ceil(2 * cam.reach() / MAX_CELLS));
    }

    static int mix(int a, int b, float t) {
        t = Math.clamp(t, 0f, 1f);
        int r = (int) ((a >> 16 & 255) * (1 - t) + (b >> 16 & 255) * t + 0.5f);
        int g = (int) ((a >> 8 & 255) * (1 - t) + (b >> 8 & 255) * t + 0.5f);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t + 0.5f);
        return r << 16 | g << 8 | bl;
    }

    /**
     * Draws the terrain into pixels: a mesh over the ground on screen, a triangle at a time with a depth buffer; each
     * pixel's colour read from the field where it lands (perspective-correct), lit by the sun and faded toward the edge.
     */
    public static Frame raster(Cam cam, Field field, int w, int h) {
        double reach = cam.reach(), step = step(cam);
        int cells = (int) Math.ceil(2 * reach / step), n = cells + 1;
        double x0 = Math.floor((cam.cx() - reach) / step) * step, z0 = Math.floor((cam.cz() - reach) / step) * step;
        double sx = (double) w / cam.width(), sy = (double) h / cam.height();
        float[] px = new float[n * n], py = new float[n * n], iz = new float[n * n];
        float[] light = new float[n * n], fade = new float[n * n], hgt = new float[n * n];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) hgt[j * n + i] = field.height(x0 + i * step, z0 + j * step);
        double fx = cam.fx(), fy = cam.fy(), fz = cam.fz();
        double rx = Math.cos(Math.toRadians(cam.yaw())), rz = Math.sin(Math.toRadians(cam.yaw()));
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double ox = cam.cx() - fx * cam.dist(), oy = cam.cy() - fy * cam.dist(), oz = cam.cz() - fz * cam.dist();
        // a sun from the north-west, fairly high
        double lx = -0.45, ly = 0.75, lz = -0.45, ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll;
        ly /= ll;
        lz /= ll;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                double x = x0 + i * step, z = z0 + j * step;
                double y = (hgt[k] - cam.cy()) * EXAGGERATION + cam.cy();
                double dx = x - ox, dy = y - oy, dz = z - oz;
                double depth = dx * fx + dy * fy + dz * fz;
                if (depth < 1) {
                    iz[k] = Float.NaN;
                    continue;
                }
                px[k] = (float) ((w / 2.0) + cam.focal() * sx * (dx * rx + dz * rz) / depth);
                py[k] = (float) ((h / 2.0) - cam.focal() * sy * (dx * ux + dy * uy + dz * uz) / depth);
                iz[k] = (float) (1 / depth);
                // the slope from the neighbouring heights
                float hl = hgt[j * n + Math.max(0, i - 1)], hr = hgt[j * n + Math.min(n - 1, i + 1)];
                float hu = hgt[Math.max(0, j - 1) * n + i], hd = hgt[Math.min(n - 1, j + 1) * n + i];
                double gx = (hr - hl) * EXAGGERATION / (2 * step), gz = (hd - hu) * EXAGGERATION / (2 * step);
                double nl = Math.sqrt(gx * gx + gz * gz + 1);
                double lambert = (-gx * lx + ly - gz * lz) / nl;
                light[k] = (float) (0.55 + 0.6 * Math.max(0, lambert));
                // the model fades into the background toward its edge
                double dist = Math.hypot(x - cam.cx(), z - cam.cz());
                fade[k] = (float) Math.clamp((reach - dist) / (reach * 0.15), 0, 1);
            }
        }
        int[] rgb = new int[w * h];
        float[] zb = new float[w * h];
        float[] wx = new float[w * h], wz = new float[w * h];
        Arrays.fill(rgb, BACK);
        Arrays.fill(wx, Float.NaN);
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int a = j * n + i, b = a + 1, c = a + n, d = c + 1;
                double xa = x0 + i * step, za = z0 + j * step;
                tri(field, a, b, c, xa, za, xa + step, za, xa, za + step, px, py, iz, light, fade, rgb, zb, wx, wz, w, h);
                tri(field, b, d, c, xa + step, za, xa + step, za + step, xa, za + step, px, py, iz, light, fade, rgb, zb, wx, wz, w, h);
            }
        }
        return new Frame(cam, w, h, rgb, wx, wz);
    }

    private static void tri(Field field, int a, int b, int c, double xa, double za, double xb, double zb0, double xc, double zc,
                            float[] px, float[] py, float[] iz, float[] light, float[] fade, int[] rgb, float[] zb, float[] wx, float[] wz, int w, int h) {
        float ia = iz[a], ib = iz[b], ic = iz[c];
        if (Float.isNaN(ia) || Float.isNaN(ib) || Float.isNaN(ic)) return;
        if (fade[a] <= 0 && fade[b] <= 0 && fade[c] <= 0) return;
        float x0 = px[a], y0 = py[a], x1 = px[b], y1 = py[b], x2 = px[c], y2 = py[c];
        float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-6f) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minX > maxX || minY > maxY) return;
        float la = light[a], lb = light[b], lc = light[c], fa = fade[a], fb = fade[b], fc = fade[c];
        float inv = 1f / area;
        for (int y = minY; y <= maxY; y++) {
            float cy = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float cx = x + 0.5f;
                float w0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) * inv;
                float w1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) * inv;
                float w2 = 1 - w0 - w1;
                if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) continue;
                // nearer is a larger 1/depth
                float inverse = w0 * ia + w1 * ib + w2 * ic;
                int p = y * w + x;
                if (inverse <= zb[p]) continue;
                zb[p] = inverse;
                // perspective-correct world position
                float pa = w0 * ia / inverse, pb = w1 * ib / inverse, pc = w2 * ic / inverse;
                double worldX = pa * xa + pb * xb + pc * xc, worldZ = pa * za + pb * zb0 + pc * zc;
                float l = pa * la + pb * lb + pc * lc, f = pa * fa + pb * fb + pc * fc;
                int col = field.colour(worldX, worldZ);
                int lit = Math.clamp((int) ((col >> 16 & 255) * l), 0, 255) << 16 | Math.clamp((int) ((col >> 8 & 255) * l), 0, 255) << 8
                    | Math.clamp((int) ((col & 255) * l), 0, 255);
                rgb[p] = f >= 1f ? lit : mix(BACK, lit, f);
                wx[p] = (float) worldX;
                wz[p] = (float) worldZ;
            }
        }
    }
}
