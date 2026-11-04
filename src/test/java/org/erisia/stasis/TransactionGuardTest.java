package org.erisia.stasis;

import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** TransactionGuard (Incognito 2026-11-04: Curios beta.3 leaked a root transaction at Nero's login). */
class TransactionGuardTest {
    /** A journalled int, like an inventory slot. */
    static final class Slot extends SnapshotJournal<Integer> {
        int value;
        int commits;

        void set(int v, Transaction tx) {
            updateSnapshots(tx);
            value = v;
        }

        @Override protected Integer createSnapshot() { return value; }
        @Override protected void revertToSnapshot(Integer s) { value = s; }
        @Override protected void onRootCommit(Integer s) { commits++; }
    }

    @Test
    void nothingOpenNothingDone() {
        assertEquals(0, TransactionGuard.check("test"));
    }

    @Test
    void commitsALeakedRootAndKeepsItsChange() {
        Slot slot = new Slot();
        slot.set(5, Transaction.open(null));   // leaked, as Curios does
        assertEquals(Transaction.Lifecycle.OPEN, Transaction.getLifecycle());
        assertEquals(1, TransactionGuard.check("test"));
        assertEquals(Transaction.Lifecycle.NONE, Transaction.getLifecycle());
        assertEquals(5, slot.value, "committed, not reverted: the hand-back stands");
        assertEquals(1, slot.commits);
        try (Transaction tx = Transaction.open(null)) {   // the next user opens fine
            slot.set(7, tx);
            tx.commit();
        }
        assertEquals(7, slot.value);
    }

    @Test
    void commitsNestedLeaksInnermostFirst() {
        Slot slot = new Slot();
        Transaction root = Transaction.open(null);
        slot.set(1, root);
        slot.set(2, Transaction.open(root));
        assertEquals(2, TransactionGuard.check("test"));
        assertEquals(Transaction.Lifecycle.NONE, Transaction.getLifecycle());
        assertEquals(2, slot.value);
    }
}
