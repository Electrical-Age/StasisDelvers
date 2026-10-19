package org.erisia.stasis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---- L2: costs, times, the breach's state machine

    @Test
    void costsScaleWithTheRift() {
        // Baughn: holding ~10 kW to break even; the breach 3-5x, r 9 (stronghold gates) cheapest, r 60 the endgame
        assertEquals(10000, Lance.holdW(9, 10000));
        assertEquals(10000, Lance.holdW(60, 10000));
        assertEquals(30000, Lance.breachW(9, 10000, 3, 5), 1e-6);
        assertEquals(50000, Lance.breachW(60, 10000, 3, 5), 1e-6);
        assertEquals(40000, Lance.breachW(34.5f, 10000, 3, 5), 1e-6);
        assertEquals(30000, Lance.breachW(3, 10000, 3, 5), 1e-6);    // (clamped below r 9: return rifts aren't lanceable anyway)
        assertEquals(50000, Lance.breachW(64, 10000, 3, 5), 1e-6);
        assertEquals(120, Lance.seconds(9, 120), 1e-9);
        assertEquals(120 * Math.sqrt(60 / 9.0), Lance.seconds(60, 120), 1e-4);
        assertTrue(Lance.seconds(24, 120) > 120 && Lance.seconds(24, 120) < Lance.seconds(60, 120));
    }

    @Test
    void strengthSteps() {
        assertEquals(1f, Lance.strength(0));
        assertEquals(Lance.OPEN_SCALE, Lance.strength(Lance.STEPS));
        for (int s = 1; s <= Lance.STEPS; s++) assertTrue(Lance.strength(s) < Lance.strength(s - 1));
        assertEquals(1 - 0.75f / 2, Lance.strength(4), 1e-6);
    }

    static Lance.Gate gate() {
        return new Lance.Gate(5, 0, 64, 0, 9, false, 0, 0, 0, Lance.NO_LANCE, -1);
    }

    static Lance.Gate run(Lance.Gate g, Lance.Drive d, int ticks) {
        for (int i = 0; i < ticks; i++) g = Lance.advance(g, d);
        return g;
    }

    @Test
    void breachStepsThenLatches() {
        var on = new Lance.Drive(true, true, false, 10, 20, 30);
        var g = run(gate(), on, 10);
        assertEquals(1, g.step());
        assertFalse(g.open());
        g = run(g, on, 69);
        assertEquals(7, g.step());
        g = run(g, on, 1);
        assertEquals(Lance.STEPS, g.step());
        assertTrue(g.open(), "latched at the floor");
        g = run(g, on, 500);
        assertEquals(Lance.STEPS, g.step());
        assertTrue(g.open(), "held while powered");
    }

    @Test
    void stormStallsTheBreach() {
        var g = run(gate(), new Lance.Drive(true, true, false, 10, 20, 30), 25);
        var stalled = run(g, new Lance.Drive(true, true, true, 10, 20, 30), 200);
        assertEquals(g.step(), stalled.step());
        assertEquals(g.progress(), stalled.progress());
        assertEquals(0, stalled.grace(), "powered in a storm: no grace counting");
    }

    @Test
    void powerLossGraceThenRelaxStepByStep() {
        var on = new Lance.Drive(true, true, false, 10, 20, 30);
        var off = new Lance.Drive(true, false, false, 10, 20, 30);
        var g = run(gate(), on, 80);
        assertTrue(g.open());
        g = run(g, off, 30);
        assertTrue(g.open(), "still latched in the grace");
        assertTrue(Lance.klaxon(g, 30));
        assertFalse(Lance.relaxing(g, 30));
        var lastGrace = g;
        g = run(g, off, 1);
        assertFalse(g.open(), "unlatched after the grace");
        assertEquals(Lance.STEPS - 1, g.step(), "first step back");
        // the klaxon only in the grace; the relax has its own cue, once as it starts and once per step (AdventurAgent)
        assertFalse(Lance.klaxon(g, 30), "no klaxon while relaxing");
        assertTrue(Lance.relaxing(g, 30));
        assertTrue(Lance.closingCue(lastGrace, g, 30), "the cue as the relax starts");
        int cues = 0;
        for (int i = 0; i < 20 * Lance.STEPS; i++) {
            var n = run(g, off, 1);
            assertFalse(Lance.klaxon(n, 30), "no klaxon in the relax, tick " + i);
            if (Lance.closingCue(g, n, 30)) cues++;
            if (n.step() == 0 && n.progress() == 0) break;
            g = n;
        }
        assertEquals(Lance.STEPS - 1, cues, "one cue per step back");
        g = run(run(gate(), on, 80), off, 31);
        g = run(g, off, 19);
        assertEquals(Lance.STEPS - 1, g.step(), "a step every relaxTicks, never a snap");
        g = run(g, off, 1);
        assertEquals(Lance.STEPS - 2, g.step());
        g = run(g, off, 20 * Lance.STEPS);
        assertEquals(0, g.step());
        assertEquals(0, g.progress());
        assertEquals(0, g.grace());
        // power back mid-relax: on from there
        var h = run(run(gate(), on, 80), off, 31 + 25);
        int st = h.step();
        h = run(h, on, 1);
        assertEquals(0, h.grace());
        assertTrue(h.step() >= st);
    }

    @Test
    void noFocusIsLikeNoPower() {
        var g = run(gate(), new Lance.Drive(true, true, false, 10, 20, 30), 35);
        var lost = run(g, new Lance.Drive(false, true, false, 10, 20, 30), 31 + 20 * 4);
        assertEquals(0, lost.step());
    }

    @Test
    void dormancyRules() {
        var a = new Lance.Arrival("rift:x:5", 0, 0, "minecraft:overworld", 5, false, false);
        var gone = gate().withLance(Lance.NO_LANCE, 1000);
        assertTrue(Lance.dormant(a, gone, 1000 + Lance.DORMANT_AFTER, true));
        assertFalse(Lance.dormant(a, gone, 1000 + Lance.DORMANT_AFTER - 1, true), "not before 15 min");
        assertFalse(Lance.dormant(a, gone, 1000 + Lance.DORMANT_AFTER, false), "not with someone near");
        assertFalse(Lance.dormant(a, gate().withLance(123, -1), 1 << 30, true), "never while the lance stands");
        assertFalse(Lance.dormant(a, gate(), 1 << 30, true), "never for a gate that never had a lance");
        var central = new Lance.Arrival("island:3", 0, 0, "minecraft:overworld", 5, true, false);
        assertFalse(Lance.dormant(central, gone, 1 << 30, true), "the End rift's islands serve every lance");
    }
}
