package org.erisia.stasis.towergen;

/**
 * Small 3D convex hull for joint hubs (tens of points). Quickhull: start from a maximal tetrahedron, then repeatedly
 * take the face (lowest id) with outside points, add its farthest point (ties: lowest index) and replace the faces it
 * sees by a fan over their horizon; the orphaned outside points move to the first new face that sees them. Distances
 * below {@code eps} (1e-9 x the cloud's extent) count as inside, so near-coplanar points never create slivers.
 * Deterministic (no hashing, fixed tie-breaks: identical results on the JVM and under TeaVM) and allocation-light
 * (flat arrays: typed-array allocation is slow in the browser build).
 *
 * <p>Degenerate clouds: {@link #dim} 2 (all points in a plane within eps) gives the 2D convex polygon in that plane
 * ({@link #poly}, drawn from both sides); {@link #dim} below 2 (collinear / single point) gives nothing. If the 3D
 * construction ever comes out inconsistent (a point outside the result, or an unpaired edge), it is retried with a
 * 10x larger eps, then reported as {@link #ok} false.
 */
final class Hull3 {
    final double[] xyz;            // deduplicated points, 3 per point
    final int n;
    int dim;                       // 3: tri / pl; 2: poly (+ normal); < 2: nothing
    boolean ok = true;
    int nTri;                      // dim 3: faces tri[3 k ..], counter-clockwise seen from outside
    int[] tri;
    double[] pl;                   // per face: nx, ny, nz, c  (n . x <= c inside)
    int[] poly;                    // dim 2: polygon (counter-clockwise about normal)
    double[] normal;               // dim 2: plane normal, offset planeC
    double planeC;
    final double eps;

    private Hull3(double[] xyz, int n, double eps) { this.xyz = xyz; this.n = n; this.eps = eps; }

    /** Hull of m points given as xyz[0 .. 3 m). */
    static Hull3 of(double[] in, int m) {
        double lx = 1e300, ly = 1e300, lz = 1e300, hx = -1e300, hy = -1e300, hz = -1e300;
        for (int i = 0; i < m; i++) {
            double x = in[3 * i], y = in[3 * i + 1], z = in[3 * i + 2];
            lx = Math.min(lx, x); ly = Math.min(ly, y); lz = Math.min(lz, z);
            hx = Math.max(hx, x); hy = Math.max(hy, y); hz = Math.max(hz, z);
        }
        double ext = Math.max(hx - lx, Math.max(hy - ly, hz - lz));
        double eps = Math.max(1e-12, 1e-9 * ext);
        double[] u = new double[3 * m];
        int k = 0;
        for (int i = 0; i < m; i++) {
            double x = in[3 * i], y = in[3 * i + 1], z = in[3 * i + 2];
            boolean dup = false;
            for (int j = 0; j < k && !dup; j++)
                dup = Math.abs(x - u[3 * j]) <= eps && Math.abs(y - u[3 * j + 1]) <= eps && Math.abs(z - u[3 * j + 2]) <= eps;
            if (!dup) { u[3 * k] = x; u[3 * k + 1] = y; u[3 * k + 2] = z; k++; }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            Hull3 h = new Hull3(u, k, eps);
            h.build();
            if (h.ok) return h;
            eps *= 10;
        }
        Hull3 h = new Hull3(u, k, eps);
        h.ok = false;
        h.dim = 0;
        return h;
    }

    double x(int i) { return xyz[3 * i]; }

    double[] point(int i) { return new double[]{xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]}; }

    private double d2(int i, int j) {
        double dx = xyz[3 * i] - xyz[3 * j], dy = xyz[3 * i + 1] - xyz[3 * j + 1], dz = xyz[3 * i + 2] - xyz[3 * j + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- working state (dim 3)
    private int[] fv;              // 3 per face id
    private double[] fp;           // 4 per face id
    private boolean[] alive;
    private int[] edgeF;           // directed edge (a, b) -> face id + 1
    private int[] head;            // per face: first outside point (-1 none)
    private int[] next;            // per point: next point on the same face's list
    private int nf;

    private double dist(int f, int i) {
        return fp[4 * f] * xyz[3 * i] + fp[4 * f + 1] * xyz[3 * i + 1] + fp[4 * f + 2] * xyz[3 * i + 2] - fp[4 * f + 3];
    }

    private void build() {
        if (n < 3) { dim = n < 2 ? 0 : 1; return; }
        int i0 = 0;
        for (int i = 1; i < n; i++) if (x(i) < x(i0)) i0 = i;
        int i1 = -1;
        double best = -1;
        for (int i = 0; i < n; i++) { double d = d2(i, i0); if (d > best) { best = d; i1 = i; } }
        if (best <= eps * eps) { dim = 0; return; }
        double ax = xyz[3 * i1] - xyz[3 * i0], ay = xyz[3 * i1 + 1] - xyz[3 * i0 + 1], az = xyz[3 * i1 + 2] - xyz[3 * i0 + 2];
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        int i2 = -1;
        best = -1;
        for (int i = 0; i < n; i++) {
            double bx = xyz[3 * i] - xyz[3 * i0], by = xyz[3 * i + 1] - xyz[3 * i0 + 1], bz = xyz[3 * i + 2] - xyz[3 * i0 + 2];
            double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            double d = Math.sqrt(cx * cx + cy * cy + cz * cz) / al;
            if (d > best) { best = d; i2 = i; }
        }
        if (best <= eps) { dim = 1; return; }
        double[] nrm = V.unit(V.cross(V.sub(point(i1), point(i0)), V.sub(point(i2), point(i0))));
        double c0 = V.dot(nrm, point(i0));
        int i3 = -1;
        best = -1;
        for (int i = 0; i < n; i++) {
            double d = Math.abs(nrm[0] * xyz[3 * i] + nrm[1] * xyz[3 * i + 1] + nrm[2] * xyz[3 * i + 2] - c0);
            if (d > best) { best = d; i3 = i; }
        }
        if (best <= eps) { planar(nrm, c0); return; }
        dim = 3;
        if (nrm[0] * xyz[3 * i3] + nrm[1] * xyz[3 * i3 + 1] + nrm[2] * xyz[3 * i3 + 2] - c0 > 0) { int t = i1; i1 = i2; i2 = t; }
        int cap = 4 * n + 8;
        fv = new int[3 * cap];
        fp = new double[4 * cap];
        alive = new boolean[cap];
        head = new int[cap];
        next = new int[n];
        edgeF = new int[n * n];
        addFace(i0, i1, i2);
        addFace(i0, i3, i1);
        addFace(i1, i3, i2);
        addFace(i2, i3, i0);
        for (int i = 0; i < n; i++) if (i != i0 && i != i1 && i != i2 && i != i3) assign(i, 0);
        int[] vis = new int[cap];
        int[] hz = new int[6 * n + 12];
        int[] orphans = new int[n];
        int stamp = 0;
        for (int iter = 0; iter < n; iter++) {
            int f0 = -1;
            for (int f = 0; f < nf; f++) if (alive[f] && head[f] >= 0) { f0 = f; break; }
            if (f0 < 0) break;
            int bp = -1;
            double bd = -1;
            for (int i = head[f0]; i >= 0; i = next[i]) {
                double d = dist(f0, i);
                if (d > bd || (d == bd && i < bp)) { bd = d; bp = i; }
            }
            stamp++;
            for (int f = 0; f < nf; f++) if (alive[f] && (f == f0 || dist(f, bp) > eps)) vis[f] = stamp;
            int nh = 0;
            for (int f = 0; f < nf; f++) {
                if (vis[f] != stamp) continue;
                for (int k = 0; k < 3; k++) {
                    int a = fv[3 * f + k], b = fv[3 * f + (k + 1) % 3];
                    int g = edgeF[b * n + a] - 1;
                    if (g < 0 || !alive[g]) { ok = false; return; }
                    if (vis[g] != stamp) {
                        if (nh + 2 > hz.length) hz = java.util.Arrays.copyOf(hz, 2 * hz.length);
                        hz[nh++] = a; hz[nh++] = b;
                    }
                }
            }
            int no = 0;
            for (int f = 0; f < nf; f++) {
                if (vis[f] != stamp) continue;
                alive[f] = false;
                for (int k = 0; k < 3; k++) {
                    int a = fv[3 * f + k], b = fv[3 * f + (k + 1) % 3];
                    if (edgeF[a * n + b] == f + 1) edgeF[a * n + b] = 0;
                }
                for (int i = head[f]; i >= 0; i = next[i]) if (i != bp) orphans[no++] = i;
            }
            int first = nf;
            for (int k = 0; k < nh; k += 2) {
                if (nf == alive.length) grow();
                addFace(hz[k], hz[k + 1], bp);
            }
            if (vis.length < alive.length) vis = java.util.Arrays.copyOf(vis, alive.length);
            for (int k = 0; k < no; k++) assign(orphans[k], first);
        }
        nTri = 0;
        for (int f = 0; f < nf; f++) if (alive[f]) nTri++;
        tri = new int[3 * nTri];
        pl = new double[4 * nTri];
        int t = 0;
        for (int f = 0; f < nf; f++) {
            if (!alive[f]) continue;
            System.arraycopy(fv, 3 * f, tri, 3 * t, 3);
            System.arraycopy(fp, 4 * f, pl, 4 * t, 4);
            t++;
            for (int k = 0; k < 3; k++) {                  // consistency: every edge paired
                int g = edgeF[fv[3 * f + (k + 1) % 3] * n + fv[3 * f + k]] - 1;
                if (g < 0 || !alive[g]) { ok = false; return; }
            }
        }
        for (int i = 0; i < n; i++)                         // ... and every point inside
            for (int f = 0; f < nTri; f++)
                if (pl[4 * f] * xyz[3 * i] + pl[4 * f + 1] * xyz[3 * i + 1] + pl[4 * f + 2] * xyz[3 * i + 2] - pl[4 * f + 3] > 10 * eps) { ok = false; return; }
        fv = null; fp = null; alive = null; edgeF = null; head = null; next = null;
    }

    private void grow() {
        int cap = 2 * alive.length;
        fv = java.util.Arrays.copyOf(fv, 3 * cap);
        fp = java.util.Arrays.copyOf(fp, 4 * cap);
        alive = java.util.Arrays.copyOf(alive, cap);
        head = java.util.Arrays.copyOf(head, cap);
    }

    /** Puts point i on the outside list of the first live face from id `from` on that sees it (else it is inside). */
    private void assign(int i, int from) {
        for (int f = from; f < nf; f++) {
            if (alive[f] && dist(f, i) > eps) { next[i] = head[f]; head[f] = i; return; }
        }
    }

    private void addFace(int a, int b, int c) {
        double ux = xyz[3 * b] - xyz[3 * a], uy = xyz[3 * b + 1] - xyz[3 * a + 1], uz = xyz[3 * b + 2] - xyz[3 * a + 2];
        double vx = xyz[3 * c] - xyz[3 * a], vy = xyz[3 * c + 1] - xyz[3 * a + 1], vz = xyz[3 * c + 2] - xyz[3 * a + 2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-300) l = 1e-300;
        nx /= l; ny /= l; nz /= l;
        fv[3 * nf] = a; fv[3 * nf + 1] = b; fv[3 * nf + 2] = c;
        fp[4 * nf] = nx; fp[4 * nf + 1] = ny; fp[4 * nf + 2] = nz;
        fp[4 * nf + 3] = nx * xyz[3 * a] + ny * xyz[3 * a + 1] + nz * xyz[3 * a + 2];
        alive[nf] = true;
        head[nf] = -1;
        edgeF[a * n + b] = edgeF[b * n + c] = edgeF[c * n + a] = nf + 1;
        nf++;
    }

    private void planar(double[] nrm, double c0) {
        dim = 2;
        normal = nrm;
        planeC = c0;
        double[][] f = Mesh.frame(nrm);
        double[][] q = new double[n][];
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) { double[] p = point(i); q[i] = new double[]{V.dot(p, f[0]), V.dot(p, f[1])}; ord[i] = i; }
        java.util.Arrays.sort(ord, (x, y) -> q[x][0] != q[y][0] ? Double.compare(q[x][0], q[y][0])
                : q[x][1] != q[y][1] ? Double.compare(q[x][1], q[y][1]) : Integer.compare(x, y));
        int[] h = new int[2 * n + 1];
        int k = 0;
        for (int i = 0; i < n; i++) {                       // Andrew's monotone chain (counter-clockwise in (u, v))
            while (k >= 2 && cross2(q[h[k - 2]], q[h[k - 1]], q[ord[i]]) <= eps * eps) k--;
            h[k++] = ord[i];
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross2(q[h[k - 2]], q[h[k - 1]], q[ord[i]]) <= eps * eps) k--;
            h[k++] = ord[i];
        }
        poly = java.util.Arrays.copyOf(h, Math.max(0, k - 1));
        if (poly.length < 3) dim = 1;
        // (u, v, nrm) is right-handed (v = nrm x u), so counter-clockwise in (u, v) is counter-clockwise about nrm
    }

    private static double cross2(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** dim 3: is x inside (within tol)? */
    boolean contains(double[] x, double tol) {
        if (dim != 3) return false;
        for (int f = 0; f < nTri; f++) if (pl[4 * f] * x[0] + pl[4 * f + 1] * x[1] + pl[4 * f + 2] * x[2] - pl[4 * f + 3] > tol) return false;
        return true;
    }

    /** dim 3: the parameter interval {lo, hi} of p + s d inside the hull (within tol), or null if it misses. */
    double[] clip(double[] p, double[] d, double tol) {
        if (dim != 3) return null;
        double lo = -1e300, hi = 1e300;
        for (int f = 0; f < nTri; f++) {
            double a = pl[4 * f] * d[0] + pl[4 * f + 1] * d[1] + pl[4 * f + 2] * d[2];
            double b = pl[4 * f] * p[0] + pl[4 * f + 1] * p[1] + pl[4 * f + 2] * p[2] - pl[4 * f + 3] - tol;   // inside: b + s a <= 0
            if (Math.abs(a) < 1e-12) { if (b > 0) return null; continue; }
            double s = -b / a;
            if (a > 0) hi = Math.min(hi, s); else lo = Math.max(lo, s);
            if (lo > hi) return null;
        }
        return new double[]{lo, hi};
    }

    /** Triangles to draw: the faces (dim 3) or the polygon from both sides (dim 2). */
    void draw(Mesh mesh, VisualParams.Part part) {
        if (dim == 3) {
            for (int f = 0; f < nTri; f++) mesh.tri(point(tri[3 * f]), point(tri[3 * f + 1]), point(tri[3 * f + 2]), part);
        } else if (dim == 2) {
            for (int k = 1; k + 1 < poly.length; k++) {
                mesh.tri(point(poly[0]), point(poly[k]), point(poly[k + 1]), part);
                mesh.tri(point(poly[0]), point(poly[k + 1]), point(poly[k]), part);
            }
        }
    }
}
