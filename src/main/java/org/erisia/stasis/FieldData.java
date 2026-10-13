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
        Codec.BOOL.optionalFieldOf("cottage", false).forGetter(d -> d.cottage)
    ).apply(i, FieldData::new));

    public static final SavedDataType<FieldData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "fields"), FieldData::new, CODEC);

    private int nextId;
    private final List<Field> fields;
    /** The spawn cottage has been placed (or tried) in this level (SpawnCottage). */
    private boolean cottage;

    public FieldData() {
        this(1, List.of(), false);
    }

    private FieldData(int nextId, List<Field> fields, boolean cottage) {
        this.nextId = nextId;
        this.fields = new ArrayList<>(fields);
        this.cottage = cottage;
    }

    public boolean cottageDone() {
        return cottage;
    }

    public void markCottage() {
        cottage = true;
        setDirty();
    }

    public static FieldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Field> fields() {
        return List.copyOf(fields);
    }

    public Field add(ServerLevel level, double x, double y, double z, double radius) {
        return add(level, x, y, z, radius, false);
    }

    public Field add(ServerLevel level, double x, double y, double z, double radius, boolean column) {
        return add(level, x, y, z, radius, column, 1.0);
    }

    public Field add(ServerLevel level, double x, double y, double z, double radius, boolean column, double rate) {
        Field f = new Field(nextId++, x, y, z, radius, 0, 0, column, Field.clampRate(rate));
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
        boolean removed = fields.removeIf(f -> f.id() == id);
        if (removed) {
            changed(level);
            Freezing.graceIfStopped(level, safe);
        }
        return removed;
    }

    public int clear(ServerLevel level) {
        var safe = Freezing.running(level);
        int n = fields.size();
        fields.clear();
        changed(level);
        Freezing.graceIfStopped(level, safe);
        return n;
    }

    private void changed(ServerLevel level) {
        setDirty();
        Stasis.invalidate(level);
        StasisNet.sendFields(level);
    }
}
