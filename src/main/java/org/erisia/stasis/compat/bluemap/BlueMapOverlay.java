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
                if (land && (job == null || job.isDone())) landJobs.put(key, sampleLand(e.getServer(), level, sets));
            }
        } catch (RuntimeException ex) {
            StasisDelvers.LOG.warn("BlueMap overlay: {}", ex.toString());
        }
    }

    // --- fields ---

    private static void drawFields(ServerLevel level, List<MarkerSet> sets) {
        Map<String, Marker> want = new HashMap<>();
        for (Field f : FieldData.get(level).fields()) {
            if (f.collapse() >= 1) continue;
            String state = f.collapse() > 0 ? "collapsing" : f.failing() > 0 ? "failing" : "running";
            Color c = switch (state) {
                case "collapsing" -> new Color(0xE0, 0x40, 0x30, 0.25f);
                case "failing" -> new Color(0xF0, 0xA0, 0x30, 0.25f);
                default -> new Color(0x60, 0xC8, 0xFF, 0.2f);
            };
            Color line = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0.9f);
            String rate = f.rate() > 1 ? String.format("%.1f× (accelerated)", f.rate()) : String.format("%.1f×", f.rate());
            want.put("field-" + f.id(), ShapeMarker.builder()
                .label("Stasis field")
                .detail("<b>Stasis field</b><br>Radius " + Math.round(f.radius()) + " blocks<br>Time runs at " + rate
                    + "<br>" + state.substring(0, 1).toUpperCase() + state.substring(1))
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

    private static CompletableFuture<?> sampleLand(MinecraftServer srv, ServerLevel level, List<MarkerSet> sets) {
        WorldBorder b = level.getWorldBorder();
        int cx0 = clampChunk(b.getMinX()), cz0 = clampChunk(b.getMinZ());
        int cx1 = clampChunk(b.getMaxX() - 1), cz1 = clampChunk(b.getMaxZ() - 1);
        int w = cx1 - cx0 + 1, h = cz1 - cz0 + 1;
        int y = level.getSeaLevel();
        String key = level.dimension().identifier().toString();
        return CompletableFuture.runAsync(() -> {
            boolean[] g = new boolean[w * h];
            for (int j = 0; j < h; j++)
                for (int i = 0; i < w; i++)
                    g[i + j * w] = Stasis.rateAtLand(level, ((cx0 + i) << 4) + 8, y, ((cz0 + j) << 4) + 8) <= 0;
            int hash = Arrays.hashCode(g);
            Integer was = landHash.get(key);
            if (was != null && was == hash) return;
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
            srv.execute(() -> {   // (marker maps are concurrent, but keep the swap in one place with the fields')
                for (MarkerSet s : sets) replace(s, "land-", want);
                landHash.put(key, hash);
            });
            StasisDelvers.LOG.info("BlueMap overlay: {} stopped land in {} shapes ({}x{} chunks)", key, ps.size(), w, h);
        }).exceptionally(ex -> {
            StasisDelvers.LOG.warn("BlueMap overlay: sampling {} failed: {}", key, ex.toString());
            return null;
        });
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
