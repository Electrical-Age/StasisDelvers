package org.erisia.stasis;

import java.util.function.Consumer;

/** Client hooks for tower block entities, set by the client code (keeps client classes off the server). */
public final class TowerClientHooks {
    private TowerClientHooks() {
    }

    public static volatile Consumer<TowerBlockEntity> onUpdate = be -> {
    };
    public static volatile Consumer<TowerBlockEntity> onRemove = be -> {
    };

    static void updated(TowerBlockEntity be) {
        onUpdate.accept(be);
    }

    static void removed(TowerBlockEntity be) {
        onRemove.accept(be);
    }
}
