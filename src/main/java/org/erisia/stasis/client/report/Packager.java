package org.erisia.stasis.client.report;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bug report files (SPEC 1.11, 5.1, 5.2): the redacted log and crash report, the zip, and the
 * &lt;game&gt;/bugreports/{pending,sent,.tmp} folders. Pure file work: runs off the client thread.
 */
final class Packager {
    private Packager() {
    }

    static final int LOG_TAIL_BYTES = 512 * 1024, LOG_TAIL_LINES = 3000, LOG_HEAD_LINES = 200;
    static final int CRASH_CAP = 256 * 1024;
    static final long ZIP_MAX = 8L * 1024 * 1024;
    static final int PENDING_MAX = 20, SENT_KEEP = 10;
    static final long PENDING_MAX_BYTES = 100L * 1024 * 1024;

    static Path pending(Path root) {
        return root.resolve("pending");
    }

    static Path sent(Path root) {
        return root.resolve("sent");
    }

    static Path tmp(Path root) {
        return root.resolve(".tmp");
    }

    /** logs/latest.log: the first 200 lines (mod loading, mixin errors) and the last 512 KB, at most 3000 lines. */
    static String log(Path gameDir, Redactor r) throws IOException {
        Path f = gameDir.resolve("logs/latest.log");
        if (!Files.isRegularFile(f)) return "(no logs/latest.log)\n";
        List<String> head = new ArrayList<>();
        String tail;
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long len = raf.length();
            byte[] hb = new byte[(int) Math.min(len, 128 * 1024)];
            raf.readFully(hb);
            String hs = new String(hb, StandardCharsets.UTF_8);
            String[] hl = hs.split("\n", -1);
            for (int i = 0; i < hl.length - (hb.length < len ? 1 : 0) && head.size() < LOG_HEAD_LINES; i++) head.add(hl[i]);
            long start = Math.max(0, len - LOG_TAIL_BYTES);
            byte[] tb = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(tb);
            String ts = new String(tb, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = ts.indexOf('\n');
                ts = nl < 0 ? "" : ts.substring(nl + 1);   // whole lines only
            }
            String[] tl = ts.split("\n", -1);
            int from = Math.max(0, tl.length - LOG_TAIL_LINES);
            tail = String.join("\n", Arrays.asList(tl).subList(from, tl.length));
        }
        return r.redact("--- head ---\n" + String.join("\n", head) + "\n--- tail ---\n" + tail + "\n");
    }

    /** The newest crash report written since this session started, capped and redacted (null if none). */
    static String crash(Path gameDir, long sessionStartMillis, Redactor r) throws IOException {
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return null;
        Path newest = null;
        FileTime best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : ds) {
                FileTime t = Files.getLastModifiedTime(p);
                if (t.toMillis() < sessionStartMillis) continue;
                if (best == null || t.compareTo(best) > 0) {
                    best = t;
                    newest = p;
                }
            }
        }
        if (newest == null) return null;
        byte[] b;
        try (var in = Files.newInputStream(newest)) {
            b = in.readNBytes(CRASH_CAP);
        }
        return r.redact(new String(b, StandardCharsets.UTF_8));
    }

    /** Write the zip to .tmp/&lt;id&gt;.zip from these entries (name -> bytes) and the screenshot file (may be null). */
    static Path zip(Path root, String id, Map<String, byte[]> entries, Path screenshot) throws IOException {
        Path out = tmp(root).resolve(id + ".zip");
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream z = new ZipOutputStream(os)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
            if (screenshot != null && Files.isRegularFile(screenshot)) {
                z.putNextEntry(new ZipEntry("screenshot.png"));
                Files.copy(screenshot, z);
                z.closeEntry();
            }
        }
        return out;
    }

    /** Move the finished zip into pending/. */
    static Path publish(Path root, Path zip) throws IOException {
        Path dest = pending(root).resolve(zip.getFileName());
        Files.createDirectories(dest.getParent());
        try {
            return Files.move(zip, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            return Files.move(zip, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static List<Path> zips(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            return new ArrayList<>(s.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted(Comparator.comparing(Packager::modified)).toList());
        }
    }

    static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    static long size(List<Path> ps) {
        long n = 0;
        for (Path p : ps) {
            try {
                n += Files.size(p);
            } catch (IOException e) {
                // gone
            }
        }
        return n;
    }

    /**
     * Retention: sent/ keeps the newest 10; pending/ holds at most 20 zips or 100 MB, making room by deleting the oldest
     * sent zip first and only then the oldest pending one. Returns a warning for the player, or null.
     */
    static String retain(Path root) throws IOException {
        List<Path> sent = zips(sent(root));
        while (sent.size() > SENT_KEEP) deleteWithReceipt(sent.removeFirst());
        List<Path> pend = zips(pending(root));
        int dropped = 0;
        while (pend.size() > PENDING_MAX || size(pend) + size(sent) > PENDING_MAX_BYTES) {
            if (!sent.isEmpty()) deleteWithReceipt(sent.removeFirst());
            else if (pend.size() > 1) {
                Files.deleteIfExists(pend.removeFirst());
                dropped++;
            } else break;
        }
        return dropped > 0 ? "Deleted the " + dropped + " oldest unsent bug report(s) in bugreports/pending (at most "
            + PENDING_MAX + " or 100 MB are kept)." : null;
    }

    static void deleteWithReceipt(Path zip) throws IOException {
        Files.deleteIfExists(zip);
        String n = zip.getFileName().toString();
        Files.deleteIfExists(zip.resolveSibling(n.substring(0, n.length() - 4) + ".receipt.txt"));
    }

    /** /bugreport sent: move pending zips to sent/ ("all", or a name with or without .zip). Returns the moved names. */
    static List<String> markSent(Path root, String which) throws IOException {
        List<String> moved = new ArrayList<>();
        Path sentDir = sent(root);
        for (Path p : zips(pending(root))) {
            String n = p.getFileName().toString();
            if (!which.equals("all") && !n.equals(which) && !n.equals(which + ".zip")) continue;
            Files.createDirectories(sentDir);
            Files.move(p, sentDir.resolve(n), StandardCopyOption.REPLACE_EXISTING);
            moved.add(n);
        }
        if (!moved.isEmpty()) {
            List<Path> sent = zips(sentDir);
            while (sent.size() > SENT_KEEP) deleteWithReceipt(sent.removeFirst());
        }
        return moved;
    }

    static List<String> pendingNames(Path root) {
        try {
            return zips(pending(root)).stream().map(p -> p.getFileName().toString()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Empty .tmp/ (left over from a report the game didn't finish). */
    static void cleanTmp(Path root) {
        Path t = tmp(root);
        if (!Files.isDirectory(t)) return;
        try (Stream<Path> s = Files.walk(t)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) if (!p.equals(t)) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    static Map<String, byte[]> entries() {
        return new LinkedHashMap<>();
    }
}
