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
    /** Each item's slot in its owner's inventory when it was taken (-1: not known), so it goes back there (Baughn
     *  2026-10-22: like the Corpse mod). Parallel to items. */
    private final java.util.List<Integer> slots = new java.util.ArrayList<>();
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
            if (!st.isEmpty()) s.add(st, i);
        }
        s.xp = points(player);
        player.totalExperience = 0;
        player.experienceLevel = 0;
        player.experienceProgress = 0;
        return s;
    }

    /** What a player's death gives back in experience: as much as vanilla drops (Baughn), level x 7, at most 100. */
    public static int points(Player p) {
        return Math.min(p.experienceLevel * 7, 100);
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

    public String ownerName() {
        return ownerName;
    }

    /**
     * A statue again, from a possessed one (PossessedStatue.revert): exactly these items and this experience, owner and
     * name, standing or fallen, at (x, y, z).
     */
    public static Statue restore(ServerLevel level, UUID owner, String name, List<ItemStack> items, int xp, double x, double y, double z,
                          float yaw, boolean fallen) {
        Statue s = new Statue(StasisRegistry.STATUE.get(), level);
        s.snapTo(x, Math.max(y, level.getMinY() + 1), z, yaw, 0f);
        s.owner = owner;
        s.ownerName = name;
        s.setCustomName(Component.literal(name));
        s.setCustomNameVisible(true);
        s.syncOwner();
        s.items.addAll(items);
        s.xp = Math.max(0, xp);
        if (fallen) s.setFallen(true);
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
     * Runs only where time runs (StasisEvents cancels frozen ticks): lie down, and fall, also when what it lay on is taken
     * away. Only the server moves it (a client of its own fell where the server's statue lay still, then snapped back:
     * AdventurAgent); the 4-tick position updates make the fall smooth enough.
     */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        setFallen(true);
        Vec3 v = getDeltaMovement();
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

    /** Smooth between the server's 4-tick position updates (a plain Entity snaps: AdventurAgent saw a 5 Hz fall). */
    @Override
    protected net.minecraft.world.entity.InterpolationHandler createInterpolationHandler() {
        return net.minecraft.world.entity.LinearInterpolationHandler.create(this, 4);
    }

    /** Where it will come to rest if it's still falling: the top of the first solid block below (water doesn't hold it). */
    public net.minecraft.core.BlockPos restingPlace() {
        if (onGround() || !fallen() || isRemoved()) return blockPosition();   // (unloaded: don't load its chunk to look)
        var pos = blockPosition().mutable();
        while (pos.getY() > level().getMinY() + 1 && level().getBlockState(pos.below()).getCollisionShape(level(), pos.below()).isEmpty())
            pos.move(net.minecraft.core.Direction.DOWN);
        return pos.immutable();
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
        return fallen() ? net.minecraft.world.entity.EntityDimensions.fixed(1.6f, 0.5f) : super.getDimensions(pose);
    }

    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (FALLEN.equals(accessor)) refreshDimensions();
    }

    public UUID owner() {
        return owner;
    }

    /** Add an item that came from inventory slot slot (-1: unknown). */
    public void add(ItemStack st, int slot) {
        items.add(st);
        while (slots.size() < items.size() - 1) slots.add(-1);
        slots.add(slot);
    }

    int slotOf(int i) {
        return i < slots.size() ? slots.get(i) : -1;
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
        // sneaking: a chest of its things, to take some (Baughn: like the Corpse mod); else all of it, back in place
        if (player.isSecondaryUseActive() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
            open(sp);
            return InteractionResult.SUCCESS_SERVER;
        }
        crumble(level, player);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Give everything back to the player who broke the statue, each item into the slot it came from when that slot is
     * free (armour onto them, the hotbar as it was; Baughn: like the Corpse mod), the rest anywhere (what doesn't fit
     * drops), and remove it.
     */
    public void crumble(ServerLevel level, Player player) {
        var inv = player.getInventory();
        List<ItemStack> rest = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            int slot = slotOf(i);
            if (slot >= 0 && slot < inv.getContainerSize() && inv.getItem(slot).isEmpty()) inv.setItem(slot, st.copy());
            else rest.add(st);
        }
        for (ItemStack st : rest) inv.placeItemBackInInventory(st.copy(), net.minecraft.util.Prediction.SERVER_ONLY);
        items.clear();
        slots.clear();
        if (xp > 0) player.giveExperiencePoints(xp);
        xp = 0;
        discard();
    }

    /** The statue's things in a chest window (54 slots): take what you like; what's left stays in the statue, which
     *  crumbles once it's empty. Its experience comes with the first look. */
    void open(net.minecraft.server.level.ServerPlayer player) {
        int n = Math.min(54, items.size());
        net.minecraft.world.SimpleContainer box = new net.minecraft.world.SimpleContainer(54);
        int[] from = new int[54];
        java.util.Arrays.fill(from, -1);
        for (int i = 0; i < n; i++) {
            box.setItem(i, items.get(i));
            from[i] = slotOf(i);
        }
        List<ItemStack> hidden = new java.util.ArrayList<>(items.subList(n, items.size()));
        List<Integer> hiddenSlots = new java.util.ArrayList<>();
        for (int i = n; i < items.size(); i++) hiddenSlots.add(slotOf(i));
        if (xp > 0) {
            player.giveExperiencePoints(xp);
            xp = 0;
        }
        Statue self = this;
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, pinv, pl) -> new net.minecraft.world.inventory.ChestMenu(
            net.minecraft.world.inventory.MenuType.GENERIC_9x6, id, pinv, box, 6) {
            @Override
            public void removed(Player p) {
                super.removed(p);
                // what's left goes back into the statue (an item still where it was keeps its old slot)
                List<ItemStack> was = List.copyOf(self.items);
                self.items.clear();
                self.slots.clear();
                for (int i = 0; i < 54; i++) {
                    ItemStack st = box.getItem(i);
                    if (st.isEmpty()) continue;
                    self.add(st, i < n && i < was.size() && st == was.get(i) ? from[i] : -1);
                }
                for (int i = 0; i < hidden.size(); i++) self.add(hidden.get(i), hiddenSlots.get(i));
                if (self.items.isEmpty() && !self.isRemoved()) self.discard();
            }

            @Override
            public boolean stillValid(Player p) {
                return !self.isRemoved() && p.distanceToSqr(self) < 64;
            }
        }, Component.literal(ownerName)));
    }

    /** Give back everything where the statue stands, and remove it. */
    public void crumble(ServerLevel level) {
        for (ItemStack st : items) {
            ItemEntity e = new ItemEntity(level, getX(), getY() + 0.5, getZ(), st.copy());
            e.setPickUpDelay(10);
            level.addFreshEntity(e);
        }
        items.clear();
        slots.clear();
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
        slots.clear();
        in.read("slots", com.mojang.serialization.Codec.INT.listOf()).ifPresent(slots::addAll);   // (older statues: none)
        xp = in.getIntOr("xp", 0);
        setFallen(in.getBooleanOr("fallen", false));
        syncOwner();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        out.store("owner", UUIDUtil.CODEC, owner);
        out.putString("owner_name", ownerName);
        out.store("items", ItemStack.CODEC.listOf(), List.copyOf(items));
        List<Integer> sl = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) sl.add(slotOf(i));
        out.store("slots", com.mojang.serialization.Codec.INT.listOf(), sl);
        out.putInt("xp", xp);
        out.putBoolean("fallen", fallen());
    }
}
