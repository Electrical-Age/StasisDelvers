package org.erisia.stasis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The lance's pure rules (docs/lance.md): where a gate leads, and which gate a return rift sends a player to. */
class LanceTest {
    static final String END = "minecraft:the_end", NETHER = "minecraft:the_nether";

    @Test
    void stoppedModeFollowsTheRiftsDestination() {
        assertEquals(Lance.Kind.OUTER_END, Lance.kind(false, false, false, END));
        assertEquals(Lance.Kind.NETHER, Lance.kind(false, false, false, NETHER));
        assertEquals(Lance.Kind.STRONGHOLD_END, Lance.kind(false, false, true, END));
    }

    @Test
    void spreadingModeBeforeHardModeIsNetherOnly() {
        // Baughn's decision 5: any rift, End-bound or a stronghold gate, opens to the Nether before hard mode
        for (boolean gate : new boolean[]{false, true})
            for (String d : new String[]{END, NETHER})
                assertEquals(Lance.Kind.NETHER, Lance.kind(true, false, gate, d), gate + " " + d);
    }

    @Test
    void spreadingModeAfterHardMode() {
        // decision 6: stronghold gates to the End rift's islands, wild End rifts to the outer End, the rest to the Nether
        assertEquals(Lance.Kind.CENTRAL_END, Lance.kind(true, true, true, END));
        assertEquals(Lance.Kind.OUTER_END, Lance.kind(true, true, false, END));
        assertEquals(Lance.Kind.NETHER, Lance.kind(true, true, false, NETHER));
    }

    @Test
    void destinationLevels() {
        assertEquals(net.minecraft.world.level.Level.NETHER, Lance.destDim(Lance.Kind.NETHER, END));
        assertEquals(net.minecraft.world.level.Level.NETHER, Lance.destDim(Lance.Kind.NETHER, NETHER));
        for (Lance.Kind k : new Lance.Kind[]{Lance.Kind.OUTER_END, Lance.Kind.STRONGHOLD_END, Lance.Kind.CENTRAL_END})
            assertEquals(net.minecraft.world.level.Level.END, Lance.destDim(k, NETHER));
    }

    @Test
    void returnChooserFallbackOrder() {
        // Baughn's decision 3: the lance the player came out of if open; else a random open one; else theirs regardless
        var mine = new Lance.Ref("minecraft:overworld", 7);
        var a = new Lance.Ref("minecraft:overworld", 11);
        var b = new Lance.Ref("minecraft:overworld", 13);
        assertEquals(mine, Lance.choose(mine, List.of(a, mine, b), mine, 0));
        assertEquals(a, Lance.choose(mine, List.of(a, b), mine, 0));
        assertEquals(b, Lance.choose(mine, List.of(a, b), mine, 1));
        assertEquals(b, Lance.choose(mine, List.of(a, b), mine, -1));
        assertEquals(mine, Lance.choose(mine, List.of(), mine, 5));
        // no came-from on record: a random open one, else the return rift's own home
        assertEquals(a, Lance.choose(null, List.of(a), b, 0));
        assertEquals(b, Lance.choose(null, List.of(), b, 0));
        assertNull(Lance.choose(null, List.of(), null, 0));
    }
}
