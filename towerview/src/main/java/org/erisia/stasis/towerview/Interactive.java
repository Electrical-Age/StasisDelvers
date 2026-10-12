package org.erisia.stasis.towerview;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.erisia.stasis.towergen.Layout;
import org.erisia.stasis.towergen.Library;
import org.erisia.stasis.towergen.Mesh;
import org.erisia.stasis.towergen.MeshBuilder;
import org.erisia.stasis.towergen.TowerGen;
import org.erisia.stasis.towergen.SolverParams;
import org.erisia.stasis.towergen.VisualParams;
import org.erisia.stasis.towergen.WireReduction;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

/**
 * Interactive window (LWJGL 3: GLFW + legacy OpenGL 2.1 vertex arrays and fixed-function lighting, kept simple).
 * <ul>
 *   <li>left drag: orbit; right drag: move the target up / down; wheel: zoom;</li>
 *   <li>Q / A: q +/- 0.1; 1: q = 1; 3: q = 0.3; R: rebuild now; S: save a PNG with the software renderer;
 *   Esc: quit;</li>
 *   <li>the layout file, the visual parameter file and the solver file (--solver) are polled every 0.5 s and rebuilt on change (off the render
 *   thread).</li>
 * </ul>
 */
public final class Interactive {
    private final Path layoutPath, paramsPath;
    private Path solverPath;
    private volatile double q;
    private final TowerGen gen;
    private final AtomicReference<Built> pending = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "towergen"); t.setDaemon(true); return t; });
    private volatile boolean building;
    private long layoutMtime = -1, paramsMtime = -1, solverMtime = -1;

    private record Built(Mesh mesh, VisualParams vp, String title, double[] bounds, TowerGen.Result result) {}

    private Built cur;
    private double yaw = Math.toRadians(215), pitch = Math.toRadians(18), dist = 60, tgtY = 20;
    private double lastX, lastY;
    private boolean dragL, dragR, firstFrame = true;
    private int vCount;
    private FloatBuffer vbuf, nbuf, cbuf;

    /** Test hook: save the GL framebuffer to this PNG a few frames after the first mesh, then exit. */
    private Path snap;
    private int snapFrames = -1;

    private Interactive(Path layout, Path params, double q, TowerGen gen) {
        this.layoutPath = layout; this.paramsPath = params; this.q = q; this.gen = gen;
    }

    public static void run(List<String> pos, Map<String, String> opt) throws Exception {
        Path lp = Path.of(pos.get(0));
        Path pp = opt.containsKey("params") ? Path.of(opt.get("params")) : null;
        Library lib = opt.containsKey("lib") ? Library.load(Path.of(opt.get("lib"))) : Library.shared();
        Interactive it = new Interactive(lp, pp, Double.parseDouble(opt.getOrDefault("q", "1.0")), new TowerGen(lib));
        if (opt.containsKey("snap")) it.snap = Path.of(opt.get("snap"));
        if (opt.containsKey("solver")) it.solverPath = Path.of(opt.get("solver"));
        it.loop();
    }

    private void rebuild() {
        building = true;
        double qq = q;
        worker.submit(() -> {
            try {
                Layout l = Layout.read(layoutPath);
                VisualParams vp = VisualParams.load(paramsPath);
                SolverParams sp = solverPath != null ? SolverParams.load(solverPath, gen.c) : null;
                TowerGen.Result r = gen.build(l, qq, sp);
                Mesh m = new MeshBuilder(r.c, vp).build(r);
                WireReduction.Counts wc = WireReduction.Counts.of(r.tower);
                String title = String.format("towerview %s q%.1f | util %s vol %.0f | %d cables, %d stays, %d back-stays | statics %.0f ms pass %.0f ms | %d tris%s%s",
                        layoutPath.getFileName(), qq, r.analysis == null ? "mechanism" : String.format("%.3f", r.analysis.maxUtil),
                        r.info.vol, wc.cables(), wc.stays(), wc.backstays(), r.msStatics() + r.msReduce(), r.msPass(), m.tris,
                        l.violations().isEmpty() ? "" : " | RULES: " + l.violations(),
                        (r.warnings.isEmpty() ? "" : " | " + r.warnings.size() + " warnings")
                                + (r.refusal == null ? "" : " | REFUSED " + r.refusal.reason()));
                for (String w : r.warnings) System.err.println("warning: " + w);
                pending.set(new Built(m, vp, title, m.bounds(), r));
            } catch (Exception e) {
                System.err.println("rebuild failed: " + e);
                pending.set(new Built(null, null, "towerview: " + e.getMessage(), null, null));
            } finally {
                building = false;
            }
        });
    }

    private void pollFiles() {
        try {
            long lm = Files.getLastModifiedTime(layoutPath).toMillis();
            long pm = paramsPath != null && Files.exists(paramsPath) ? Files.getLastModifiedTime(paramsPath).toMillis() : 0;
            long sm = solverPath != null && Files.exists(solverPath) ? Files.getLastModifiedTime(solverPath).toMillis() : 0;
            if (lm != layoutMtime || pm != paramsMtime || sm != solverMtime) {
                layoutMtime = lm; paramsMtime = pm; solverMtime = sm;
                rebuild();
            }
        } catch (Exception e) {
            System.err.println("poll: " + e);
        }
    }

    private void upload(Built b) {
        Mesh m = b.mesh;
        vCount = 3 * m.tris;
        vbuf = BufferUtils.createFloatBuffer(9 * m.tris);
        nbuf = BufferUtils.createFloatBuffer(9 * m.tris);
        cbuf = BufferUtils.createFloatBuffer(9 * m.tris);
        VisualParams.Part[] parts = VisualParams.Part.values();
        vbuf.put(m.pos, 0, 9 * m.tris).flip();
        for (int t = 0; t < m.tris; t++) {
            double[] c = b.vp.colour(parts[m.part[t]]);
            boolean glow = parts[m.part[t]] == VisualParams.Part.GLOW;
            for (int k = 0; k < 3; k++) {
                nbuf.put(m.nrm[3 * t]).put(m.nrm[3 * t + 1]).put(m.nrm[3 * t + 2]);
                float s = glow ? 1.6f : 1f;                     // glow: overdriven so it reads as emissive
                cbuf.put((float) c[0] * s).put((float) c[1] * s).put((float) c[2] * s);
            }
        }
        nbuf.flip(); cbuf.flip();
    }

    private void loop() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed (no display?)");
        glfwWindowHint(GLFW_SAMPLES, 4);
        long win = glfwCreateWindow(1280, 800, "towerview", 0, 0);
        if (win == 0) throw new IllegalStateException("window creation failed");
        glfwMakeContextCurrent(win);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glfwSetCursorPosCallback(win, (w, x, y) -> {
            double dx = x - lastX, dy = y - lastY;
            lastX = x; lastY = y;
            if (dragL) { yaw += dx * 0.008; pitch = Math.max(-0.2, Math.min(1.45, pitch + dy * 0.008)); }
            if (dragR) tgtY = Math.max(0, tgtY - dy * dist * 0.002);
        });
        glfwSetMouseButtonCallback(win, (w, button, action, mods) -> {
            boolean down = action == GLFW_PRESS;
            if (button == GLFW_MOUSE_BUTTON_LEFT) dragL = down;
            if (button == GLFW_MOUSE_BUTTON_RIGHT) dragR = down;
        });
        glfwSetScrollCallback(win, (w, sx, sy) -> dist = Math.max(3, dist * Math.pow(0.9, sy)));
        glfwSetKeyCallback(win, (w, key, sc, action, mods) -> {
            if (action != GLFW_PRESS && action != GLFW_REPEAT) return;
            switch (key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(w, true);
                case GLFW_KEY_Q -> { q = Math.min(1.0, Math.round((q + 0.1) * 10) / 10.0); rebuild(); }
                case GLFW_KEY_A -> { q = Math.max(0.0, Math.round((q - 0.1) * 10) / 10.0); rebuild(); }
                case GLFW_KEY_1 -> { q = 1.0; rebuild(); }
                case GLFW_KEY_3 -> { q = 0.3; rebuild(); }
                case GLFW_KEY_R -> rebuild();
                case GLFW_KEY_S -> saveShot();
                default -> { }
            }
        });
        double lastPoll = 0;
        while (!glfwWindowShouldClose(win)) {
            double now = glfwGetTime();
            if (now - lastPoll > 0.5) { pollFiles(); lastPoll = now; }
            Built b = pending.getAndSet(null);
            if (b != null) {
                glfwSetWindowTitle(win, b.title);
                if (b.mesh != null) {
                    if (firstFrame) {
                        double h = b.bounds[4];
                        double w = Math.max(b.bounds[3] - b.bounds[0], b.bounds[5] - b.bounds[2]);
                        dist = Math.max(20, 1.2 * Math.max(h, w));
                        tgtY = 0.5 * h;
                        firstFrame = false;
                    }
                    cur = b;
                    upload(b);
                    if (snap != null && snapFrames < 0) snapFrames = 3;
                }
            }
            int[] fw = new int[1], fh = new int[1];
            glfwGetFramebufferSize(win, fw, fh);
            draw(fw[0], fh[0]);
            if (snapFrames > 0 && --snapFrames == 0) { writeSnap(fw[0], fh[0]); glfwSetWindowShouldClose(win, true); }
            glfwSwapBuffers(win);
            glfwPollEvents();
        }
        worker.shutdownNow();
        glfwDestroyWindow(win);
        glfwTerminate();
    }

    private double[] eye() {
        return new double[]{dist * Math.cos(pitch) * Math.cos(yaw), tgtY + dist * Math.sin(pitch), dist * Math.cos(pitch) * Math.sin(yaw)};
    }

    private void draw(int w, int h) {
        VisualParams vp = cur != null ? cur.vp : VisualParams.defaults();
        double[] sky = vp.skyHorizon();
        glViewport(0, 0, w, h);
        glClearColor((float) sky[0], (float) sky[1], (float) sky[2], 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        double near = 0.1, far = 2000, aspect = w / (double) Math.max(1, h);
        double top = near * Math.tan(Math.toRadians(vp.fovDeg()) / 2);
        glFrustum(-top * aspect, top * aspect, -top, top, near, far);
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        double[] e = eye(), t = {0, tgtY, 0};
        lookAt(e, t);
        // ground
        glDisable(GL_LIGHTING);
        if (vp.drawGround()) {
            double[] g = vp.groundColour();
            glColor3f((float) g[0], (float) g[1], (float) g[2]);
            glBegin(GL_QUADS);
            glVertex3f(-1500, -0.01f, -1500); glVertex3f(-1500, -0.01f, 1500); glVertex3f(1500, -0.01f, 1500); glVertex3f(1500, -0.01f, -1500);
            glEnd();
        }
        if (cur == null || vCount == 0) return;
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_COLOR_MATERIAL);
        glColorMaterial(GL_FRONT_AND_BACK, GL_AMBIENT_AND_DIFFUSE);
        glLightModeli(GL_LIGHT_MODEL_TWO_SIDE, GL_TRUE);
        double az = Math.toRadians(vp.sunAzimuth()), el = Math.toRadians(vp.sunElevation());
        float[] sun = {(float) (Math.cos(el) * Math.cos(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.sin(az)), 0f};
        glLightfv(GL_LIGHT0, GL_POSITION, sun);
        float a = (float) vp.ambient();
        glLightfv(GL_LIGHT0, GL_AMBIENT, new float[]{a, a, a, 1});
        glLightfv(GL_LIGHT0, GL_DIFFUSE, new float[]{1 - a, 1 - a, 1 - a, 1});
        glEnableClientState(GL_VERTEX_ARRAY);
        glEnableClientState(GL_NORMAL_ARRAY);
        glEnableClientState(GL_COLOR_ARRAY);
        glVertexPointer(3, GL_FLOAT, 0, vbuf);
        glNormalPointer(GL_FLOAT, 0, nbuf);
        glColorPointer(3, GL_FLOAT, 0, cbuf);
        glDrawArrays(GL_TRIANGLES, 0, vCount);
        glDisableClientState(GL_VERTEX_ARRAY);
        glDisableClientState(GL_NORMAL_ARRAY);
        glDisableClientState(GL_COLOR_ARRAY);
        glDisable(GL_LIGHTING);
    }

    private static void lookAt(double[] e, double[] t) {
        double[] f = norm(new double[]{t[0] - e[0], t[1] - e[1], t[2] - e[2]});
        double[] s = norm(cross(f, new double[]{0, 1, 0}));
        double[] u = cross(s, f);
        float[] m = {
                (float) s[0], (float) u[0], (float) -f[0], 0,
                (float) s[1], (float) u[1], (float) -f[1], 0,
                (float) s[2], (float) u[2], (float) -f[2], 0,
                0, 0, 0, 1};
        glMultMatrixf(m);
        glTranslated(-e[0], -e[1], -e[2]);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] norm(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    private void writeSnap(int w, int h) {
        java.nio.ByteBuffer px = BufferUtils.createByteBuffer(w * h * 4);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = 4 * ((h - 1 - y) * w + x);
                img.setRGB(x, y, ((px.get(i) & 255) << 16) | ((px.get(i + 1) & 255) << 8) | (px.get(i + 2) & 255));
            }
        try {
            javax.imageio.ImageIO.write(img, "png", snap.toFile());
            System.out.println("snap " + snap.toAbsolutePath() + " (" + glGetString(GL_RENDERER) + ")");
        } catch (Exception e) {
            System.err.println("snap failed: " + e);
        }
    }

    private void saveShot() {
        Built b = cur;
        if (b == null) return;
        double[] e = eye();
        Raster.Camera cam = new Raster.Camera(e, new double[]{0, tgtY, 0}, b.vp.fovDeg());
        worker.submit(() -> {
            try {
                var img = new Raster(1600, 1000, 2, b.vp).render(b.mesh, cam, b.title);
                Path out = Path.of(String.format("towerview_%d.png", System.currentTimeMillis() / 1000));
                javax.imageio.ImageIO.write(img, "png", out.toFile());
                System.out.println("saved " + out.toAbsolutePath());
            } catch (Exception ex) {
                System.err.println("save failed: " + ex);
            }
        });
    }
}
