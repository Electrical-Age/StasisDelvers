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
        Codec.INT.optionalFieldOf("deep_seed", 0).forGetter(d -> d.deepSeed),
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
        this(1, List.of(), false, 0, java.util.Map.of());
    }

    /**
     * The level's deep seed (Deeps), 24 bits, saved; clients get it with the fields. A new level's comes from the world
     * seed and the dimension ({@link #derive}; Baughn: a same-seed reset of Incognito put the rifts elsewhere, as the seed
     * was drawn at random), so a world made again from its seed has its rifts where they were. Levels that already have
     * one keep it.
     */
    private int deepSeed;

    public int deepSeed() {
        if (deepSeed == 0) {   // (only without a level to derive it from: get() derives it first)
            deepSeed = 1 + new java.util.Random().nextInt(0xFFFFFE);
            setDirty();
        }
        return deepSeed;
    }

    /** The deep seed of a world seed and dimension: 1..0xFFFFFE (0 means "not loaded yet"), well mixed (SplitMix64). */
    public static int derive(long worldSeed, String dimension) {
        long z = worldSeed ^ (dimension.hashCode() * 0x9E3779B97F4A7C15L) ^ 0x5741534953L;   // ("stasis")
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return 1 + (int) Math.floorMod(z, 0xFFFFFEL);
    }

    private FieldData(int nextId, List<Field> fields, boolean cottage, int deepSeed, java.util.Map<String, Double> clocks) {
        this.deepSeed = deepSeed;
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
        if (d.deepSeed == 0) {
            d.deepSeed = derive(level.getSeed(), level.dimension().identifier().toString());
            d.setDirty();
        }
        if (LOADED.get(level) != d) LOADED.put(level, d);
        return d;
    }

    /** Tests: a known deep seed. */
    public void testDeepSeed(int seed) {
        deepSeed = seed;
    }

    /** Any thread: the level's deep seed if its data is loaded (made at level load), else 0. */
    static int peekDeepSeed(ServerLevel level) {
        FieldData d = LOADED.get(level);
        return d == null ? 0 : d.deepSeed;
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
        Field f = new Field(nextId++, x, y, z, radius, column).withRate(rate).withSeed(level.getRandom().nextInt());
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
                    var safe = f.radius() < old.radius() || f.stretch().lessThan(old.stretch()) ? Freezing.running(level) : null;
                    fields.set(i, f);
                    changed(level);
                    if (safe != null) Freezing.graceIfStopped(level, safe);
                }
                return;
            }
        }
    }

    /**
     * Fields bound to an entity (field id -> its network id): a quern vehicle's (λ-Nero 2026-11-09, notes/quern-vehicles).
     * Not saved: the vehicle binds its field again on its first tick after a load. Clients get the map with the fields
     * and move a bound field with the entity themselves every tick (ClientFields.follow), so a moving field costs no
     * packets: a boat's own client sees the water ahead thawed where its boat is now, not where the server last said.
     */
    private final java.util.Map<Integer, Integer> bound = new java.util.LinkedHashMap<>();
    /** Bound fields moved since the last full send / flame relight (tickMoves). */
    private boolean movedSinceSend, movedSinceFlames;
    /** The snapshot the flames were last relit for. */
    private List<Field> flamesSaw = List.of();

    /** The fields bound to an entity now (main thread). */
    public List<Field> boundFields() {
        if (bound.isEmpty()) return List.of();
        List<Field> out = new ArrayList<>();
        for (Field f : snapshot) if (bound.containsKey(f.id())) out.add(f);
        return out;
    }

    /** Main thread: the level's data if loaded, else null (no creation; for per-entity checks). */
    static FieldData loaded(ServerLevel level) {
        return LOADED.get(level);
    }

    public java.util.Map<Integer, Integer> bound() {
        return java.util.Collections.unmodifiableMap(bound);
    }

    /** Bind field id to entity (its owner moves it with move()); sends the fields. */
    public void bind(ServerLevel level, int id, net.minecraft.world.entity.Entity entity) {
        if (get(id).isEmpty()) return;
        Integer was = bound.put(id, entity.getId());
        if (was == null || was != entity.getId()) changed(level);
    }

    /**
     * Move field id to (x, y, z) (a vehicle's, each tick it moves): the snapshot and the rule's cache, but no packet (the
     * clients follow the entity), no flame relight (tickMoves catches up, throttled). A player safe in it before and
     * stopped after gets the usual grace, as when a field shrinks (a cart driving away from someone beside it).
     */
    public void move(ServerLevel level, int id, double x, double y, double z) {
        for (int i = 0; i < fields.size(); i++) {
            Field old = fields.get(i);
            if (old.id() != id) continue;
            if (old.x() == x && old.y() == y && old.z() == z) return;
            var safe = Freezing.running(level);
            fields.set(i, old.withCentre(x, y, z));
            snapshot = List.copyOf(fields);
            setDirty();
            Stasis.invalidate(level);
            movedSinceSend = movedSinceFlames = true;
            Freezing.graceIfStopped(level, safe);
            return;
        }
    }

    /** Ticks between catching up on moved fields: flames relit, and a full send (for players not tracking the vehicle). */
    static final int FLAMES_EVERY = 5, SEND_EVERY = 20;

    /** LevelTickEvent.Post (server): catch up on bound fields that moved. */
    static void tickMoves(net.neoforged.neoforge.event.tick.LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        FieldData d = LOADED.get(level);
        if (d == null || d.bound.isEmpty() && !d.movedSinceSend && !d.movedSinceFlames) return;
        long t = level.getGameTime();
        if (d.movedSinceFlames && t % FLAMES_EVERY == 0) {
            d.movedSinceFlames = false;
            Flames.fieldsChanged(level, d.flamesSaw, d.snapshot);
            d.flamesSaw = d.snapshot;
        }
        if (d.movedSinceSend && t % SEND_EVERY == 0) {
            d.movedSinceSend = false;
            StasisNet.sendFields(level);
        }
    }

    public java.util.Optional<Field> get(int id) {
        return fields.stream().filter(f -> f.id() == id).findFirst();
    }

    public boolean remove(ServerLevel level, int id) {
        var safe = Freezing.running(level);
        for (Field f : fields) if (f.id() == id) LocalTime.fieldGone(level, f);
        boolean removed = fields.removeIf(f -> f.id() == id);
        bound.remove(id);
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
        bound.clear();
        changed(level);
        Freezing.graceIfStopped(level, safe);
        return n;
    }

    private void changed(ServerLevel level) {
        List<Field> old = snapshot;
        snapshot = List.copyOf(fields);
        setDirty();
        Stasis.invalidate(level);
        bound.keySet().removeIf(id -> get(id).isEmpty());
        movedSinceSend = false;
        StasisNet.sendFields(level);
        Flames.fieldsChanged(level, flamesSaw.isEmpty() || !movedSinceFlames ? old : flamesSaw, snapshot);
        movedSinceFlames = false;
        flamesSaw = snapshot;
    }
}
