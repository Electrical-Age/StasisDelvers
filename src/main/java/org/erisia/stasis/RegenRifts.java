package org.erisia.stasis;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Regenerating only the rift chunks of a live world (Baughn, 2026-10-19: "I don't want a server reset, but is it
 * possible to delete and recreate just the chunks with rifts in them? Nobody settled there"). Rift worldgen only
 * reaches chunks generated after it existed, so older chunks have none; deleting them from the region files makes the
 * game generate them again, rifts included.
 * <ul>
 * <li>{@code /stasis regenrifts plan} (here) lists every generated overworld chunk that DeepFeature.place writes into
 * (the same test, {@link DeepFeature#deepsAt}), from the level's deep seed, to {@link #PLAN} in the world folder. Pure
 * computation over the region files' location tables: no chunk is loaded. The spawn area and stronghold portal rooms
 * waiting for hard mode (PendingGates) are listed as EXCLUDE.</li>
 * <li>{@code tools/regen-rift-chunks.py} (offline, server stopped) deletes the planned chunks that nobody settled
 * (InhabitedTime, EA block entities, OPAC claims) and writes {@link #DONE}.</li>
 * <li>At the next overworld load, {@link #applyDone} forgets the per-chunk saved state of the deleted chunks (rift
 * crystal buds and their "swept" mark, gutters), so the regenerated chunks' buds are stamped afresh.</li>
 * </ul>
 */
final class RegenRifts {
    private RegenRifts() {
    }

    static final String PLAN = "stasisdelvers-regen-plan.txt", DONE = "stasisdelvers-regen-done.txt";
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** The overworld's region folder: dimensions/minecraft/overworld/region in 26.x saves, region in older ones. */
    static Path regionDir(Path root) {
        Path dims = root.resolve("dimensions/minecraft/overworld/region");
        return Files.isDirectory(dims) ? dims : root.resolve("region");
    }

    /** Every chunk with data in the region files of dir (their 4 KB location tables only). */
    static List<ChunkPos> generated(Path dir) throws IOException {
        List<ChunkPos> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                Matcher m = REGION.matcher(f.getFileName().toString());
                if (!m.matches() || Files.size(f) < 4096) continue;
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
                byte[] head = new byte[4096];
                try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                    raf.readFully(head);
                }
                for (int i = 0; i < 1024; i++) {
                    int loc = ((head[4 * i] & 0xFF) << 24) | ((head[4 * i + 1] & 0xFF) << 16) | ((head[4 * i + 2] & 0xFF) << 8) | (head[4 * i + 3] & 0xFF);
                    if (loc >>> 8 != 0) out.add(new ChunkPos(rx * 32 + (i & 31), rz * 32 + (i >> 5)));
                }
            }
        }
        return out;
    }

    /** One plan line's reason for the chunk with corner (x0, z0): the deeps that reach it, or null when none. */
    static String reason(int seed, int x0, int z0, int minY, int maxY) {
        List<StaticDeeps.Deep> deeps = DeepFeature.deepsAt(seed, x0, z0, minY, maxY);
        if (deeps.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (StaticDeeps.Deep d : deeps) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(String.format(java.util.Locale.ROOT, "deep %06x at %.0f %.0f %.0f r %.0f (%s)", d.id(), d.x(), d.y(), d.z(), d.radius(),
                DeepFeature.reaches(d, x0, z0, false) ? "carve" : "strata"));
        }
        return sb.toString();
    }

    /** Write the plan for the overworld; returns a one-line summary. */
    static String plan(ServerLevel level) throws IOException {
        if (level.dimension() != Level.OVERWORLD) throw new IOException("the plan is for the overworld only");
        int seed = Deeps.seed(level);
        if (seed == 0) throw new IOException("no deep seed for this level");
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dir = regionDir(root);
        List<ChunkPos> chunks = generated(dir);
        int[] spawn = Stasis.spawnArea(level);
        List<PendingGates.Room> rooms = PendingGates.list(level);
        int minY = level.getMinY(), maxY = level.getMaxY();
        List<String> lines = new ArrayList<>();
        int rift = 0, excluded = 0;
        for (ChunkPos cp : chunks) {
            int x0 = cp.x() << 4, z0 = cp.z() << 4;
            String why = reason(seed, x0, z0, minY, maxY);
            if (why == null) continue;
            String ex = null;
            if (spawn != null && x0 + 15 >= spawn[0] && x0 <= spawn[2] && z0 + 15 >= spawn[1] && z0 <= spawn[3]) ex = "spawn area";
            for (PendingGates.Room r : rooms)
                if (ex == null && r.box().maxX() >= x0 && r.box().minX() <= x0 + 15 && r.box().maxZ() >= z0 && r.box().minZ() <= z0 + 15)
                    ex = "stronghold portal room";
            if (ex != null) {
                excluded++;
                lines.add(cp.x() + " " + cp.z() + " EXCLUDE " + ex + "; " + why);
            } else {
                rift++;
                lines.add(cp.x() + " " + cp.z() + " " + why);
            }
        }
        List<String> out = new ArrayList<>();
        out.add("# Stasis Delvers rift regeneration plan (/stasis regenrifts plan); tools/regen-rift-chunks.py reads it.");
        out.add("# dimension minecraft:overworld, deep seed " + seed + ", region dir " + root.relativize(dir) + ", y " + minY + ".." + maxY
            + ", made " + java.time.Instant.now());
        out.add("# " + chunks.size() + " chunks generated, " + rift + " to regenerate, " + excluded + " excluded");
        out.add("# cx cz reason (EXCLUDE: never delete)");
        out.addAll(lines);
        Files.write(root.resolve(PLAN), out);
        return chunks.size() + " chunks generated; " + rift + " reached by rift worldgen, " + excluded + " more excluded (spawn, portal rooms); plan in "
            + PLAN;
    }

    /** The chunks of a done file: lines "cx cz", '#' comments. */
    static Set<Long> parseChunks(List<String> lines) {
        Set<Long> out = new HashSet<>();
        for (String l : lines) {
            l = l.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\s+");
            out.add(ChunkPos.pack(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
        }
        return out;
    }

    /**
     * Overworld load (main thread): if the offline tool deleted chunks, forget their crystal buds and swept marks and
     * their gutters (else the regenerated chunks' buds would never be stamped, and stale buds would grow on new rock),
     * then rename the file so it is done once.
     */
    static void applyDone(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return;
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        Path f = root.resolve(DONE);
        if (!Files.exists(f)) return;
        try {
            Set<Long> chunks = parseChunks(Files.readAllLines(f));
            int buds = forgetCrystals(Crystals.Data.get(level), chunks);
            int gutters = Flames.forgetChunks(level, chunks);
            Files.move(f, root.resolve(DONE + ".applied-" + System.currentTimeMillis()));
            StasisDelvers.LOG.info("Rift regeneration: forgot the saved state of {} deleted chunks ({} bud chunks, {} gutters)", chunks.size(), buds, gutters);
        } catch (IOException | RuntimeException e) {
            StasisDelvers.LOG.error("Rift regeneration: could not apply {}", f, e);
        }
    }

    static int forgetCrystals(Crystals.Data d, Set<Long> chunks) {
        int n = 0;
        for (long k : chunks) {
            if (d.buds.remove(k) != null) n++;
            d.swept.remove(k);
        }
        d.setDirty();
        return n;
    }
}
