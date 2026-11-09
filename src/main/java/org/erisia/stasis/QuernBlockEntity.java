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
 *   <li>Its last {@link #WARN_TICKS} its shield's hexes drop out, more and more (a failing field's flicker was lost in
 *       the orange shield's pulse: AdventurAgent).</li>
 *   <li>It counts down only while its chunk is loaded (it loads no chunks), and pistons can't move it.</li>
 * </ul>
 */
public class QuernBlockEntity extends BlockEntity implements WorldlyContainer, QuernCore.Host {
    public static final double RADIUS = QuernCore.RADIUS;
    public static final double RATE = QuernCore.RATE;
    public static final int QUEUE = QuernCore.QUEUE;
    public static final int CHARGED_TICKS = QuernCore.CHARGED_TICKS, PLAIN_TICKS = QuernCore.PLAIN_TICKS;
    static final int[] SLOTS = {0, 1, 2, 3};

    /** Its workings (QuernCore: shared with the quern vehicles). */
    final QuernCore core = new QuernCore(this);

    /** Client: the querns loaded (dust particles). */
    public static final java.util.Map<BlockPos, QuernBlockEntity> CLIENT = new java.util.concurrent.ConcurrentHashMap<>();

    public QuernBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.QUERN_ENTITY.get(), pos, state);
    }

    public static boolean isFuel(ItemStack s) {
        return QuernCore.isFuel(s);
    }

    public static int ticksFor(ItemStack s) {
        return QuernCore.ticksFor(s);
    }

    public ItemStack burning() {
        return core.burning();
    }

    public ItemStack queued(int i) {
        return core.queued(i);
    }

    public boolean grinding() {
        return core.grinding();
    }

    /** 0..1 of the current shard left (the client shrinks it). */
    public float left() {
        return core.left();
    }

    public int fieldId() {
        return core.fieldId();
    }

    /** Tests: jump the clock of the shard being ground. */
    public void setBurnLeftForTests(int ticks) {
        core.setBurnLeftForTests(ticks);
    }

    // ---- QuernCore.Host

    @Override
    public double cx() {
        return worldPosition.getX() + 0.5;
    }

    @Override
    public double cy() {
        return worldPosition.getY() + 0.5;
    }

    @Override
    public double cz() {
        return worldPosition.getZ() + 0.5;
    }

    @Override
    public void dirty() {
        setChanged();
    }

    @Override
    public void sync() {
        if (level instanceof ServerLevel sl) sync(sl);
    }

    /** Client: sparkling dust dripping off the crystal while it grinds (Baughn): fine specks, white and violet, falling
     *  into the bowl; now and then a spark where a laser meets it. (Falling amethyst dust piled into a solid purple blob
     *  that hid the bowl and the crystal: AdventurAgent.) */
    void clientTick() {
        if (!core.grinding || core.burning.isEmpty() || level == null) return;
        var r = level.getRandom();
        double x = worldPosition.getX() + 0.5, y = worldPosition.getY() + 1.08, z = worldPosition.getZ() + 0.5;
        if (r.nextInt(2) == 0)
            level.addParticle(new net.minecraft.core.particles.DustParticleOptions(r.nextInt(3) == 0 ? 0xF4EEFF : 0xC090FF, 0.25f + 0.15f * r.nextFloat()),
                x + (r.nextDouble() - 0.5) * 0.12, y, z + (r.nextDouble() - 0.5) * 0.12, 0, -0.04, 0);
        if (r.nextInt(6) == 0)
            level.addParticle(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK,
                x + (r.nextDouble() - 0.5) * 0.2, y + 0.04 + (r.nextDouble() - 0.5) * 0.15, z + (r.nextDouble() - 0.5) * 0.2, 0, 0, 0);
    }

    void serverTick(ServerLevel sl) {
        core.tick(sl);
    }

    void field(ServerLevel sl, boolean on) {
        core.field(sl, on);
    }

    public boolean queueEmpty() {
        return core.queueEmpty();
    }

    /** A player puts one shard in the queue; false if it's full or not fuel. */
    public boolean offer(ItemStack s) {
        return core.offer(s);
    }

    /** The last queued shard back out (sneak-use), or empty. */
    public ItemStack takeLast() {
        return core.takeLast();
    }

    /** Field time left in all its shards, the one grinding and the queue (Baughn: one total, not "x of y queued"). */
    public int ticksLeft() {
        return core.ticksLeft();
    }

    public Component statusText() {
        return core.statusText("Rift quern");
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
        return slot >= 0 && slot < QUEUE && core.queue[slot].isEmpty() && isFuel(stack);
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
        return slot >= 0 && slot < QUEUE ? core.queue[slot] : ItemStack.EMPTY;
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
        core.setQueued(slot, stack);
    }

    @Override
    public boolean stillValid(Player player) {
        return false;
    }

    @Override
    public void clearContent() {
        java.util.Arrays.fill(core.queue, ItemStack.EMPTY);
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
            for (ItemStack s : core.takeQueue()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, s);
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
        core.save(out);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        core.load(in);
    }
}
