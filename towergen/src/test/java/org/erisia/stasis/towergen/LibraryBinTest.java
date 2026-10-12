package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The binary library (towerweb) is lossless: every kept field round-trips and meshes hash the same. */
class LibraryBinTest {
    @Test
    void roundTrip() {
        Library a = Library.shared();
        byte[] bin = LibraryBin.write(a);
        Library b = LibraryBin.read(bin);
        System.out.println("TGLB1 " + bin.length + " bytes, read in " + b.loadMs + " ms");
        assertEquals(a.entries.size(), b.entries.size());
        for (int q = 0; q < a.entries.size(); q++) {
            Library.Entry x = a.entries.get(q), y = b.entries.get(q);
            String w = "entry " + q;
            assertEquals(x.key, y.key, w);
            assertEquals(x.kind, y.kind, w);
            assertArrayEquals(x.pole, y.pole, w); assertArrayEquals(x.ghost, y.ghost, w);
            assertArrayEquals(x.sup, y.sup, w); assertArrayEquals(x.top, y.top, w);
            assertArrayEquals(x.legs, y.legs, w);
            assertArrayEquals(x.roots, y.roots, w); assertArrayEquals(x.anchors, y.anchors, w);
            assertEquals(x.tip, y.tip, w);
            assertArrayEquals(x.reacRoots, y.reacRoots, w); assertArrayEquals(x.reacAnchors, y.reacAnchors, w);
            variant(x.q1, y.q1, w); variant(x.q03, y.q03, w);
        }
        assertArrayEquals(a.c.WEXT, b.c.WEXT);
        assertArrayEquals(a.c.refN, b.c.refN);
        assertEquals(a.c.Q_LOW, b.c.Q_LOW);
        TowerGen ga = new TowerGen(a), gb = new TowerGen(b);
        MeshBuilder mb = new MeshBuilder(a.c, VisualParams.defaults());
        for (Layout l : List.of(MeshTest.H64MAX, MeshTest.H24B, new Layout(100, List.of())))
            for (double q : new double[]{1.0, 0.3})
                assertEquals(mb.build(ga.build(l, q)).hash(), mb.build(gb.build(l, q)).hash(), l + " q" + q);
    }

    private static void variant(Library.Variant x, Library.Variant y, String w) {
        if (x == null) { assertNull(y, w); return; }
        assertTrue(y != null, w);
        assertEquals(java.util.Arrays.toString(x.nodes), java.util.Arrays.toString(y.nodes), w);
        for (int k = 0; k < x.nodes.length; k++)
            assertEquals(Double.doubleToRawLongBits(x.nodes[k]), Double.doubleToRawLongBits(y.nodes[k]), w);
        assertArrayEquals(x.mi, y.mi, w); assertArrayEquals(x.mj, y.mj, w); assertArrayEquals(x.kind, y.kind, w);
        assertArrayEquals(x.ws, y.ws, w); assertArrayEquals(x.cs, y.cs, w);
        assertArrayEquals(x.pre, y.pre, w); assertArrayEquals(x.poleW, y.poleW, w);
    }
}
