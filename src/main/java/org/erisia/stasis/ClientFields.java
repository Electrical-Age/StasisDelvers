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

    public static List<Field> fields() {
        return fields;
    }

    static void set(List<Field> f, net.minecraft.world.level.Level level) {
        boolean newLevel = Flames.clientLevel(level);
        List<Field> old = newLevel ? List.of() : fields;
        fields = List.copyOf(f);
        if (level != null) {
            Stasis.invalidate(level);
            Flames.fieldsChanged(level, old, fields);
        }
    }
}
