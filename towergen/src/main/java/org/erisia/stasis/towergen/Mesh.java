package org.erisia.stasis.towergen;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/** Triangle soup with flat normals and a part (material) per triangle. Tower coordinates: y up, core centre at 0. */
public final class Mesh {
    public float[] pos = new float[9 * 1024];     // 3 vertices x 3 per triangle
    public float[] nrm = new float[3 * 1024];     // one normal per triangle
    public byte[] part = new byte[1024];
    public int tris;

    private void grow() {
        int cap = part.length * 2;
        pos = Arrays.copyOf(pos, 9 * cap);
        nrm = Arrays.copyOf(nrm, 3 * cap);
        part = Arrays.copyOf(part, cap);
    }

    public void tri(double[] a, double[] b, double[] c, VisualParams.Part p) {
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-14) return;                      // degenerate
        if (tris == part.length) grow();
        int o = 9 * tris;
        pos[o] = (float) a[0]; pos[o + 1] = (float) a[1]; pos[o + 2] = (float) a[2];
        pos[o + 3] = (float) b[0]; pos[o + 4] = (float) b[1]; pos[o + 5] = (float) b[2];
        pos[o + 6] = (float) c[0]; pos[o + 7] = (float) c[1]; pos[o + 8] = (float) c[2];
        nrm[3 * tris] = (float) (nx / l); nrm[3 * tris + 1] = (float) (ny / l); nrm[3 * tris + 2] = (float) (nz / l);
        part[tris] = (byte) p.ordinal();
        tris++;
    }

    public void quad(double[] a, double[] b, double[] c, double[] d, VisualParams.Part p) {
        tri(a, b, c, p);
        tri(a, c, d, p);
    }

    /** Orthonormal frame (u, v) perpendicular to the unit axis a (p25shots.frame). */
    public static double[][] frame(double[] a) {
        double[] r = Math.abs(a[1]) > 0.5 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double[] u = V.sub(r, V.mul(a, V.dot(r, a)));
        u = V.unit(u);
        return new double[][]{u, V.cross(a, u)};
    }

    /** Square-section box from p0 to p1 (w wide); a frustum if w1 differs from w0. */
    public void beam(double[] p0, double[] p1, double w0, double w1, VisualParams.Part p) { beam(p0, p1, w0, w1, p, true, true); }

    /** As {@link #beam(double[], double[], double, double, VisualParams.Part)}; cap0 / cap1 false leave out the end faces
     *  (ends sealed inside a joint hub: never visible, and coincident with a hub face they would z-fight). */
    public void beam(double[] p0, double[] p1, double w0, double w1, VisualParams.Part p, boolean cap0, boolean cap1) {
        double[] d = V.sub(p1, p0);
        double L = V.norm(d);
        if (L < 1e-9) return;
        double[] a = V.mul(d, 1 / L);
        double[][] f = frame(a);
        double[] u = f[0], v = f[1];
        double[][] c0 = new double[4][], c1 = new double[4][];
        double[][] sg = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}};
        for (int k = 0; k < 4; k++) {
            c0[k] = new double[3]; c1[k] = new double[3];
            for (int q = 0; q < 3; q++) {
                c0[k][q] = p0[q] + 0.5 * w0 * (sg[k][0] * u[q] + sg[k][1] * v[q]);
                c1[k][q] = p1[q] + 0.5 * w1 * (sg[k][0] * u[q] + sg[k][1] * v[q]);
            }
        }
        for (int k = 0; k < 4; k++) {
            int k2 = (k + 1) % 4;
            quad(c0[k], c0[k2], c1[k2], c1[k], p);
        }
        if (cap0) quad(c0[3], c0[2], c0[1], c0[0], p);
        if (cap1) quad(c1[0], c1[1], c1[2], c1[3], p);
    }

    public void beam(double[] p0, double[] p1, double w, VisualParams.Part p) { beam(p0, p1, w, w, p); }

    /** Axis-aligned vertical block centred on (x, z) from y0 to y1. */
    public void block(double[] c, double y0, double y1, double w, VisualParams.Part p) {
        beam(new double[]{c[0], y0, c[2]}, new double[]{c[0], y1, c[2]}, w, p);
    }

    /** n-sided prism (disc) of radius r centred on c along the unit axis, thickness t. */
    public void prism(double[] c, double[] axis, double r, double t, int n, VisualParams.Part p) {
        double[][] f = frame(axis);
        double[] lo = V.sub(c, V.mul(axis, t / 2)), hi = V.add(c, V.mul(axis, t / 2));
        double[][] a = new double[n][], b = new double[n][];
        for (int k = 0; k < n; k++) {
            double th = 2 * Math.PI * k / n;
            double[] o = V.add(V.mul(f[0], r * Math.cos(th)), V.mul(f[1], r * Math.sin(th)));
            a[k] = V.add(lo, o); b[k] = V.add(hi, o);
        }
        for (int k = 0; k < n; k++) {
            int k2 = (k + 1) % n;
            quad(a[k], a[k2], b[k2], b[k], p);
            tri(hi, b[k], b[k2], p);
            tri(lo, a[k2], a[k], p);
        }
    }

    /** Bounding box {minx, miny, minz, maxx, maxy, maxz}. */
    public double[] bounds() {
        double[] b = {1e30, 1e30, 1e30, -1e30, -1e30, -1e30};
        for (int i = 0; i < 3 * tris; i++) {
            for (int q = 0; q < 3; q++) {
                double v = pos[3 * i + q];
                b[q] = Math.min(b[q], v); b[q + 3] = Math.max(b[q + 3], v);
            }
        }
        return b;
    }

    /** SHA-256 over positions, normals and parts (determinism checks). */
    public String hash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer bb = ByteBuffer.allocate(4 * (9 + 3) + 1);
            for (int t = 0; t < tris; t++) {
                bb.clear();
                for (int q = 0; q < 9; q++) bb.putFloat(pos[9 * t + q]);
                for (int q = 0; q < 3; q++) bb.putFloat(nrm[3 * t + q]);
                bb.put(part[t]);
                md.update(bb.array(), 0, bb.position());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Wavefront OBJ (one group per part) for external viewers. */
    public String toObj() {
        StringBuilder sb = new StringBuilder("# towergen mesh\n");
        for (VisualParams.Part p : VisualParams.Part.values()) {
            boolean any = false;
            for (int t = 0; t < tris; t++) {
                if (part[t] != p.ordinal()) continue;
                if (!any) { sb.append("g ").append(p.name().toLowerCase()).append("\nusemtl ").append(p.material.name().toLowerCase()).append('\n'); any = true; }
                for (int k = 0; k < 3; k++)
                    sb.append("v ").append(pos[9 * t + 3 * k]).append(' ').append(pos[9 * t + 3 * k + 1]).append(' ').append(pos[9 * t + 3 * k + 2]).append('\n');
                sb.append("f -3 -2 -1\n");
            }
        }
        return sb.toString();
    }
}
