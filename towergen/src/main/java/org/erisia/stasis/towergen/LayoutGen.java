package org.erisia.stasis.towergen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Random layouts by the game rules (TOWER-DESIGN.md; same distribution as p3agen.py): H 4..40 with p 0.75 else
 * 41..100; boom count 0 with p 0.2 else 1 + Exp(mean 2.2) capped at 12; booms at integer y in 2..H-2 in E/S/W/N,
 * at most one per (y, d), length 2 + (H - y - 2) u^1.7 (<= H - y).
 */
public final class LayoutGen {
    private LayoutGen() {}

    public static Layout random(Random rng) {
        int H = rng.nextDouble() < 0.75 ? 4 + rng.nextInt(37) : 41 + rng.nextInt(60);
        double u = rng.nextDouble();
        int nb = u < 0.2 ? 0 : Math.min(12, 1 + (int) (-Math.log(1 - rng.nextDouble()) * 2.2));
        List<int[]> slots = new ArrayList<>();
        for (int y = 2; y < H - 1; y++) for (int d = 0; d < 4; d++) slots.add(new int[]{y, d});
        Collections.shuffle(slots, rng);
        List<Layout.Boom> booms = new ArrayList<>();
        for (int i = 0; i < Math.min(nb, slots.size()); i++) {
            int y = slots.get(i)[0], d = slots.get(i)[1];
            int Lmax = H - y;
            int L = Lmax > 2 ? 2 + (int) ((Lmax - 2) * Math.pow(rng.nextDouble(), 1.7) + 0.5) : 2;
            booms.add(new Layout.Boom(y, d, Math.min(L, Lmax)));
        }
        booms.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y()) : Integer.compare(a.dir(), b.dir()));
        return new Layout(H, booms);
    }
}
