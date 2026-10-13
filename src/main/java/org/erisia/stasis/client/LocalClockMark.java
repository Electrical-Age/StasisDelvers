package org.erisia.stasis.client;

/** Marks the client's overworld clock instance (mixin.client.ClientClockManagerMixin), whose reads give local time. */
public interface LocalClockMark {
    void stasis$markOverworld();
}
