package org.erisia.stasis;

/** A tower's booms: how far they stretch its field (Baughn 2026-10-18), and how that reads for players. Pure. */
public final class TowerBooms {
    private TowerBooms() {
    }

    /** A side reaches 1 + k * (its longest boom's share of its allowed length, H - y) (Field.Stretch). */
    public static Field.Stretch stretch(org.erisia.stasis.towergen.Layout layout, double k) {
        double[] f = new double[4];
        for (var b : layout.booms()) {
            int room = layout.H() - b.y();
            if (room > 0) f[b.dir()] = Math.max(f[b.dir()], Math.min(1.0, b.length() / (double) room));
        }
        return new Field.Stretch(1 + k * f[0], 1 + k * f[1], 1 + k * f[2], 1 + k * f[3]);
    }

    /**
     * The booms for players (Baughn 2026-10-30: the wording on boom sizes was unclear): each as its side and its length
     * of the most it may have (the mast above where it starts), then how far the field reaches each stretched way, e.g.
     * "Booms: east 6/8 (most: the mast above each); the field reaches 1.38 r east." Short: the console shows three lines.
     * With none, what a boom would do.
     */
    public static String text(org.erisia.stasis.towergen.Layout layout, double k) {
        String[] side = {"east", "south", "west", "north"};
        if (layout.booms().isEmpty())
            return String.format("No booms: a boom off the mast stretches the field towards its side, up to %.2f r at full length "
                + "(as long as the mast above it).", 1 + k);
        var t = stretch(layout, k);
        double[] reach = {t.e(), t.s(), t.w(), t.n()};
        var parts = new java.util.ArrayList<String>();
        for (var b : layout.booms()) {
            int room = Math.max(0, layout.H() - b.y());
            parts.add(String.format("%s %d/%d", side[b.dir()], b.length(), room));
        }
        var sides = new java.util.ArrayList<String>();
        for (int d = 0; d < 4; d++) if (reach[d] > 1 + 1e-9) sides.add(String.format("%.2f r %s", reach[d], side[d]));
        return "Booms: " + String.join(", ", parts) + " (most: the mast above each)"
            + (sides.isEmpty() ? "." : "; the field reaches " + String.join(", ", sides) + ".");
    }
}
