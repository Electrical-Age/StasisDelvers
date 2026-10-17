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

    static void set(List<Field> f, net.minecraft.world.level.Level level) {
        boolean newLevel = Flames.clientLevel(level);
        List<Field> old = newLevel ? List.of() : fields;
        fields = List.copyOf(f);
        if (level != null) {
            Stasis.invalidate(level);
            Flames.fieldsChanged(level, old, fields);
            onChange.accept(old, fields);
        }
    }
}
