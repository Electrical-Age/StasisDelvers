package org.erisia.stasis;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A rift quern's workings, wherever it sits (docs/quern.md): the shard it grinds, its queue, and its field. The block
 * (QuernBlockEntity) and the quern vehicles (λ-Nero 2026-11-09: QuernMinecart, QuernBoat) each hold one and tick it
 * with where they are; room for a mini-tower later. A vehicle's field is bound to it (FieldData.bind) and moves with it.
 */
public final class QuernCore {
    /** Where the quern is, and what to tell when its state changes. */
    public interface Host {
        double cx();

        double cy();

        double cz();

        /** Saved state changed. */
        void dirty();

        /** Clients should see the new state (the shard, the queue, grinding). */
        void sync();

        /** The entity its field moves with, or null for a block. */
        default @Nullable Entity boundTo() {
            return null;
        }
    }

    public static final double RADIUS = 4;
    /** Baughn: 0.4, "plenty of reason to avoid it" (towers run at 0.6). */
    public static final double RATE = 0.4;
    public static final int QUEUE = 4;
    /** Baughn: ten minutes for charged shards, five for plain ones. */
    public static final int CHARGED_TICKS = 20 * 60 * 10, PLAIN_TICKS = 20 * 60 * 5;
    static final int WARN_TICKS = 20 * 30;

    final Host host;
    /** The shard being ground (shown hovering; spent), and the queue (shown in the table's corners). */
    ItemStack burning = ItemStack.EMPTY;
    final ItemStack[] queue = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    /** Ticks left of the shard being ground, of its total. */
    int burnLeft, burnTotal;
    /** Synced: grinding now (the field up). */
    boolean grinding;
    int fieldId = Stasis.NO_FIELD;

    public QuernCore(Host host) {
        this.host = host;
    }

    public static boolean isFuel(ItemStack s) {
        return s.is(StasisRegistry.RIFT_SHARD.get()) || s.is(StasisRegistry.CHARGED_RIFT_SHARD.get());
    }

    public static int ticksFor(ItemStack s) {
        return s.is(StasisRegistry.CHARGED_RIFT_SHARD.get()) ? CHARGED_TICKS : s.is(StasisRegistry.RIFT_SHARD.get()) ? PLAIN_TICKS : 0;
    }

    public ItemStack burning() {
        return burning;
    }

    public ItemStack queued(int i) {
        return queue[i];
    }

    public boolean grinding() {
        return grinding;
    }

    public int fieldId() {
        return fieldId;
    }

    /** 0..1 of the current shard left (the client shrinks it). */
    public float left() {
        return burnTotal <= 0 ? 0 : Math.max(0, Math.min(1, burnLeft / (float) burnTotal));
    }

    /** Tests: jump the clock of the shard being ground. */
    public void setBurnLeftForTests(int ticks) {
        burnLeft = ticks;
    }

    /** Would time run here at the quern's rate or faster without its own field? Then it doesn't grind. */
    boolean wanted(ServerLevel sl) {
        return Stasis.rateAtSteadyWithout(sl, (int) Math.floor(host.cx()), (int) Math.floor(host.cy()), (int) Math.floor(host.cz()), fieldId) < RATE;
    }

    /** One tick: start the next shard if one is due and wanted, grind, and keep the field up (or take it down). */
    /** Wanted at its last tick (true before its first: a status asked right after the first shard says nothing of waiting). */
    boolean lastWanted = true;

    public void tick(ServerLevel sl) {
        boolean want = wanted(sl);
        lastWanted = want;
        boolean was = grinding;
        ItemStack wasBurning = burning;
        if (burnLeft <= 0 && want) {
            burning = ItemStack.EMPTY;
            for (int i = 0; i < QUEUE; i++)
                if (!queue[i].isEmpty()) {
                    burning = queue[i];
                    System.arraycopy(queue, i + 1, queue, i, QUEUE - 1 - i);
                    queue[QUEUE - 1] = ItemStack.EMPTY;
                    burnTotal = burnLeft = ticksFor(burning);
                    sl.playSound(null, host.cx(), host.cy(), host.cz(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.8f, 0.7f);
                    break;
                }
        }
        grinding = want && burnLeft > 0;
        if (grinding) {
            burnLeft--;
            if (burnLeft == 0) burning = ItemStack.EMPTY;   // (spent; the next one starts next tick)
        }
        field(sl, grinding);
        if (grinding != was || burning != wasBurning || sl.getGameTime() % 100 == 0) {
            host.dirty();
            host.sync();
        }
    }

    /** The field while grinding, its hexes dropping out over its last WARN_TICKS, moved to where it is; none otherwise. */
    public void field(ServerLevel sl, boolean on) {
        FieldData fd = FieldData.get(sl);
        if (on) {
            double dropout = burnLeft < WARN_TICKS && queueEmpty() ? Math.round(12 * (1 - burnLeft / (double) WARN_TICKS)) / 15.0 : 0;
            Entity bound = host.boundTo();
            if (fieldId == Stasis.NO_FIELD || fd.get(fieldId).isEmpty()) {
                fieldId = fd.add(sl, host.cx(), host.cy(), host.cz(), RADIUS, false, RATE).id();
                // orange all over (Baughn: a much weaker field than a tower's)
                fd.update(sl, fieldId, x -> x.withShield(1, 0, 0));
                host.dirty();
            }
            if (bound != null) {
                fd.bind(sl, fieldId, bound);   // (again after a load, or when the entity's id changed)
                fd.move(sl, fieldId, host.cx(), host.cy(), host.cz());
            }
            var f = fd.get(fieldId);
            if (f.isPresent() && f.get().dropout() != dropout) fd.update(sl, fieldId, x -> x.withShield(1, dropout, 0));
        } else if (fieldId != Stasis.NO_FIELD) {
            fd.remove(sl, fieldId);
            fieldId = Stasis.NO_FIELD;
            host.dirty();
        }
    }

    public boolean queueEmpty() {
        for (ItemStack s : queue) if (!s.isEmpty()) return false;
        return true;
    }

    /** A player puts one shard in the queue; false if it's full or not fuel. */
    public boolean offer(ItemStack s) {
        if (!isFuel(s)) return false;
        for (int i = 0; i < QUEUE; i++)
            if (queue[i].isEmpty()) {
                queue[i] = s.copyWithCount(1);
                host.dirty();
                host.sync();
                return true;
            }
        return false;
    }

    /** The last queued shard back out (sneak-use), or empty. */
    public ItemStack takeLast() {
        for (int i = QUEUE - 1; i >= 0; i--)
            if (!queue[i].isEmpty()) {
                ItemStack s = queue[i];
                queue[i] = ItemStack.EMPTY;
                host.dirty();
                host.sync();
                return s;
            }
        return ItemStack.EMPTY;
    }

    /** Field time left in all its shards, the one grinding and the queue (Baughn: one total, not "x of y queued"). */
    public int ticksLeft() {
        int t = Math.max(0, burnLeft);
        for (ItemStack s : queue) t += ticksFor(s);
        return t;
    }

    /** Its status, "NAME: m:ss of field left." (name: "Rift quern", "Quern minecart"...). */
    public Component statusText(String name) {
        int t = ticksLeft();
        if (t == 0) return Component.literal(name + ": empty.");
        String why = !grinding && !lastWanted ? " (waiting: time runs here without it)" : "";
        return Component.literal(String.format("%s: %d:%02d of field left%s.", name, t / 1200, t / 20 % 60, why));
    }

    /** Puts slot's shard in place (a hopper), keeping the queue packed so the corners fill in order. */
    void setQueued(int slot, ItemStack stack) {
        if (slot < 0 || slot >= QUEUE) return;
        queue[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        for (int i = 0; i < QUEUE - 1; i++)
            if (queue[i].isEmpty())
                for (int j = i + 1; j < QUEUE; j++)
                    if (!queue[j].isEmpty()) {
                        queue[i] = queue[j];
                        queue[j] = ItemStack.EMPTY;
                        break;
                    }
        host.dirty();
        host.sync();
    }

    /** The queue, emptied (to drop when it's broken; the shard being ground is spent). */
    public java.util.List<ItemStack> takeQueue() {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (int i = 0; i < QUEUE; i++) {
            if (!queue[i].isEmpty()) out.add(queue[i]);
            queue[i] = ItemStack.EMPTY;
        }
        return out;
    }

    public void save(ValueOutput out) {
        if (!burning.isEmpty()) out.store("burning", ItemStack.CODEC, burning);
        for (int i = 0; i < QUEUE; i++) if (!queue[i].isEmpty()) out.store("q" + i, ItemStack.CODEC, queue[i]);
        out.putInt("left", burnLeft);
        out.putInt("total", burnTotal);
        out.putBoolean("grinding", grinding);
        out.putInt("field", fieldId);
    }

    public void load(ValueInput in) {
        burning = in.read("burning", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        for (int i = 0; i < QUEUE; i++) queue[i] = in.read("q" + i, ItemStack.CODEC).orElse(ItemStack.EMPTY);
        burnLeft = in.getIntOr("left", 0);
        burnTotal = in.getIntOr("total", 0);
        grinding = in.getBooleanOr("grinding", false);
        fieldId = in.getIntOr("field", Stasis.NO_FIELD);
    }
}
