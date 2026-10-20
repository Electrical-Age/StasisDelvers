package org.erisia.stasis;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import static org.junit.jupiter.api.Assertions.*;

/** A new level's deep seed comes from the world seed and dimension (Baughn): the same every time, in 1..0xFFFFFE. */
class DeepSeedTest {
    @Property(tries = 500)
    void derivedFromTheWorldSeed(@ForAll long worldSeed) {
        for (String dim : new String[]{"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"}) {
            int s = FieldData.derive(worldSeed, dim);
            assertTrue(s >= 1 && s <= 0xFFFFFE, "in range: " + s);
            assertEquals(s, FieldData.derive(worldSeed, dim), "the same every time");
        }
        assertNotEquals(FieldData.derive(worldSeed, "minecraft:overworld"), FieldData.derive(worldSeed, "minecraft:the_nether"),
            "dimensions differ");
    }
}
