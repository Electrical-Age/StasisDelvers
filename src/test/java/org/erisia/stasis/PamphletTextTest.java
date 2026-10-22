package org.erisia.stasis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The pamphlet's pages never use §r: in a book it resets to chat style, white with a shadow (Baughn, AdventurAgent). */
public class PamphletTextTest {
    @Test
    void noResetCodes() throws Exception {
        try (var in = getClass().getResourceAsStream("/assets/stasisdelvers/lang/en_us.json")) {
            assertNotNull(in);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                if (line.contains("pamphlet.page")) assertFalse(line.contains("§r"), line);
        }
    }
}
