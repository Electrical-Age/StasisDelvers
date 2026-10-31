package org.erisia.stasis.towergen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The outlines of poles hidden under a generated tower come from what the mesh draws in their cells. */
class CellCoverTest {
    @Test
    void poleCells() {
        var cells = CellCover.poleCells(new Layout(10, List.of(new Layout.Boom(5, 0, 2), new Layout.Boom(6, 3, 1))), 2);
        assertEquals(new CellCover.Cell(0, 2, 0), cells.get(0));
        assertEquals(new CellCover.Cell(0, 9, 0), cells.get(7));
        assertEquals(new CellCover.Cell(1, 4, 0), cells.get(8));
        assertEquals(new CellCover.Cell(2, 4, 0), cells.get(9));
        assertEquals(new CellCover.Cell(0, 5, -1), cells.get(10));
    }

    @Test
    void aBeamCoversItsCellsOnly() {
        Mesh m = new Mesh();
        // a 0.25-wide vertical beam up the mast from y 2.5 to 4
        m.beam(new double[]{0, 2.5, 0}, new double[]{0, 4, 0}, 0.25, VisualParams.Part.values()[0]);
        var cells = List.of(new CellCover.Cell(0, 2, 0), new CellCover.Cell(0, 3, 0), new CellCover.Cell(0, 4, 0), new CellCover.Cell(1, 3, 0));
        Map<CellCover.Cell, BitSet> c = CellCover.cover(m, cells);
        BitSet low = c.get(cells.get(0)), mid = c.get(cells.get(1));
        assertFalse(low.isEmpty(), "the beam's foot");
        assertFalse(mid.isEmpty(), "the beam's middle");
        assertTrue(c.get(cells.get(3)).isEmpty(), "nothing beside it");
        // its foot starts half way up the lower cell: no sub-cell below y 0.5 there
        for (int i = low.nextSetBit(0); i >= 0; i = low.nextSetBit(i + 1)) assertTrue((i / CellCover.N) % CellCover.N >= CellCover.N / 2 - 1, "bit " + i);
        // and it stays near the middle in x and z
        for (int i = mid.nextSetBit(0); i >= 0; i = mid.nextSetBit(i + 1)) {
            int sx = i / (CellCover.N * CellCover.N), sz = i % CellCover.N;
            assertTrue(sx >= 2 && sx <= 5 && sz >= 2 && sz <= 5, "bit " + i);
        }
    }

    @Test
    void bigTowerIsQuickAndDrawsTheMast() {
        TowerGen g = new TowerGen(Library.shared());
        Layout l = new Layout(64, List.of(new Layout.Boom(24, 0, 40)));
        Mesh m = new MeshBuilder(Library.shared().c, VisualParams.defaults()).build(g.build(l, 1.0));
        long t0 = System.nanoTime();
        var c = CellCover.cover(m, CellCover.poleCells(l, 2));
        double ms = (System.nanoTime() - t0) / 1e6;
        long drawn = c.values().stream().filter(b -> !b.isEmpty()).count();
        System.out.println("cell cover H64 + boom 40: " + c.size() + " cells, " + drawn + " drawn, " + ms + " ms");
        assertTrue(ms < 3000, ms + " ms");
        // the drawn pole's top cells, and every cell of the boom (the mast below the socket is the ladder well)
        for (var e : c.entrySet()) {
            var k = e.getKey();
            if (k.x() > 0 || k.y() >= 57) assertFalse(e.getValue().isEmpty(), "drawn in " + k);
        }
    }
}
