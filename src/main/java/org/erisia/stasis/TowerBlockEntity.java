package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

/**
 * A stasis tower. Its height (segments stacked on the base) sets the field radius r = 4 (segments + 2); its draw
 * is P = k r^1.5 W and its store holds c r^2 J (Baughn: bigger bases get more time to fix: store/draw grows as
 * sqrt(r); r = 24 runs 10 minutes on a full store). States:
 * <ul>
 * <li>OFF: no field. Starts once the store is at least half full.</li>
 * <li>RUNNING: the field is up and its chunks stay loaded; the draw comes out of the store. While less comes in than
 * goes out, the shield shows it (failing = how far the store has run down).</li>
 * <li>COLLAPSING: the store ran out; the field stops in patches, outer blocks first, over 15 s, then OFF.</li>
 * </ul>
 * The base block entity ticks even in stopped land (LevelChunkMixin), or it could never start.
 */
public class TowerBlockEntity extends BlockEntity {
    public enum State { OFF, RUNNING, COLLAPSING }

    /** FE per J (Electrical Age's exporter ratio, 4/3). */
    static final double FE_PER_J = 4.0 / 3.0;
    static final int COLLAPSE_TICKS = 15 * 20;

    State state = State.OFF;
    int fieldId;
    int segments;
    int collapseTicks;
    double lastIn;    // J received last tick
    double failing;

    /** The store, in FE (insert-only from outside). */
    final Store store = new Store();

    final class Store extends SimpleEnergyHandler {
        Store() {
            super(1, Integer.MAX_VALUE, 0);
        }

        void setCapacity(int c) {
            capacity = Math.max(1, c);
            if (energy > capacity) energy = capacity;
        }

        int amount() {
            return energy;
        }

        void take(int fe) {
            energy = Math.max(0, energy - fe);
        }

        /** Size the store before taking energy (the first insert can come before onLoad or the first tick). */
        @Override
        public int insert(int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            if (!sized && level != null && !level.isClientSide()) {
                sized = true;
                segments = countSegments();
                setCapacity((int) (storeFor(radius()) * FE_PER_J));
            }
            return super.insert(amount, transaction);
        }

        @Override
        protected void onEnergyChanged(int previousAmount) {
            if (energy > previousAmount) received += energy - previousAmount;
            setChanged();
        }
    }

    private int received;   // FE inserted this tick
    private boolean sized;

    /** The generated tower over this core (P3d). */
    public final TowerStructure structure = new TowerStructure();

    public TowerBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.TOWER_ENTITY.get(), pos, state);
        structure.seed = (int) (pos.asLong() ^ (pos.asLong() >>> 32));
    }

    /** Sends the tower's layout and status to clients (they build and draw the mesh). */
    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    /** Count the segments and size the store as soon as the tower exists (before its first tick). */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) TowerClientHooks.updated(this);
        if (level != null && !level.isClientSide()) {
            segments = countSegments();
            store.setCapacity((int) (storeFor(radius()) * FE_PER_J));
        }
    }

    public State state() {
        return state;
    }

    public int segments() {
        return segments;
    }

    public double radius() {
        return radiusFor(segments);
    }

    public static double radiusFor(int segments) {
        return 4.0 * (segments + 2);
    }

    /** Draw in W. */
    public static double drawFor(double r) {
        return StasisConfig.towerDrawK * Math.pow(r, 1.5);
    }

    /** Tests: multiply this tower's draw (to empty it quickly). */
    public double testDrawFactor = 1;

    /** Store in J. */
    public static double storeFor(double r) {
        return StasisConfig.towerStoreC * r * r;
    }

    public double storedJ() {
        return store.amount() / FE_PER_J;
    }

    public double storeMaxJ() {
        return storeFor(radius());
    }

    int countSegments() {
        int n = 0;
        BlockPos p = TowerStructure.pole(worldPosition, 1);
        while (n < StasisConfig.towerMaxSegments && TowerPoleBlock.isMast(level.getBlockState(p))) {
            n++;
            p = p.above();
        }
        return n;
    }

    public int ticks;

    void serverTick() {
        if (!(level instanceof ServerLevel sl)) return;
        ticks++;
        if (structure.tick(sl, worldPosition, ticks)) {
            setChanged();
            sl.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
        if (level.getGameTime() % 20 == 0 || segments == 0) {
            int s = countSegments();
            if (s != segments) {
                segments = s;
                if (state != State.OFF) FieldData.get(sl).update(sl, fieldId, f -> f.withRadius(radius()));
                Tickets.update(sl, this);
            }
        }
        double r = radius();
        store.setCapacity((int) (storeFor(r) * FE_PER_J));
        double in = received / FE_PER_J;
        received = 0;
        double perTick = drawFor(r) * testDrawFactor / 20.0;
        switch (state) {
            case OFF -> {
                if (storedJ() >= 0.5 * storeFor(r)) start(sl);
            }
            case RUNNING -> {
                if (FieldData.get(sl).get(fieldId).isEmpty()) {     // removed by a command: start over
                    stop(sl);
                    break;
                }
                store.take((int) Math.ceil(perTick * FE_PER_J));
                lastIn = 0.9 * lastIn + 0.1 * in;
                // the shield shows a shortfall: how far the store has run down, while less comes in than goes out
                double target = lastIn < perTick * 0.999 ? 1 - storedJ() / storeFor(r) : 0;
                failing += Math.signum(target - failing) * Math.min(Math.abs(target - failing), 0.01);
                if (level.getGameTime() % 5 == 0) FieldData.get(sl).update(sl, fieldId, f -> f.withFailing(failing));
                if (store.amount() <= 0) {
                    state = State.COLLAPSING;
                    collapseTicks = 0;
                    StasisDelvers.LOG.info("Stasis tower at {} ran out of power: field {} collapses", worldPosition.toShortString(), fieldId);
                }
            }
            case COLLAPSING -> {
                collapseTicks++;
                double c = Math.min(1.0, collapseTicks / (double) COLLAPSE_TICKS);
                if (collapseTicks % 5 == 0 || c >= 1) FieldData.get(sl).update(sl, fieldId, f -> f.withCollapse(c).withFailing(1));
                if (c >= 1) stop(sl);
            }
        }
        setChanged();
    }

    void start(ServerLevel sl) {
        Field f = FieldData.get(sl).add(sl, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, radius(), false,
            StasisConfig.towerFieldRate);
        fieldId = f.id();
        state = State.RUNNING;
        failing = 0;
        lastIn = drawFor(radius()) / 20.0;
        Tickets.update(sl, this);
        StasisDelvers.LOG.info("Stasis tower at {} started: field {} r {}", worldPosition.toShortString(), fieldId, radius());
    }

    void stop(ServerLevel sl) {
        if (fieldId != 0) FieldData.get(sl).remove(sl, fieldId);
        fieldId = 0;
        state = State.OFF;
        collapseTicks = 0;
        failing = 0;
        Tickets.update(sl, this);
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel sl && state != State.OFF && !removedByUnload) stop(sl);
        if (level != null && !level.isClientSide() && !removedByUnload) structure.release(level);
        if (level != null && level.isClientSide()) TowerClientHooks.removed(this);
        super.setRemoved();
    }

    private boolean removedByUnload;

    @Override
    public void onChunkUnloaded() {
        removedByUnload = true;
        super.onChunkUnloaded();
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putString("state", state.name());
        out.putInt("field", fieldId);
        out.putInt("segments", segments);
        out.putInt("collapse", collapseTicks);
        out.putInt("energy", store.amount());
        out.putDouble("failing", failing);
        structure.save(out);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        try {
            state = State.valueOf(in.getStringOr("state", "OFF"));
        } catch (IllegalArgumentException e) {
            state = State.OFF;
        }
        fieldId = in.getIntOr("field", 0);
        segments = in.getIntOr("segments", 0);
        collapseTicks = in.getIntOr("collapse", 0);
        store.setCapacity(Integer.MAX_VALUE);
        store.set(in.getIntOr("energy", 0));
        failing = in.getDoubleOr("failing", 0);
        structure.load(in);
        if (level != null && level.isClientSide()) TowerClientHooks.updated(this);
    }

    /** For tests and the tower's own chunk loading. */
    public SimpleEnergyHandler energy() {
        return store;
    }
}
