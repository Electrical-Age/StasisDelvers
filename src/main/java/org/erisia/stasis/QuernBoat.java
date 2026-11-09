package org.erisia.stasis;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * The Quern Boat (λ-Nero 2026-11-09): a chest boat (any vanilla wood, or the bamboo chest raft) carrying a rift quern
 * where the chest sat, its field moving with it, so the stilled water ahead of it thaws and it sails across stopped
 * water; it keeps its chest. A shard used on it joins the queue (its status shows on the action bar); otherwise it is a
 * chest boat: use to ride, sneak-use for the chest. Its rider runs at full speed in its field (PlayerRates).
 */
public class QuernBoat extends AbstractChestBoat implements QuernVehicle, QuernCore.Host {
    private static final EntityDataAccessor<Boolean> DATA_GRINDING = SynchedEntityData.defineId(QuernBoat.class, EntityDataSerializers.BOOLEAN);

    final QuernCore core = new QuernCore(this);
    final boolean raft;

    public QuernBoat(EntityType<? extends QuernBoat> type, Level level, java.util.function.Supplier<Item> drop, boolean raft) {
        super(type, level, drop);
        this.raft = raft;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder data) {
        super.defineSynchedData(data);
        data.define(DATA_GRINDING, false);
    }

    @Override
    protected double rideHeight(EntityDimensions dimensions) {
        return raft ? dimensions.height() * 0.8888889f : dimensions.height() / 3.0f;   // (as ChestRaft, ChestBoat)
    }

    @Override
    public QuernCore quern() {
        return core;
    }

    @Override
    public String quernName() {
        return raft ? "Quern raft" : "Quern boat";
    }

    public boolean grindingClient() {
        return entityData.get(DATA_GRINDING);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) QuernVehicles.clientDust(this, grindingClient(), 0.9);
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
    }

    @Override
    public Entity boundTo() {
        return this;
    }

    // ---- use, drops, saves

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        InteractionResult r = QuernVehicle.use(this, player, hand);
        if (r == InteractionResult.SUCCESS_SERVER) player.sendOverlayMessage(core.statusText(quernName()));
        if (r != InteractionResult.PASS) return r;
        return super.interact(player, hand, location);
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
