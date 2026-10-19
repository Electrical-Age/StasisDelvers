package org.erisia.stasis;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rift lance's core (L2; LANCE-v2 with Argon's review and Baughn's decisions; docs/lance.md). Each tick (it ticks in
 * stopped land, like a tower's base: Stasis.exemptBlockEntity):
 * <ul>
 * <li>Every second it surveys its multiblock ({@link #scan}: a mast of survey poles on it, a boom off the mast's top) and
 * binds to the rift whose eye the boom's tip covers (Lance.riftUnder, Lance.bind: one lance per rift).</li>
 * <li>With a focus that fits the gate's destination (tier 1 the Nether, tier 2 the End) it draws from its FE store (fed by
 * EA's exporter, as a tower is): the breach draw (x2 in a storm over the rift) until the gate latches, then the hold
 * draw. Once the store can't meet a tick's draw it is browned out until full again, so a short supply can't hold a gate.</li>
 * <li>Lance.drive steps the gate: down to a quarter of the rift, the latch, the hold; without power the grace (a klaxon
 * here), then the relax.</li>
 * <li>While active it force-loads its own chunk and the rift's core chunk (LanceTickets), and in spreading mode holds a
 * small field round itself (r {@link #HELD_RADIUS}), so a drifting deep passing over running land doesn't stop the EA
 * nodes beside it (Argon). Its power plant still needs a tower's field, or luck.</li>
 * </ul>
 * Clients get the boom's tip, the eye and whether the beam is on (StasisPost draws the beam and turns the eye to the tip).
 */
public class LanceBlockEntity extends BlockEntity {
    static final double FE_PER_J = TowerBlockEntity.FE_PER_J;
    /** The structure: at least MIN_MAST mast poles on the core, at most MAX_MAST; a boom of 1..MAX_BOOM poles. */
    public static final int MIN_MAST = 3, MAX_MAST = 64, MAX_BOOM = 64;
    /** Spreading mode: the field an active lance holds round its core (Argon: r ~4). */
    public static final double HELD_RADIUS = 4;

    ItemStack focus = ItemStack.EMPTY;
    final Store store = new Store();
    /** The store ran dry: no power until it is full again (no flicker, and a short supply can't hold a gate). */
    boolean brownout;
    /** The rift bound (deep id; 0 none) and its radius (the store's size). */
    int boundId;
    float boundR = Lance.R_SMALL;
    /** The boom's tip (null: no valid structure), and why not (or why unbound). */
    BlockPos tip;
    String problem = "";
    /** Spreading mode: the small field held round the core (0 none). */
    int fieldId;
    /** Synced: the bound rift's eye, and the beam (the lance pours power into the rift). */
    float eyeX, eyeY, eyeZ;
    boolean beam;
    /** Memory: ticks, draw now (W), power in (W, averaged), the chunks forced. */
    long ticks;
    double drawW, inAvgW;
    private int received;
    /** (null until the first tick: then update once, so tickets forced before a restart are released if inactive) */
    private LongOpenHashSet forced;

    /** Tests: watts fed straight into the store each tick (NaN: none); the breach's speed; a storm forced (null: the sky's). */
    public double testSupplyW = Double.NaN, testSpeed = 1;
    public Boolean testStorm;

    /** Client: the lances loaded, by position (StasisPost: the beam, the eye's fixation). */
    public static final Map<BlockPos, LanceBlockEntity> CLIENT = new ConcurrentHashMap<>();

    final class Store extends SimpleEnergyHandler {
        Store() {
            super(capacityFor(Lance.R_SMALL), Integer.MAX_VALUE, 0);
        }

        void setCapacity(int c) {
            capacity = Math.max(1, c);
            if (energy > capacity) energy = capacity;
        }

        int amount() {
            return energy;
        }

        int capacity() {
            return capacity;
        }

        void take(int fe) {
            energy = Math.max(0, energy - fe);
        }

        @Override
        protected void onEnergyChanged(int previousAmount) {
            if (energy > previousAmount) received += energy - previousAmount;
            setChanged();
        }
    }

    /** The store's capacity (FE) for a rift of radius r: lanceStoreSeconds of its breach draw. */
    static int capacityFor(float r) {
        return (int) Math.min(Integer.MAX_VALUE, StasisConfig.lanceStoreSeconds * Lance.breachW(r) * FE_PER_J);
    }

    public LanceBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.LANCE_ENTITY.get(), pos, state);
    }

    public ItemStack focus() {
        return focus;
    }

    public void setFocus(ItemStack f) {
        focus = f;
        setChanged();
    }

    public int boundId() {
        return boundId;
    }

    public BlockPos tip() {
        return tip;
    }

    public boolean beam() {
        return beam;
    }

    public float[] eye() {
        return new float[]{eyeX, eyeY, eyeZ};
    }

    /** The draw this tick (W; 0 unpowered), and why it isn't bound ("" if it is). */
    public double drawW() {
        return drawW;
    }

    public String problem() {
        return problem;
    }

    public boolean brownout() {
        return brownout;
    }

    /** Tests: one tick now (the survey too). */
    public void testTick() {
        if (level instanceof ServerLevel sl) survey(sl);
        serverTick();
    }

    public double storedJ() {
        return store.amount() / FE_PER_J;
    }

    public double storeMaxJ() {
        return store.capacity() / FE_PER_J;
    }

    public SimpleEnergyHandler energy() {
        return store;
    }

    // ---- the structure

    /** A survey of the multiblock: the boom's tip (null if invalid, with the problem), mast and boom lengths. */
    public record Scan(BlockPos tip, int mast, int boom, Direction dir, String problem) {
    }

    /**
     * The lance's multiblock (kept simple; docs/lance.md): on the core, a mast of at least MIN_MAST vertical survey poles;
     * off the mast's top pole, one boom of horizontal poles in a straight line. Its last pole is the tip.
     */
    public static Scan scan(Level level, BlockPos core) {
        int mast = 0;
        while (mast < MAX_MAST && TowerPoleBlock.isMast(level.getBlockState(core.above(mast + 1)))) mast++;
        if (mast < MIN_MAST) return new Scan(null, mast, 0, null, "Raise a mast of at least " + MIN_MAST + " survey poles on the lance core.");
        BlockPos top = core.above(mast);
        Direction dir = null;
        int boom = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int n = 0;
            while (n < MAX_BOOM && boomPole(level.getBlockState(top.relative(d, n + 1)), d)) n++;
            if (n == 0) continue;
            if (dir != null) return new Scan(null, mast, 0, null, "A lance has one boom, off the mast's top pole.");
            dir = d;
            boom = n;
        }
        if (dir == null) return new Scan(null, mast, 0, null, "Run a boom of survey poles off the mast's top pole, out over the rift's eye.");
        return new Scan(top.relative(dir, boom), mast, boom, dir, null);
    }

    static boolean boomPole(BlockState s, Direction d) {
        return s.is(StasisRegistry.TOWER_SEGMENT.get()) && s.getValue(TowerPoleBlock.AXIS) == d.getAxis();
    }

    /** Survey the multiblock and (re)bind to the rift under the boom's tip. */
    void survey(ServerLevel sl) {
        Scan s = scan(sl, worldPosition);
        BlockPos oldTip = tip;
        tip = s.tip();
        String why = s.problem();
        StaticDeeps.Deep d = null;
        if (tip != null) {
            d = Lance.riftUnder(sl, tip);
            if (d == null) why = "The boom's tip is over no rift's eye: it must reach within 1.5 radii of it (horizontally), above it.";
            else {
                String refused = Lance.bind(sl, d, worldPosition);
                if (refused != null) {
                    why = refused;
                    d = null;
                }
            }
        }
        int id = d == null ? 0 : d.id();
        if (boundId != 0 && boundId != id) Lance.unbind(sl, boundId, worldPosition);
        boolean sync = !java.util.Objects.equals(oldTip, tip);
        if (id != boundId) {
            boundId = id;
            if (d != null) {
                boundR = d.radius();
                float[] c = StaticDeeps.core(d);
                eyeX = c[0];
                eyeY = c[1];
                eyeZ = c[2];
                store.setCapacity(capacityFor(boundR));
            }
            sync = true;
        }
        problem = why == null ? "" : why;
        if (sync) sync(sl);
    }

    // ---- the tick

    void serverTick() {
        if (!(level instanceof ServerLevel sl)) return;
        ticks++;
        if (!Double.isNaN(testSupplyW)) store.set((int) Math.min(store.capacity(), store.amount() + Math.round(testSupplyW / 20 * FE_PER_J)));
        inAvgW += (received / FE_PER_J * 20 - inAvgW) * 0.05;
        received = 0;
        if (ticks % 20 == 1) survey(sl);
        Lance.Gate g = boundId == 0 ? null : Lance.GateData.get(sl).gate(boundId);
        boolean want = g != null && fits(sl, g);
        boolean storm = g != null && !g.open() && stormOver(sl, g);
        double w = !want ? 0 : g.open() ? Lance.holdW(g.r()) : Lance.breachW(g.r()) * (storm ? StasisConfig.lanceStormDraw : 1);
        boolean powered = false;
        if (want) {
            if (brownout && store.amount() >= 0.99 * store.capacity()) brownout = false;
            double need = w / 20;
            if (!brownout && storedJ() >= need) {
                store.take((int) Math.ceil(need * FE_PER_J));
                powered = true;
            } else brownout = true;
        }
        drawW = powered ? w : 0;
        Lance.Gate was = g;
        if (g != null) g = Lance.drive(sl, g.id(), want, powered, storm, testSpeed);
        if (g != null) {
            int graceTicks = Lance.drive(g.r(), false, false, false, testSpeed).graceTicks();
            if (Lance.klaxon(g, graceTicks) && ticks % 40 == 0) klaxon(sl, g);
            else if (was != null && Lance.closingCue(was, g, graceTicks)) closing(sl);
        }
        boolean active = g != null && (powered || g.open() || g.step() > 0 || g.progress() > 0);
        tickets(sl, active ? g : null);
        heldField(sl, active);
        boolean b = want && powered;
        if (b != beam) {
            beam = b;
            sync(sl);
        }
    }

    /** Does the focus fit the gate's destination (tier 1 the Nether, tier 2 the End)? */
    boolean fits(ServerLevel sl, Lance.Gate g) {
        return focus.getItem() instanceof RiftFocusItem f && f.tier() >= RiftFocusItem.needs(Lance.kind(sl.getServer(), g.deep()));
    }

    /** A storm over the rift: at the boom's tip, or on the ground over the rift's eye (Storms.at: the surface band). */
    boolean stormOver(ServerLevel sl, Lance.Gate g) {
        if (testStorm != null) return testStorm;
        if (tip != null && Storms.at(sl, tip.getX(), tip.getY(), tip.getZ()) > 0) return true;
        float[] c = StaticDeeps.core(g.deep());
        int x = (int) Math.floor(c[0]), z = (int) Math.floor(c[2]);
        if (!sl.hasChunkAt(new BlockPos(x, 0, z))) return false;
        int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return Storms.at(sl, x, y, z) > 0;
    }

    /** The klaxon (every 2 s of the grace): the gate is about to slip. */
    void klaxon(ServerLevel sl, Lance.Gate g) {
        sl.playSound(null, worldPosition, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), SoundSource.BLOCKS, 4f, 0.5f);
        int left = (int) Math.ceil((Lance.drive(g.r(), false, false, false, testSpeed).graceTicks() - g.grace()) / 20.0);
        Component m = Component.literal("The lance has lost power: the gate slips in " + left + " s").withStyle(ChatFormatting.RED);
        for (ServerPlayer p : sl.players())
            if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(worldPosition)) < 48 * 48) p.sendOverlayMessage(m);
    }

    /** The relax's cue (AdventurAgent: not the klaxon for minutes): a low groan and a word, as it starts and per step back. */
    void closing(ServerLevel sl) {
        sl.playSound(null, worldPosition, SoundEvents.CONDUIT_DEACTIVATE, SoundSource.BLOCKS, 3f, 0.5f);
        Component m = Component.literal("The gate is closing").withStyle(ChatFormatting.GOLD);
        for (ServerPlayer p : sl.players())
            if (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(worldPosition)) < 48 * 48) p.sendOverlayMessage(m);
    }

    /** Force the core's chunk and the rift's core chunk while active; release them otherwise. */
    void tickets(ServerLevel sl, Lance.Gate g) {
        LongOpenHashSet now = new LongOpenHashSet();
        if (g != null) {
            now.add(ChunkPos.pack(worldPosition.getX() >> 4, worldPosition.getZ() >> 4));
            float[] c = StaticDeeps.core(g.deep());
            now.add(ChunkPos.pack((int) Math.floor(c[0]) >> 4, (int) Math.floor(c[2]) >> 4));
        }
        if (now.equals(forced)) return;
        forced = now;
        LanceTickets.update(sl, worldPosition, now);
    }

    /** Spreading mode, while active: the small field round the core (Argon), so drifting deeps don't stop its EA nodes. */
    void heldField(ServerLevel sl, boolean active) {
        boolean want = active && StasisConfig.spreading() && Spread.active(sl);
        FieldData fd = FieldData.get(sl);
        if (want && (fieldId == 0 || fd.get(fieldId).isEmpty())) {
            fieldId = fd.add(sl, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, HELD_RADIUS, false,
                StasisConfig.towerFieldRate).id();
            setChanged();
        } else if (!want && fieldId != 0) {
            fd.remove(sl, fieldId);
            fieldId = 0;
            setChanged();
        }
    }

    void sync(ServerLevel sl) {
        setChanged();
        sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    public Component statusText() {
        if (!(level instanceof ServerLevel sl)) return Component.empty();
        StringBuilder b = new StringBuilder("Rift lance: ");
        Lance.Gate g = boundId == 0 ? null : Lance.GateData.get(sl).gate(boundId);
        if (g == null) b.append("not bound. ").append(problem);
        else {
            Lance.Kind k = Lance.kind(sl.getServer(), g.deep());
            int need = RiftFocusItem.needs(k);
            b.append(String.format(java.util.Locale.ROOT, "rift %d (r %.0f), to %s; ", g.id(), g.r(), k == Lance.Kind.NETHER ? "the Nether" : "the End"));
            b.append(focus.isEmpty() ? "no focus (needs " + (need == 1 ? "a rift focus" : "a charged rift focus") + ")"
                : fits(sl, g) ? "focus fits" : "the focus is too weak (needs a charged rift focus)");
            b.append(String.format(java.util.Locale.ROOT, ". Gate: step %d/%d (%d %% of the rift)%s%s.", g.step(), Lance.STEPS,
                Math.round(100 * g.strength()), g.open() ? ", OPEN" : "", g.grace() > 0 ? ", no power" : ""));
        }
        b.append(String.format(java.util.Locale.ROOT, " Store %.0f / %.0f kJ%s, draw %.1f kW, in %.1f kW.", storedJ() / 1000, storeMaxJ() / 1000,
            brownout ? " (refilling)" : "", drawW / 1000, inAvgW / 1000));
        return Component.literal(b.toString());
    }

    // ---- life cycle

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) CLIENT.put(worldPosition.immutable(), this);
    }

    private boolean removedByUnload;

    @Override
    public void onChunkUnloaded() {
        removedByUnload = true;
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) CLIENT.remove(worldPosition, this);
        if (level instanceof ServerLevel sl && !removedByUnload) {
            if (boundId != 0) Lance.unbind(sl, boundId, worldPosition);
            boundId = 0;
            heldField(sl, false);
            tickets(sl, null);
        }
        super.setRemoved();
    }

    /** Broken: the focus drops. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null && !focus.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, focus);
        focus = ItemStack.EMPTY;
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
        if (!focus.isEmpty()) out.store("focus", ItemStack.CODEC, focus);
        out.putInt("cap", store.capacity());
        out.putInt("energy", store.amount());
        out.putBoolean("brownout", brownout);
        out.putInt("bound", boundId);
        out.putFloat("boundR", boundR);
        if (tip != null) out.putLong("tip", tip.asLong());
        out.putInt("field", fieldId);
        out.putFloat("eyeX", eyeX);
        out.putFloat("eyeY", eyeY);
        out.putFloat("eyeZ", eyeZ);
        out.putBoolean("beam", beam);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        focus = in.read("focus", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        store.setCapacity(in.getIntOr("cap", capacityFor(Lance.R_SMALL)));
        store.set(in.getIntOr("energy", 0));
        brownout = in.getBooleanOr("brownout", false);
        boundId = in.getIntOr("bound", 0);
        boundR = in.getFloatOr("boundR", Lance.R_SMALL);
        long t = in.getLongOr("tip", Long.MIN_VALUE);
        tip = t == Long.MIN_VALUE ? null : BlockPos.of(t);
        fieldId = in.getIntOr("field", 0);
        eyeX = in.getFloatOr("eyeX", 0);
        eyeY = in.getFloatOr("eyeY", 0);
        eyeZ = in.getFloatOr("eyeZ", 0);
        beam = in.getBooleanOr("beam", false);
    }
}
