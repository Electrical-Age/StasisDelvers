package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmokeTest {
    @Test
    void gameOnClasspath() {
        assertEquals(3, new BlockPos(1, 2, 3).getZ());
    }
}
