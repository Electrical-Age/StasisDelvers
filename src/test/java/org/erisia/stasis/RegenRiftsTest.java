package org.erisia.stasis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import net.jqwik.api.Example;
import net.minecraft.world.level.ChunkPos;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RegenRifts (Baughn: regenerate only the rift chunks): which chunks exist on disk, and the tool's done file. */
class RegenRiftsTest {
    /** A region file's location table gives the generated chunks, with region offsets (negative regions too). */
    @Example
    void generatedReadsLocationTables() throws Exception {
        Path dir = Files.createTempDirectory("regen");
        byte[] head = new byte[8192];
        int i = 3 + 32 * 5;   // chunk (3, 5) in its region
        head[4 * i + 2] = 2;   // sector offset 2
        head[4 * i + 3] = 1;   // one sector
        Files.write(dir.resolve("r.-1.2.mca"), head);
        Files.write(dir.resolve("r.0.0.mca"), new byte[8192]);   // empty table
        List<ChunkPos> got = RegenRifts.generated(dir);
        assertEquals(List.of(new ChunkPos(-32 + 3, 64 + 5)), got);
    }

    /** The done file: "cx cz" per line, comments and blank lines skipped. */
    @Example
    void doneFileParses() {
        Set<Long> s = RegenRifts.parseChunks(List.of("# deleted", "", "3 -4", " -7 12 "));
        assertEquals(Set.of(ChunkPos.pack(3, -4), ChunkPos.pack(-7, 12)), s);
    }
}
