package org.erisia.stasis;

import java.util.List;

/** The fields of the level the client is in, as last sent by the server (StasisNet.Fields). */
public final class ClientFields {
    private ClientFields() {
    }

    private static volatile List<Field> fields = List.of();
    private static volatile int deepSeed;

    public static int deepSeed() {
        return deepSeed;
    }

    static void deepSeed(int s) {
        deepSeed = s;
    }

    /** Is the point inside a (sphere) field's shield? (Weather stays out: WeatherEffectRendererMixin, ClientLevelMixin.) */
    public static boolean underShield(double x, double y, double z) {
        for (Field f : fields()) if (!f.column() && f.containsPoint(x, y, z)) return true;
        return false;
    }

    public static List<Field> fields() {
        return fields;
    }

    /** Client code to tell when the fields change (old, now): chunk meshing re-meshes for frozen textures (StasisClient). */
    public static volatile java.util.function.BiConsumer<List<Field>, List<Field>> onChange = (a, b) -> {
    };

    /** Fields bound to an entity (field id -> entity network id), as last sent (FieldData.bound). */
    private static volatile java.util.Map<Integer, Integer> bound = java.util.Map.of();

    static void bind(List<Integer> fieldIds, List<Integer> entityIds) {
        java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < Math.min(fieldIds.size(), entityIds.size()); i++) m.put(fieldIds.get(i), entityIds.get(i));
        bound = m;
    }

    /** The fields as they stand now after the last follow(): those the last remesh was asked for (FrozenSprites). */
    private static List<Field> meshed = List.of();
    private static long followTicks;
    /** Client ticks between re-meshes for bound fields that moved (frozen textures round a moving quern vehicle). */
    public static final int FOLLOW_MESH_EVERY = 4;

    /**
     * Each client tick (StasisClient): move each bound field to its entity, where this client has it, so the rule
     * (stilled water, frozen blocks) follows a quern boat or cart on this side with no lag. The list is swapped whole
     * (meshing threads read it without a lock). Frozen textures re-mesh every FOLLOW_MESH_EVERY ticks for what moved.
     */
    public static void follow(net.minecraft.world.level.Level level) {
        java.util.Map<Integer, Integer> b = bound;
        if (b.isEmpty() || level == null) return;
        List<Field> cur = fields;
        List<Field> next = null;
        for (int i = 0; i < cur.size(); i++) {
            Field f = cur.get(i);
            Integer eid = b.get(f.id());
            if (eid == null) continue;
            var e = level.getEntity(eid);
            if (e == null) continue;
            if (e.getX() == f.x() && e.getY() == f.y() && e.getZ() == f.z()) continue;
            if (next == null) next = new java.util.ArrayList<>(cur);
            next.set(i, f.withCentre(e.getX(), e.getY(), e.getZ()));
        }
        followTicks++;
        if (next != null) {
            fields = List.copyOf(next);
            Stasis.invalidate(level);
        }
        if (followTicks % FOLLOW_MESH_EVERY == 0 && meshed != fields) {
            List<Field> old = meshed;
            meshed = fields;
            Flames.fieldsChanged(level, old, meshed);
            onChange.accept(old, meshed);
        }
    }

    static void set(List<Field> f, net.minecraft.world.level.Level level) {
        boolean newLevel = Flames.clientLevel(level);
        List<Field> old = newLevel ? List.of() : fields;
        fields = List.copyOf(f);
        if (level != null) {
            Stasis.invalidate(level);
            Flames.fieldsChanged(level, old, fields);
            onChange.accept(old, fields);
            meshed = fields;
        }
    }
}
