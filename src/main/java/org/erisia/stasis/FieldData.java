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
        Field.CODEC.listOf().fieldOf("fields").forGetter(d -> d.fields)
    ).apply(i, FieldData::new));

    public static final SavedDataType<FieldData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "fields"), FieldData::new, CODEC);

    private int nextId;
    private final List<Field> fields;

    public FieldData() {
        this(1, List.of());
    }

    private FieldData(int nextId, List<Field> fields) {
        this.nextId = nextId;
        this.fields = new ArrayList<>(fields);
    }

    public static FieldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Field> fields() {
        return List.copyOf(fields);
    }

    public Field add(ServerLevel level, double x, double y, double z, double radius) {
        Field f = new Field(nextId++, x, y, z, radius);
        fields.add(f);
        changed(level);
        return f;
    }

    public boolean remove(ServerLevel level, int id) {
        boolean removed = fields.removeIf(f -> f.id() == id);
        if (removed) changed(level);
        return removed;
    }

    public int clear(ServerLevel level) {
        int n = fields.size();
        fields.clear();
        changed(level);
        return n;
    }

    private void changed(ServerLevel level) {
        setDirty();
        Stasis.invalidate(level);
        StasisNet.sendFields(level);
    }
}
