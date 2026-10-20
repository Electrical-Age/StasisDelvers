package org.erisia.stasis;

import net.minecraft.core.NonNullList;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.syncher.SynchedEntityData;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What's left of a player frozen in time: holds their inventory and experience where they stood (or hung, mid-fall).
 * It can't be hurt, pushed or blown up and never despawns. Its owner (or a creative-mode player) right-clicks it to
 * get everything back; the statue then crumbles.
 * <p>
 * Remains (Baughn: "reimplement Corpse"): any player death leaves one ({@link Remains}). Where time runs it falls and
 * lies down ({@link #fallen()}, once its first running tick comes), and stays lying if time stops again. It never
 * falls out of the world, never goes through a portal and never burns.
 */
public class Statue extends Entity {
    /** Synced for the client's model: whose skin to show. */
    static final net.minecraft.network.syncher.EntityDataAccessor<String> OWNER_ID =
        SynchedEntityData.defineId(Statue.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
    static final net.minecraft.network.syncher.EntityDataAccessor<String> OWNER_NAME =
        SynchedEntityData.defineId(Statue.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
    /** Lying down: time ran where it stood (or it was left where time runs). */
    static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> FALLEN =
        SynchedEntityData.defineId(Statue.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);

    private UUID owner = UUID.randomUUID();
    private String ownerName = "";
    private NonNullList<ItemStack> items = NonNullList.create();
    private int xp;

    public Statue(EntityType<? extends Statue> type, Level level) {
        super(type, level);
        this.setNoGravity(true);
    }

    public static Statue of(ServerLevel level, Player player) {
        Statue s = new Statue(StasisRegistry.STATUE.get(), level);
        s.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0f);
        s.owner = player.getUUID();
        s.ownerName = player.getName().getString();
        s.setCustomName(Component.literal(s.ownerName));   // just the name (Baughn: creepier)
        s.setCustomNameVisible(true);
        s.syncOwner();
        // keepInventory (Baughn): the player keeps gear and experience; the statue is only a statue
        if (level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY)) return s;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.removeItemNoUpdate(i);
            if (!st.isEmpty()) s.items.add(st);
        }
        s.xp = player.totalExperience;
        player.totalExperience = 0;
        player.experienceLevel = 0;
        player.experienceProgress = 0;
        return s;
    }

    /** The remains of a death that wasn't a freeze: the drops are added by {@link Remains#drops}. */
    static Statue remains(ServerLevel level, Player player, int xp) {
        Statue s = new Statue(StasisRegistry.STATUE.get(), level);
        double y = Math.max(player.getY(), level.getMinY() + 1);   // (a death in the void: kept at the floor of the world)
        s.snapTo(player.getX(), y, player.getZ(), player.getYRot(), 0f);
        s.owner = player.getUUID();
        s.ownerName = player.getName().getString();
        s.setCustomName(Component.literal(s.ownerName));
        s.setCustomNameVisible(true);
        s.syncOwner();
        s.xp = Math.max(xp, 0);
        return s;
    }

    public boolean fallen() {
        return entityData.get(FALLEN);
    }

    void setFallen(boolean f) {
        if (f == fallen()) return;
        entityData.set(FALLEN, f);
        refreshDimensions();
    }

    /**
     * Runs only where time runs (StasisEvents cancels frozen ticks, on both sides): lie down, and fall. Both sides move it,
     * so the client's fall is smooth; the server's position wins.
     */
    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) setFallen(true);
        if (!fallen()) return;
        Vec3 v = getDeltaMovement();
        if (onGround() && v.lengthSqr() < 1e-6) return;
        v = new Vec3(v.x * 0.5, isInWater() ? Math.max(v.y - 0.01, -0.1) : v.y - 0.04, v.z * 0.5);
        move(net.minecraft.world.entity.MoverType.SELF, v);
        setDeltaMovement(onGround() ? Vec3.ZERO : v.scale(0.98));
        keepInWorld();
    }

    /** Never below the floor of the world (vanilla would discard it there, items and all). */
    private void keepInWorld() {
        double floor = level().getMinY() + 1;
        if (getY() < floor) {
            setPos(getX(), floor, getZ());
            setDeltaMovement(Vec3.ZERO);
        }
    }

    @Override
    protected void onBelowWorld() {
        keepInWorld();
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    @Override
    public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
        return fallen() ? net.minecraft.world.entity.EntityDimensions.fixed(1.0f, 0.5f) : super.getDimensions(pose);
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (FALLEN.equals(accessor)) refreshDimensions();
    }

    public UUID owner() {
        return owner;
    }

    public List<ItemStack> items() {
        return items;
    }

    public int xp() {
        return xp;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(OWNER_ID, "");
        b.define(OWNER_NAME, "");
        b.define(FALLEN, false);
    }

    private void syncOwner() {
        entityData.set(OWNER_ID, owner.toString());
        entityData.set(OWNER_NAME, ownerName);
    }

    /** Client: the owner's id and name (for the skin). */
    public String syncedOwnerId() {
        return entityData.get(OWNER_ID);
    }

    public String syncedOwnerName() {
        return entityData.get(OWNER_NAME);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        // (AdventurAgent: players hit it first, and nothing said how to free the gear)
        if (source.getEntity() instanceof net.minecraft.server.level.ServerPlayer p)
            p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("A frozen statue: right-click it to free what it holds"));
        return false;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return true;
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        if (!(level() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        if (!player.getUUID().equals(owner) && !player.isCreative()) {
            player.sendOverlayMessage(Component.literal("This statue won't move for you."));
            return InteractionResult.FAIL;
        }
        crumble(level, player);
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Give everything back to the player who broke the statue (what doesn't fit drops), and remove it. */
    public void crumble(ServerLevel level, Player player) {
        for (ItemStack st : items) player.getInventory().placeItemBackInInventory(st.copy(), net.minecraft.util.Prediction.SERVER_ONLY);
        items.clear();
        if (xp > 0) player.giveExperiencePoints(xp);
        xp = 0;
        discard();
    }

    /** Give back everything where the statue stands, and remove it. */
    public void crumble(ServerLevel level) {
        for (ItemStack st : items) {
            ItemEntity e = new ItemEntity(level, getX(), getY() + 0.5, getZ(), st.copy());
            e.setPickUpDelay(10);
            level.addFreshEntity(e);
        }
        items.clear();
        if (xp > 0) ExperienceOrb.award(level, position(), xp);
        xp = 0;
        discard();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        in.read("owner", UUIDUtil.CODEC).ifPresent(u -> owner = u);
        ownerName = in.getStringOr("owner_name", "");
        Optional<List<ItemStack>> list = in.read("items", ItemStack.CODEC.listOf());
        items = NonNullList.create();
        list.ifPresent(items::addAll);
        xp = in.getIntOr("xp", 0);
        setFallen(in.getBooleanOr("fallen", false));
        syncOwner();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        out.store("owner", UUIDUtil.CODEC, owner);
        out.putString("owner_name", ownerName);
        out.store("items", ItemStack.CODEC.listOf(), List.copyOf(items));
        out.putInt("xp", xp);
        out.putBoolean("fallen", fallen());
    }
}
