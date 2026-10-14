package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.List;

/** The fields of one level, saved with it. Every change drops the level's lookup cache (Stasis.invalidate). */
public final class FieldData extends SavedData {
    public static final Codec<FieldData> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.INT.fieldOf("next_id").forGetter(d -> d.nextId),
        Field.CODEC.listOf().fieldOf("fields").forGetter(d -> d.fields),
        Codec.BOOL.optionalFieldOf("cottage", false).forGetter(d -> d.cottage),
        Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("clocks", java.util.Map.of()).forGetter(d -> {
            java.util.Map<String, Double> m = new java.util.HashMap<>();
            d.clocks.forEach((k, v) -> m.put(Integer.toString(k), v));
            return m;
        })
    ).apply(i, FieldData::new));

    public static final SavedDataType<FieldData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "fields"), FieldData::new, CODEC);

    private int nextId;
    private final List<Field> fields;
    /** The spawn cottage has been placed (or tried) in this level (SpawnCottage). */
    private boolean cottage;

    /** Field id -> its local time of day (LocalTime). */
    final java.util.Map<Integer, Double> clocks = new java.util.HashMap<>();

    public FieldData() {
        this(1, List.of(), false, java.util.Map.of());
    }

    private FieldData(int nextId, List<Field> fields, boolean cottage, java.util.Map<String, Double> clocks) {
        this.nextId = nextId;
        this.fields = new ArrayList<>(fields);
        this.snapshot = List.copyOf(fields);
        this.cottage = cottage;
        clocks.forEach((k, v) -> this.clocks.put(Integer.parseInt(k), v));
    }

    public boolean cottageDone() {
        return cottage;
    }

    public void markCottage() {
        cottage = true;
        setDirty();
    }

    /** Loaded instances, for other threads (the light engine asks Stasis.isRunning): the data storage isn't thread-safe. */
    private static final java.util.Map<ServerLevel, FieldData> LOADED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Main thread only. */
    public static FieldData get(ServerLevel level) {
        FieldData d = level.getDataStorage().computeIfAbsent(TYPE);
        if (LOADED.get(level) != d) LOADED.put(level, d);
        return d;
    }

    /** Any thread: the level's fields if they have been loaded (on the main thread), else null. */
    static List<Field> peek(ServerLevel level) {
        FieldData d = LOADED.get(level);
        return d == null ? null : d.snapshot;
    }

    /** An immutable copy of `fields`, renewed on every change (read from other threads). */
    private volatile List<Field> snapshot = List.of();

    public List<Field> fields() {
        return snapshot;
    }

    public Field add(ServerLevel level, double x, double y, double z, double radius) {
        return add(level, x, y, z, radius, false);
    }

    public Field add(ServerLevel level, double x, double y, double z, double radius, boolean column) {
        return add(level, x, y, z, radius, column, 1.0);
    }

    public Field add(ServerLevel level, double x, double y, double z, double radius, boolean column, double rate) {
        Field f = new Field(nextId++, x, y, z, radius, 0, 0, column, Field.clampRate(rate), level.getRandom().nextInt() & 0xFFFFFF, 0);
        fields.add(f);
        changed(level);
        return f;
    }

    /** Replace field `id` (radius, failing, collapse, rate changes); no-op if it's gone. */
    public void update(ServerLevel level, int id, java.util.function.UnaryOperator<Field> change) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).id() == id) {
                Field old = fields.get(i);
                Field f = change.apply(old);
                if (!f.equals(old)) {
                    var safe = f.radius() < old.radius() ? Freezing.running(level) : null;
                    fields.set(i, f);
                    changed(level);
                    if (safe != null) Freezing.graceIfStopped(level, safe);
                }
                return;
            }
        }
    }

    public java.util.Optional<Field> get(int id) {
        return fields.stream().filter(f -> f.id() == id).findFirst();
    }

    public boolean remove(ServerLevel level, int id) {
        var safe = Freezing.running(level);
        for (Field f : fields) if (f.id() == id) LocalTime.fieldGone(level, f);
        boolean removed = fields.removeIf(f -> f.id() == id);
        if (removed) {
            changed(level);
            Freezing.graceIfStopped(level, safe);
        }
        return removed;
    }

    public int clear(ServerLevel level) {
        var safe = Freezing.running(level);
        for (Field f : fields) LocalTime.fieldGone(level, f);
        int n = fields.size();
        fields.clear();
        changed(level);
        Freezing.graceIfStopped(level, safe);
        return n;
    }

    private void changed(ServerLevel level) {
        List<Field> old = snapshot;
        snapshot = List.copyOf(fields);
        setDirty();
        Stasis.invalidate(level);
        StasisNet.sendFields(level);
        Flames.fieldsChanged(level, old, snapshot);
    }
}
