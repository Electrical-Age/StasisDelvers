package org.erisia.stasis.towerview;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.erisia.stasis.towergen.Mesh;
import org.erisia.stasis.towergen.VisualParams;

/**
 * Dependency-free software rasteriser: z-buffer (1/z, perspective-correct depth), flat Lambert shading with a
 * hemispheric ambient, emissive glow, distance haze, sky gradient, a ground plane with a block grid and a sun shadow
 * map, supersampling. Multi-threaded by screen bands.
 */
public final class Raster {
    public record Camera(double[] eye, double[] target, double fovDeg) {}

    private final int W, H, ss;
    private final VisualParams vp;
    private static final int SHADOW = 2048;

    public Raster(int w, int h, int ss, VisualParams vp) {
        this.W = w; this.H = h; this.ss = Math.max(1, ss); this.vp = vp;
    }

    private static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }
    private static double[] unit(double[] a) { double l = Math.sqrt(dot(a, a)); return new double[]{a[0] / l, a[1] / l, a[2] / l}; }

    /** Sun shadow map: orthographic depth along the sun direction over the mesh bounds. */
    private final class Shadow {
        final double[] sx, sy, sz;        // sun basis: sx, sy across, sz toward the sun
        final double x0, y0, scale;
        final float[] depth = new float[SHADOW * SHADOW];   // largest "toward-sun" coordinate (occluder) per texel

        Shadow(Mesh m, double[] sun) {
            sz = sun;
            double[][] f = Mesh.frame(sun);
            sx = f[0]; sy = f[1];
            double[] b = m.bounds();
            double minx = 1e30, maxx = -1e30, miny = 1e30, maxy = -1e30;
            for (int k = 0; k < 8; k++) {
                double[] p = {b[(k & 1) == 0 ? 0 : 3], b[(k & 2) == 0 ? 1 : 4], b[(k & 4) == 0 ? 2 : 5]};
                double u = dot(p, sx), v = dot(p, sy);
                minx = Math.min(minx, u); maxx = Math.max(maxx, u); miny = Math.min(miny, v); maxy = Math.max(maxy, v);
            }
            double ext = Math.max(maxx - minx, maxy - miny) + 1.0;
            x0 = minx - 0.5; y0 = miny - 0.5; scale = SHADOW / ext;
            java.util.Arrays.fill(depth, Float.NEGATIVE_INFINITY);
            float[] pos = m.pos;
            for (int t = 0; t < m.tris; t++) {
                if (VisualParams.Part.values()[m.part[t]] == VisualParams.Part.GLOW) continue;
                double[] u = new double[3], v = new double[3], w = new double[3];
                for (int k = 0; k < 3; k++) {
                    double[] p = {pos[9 * t + 3 * k], pos[9 * t + 3 * k + 1], pos[9 * t + 3 * k + 2]};
                    u[k] = (dot(p, sx) - x0) * scale; v[k] = (dot(p, sy) - y0) * scale; w[k] = dot(p, sz);
                }
                rasterMax(u, v, w);
            }
        }

        private void rasterMax(double[] u, double[] v, double[] w) {
            int xa = (int) Math.max(0, Math.floor(Math.min(u[0], Math.min(u[1], u[2]))));
            int xb = (int) Math.min(SHADOW - 1, Math.ceil(Math.max(u[0], Math.max(u[1], u[2]))));
            int ya = (int) Math.max(0, Math.floor(Math.min(v[0], Math.min(v[1], v[2]))));
            int yb = (int) Math.min(SHADOW - 1, Math.ceil(Math.max(v[0], Math.max(v[1], v[2]))));
            double area = (u[1] - u[0]) * (v[2] - v[0]) - (u[2] - u[0]) * (v[1] - v[0]);
            if (Math.abs(area) < 1e-12) {
                // thin sliver (edge-on): stamp the vertices so thin cables still cast something
                for (int k = 0; k < 3; k++) {
                    int x = (int) u[k], y = (int) v[k];
                    if (x >= 0 && y >= 0 && x < SHADOW && y < SHADOW) depth[y * SHADOW + x] = Math.max(depth[y * SHADOW + x], (float) w[k]);
                }
                return;
            }
            for (int y = ya; y <= yb; y++) {
                for (int x = xa; x <= xb; x++) {
                    double px = x + 0.5, py = y + 0.5;
                    double b0 = ((u[1] - px) * (v[2] - py) - (u[2] - px) * (v[1] - py)) / area;
                    double b1 = ((u[2] - px) * (v[0] - py) - (u[0] - px) * (v[2] - py)) / area;
                    double b2 = 1 - b0 - b1;
                    if (b0 < -1e-9 || b1 < -1e-9 || b2 < -1e-9) continue;
                    float z = (float) (b0 * w[0] + b1 * w[1] + b2 * w[2]);
                    int i = y * SHADOW + x;
                    if (z > depth[i]) depth[i] = z;
                }
            }
        }

        /** 0 = fully shadowed .. 1 = lit (3x3 PCF). */
        double lit(double[] p, double bias) {
            double u = (dot(p, sx) - x0) * scale, v = (dot(p, sy) - y0) * scale, w = dot(p, sz);
            int x = (int) u, y = (int) v;
            int lit = 0, n = 0;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int xx = x + dx, yy = y + dy;
                    n++;
                    if (xx < 0 || yy < 0 || xx >= SHADOW || yy >= SHADOW) { lit++; continue; }
                    if (depth[yy * SHADOW + xx] <= w + bias) lit++;
                }
            return lit / (double) n;
        }
    }

    public BufferedImage render(Mesh m, Camera cam, String caption) {
        int Ws = W * ss, Hs = H * ss;
        double[] eye = cam.eye();
        double[] f = unit(sub(cam.target(), eye));
        double[] r = unit(cross(f, new double[]{0, 1, 0}));
        double[] u = cross(r, f);
        double focal = (Hs / 2.0) / Math.tan(Math.toRadians(cam.fovDeg()) / 2);
        double az = Math.toRadians(vp.sunAzimuth()), el = Math.toRadians(vp.sunElevation());
        double[] sun = {Math.cos(el) * Math.cos(az), Math.sin(el), Math.cos(el) * Math.sin(az)};
        Shadow sh = new Shadow(m, sun);
        double[] bnd = m.bounds();
        double scene = Math.max(bnd[4], Math.max(bnd[3] - bnd[0], bnd[5] - bnd[2]));
        double haze = Math.max(60, 6 * scene);
        float[] zb = new float[Ws * Hs];
        int[] col = new int[Ws * Hs];
        double[] hor = vp.skyHorizon(), top = vp.skyTop(), gc = vp.groundColour();
        // background: sky and ground (with shadow), depth of the ground plane
        int nth = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
        ExecutorService ex = Executors.newFixedThreadPool(nth);
        try {
            List<Future<?>> fs = new ArrayList<>();
            int band = (Hs + nth - 1) / nth;
            for (int t0 = 0; t0 < nth; t0++) {
                final int ya = t0 * band, yb = Math.min(Hs, ya + band);
                fs.add(ex.submit(() -> {
                    for (int y = ya; y < yb; y++) {
                        for (int x = 0; x < Ws; x++) {
                            double dx = (x + 0.5 - Ws / 2.0) / focal, dy = (Hs / 2.0 - (y + 0.5)) / focal;
                            double[] d = unit(new double[]{f[0] + r[0] * dx + u[0] * dy, f[1] + r[1] * dx + u[1] * dy, f[2] + r[2] * dx + u[2] * dy});
                            int i = y * Ws + x;
                            if (vp.drawGround() && d[1] < -1e-6 && eye[1] > 0) {
                                double t = -eye[1] / d[1];
                                double[] hp = {eye[0] + t * d[0], 0, eye[2] + t * d[2]};
                                double viewZ = t * dot(d, f);
                                zb[i] = (float) (1 / viewZ);
                                double g = vp.groundGrid();
                                double shade = 1.0;
                                if (g > 0) {
                                    double fx = Math.abs(hp[0] / g - Math.rint(hp[0] / g)), fz = Math.abs(hp[2] / g - Math.rint(hp[2] / g));
                                    double lw = 0.03 * Math.max(1, t / 40);
                                    if (fx * g < lw || fz * g < lw) shade = 0.88;
                                }
                                double lit = sh.lit(hp, 0.05);
                                double light = vp.ambient() + (1 - vp.ambient()) * Math.max(0, sun[1]) * (0.35 + 0.65 * lit);
                                double hz = 1 - Math.exp(-t / haze);
                                col[i] = rgb(mix(gc[0] * light * shade, hor[0], hz), mix(gc[1] * light * shade, hor[1], hz), mix(gc[2] * light * shade, hor[2], hz));
                            } else {
                                zb[i] = 0;
                                double s = Math.pow(Math.max(0, d[1]), 0.6);
                                col[i] = rgb(mix(hor[0], top[0], s), mix(hor[1], top[1], s), mix(hor[2], top[2], s));
                            }
                        }
                    }
                }));
            }
            for (Future<?> fu : fs) fu.get();
            // transform, light and clip the triangles once
            List<double[]> st = new ArrayList<>();     // screen triangles: x0 y0 iz0 x1 y1 iz1 x2 y2 iz2, packed rgb
            float[] pos = m.pos;
            double near = 0.05;
            VisualParams.Part[] parts = VisualParams.Part.values();
            for (int t = 0; t < m.tris; t++) {
                double[][] vv = new double[3][];
                double[] cen = new double[3];
                for (int k = 0; k < 3; k++) {
                    double[] p = {pos[9 * t + 3 * k] - eye[0], pos[9 * t + 3 * k + 1] - eye[1], pos[9 * t + 3 * k + 2] - eye[2]};
                    vv[k] = new double[]{dot(p, r), dot(p, u), dot(p, f)};
                    for (int q = 0; q < 3; q++) cen[q] += pos[9 * t + 3 * k + q] / 3;
                }
                if (vv[0][2] < near && vv[1][2] < near && vv[2][2] < near) continue;
                VisualParams.Part part = parts[m.part[t]];
                double[] base = vp.colour(part);
                double[] nn = {m.nrm[3 * t], m.nrm[3 * t + 1], m.nrm[3 * t + 2]};
                if (dot(nn, sub(eye, cen)) < 0) nn = new double[]{-nn[0], -nn[1], -nn[2]};
                double amb, dir;
                if (part == VisualParams.Part.GLOW) { amb = 1.05; dir = 0; }
                else {
                    amb = vp.ambient() * (0.75 + 0.25 * nn[1]);
                    dir = (1 - vp.ambient()) * Math.max(0, dot(nn, sun));
                    // thin wires don't receive shadows (shadow-map acne at their size); flag with a negative direct term
                    if (part == VisualParams.Part.CABLE || part == VisualParams.Part.BACKSTAY) dir = -dir - 1e-9;
                }
                double dist = Math.sqrt(dot(sub(cen, eye), sub(cen, eye)));
                double hz = 1 - Math.exp(-dist / haze);
                List<double[]> poly = clipNear(vv, near);
                for (int k = 1; k + 1 < poly.size(); k++) {
                    double[] a = poly.get(0), b = poly.get(k), cc = poly.get(k + 1);
                    double[] tr = new double[28];
                    int o = 0;
                    for (double[] q : new double[][]{a, b, cc}) {
                        tr[o++] = Ws / 2.0 + q[0] / q[2] * focal;
                        tr[o++] = Hs / 2.0 - q[1] / q[2] * focal;
                        tr[o++] = 1 / q[2];
                    }
                    for (double[] q : new double[][]{a, b, cc})       // world position / z (perspective-correct)
                        for (int qq = 0; qq < 3; qq++) tr[o++] = (eye[qq] + r[qq] * q[0] + u[qq] * q[1] + f[qq] * q[2]) / q[2];
                    for (int qq = 0; qq < 3; qq++) tr[o++] = base[qq] * amb;
                    for (int qq = 0; qq < 3; qq++) tr[o++] = base[qq] * dir;
                    tr[o++] = hz;
                    tr[o++] = nn[0]; tr[o++] = nn[1]; tr[o] = nn[2];      // for the normal-offset shadow lookup
                    st.add(tr);
                }
            }
            fs.clear();
            for (int t0 = 0; t0 < nth; t0++) {
                final int ya = t0 * band, yb = Math.min(Hs, ya + band);
                fs.add(ex.submit(() -> {
                    for (double[] tr : st) rasterTri(tr, ya, yb, Ws, zb, col, sh, hor);
                }));
            }
            for (Future<?> fu : fs) fu.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            ex.shutdown();
        }
        // downsample
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int n2 = ss * ss;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int sr = 0, sg = 0, sb = 0;
                for (int j = 0; j < ss; j++)
                    for (int i = 0; i < ss; i++) {
                        int c = col[(y * ss + j) * Ws + x * ss + i];
                        sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
                    }
                img.setRGB(x, y, ((sr / n2) << 16) | ((sg / n2) << 8) | (sb / n2));
            }
        }
        if (caption != null && !caption.isEmpty()) label(img, caption);
        return img;
    }

    static void label(BufferedImage img, String text) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int fs = Math.max(11, img.getHeight() / 48);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fs));
        int w = g.getFontMetrics().stringWidth(text);
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(10, 10, w + 2 * fs / 2, fs * 2);
        g.setColor(Color.WHITE);
        g.drawString(text, 10 + fs / 2, 10 + fs * 3 / 2 - 2);
        g.dispose();
    }

    private static double mix(double a, double b, double t) { return a + (b - a) * t; }

    private static int rgb(double r, double g, double b) {
        int R = (int) Math.round(255 * Math.max(0, Math.min(1, r)));
        int G = (int) Math.round(255 * Math.max(0, Math.min(1, g)));
        int B = (int) Math.round(255 * Math.max(0, Math.min(1, b)));
        return (R << 16) | (G << 8) | B;
    }

    /** Sutherland-Hodgman against z >= near (view space). */
    private static List<double[]> clipNear(double[][] v, double near) {
        List<double[]> out = new ArrayList<>(4);
        for (int k = 0; k < 3; k++) {
            double[] a = v[k], b = v[(k + 1) % 3];
            boolean ia = a[2] >= near, ib = b[2] >= near;
            if (ia) out.add(a);
            if (ia != ib) {
                double t = (near - a[2]) / (b[2] - a[2]);
                out.add(new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), near});
            }
        }
        return out;
    }

    private static void rasterTri(double[] t, int ya, int yb, int Ws, float[] zb, int[] col, Shadow sh, double[] hor) {
        double x0 = t[0], y0 = t[1], z0 = t[2], x1 = t[3], y1 = t[4], z1 = t[5], x2 = t[6], y2 = t[7], z2 = t[8];
        double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
        if (Math.abs(area) < 1e-12) return;
        int minY = Math.max(ya, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(yb - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        if (minY > maxY) return;
        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(Ws - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        if (minX > maxX) return;
        double inv = 1 / area;
        boolean noShadow = t[21] < 0;           // shared across band threads: read only
        double dr = Math.abs(t[21]), dg = Math.abs(t[22]), db = Math.abs(t[23]);
        boolean direct = !noShadow && (dr > 0 || dg > 0 || db > 0);
        double hz = t[24];
        int flat = direct ? 0 : rgb(mix(t[18] + dr, hor[0], hz), mix(t[19] + dg, hor[1], hz), mix(t[20] + db, hor[2], hz));
        double[] p = new double[3];
        double off = 2.0 / sh.scale;              // normal offset: two shadow texels
        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) * inv;
                double w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) * inv;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                double zi = w0 * z0 + w1 * z1 + w2 * z2;
                float z = (float) zi;
                int i = y * Ws + x;
                if (z <= zb[i]) continue;
                zb[i] = z;
                if (!direct) { col[i] = flat; continue; }
                for (int q = 0; q < 3; q++) p[q] = (w0 * t[9 + q] + w1 * t[12 + q] + w2 * t[15 + q]) / zi + t[25 + q] * off;
                double l = 0.25 + 0.75 * sh.lit(p, 0.04 + 1.5 / sh.scale);
                col[i] = rgb(mix(t[18] + dr * l, hor[0], hz), mix(t[19] + dg * l, hor[1], hz), mix(t[20] + db * l, hor[2], hz));
            }
        }
    }
}
