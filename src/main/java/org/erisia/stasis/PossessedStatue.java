package org.erisia.stasis;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A statue a Puppeteer has taken (notes/fauna/F4-DESIGN.md; Baughn and λ-Nero, OPTIONS.md). It hunts its owner: their own
 * body, in their own gear, in stop-motion. <b>Mission statement (λ-Nero): it never loses a player's gear.</b>
 * <ul>
 *   <li>It carries the statue's items and experience; armour is worn (looks, toughness), tools stay in the list
 *       (Baughn: they don't know how to use tools).</li>
 *   <li>Every end is {@link #revert}: a Statue with exactly the items is added <b>first</b>; only if that worked does this
 *       one go. Killed (in its owner's chunk, by a player), /kill, a field growing over it, the void, a lost owner.</li>
 *   <li>Damage only from players while it is in its owner's chunk (λ-Nero); nothing else hurts it.</li>
 *   <li>It loads its 3x3 chunks ({@link #TICKETS}, owned by its UUID, saved; the validator keeps only those in
 *       {@link Data}) and is never removed by the usual mob rules (persistent, not a Monster: peaceful keeps it).</li>
 *   <li>Owner offline, in another dimension, or unreachable for {@link #LOST_TICKS} of chasing: it goes home (teleports
 *       to where it was taken) and stands again as a statue there. Owner dies: 50 % it hunts them at their respawn (a
 *       field's edge stops it), else home (Baughn).</li>
 *   <li>Stop-motion bursts as a possessed mob ({@link #running}); stuck for 5 s of bursts, it blinks up to 8 blocks
 *       towards its owner (λ-Nero: a cheat teleport out of holes). The fauna wall holds.</li>
 * </ul>
 */
public class PossessedStatue extends PathfinderMob {
    static final EntityDataAccessor<String> OWNER_ID = SynchedEntityData.defineId(PossessedStatue.class, EntityDataSerializers.STRING);
    static final EntityDataAccessor<String> OWNER_NAME = SynchedEntityData.defineId(PossessedStatue.class, EntityDataSerializers.STRING);

    public static final int LOST_TICKS = 2400;
    static final int STUCK_TICKS = 100;
    static final float HIT = 3;
    static final double SENSE = 64;

    private UUID owner = new UUID(0, 0);
    private String ownerName = "";
    private final List<ItemStack> items = new ArrayList<>();
    private int xp;
    private BlockPos home = BlockPos.ZERO;
    private float homeYaw;
    /** Going home (the owner died and the roll said so): saved. */
    private boolean homeward;

    // memory only
    long burstUntil, nextBurst;
    private BlockPos lastStopped;
    private Vec3 progressAt;
    private int stuckTicks, lostTicks, hitCooldown;
    private double bestDist = Double.MAX_VALUE;
    private long ticketChunk = Long.MIN_VALUE;

    public PossessedStatue(EntityType<? extends PossessedStatue> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(false);
        for (EquipmentSlot s : EquipmentSlot.values()) setDropChance(s, 0f);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 40.0).add(Attributes.MOVEMENT_SPEED, 0.25)
            .add(Attributes.FOLLOW_RANGE, 48.0).add(Attributes.ATTACK_DAMAGE, HIT);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(OWNER_ID, "");
        b.define(OWNER_NAME, "");
    }

    public String syncedOwnerId() {
        return entityData.get(OWNER_ID);
    }

    public String syncedOwnerName() {
        return entityData.get(OWNER_NAME);
    }

    public UUID owner() {
        return owner;
    }

    /** Every item it holds: the list and whatever it wears (for the gear invariant). */
    public List<ItemStack> allItems() {
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack st : items) if (!st.isEmpty()) all.add(st);
        for (EquipmentSlot s : EquipmentSlot.values()) {
            ItemStack st = getItemBySlot(s);
            if (!st.isEmpty()) all.add(st);
        }
        return all;
    }

    public int xp() {
        return xp;
    }

    public BlockPos home() {
        return home;
    }

    // ---- taking a statue

    /** Can a Puppeteer take this: a statue in stopped land (rare: only a Puppeteer already there notices it). */
    public static boolean canTake(Entity e) {
        return e instanceof Statue s && !s.isRemoved() && e.level() instanceof ServerLevel level && Stasis.stops(level)
            && !Stasis.isRunning(level, e.blockPosition());
    }

    /** The Puppeteer is in: the statue becomes a possessed one. The new entity is added first; only then the statue goes. */
    public static PossessedStatue take(ServerLevel level, Statue s) {
        PossessedStatue p = StasisRegistry.POSSESSED_STATUE.get().create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (p == null) return null;
        p.snapTo(s.getX(), s.getY(), s.getZ(), s.getYRot(), 0f);
        p.owner = s.owner();
        p.ownerName = s.ownerName();
        p.xp = s.xp();
        p.home = s.blockPosition();
        p.homeYaw = s.getYRot();
        for (ItemStack st : s.items()) p.items.add(st.copy());
        p.wearArmour();
        p.syncOwner();
        if (!level.addFreshEntity(p)) return null;
        s.items().clear();
        s.discard();
        StasisDelvers.LOG.info("A Puppeteer took {}'s statue at {} ({} stacks)", p.ownerName, p.home.toShortString(), p.allItems().size());
        return p;
    }

    private void syncOwner() {
        entityData.set(OWNER_ID, owner.toString());
        entityData.set(OWNER_NAME, ownerName);
        setCustomName(Component.literal(ownerName));
        setCustomNameVisible(true);
    }

    /** Armour from the list into the empty armour slots (looks and toughness); everything else stays in the list. */
    private void wearArmour() {
        for (var it = items.iterator(); it.hasNext(); ) {
            ItemStack st = it.next();
            var eq = st.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            if (eq == null || !eq.slot().isArmor() || !getItemBySlot(eq.slot()).isEmpty()) continue;
            setItemSlot(eq.slot(), st);
            it.remove();
        }
    }

    // ---- the one way out

    /**
     * A statue again, here (standing, or fallen: remains), with everything. The statue is added first; if that fails
     * (its chunk not loaded), nothing changes and the caller tries again later. Returns whether it reverted.
     */
    public boolean revert(boolean fallen, String why) {
        if (isRemoved() || !(level() instanceof ServerLevel level)) return false;
        List<ItemStack> all = new ArrayList<>(items.size() + 6);
        for (ItemStack st : items) if (!st.isEmpty()) all.add(st.copy());
        for (EquipmentSlot s : EquipmentSlot.values()) {
            ItemStack st = getItemBySlot(s);
            if (!st.isEmpty()) all.add(st.copy());
        }
        Statue st = Statue.restore(level, owner, ownerName, all, xp, getX(), getY(), getZ(), getYRot(), fallen);
        if (!level.addFreshEntity(st)) return false;
        items.clear();
        for (EquipmentSlot s : EquipmentSlot.values()) setItemSlot(s, ItemStack.EMPTY);
        xp = 0;
        release(level);
        StasisDelvers.LOG.info("{}'s possessed statue stands again at {} ({}; {} stacks)", ownerName, st.blockPosition().toShortString(), why, all.size());
        notice(level, owner, String.format(fallen ? "Your remains lie at %d %d %d (%s)." : "Your statue stands again at %d %d %d (%s).",
            st.getBlockX(), st.getBlockY(), st.getBlockZ(), level.dimension().identifier()));
        discard();
        return true;
    }

    /** Its tickets and registry entry go (after a revert). */
    private void release(ServerLevel level) {
        if (ticketChunk != Long.MIN_VALUE) force(level, ticketChunk, false);
        ticketChunk = Long.MIN_VALUE;
        Data.get(level).remove(getUUID());
        LIVE.remove(this);
    }

    /** Teleport home and stand again there (the home chunk forced for the add). */
    boolean goHome(ServerLevel level, String why) {
        int hx = home.getX() >> 4, hz = home.getZ() >> 4;
        TICKETS.forceChunk(level, getUUID(), hx, hz, true, true);
        level.getChunk(hx, hz);   // (loaded now, so the statue can be added)
        snapTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, homeYaw, 0f);
        boolean ok = revert(false, why);
        if (ok) TICKETS.forceChunk(level, getUUID(), hx, hz, false, true);
        return ok;
    }

    // ---- damage (λ-Nero: only from players, only in its owner's chunk)

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {   // /kill, the void
            revert(true, "killed");
            return false;
        }
        if (!(source.getEntity() instanceof Player)) return false;
        ServerPlayer o = level.getServer().getPlayerList().getPlayer(owner);
        if (o == null || o.level() != level || !o.chunkPosition().equals(chunkPosition())) return false;
        return super.hurtServer(level, source, damage);
    }

    @Override
    public void die(DamageSource source) {
        if (!revert(true, "killed")) setHealth(1f);   // (couldn't stand again here: alive until it can)
    }

    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
    }

    /** Never removed by dying: if the revert in die() couldn't happen, it keeps trying here (vanilla would discard it). */
    @Override
    protected void tickDeath() {
        if (level() instanceof ServerLevel && !isRemoved()) revert(true, "killed (late)");
    }

    @Override
    protected void onBelowWorld() {
        if (level() instanceof ServerLevel level && !goHome(level, "fell out of the world"))
            setPos(getX(), level.getMinY() + 1, getZ());
    }

    @Override
    public boolean removeWhenFarAway(double distSqr) {
        return false;
    }

    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected boolean canRide(Entity vehicle) {
        return false;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getAmbientSound() {
        return net.minecraft.sounds.SoundEvents.SCULK_CLICKING;
    }

    @Override
    public float getVoicePitch() {
        return 0.5f;
    }

    // ---- save

    @Override
    protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        out.store("owner", UUIDUtil.CODEC, owner);
        out.putString("owner_name", ownerName);
        out.store("items", ItemStack.CODEC.listOf(), List.copyOf(items));
        out.putInt("xp", xp);
        out.store("home", BlockPos.CODEC, home);
        out.putFloat("home_yaw", homeYaw);
        out.putBoolean("homeward", homeward);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        in.read("owner", UUIDUtil.CODEC).ifPresent(u -> owner = u);
        ownerName = in.getStringOr("owner_name", "");
        items.clear();
        in.read("items", ItemStack.CODEC.listOf()).ifPresent(items::addAll);
        xp = in.getIntOr("xp", 0);
        in.read("home", BlockPos.CODEC).ifPresent(h -> home = h);
        homeYaw = in.getFloatOr("home_yaw", 0f);
        homeward = in.getBooleanOr("homeward", false);
        syncOwner();
    }

    // ---- the registry, bursts and the hunt (server tick: its own tick is cancelled between bursts)

    /** The possessed statues loaded now. Server thread only. */
    static final Map<PossessedStatue, Boolean> LIVE = new WeakHashMap<>();

    /** GameTests: is it in the live registry (it registers when it joins a level)? */
    public static boolean registered(PossessedStatue p) {
        return LIVE.containsKey(p);
    }

    /** In a burst (so it ticks, not frozen): Stasis.isFrozen. */
    static boolean running(Entity e) {
        return e instanceof PossessedStatue p && e.level().getGameTime() < p.burstUntil;
    }

    /** Joined (spawned, or loaded from a save): register, force its chunks, show the eyes (advisor: else a reload strands it). */
    static void joined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (!(e.getEntity() instanceof PossessedStatue p) || !(e.getLevel() instanceof ServerLevel level)) return;
        LIVE.put(p, true);
        p.setData(StasisRegistry.POSSESSED.get(), true);
        p.lastStopped = p.blockPosition();
        p.retarget(level);
    }

    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (LIVE.isEmpty()) return;
        for (PossessedStatue p : List.copyOf(LIVE.keySet())) {
            if (p.isRemoved() || !(p.level() instanceof ServerLevel level)) {
                if (p.isRemoved() && p.getRemovalReason() != null && p.getRemovalReason().shouldDestroy() && !p.allItems().isEmpty())
                    StasisDelvers.LOG.error("A possessed statue of {} went with {} stacks still in it at {}: a bug (it should have reverted)",
                        p.ownerName, p.allItems().size(), p.blockPosition().toShortString());
                LIVE.remove(p);
                continue;
            }
            p.step(level);
        }
    }

    /** Its 3x3 chunks follow it. */
    private void retarget(ServerLevel level) {
        long now = ChunkPos.pack(chunkPosition().x(), chunkPosition().z());
        if (now == ticketChunk) return;
        if (ticketChunk != Long.MIN_VALUE) force(level, ticketChunk, false);
        force(level, now, true);
        ticketChunk = now;
        Data.get(level).put(getUUID(), now);
    }

    private void force(ServerLevel level, long chunk, boolean add) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) TICKETS.forceChunk(level, getUUID(), cx + dx, cz + dz, add, true);
    }

    private void step(ServerLevel level) {
        long now = level.getGameTime();
        retarget(level);
        if (now % 20 == 0) Data.get(level).seen(getUUID(), now);
        // the wall (as fauna; never a discard: a field grown over it means remains)
        if (RiftFauna.inStopped(level, blockPosition())) lastStopped = blockPosition();
        else {
            BlockPos back = lastStopped != null && RiftFauna.inStopped(level, lastStopped) ? lastStopped
                : level.getRandom().nextBoolean() ? RiftFauna.escape(level, blockPosition()) : null;
            if (back == null) {
                revert(true, "a field grew over it");
                return;
            }
            lastStopped = back;
            setPos(back.getX() + 0.5, back.getY(), back.getZ() + 0.5);
            getNavigation().stop();
            setDeltaMovement(Vec3.ZERO);
        }
        ServerPlayer o = level.getServer().getPlayerList().getPlayer(owner);
        if (o == null || o.level() != level || o.isSpectator()) {
            goHome(level, o == null ? "its owner left" : "its owner left the dimension");
            return;
        }
        if (homeward) {
            goHome(level, "its owner died");
            return;
        }
        double dist = Math.sqrt(distanceToSqr(o));
        // unreachable: no real progress (2 blocks closer than its best) in LOST_TICKS of chasing
        if (dist < bestDist - 2) {
            bestDist = dist;
            lostTicks = 0;
        } else if (++lostTicks >= LOST_TICKS) {
            goHome(level, "it lost its owner");
            return;
        }
        if (hitCooldown > 0) hitCooldown--;
        if (now >= burstUntil && now >= nextBurst) {
            var r = level.getRandom();
            float speed = 1 + Math.min(Dread.at(level, getBlockX(), getBlockY(), getBlockZ()), 1f);
            int run = 2 + r.nextInt(7);
            long hold = (long) ((5 + r.nextInt(36)) / speed);
            burstUntil = now + run;
            nextBurst = burstUntil + hold;
            getNavigation().moveTo(o, Math.min(Possession.burstPace(run, hold), 8.0));   // (on average a walking pace: Baughn)
            getLookControl().setLookAt(o, 60f, 60f);
        }
        if (now < burstUntil) {
            // stuck (5 s of burst time without a block of movement, owner further than 3): the cheat blink (λ-Nero)
            if (progressAt == null || position().distanceToSqr(progressAt) > 1) {
                progressAt = position();
                stuckTicks = 0;
            } else if (dist > 3 && ++stuckTicks >= STUCK_TICKS / 5) {   // (bursts are a fifth of the time or so)
                blinkTowards(level, o.position());
                stuckTicks = 0;
                progressAt = position();
            }
            if (hitCooldown == 0 && distanceToSqr(o) < Math.pow(1.4 + getBbWidth() / 2, 2)) {
                var v = o.getDeltaMovement();
                o.hurtServer(level, level.damageSources().mobAttack(this), HIT);
                o.setDeltaMovement(v);
                o.needsSync = true;
                hitCooldown = 30;
            }
        }
    }

    /** Up to 8 blocks towards {@code to}, onto a standable stopped spot with room for it. */
    boolean blinkTowards(ServerLevel level, Vec3 to) {
        Vec3 d = to.subtract(position());
        double len = Math.min(8, d.length() - 1.5);
        if (len < 1) return false;
        Vec3 dir = d.normalize();
        for (double l = len; l >= 2; l -= 1) {
            Vec3 t = position().add(dir.scale(l));
            for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, 4, 5, 6}) {
                BlockPos q = BlockPos.containing(t.x, Math.floor(t.y) + dy, t.z);
                if (!FaunaSpawner.standable(level, q) || !RiftFauna.inStopped(level, q)) continue;
                var box = getDimensions(getPose()).makeBoundingBox(Vec3.atBottomCenterOf(q));
                if (level.getBlockCollisions(null, box).iterator().hasNext()) continue;
                setPos(q.getX() + 0.5, q.getY(), q.getZ() + 0.5);
                getNavigation().stop();
                return true;
            }
        }
        return false;
    }

    /** The owner died: 50 % it hunts them at their respawn, else it goes home (Baughn). */
    static void ownerDied(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer pl)) return;
        for (PossessedStatue p : List.copyOf(LIVE.keySet()))
            if (p.owner.equals(pl.getUUID()) && !p.isRemoved()) {
                boolean hunt = testHunt != null ? testHunt : pl.getRandom().nextBoolean();
                p.homeward = !hunt;
            }
    }

    /** GameTests: force the owner-death roll (null: random). */
    public static volatile Boolean testHunt = null;

    // ---- notices for owners who weren't online

    static void notice(ServerLevel level, UUID who, String text) {
        ServerPlayer o = level.getServer().getPlayerList().getPlayer(who);
        if (o != null) o.sendSystemMessage(Component.literal(text));
        else Data.get(level.getServer().overworld()).notices.put(who, text);
    }

    static void login(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Data d = Data.get(p.level().getServer().overworld());
        String n = d.notices.remove(p.getUUID());
        if (n != null) {
            d.setDirty();
            p.sendSystemMessage(Component.literal(n));
        }
    }

    // ---- chunk tickets and the saved registry

    static final TicketController TICKETS = new TicketController(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "possessed_statue"),
        PossessedStatue::validate);

    static void registerTickets(net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent e) {
        e.register(TICKETS);
    }

    /** Level load: keep the tickets of possessed statues the registry knows; any other is stale. */
    private static void validate(ServerLevel level, TicketHelper helper) {
        Data d = Data.get(level);
        for (UUID id : List.copyOf(helper.getEntityTickets().keySet()))
            if (!d.where.containsKey(id)) {
                helper.removeAllTickets(id);
                StasisDelvers.LOG.warn("Dropped stale possessed-statue tickets of {}", id);
            }
        for (BlockPos pos : List.copyOf(helper.getBlockTickets().keySet())) helper.removeAllTickets(pos);
    }

    /**
     * The backstop (advisor): a registry entry whose chunk is loaded but whose entity hasn't been seen for 5 minutes has
     * lost its entity; drop its tickets so 9 chunks aren't forced for ever, and say so loudly.
     */
    static void sweep(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        if (e.getServer().getTickCount() % 1200 != 0) return;
        for (ServerLevel level : e.getServer().getAllLevels()) {
            Data d = Data.get(level);
            long now = level.getGameTime();
            for (var en : List.copyOf(d.where.entrySet())) {
                long chunk = en.getValue();
                if (!level.hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) continue;
                if (now - d.seen.getOrDefault(en.getKey(), now) < 6000) continue;
                if (level.getEntity(en.getKey()) != null) continue;
                StasisDelvers.LOG.error("A possessed statue {} is in the registry at chunk {} but gone: dropping its tickets", en.getKey(),
                    ChunkPos.unpack(chunk));
                int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) TICKETS.forceChunk(level, en.getKey(), cx + dx, cz + dz, false, true);
                d.remove(en.getKey());
            }
        }
    }

    /** Per level: possessed statue → its chunk (and when last seen); in the overworld also notices for absent owners. */
    public static final class Data extends SavedData {
        record Entry(UUID id, long chunk) {
            static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Entry::id), Codec.LONG.fieldOf("chunk").forGetter(Entry::chunk)).apply(i, Entry::new));
        }

        record Notice(UUID id, String text) {
            static final Codec<Notice> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Notice::id), Codec.STRING.fieldOf("text").forGetter(Notice::text)).apply(i, Notice::new));
        }

        static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
            Entry.CODEC.listOf().optionalFieldOf("statues", List.of()).forGetter(d -> d.where.entrySet().stream().map(x -> new Entry(x.getKey(), x.getValue())).toList()),
            Notice.CODEC.listOf().optionalFieldOf("notices", List.of()).forGetter(d -> d.notices.entrySet().stream().map(x -> new Notice(x.getKey(), x.getValue())).toList())
        ).apply(i, Data::new));
        static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "possessed_statues"), Data::new, CODEC);

        final Map<UUID, Long> where = new HashMap<>();
        final Map<UUID, String> notices = new HashMap<>();
        /** Memory only: game time each was last ticked. */
        final Map<UUID, Long> seen = new HashMap<>();

        public Data() {
        }

        Data(List<Entry> statues, List<Notice> notices) {
            for (Entry e : statues) where.put(e.id(), e.chunk());
            for (Notice n : notices) this.notices.put(n.id(), n.text());
        }

        public static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(TYPE);
        }

        void put(UUID id, long chunk) {
            Long old = where.put(id, chunk);
            if (old == null || old != chunk) setDirty();
        }

        void seen(UUID id, long now) {
            seen.put(id, now);
        }

        void remove(UUID id) {
            if (where.remove(id) != null) setDirty();
            seen.remove(id);
        }

        /** GameTests. */
        public boolean has(UUID id) {
            return where.containsKey(id);
        }

        public String noticeFor(UUID id) {
            return notices.get(id);
        }
    }
}
