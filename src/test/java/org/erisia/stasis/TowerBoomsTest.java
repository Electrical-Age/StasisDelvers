package org.erisia.stasis;

import org.erisia.stasis.towergen.Layout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The booms' wording on the console and the core's status (Baughn 2026-10-30: boom sizes were unclear). */
class TowerBoomsTest {
    @Test
    void eachBoomWithItsMostAndTheReach() {
        // a mast of 12: a boom at y 4 may be 8 long; 6 of 8 reaches 1 + 0.5 * 0.75 east; one full-length north
        var l = new Layout(12, List.of(new Layout.Boom(4, 0, 6), new Layout.Boom(8, 3, 4)));
        assertEquals("Booms: east 6/8, north 4/4 (most: the mast above each); the field reaches 1.38 r east, 1.50 r north.",
            TowerBooms.text(l, 0.5));
    }

    @Test
    void noBoomsSaysWhatOneWouldDo() {
        assertEquals("No booms: a boom off the mast stretches the field towards its side, up to 1.50 r at full length "
            + "(as long as the mast above it).", TowerBooms.text(new Layout(10, List.of()), 0.5));
    }
}
