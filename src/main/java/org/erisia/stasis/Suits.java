package org.erisia.stasis;

import net.minecraft.server.level.ServerPlayer;

/** Suits that let a player move in stopped land (T2b: the Delver suit and the combined suit, on EA power). */
final class Suits {
    private Suits() {
    }

    /** Is the player wearing a suit with charge left? Draws this tick's power if so. */
    static boolean protect(ServerPlayer p) {
        return false;
    }
}
