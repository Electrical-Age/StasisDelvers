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
 */
public class Statue extends Entity {
    /** Synced for the client's model: whose skin to show. */
    static final net.minecraft.network.syncher.EntityDataAccessor<String> OWNER_ID =
        SynchedEntityData.defineId(Statue.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
    static final net.minecraft.network.syncher.EntityDataAccessor<String> OWNER_NAME =
        SynchedEntityData.defineId(Statue.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);

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
        syncOwner();
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        out.store("owner", UUIDUtil.CODEC, owner);
        out.putString("owner_name", ownerName);
        out.store("items", ItemStack.CODEC.listOf(), List.copyOf(items));
        out.putInt("xp", xp);
    }
}
