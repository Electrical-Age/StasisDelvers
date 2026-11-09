package org.erisia.stasis;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * The Quern Minecart (λ-Nero 2026-11-09): a minecart carrying a rift quern, its field moving with it, so rails, powered
 * rails and the redstone beside them run round it in stopped land. It doesn't drive itself and isn't ridden (Nero: chain
 * a quern cart, a minecart and a furnace cart). A shard used on it joins the queue; sneak-use with an empty hand takes
 * the last one back; an empty hand shows its status.
 */
public class QuernMinecart extends AbstractMinecart implements QuernVehicle, QuernCore.Host {
    private static final EntityDataAccessor<Boolean> DATA_GRINDING = SynchedEntityData.defineId(QuernMinecart.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<ItemStack> DATA_SHARD = SynchedEntityData.defineId(QuernMinecart.class, EntityDataSerializers.ITEM_STACK);

    final QuernCore core = new QuernCore(this);

    public QuernMinecart(EntityType<? extends QuernMinecart> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder data) {
        super.defineSynchedData(data);
        data.define(DATA_GRINDING, false);
        data.define(DATA_SHARD, ItemStack.EMPTY);
    }

    @Override
    public QuernCore quern() {
        return core;
    }

    @Override
    public String quernName() {
        return "Quern minecart";
    }

    /** Client: grinding now (dust). */
    public boolean grindingClient() {
        return entityData.get(DATA_GRINDING);
    }

    /** Client: the shard it grinds. */
    public ItemStack shardClient() {
        return entityData.get(DATA_SHARD);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) QuernVehicles.clientDust(this, grindingClient(), 0.75);
    }

    // ---- QuernCore.Host

    @Override
    public double cx() {
        return getX();
    }

    @Override
    public double cy() {
        return getY() + 0.5;
    }

    @Override
    public double cz() {
        return getZ();
    }

    @Override
    public void dirty() {
    }

    @Override
    public void sync() {
        entityData.set(DATA_GRINDING, core.grinding());
        entityData.set(DATA_SHARD, core.burning().copy());
    }

    @Override
    public Entity boundTo() {
        return this;
    }

    // ---- use, drops, saves

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        InteractionResult r = QuernVehicle.use(this, player, hand);
        if (r != InteractionResult.PASS) return r;
        return QuernVehicle.takeOrStatus(this, player, hand);
    }

    @Override
    protected Item getDropItem() {
        return StasisRegistry.QUERN_MINECART.get();
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(StasisRegistry.QUERN_MINECART.get());
    }

    @Override
    public BlockState getDefaultDisplayBlockState() {
        return StasisRegistry.QUERN.get().defaultBlockState();
    }

    @Override
    public void remove(RemovalReason reason) {
        QuernVehicle.removed(this, reason);
        super.remove(reason);
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        QuernVehicle.save(this, out);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        QuernVehicle.load(this, in);
    }
}
