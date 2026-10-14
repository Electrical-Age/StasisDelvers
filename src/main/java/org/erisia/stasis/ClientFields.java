package org.erisia.stasis;

import java.util.List;

/** The fields of the level the client is in, as last sent by the server (StasisNet.Fields). */
public final class ClientFields {
    private ClientFields() {
    }

    private static volatile List<Field> fields = List.of();

    public static List<Field> fields() {
        return fields;
    }

    static void set(List<Field> f, net.minecraft.world.level.Level level) {
        Flames.clientLevel(level);
        fields = List.copyOf(f);
        if (level != null) {
            Stasis.invalidate(level);
            Flames.fieldsChanged(level);
        }
    }
}
