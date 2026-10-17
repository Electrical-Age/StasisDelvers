package org.erisia.stasis.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.erisia.stasis.TowerBlockEntity;
import org.joml.Vector3d;

/**
 * The frozen remnants of collapsed towers on this client (TowerBlockEntity.Remnant): StasisPost draws their hexes and
 * mist, BoltRenderer their bolts, held mid-strike.
 */
public final class Remnants {
    private Remnants() {
    }

    record Entry(TowerBlockEntity.Remnant remnant, List<List<Vector3d[]>> bolts) {
    }

    static final Map<BlockPos, Entry> BY_TOWER = new HashMap<>();

    static synchronized void updated(TowerBlockEntity be) {
        var r = be.remnant();
        if (r == null) {
            BY_TOWER.remove(be.getBlockPos());
            return;
        }
        Entry old = BY_TOWER.get(be.getBlockPos());
        if (old != null && old.remnant().equals(r)) return;
        List<List<Vector3d[]>> bolts = new ArrayList<>();
        // only the trunk thinned: thinned branches (5 points) read as long dead-straight lines (AdventurAgent)
        for (var b : r.bolts()) {
            List<Vector3d[]> st = new ArrayList<>(BoltRenderer.strokes(b));
            st.set(0, thin(st.get(0)));
            bolts.add(st);
        }
        BY_TOWER.put(be.getBlockPos().immutable(), new Entry(r, bolts));
    }

    static synchronized void removed(TowerBlockEntity be) {
        BY_TOWER.remove(be.getBlockPos());
    }

    static synchronized void clear() {
        BY_TOWER.clear();
    }

    static synchronized List<Map.Entry<BlockPos, TowerBlockEntity.Remnant>> all() {
        List<Map.Entry<BlockPos, TowerBlockEntity.Remnant>> l = new ArrayList<>();
        for (var e : BY_TOWER.entrySet()) l.add(Map.entry(e.getKey(), e.getValue().remnant()));
        return l;
    }

    /** The frozen bolts of remnants within `range` of the camera. */
    static synchronized List<List<Vector3d[]>> bolts(net.minecraft.world.phys.Vec3 cam, double range) {
        List<List<Vector3d[]>> l = new ArrayList<>();
        for (var e : BY_TOWER.entrySet())
            if (e.getKey().distToCenterSqr(cam) < range * range) l.addAll(e.getValue().bolts());
        return l;
    }

    /** Every other point (the ends kept): a held trunk costs the shader half as much (~48 points a held bolt). */
    static Vector3d[] thin(Vector3d[] p) {
        List<Vector3d> l = new ArrayList<>();
        for (int i = 0; i < p.length; i += 2) l.add(p[i]);
        if ((p.length - 1) % 2 != 0) l.add(p[p.length - 1]);
        return l.toArray(new Vector3d[0]);
    }
}
