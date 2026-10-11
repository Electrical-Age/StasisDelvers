package org.erisia.stasis.client;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shield's cells: a Goldberg polyhedron (the dual of a geodesic icosphere: hexagons and 12 pentagons, nearly
 * even), as in the render prototype (timestop-proto/shield.py). Unit sphere; cached per frequency.
 */
final class Goldberg {
    private Goldberg() {
    }

    record Cell(Vector3d centre, Vector3d[] ring, double rnd) {
    }

    private static final Map<Integer, List<Cell>> CACHE = new HashMap<>();

    static synchronized List<Cell> cells(int freq) {
        return CACHE.computeIfAbsent(freq, Goldberg::build);
    }

    private static List<Cell> build(int freq) {
        double t = (1 + Math.sqrt(5)) / 2;
        double[][] iv = {{-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0}, {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
            {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}};
        int[][] faces = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6},
            {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        Vector3d[] ico = new Vector3d[12];
        for (int i = 0; i < 12; i++) ico[i] = new Vector3d(iv[i][0], iv[i][1], iv[i][2]).normalize();
        List<Vector3d> verts = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        List<int[]> tris = new ArrayList<>();
        for (int[] f : faces) {
            Vector3d A = ico[f[0]], B = ico[f[1]], C = ico[f[2]];
            int[][] grid = new int[freq + 1][freq + 1];
            for (int i = 0; i <= freq; i++)
                for (int j = 0; j <= freq - i; j++) {
                    int k = freq - i - j;
                    Vector3d p = new Vector3d(A).mul(i).add(new Vector3d(B).mul(j)).add(new Vector3d(C).mul(k)).div(freq).normalize();
                    String key = Math.round(p.x * 1e6) + "," + Math.round(p.y * 1e6) + "," + Math.round(p.z * 1e6);
                    Integer id = index.get(key);
                    if (id == null) {
                        id = verts.size();
                        index.put(key, id);
                        verts.add(p);
                    }
                    grid[i][j] = id;
                }
            for (int i = 0; i < freq; i++)
                for (int j = 0; j < freq - i; j++) {
                    tris.add(new int[]{grid[i][j], grid[i + 1][j], grid[i][j + 1]});
                    if (i + j < freq - 1) tris.add(new int[]{grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]});
                }
        }
        Vector3d[] cent = new Vector3d[tris.size()];
        List<List<Integer>> around = new ArrayList<>();
        for (int i = 0; i < verts.size(); i++) around.add(new ArrayList<>());
        for (int ti = 0; ti < tris.size(); ti++) {
            int[] tr = tris.get(ti);
            cent[ti] = new Vector3d(verts.get(tr[0])).add(verts.get(tr[1])).add(verts.get(tr[2])).normalize();
            for (int v : tr) around.get(v).add(ti);
        }
        List<Cell> cells = new ArrayList<>();
        for (int vi = 0; vi < verts.size(); vi++) {
            Vector3d n = verts.get(vi);
            Vector3d a = new Vector3d(n).cross(Math.abs(n.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0)).normalize();
            Vector3d b = new Vector3d(n).cross(a);
            List<Integer> ts = new ArrayList<>(around.get(vi));
            ts.sort((p, q) -> Double.compare(angle(cent[p], n, a, b), angle(cent[q], n, a, b)));
            Vector3d[] ring = new Vector3d[ts.size()];
            for (int i = 0; i < ring.length; i++) ring[i] = cent[ts.get(i)];
            double rnd = Math.abs(Math.sin(vi * 12.9898 + 78.233) * 43758.5453) % 1.0;
            cells.add(new Cell(n, ring, rnd));
        }
        return cells;
    }

    private static double angle(Vector3d p, Vector3d n, Vector3d a, Vector3d b) {
        Vector3d d = new Vector3d(p).sub(n);
        return Math.atan2(d.dot(b), d.dot(a));
    }
}
