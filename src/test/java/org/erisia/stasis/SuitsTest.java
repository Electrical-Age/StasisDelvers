package org.erisia.stasis;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Suits.available and Suits.drain on plain cells (no player): what the feed guard promises is what the feed takes. */
class SuitsTest {
    static final class Cell implements Suits.Cell {
        double energy;
        final double max, discharge;

        Cell(double energy, double max, double discharge) {
            this.max = max;
            this.energy = Math.min(energy, max);
            this.discharge = discharge;
        }

        public double energy() { return energy; }
        public double max() { return max; }
        public double discharge() { return discharge; }
        public void set(double e) { energy = e; }

        Cell copy() { return new Cell(energy, max, discharge); }

        @Override
        public String toString() { return String.format("Cell(%.1f/%.1f, %.1f W)", energy, max, discharge); }
    }

    @Provide
    Arbitrary<Cell> batteries() {
        // EA batteries, and rift capacitors (they discharge nothing: CapacitorItem.getDischagePower is 0)
        Arbitrary<Double> max = Arbitraries.doubles().between(100, 200_000).ofScale(1);
        Arbitrary<Double> share = Arbitraries.doubles().between(0, 1).ofScale(3);
        Arbitrary<Double> power = Arbitraries.oneOf(Arbitraries.just(0.0), Arbitraries.doubles().between(50, 5000).ofScale(1));
        return Combinators.combine(max, share, power).as((m, f, w) -> new Cell(m * f, m, w));
    }

    @Provide
    Arbitrary<Cell> pieces() {
        Arbitrary<Double> max = Arbitraries.doubles().between(1000, 400_000).ofScale(1);
        Arbitrary<Double> share = Arbitraries.doubles().between(0, 1).ofScale(3);
        return Combinators.combine(max, share).as((m, f) -> new Cell(m * f, m, 0));
    }

    static List<Suits.Cell> copy(List<Cell> l) {
        return l.stream().map(c -> (Suits.Cell) c.copy()).toList();
    }

    /** Feeding a dead tower (reserve 0, FEED_W a second) until nothing comes takes all that available promised. */
    @Property(tries = 500)
    void drainingTakesWhatAvailablePromised(@ForAll @Size(max = 6) List<@net.jqwik.api.From("batteries") Cell> carried,
                                            @ForAll @Size(max = 4) List<@net.jqwik.api.From("pieces") Cell> worn) {
        List<Suits.Cell> c = copy(carried), w = copy(worn);
        double promised = Suits.available(c, w), taken = 0;
        for (int second = 0; second < 1_000_000; second++) {
            double got = Suits.drain(c, w, TowerBlockEntity.FEED_W, 0);
            assertTrue(got >= -1e-9 && got <= TowerBlockEntity.FEED_W + 1e-9);
            taken += got;
            if (got <= 1e-6) break;
        }
        assertEquals(promised, taken, 1e-4 * (1 + c.size() + w.size()), () -> "carried " + carried + " worn " + worn);
    }

    /** One drain: at most maxJ, never below a piece's reserve, never a battery past its discharge power. */
    @Property(tries = 1000)
    void drainRespectsLimits(@ForAll @Size(max = 6) List<@net.jqwik.api.From("batteries") Cell> carried,
                             @ForAll @Size(max = 4) List<@net.jqwik.api.From("pieces") Cell> worn,
                             @ForAll @DoubleRange(min = 0, max = 50_000) double maxJ, @ForAll @DoubleRange(min = 0, max = 0.5) double reserve) {
        List<Suits.Cell> c = copy(carried), w = copy(worn);
        double before = Suits.available(c, w);
        double got = Suits.drain(c, w, maxJ, reserve);
        assertTrue(got >= -1e-9 && got <= maxJ + 1e-9, () -> "took " + got);
        assertEquals(before - got, Suits.available(c, w), 1e-6 * (1 + before));
        for (int i = 0; i < c.size(); i++) {
            double drop = carried.get(i).energy - c.get(i).energy();
            assertTrue(drop >= -1e-9 && drop <= carried.get(i).discharge + 1e-9);
        }
        for (int i = 0; i < w.size(); i++) {
            Cell o = worn.get(i);
            double floor = Math.min(o.energy, reserve * o.max);
            assertTrue(w.get(i).energy() >= floor - 1e-6, () -> "below reserve: " + o);
        }
    }

    @Example
    void bugB_aCapacitorIsNotPower() {
        // a charged rift capacitor carried, an empty suit: nothing to feed a tower with
        List<Suits.Cell> carried = List.of(new Cell(48_000, 48_000, 0)), worn = List.of(new Cell(0, 80_000, 0));
        assertEquals(0, Suits.available(carried, worn));
        assertEquals(0, Suits.drain(carried, worn, TowerBlockEntity.FEED_W, 0));
    }
}
