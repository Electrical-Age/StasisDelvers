package org.erisia.stasis;

import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Commits transactions that another mod opened and never closed, before the next user of the transfer API trips on them.
 * <p>
 * Curios 17.0.0-beta.3 calls {@code Transaction.open(null)} without closing it in two places: handing a curio that no
 * longer fits its slot back to the player's inventory ({@code CurioInventoryCapability.handleInvalidStacks}, every player
 * tick, so right at login), and a right-click swap into an occupied slot ({@code CuriosCommonEvents.curioRightClick}).
 * The root transaction stays open on the server thread, and the next {@code Transaction.open} anywhere throws "A root
 * transaction ... is already active": with removeErroringBlockEntities that deleted 55 block entities on Incognito
 * (2026-11-04 13:51, Nero's login; ErisiAgent asked for a guard), without it the server crashes (2026-10-26).
 * <p>
 * Both leakers changed their inventories inside the transaction and expect that to stand, so the guard <b>commits</b>
 * (aborting would undo the hand-back, and the item would be lost, or undo a swap's extract and duplicate it). It runs
 * at the lowest priority right after each player tick and each right-click (Curios' handlers run then, and entities tick
 * before block entities in the same level tick), and at the start of each level and server tick as a catch-all. The
 * transfer API is single-threaded per thread and never legitimately spans an event, so an open transaction there is
 * always a leak. Server side only.
 */
public final class TransactionGuard {
    private TransactionGuard() {
    }

    private static long warned;

    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, EntityTickEvent.Post.class, e -> {
            if (e.getEntity() instanceof Player p && !p.level().isClientSide()) check("after a player tick");
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, PlayerInteractEvent.RightClickItem.class, e -> {
            if (!e.getLevel().isClientSide()) check("after a right-click");
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, LevelTickEvent.Pre.class, e -> {
            if (!e.getLevel().isClientSide()) check("at a level tick");
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ServerTickEvent.Pre.class, e -> check("at a server tick"));
    }

    /** Commits every leaked transaction on this thread, innermost first. Returns how many. */
    public static int check(String where) {
        if (Transaction.getLifecycle() != Transaction.Lifecycle.OPEN) return 0;
        int n = 0;
        while (Transaction.getLifecycle() == Transaction.Lifecycle.OPEN && n < 64) {
            Transaction t = (Transaction) Transaction.getCurrentOpenedTransaction();
            String owner = owner(t);
            try {
                t.commit();
            } catch (RuntimeException ex) {
                StasisDelvers.LOG.error("Leaked transaction of {} could not be committed {}", owner, where, ex);
                return n;
            }
            n++;
            // (a leaker that runs every tick would flood the log: at most one line a minute)
            long now = System.currentTimeMillis();
            if (now - warned > 60_000) {
                warned = now;
                StasisDelvers.LOG.warn("Committed a transaction {} left open (depth {}) {}: a mod forgot to close it",
                    owner, t.depth(), where);
            }
        }
        return n;
    }

    /** The class that opened it (Transaction.callerClass is package-private; for the log only). */
    private static String owner(Transaction t) {
        try {
            var f = Transaction.class.getDeclaredField("callerClass");
            f.setAccessible(true);
            Object c = f.get(t);
            return c instanceof Class<?> k ? k.getName() : "an unknown class";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return "an unknown class";
        }
    }
}
