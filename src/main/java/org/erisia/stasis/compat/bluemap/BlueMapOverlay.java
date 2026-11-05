package org.erisia.stasis.compat.bluemap;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.erisia.stasis.Field;
import org.erisia.stasis.FieldData;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisDelvers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Stopped land and stasis fields on BlueMap's web map (λ-Nero, Baughn OK 2026-11-04, Discord 1567515330270462003): a
 * toggleable "Stasis" marker set on each map of a stopped dimension. Only what is now (Nero: no predictions of the
 * spread). Server side only, through BlueMap's API; nothing happens without BlueMap.
 * <p>
 * Stopped land is the Xaero overlay's rule (compat/xaero/StoppedLand): a chunk counts as stopped when Stasis.rateAtLand
 * at its centre, at sea level, is 0. The land is sampled over the world border (or {@link #MAX_CHUNKS} round 0,0) off the
 * main thread (rateAtLand is safe there) every {@link #LAND_SECONDS}, and the markers are replaced only when the grid
 * changed. Fields are redrawn every {@link #FIELD_SECONDS} on the main thread (a few dozen shapes).
 */
public final class BlueMapOverlay {
    private BlueMapOverlay() {
    }

    static final String SET_ID = "stasisdelvers";
    static final int FIELD_SECONDS = 15, LAND_SECONDS = 120;
    /** At most this many chunks per side are sampled (17000-block border: 1063). */
    static final int MAX_CHUNKS = 2048;
    static final Color LAND_FILL = new Color(0x70, 0x70, 0x70, 0.45f), LAND_LINE = new Color(0x50, 0x50, 0x50, 0.8f);

    private static volatile BlueMapAPI api;
    private static int ticks;
    private static final Map<String, Integer> landHash = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<?>> landJobs = new HashMap<>();

    /** From the mod constructor, only when BlueMap is loaded. */
    public static void register() {
        BlueMapAPI.onEnable(a -> {
            api = a;
            landHash.clear();
            ticks = 0;
            installShells(a);
        });
        BlueMapAPI.onDisable(a -> api = null);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, BlueMapOverlay::tick);
    }

    private static void tick(ServerTickEvent.Post e) {
        BlueMapAPI a = api;
        if (a == null) return;
        int t = ticks++;
        boolean fields = t % (FIELD_SECONDS * 20) == 0, land = t % (LAND_SECONDS * 20) == 20;
        if (!fields && !land) return;
        try {
            for (ServerLevel level : e.getServer().getAllLevels()) {
                List<BlueMapMap> maps = a.getWorld(level).map(w -> List.copyOf(w.getMaps())).orElse(List.of());
                if (maps.isEmpty()) continue;
                if (!Stasis.stops(level)) {
                    for (BlueMapMap m : maps) m.getMarkerSets().remove(SET_ID);
                    landHash.remove(level.dimension().identifier().toString());
                    continue;
                }
                List<MarkerSet> sets = new ArrayList<>(maps.size());
                for (BlueMapMap m : maps) sets.add(m.getMarkerSets().computeIfAbsent(SET_ID, k -> MarkerSet.builder()
                    .label("Stasis: stopped land and fields").toggleable(true).defaultHidden(false).build()));
                if (fields) drawFields(level, sets);
                String key = level.dimension().identifier().toString();
                CompletableFuture<?> job = landJobs.get(key);
                if (land && (job == null || job.isDone())) landJobs.put(key, sampleLand(e.getServer(), level, maps, sets));
            }
        } catch (RuntimeException ex) {
            StasisDelvers.LOG.warn("BlueMap overlay: {}", ex.toString());
        }
    }

    // --- fields ---

    /**
     * Writes the shell script (assets/stasisdelvers/bluemap/stasis-shells.js: the fields as hexagon-gridded spheroids, as
     * in game; Baughn) into BlueMap's web root and registers it. The file name carries a hash of its content, since the
     * web root is served with long cache headers.
     */
    private static void installShells(BlueMapAPI a) {
        try (var in = BlueMapOverlay.class.getResourceAsStream("/assets/stasisdelvers/bluemap/stasis-shells.js")) {
            if (in == null) return;
            byte[] js = in.readAllBytes();
            String hash = String.format("%08x", Arrays.hashCode(js));
            String rel = "assets/stasisdelvers/stasis-shells-" + hash + ".js";
            java.nio.file.Path file = a.getWebApp().getWebRoot().resolve(rel);
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, js);
            a.getWebApp().registerScript(rel);
        } catch (java.io.IOException | RuntimeException ex) {
            StasisDelvers.LOG.warn("BlueMap overlay: could not install the shell script: {}", ex.toString());
        }
    }

    /** The field's shape for the shell script: x,y,z,r,e,s,w,n,column,amber,dropout,collapse,side,rate. */
    static String shellData(Field f) {
        var st = f.stretch();
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%d,%.4f,%.4f,%.4f,%.4f,%.3f",
            f.x(), f.y(), f.z(), f.radius(), st.e(), st.s(), st.w(), st.n(), f.column() ? 1 : 0, f.amber(), f.dropout(),
            f.collapse(), f.side(), f.rate());
    }

    private static void drawFields(ServerLevel level, List<MarkerSet> sets) {
        Map<String, Marker> want = new HashMap<>();
        for (Field f : FieldData.get(level).fields()) {
            if (f.collapse() >= 1) continue;
            String state = f.collapse() > 0 ? "collapsing" : f.failing() > 0 ? "failing" : "running";
            Color c = switch (state) {
                case "collapsing" -> new Color(0xE0, 0x40, 0x30, 0.08f);   // (faint: the shell script draws the field)
                case "failing" -> new Color(0xF0, 0xA0, 0x30, 0.08f);
                default -> new Color(0x60, 0xC8, 0xFF, 0.06f);
            };
            Color line = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0.9f);
            String rate = String.format(java.util.Locale.ROOT, f.rate() > 1 ? "%.1f× (accelerated)" : "%.1f×", f.rate());
            want.put("field-" + f.id(), ShapeMarker.builder()
                .label("Stasis field")
                .detail("<b>Stasis field</b><br>Radius " + Math.round(f.radius()) + " blocks<br>Time runs at " + rate
                    + "<br>" + state.substring(0, 1).toUpperCase() + state.substring(1)
                    + "<span hidden data-shell=\"" + shellData(f) + "\"></span>")
                .shape(fieldShape(f), (float) f.y())
                .position(f.x(), f.y(), f.z())
                .depthTestEnabled(false)
                .lineWidth(2).lineColor(line).fillColor(c)
                .build());
        }
        for (MarkerSet s : sets) replace(s, "field-", want);
    }

    /** The field's footprint: its ellipse in quarters, stretched east/south/west/north by the tower's booms (Field.Stretch). */
    static Shape fieldShape(Field f) {
        int n = 72;
        Vector2d[] pts = new Vector2d[n];
        var st = f.stretch();
        for (int k = 0; k < n; k++) {
            double a = 2 * Math.PI * k / n, cx = Math.cos(a), sz = Math.sin(a);
            pts[k] = new Vector2d(f.x() + f.radius() * cx * (cx >= 0 ? st.e() : st.w()), f.z() + f.radius() * sz * (sz >= 0 ? st.s() : st.n()));
        }
        return new Shape(pts);
    }

    // --- stopped land ---

    /** Blocks per pixel of the stopped-land mask the shell script paints onto the terrain. */
    static final int MASK_CELL = 8;
    static final String MASK_ASSET = "stasisdelvers-land.png";

    private static CompletableFuture<?> sampleLand(MinecraftServer srv, ServerLevel level, List<BlueMapMap> maps, List<MarkerSet> sets) {
        WorldBorder b = level.getWorldBorder();
        int cx0 = clampChunk(b.getMinX()), cz0 = clampChunk(b.getMinZ());
        int cx1 = clampChunk(b.getMaxX() - 1), cz1 = clampChunk(b.getMaxZ() - 1);
        int w = cx1 - cx0 + 1, h = cz1 - cz0 + 1;
        int mw = w * 16 / MASK_CELL, mh = h * 16 / MASK_CELL, x0 = cx0 << 4, z0 = cz0 << 4;
        int y = level.getSeaLevel();
        String key = level.dimension().identifier().toString();
        return CompletableFuture.runAsync(() -> {
            // the mask: one sample per MASK_CELL blocks (the script smooths it on the terrain); the fallback polygons take
            // every other sample (one per chunk)
            boolean[] m = new boolean[mw * mh];
            for (int j = 0; j < mh; j++)
                for (int i = 0; i < mw; i++)
                    m[i + j * mw] = Stasis.rateAtLand(level, x0 + i * MASK_CELL + MASK_CELL / 2, y, z0 + j * MASK_CELL + MASK_CELL / 2) <= 0;
            int hash = Arrays.hashCode(m);
            Integer was = landHash.get(key);
            if (was != null && was == hash) return;
            int step = 16 / MASK_CELL;
            boolean[] g = new boolean[w * h];
            for (int j = 0; j < h; j++)
                for (int i = 0; i < w; i++) g[i + j * w] = m[(i * step + step / 2) + (j * step + step / 2) * mw];
            List<GridOutline.Polygon> ps = GridOutline.trace(g, w, h);
            Map<String, Marker> want = new HashMap<>();
            int k = 0;
            for (GridOutline.Polygon p : ps) {
                Shape[] holes = p.holes().stream().map(r -> shape(r, cx0, cz0)).toArray(Shape[]::new);
                Shape outer = shape(p.outer(), cx0, cz0);
                want.put("land-" + k++, ShapeMarker.builder()
                    .label("Stopped land")
                    .shape(outer, y)
                    .holes(holes)
                    .centerPosition()
                    .depthTestEnabled(false)
                    .lineWidth(1).lineColor(LAND_LINE).fillColor(LAND_FILL)
                    .listed(false)
                    .build());
            }
            byte[] png = maskPng(m, mw, mh);
            Map<BlueMapMap, String> urls = new HashMap<>();
            for (BlueMapMap map : maps) {
                try (var out = map.getAssetStorage().writeAsset(MASK_ASSET)) {
                    out.write(png);
                    urls.put(map, map.getAssetStorage().getAssetUrl(MASK_ASSET));
                } catch (java.io.IOException ex) {
                    StasisDelvers.LOG.warn("BlueMap overlay: could not write the land mask for {}: {}", map.getId(), ex.toString());
                }
            }
            srv.execute(() -> {   // (marker maps are concurrent, but keep the swap in one place with the fields')
                for (int n = 0; n < maps.size(); n++) {
                    replace(sets.get(n), "land-", want);
                    String url = urls.get(maps.get(n));
                    if (url != null) sets.get(n).put("mask-land", maskMarker(url, hash, x0, z0, mw, mh, y));
                }
                landHash.put(key, hash);
            });
            StasisDelvers.LOG.info("BlueMap overlay: {} stopped land in {} shapes ({}x{} chunks), mask {}x{} ({} KB)", key,
                ps.size(), w, h, mw, mh, png.length / 1024);
        }).exceptionally(ex -> {
            StasisDelvers.LOG.warn("BlueMap overlay: sampling {} failed: {}", key, ex.toString());
            return null;
        });
    }

    /** The mask as a grey PNG: 255 where time is stopped. */
    static byte[] maskPng(boolean[] m, int w, int h) {
        var img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        byte[] px = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int k = 0; k < px.length; k++) px[k] = m[k] ? (byte) 255 : 0;
        var bytes = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(img, "png", bytes);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    /**
     * An invisible marker telling the shell script where the mask is: url (with the grid hash, the asset is cached),
     * its first block corner, blocks per pixel and size, and sea level.
     */
    static Marker maskMarker(String url, int hash, int x0, int z0, int w, int h, int y) {
        String data = url + "?v=" + Integer.toHexString(hash) + "," + x0 + "," + z0 + "," + MASK_CELL + "," + w + "," + h;
        return de.bluecolored.bluemap.api.markers.HtmlMarker.builder()
            .label("Stopped land (mask)")
            .position(x0, y, z0)
            .html("<div hidden data-stasis-mask=\"" + data + "\"></div>")
            .listed(false)
            .build();
    }

    private static int clampChunk(double block) {
        return Math.clamp(((int) Math.floor(block)) >> 4, -MAX_CHUNKS / 2, MAX_CHUNKS / 2 - 1);
    }

    private static Shape shape(GridOutline.Ring r, int cx0, int cz0) {
        Vector2d[] pts = new Vector2d[r.size()];
        for (int k = 0; k < r.size(); k++) pts[k] = new Vector2d((cx0 + r.x()[k]) * 16.0, (cz0 + r.z()[k]) * 16.0);
        return new Shape(pts);
    }

    /** Puts the wanted markers with this prefix into the set and drops the others with it. */
    private static void replace(MarkerSet s, String prefix, Map<String, Marker> want) {
        s.getMarkers().keySet().removeIf(id -> id.startsWith(prefix) && !want.containsKey(id));
        s.getMarkers().putAll(want);
    }
}
