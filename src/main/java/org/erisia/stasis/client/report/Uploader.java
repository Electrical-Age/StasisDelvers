package org.erisia.stasis.client.report;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Where a finished report goes after it lands in bugreports/pending (SPEC 5.4). Only option C (manual: the player posts
 * the zip in Discord, then runs /bugreport sent) exists now: NONE. Option B (chunks over the game connection, a
 * ReportChunk/ReportAck pair in StasisNet) or A (HTTP POST) would implement this and be set as BugReport.uploader.
 */
public interface Uploader {
    /** Whether reports are uploaded at all. */
    boolean enabled();

    /**
     * Upload a zip in the background. Calls done exactly once, on any thread: with a receipt id on success (the caller
     * then moves the zip to sent/ and writes the receipt), or null with failure set.
     */
    void upload(Path zip, String reportId, Consumer<Result> done);

    record Result(String receiptId, String failure) {
    }

    Uploader NONE = new Uploader() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public void upload(Path zip, String reportId, Consumer<Result> done) {
            done.accept(new Result(null, "upload not configured"));
        }
    };
}
