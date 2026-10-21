package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The rift quern (Baughn 2026-10-21; notes/quern/PLAN.md): a table that grinds rift shards and, while it grinds, holds a
 * small field round itself: a sphere of {@link #RADIUS} running at {@link #RATE}. A charged shard lasts 10 minutes, a
 * plain one 5. It holds the shard it grinds and a queue of {@link #QUEUE} more (hoppers may fill the queue, nothing comes
 * out). It replaces the tower yard as the way to start EA machinery in stopped land (config towerYard, off).
 * <ul>
 *   <li>It grinds only where time would run slower than its own field without it: in stopped land, not inside a tower's
 *       field or running land, so shards aren't wasted in a base. It pauses (its field gone) until that changes.</li>
 *   <li>A shard is spent once it starts: breaking the quern drops only the queue.</li>
 *   <li>Its last {@link #WARN_TICKS} show as a failing field (the shield's flicker).</li>
 *   <li>It counts down only while its chunk is loaded (it loads no chunks), and pistons can't move it.</li>
 * </ul>
 */
public class QuernBlockEntity extends BlockEntity implements WorldlyContainer {
    public static final double RADIUS = 4;
    /** Baughn: 0.4, "plenty of reason to avoid it" (towers run at 0.6). */
    public static final double RATE = 0.4;
    public static final int QUEUE = 4;
    /** Baughn: ten minutes for charged shards, five for plain ones. */
    public static final int CHARGED_TICKS = 20 * 60 * 10, PLAIN_TICKS = 20 * 60 * 5;
    static final int WARN_TICKS = 20 * 30;
    static final int[] SLOTS = {0, 1, 2, 3};

    /** The shard being ground (shown hovering; spent), and the queue (shown in the table's corners). */
    ItemStack burning = ItemStack.EMPTY;
    final ItemStack[] queue = {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY};
    /** Ticks left of the shard being ground, of its total. */
    int burnLeft, burnTotal;
    /** Synced: grinding now (the field up). */
    boolean grinding;
    int fieldId = Stasis.NO_FIELD;

    /** Client: the querns loaded (dust particles). */
    public static final java.util.Map<BlockPos, QuernBlockEntity> CLIENT = new java.util.concurrent.ConcurrentHashMap<>();

    public QuernBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.QUERN_ENTITY.get(), pos, state);
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

    /** 0..1 of the current shard left (the client shrinks it). */
    public float left() {
        return burnTotal <= 0 ? 0 : Math.max(0, Math.min(1, burnLeft / (float) burnTotal));
    }

    public int fieldId() {
        return fieldId;
    }

    /** Tests: jump the clock of the shard being ground. */
    public void setBurnLeftForTests(int ticks) {
        burnLeft = ticks;
    }

    /** Would time run here at the quern's rate or faster without its own field? Then it doesn't grind. */
    boolean wanted(ServerLevel sl) {
        return Stasis.rateAtSteadyWithout(sl, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), fieldId) < RATE;
    }

    void serverTick(ServerLevel sl) {
        boolean want = wanted(sl);
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
                    sl.playSound(null, worldPosition, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.8f, 0.7f);
                    break;
                }
        }
        grinding = want && burnLeft > 0;
        if (grinding) {
            burnLeft--;
            if (burnLeft == 0) burning = ItemStack.EMPTY;   // (spent; the next one starts next tick)
        }
        field(sl, grinding);
        if (grinding != was || burning != wasBurning || sl.getGameTime() % 100 == 0) sync(sl);
    }

    /** The field while grinding, failing in its last WARN_TICKS (the flicker); none otherwise. */
    void field(ServerLevel sl, boolean on) {
        FieldData fd = FieldData.get(sl);
        if (on) {
            double failing = burnLeft < WARN_TICKS && queueEmpty() ? 0.5 : 0;
            if (fieldId == Stasis.NO_FIELD || fd.get(fieldId).isEmpty()) {
                fieldId = fd.add(sl, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, RADIUS, false, RATE).id();
                setChanged();
            }
            var f = fd.get(fieldId);
            if (f.isPresent() && f.get().failing() != failing) fd.update(sl, fieldId, x -> x.withFailing(failing));
        } else if (fieldId != Stasis.NO_FIELD) {
            fd.remove(sl, fieldId);
            fieldId = Stasis.NO_FIELD;
            setChanged();
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
                changed();
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
                changed();
                return s;
            }
        return ItemStack.EMPTY;
    }

    public Component statusText() {
        int n = 0;
        for (ItemStack s : queue) if (!s.isEmpty()) n++;
        String now = burnLeft > 0 ? String.format("%d:%02d left of this shard", burnLeft / 1200, burnLeft / 20 % 60) : "nothing grinding";
        String why = burnLeft > 0 && !grinding ? " (paused: time runs here without it)" : "";
        return Component.literal("Rift quern: " + now + why + "; " + n + " of " + QUEUE + " queued.");
    }

    void changed() {
        setChanged();
        if (level instanceof ServerLevel sl) sync(sl);
    }

    void sync(ServerLevel sl) {
        setChanged();
        sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    // ---- hoppers: shards into the queue, nothing out

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return false;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < QUEUE && queue[slot].isEmpty() && isFuel(stack);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public int getContainerSize() {
        return QUEUE;
    }

    @Override
    public boolean isEmpty() {
        return queueEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < QUEUE ? queue[slot] : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        return ItemStack.EMPTY;   // (nothing comes out but by hand)
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot < 0 || slot >= QUEUE) return;
        queue[slot] = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        // (a hopper fills the slot it was given: keep the queue packed so the corners fill in order)
        for (int i = 0; i < QUEUE - 1; i++)
            if (queue[i].isEmpty())
                for (int j = i + 1; j < QUEUE; j++)
                    if (!queue[j].isEmpty()) {
                        queue[i] = queue[j];
                        queue[j] = ItemStack.EMPTY;
                        break;
                    }
        changed();
    }

    @Override
    public boolean stillValid(Player player) {
        return false;
    }

    @Override
    public void clearContent() {
        java.util.Arrays.fill(queue, ItemStack.EMPTY);
    }

    // ---- life cycle

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) CLIENT.put(worldPosition, this);
    }

    /** Unloaded or broken: its field goes too (a neighbour chunk inside it would run on with nothing grinding); the next
     *  tick after loading puts it back. */
    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) CLIENT.remove(worldPosition, this);
        if (level instanceof ServerLevel sl) field(sl, false);
        super.setRemoved();
    }

    /** Broken: the queue drops; the shard being ground is spent. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null)
            for (ItemStack s : queue) if (!s.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, s);
        clearContent();
        super.preRemoveSideEffects(pos, state);
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (!burning.isEmpty()) out.store("burning", ItemStack.CODEC, burning);
        for (int i = 0; i < QUEUE; i++) if (!queue[i].isEmpty()) out.store("q" + i, ItemStack.CODEC, queue[i]);
        out.putInt("left", burnLeft);
        out.putInt("total", burnTotal);
        out.putBoolean("grinding", grinding);
        out.putInt("field", fieldId);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        burning = in.read("burning", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        for (int i = 0; i < QUEUE; i++) queue[i] = in.read("q" + i, ItemStack.CODEC).orElse(ItemStack.EMPTY);
        burnLeft = in.getIntOr("left", 0);
        burnTotal = in.getIntOr("total", 0);
        grinding = in.getBooleanOr("grinding", false);
        fieldId = in.getIntOr("field", Stasis.NO_FIELD);
    }
}
